package me.matsumo.romaflow.core.ime

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RomaFlowEngine] の未確定 buffer まわりの振る舞いを検証するテスト。
 *
 * テストは macosArm64 上で実行する。Android ターゲットは host unit test を有効化していないため
 * 本テストは実行されず、依存がコンパイル時に解決されることのみ確認する。
 * 変換系は決定的な [FakeConversionProvider] を注入し、convert() の結果を applyConversion() で反映する。
 */
class RomaFlowEngineTest {

    @Test
    fun inputRomaji_accumulatesAndConvertsToKana() {
        val engine = RomaFlowEngine(FakeConversionProvider())

        // buffer は raw romaji を連結するため、表示かなは連結後の文字列を変換した結果になる
        assertEquals("か", engine.inputRomaji("ka"))
        assertEquals("かき", engine.inputRomaji("ki"))
        assertEquals("かきく", engine.inputRomaji("ku"))
    }

    @Test
    fun inputRomaji_handlesSyllabicNUsingImeRule() {
        val engine = RomaFlowEngine(FakeConversionProvider())

        // IME モードなので nn→ん が効き、こんんにちは ではなく こんにちは になる
        assertEquals("こんにちは", engine.inputRomaji("konnnitiha"))
    }

    @Test
    fun inputRomaji_convertsPunctuation() {
        val engine = RomaFlowEngine(FakeConversionProvider())

        assertEquals("。", engine.inputRomaji("."))
    }

    @Test
    fun inputRomaji_keepsUppercaseWordAsLatin() {
        val engine = RomaFlowEngine(FakeConversionProvider())

        // 大文字始まりの塊は英単語として Latin のまま残し、空白以降の小文字はかな変換する
        assertEquals("Tokyo です", engine.inputRomaji("Tokyo desu"))
    }

    @Test
    fun inputRomaji_defersTrailingNWhileTyping() {
        val engine = RomaFlowEngine(FakeConversionProvider())

        // 入力途中は末尾の単独 n を保留する
        assertEquals("おn", engine.inputRomaji("on"))
    }

    @Test
    fun deleteBackward_removesLastRomajiCharacter() {
        val engine = RomaFlowEngine(FakeConversionProvider())
        engine.inputRomaji("ka")
        engine.inputRomaji("i")

        // "kai" (かい) から末尾1文字を削ると "ka" (か) になる
        assertEquals("か", engine.deleteBackward())
    }

    @Test
    fun deleteBackward_onEmptyBufferStaysEmpty() {
        val engine = RomaFlowEngine(FakeConversionProvider())

        assertEquals("", engine.deleteBackward())
        assertFalse(engine.hasComposition())
    }

    @Test
    fun commit_resolvesTrailingNAndClearsBuffer() {
        val engine = RomaFlowEngine(FakeConversionProvider())
        engine.inputRomaji("on")

        // 確定時は保留していた末尾 n を ん へ解決する
        assertEquals("おん", engine.commit())
        assertFalse(engine.hasComposition())
        assertEquals("あ", engine.inputRomaji("a"))
    }

    @Test
    fun commit_returnsKanaAndClearsBuffer() {
        val engine = RomaFlowEngine(FakeConversionProvider())
        engine.inputRomaji("nihon")

        assertEquals("にほん", engine.commit())
        assertFalse(engine.hasComposition())
    }

    @Test
    fun cancel_clearsBuffer() {
        val engine = RomaFlowEngine(FakeConversionProvider())
        engine.inputRomaji("kyou")

        engine.cancel()

        assertFalse(engine.hasComposition())
    }

    @Test
    fun hasComposition_reflectsBufferState() {
        val engine = RomaFlowEngine(FakeConversionProvider())

        assertFalse(engine.hasComposition())

        engine.inputRomaji("a")

        assertTrue(engine.hasComposition())
    }

    @Test
    fun convert_runsProviderAndEntersConvertedState() = runTest {
        val engine = RomaFlowEngine(FakeConversionProvider())
        engine.inputRomaji("nihongo")

        // FakeConversionProvider の変換表により にほんご→日本語 になる
        assertEquals("日本語", engine.convertAndApply())
        assertTrue(engine.isConverted())
    }

    @Test
    fun convert_onEmptyBufferReturnsEmptyAndStaysUnconverted() = runTest {
        val engine = RomaFlowEngine(FakeConversionProvider())

        assertEquals("", engine.convertAndApply())
        assertFalse(engine.isConverted())
    }

    @Test
    fun applyConversion_ignoresEmptyResultAndStaysUnconverted() {
        val engine = RomaFlowEngine(FakeConversionProvider())
        engine.inputRomaji("nihongo")

        // 失敗(空文字)は据え置き。変換状態に入らずかな入力を維持する
        assertEquals("", engine.applyConversion(""))
        assertFalse(engine.isConverted())
        assertTrue(engine.hasComposition())
    }

