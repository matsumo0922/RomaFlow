package me.matsumo.romaflow.core.ime

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RomaFlowEngine] の変換 draft（増分入力・全文変換・混在 preedit・単語選択・per-segment revert・backspace）の
 * 振る舞いを検証するテスト。
 *
 * テストは macosArm64 上で実行する。Android ターゲットは host unit test を有効化していないため
 * 本テストは実行されず、依存がコンパイル時に解決されることのみ確認する。
 * 変換系は決定的な [FakeConversionProvider]、分割は注入する [Segmenter]、対応付けは [FakeAligner] を使う。
 */
class RomaFlowEngineTest {

    @Test
    fun inputRomaji_accumulatesAndConvertsToKana() {
        val engine = newEngine()

        assertEquals("か", engine.inputRomaji("ka"))
        assertEquals("かき", engine.inputRomaji("ki"))
        assertEquals("かきく", engine.inputRomaji("ku"))
    }

    @Test
    fun inputRomaji_handlesSyllabicNUsingImeRule() {
        val engine = newEngine()

        assertEquals("こんにちは", engine.inputRomaji("konnnitiha"))
    }

    @Test
    fun inputRomaji_convertsPunctuation() {
        val engine = newEngine()

        assertEquals("。", engine.inputRomaji("."))
    }

    @Test
    fun inputRomaji_keepsUppercaseWordAsLatin() {
        val engine = newEngine()

        assertEquals("Tokyo です", engine.inputRomaji("Tokyo desu"))
    }

    @Test
    fun inputRomaji_defersTrailingNWhileTyping() {
        val engine = newEngine()

        assertEquals("おn", engine.inputRomaji("on"))
    }

    @Test
    fun deleteBackward_removesLastKana() {
        val engine = newEngine()
        engine.inputRomaji("kai")

        assertEquals("か", engine.deleteBackward())
    }

    @Test
    fun deleteBackward_removesPendingRomajiFirst() {
        val engine = newEngine()
        engine.inputRomaji("ky")

        // pendingRomaji 非空時はまず pending の末尾 1 code unit を削る（優先順位 1）。
        assertEquals("k", engine.deleteBackward())
    }

    @Test
    fun deleteBackward_onEmptyBufferStaysEmpty() {
        val engine = newEngine()

        assertEquals("", engine.deleteBackward())
        assertFalse(engine.hasComposition())
    }

    @Test
    fun commit_resolvesTrailingNAndClearsBuffer() {
        val engine = newEngine()
        engine.inputRomaji("on")

        assertEquals("おん", engine.commit())
        assertFalse(engine.hasComposition())
        assertEquals("あ", engine.inputRomaji("a"))
    }

    @Test
    fun commit_returnsKanaAndClearsBuffer() {
        val engine = newEngine()
        engine.inputRomaji("nihon")

        assertEquals("にほん", engine.commit())
        assertFalse(engine.hasComposition())
    }

    @Test
    fun cancel_onUnconvertedClearsBuffer() {
        val engine = newEngine()
        engine.inputRomaji("kyou")

        assertEquals("", engine.cancel())
        assertFalse(engine.hasComposition())
    }

    @Test
    fun hasComposition_reflectsBufferState() {
        val engine = newEngine()

        assertFalse(engine.hasComposition())

        engine.inputRomaji("a")

        assertTrue(engine.hasComposition())
    }

    @Test
    fun finalizePendingRomaji_resolvesTrailingNAndMatchesCommit() {
        val engine = newEngine()
        engine.inputRomaji("on")

        // Tab 起点の finalize で末尾 n を ん へ解決し、表示用 preedit を返す（おn→おん）。
        assertEquals("おん", engine.finalizePendingRomaji())

        // finalize 後（=変換失敗・キャンセル相当）の commit は表示と一致する。
        assertEquals("おん", engine.commit())
    }

    @Test
    fun convert_runsProviderAndEntersConvertedState() = runTest {
        val engine = newEngine()
        engine.inputRomaji("nihongo")

        assertEquals("日本語", engine.convertAndApply())
        assertTrue(engine.isConverted())
        assertEquals(3, engine.segmentCount())
        assertEquals("日", engine.segmentText(0))
        assertEquals("Converted", engine.segmentStatus(0))
    }

