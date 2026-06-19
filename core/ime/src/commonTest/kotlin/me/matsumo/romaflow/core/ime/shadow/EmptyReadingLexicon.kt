package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.morphology.LexemeMatch
import me.matsumo.romaflow.core.morphology.ReadingLexicon

/**
 * テスト用の空 [ReadingLexicon]。マッチを返さない。
 *
 * 辞書なし状態で [ShadowCompositionEngine] や [CompositionState] のふるまいを検証するために使う。
 */
internal object EmptyReadingLexicon : ReadingLexicon {
    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> = emptyList()
}
