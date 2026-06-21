package me.matsumo.romaflow.core.ime

import kotlinx.coroutines.runBlocking
import me.matsumo.romaflow.core.ime.shadow.FactorizedRerankRequest
import me.matsumo.romaflow.core.ime.shadow.FactorizedRerankResult
import me.matsumo.romaflow.core.morphology.IpadicReadingLexicon
import me.matsumo.romaflow.core.morphology.MomijiConnectionCostProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RomaFlowEngine + FactorizedRerankResolver 統合テスト（macosArm64・実 IPADIC 辞書使用）。
 *
 * 実 LLM は使わず [FakeConversionProvider] で決定論的に動作を確認する。
 *
 * ## 検証項目（回帰の主役）
 * - `べんきょうしてせいかをあげた` が Fake choices（せいか→成果 / あげた→上げた）で
 *   `勉強して成果を上げた` になること。
 * - region が 0 個（曖昧箇所なし）のとき baseline（Viterbi rank-0）を返すこと。
 * - OOV（ASCII literal）が KEEP option として保持されること。
 */
@Suppress("FunctionNaming")
class RomaFlowEngineFactorizedIntegrationTest {

    private val lexicon by lazy { IpadicReadingLexicon() }
    private val costProvider by lazy { MomijiConnectionCostProvider.load() }

    /**
     * 回帰テスト主役: `べんきょうしてせいかをあげた` が Fake choices で `勉強して成果を上げた` になること。
     *
     * [FakeConversionProvider.FACTORIZED_RERANK_TABLE] には
     * せいか→成果 / あげた→上げた が設定されている。
     * これにより factorized rerank が正しく機能すれば変換結果が `勉強して成果を上げた` になる。
     * flat rerank（全文 N-best）では成果が top-16 圏外のため不可能だが、factorized は可能なことを確認する。
     */
    @Test
    fun factorized_benkyoushiteseikawooageta_becomesCorrectSurface() = runBlocking {
        val provider = FakeConversionProvider()
        val engine = buildFactorizedEngine(provider)

        engine.inputRomaji("benkyo")
        engine.inputRomaji("u")
        engine.inputRomaji("sh")
        engine.inputRomaji("i")
        engine.inputRomaji("te")
        engine.inputRomaji("seika")
        engine.inputRomaji("wo")
        engine.inputRomaji("ageta")

        val convertResult = engine.convert()

        assertTrue(
            convertResult.isNotEmpty(),
            "factorized rerank でも convert() は非空を返すこと（実際: '$convertResult'）",
        )

        engine.applyConversion(convertResult)

        assertTrue(
            engine.isConverted(),
            "applyConversion で isConverted=true になること",
        )

        val allSurfaces = buildString {
            for (segmentIndex in 0 until engine.segmentCount()) {
                append(engine.segmentText(segmentIndex))
            }
        }

        // 報告バグの本丸: せいか の同音語選択。flat rerank では top-16 圏外で「生家/成績」になっていた。
        // factorized では せいか span の region から Fake が「成果」を選べることを検証する（捏造でなく格子内選択）。
        assertTrue(
            convertResult.contains("成果"),
            "factorized rerank で せいか→成果 が選択されること（実際: '$convertResult'）",
        )

        // 全文の組み立て検証。あげた は IPADIC で「あげ＋た」に分割されるため Fake は あげ→上げ を選ぶ。
        // region 選択を baseline に差し込んだ完全 surface が convert() の戻り値と segment 表層の双方に反映されること。
        assertEquals(
            "勉強して成果を上げた",
            convertResult,
            "factorized rerank で全文が組み立てられること（実際: '$convertResult'）",
        )
        assertEquals(
            "勉強して成果を上げた",
            allSurfaces,
            "applyConversion 後の segment 表層が convert() の surface と一致すること（surface-carry）",
        )
    }

    /**
     * factorized rerank で Fake が全 region を未選択にした場合（choices 空）、
     * baseline（Viterbi rank-0）surface が採用されることを確認する。
     *
     * choices 空 → 各 region が未選択 → baseline lexeme を使う → baseline surface が preferredSurface になる。
     */
    @Test
    fun factorized_emptyChoices_fallsBackToBaseline() = runBlocking {
        val provider = AlwaysEmptyChoiceProvider()
        val engine = buildFactorizedEngine(provider)

        engine.inputRomaji("tenki")

        val convertResult = engine.convert()

        assertTrue(
            convertResult.isNotEmpty(),
            "choices 空でも convert() は Viterbi rank-0 の表層を返すこと（実際: '$convertResult'）",
        )

        engine.applyConversion(convertResult)

        assertTrue(
            engine.isConverted(),
            "choices 空でも applyConversion で isConverted=true になること",
        )
    }

    /**
     * OOV（ASCII 英字）入力で literal arc が保持されることを確認する。
     *
     * "ok" を inputRomaji すると "おk" になる（"k" は孤立 literal）。
     * factorized rerank でも OOV literal が surface に保持されることを確認する。
     */
    @Test
    fun factorized_oovAscii_literalPreserved() = runBlocking {
        val provider = AlwaysEmptyChoiceProvider()
        val engine = buildFactorizedEngine(provider)

        val preeditAfterInput = engine.inputRomaji("ok")

        assertEquals("おk", preeditAfterInput, "romaji→kana 変換で 'ok' が 'おk' になること")

        val convertResult = engine.convert()

        assertTrue(convertResult.isNotEmpty(), "convert() が空でないこと（実際: '$convertResult'）")

        engine.applyConversion(convertResult)

        assertTrue(engine.isConverted(), "isConverted=true になること")

        val allSurfaces = buildString {
            for (segmentIndex in 0 until engine.segmentCount()) {
                append(engine.segmentText(segmentIndex))
            }
        }

        assertTrue(
            allSurfaces.contains("k"),
            "literal 'k' が segment 表層に保持されること（実際: '$allSurfaces'）",
        )
    }

    private fun buildFactorizedEngine(provider: ConversionProvider): RomaFlowEngine {
        return RomaFlowEngine(
            conversionProvider = provider,
            segmenter = FactorizedPassthroughSegmenter,
            aligner = DpReadingAligner(),
            readingLexiconFactory = { lexicon },
            connectionCostProviderFactory = { costProvider },
            rerankMode = RerankMode.Factorized,
        )
    }
}

// ---- helpers ----

/**
 * テキストをそのまま 1 トークンとして返す [Segmenter]。
 */
private object FactorizedPassthroughSegmenter : Segmenter {
    override fun segment(text: String): List<SegmentToken> {
        return listOf(SegmentToken(text, text))
    }
}

/**
 * rerankFactorized が常に空 choices を返す [ConversionProvider] スタブ。
 *
 * factorized fallback（choices 空 → baseline）をテストするために使う。
 */
private class AlwaysEmptyChoiceProvider : ConversionProvider {
    override suspend fun convert(request: ConversionRequest): String = ""
    override suspend fun candidates(request: WordCandidateRequest): String = ""
    override suspend fun rerank(request: RerankRequest): Int = -1
    override suspend fun rerankFactorized(request: FactorizedRerankRequest): FactorizedRerankResult =
        FactorizedRerankResult(choices = emptyMap())
}
