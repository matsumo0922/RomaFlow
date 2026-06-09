package me.matsumo.romaflow.core.ime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RomaFlowEngine の composition 状態を検証するテスト。
 */
class RomaFlowEngineTest {
    @Test
    fun inputTextAppendsRawText() {
        val engine = RomaFlowEngine()

        val compositionText = engine.inputText("abc")

        assertEquals("abc", compositionText)
        assertEquals("abc", engine.currentComposition())
        assertTrue(engine.hasComposition())
    }

    @Test
    fun deleteBackwardRemovesLastCharacter() {
        val engine = RomaFlowEngine()
        engine.inputText("abc")

        val compositionText = engine.deleteBackward()

        assertEquals("ab", compositionText)
        assertEquals("ab", engine.currentComposition())
        assertTrue(engine.hasComposition())
    }

    @Test
    fun deleteBackwardKeepsEmptyComposition() {
        val engine = RomaFlowEngine()

        val compositionText = engine.deleteBackward()

        assertEquals("", compositionText)
        assertFalse(engine.hasComposition())
    }

    @Test
    fun commitCompositionReturnsTextAndClearsBuffer() {
        val engine = RomaFlowEngine()
        engine.inputText("romaflow")

        val committedText = engine.commitComposition()

        assertEquals("romaflow", committedText)
        assertEquals("", engine.currentComposition())
        assertFalse(engine.hasComposition())
    }

    @Test
    fun clearCompositionDiscardsText() {
        val engine = RomaFlowEngine()
        engine.inputText("draft")

        val compositionText = engine.clearComposition()

        assertEquals("", compositionText)
        assertEquals("", engine.currentComposition())
        assertFalse(engine.hasComposition())
    }
}
