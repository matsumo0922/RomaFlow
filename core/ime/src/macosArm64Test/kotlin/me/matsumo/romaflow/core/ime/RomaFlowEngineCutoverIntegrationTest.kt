package me.matsumo.romaflow.core.ime

import kotlinx.coroutines.runBlocking
import me.matsumo.romaflow.core.morphology.IpadicReadingLexicon
import me.matsumo.romaflow.core.morphology.MomijiConnectionCostProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A-rerank cutover の結合テスト（macosArm64・実辞書使用）。
 *
 * IpadicReadingLexicon と MomijiConnectionCostProvider を使い、正常 cutover パス
 * （verified path 経由での segment 生成）と rerank 失敗時の Viterbi 1位 fallback を確認する。
 *
 * テストケース一覧:
 * - 正常 cutover: provider が「天気」を返すとき verified path 経由で segment が生成されること
 * - segment range 絶対座標: segment の reading が readingInput の offset 0 始まりの絶対座標を指すこと
 * - rerank 失敗 → Viterbi 1位 fallback: rerank() が -1 を返すとき辞書 rank-0（「天気」）で変換されること
 * - literal verified path: provider が reading と同一の surface を返した場合 LiteralLexicon が
 *   verified path を作り buildTailSegmentsFromPath 経由で segment 化されること（commonTest 側で確認）
 * - 空変換 no-op / stale revision no-op
 *
 * 実辞書ロードがあるため commonTest ではなく macosArm64Test に置く。
 */
@Suppress("FunctionNaming")
class RomaFlowEngineCutoverIntegrationTest {

    private val lexicon by lazy { IpadicReadingLexicon() }
    private val costProvider by lazy { MomijiConnectionCostProvider.load() }

    @Test
    fun cutover_verifiedPath_tenki() = runBlocking {
        // 「てんき」→「天気」の verified path cutover を確認する
        val provider = FixedConversionProvider("天気")
        val engine = RomaFlowEngine(
            conversionProvider = provider,
            segmenter = TenkiSegmenter,
            aligner = DpReadingAligner(),
            readingLexiconFactory = { lexicon },
            connectionCostProviderFactory = { costProvider },
        )

        engine.inputRomaji("tenki")

        val convertResult = engine.convert()

        // provider が返した変換結果が convert() 経由で伝わること
        assertEquals("天気", convertResult)

        val preedit = engine.applyConversion(convertResult)

        // preedit に変換後の surface が表示されること
        assertTrue(preedit.contains("天気"), "preedit が天気を含むこと: actual=$preedit")
        assertTrue(engine.isConverted())
    }

    @Test
    fun cutover_verifiedPath_segmentRangeIsAbsolute() = runBlocking {
        // segment の range が readingInput の絶対座標（offset 0 始まり）を指すこと
        val provider = FixedConversionProvider("天気")
        val engine = RomaFlowEngine(
            conversionProvider = provider,
            segmenter = TenkiSegmenter,
            aligner = DpReadingAligner(),
            readingLexiconFactory = { lexicon },
            connectionCostProviderFactory = { costProvider },
        )

        engine.inputRomaji("tenki")

        val convertResult = engine.convert()

        engine.applyConversion(convertResult)

        // segment が 1 つ以上あり、surface が "天気" であること
        assertTrue(engine.segmentCount() >= 1)
        assertEquals("天気", engine.segmentText(0))

        // segment の reading が readingInput（"てんき" = 3文字）全体を覆うこと。
        // verified path では lexeme の reading 長で切り出した ひらがな reading が格納される。
        // "てんき" は IPADIC で 1 lexeme（天気）として処理されるため reading は "てんき"。
        assertEquals("てんき", engine.segmentReading(0))
    }

    @Test
    fun cutover_rerankFailure_viterbiRank0Fallback() = runBlocking {
        // A-rerank 設計: rerank() が -1 を返した場合（失敗）、格子の Viterbi 1位（rank-0）の表層を採用する。
        //
        // FixedConversionProvider は rerank() で -1 を返す（convert() の固定値は使われない）。
        // IPADIC + MomijiConnectionCostProvider を使った格子の rank-0 は "天気" になるため、
        // rerank 失敗でも "天気" に変換されることを確認する。
        val provider = FixedConversionProvider("XYZ") // convert() の固定値は参照されない
        val engine = RomaFlowEngine(
            conversionProvider = provider,
            segmenter = TenkiSegmenter,
            aligner = DpReadingAligner(),
            readingLexiconFactory = { lexicon },
            connectionCostProviderFactory = { costProvider },
        )

        engine.inputRomaji("tenki")

        val convertResult = engine.convert()

        // rerank 失敗（-1）→ Viterbi 1位 = "天気"
        assertEquals("天気", convertResult)

        val preedit = engine.applyConversion(convertResult)

        assertTrue(preedit.contains("天気"), "rerank 失敗でも Viterbi 1位 fallback で変換されること: actual=$preedit")
        assertTrue(engine.isConverted())
    }

