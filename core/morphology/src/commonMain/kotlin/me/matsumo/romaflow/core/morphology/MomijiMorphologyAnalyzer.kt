package me.matsumo.romaflow.core.morphology

import io.github.tokuhirom.momiji.core.Node
import io.github.tokuhirom.momiji.ipadic.MomijiIpadicLoader

/**
 * momiji（Pure Kotlin の MeCab 互換解析器）と同梱 IPADIC を用いた形態素解析器。
 *
 * KMP の commonMain に置けるため macosArm64・Android の双方で同一実装を共有できるかを
 * 検証する PoC。LLM が出力した日本語テキストを分かち書きし、各形態素の読みを取り出す。
 */
class MomijiMorphologyAnalyzer {
    private val latticeBuilder = MomijiIpadicLoader().load()

    /**
     * [text] を形態素列へ分割する。BOS / EOS は除外し、語ノードのみを返す。
     */
    fun analyze(text: String): List<Morpheme> {
        val nodes = latticeBuilder.buildLattice(text).viterbi()

        return nodes.filterIsInstance<Node.Word>().map(::toMorpheme)
    }

    private fun toMorpheme(node: Node.Word): Morpheme {
        val features = node.dictRow?.feature?.split(FEATURE_DELIMITER).orEmpty()

        return Morpheme(
            surface = node.surface,
            reading = features.featureOrNull(FEATURE_INDEX_READING),
            partOfSpeech = features.featureOrNull(FEATURE_INDEX_PART_OF_SPEECH),
        )
    }

    private fun List<String>.featureOrNull(index: Int): String? {
        val value = getOrNull(index)

        return value?.takeUnless { it == FEATURE_EMPTY_MARK }
    }

    private companion object {
        /** IPADIC の feature 文字列の区切り文字。 */
        const val FEATURE_DELIMITER = ","

        /** feature 中で値なしを表すマーク。 */
        const val FEATURE_EMPTY_MARK = "*"

        /** feature 列における品詞の位置。 */
        const val FEATURE_INDEX_PART_OF_SPEECH = 0

        /** feature 列における読み（カタカナ）の位置。 */
        const val FEATURE_INDEX_READING = 7
    }
}
