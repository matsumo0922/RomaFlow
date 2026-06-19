package me.matsumo.romaflow.core.morphology

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * EOS 連接コストが Viterbi / N-best の順位に正しく反映されることを検証する回帰テスト。
 *
 * fake [ReadingLexicon] と fake [ConnectionCostProvider] を使い、
 * 「EOS コストだけで rank が反転する」最小ケースを組む。
 * EOS を加算しない実装ではこのテストが落ちる。
 *
 * シナリオ:
 * - 読み「ab」に対し、2 つの経路が存在する。
 *   - 経路 A: 語 A（wcost=10, rcAttr=1）のみ（単語経路）
 *   - 経路 B: 語 B1（wcost=5, rcAttr=2）+ 語 B2（wcost=3, rcAttr=3）（複合経路）
 * - 語→語連接コストはゼロ。
 * - EOS 連接コスト: rcAttr=1 → EOS = 100（A に不利）、rcAttr=3 → EOS = 1（B に有利）。
 * - EOS なし: A コスト=10、B コスト=8 → B が rank 0。
 * - EOS あり: A コスト=10+100=110、B コスト=8+1=9 → B が rank 0（差が拡大、順位は同じだが値が正しい）。
 * - さらに EOS なしでは A=10 < B=8 が逆転して A が rank 0 になるよう wcost を調整した版でも検証。
 *
 * 逆転ケース:
 * - 経路 X: 語 X（wcost=3, rcAttr=10）のみ
 * - 経路 Y: 語 Y（wcost=5, rcAttr=20）のみ
 * - 語→語連接コストはゼロ。
 * - EOS コスト: rcAttr=10 → EOS = 100、rcAttr=20 → EOS = 1。
 * - EOS なし: X=3 < Y=5 → X が rank 0。
 * - EOS あり: X=3+100=103 > Y=5+1=6 → Y が rank 0（rank が逆転）。
 */
class ReadingLatticeDecoderEosCostTest {

    // ---- fake lexicon ----

    /**
     * 固定の reading→LexemeMatch マップを返す fake [ReadingLexicon]。
     *
     * テスト用に任意の辞書構造を組める最小実装。
     */
    private class FakeReadingLexicon(
        private val matchMap: Map<Pair<String, Int>, List<LexemeMatch>>,
    ) : ReadingLexicon {

        override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> =
            matchMap[reading to startOffset] ?: emptyList()
    }

    // ---- fake cost provider ----

    /**
     * 固定の (rcAttr, lcAttr) → cost マップを返す fake [ConnectionCostProvider]。
     *
     * マップに存在しない組み合わせは 0 を返す。
     */
    private class FakeConnectionCostProvider(
        private val costMap: Map<Pair<Int, Int>, Int>,
    ) : ConnectionCostProvider {

        override fun transitionCost(previousRightContextId: Int, nextLeftContextId: Int): Int =
            costMap[previousRightContextId to nextLeftContextId] ?: 0
    }

    // ---- helpers ----

    /**
     * テスト用 [LexemeEntry] を生成するファクトリ。
     */
    private fun lexeme(
        surface: String,
        lcAttr: Int,
        rcAttr: Int,
        wcost: Int,
    ): LexemeEntry = LexemeEntry(
        surface = surface,
        reading = surface,
        lcAttr = lcAttr,
        rcAttr = rcAttr,
        posId = 0,
        wcost = wcost,
    )

    // ---- テストケース ----

    /**
     * EOS コストにより N-best rank が逆転するケースを検証する。
     *
     * EOS コストを含まない実装では X（wcost=3）が rank 0 になるが、
     * EOS コストを含む正しい実装では Y（wcost=5 + EOS=1 = 6）が rank 0 になる。
     */
    @Test
    fun nBestRankIsReversedByEosCost() {
        // 語 X: rcAttr=10、EOS コスト=100
        val lexemeX = lexeme(surface = "X", lcAttr = 0, rcAttr = 10, wcost = 3)
        // 語 Y: rcAttr=20、EOS コスト=1
        val lexemeY = lexeme(surface = "Y", lcAttr = 0, rcAttr = 20, wcost = 5)

        // 読み "あ"（1 文字）に対して X と Y が並立するシナリオ
        val reading = "あ"
        val lexicon = FakeReadingLexicon(
            matchMap = mapOf(
                (reading to 0) to listOf(
                    LexemeMatch(readingEndOffset = 1, lexeme = lexemeX),
                    LexemeMatch(readingEndOffset = 1, lexeme = lexemeY),
                ),
            ),
        )

        // BOS→X lcAttr=0: cost=0、BOS→Y lcAttr=0: cost=0
        // EOS: X rcAttr=10 → EOS = 100、Y rcAttr=20 → EOS = 1
        val costProvider = FakeConnectionCostProvider(
            costMap = mapOf(
                Pair(10, 0) to 100,
                Pair(20, 0) to 1,
            ),
        )

        val paths = ReadingLatticeDecoder.nBest(
            reading = reading,
            lexicon = lexicon,
            costProvider = costProvider,
            n = 2,
        )

        println("EOS rank reversal test paths:")
        paths.forEachIndexed { index, (cost, path) ->
            println("  [$index] cost=$cost surfaces=${path.map { it.surface }}")
        }

        // EOS あり: X=3+100=103、Y=5+1=6 → Y が rank 0
        assertEquals(2, paths.size, "経路が 2 件あること")
        assertEquals(listOf("Y"), paths[0].second.map { it.surface }, "rank 0 は Y（EOS コスト込みで最小）")
        assertEquals(listOf("X"), paths[1].second.map { it.surface }, "rank 1 は X（EOS コスト込みで高コスト）")
    }

    /**
     * EOS コストが N-best の累積コスト値に加算されていることを検証する。
     *
     * wcost=10、BOS→語 連接=2、語→EOS 連接=7 のとき、
     * 総コスト = 2 + 10 + 7 = 19 であること。
     */
    @Test
    fun nBestCumulativeCostIncludesEosCost() {
        val lexemeA = lexeme(surface = "A", lcAttr = 5, rcAttr = 8, wcost = 10)

        val reading = "い"
        val lexicon = FakeReadingLexicon(
            matchMap = mapOf(
                (reading to 0) to listOf(
                    LexemeMatch(readingEndOffset = 1, lexeme = lexemeA),
                ),
            ),
        )

        // BOS(0) → A lcAttr=5: cost=2、A rcAttr=8 → EOS(0): cost=7
        val costProvider = FakeConnectionCostProvider(
            costMap = mapOf(
                Pair(0, 5) to 2,
                Pair(8, 0) to 7,
            ),
        )

        val paths = ReadingLatticeDecoder.nBest(
            reading = reading,
            lexicon = lexicon,
            costProvider = costProvider,
            n = 1,
        )

        println("EOS cumulative cost test paths:")
        paths.forEachIndexed { index, (cost, path) ->
            println("  [$index] cost=$cost surfaces=${path.map { it.surface }}")
        }

        assertEquals(1, paths.size, "経路が 1 件あること")

        val expectedTotalCost = 2L + 10L + 7L
        assertEquals(expectedTotalCost, paths[0].first, "総コストが BOS連接 + wcost + EOS連接 の合計であること")
    }
}