    @Test
    fun commit_returnsConvertedTextWhenConverted() = runTest {
        val engine = RomaFlowEngine(FakeConversionProvider())
        engine.inputRomaji("kanji")
        engine.convertAndApply()

        // 変換済み状態の確定は変換結果をそのまま返す (WYSIWYG)
        assertEquals("漢字", engine.commit())
        assertFalse(engine.hasComposition())
        assertFalse(engine.isConverted())
    }

    @Test
    fun deleteBackward_revertsConversionToKana() = runTest {
        val engine = RomaFlowEngine(FakeConversionProvider())
        engine.inputRomaji("kanji")
        engine.convertAndApply()

        // 変換済み状態の Backspace は変換を取り消し、かな表示へ戻す (文字は削らない)
        assertEquals("かんじ", engine.deleteBackward())
        assertFalse(engine.isConverted())
        assertTrue(engine.hasComposition())
    }

    @Test
    fun inputRomaji_clearsConvertedStateWhenTypingAfterConversion() = runTest {
        val engine = RomaFlowEngine(FakeConversionProvider())
        engine.inputRomaji("watasi")
        engine.convertAndApply()
        assertTrue(engine.isConverted())

        // 変換済み状態で追加入力が来たら変換フラグを解除する (commit は呼び出し側が先に行う想定)
        engine.inputRomaji("a")

        assertFalse(engine.isConverted())
    }

    @Test
    fun cancel_clearsConvertedState() = runTest {
        val engine = RomaFlowEngine(FakeConversionProvider())
        engine.inputRomaji("toukyou")
        engine.convertAndApply()

        engine.cancel()

        assertFalse(engine.hasComposition())
        assertFalse(engine.isConverted())
    }

    @Test
    fun convert_buildsKanjiKanaKatakanaCandidates() = runTest {
        val engine = RomaFlowEngine(FakeConversionProvider())
        engine.inputRomaji("nihongo")
        engine.convertAndApply()

        // 変換結果・ひらがな読み・カタカナ読みが改行区切りで並ぶ
        assertEquals("日本語\nにほんご\nニホンゴ", engine.candidatesText())
        assertTrue(engine.hasMultipleCandidates())
    }

    @Test
    fun convert_offersKanaAndKatakanaWhenNoKanjiConversion() = runTest {
        val engine = RomaFlowEngine(FakeConversionProvider())
        engine.inputRomaji("sushi")
        engine.convertAndApply()

        // 変換表に無い読みは変換結果がひらがなと一致するため、ひらがなとカタカナの2候補に畳まれる
        assertEquals("すし\nスシ", engine.candidatesText())
        assertTrue(engine.hasMultipleCandidates())
    }

    @Test
    fun convert_collapsesToSingleCandidateForLatinWord() = runTest {
        val engine = RomaFlowEngine(FakeConversionProvider())
        engine.inputRomaji("Tokyo")
        engine.convertAndApply()

        // ひらがなを含まない Latin 語は3候補すべてが一致するため1候補に畳まれる
        assertEquals("Tokyo", engine.candidatesText())
        assertFalse(engine.hasMultipleCandidates())
    }

    @Test
    fun candidatesText_isEmptyWhenNotConverted() {
        val engine = RomaFlowEngine(FakeConversionProvider())
        engine.inputRomaji("nihongo")

        // Tab 変換前は候補が存在しない
        assertEquals("", engine.candidatesText())
        assertFalse(engine.hasMultipleCandidates())
    }

    @Test
    fun commitCandidate_commitsGivenTextAndClearsState() = runTest {
        val engine = RomaFlowEngine(FakeConversionProvider())
        engine.inputRomaji("nihongo")
        engine.convertAndApply()

        // 候補ウィンドウで選ばれた候補 (ここではカタカナ) をそのまま確定する
        assertEquals("ニホンゴ", engine.commitCandidate("ニホンゴ"))
        assertFalse(engine.hasComposition())
        assertFalse(engine.isConverted())
        assertEquals("", engine.candidatesText())
    }

    @Test
    fun commit_clearsCandidates() = runTest {
        val engine = RomaFlowEngine(FakeConversionProvider())
        engine.inputRomaji("kanji")
        engine.convertAndApply()

        engine.commit()

        assertEquals("", engine.candidatesText())
        assertFalse(engine.hasMultipleCandidates())
    }

    @Test
    fun deleteBackward_clearsCandidatesWhenRevertingConversion() = runTest {
        val engine = RomaFlowEngine(FakeConversionProvider())
        engine.inputRomaji("nihongo")
        engine.convertAndApply()

        engine.deleteBackward()

        // 変換取り消しで候補も消える
        assertEquals("", engine.candidatesText())
        assertFalse(engine.hasMultipleCandidates())
    }
}

/** convert() の結果を applyConversion() で反映するテスト用ヘルパー。 */
private suspend fun RomaFlowEngine.convertAndApply(): String {
    val result = convert()

    return applyConversion(result)
}
