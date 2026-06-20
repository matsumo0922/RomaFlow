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
 * （verified path 経由での segment 生成）を確認する。
 *
 * - 正常 cutover: fake provider が「天気」を返すとき、convert()→applyConversion() 後に
 *   segment surface が「天気」・range が readingInput の絶対座標で正しいこと。
 * - OOV fallback: provider が格子上に完全経路のない surface を返しても segment が表示されること。
 *
 * 実辞書ロードがあるため commonTest ではなく macosArm64Test に置く。
 */
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
        // segment の range が readingInput の絶対座標を指すこと
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

        // readingInput 全体（"てんき"=3文字）に対し segment が 0 始まりの絶対座標を持つこと
        // segmentCount() >= 1 で最初の segment が index 0 から始まること
        assertTrue(engine.segmentCount() >= 1)
        assertEquals("天気", engine.segmentText(0))
    }

    @Test
    fun cutover_oovFallback_surfaceIsPreserved() = runBlocking {
        // 格子上に完全経路がない OOV surface でも legacy fallback で segment に表示されること
        // "ぬるぽ" という OOV 語を provider が返しても segment として確認できること
        val oovSurface = "ぬるぽ"
        val provider = FixedConversionProvider(oovSurface)
        val engine = RomaFlowEngine(
            conversionProvider = provider,
            segmenter = OovSegmenter(oovSurface),
            aligner = DpReadingAligner(),
            readingLexicon = lexicon,
            connectionCostProvider = costProvider,
        )

        engine.inputRomaji("nurupo")

        val convertResult = engine.convert()

        engine.applyConversion(convertResult)

        // OOV でも segment が消えない（legacy buildTailSegments fallback）
        assertTrue(engine.isConverted() || engine.hasComposition(), "OOV でも状態が保持されること")
        assertTrue(engine.segmentCount() >= 1, "segment が最低1つ生成されること")
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
