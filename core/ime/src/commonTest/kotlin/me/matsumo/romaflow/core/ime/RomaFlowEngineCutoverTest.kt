package me.matsumo.romaflow.core.ime

import kotlinx.coroutines.test.runTest
import me.matsumo.romaflow.core.morphology.LexemeMatch
import me.matsumo.romaflow.core.morphology.ReadingLexicon
import me.matsumo.romaflow.core.morphology.ZeroConnectionCostProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A-5 cutover の不変条件をテストで固定するテストクラス。
 *
 * 実辞書が必要な正常 cutover（verified path 経由）のテストは [macosArm64Test] 側に配置する。
 * こちらは実辞書不要な不変条件系（空 / stale / failure / pending finalization）を
 * スタブ [ReadingLexicon] と [ZeroConnectionCostProvider] で検証する。
 *
 * テストはすべて [RomaFlowEngine] の公開 API のみを通じて行い、
 * 内部実装（resolver / applier）は間接的にしか触らない。
 */
class RomaFlowEngineCutoverTest {

    @Test
    fun convert_onEmptyInput_returnsEmpty() = runTest {
        val engine = newStubEngine()

        val result = engine.convert()

        assertEquals("", result)
    }

    @Test
    fun applyConversion_onEmptyInput_isNoOp() {
        val engine = newStubEngine()

        val result = engine.applyConversion("天気")

        assertEquals("", result)
        assertFalse(engine.isConverted())
    }

    @Test
    fun convert_onEmptyInput_applyConversion_isNoOp() = runTest {
        val engine = newStubEngine()

        val convertResult = engine.convert()

        assertEquals("", convertResult)

        val applyResult = engine.applyConversion("")

        assertEquals("", applyResult)
        assertFalse(engine.isConverted())
    }

    @Test
    fun applyConversion_onStaleRevision_isNoOp() = runTest {
        val engine = newStubEngine()
        engine.inputRomaji("tenki")

        // convert() を呼んで pendingConversionRevision を確定させる
        val convertResult = engine.convert()

        // 追加入力で inputRevision を進める（stale 状態を作る）
        engine.inputRomaji("ka")

        // stale になった結果を applyConversion に渡しても no-op
        val applyResult = engine.applyConversion(convertResult)

        assertEquals("", applyResult)
        assertFalse(engine.isConverted())
    }

    @Test
    fun applyConversion_onEmptyResult_isNoOp() = runTest {
        val engine = newStubEngine()
        engine.inputRomaji("tenki")
        engine.convert()

        val result = engine.applyConversion("")

        assertEquals("", result)
        assertFalse(engine.isConverted())
    }

    @Test
    fun convert_pendingFinalizationApplied_beforeConvert() = runTest {
        // "on" を入力すると pending "n" が残る
        // provider は読みをそのまま返す PassThroughConversionProvider を使って確認する
        val recording = PassThroughConversionProvider()
        val recordingEngine = RomaFlowEngine(
            conversionProvider = recording,
            segmenter = FakeSegmenter(),
            aligner = FakeAligner(),
            readingLexicon = StubEmptyReadingLexicon,
            connectionCostProvider = ZeroConnectionCostProvider,
        )
        recordingEngine.inputRomaji("on")

        recordingEngine.convert()

        // provider に渡った reading は "おん"（pending n→ん が確定済み）であること
        val request = requireNotNull(recording.lastRequest)
        assertEquals("おん", request.readingInput)
    }

    @Test
    fun failureProvider_convertReturnsEmpty() = runTest {
        // provider が例外を投げた場合、convert() が "" を返すこと（failure no-op）
        // LegacyFullTextResolver は runCatching で failure を受け取り Failure proposal を返す
        val failingProvider = FailingConversionProvider
        val engine = RomaFlowEngine(
            conversionProvider = failingProvider,
            segmenter = FakeSegmenter(),
            aligner = FakeAligner(),
            readingLexicon = StubEmptyReadingLexicon,
            connectionCostProvider = ZeroConnectionCostProvider,
        )
        engine.inputRomaji("tenki")

        val result = engine.convert()

        // 例外 → Failure proposal → "" を返す
        assertEquals("", result)
    }

    @Test
    fun convert_lockedPrefixProvider_tailReadingPassedToProvider() = runTest {
        // locked prefix がある場合、provider に tail reading だけが渡ること
        // これは RomaFlowEngineTest.reconvert_sendsTailReadingAndLockedPrefixContextToProvider と
        // 同じ不変条件を cutover 後の ProposalApplier 経路で確認する
        val provider = PassThroughConversionProvider()
        val engine = RomaFlowEngine(
            conversionProvider = provider,
            segmenter = WatashiTenkiSegmenter,
            aligner = FakeAligner(),
            readingLexicon = StubEmptyReadingLexicon,
            connectionCostProvider = ZeroConnectionCostProvider,
        )
        engine.inputRomaji("watashitenki")
        engine.applyConversion(engine.convert())

        // segment 0（わ）を選択して確定 → 最初の1文字が locked prefix になる
        // スタブ辞書では OOV fallback で1文字ずつ segment になる
        // segment 末尾から ← で先頭に向かって移動し、segment 0 を確定する
        val segmentCount = engine.segmentCount()
        repeat(segmentCount) { engine.moveSelectionLeft() }
        // segment 0 を1文字（わ）として確定
        engine.confirmCandidate("渡")

        // 再変換: provider に tail reading（わ以外）が渡ること
        engine.convert()

        val request = requireNotNull(provider.lastRequest)
        // locked prefix（"わ"=1文字）以降の tail が渡ること
        val expectedTail = "たしてんき"
        assertEquals(expectedTail, request.readingInput)
    }

