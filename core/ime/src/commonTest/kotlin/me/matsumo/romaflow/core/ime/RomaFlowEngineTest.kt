package me.matsumo.romaflow.core.ime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RomaFlowEngine] の未確定 buffer まわりの振る舞いを検証するテスト。
 *
 * テストは macosArm64 上で実行する。Android ターゲットは host unit test を有効化していないため
 * 本テストは実行されず、依存がコンパイル時に解決されることのみ確認する。
 */
class RomaFlowEngineTest {

    @Test
    fun inputRomaji_accumulatesAndConvertsToKana() {
        val engine = RomaFlowEngine()

        // buffer は raw romaji を連結するため、表示かなは連結後の文字列を変換した結果になる
        assertEquals("か", engine.inputRomaji("ka"))
        assertEquals("かき", engine.inputRomaji("ki"))
        assertEquals("かきく", engine.inputRomaji("ku"))
    }

    @Test
    fun inputRomaji_handlesSyllabicNUsingImeRule() {
        val engine = RomaFlowEngine()

        // IME モードなので nn→ん が効き、こんんにちは ではなく こんにちは になる
        assertEquals("こんにちは", engine.inputRomaji("konnnitiha"))
    }

    @Test
    fun inputRomaji_convertsPunctuation() {
        val engine = RomaFlowEngine()

        assertEquals("。", engine.inputRomaji("."))
    }

    @Test
    fun inputRomaji_keepsUppercaseWordAsLatin() {
        val engine = RomaFlowEngine()

        // 大文字始まりの塊は英単語として Latin のまま残し、空白以降の小文字はかな変換する
        assertEquals("Tokyo です", engine.inputRomaji("Tokyo desu"))
    }

    @Test
    fun inputRomaji_defersTrailingNWhileTyping() {
        val engine = RomaFlowEngine()

        // 入力途中は末尾の単独 n を保留する
        assertEquals("おn", engine.inputRomaji("on"))
    }

    @Test
    fun deleteBackward_removesLastRomajiCharacter() {
        val engine = RomaFlowEngine()
        engine.inputRomaji("ka")
        engine.inputRomaji("i")

        // "kai" (かい) から末尾1文字を削ると "ka" (か) になる
        assertEquals("か", engine.deleteBackward())
    }

    @Test
    fun deleteBackward_onEmptyBufferStaysEmpty() {
        val engine = RomaFlowEngine()

        assertEquals("", engine.deleteBackward())
        assertFalse(engine.hasComposition())
    }

    @Test
    fun commit_resolvesTrailingNAndClearsBuffer() {
        val engine = RomaFlowEngine()
        engine.inputRomaji("on")

        // 確定時は保留していた末尾 n を ん へ解決する
        assertEquals("おん", engine.commit())
        assertFalse(engine.hasComposition())
        assertEquals("あ", engine.inputRomaji("a"))
    }

    @Test
    fun commit_returnsKanaAndClearsBuffer() {
        val engine = RomaFlowEngine()
        engine.inputRomaji("nihon")

        assertEquals("にほん", engine.commit())
        assertFalse(engine.hasComposition())
    }

    @Test
    fun cancel_clearsBuffer() {
        val engine = RomaFlowEngine()
        engine.inputRomaji("kyou")

        engine.cancel()

        assertFalse(engine.hasComposition())
    }

    @Test
    fun hasComposition_reflectsBufferState() {
        val engine = RomaFlowEngine()

        assertFalse(engine.hasComposition())

        engine.inputRomaji("a")

        assertTrue(engine.hasComposition())
    }

    @Test
    fun convert_runsProviderAndEntersConvertedState() {
        val engine = RomaFlowEngine()
        engine.inputRomaji("nihongo")

        // FakeConversionProvider の変換表により にほんご→日本語 になる
        assertEquals("日本語", engine.convert())
        assertTrue(engine.isConverted())
    }

    @Test
    fun convert_onEmptyBufferReturnsEmptyAndStaysUnconverted() {
        val engine = RomaFlowEngine()

        assertEquals("", engine.convert())
        assertFalse(engine.isConverted())
    }

    @Test
    fun commit_returnsConvertedTextWhenConverted() {
        val engine = RomaFlowEngine()
        engine.inputRomaji("kanji")
        engine.convert()

        // 変換済み状態の確定は変換結果をそのまま返す (WYSIWYG)
        assertEquals("漢字", engine.commit())
        assertFalse(engine.hasComposition())
        assertFalse(engine.isConverted())
    }

    @Test
    fun deleteBackward_revertsConversionToKana() {
        val engine = RomaFlowEngine()
        engine.inputRomaji("kanji")
        engine.convert()

        // 変換済み状態の Backspace は変換を取り消し、かな表示へ戻す (文字は削らない)
        assertEquals("かんじ", engine.deleteBackward())
        assertFalse(engine.isConverted())
        assertTrue(engine.hasComposition())
    }

    @Test
    fun inputRomaji_clearsConvertedStateWhenTypingAfterConversion() {
        val engine = RomaFlowEngine()
        engine.inputRomaji("watasi")
        engine.convert()
        assertTrue(engine.isConverted())

        // 変換済み状態で追加入力が来たら変換フラグを解除する (commit は呼び出し側が先に行う想定)
        engine.inputRomaji("a")

        assertFalse(engine.isConverted())
    }

    @Test
    fun cancel_clearsConvertedState() {
        val engine = RomaFlowEngine()
        engine.inputRomaji("toukyou")
        engine.convert()

        engine.cancel()

        assertFalse(engine.hasComposition())
        assertFalse(engine.isConverted())
    }
}
