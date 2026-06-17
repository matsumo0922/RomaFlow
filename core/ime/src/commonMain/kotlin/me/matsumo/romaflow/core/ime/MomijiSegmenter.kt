package me.matsumo.romaflow.core.ime

import me.matsumo.romaflow.core.morphology.MomijiMorphologyAnalyzer
import me.matsumo.romaflow.core.morphology.Morpheme

/**
 * momiji の [MomijiMorphologyAnalyzer] をラップした [Segmenter]。
 *
 * 形態素の読み（IPADIC はカタカナ）はひらがなへ正規化する。読みを持たない未知語は表層形をそのまま読みとする。
 */
internal class MomijiSegmenter(
    private val analyzer: MomijiMorphologyAnalyzer = MomijiMorphologyAnalyzer(),
) : Segmenter {

    override fun segment(text: String): List<SegmentToken> {
        val morphemes = analyzer.analyze(text)

        return morphemes.map(::toToken)
    }

    private fun toToken(morpheme: Morpheme): SegmentToken {
        val reading = morpheme.reading?.let(::katakanaToHiragana) ?: morpheme.surface

        return SegmentToken(morpheme.surface, reading)
    }

    private fun katakanaToHiragana(katakana: String): String {
        val builder = StringBuilder(katakana.length)

        for (character in katakana) {
            builder.append(toHiraganaChar(character))
        }

        return builder.toString()
    }

    private fun toHiraganaChar(character: Char): Char {
        val codePoint = character.code

        if (codePoint in KATAKANA_BLOCK_START..KATAKANA_BLOCK_END) {
            return (codePoint - HIRAGANA_TO_KATAKANA_OFFSET).toChar()
        }

        return character
    }

    private companion object {
        /** カタカナブロックの開始コードポイント（U+30A1 ァ）。 */
        const val KATAKANA_BLOCK_START = 0x30A1

        /** カタカナブロックの終了コードポイント（U+30F6 ヶ）。 */
        const val KATAKANA_BLOCK_END = 0x30F6

        /** ひらがなからカタカナへのコードポイント差分。 */
        const val HIRAGANA_TO_KATAKANA_OFFSET = 0x60
    }
}
