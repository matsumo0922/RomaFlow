package me.matsumo.romaflow.core.morphology

import io.github.tokuhirom.momiji.ipadic.momijiLoadMatrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A-spike の go/no-go テスト。
 *
 * 4 項目を確認する:
 * 1. momijiLoadMatrix() が呼び出せ Matrix.find() が動作する
 * 2. sys.dic のリッチエントリ（lcAttr / rcAttr / wcost）が読み取れる
 * 3. あいかわらず の N-best に単語経路・複合経路の両方が現れる
 * 4. てんき の N-best に 天気 と 転機 が含まれる
 */
class ReadingLatticeReachabilityTest {

    @Test
    fun momijiLoadMatrixReturnsMatrixThatCanLookUpTransitionCost() {
        val matrix = momijiLoadMatrix()
        val cost = matrix.find(0, 0)

        // BOS→BOS は何らかの値を返せば OK（例外が出ないことが目的）
        println("BOS→BOS cost: $cost")

        // 接続コストは通常 Short の範囲
        assertTrue(cost.toInt() in Short.MIN_VALUE..Short.MAX_VALUE)
    }

    @Test
    fun sysDicProvidesRichEntriesWithLcAttrRcAttrWcost() {
        val reader = IpadicRichDictReader()
        val entries = reader.readRichEntries()

        assertFalse(entries.isEmpty(), "リッチエントリが 1 件以上存在すること")

        val sample = entries.first()
        println("First rich entry: $sample")

        // lcAttr / rcAttr は UShort 由来なので 0..65535 の範囲
        assertTrue(sample.lcAttr in 0..65535)
        assertTrue(sample.rcAttr in 0..65535)

        // wcost は Short 由来なので Short の範囲（負値も有効）
        assertTrue(sample.wcost in Short.MIN_VALUE..Short.MAX_VALUE)

        // posId は 0 以上
        assertTrue(sample.posId >= 0)
    }

    @Test
    fun nBestForAikawarazuIncludesBothSingleWordAndMultiWordPaths() {
        val lexicon = IpadicReadingLexicon()
        val costProvider = MomijiConnectionCostProvider.load()
        val reading = "あいかわらず"

        val paths = ReadingLatticeDecoder.nBest(
            reading = reading,
            lexicon = lexicon,
            costProvider = costProvider,
            n = 10,
        )

        println("N-best for $reading:")
        paths.forEachIndexed { index, (cost, path) ->
            println("  [$index] cost=$cost surfaces=${path.map { it.surface }}")
        }

        assertFalse(paths.isEmpty(), "あいかわらず の N-best が 1 件以上あること")

        val hasSingleWordPath = paths.any { (_, path) -> path.size == 1 }
        val hasMultiWordPath = paths.any { (_, path) -> path.size > 1 }

        // A-spike 死活条件: 単語経路・複合経路がそれぞれ独立して N-best に共存すること
        assertTrue(hasSingleWordPath, "N-best に 単語経路（path.size == 1）が含まれること")
        assertTrue(hasMultiWordPath, "N-best に 複合経路（path.size > 1）が含まれること")

        // rank 0 の path 表層形が ["相変わらず"] であること（Viterbi 最良経路の死活確認）
        val rank0Surfaces = paths[0].second.map { it.surface }
        assertEquals(listOf("相変わらず"), rank0Surfaces, "rank 0 の surface 列が [相変わらず] であること")
    }

    @Test
    fun nBestForTenkiIncludesTenkiAndTenki() {
        val lexicon = IpadicReadingLexicon()
        val costProvider = MomijiConnectionCostProvider.load()
        val reading = "てんき"

        val paths = ReadingLatticeDecoder.nBest(
            reading = reading,
            lexicon = lexicon,
            costProvider = costProvider,
            n = 16,
        )

        println("N-best for $reading:")
        paths.forEachIndexed { index, (cost, path) ->
            println("  [$index] cost=$cost surfaces=${path.map { it.surface }}")
        }

        val allSurfaces = paths.flatMap { (_, path) -> path.map { it.surface } }.toSet()
        println("All surfaces: $allSurfaces")

        assertTrue("天気" in allSurfaces, "天気 が N-best に含まれること（found: $allSurfaces）")
        assertTrue("転機" in allSurfaces, "転機 が N-best に含まれること（found: $allSurfaces）")
    }
}
