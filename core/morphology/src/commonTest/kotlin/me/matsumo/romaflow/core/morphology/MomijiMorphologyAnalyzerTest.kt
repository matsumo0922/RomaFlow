package me.matsumo.romaflow.core.morphology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MomijiMorphologyAnalyzerTest {

    private val analyzer = MomijiMorphologyAnalyzer()

    @Test
    fun analyze_splitsJapaneseTextIntoMorphemes() {
        val morphemes = analyzer.analyze("今日は良い天気です")

        assertTrue(morphemes.isNotEmpty(), "形態素が 1 つ以上得られること")
    }

    @Test
    fun analyze_preservesOriginalTextWhenSurfacesAreConcatenated() {
        val text = "今日は良い天気です"

        val morphemes = analyzer.analyze(text)
        val concatenated = morphemes.joinToString(separator = "") { it.surface }

        assertEquals(text, concatenated, "表層形を連結すると入力テキストに一致すること")
    }

    @Test
    fun analyze_extractsReadingForKnownWord() {
        val morphemes = analyzer.analyze("日本語")
        val hasReading = morphemes.any { it.reading != null }

        assertTrue(hasReading, "既知語から読みが取得できること")
    }
}