    @Test
    fun convert_onEmptyBufferReturnsEmptyAndStaysUnconverted() = runTest {
        val engine = newEngine()

        assertEquals("", engine.convertAndApply())
        assertFalse(engine.isConverted())
    }

    @Test
    fun applyConversion_ignoresEmptyResultAndStaysUnconverted() {
        val engine = newEngine()
        engine.inputRomaji("nihongo")

        assertEquals("", engine.applyConversion(""))
        assertFalse(engine.isConverted())
        assertTrue(engine.hasComposition())
    }

    @Test
    fun applyConversion_rejectsStaleResultWhenInputChangedAfterRequest() = runTest {
        val engine = newEngine()
        engine.inputRomaji("nihongo")
        val result = engine.convert()

        engine.inputRomaji("ka")

        assertEquals("", engine.applyConversion(result))
        assertFalse(engine.isConverted())
    }

    @Test
    fun commit_returnsConvertedTextWhenConverted() = runTest {
        val engine = newEngine()
        engine.inputRomaji("kanji")
        engine.convertAndApply()

        assertEquals("漢字", engine.commit())
        assertFalse(engine.hasComposition())
        assertFalse(engine.isConverted())
    }

    @Test
    fun typingAfterConversion_doesNotReinterpretFrozenKana() = runTest {
        val engine = newEngine()
        engine.inputRomaji("hon")

        // 「ほん」を Tab 変換（provider は表に無いのでそのまま）→ 確定済みのかなは frozen。
        engine.convertAndApply()

        // その後 a を追記しても、確定済みの ほん が ほな に再解釈されてはいけない（F1 回帰の番人）。
        assertEquals("ほんあ", engine.inputRomaji("a"))
    }

    @Test
    fun typingAfterConversion_appendsUnconvertedTailToMixedPreedit() = runTest {
        val engine = newEngine()
        engine.inputRomaji("kanji")
        engine.convertAndApply()

        // 変換済 segments（漢字）は残し、追記分は未変換かな tail として混在表示する。
        assertEquals("漢字あ", engine.inputRomaji("a"))
        assertEquals(3, engine.segmentCount())
        assertEquals("Unconverted", engine.segmentStatus(2))

        // 末尾の未変換かなを 1 つ削ると変換済 preedit に戻る（tail の末尾 1 かな削除）。
        assertEquals("漢字", engine.deleteBackward())
    }

    @Test
    fun deleteBackward_onNonExactLastSegmentFallsBackToWholeRevert() = runTest {
        val engine = newEngine()
        engine.inputRomaji("kanji")
        engine.convertAndApply()

        // FakeSegmenter の読みは表層形（漢字）なので readingInput と完全一致せず、末尾 segment の
        // backspace は per-segment ではなく全体 revert にフォールバックする（confidence 低）。
        assertEquals("かんじ", engine.deleteBackward())
        assertFalse(engine.isConverted())
        assertTrue(engine.hasComposition())
    }

    @Test
    fun deleteBackward_revertsOnlyLastSegmentWhenExact() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()

