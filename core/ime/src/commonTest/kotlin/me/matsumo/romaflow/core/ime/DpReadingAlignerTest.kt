package me.matsumo.romaflow.core.ime

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [DpReadingAligner] の DP アライメント（誤字あり/なしでの range 付与・confidence 判定）を検証するテスト。
 */
class DpReadingAlignerTest {

    @Test
    fun align_singleExactToken_coversWholeInputWithFullConfidence() {
        val aligner = DpReadingAligner()
        val tokens = listOf(SegmentToken("漢字", "かんじ"))

        val aligned = aligner.align("かんじ", tokens)

        assertEquals(1, aligned.size)
        assertEquals(TextRange(0, 3, 1f), aligned[0].range)
    }

    @Test
    fun align_multipleExactTokens_assignsContiguousFullConfidenceRanges() {
        val aligner = DpReadingAligner()
        val tokens = listOf(
            SegmentToken("私", "わたし"),
            SegmentToken("天気", "てんき"),
        )

        val aligned = aligner.align("わたしてんき", tokens)

        assertEquals(TextRange(0, 3, 1f), aligned[0].range)
        assertEquals(TextRange(3, 6, 1f), aligned[1].range)
    }

    @Test
    fun align_substitutionTypo_keepsRangeButMarksLowConfidence() {
        val aligner = DpReadingAligner()

        // 打った通り「てんく」、補正後読み「てんき」。range は全体だが完全一致しないため confidence 0f。
        val aligned = aligner.align("てんく", listOf(SegmentToken("天気", "てんき")))

        assertEquals(1, aligned.size)
        assertEquals(TextRange(0, 3, 0f), aligned[0].range)
    }

    @Test
    fun align_inputShorterThanReading_stillCoversWholeInput() {
        val aligner = DpReadingAligner()

        // 打った通り「てき」（ん 抜け）、補正後読み「てんき」。range は readingInput 全体を覆う。
        val aligned = aligner.align("てき", listOf(SegmentToken("天気", "てんき")))

        assertEquals(TextRange(0, 2, 0f), aligned[0].range)
    }

    @Test
    fun align_inputHasExtraChar_attachesExtraToFollowingToken() {
        val aligner = DpReadingAligner()
        val tokens = listOf(
            SegmentToken("私", "わたし"),
            SegmentToken("天気", "てんき"),
        )

        // 打った通り「わたしいてんき」（い 余分）。先頭 token は完全一致、余分文字は後続 token 側へ寄せる。
        val aligned = aligner.align("わたしいてんき", tokens)

        assertEquals(TextRange(0, 3, 1f), aligned[0].range)
        assertEquals(TextRange(3, 7, 0f), aligned[1].range)
    }

    @Test
    fun align_emptyTokens_returnsEmpty() {
        val aligner = DpReadingAligner()

        assertEquals(emptyList(), aligner.align("かんじ", emptyList()))
    }
}
