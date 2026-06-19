package me.matsumo.romaflow.core.ime

import kotlinx.coroutines.runBlocking
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
 * LLM ライブテストは OPENAI_API_KEY が設定されている場合のみ実行する（opt-in）。
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

    /**
     * LLM ライブテスト（opt-in）。OPENAI_API_KEY が空の場合はスキップする。
     *
     * [defaultConversionProvider] を使い、corpus readings をそれぞれ変換して
     * resolver の 1 往復レイテンシ（p50/p95）と top-1 精度を計測・報告する。
     * このテストはネットワーク呼び出しを含むため、CI の通常フローでは実行しないこと
     * （API キーが未設定なら自動スキップ）。
     *
     * TODO: corpus サイズを各カテゴリ 20〜50 文に拡大する（現状は PoC の最小構成）
     */
    @Test
    fun a0LlmLiveTest() {
        val apiKey = BuildKonfig.OPENAI_API_KEY

        if (apiKey.isEmpty()) {
            println("=== A-0 LLM Live Test: SKIPPED (OPENAI_API_KEY is not configured) ===")
            return
        }

        val provider = defaultConversionProvider()
        val timeSource = TimeSource.Monotonic

        // corpus: reading → 期待 top-1 表層形
        val corpus = listOf(
            "てんき" to "天気",
            "わたしはがくせいです" to "私は学生です",
            "とうきょうにいく" to "東京に行く",
            "にほんご" to "日本語",
            "おはようございます" to "おはようございます",
        )

        val latencies = mutableListOf<Long>()
        var top1Correct = 0

        println("=== A-0 LLM Live Test ===")
        println("model=${BuildKonfig.OPENAI_MODEL}")

        for ((reading, expectedTop1) in corpus) {
            val request = ConversionRequest(readingInput = reading, prefixContext = "")
            val startMark = timeSource.markNow()

            val result = runBlocking { provider.convert(request) }

            val elapsed = startMark.elapsedNow()
            val elapsedMs = elapsed.inWholeMilliseconds

            latencies.add(elapsedMs)

            val isCorrect = result.trim() == expectedTop1
            if (isCorrect) top1Correct++

            println("reading=$reading expected=$expectedTop1 result=$result elapsed=${elapsed} correct=$isCorrect")
        }

        val sortedLatencies = latencies.sorted()
        // 偶数 corpus でも下側中央を取るため (size - 1) / 2 を使う（切り捨て除算で下寄り）
        val p50Index = (sortedLatencies.size - 1) / 2
        val p95Index = (sortedLatencies.size * 95 / 100).coerceAtMost(sortedLatencies.size - 1)
        val p50Ms = sortedLatencies[p50Index]
        val p95Ms = sortedLatencies[p95Index]
        val top1Accuracy = top1Correct.toDouble() / corpus.size

        println("=== LLM Live Test Results ===")
        println("latency p50=${p50Ms}ms p95=${p95Ms}ms")
        val top1AccuracyPercent = (top1Accuracy * 100).toInt()
        println("top-1 accuracy=${top1AccuracyPercent}% ($top1Correct/${corpus.size})")
        println("all latencies (ms): $sortedLatencies")
    }
}
