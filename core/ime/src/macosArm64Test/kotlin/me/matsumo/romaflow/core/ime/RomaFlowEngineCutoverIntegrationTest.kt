package me.matsumo.romaflow.core.ime

import kotlinx.coroutines.runBlocking
import me.matsumo.romaflow.core.morphology.IpadicReadingLexicon
import me.matsumo.romaflow.core.morphology.MomijiConnectionCostProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A-5 cutover の結合テスト（macosArm64・実辞書使用）。
 *
 * IpadicReadingLexicon と MomijiConnectionCostProvider を使い、正常 cutover パス
 * （verified path 経由での segment 生成）と OOV/格子外 surface での legacy fallback を確認する。
 *
 * テストケース一覧:
 * - 正常 cutover: provider が「天気」を返すとき verified path 経由で segment が生成されること
 * - segment range 絶対座標: segment の reading が readingInput の offset 0 始まりの絶対座標を指すこと
 * - OOV fallback（真の格子外 surface）: provider が格子上に経路の作れない ASCII surface を返すとき
 *   `buildTailSegments`（legacy aligner 経由）に落ちて surface が表示されること
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
            readingLexicon = lexicon,
            connectionCostProvider = costProvider,
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
            readingLexicon = lexicon,
            connectionCostProvider = costProvider,
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
    fun cutover_oovFallback_asciiSurfaceUsesLegacySegmenter() = runBlocking {
        // OOV fallback: provider が格子上に経路の作れない surface を返したとき
        // buildTailSegments（legacy segmenter/aligner 経由）に落ちて provider の surface が表示されること。
        //
        // ひらがな "てんき" に対して ASCII "XYZ" を返すと、格子の全 arc（IPADIC 語・LiteralLexicon ひらがな文字）
        // とも surface が一致せず findMinCostPathForSurface が null を返す。
        // applyWithPreferredSurface → state（isConverted=false）→ buildTailSegments fallback となる。
        val oovSurface = "XYZ"
        val provider = FixedConversionProvider(oovSurface)
        val engine = RomaFlowEngine(
            conversionProvider = provider,
            segmenter = OovSegmenter(oovSurface),
            aligner = DpReadingAligner(),
            readingLexicon = lexicon,
            connectionCostProvider = costProvider,
        )

        engine.inputRomaji("tenki")

        val convertResult = engine.convert()

        // convert() は provider が返した surface をそのまま返す
        assertEquals(oovSurface, convertResult)

        engine.applyConversion(convertResult)

        // legacy buildTailSegments fallback で segment が生成され、surface が provider の結果と一致すること
        assertTrue(engine.isConverted(), "OOV fallback でも isConverted=true になること")
        assertTrue(engine.segmentCount() >= 1, "segment が最低1つ生成されること")
        assertEquals(oovSurface, engine.segmentText(0), "legacy fallback の segment surface が provider の結果と一致すること")
    }

    @Test
    fun cutover_emptyConvert_applyIsNoOp() = runBlocking {
        val engine = RomaFlowEngine(
            conversionProvider = EmptyConversionProvider,
            segmenter = TenkiSegmenter,
            aligner = DpReadingAligner(),
            readingLexicon = lexicon,
            connectionCostProvider = costProvider,
        )

        engine.inputRomaji("tenki")

        val convertResult = engine.convert()

        // provider が空を返す場合、convert() も "" を返す
        assertEquals("", convertResult)

        val applyResult = engine.applyConversion("")

        assertEquals("", applyResult)
        assertFalse(engine.isConverted())
    }

    @Test
    fun cutover_staleRevision_applyIsNoOp() = runBlocking {
        val provider = FixedConversionProvider("天気")
        val engine = RomaFlowEngine(
            conversionProvider = provider,
            segmenter = TenkiSegmenter,
            aligner = DpReadingAligner(),
            readingLexicon = lexicon,
            connectionCostProvider = costProvider,
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

/** 固定の変換結果を返す [ConversionProvider]。 */
private class FixedConversionProvider(private val fixedResult: String) : ConversionProvider {
    override suspend fun convert(request: ConversionRequest): String = fixedResult
    override suspend fun candidates(request: WordCandidateRequest): String = ""
}

/** 空文字を返す [ConversionProvider]（failure no-op テスト用）。 */
private object EmptyConversionProvider : ConversionProvider {
    override suspend fun convert(request: ConversionRequest): String = ""
    override suspend fun candidates(request: WordCandidateRequest): String = ""
}

/** 「天気」を 1 token で分割する [Segmenter]。 */
private object TenkiSegmenter : Segmenter {
    override fun segment(text: String): List<SegmentToken> {
        return listOf(SegmentToken(text, text))
    }
}

/** 指定 surface を 1 token で分割する OOV テスト用 [Segmenter]。 */
private class OovSegmenter(private val surface: String) : Segmenter {
    override fun segment(text: String): List<SegmentToken> {
        return listOf(SegmentToken(surface, text))
    }
}
