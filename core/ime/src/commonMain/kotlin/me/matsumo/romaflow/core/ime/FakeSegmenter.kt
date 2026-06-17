package me.matsumo.romaflow.core.ime

/**
 * 決定的な [Segmenter] スタブ。
 *
 * 形態素解析を使わず、1 文字を 1 token として分割する。テスト・開発用で同じ入力には常に同じ結果を返す。
 * 読みは表層形をそのまま用いる（テストでは読みの正確さに依存しない）。
 */
internal class FakeSegmenter : Segmenter {

    override fun segment(text: String): List<SegmentToken> {
        return text.map(::toToken)
    }

    private fun toToken(character: Char): SegmentToken {
        val surface = character.toString()

        return SegmentToken(surface, surface)
    }
}
