package me.matsumo.romaflow.core.morphology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * cross-hypothesis 比較（A-2）の検証テスト。
 *
 * 複数の [ReadingHypothesis]（無修正 path と提案読み）を横断して argmin を選択する仕組みと、
 * タイピングコストによる競合を検証する。
 */
class CrossHypothesisDecoderTest {

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
        private val costMap: Map<Pair<Int, Int>, Int> = emptyMap(),
    ) : ConnectionCostProvider {
        override fun transitionCost(previousRightContextId: Int, nextLeftContextId: Int): Int =
            costMap[previousRightContextId to nextLeftContextId] ?: 0
    }

    /**
     * タイピングコスト 0 の無修正 path と正の typingCost を持つ提案読みで、
     * 無修正が勝つケースを検証する。
     */
    @Test
    fun selectBestPrefersUnmodifiedPathWhenDecodeCostDifferenceDominates() {
        // 無修正読み "てんき" → 辞書語 "天気"（wcost=10）
        val lexemeTenki = lexeme("天気", lcAttr = 0, rcAttr = 0, wcost = 10)
        // 提案読み "てんき" → 辞書語 "転機"（wcost=50）
        val lexemeTenki2 = lexeme("転機", lcAttr = 0, rcAttr = 0, wcost = 50)

        val lexicon = FakeReadingLexicon(
            matchMap = mapOf(
                ("てんき" to 0) to listOf(
                    LexemeMatch(readingEndOffset = 3, lexeme = lexemeTenki),
                ),
                ("てんき2" to 0) to listOf(
                    LexemeMatch(readingEndOffset = 4, lexeme = lexemeTenki2),
                ),
            ),
        )
        val costProvider = FakeConnectionCostProvider()

        // 無修正: typingCost=0, decode=10, total=10
        // 提案読み: typingCost=5, decode=50, total=55
        val hypotheses = listOf(
            ReadingHypothesis(reading = "てんき", typingCost = 0),
            ReadingHypothesis(reading = "てんき2", typingCost = 5),
        )

        val result = CrossHypothesisDecoder.selectBest(hypotheses, lexicon, costProvider)

        assertNotNull(result, "結果が存在すること")
        assertEquals("てんき", result.hypothesis.reading, "無修正 path が選ばれること")
        assertEquals(listOf("天気"), result.path.map { it.surface }, "天気 が選ばれること")
    }

    /**
     * 提案読みが無修正よりも大幅に低コストで、typingCost を加えても勝つケース。
     */
    @Test
    fun selectBestPicksProposedReadingWhenDecodeSavingsExceedTypingCost() {
        // 無修正読み "a" → 辞書語 X（wcost=100）
        val lexemeUnmodified = lexeme("X", lcAttr = 0, rcAttr = 0, wcost = 100)
        // 提案読み "b" → 辞書語 Y（wcost=5）
        val lexemeProposed = lexeme("Y", lcAttr = 0, rcAttr = 0, wcost = 5)

        val lexicon = FakeReadingLexicon(
            matchMap = mapOf(
                ("a" to 0) to listOf(LexemeMatch(readingEndOffset = 1, lexeme = lexemeUnmodified)),
                ("b" to 0) to listOf(LexemeMatch(readingEndOffset = 1, lexeme = lexemeProposed)),
            ),
        )
        val costProvider = FakeConnectionCostProvider()

        // 無修正: typingCost=0, decode=100, total=100
        // 提案: typingCost=10, decode=5, total=15
        val hypotheses = listOf(
            ReadingHypothesis(reading = "a", typingCost = 0),
            ReadingHypothesis(reading = "b", typingCost = 10),
        )

        val result = CrossHypothesisDecoder.selectBest(hypotheses, lexicon, costProvider)

        assertNotNull(result, "結果が存在すること")
        assertEquals("b", result.hypothesis.reading, "提案読み が選ばれること")
        assertEquals(listOf("Y"), result.path.map { it.surface }, "Y が選ばれること")
        assertEquals(15L, result.totalCost, "総コスト = decode(5) + typing(10) = 15")
    }

    /**
     * デコードできない仮説は除外され、デコードできる仮説が選ばれること。
     */
    @Test
    fun selectBestSkipsHypothesesWithNoDecodablePath() {
        val lexemeValid = lexeme("Valid", lcAttr = 0, rcAttr = 0, wcost = 20)
        val lexicon = FakeReadingLexicon(
            matchMap = mapOf(
                ("valid" to 0) to listOf(LexemeMatch(readingEndOffset = 5, lexeme = lexemeValid)),
                // "empty" には辞書語なし → デコード失敗
            ),
        )
        val costProvider = FakeConnectionCostProvider()

        val hypotheses = listOf(
            ReadingHypothesis(reading = "empty", typingCost = 0),
            ReadingHypothesis(reading = "valid", typingCost = 0),
        )

        val result = CrossHypothesisDecoder.selectBest(hypotheses, lexicon, costProvider)

        assertNotNull(result, "デコード可能な仮説が選ばれること")
        assertEquals("valid", result.hypothesis.reading)
    }

    /**
     * 全仮説がデコード失敗の場合は null を返すこと。
     */
    @Test
    fun selectBestReturnsNullWhenAllHypothesesFail() {
        val emptyLexicon = object : ReadingLexicon {
            override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> =
                emptyList()
        }
        val costProvider = FakeConnectionCostProvider()

        val hypotheses = listOf(
            ReadingHypothesis(reading = "no-match", typingCost = 0),
        )

        val result = CrossHypothesisDecoder.selectBest(hypotheses, emptyLexicon, costProvider)

        assertNull(result, "全仮説がデコード失敗のとき null を返すこと")
    }

    /**
     * [rankAllCandidates] が複数仮説を横断して cost 昇順に統合すること。
     */
    @Test
    fun rankAllCandidatesMergesHypothesesInCostOrder() {
        val lexemeA = lexeme("A", lcAttr = 0, rcAttr = 0, wcost = 1)
        val lexemeB = lexeme("B", lcAttr = 0, rcAttr = 0, wcost = 10)
        val lexemeC = lexeme("C", lcAttr = 0, rcAttr = 0, wcost = 3)

        val lexicon = FakeReadingLexicon(
            matchMap = mapOf(
                ("h1" to 0) to listOf(
                    LexemeMatch(readingEndOffset = 2, lexeme = lexemeA),
                    LexemeMatch(readingEndOffset = 2, lexeme = lexemeB),
                ),
                ("h2" to 0) to listOf(
                    LexemeMatch(readingEndOffset = 2, lexeme = lexemeC),
                ),
            ),
        )
        val costProvider = FakeConnectionCostProvider()

        // h1: typingCost=0, A→total=1, B→total=10
        // h2: typingCost=5, C→total=3+5=8
        val hypotheses = listOf(
            ReadingHypothesis(reading = "h1", typingCost = 0),
            ReadingHypothesis(reading = "h2", typingCost = 5),
        )

        val ranked = CrossHypothesisDecoder.rankAllCandidates(hypotheses, lexicon, costProvider, n = 3)

        println("rankAllCandidates result:")
        ranked.forEachIndexed { index, (cost, hyp, path) ->
            println("  [$index] cost=$cost reading=${hyp.reading} surfaces=${path.map { it.surface }}")
        }

        assertEquals(3, ranked.size, "候補が 3 件あること")

        // rank 0: A（total=1）、rank 1: C（total=8）、rank 2: B（total=10）
        assertEquals(listOf("A"), ranked[0].third.map { it.surface }, "rank 0 は A（total=1）")
        assertEquals(listOf("C"), ranked[1].third.map { it.surface }, "rank 1 は C（total=8）")
        assertEquals(listOf("B"), ranked[2].third.map { it.surface }, "rank 2 は B（total=10）")

        assertTrue(ranked[0].first <= ranked[1].first, "rank 0 ≤ rank 1 のコスト")
        assertTrue(ranked[1].first <= ranked[2].first, "rank 1 ≤ rank 2 のコスト")
    }
}
