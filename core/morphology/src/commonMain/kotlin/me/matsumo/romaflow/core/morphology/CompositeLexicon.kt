package me.matsumo.romaflow.core.morphology

/**
 * 複数の [ReadingLexicon] を結合し、全マッチを統合して返す [ReadingLexicon]。
 *
 * primary（辞書語）の結果が空の位置でも [fallback]（literal/OOV arc 等）が補完するため、
 * 辞書外 1 文字によるデコード経路の消失（デッドロック）を防ぐ。
 *
 * 結果順: primary の結果を先に、次に fallback の結果を返す。
 */
internal class CompositeLexicon(
    private val primary: ReadingLexicon,
    private val fallback: ReadingLexicon,
) : ReadingLexicon {

    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> {
        val primaryMatches = primary.commonPrefixSearch(reading, startOffset)
        val fallbackMatches = fallback.commonPrefixSearch(reading, startOffset)

        return primaryMatches + fallbackMatches
    }
}