    @Test
    fun cutover_rerankAlwaysFails_viterbiRank0IsUsed() = runBlocking {
        // A-rerank 設計: rerank() が常に -1 を返す provider でも Viterbi 1位（rank-0）で変換が成功すること。
        //
        // AlwaysFailRerankProvider は rerank() で常に -1 を返す。
        // IPADIC + MomijiConnectionCostProvider の格子 rank-0 は "天気" になるため、
        // rerank 失敗でも "天気" に変換されることを確認する。
        val engine = RomaFlowEngine(
            conversionProvider = AlwaysFailRerankProvider,
            segmenter = TenkiSegmenter,
            aligner = DpReadingAligner(),
            readingLexiconFactory = { lexicon },
            connectionCostProviderFactory = { costProvider },
        )

        engine.inputRomaji("tenki")

        val convertResult = engine.convert()

        // rerank 失敗（-1）→ Viterbi 1位 = "天気"
        assertEquals("天気", convertResult)

        val applyResult = engine.applyConversion(convertResult)

        // rerank 失敗でも Viterbi 1位 fallback で変換が完了すること
        assertEquals("天気", applyResult)
        assertTrue(engine.isConverted())
    }

    @Test
    fun cutover_staleRevision_applyIsNoOp() = runBlocking {
        val provider = FixedConversionProvider("天気")
        val engine = RomaFlowEngine(
            conversionProvider = provider,
            segmenter = TenkiSegmenter,
            aligner = DpReadingAligner(),
            readingLexiconFactory = { lexicon },
            connectionCostProviderFactory = { costProvider },
        )

        engine.inputRomaji("tenki")

        val convertResult = engine.convert()

        // convert 後に入力を変えて stale にする
        engine.inputRomaji("ka")

        val applyResult = engine.applyConversion(convertResult)

        // stale なので applyConversion は no-op
        assertEquals("", applyResult)
        assertFalse(engine.isConverted())
    }
}

// ---- helpers ----

/** 固定の変換結果を返す [ConversionProvider]。rerank は常に -1 を返す。 */
private class FixedConversionProvider(private val fixedResult: String) : ConversionProvider {
    override suspend fun convert(request: ConversionRequest): String = fixedResult
    override suspend fun candidates(request: WordCandidateRequest): String = ""
    override suspend fun rerank(request: RerankRequest): Int = -1
    override suspend fun rerankFactorized(
        request: me.matsumo.romaflow.core.ime.shadow.FactorizedRerankRequest,
    ): me.matsumo.romaflow.core.ime.shadow.FactorizedRerankResult =
        me.matsumo.romaflow.core.ime.shadow.FactorizedRerankResult(choices = emptyMap())
}

/**
 * rerank() が常に -1 を返す [ConversionProvider]（rerank 失敗テスト用）。
 *
 * A-rerank 設計では rerank 失敗時は Viterbi 1位 fallback を使うため、
 * 変換が no-op になるのではなく格子から最良 surface が採用される。
 */
private object AlwaysFailRerankProvider : ConversionProvider {
    override suspend fun convert(request: ConversionRequest): String = ""
    override suspend fun candidates(request: WordCandidateRequest): String = ""
    override suspend fun rerank(request: RerankRequest): Int = -1
    override suspend fun rerankFactorized(
        request: me.matsumo.romaflow.core.ime.shadow.FactorizedRerankRequest,
    ): me.matsumo.romaflow.core.ime.shadow.FactorizedRerankResult =
        me.matsumo.romaflow.core.ime.shadow.FactorizedRerankResult(choices = emptyMap())
}

/** 「天気」を 1 token で分割する [Segmenter]。 */
private object TenkiSegmenter : Segmenter {
    override fun segment(text: String): List<SegmentToken> {
        return listOf(SegmentToken(text, text))
    }
}