    @Test
    fun literalPath_readingAsSurface_segmentsByLiteralLexicon() = runTest {
        // provider が reading と同一の surface を返す場合、LiteralLexicon が verified path を作る。
        // これは buildTailSegments（OOV fallback）ではなく buildTailSegmentsFromPath（literal-path）経由。
        //
        // StubEmptyReadingLexicon は IPADIC を持たないため LiteralLexicon のみが arc を提供する。
        // reading の各文字と同一 surface の1文字 arc が verified path を作り、1文字ずつ segment になる。
        val provider = PassThroughConversionProvider()
        val engine = RomaFlowEngine(
            conversionProvider = provider,
            segmenter = FakeSegmenter(),
            aligner = FakeAligner(),
            readingLexicon = StubEmptyReadingLexicon,
            connectionCostProvider = ZeroConnectionCostProvider,
        )
        engine.inputRomaji("tenki")
        engine.applyConversion(engine.convert())

        // LiteralLexicon の verified path 経由で segment が生成されること
        assertTrue(engine.isConverted())

        // "てんき" は3文字 → LiteralLexicon arc（て/ん/き）で1文字ずつ3 segment になること
        assertEquals(3, engine.segmentCount(), "literal-path では1文字ずつ segment になること")
        assertEquals("て", engine.segmentText(0))
        assertEquals("ん", engine.segmentText(1))
        assertEquals("き", engine.segmentText(2))
    }
}

// ---- helpers ----

/** スタブ lexicon（エントリ不要）と ZeroConnectionCostProvider を注入した engine を作る。 */
private fun newStubEngine(): RomaFlowEngine {
    return RomaFlowEngine(
        conversionProvider = FakeConversionProvider(),
        segmenter = FakeSegmenter(),
        aligner = FakeAligner(),
        readingLexicon = StubEmptyReadingLexicon,
        connectionCostProvider = ZeroConnectionCostProvider,
    )
}

/** convert() に渡された最後の [ConversionRequest] を記録し、変換結果は読みを素通しで返す provider。 */
private class PassThroughConversionProvider : ConversionProvider {

    var lastRequest: ConversionRequest? = null
        private set

    override suspend fun convert(request: ConversionRequest): String {
        lastRequest = request

        // provider は読みをそのまま返す（後段の ProposalApplier で格子探索するが OOV fallback で処理される）
        return request.readingInput
    }

    override suspend fun candidates(request: WordCandidateRequest): String = ""
}

/** 常に例外をスローするテスト用 provider（failure no-op テスト用）。 */
private object FailingConversionProvider : ConversionProvider {
    override suspend fun convert(request: ConversionRequest): String {
        error("conversion failed for testing")
    }

    override suspend fun candidates(request: WordCandidateRequest): String = ""
}

/**
 * エントリを返さないスタブ [ReadingLexicon]。
 *
 * OOV fallback（[me.matsumo.romaflow.core.morphology.LiteralLexicon]）のみで動作させるために使う。
 * 格子に辞書エントリが無くても [me.matsumo.romaflow.core.morphology.CompositeLexicon] が
 * LiteralLexicon で arc を補完するため、ProposalApplier が失敗しない。
 */
private object StubEmptyReadingLexicon : ReadingLexicon {
    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> = emptyList()
}

/** テキストを1文字ずつ token に分割する [Segmenter]（OOV テスト用）。 */
private object CharByCharSegmenter : Segmenter {
    override fun segment(text: String): List<SegmentToken> {
        return text.map { SegmentToken(it.toString(), it.toString()) }
    }
}

/** 「私天気」を読み付き 2 token に分割するテスト用 [Segmenter]。 */
private object WatashiTenkiSegmenter : Segmenter {
    override fun segment(text: String): List<SegmentToken> {
        return when (text) {
            "私天気" -> listOf(
                SegmentToken("私", "わたし"),
                SegmentToken("天気", "てんき"),
            )
            "天気" -> listOf(SegmentToken("天気", "てんき"))
            "わたしてんき" -> listOf(
                SegmentToken("わたし", "わたし"),
                SegmentToken("てんき", "てんき"),
            )
            else -> text.map { SegmentToken(it.toString(), it.toString()) }
        }
    }
}