        // 読みが完全一致する変換済末尾 segment（天気）だけを打った通りのかなへ戻す（優先順位 4 / range revert）。
        assertEquals("私てんき", engine.deleteBackward())
        assertEquals(2, engine.segmentCount())
        assertEquals("Converted", engine.segmentStatus(0))
        assertEquals("Unconverted", engine.segmentStatus(1))
        assertTrue(engine.isConverted())
    }

    @Test
    fun deleteBackward_onSelectedConvertedSegmentRevertsThatSegment() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()

        // 先頭 segment（私）を選択し backspace → その segment だけ中間かなへ戻す（優先順位 2）。
        engine.moveSelectionRight()

        assertEquals("わたし天気", engine.deleteBackward())
        assertEquals("Unconverted", engine.segmentStatus(0))
        assertEquals("Converted", engine.segmentStatus(1))
    }

    @Test
    fun deleteBackward_onSelectedUnconvertedSegmentDeletesTrailingKana() = runTest {
        val engine = newEngine(WATASHI_TENKI_SEGMENTER)
        engine.inputRomaji("watashitenki")
        engine.convertAndApply()
        engine.moveSelectionRight()
        engine.deleteBackward()

        // 中間かな（わたし）を選択したまま backspace → その segment 末尾の 1 かなだけ削る（優先順位 3）。
        assertEquals("わた天気", engine.deleteBackward())
        assertEquals(0, engine.selectedSegmentIndex())
        assertEquals("Unconverted", engine.segmentStatus(0))
        assertEquals("Converted", engine.segmentStatus(1))
    }

    @Test
    fun deleteBackward_onSelectedNonExactSegmentFallsBackToWholeRevert() = runTest {
        val engine = newEngine()
        engine.inputRomaji("nihongo")
        engine.convertAndApply()

        // 完全一致しない（FakeSegmenter）変換済 segment を選択して backspace → 全体 revert。
        engine.moveSelectionRight()

        assertEquals("にほんご", engine.deleteBackward())
        assertFalse(engine.isConverted())
    }

    @Test
    fun cancel_revertsConversionThenClears() = runTest {
        val engine = newEngine()
        engine.inputRomaji("toukyou")
        engine.convertAndApply()

        // 1 回目の Esc は segments を破棄して readingInput へ戻す。
        assertEquals("とうきょう", engine.cancel())
        assertFalse(engine.isConverted())
        assertTrue(engine.hasComposition())

        // 2 回目の Esc で composition 全体を破棄する。
        assertEquals("", engine.cancel())
        assertFalse(engine.hasComposition())
    }

    @Test
    fun moveSelection_navigatesSegmentsWithClamp() = runTest {
        val engine = newEngine()
        engine.inputRomaji("nihongo")
        engine.convertAndApply()

        assertEquals(-1, engine.selectedSegmentIndex())

        // 変換直後の未選択から → で先頭。
        engine.moveSelectionRight()
        assertEquals(0, engine.selectedSegmentIndex())

        // 左端を超えてもクランプ。
        engine.moveSelectionLeft()
        engine.moveSelectionLeft()
        assertEquals(0, engine.selectedSegmentIndex())

        // 右端を超えてもクランプ。
        repeat(5) { engine.moveSelectionRight() }
        assertEquals(2, engine.selectedSegmentIndex())
    }

    @Test
    fun moveSelectionLeft_fromUnselectedSelectsLastSegment() = runTest {
        val engine = newEngine()
        engine.inputRomaji("nihongo")
        engine.convertAndApply()

        engine.moveSelectionLeft()

        assertEquals(2, engine.selectedSegmentIndex())
    }
}

/** 「私天気」を読み付きの 2 token（私=わたし / 天気=てんき）へ分割するテスト用 segmenter。 */
private val WATASHI_TENKI_SEGMENTER = MappedSegmenter(
    mapOf(
        "私天気" to listOf(
            SegmentToken("私", "わたし"),
            SegmentToken("天気", "てんき"),
        ),
    ),
)

/** FakeConversionProvider + 指定 [segmenter] + FakeAligner を注入したテスト用 [RomaFlowEngine]。 */
private fun newEngine(segmenter: Segmenter = FakeSegmenter()): RomaFlowEngine {
    return RomaFlowEngine(FakeConversionProvider(), segmenter, FakeAligner())
}

/** convert() の結果を applyConversion() で反映するテスト用ヘルパー。 */
private suspend fun RomaFlowEngine.convertAndApply(): String {
    val result = convert()

    return applyConversion(result)
}

/** 既知の変換結果を読み付き token 列へ分割し、未登録の入力は 1 文字 1 token にフォールバックする segmenter。 */
private class MappedSegmenter(private val table: Map<String, List<SegmentToken>>) : Segmenter {

    override fun segment(text: String): List<SegmentToken> {
        return table[text] ?: text.map { SegmentToken(it.toString(), it.toString()) }
    }
}
