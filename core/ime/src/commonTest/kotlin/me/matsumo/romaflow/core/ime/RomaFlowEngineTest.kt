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
    fun inputRomaji_convertsPunctuation() {
        val engine = RomaFlowEngine()

        assertEquals("。", engine.inputRomaji("."))
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
    fun commit_returnsKanaAndClearsBuffer() {
        val engine = RomaFlowEngine()
        engine.inputRomaji("nihon")

        assertEquals("にほん", engine.commit())
        assertFalse(engine.hasComposition())
        assertEquals("あ", engine.inputRomaji("a"))
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
}
