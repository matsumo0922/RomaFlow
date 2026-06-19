package me.matsumo.romaflow.core.morphology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真のグローバル N-best（A-2）の検証テスト。
 *
 * DFS 打ち切り近似（A-spike）から best-first 探索（A-2）への変更で、
 * EOS コストが large な経路が正しく後退することを確認する。
 *
 * シナリオ: EOS コストが経路間で大きく異なる場合に rank が正しくつくこと。
 */
class TrueNBestTest {

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
     * EOS コストが大きく異なる 3 経路の rank が正しくつくことを検証する。
     *
     * 経路 A: wcost=1, rcAttr=10, EOS=1000 → total=1+1000=1001
     * 経路 B: wcost=5, rcAttr=20, EOS=2 → total=5+2=7
     * 経路 C: wcost=10, rcAttr=30, EOS=1 → total=10+1=11
     *
     * 期待 rank: B（7） < C（11） < A（1001）
     */
    @Test
    fun trueNBestCorrectlyRanksPathsByTotalCostIncludingEos() {
        val lexemeA = lexeme("A", lcAttr = 0, rcAttr = 10, wcost = 1)
        val lexemeB = lexeme("B", lcAttr = 0, rcAttr = 20, wcost = 5)
        val lexemeC = lexeme("C", lcAttr = 0, rcAttr = 30, wcost = 10)

        val reading = "x"
        val lexicon = FakeReadingLexicon(
            matchMap = mapOf(
                (reading to 0) to listOf(
                    LexemeMatch(readingEndOffset = 1, lexeme = lexemeA),
                    LexemeMatch(readingEndOffset = 1, lexeme = lexemeB),
                    LexemeMatch(readingEndOffset = 1, lexeme = lexemeC),
                ),
            ),
        )

        val costProvider = FakeConnectionCostProvider(
            costMap = mapOf(
                Pair(10, 0) to 1000,
                Pair(20, 0) to 2,
                Pair(30, 0) to 1,
            ),
        )

        val paths = ReadingLatticeDecoder.nBest(reading, lexicon, costProvider, n = 3)

        println("TrueNBest rank test:")
        paths.forEachIndexed { index, (cost, path) ->
            println("  [$index] cost=$cost surfaces=${path.map { it.surface }}")
        }

        assertEquals(3, paths.size, "経路が 3 件あること")
        assertEquals(listOf("B"), paths[0].second.map { it.surface }, "rank 0 は B（total=7）")
        assertEquals(listOf("C"), paths[1].second.map { it.surface }, "rank 1 は C（total=11）")
        assertEquals(listOf("A"), paths[2].second.map { it.surface }, "rank 2 は A（total=1001）")

        assertTrue(paths[0].first < paths[1].first, "rank 0 < rank 1")
        assertTrue(paths[1].first < paths[2].first, "rank 1 < rank 2")
    }

    /**
     * 複数語の連接を持つ経路で EOS コスト逆転が起きるケースを検証する。
     *
     * 経路 P = [P1, P2]: total = 2 + 5 + 3 + 50 = 60 (BOS→P1=2, P1.wcost=5, P1→P2=3, P2.wcost=50, P2→EOS=0)
     * wait: 詳細は下記のコスト設定を参照。
     *
     * ここでは「単語経路」と「複合経路」の 2 つを使う:
     * - 単語経路 S: [S] wcost=3, rcAttr=99, EOS=100 → total=3+100=103
     * - 複合経路 M1+M2: [M1]wcost=2+[M2]wcost=4, rcAttr(M2)=88, EOS=1 → total=2+4+1=7
     */
    @Test
    fun trueNBestMultiWordPathBeatsHighEosSingleWordPath() {
        val lexemeSingle = lexeme("S", lcAttr = 0, rcAttr = 99, wcost = 3)
        val lexemeMulti1 = lexeme("M1", lcAttr = 0, rcAttr = 50, wcost = 2)
        val lexemeMulti2 = lexeme("M2", lcAttr = 50, rcAttr = 88, wcost = 4)

        val reading = "ab"
        val lexicon = FakeReadingLexicon(
            matchMap = mapOf(
                // 位置 0 から: 単語 S (長さ2) / M1 (長さ1)
                (reading to 0) to listOf(
                    LexemeMatch(readingEndOffset = 2, lexeme = lexemeSingle),
                    LexemeMatch(readingEndOffset = 1, lexeme = lexemeMulti1),
                ),
                // 位置 1 から: M2 (長さ1)
                (reading to 1) to listOf(
                    LexemeMatch(readingEndOffset = 2, lexeme = lexemeMulti2),
                ),
            ),
        )

        // M1→M2 連接コスト=0（同 rcAttr/lcAttr で一致）、EOS: S.rcAttr=99→100, M2.rcAttr=88→1
        val costProvider = FakeConnectionCostProvider(
            costMap = mapOf(
                Pair(99, 0) to 100,
                Pair(88, 0) to 1,
                Pair(50, 50) to 0,
            ),
        )

        val paths = ReadingLatticeDecoder.nBest(reading, lexicon, costProvider, n = 2)

        println("MultiWord vs SingleWord EOS test:")
        paths.forEachIndexed { index, (cost, path) ->
            println("  [$index] cost=$cost surfaces=${path.map { it.surface }}")
        }

        assertEquals(2, paths.size, "経路が 2 件あること")

        // 複合経路 [M1, M2]: BOS→M1(lcAttr=0)=0, M1.wcost=2, M1→M2(lcAttr=50,rcAttr=50)=0, M2.wcost=4, M2→EOS(rcAttr=88)=1 = 7
        // 単語経路 [S]: BOS→S(lcAttr=0)=0, S.wcost=3, S→EOS(rcAttr=99)=100 = 103
        val multiWordSurfaces = paths[0].second.map { it.surface }
        assertEquals(listOf("M1", "M2"), multiWordSurfaces, "rank 0 は複合経路 [M1, M2]（total=7）")
        assertEquals(listOf("S"), paths[1].second.map { it.surface }, "rank 1 は単語経路 [S]（total=103）")
    }
}
