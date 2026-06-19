package me.matsumo.romaflow.core.ime

import me.matsumo.romaflow.core.morphology.IpadicReadingLexicon
import me.matsumo.romaflow.core.morphology.MomijiConnectionCostProvider
import me.matsumo.romaflow.core.morphology.ReadingLatticeDecoder
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * A-0 バッチ評価ハーネス。
 *
 * 決定論的な lattice / Viterbi 部分を常に実行し、N-best の精度・レイテンシを計測する。
 * LLM ライブテストは OPENAI_API_KEY が設定されている場合のみ実行する。
 */
class BatchEvaluationHarnessTest {

    /**
     * A-0 バッチ評価: てんき・かんじ・わたし の N-best に期待候補が含まれるか検証する。
     *
     * 各 reading について [ReadingLatticeDecoder.nBest] を実行し、期待される表層形が
     * 上位 16 件以内に現れることを確認する。
     */
    @Test
    fun a0BatchEvalViterbiCoversKnownReadingsWithExpectedTopCandidates() {
        val timeSource = TimeSource.Monotonic
        val lexicon = IpadicReadingLexicon()
        val costProvider = MomijiConnectionCostProvider.load()

        val testCases = listOf(
            "てんき" to listOf("天気", "転機"),
            "かんじ" to listOf("漢字", "幹事"),
            "わたし" to listOf("私"),
        )

        println("=== A-0 Batch Evaluation ===")

        for ((reading, expectedSurfaces) in testCases) {
            val startMark = timeSource.markNow()

            val paths = ReadingLatticeDecoder.nBest(
                reading = reading,
                lexicon = lexicon,
                costProvider = costProvider,
                n = 16,
            )

            val elapsed = startMark.elapsedNow()
            val allSurfaces = paths.flatMap { (_, path) -> path.map { it.surface } }.toSet()

            println("reading=$reading elapsed=$elapsed")
            paths.forEachIndexed { index, (cost, path) ->
                println("  [$index] cost=$cost surfaces=${path.map { it.surface }}")
            }
            println("  allSurfaces=$allSurfaces")

            assertFalse(paths.isEmpty(), "reading=$reading の N-best が 1 件以上あること")

            for (expected in expectedSurfaces) {
                assertTrue(
                    expected in allSurfaces,
                    "reading=$reading の N-best に $expected が含まれること (found: $allSurfaces)",
                )
            }
        }
    }

    /**
     * A-0 レイテンシチェック: 辞書ロード済み状態（ウォームアップ後）での N-best 計算が 1 秒以内に完了する。
     *
     * 初回の [IpadicReadingLexicon] 構築（逆引き index の lazy init）は重いため、
     * ウォームアップ呼び出しを 1 回行った後に各 reading のレイテンシを計測する。
     */
    @Test
    fun a0LatencyCheckNBestCompletesWithin1SecondPerReadingAfterWarmup() {
        val lexicon = IpadicReadingLexicon()
        val costProvider = MomijiConnectionCostProvider.load()
        val timeSource = TimeSource.Monotonic

        // 逆引き index の lazy init をここで起動して完了させる（ウォームアップ）
        ReadingLatticeDecoder.nBest(
            reading = "てんき",
            lexicon = lexicon,
            costProvider = costProvider,
            n = 1,
        )

        val readings = listOf("てんき", "かんじ", "わたし", "にほんご", "とうきょう")

        println("=== A-0 Latency Check (after warmup) ===")

        for (reading in readings) {
            val startMark = timeSource.markNow()

            ReadingLatticeDecoder.nBest(
                reading = reading,
                lexicon = lexicon,
                costProvider = costProvider,
                n = 8,
            )

            val elapsed = startMark.elapsedNow()

            println("reading=$reading elapsed=$elapsed")

            assertTrue(
                elapsed.inWholeSeconds < 1,
                "reading=$reading の N-best（ウォームアップ後）が 1 秒以内に完了すること（実際: $elapsed）",
            )
        }
    }
}
