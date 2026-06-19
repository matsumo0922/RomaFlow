package me.matsumo.romaflow.core.morphology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 負の連接コストが存在する場合のグローバル N-best 正確性 regression テスト。
 *
 * PR レビューで指摘された反例を再現する:
 * - Path A: 1語で complete、低 wcost だが高 EOS コスト
 * - Path B: B1 の partial cost が高いが B1→B2 の連接コストが大きく負で complete cost が最小
 *
 * Dijkstra 型（h=0 の best-first）は B の partial cost（B1 後）が A の complete cost より高い
 * 場合に A を先に確定してしまう。A*（h=最小 suffix コスト）は B の complete cost を
 * 下界で評価するため、A を確定する前に B を先に pop できる。
 *
 * 具体的な数値（レビューの反例):
 * - A: wcost=5, EOS cost=0 → total=5
 * - B1: wcost=10 （partial cost=10 > A complete cost 時点の f=5 では A が先に確定されそう）
 *   B1→B2 連接コスト = -30
 *   B2: wcost=5, EOS cost=0 → total=10+(-30)+5=−15
 *
 * A の total(5) > B の total(-15) なので rank-0 は B=[B1,B2] でなければならない。
 *
 * 接続コストが符号付き Short 由来（実データ: BOS→BOS = -434 等）であることを踏まえ、
 * 整合性テストとして負コスト状況での正しさを確認する。
 */
class NegativeCostNBestRegressionTest {

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

    private class FakeReadingLexicon(
        private val matchMap: Map<Pair<String, Int>, List<LexemeMatch>>,
    ) : ReadingLexicon {
        override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> =
            matchMap[reading to startOffset] ?: emptyList()
    }

    private class FakeConnectionCostProvider(
        private val costMap: Map<Pair<Int, Int>, Int>,
    ) : ConnectionCostProvider {
        override fun transitionCost(previousRightContextId: Int, nextLeftContextId: Int): Int =
            costMap[previousRightContextId to nextLeftContextId] ?: 0
    }

    /**
     * レビュー反例: 部分コストが大きいが負の連接コストで完全コストが最小になる経路が rank-0 になること。
     *
     * コスト内訳:
     * - 経路 A: BOS→A(lcAttr=1)=0, A.wcost=5, A→EOS(rcAttr=2)=0 → total=5
     * - 経路 B: BOS→B1(lcAttr=3)=0, B1.wcost=10, B1→B2(rcAttr=4→lcAttr=5)=-30,
     *           B2.wcost=5, B2→EOS(rcAttr=6)=0 → total=10+(-30)+5=-15
     *
     * h=0 の Dijkstra 型: B1 の partial cost=10 > A の complete cost=5 なので
     * A を先に確定 → rank-0 が A になってしまう（バグ）。
     *
     * h=min suffix: B1 の f = g(10) + h(B1 suffix: -30+5+0=-25) = 10-25=-15。
     * A の f = g(5) + h(A suffix: 0) = 5。-15 < 5 なので B が先に pop される → rank-0 が B（正解）。
     */
    @Test
    fun negativeLinkCostPathBecomesRank0WhenCompleteCostIsSmallest() {
        val lexemeA = lexeme("A", lcAttr = 1, rcAttr = 2, wcost = 5)
        val lexemeB1 = lexeme("B1", lcAttr = 3, rcAttr = 4, wcost = 10)
        val lexemeB2 = lexeme("B2", lcAttr = 5, rcAttr = 6, wcost = 5)

        // 読み "xy"（2 文字）に対し、A は全体（長さ2）、B1+B2 はそれぞれ 1 文字
        val reading = "xy"
        val lexicon = FakeReadingLexicon(
            matchMap = mapOf(
                // 位置 0 から: A（長さ2）、B1（長さ1）
                (reading to 0) to listOf(
                    LexemeMatch(readingEndOffset = 2, lexeme = lexemeA),
                    LexemeMatch(readingEndOffset = 1, lexeme = lexemeB1),
                ),
                // 位置 1 から: B2（長さ1）
                (reading to 1) to listOf(
                    LexemeMatch(readingEndOffset = 2, lexeme = lexemeB2),
                ),
            ),
        )

        // 重要: B1(rcAttr=4) → B2(lcAttr=5) の連接コスト = -30（負の値）
        val costProvider = FakeConnectionCostProvider(
            costMap = mapOf(
                Pair(4, 5) to -30,
            ),
        )

        val paths = ReadingLatticeDecoder.nBest(reading, lexicon, costProvider, n = 2)

        println("Negative connection cost regression test:")
        paths.forEachIndexed { index, (cost, path) ->
            println("  [$index] cost=$cost surfaces=${path.map { it.surface }}")
        }

        assertEquals(2, paths.size, "経路が 2 件あること")

        // B の total: 0 + 10 + (-30) + 5 + 0(EOS) = -15
        // A の total: 0 + 5 + 0(EOS) = 5
        // rank-0 は B=[B1,B2]（-15 < 5）
        assertEquals(
            listOf("B1", "B2"),
            paths[0].second.map { it.surface },
            "rank-0 は負コスト連接で complete cost が最小の B=[B1,B2]",
        )
        assertEquals(
            listOf("A"),
            paths[1].second.map { it.surface },
            "rank-1 は A",
        )

        assertEquals(-15L, paths[0].first, "B の total cost = -15")
        assertEquals(5L, paths[1].first, "A の total cost = 5")

        assertTrue(paths[0].first < paths[1].first, "rank-0 のコスト < rank-1 のコスト")
    }

    /**
     * BOS 連接コストが負の場合（実データに近い状況）でも N-best が正しく動くこと。
     *
     * BOS → 先頭語 の連接コストが負のとき（実データ: BOS→BOS=-434 に相当）、
     * その語を含む経路の g(prefix cost) が負になる。
     * A* は g + h で正しく評価するため順位を誤らない。
     */
    @Test
    fun negativeBosConnectionCostIsHandledCorrectly() {
        val lexemeX = lexeme("X", lcAttr = 10, rcAttr = 11, wcost = 100)
        val lexemeY = lexeme("Y", lcAttr = 20, rcAttr = 21, wcost = 3)

        val reading = "a"
        val lexicon = FakeReadingLexicon(
            matchMap = mapOf(
                (reading to 0) to listOf(
                    LexemeMatch(readingEndOffset = 1, lexeme = lexemeX),
                    LexemeMatch(readingEndOffset = 1, lexeme = lexemeY),
                ),
            ),
        )

        // BOS(0) → X.lcAttr(10) 連接コスト = -200（負）
        // BOS(0) → Y.lcAttr(20) 連接コスト = 0
        // X: g = -200 + 100 = -100, EOS=0, total=-100
        // Y: g = 0 + 3 = 3, EOS=0, total=3
        // rank-0 は X（-100 < 3）
        val costProvider = FakeConnectionCostProvider(
            costMap = mapOf(
                Pair(0, 10) to -200,
            ),
        )

        val paths = ReadingLatticeDecoder.nBest(reading, lexicon, costProvider, n = 2)

        println("Negative BOS connection cost test:")
        paths.forEachIndexed { index, (cost, path) ->
            println("  [$index] cost=$cost surfaces=${path.map { it.surface }}")
        }

        assertEquals(2, paths.size, "経路が 2 件あること")
        assertEquals(listOf("X"), paths[0].second.map { it.surface }, "rank-0 は X（BOS→X 負コストで total=-100）")
        assertEquals(-100L, paths[0].first, "X の total cost = -100")
    }
}
