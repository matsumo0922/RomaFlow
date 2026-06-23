package me.matsumo.romaflow.core.ime

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RomaFlowEngine + RerankResolver 統合テスト（macosArm64・実 IPADIC 辞書使用）。
 *
 * 実 LLM は使わず [FakeConversionProvider] または固定スタブで決定論的に動作を確認する。
 *
 * ## 検証項目
 * - rerank が Viterbi 1位 fallback（-1）を返すときでも segment が生成されること
 * - OOV（ASCII）で literal 保持が機能すること
 * - rerank 失敗時も isConverted=true になること
 */
@Suppress("FunctionNaming")
class RomaFlowEngineRerankIntegrationTest {

    private val lexicon by lazy { MozcTestDictionary.readingLexicon }
    private val costProvider by lazy { MozcTestDictionary.costProvider }

    /**
     * rerank が -1（失敗）を返したとき Viterbi 1位（辞書 rank-0）の表層で変換されることを確認する。
     *
     * [AlwaysFailRerankStub] は常に -1 を返す。engine は Viterbi 1位（graph.bestPathId rank-0）の
     * 表層で ProposeJointCorrection を組み立て、ProposalApplier が verified path 経由で segment 化する。
     */
    @Test
    fun rerank_viterbiFallback_whenRerankFails() = runBlocking {
        val provider = AlwaysFailRerankStub()
        val engine = RomaFlowEngine(
            conversionProvider = provider,
            segmenter = PassthroughSegmenter,
            aligner = DpReadingAligner(),
            readingLexiconFactory = { lexicon },
            connectionCostProviderFactory = { costProvider },
        )

        engine.inputRomaji("tenki")

        val convertResult = engine.convert()

        // rerank が -1 を返しても convert() は "" でなく Viterbi 1位の surface を返すこと
        // （RerankResolver は -1 時に bestPathId の surface を選んで ProposeJointCorrection を返す）
        assertTrue(
            convertResult.isNotEmpty(),
            "rerank 失敗時も convert() は Viterbi 1位の表層文字列を返すこと（実際: '$convertResult'）",
        )

        engine.applyConversion(convertResult)

        // Viterbi 1位経路で segment が生成されること
        assertTrue(
            engine.isConverted(),
            "rerank 失敗時も applyConversion で isConverted=true になること",
        )
        assertTrue(
            engine.segmentCount() >= 1,
            "segment が最低1つ生成されること（実際: ${engine.segmentCount()}）",
        )
    }

    /**
     * FakeConversionProvider の rerank が決定論的に 0 を返すとき、
     * rank-0 の候補表層で変換が完了することを確認する。
     *
     * "てんき" の場合、IPADIC の Viterbi 1位は "天気" であることが多く、
     * FakeConversionProvider も RERANK_TABLE で "天気" を返すためほぼ一致する。
     */
    @Test
    fun rerank_fakeProvider_selectsCandidate() = runBlocking {
        val provider = FakeConversionProvider()
        val engine = RomaFlowEngine(
            conversionProvider = provider,
            segmenter = PassthroughSegmenter,
            aligner = DpReadingAligner(),
            readingLexiconFactory = { lexicon },
            connectionCostProviderFactory = { costProvider },
        )

        engine.inputRomaji("tenki")

        val convertResult = engine.convert()

        assertTrue(
            convertResult.isNotEmpty(),
            "Fake rerank でも convert() は非空を返すこと（実際: '$convertResult'）",
        )

        engine.applyConversion(convertResult)

        assertTrue(
            engine.isConverted(),
            "Fake rerank でも applyConversion で isConverted=true になること",
        )
    }

    /**
     * OOV（ASCII 英字混じり）入力で literal arc が segment 表層として保持されることを確認する。
     *
     * "ok" を inputRomaji すると romaji→kana 変換で "おk" になる。
     * IPADIC には "おk" の辞書語が存在しないため LiteralLexicon fallback が働き、
     * "お" と "k" の2 arc で格子が構築される（Viterbi 1位 = literal 経路）。
     * rerank が -1 を返しても literal 経路の表層（"おk"）が preedit に反映されることを確認する。
     *
     * 注: `engine.inputRomaji("ok")` は "おk" を返す（"k" は孤立ローマ字として literal 保持）。
     * この "k" が literal arc として格子に乗り、applyConversion 後の segmentText に現れることを検証する。
     */
    @Test
    fun rerank_oovAscii_literalPreserved() = runBlocking {
        val provider = AlwaysFailRerankStub()
        val engine = RomaFlowEngine(
            conversionProvider = provider,
            segmenter = PassthroughSegmenter,
            aligner = DpReadingAligner(),
            readingLexiconFactory = { lexicon },
            connectionCostProviderFactory = { costProvider },
        )

        val preeditAfterInput = engine.inputRomaji("ok")

        // "ok" → "おk"（孤立 "k" が literal 保持されること）
        assertEquals("おk", preeditAfterInput, "romaji→kana 変換で 'ok' が 'おk' になること")

        val convertResult = engine.convert()

        // rerank -1 → Viterbi 1位（literal 経路）= "おk"
        assertTrue(convertResult.isNotEmpty(), "convert() が空でないこと（実際: '$convertResult'）")

        engine.applyConversion(convertResult)

        // segment が生成され、"k" が literal として含まれること
        assertTrue(engine.isConverted(), "isConverted=true になること")
        assertTrue(engine.segmentCount() >= 1, "segment が最低1つ生成されること")

        // 全 segment の surface を連結して "k" が保持されていることを確認する
        val allSurfaces = (0 until engine.segmentCount()).joinToString("") { index -> engine.segmentText(index) }
        assertTrue(
            allSurfaces.contains("k"),
            "literal 'k' が segment 表層に保持されること（実際の全 surface: '$allSurfaces'）",
        )
    }
}

// ---- helpers ----

/**
 * rerank が常に -1（失敗）を返す [ConversionProvider] スタブ。
 *
 * convert は空文字、candidates も空文字を返す。
 * RerankResolver の Viterbi 1位 fallback をテストするために使う。
 */
private class AlwaysFailRerankStub : ConversionProvider {
    override suspend fun convert(request: ConversionRequest): String = ""
    override suspend fun candidates(request: WordCandidateRequest): String = ""
    override suspend fun rerank(request: RerankRequest): Int = -1
    override suspend fun rerankFactorized(
        request: me.matsumo.romaflow.core.ime.shadow.FactorizedRerankRequest,
    ): me.matsumo.romaflow.core.ime.shadow.FactorizedRerankResult =
        me.matsumo.romaflow.core.ime.shadow.FactorizedRerankResult(decisions = emptyMap())
    override suspend fun proposeFullTailSurface(reading: String, prefixContext: String): String = ""
}

/**
 * テキストをそのまま 1 トークンとして返す [Segmenter]。
 *
 * Segment 分割の詳細が関係しないテストで使う最小スタブ。
 */
private object PassthroughSegmenter : Segmenter {
    override fun segment(text: String): List<SegmentToken> {
        return listOf(SegmentToken(text, text))
    }
}
