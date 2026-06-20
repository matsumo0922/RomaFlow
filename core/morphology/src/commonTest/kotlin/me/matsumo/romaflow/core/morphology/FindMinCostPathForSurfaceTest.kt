package me.matsumo.romaflow.core.morphology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [ReadingLatticeDecoder.findMinCostPathForSurface] の正しさ検証テスト。
 *
 * DP 状態が (readingOffset, surfaceOffset, rcAttr) の3要素であることを確認する。
 * 特に「prefix cost が低くても rcAttr が違って下流連接コストが大きい」経路を
 * 誤って採用しないことを反例テスト [rcAttrAwareArgminSelectsBestPath] で検証する。
 */
class FindMinCostPathForSurfaceTest {

    // region: rcAttr Markov 正しさ（人間レビューの反例）

    /**
     * rcAttr 違いの経路で真の最小コストを選べることを確認する（人間レビュー反例の再現）。
     *
     * ## セットアップ
     * ```
     * reading="ab", surface="XY"
     * A: surface"X" reading"a" lc=0 rc=1 wcost=0
     * B: surface"X" reading"a" lc=0 rc=2 wcost=10
     * C: surface"Y" reading"b" lc=3 rc=0 wcost=0
     * transitionCost(1,3)=+1000, transitionCost(2,3)=-1000, それ以外=0
     * ```
     *
     * ## 真の最小コスト
     * - A,C: 0 + transitionCost(1,3) + 0 + transitionCost(0,0) = 1000
     * - B,C: 10 + transitionCost(2,3) + 0 + transitionCost(0,0) = 10 + (-1000) = -990
     * よって B,C = -990 が最小。
     *
     * ## 旧実装（rcAttr なし）が誤る理由
     * (readingOffset=1, surfaceOffset=1) で A（prefix cost 0）を残し B（prefix cost 10）を捨てる。
     * 結果 A,C = 1000 を返し、真の最小 -990 を取りこぼす。
     */
    @Test
    fun rcAttrAwareArgminSelectsBestPath() {
        val lexemeA = LexemeEntry(
            surface = "X",
            reading = "ア",
            lcAttr = 0,
            rcAttr = 1,
            posId = 0,
            wcost = 0,
        )
        val lexemeB = LexemeEntry(
            surface = "X",
            reading = "ア",
            lcAttr = 0,
            rcAttr = 2,
            posId = 0,
            wcost = 10,
        )
        val lexemeC = LexemeEntry(
            surface = "Y",
            reading = "イ",
            lcAttr = 3,
            rcAttr = 0,
            posId = 0,
            wcost = 0,
        )

        val lexicon = TwoPositionLexicon(
            atOffset0 = listOf(lexemeA, lexemeB),
            atOffset1 = listOf(lexemeC),
        )

        val costProvider = SelectiveCostProvider(
            overrides = mapOf(
                (1 to 3) to 1000,
                (2 to 3) to -1000,
            ),
        )

        val result = ReadingLatticeDecoder.findMinCostPathForSurface(
            reading = "ab",
            surface = "XY",
            lexicon = lexicon,
            costProvider = costProvider,
        )

        assertNotNull(result, "経路が見つかること")

        val (totalCost, path) = result

        assertEquals(-990L, totalCost, "真の最小コスト -990 が返ること（旧実装は 1000 を返して失敗する）")
        assertEquals(2, path.size, "経路が 2 lexeme であること")

        // B,C の経路を確認: 先頭 lexeme は rc==2（B）か wcost==10（B）
        val firstLexeme = path[0]

        assertEquals(2, firstLexeme.rcAttr, "先頭 lexeme が B（rc=2）であること")
        assertEquals(10, firstLexeme.wcost, "先頭 lexeme が B（wcost=10）であること")
    }

    // endregion

    // region: 基本動作

