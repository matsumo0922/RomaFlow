package me.matsumo.romaflow.core.ime

/**
 * 決定的な [ReadingAligner] スタブ。
 *
 * 誤字を想定せず、token の読み長ぶんだけ readingInput を先頭から順に割り当てる（最後の token は残り全部）。
 * confidence は readingInput[range] と token の読みが完全一致したときのみ 1f。テスト・開発用。
 */
internal class FakeAligner : ReadingAligner {

    override fun align(readingInput: String, tokens: List<SegmentToken>): List<AlignedSegment> {
        var cursor = 0

        return tokens.mapIndexed { index, token ->
            val start = cursor.coerceIn(0, readingInput.length)
            val rawEnd = if (index == tokens.lastIndex) readingInput.length else start + token.reading.length
            val end = rawEnd.coerceIn(start, readingInput.length)

            cursor = end

            toAlignedSegment(token, readingInput, start, end)
        }
    }

    private fun toAlignedSegment(
        token: SegmentToken,
        readingInput: String,
        start: Int,
        end: Int,
    ): AlignedSegment {
        val covered = readingInput.substring(start, end)
        val confidence = if (covered == token.reading) 1f else 0f

        return AlignedSegment(token, TextRange(start, end, confidence))
    }
}