    /**
     * 単一 lexeme で reading と surface が完全一致する場合に正しく経路を返すことを確認する。
     */
    @Test
    fun singleLexemeExactMatchReturnsPath() {
        val lexeme = buildLexeme(surface = "天気", reading = "テンキ", wcost = 100)
        val lexicon = SingleLexemeAtOffsetZeroLexicon(reading = "てんき", lexeme = lexeme)

        val result = ReadingLatticeDecoder.findMinCostPathForSurface(
            reading = "てんき",
            surface = "天気",
            lexicon = lexicon,
            costProvider = ZeroConnectionCostProvider,
        )

        assertNotNull(result, "経路が見つかること")

        val (_, path) = result

        assertEquals(1, path.size, "経路が 1 lexeme であること")
        assertEquals("天気", path[0].surface, "surface が '天気' であること")
    }

    /**
     * surface が格子上に存在しない場合に null を返すことを確認する。
     */
    @Test
    fun surfaceNotInLatticeReturnsNull() {
        val lexeme = buildLexeme(surface = "天気", reading = "テンキ", wcost = 100)
        val lexicon = SingleLexemeAtOffsetZeroLexicon(reading = "てんき", lexeme = lexeme)

        val result = ReadingLatticeDecoder.findMinCostPathForSurface(
            reading = "てんき",
            surface = "転機", // 格子上に存在しない
            lexicon = lexicon,
            costProvider = ZeroConnectionCostProvider,
        )

        assertNull(result, "格子上に存在しない surface は null を返すこと")
    }

    /**
     * reading が空の場合に null を返すことを確認する。
     */
    @Test
    fun emptyReadingReturnsNull() {
        val result = ReadingLatticeDecoder.findMinCostPathForSurface(
            reading = "",
            surface = "天気",
            lexicon = SingleLexemeAtOffsetZeroLexicon("てんき", buildLexeme("天気", "テンキ", 100)),
            costProvider = ZeroConnectionCostProvider,
        )

        assertNull(result, "空 reading は null を返すこと")
    }

    // endregion

    // region: helper

    private fun buildLexeme(surface: String, reading: String, wcost: Int): LexemeEntry {
        return LexemeEntry(
            surface = surface,
            reading = reading,
            lcAttr = 0,
            rcAttr = 0,
            posId = 0,
            wcost = wcost,
        )
    }

    // endregion
}

/**
 * テスト用 [ReadingLexicon]: 位置 0 では [atOffset0] を、位置 1 では [atOffset1] を返す。
 *
 * [FindMinCostPathForSurfaceTest.rcAttrAwareArgminSelectsBestPath] のために各位置で
 * 異なる lexeme セットを返す単純な2位置スタブ。
 */
private class TwoPositionLexicon(
    private val atOffset0: List<LexemeEntry>,
    private val atOffset1: List<LexemeEntry>,
) : ReadingLexicon {

    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> {
        val lexemes = when (startOffset) {
            0 -> atOffset0
            1 -> atOffset1
            else -> emptyList()
        }

        return lexemes.map { lexeme ->
            LexemeMatch(
                readingEndOffset = startOffset + 1,
                lexeme = lexeme,
            )
        }
    }
}

/**
 * テスト用 [ReadingLexicon]: [reading] に一致するときにのみ [lexeme] 1件を返す。
 *
 * startOffset=0 のときのみマッチし、reading 全体と一致するケースを扱う。
 */
private class SingleLexemeAtOffsetZeroLexicon(
    private val reading: String,
    private val lexeme: LexemeEntry,
) : ReadingLexicon {

    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> {
        if (startOffset != 0) return emptyList()

        val endOffset = this.reading.length

        if (endOffset > reading.length) return emptyList()

        val slice = reading.substring(0, endOffset)

        if (slice != this.reading) return emptyList()

        return listOf(LexemeMatch(readingEndOffset = endOffset, lexeme = lexeme))
    }
}

/**
 * テスト用 [ConnectionCostProvider]: [overrides] で指定した (lc, rc) ペアのコストを返し、
 * それ以外は 0 を返す。
 */
private class SelectiveCostProvider(
    private val overrides: Map<Pair<Int, Int>, Int>,
) : ConnectionCostProvider {

    override fun transitionCost(leftContextId: Int, rightContextId: Int): Int {
        return overrides[Pair(leftContextId, rightContextId)] ?: 0
    }
}
