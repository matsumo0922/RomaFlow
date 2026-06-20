package me.matsumo.romaflow.core.ime.shadow

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import me.matsumo.romaflow.core.ime.BuildKonfig
import me.matsumo.romaflow.core.ime.CompositionSource
import me.matsumo.romaflow.core.ime.defaultConversionProvider
import me.matsumo.romaflow.core.morphology.IpadicReadingLexicon
import me.matsumo.romaflow.core.morphology.MomijiConnectionCostProvider
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * A-4 resolver 戦略の実測比較ハーネス（macosArm64）。
 *
 * A-0 の [BatchEvaluationHarnessTest][me.matsumo.romaflow.core.ime.BatchEvaluationHarnessTest] を
 * 拡張し、4 戦略（FakeDeterministic / LegacyFullText / OneShotJointPatch / TwoStepToolLoop）を
 * 同一 corpus で走らせて top-1 精度と latency p50/p95 を戦略別に集計する（§8/§10）。
 *
 * ## Fake テスト（通常実行）
 * [a4FakeResolverEvaluationApplierWorkflow] は実辞書を使うが LLM を呼ばない。
 * [FakeDeterministicResolver] + [ProposalApplier] の検証フロー（§6）全体を実辞書で確認する。
 *
 * ## 4 戦略比較ライブテスト（二重 opt-in）
 * [a4LiveResolverStrategyComparison] は以下の両条件が揃った場合のみ実行する:
 * 1. `BuildKonfig.OPENAI_API_KEY` が非空
 * 2. 環境変数 `A4_LIVE_TEST_ENABLED=true` が明示されている
 *
 * 手動実行例:
 * ```
 * A4_LIVE_TEST_ENABLED=true ./gradlew :core:ime:macosArm64Test --no-configuration-cache
 * ```
 */
class A4ResolverComparisonTest {

    private val lexicon by lazy { IpadicReadingLexicon() }
    private val costProvider by lazy { MomijiConnectionCostProvider.load() }

    /**
     * A-4 Fake 評価: FakeDeterministicResolver + ProposalApplier が実辞書で正しく動作することを確認する。
     *
     * 変換表に含まれる corpus reading に対し、applier が正しい表層形を採用することを検証する。
     * 実 LLM は使わない。通常の CI フローで実行可能。
     *
     * corpus の読みは [FakeDeterministicResolver] の READING_TO_PREFERRED_SURFACE に含まれるものを使う。
     */
    @Test
    fun a4FakeResolverEvaluationApplierWorkflow() {
        val resolver = FakeDeterministicResolver()
        val applier = ProposalApplier(lexicon = lexicon, costProvider = costProvider)

        val corpus = listOf(
            "てんき" to "天気",
            "わたし" to "私",
            "かんじ" to "漢字",
            "にほんご" to "日本語",
            "とうきょう" to "東京",
        )

        println("=== A-4 Fake Resolver Evaluation ===")

        var correctCount = 0

        for ((reading, expectedSurface) in corpus) {
            val state = buildStateWithReading(reading)
            val request = buildRequest(state)

            val proposal = runBlocking { resolver.propose(request) }

            val resultState = applier.apply(
                proposal = proposal,
                request = request,
                state = state,
                currentInputRevision = 0,
                currentGraphRevision = 0,
                currentCandidatePackDigest = 0,
            )

            val resultSurface = extractSurface(resultState)
            val isCorrect = resultSurface == expectedSurface

            if (isCorrect) correctCount++

            println("reading=$reading expected=$expectedSurface result=$resultSurface correct=$isCorrect proposal=$proposal")
        }

        val accuracy = correctCount.toDouble() / corpus.size

        println("=== Fake Resolver Accuracy: ${(accuracy * 100).toInt()}% ($correctCount/${corpus.size}) ===")

        assertTrue(
            accuracy >= 0.6,
            "Fake resolver の top-1 精度が 60% 以上であること（実際: ${(accuracy * 100).toInt()}%）",
        )
    }

    /**
     * A-4 ライブテスト（二重 opt-in）: 4 戦略を同一 corpus で走らせ top-1 / latency を戦略別に比較する。
     *
     * 以下の両条件が揃った場合のみ実行する（A-0 live test と同じ opt-in 方式）:
     * 1. `BuildKonfig.OPENAI_API_KEY` が非空
     * 2. 環境変数 `A4_LIVE_TEST_ENABLED=true` が明示
     *
     * ## 戦略と corpus の組み合わせ
     * - FakeDeterministic: 変換表内の corpus（辞書依存の比較基準）
     * - LegacyFullText (strategy 1): 全文 1 往復 call1 ラップ
     * - OneShotJointPatch (strategy 2): call2 で candidates を 1 往復
     * - TwoStepToolLoop (strategy 3): call1 → 条件付き call2 の最大 2 往復
     *
     * ## corpus 設計（§8 の戦略差が出やすいケース）
     * - 通常変換: 辞書に載っている直変換（てんき → 天気）
     * - 同音異義語: 辞書に複数候補が存在する（かんじ → 漢字 or 幹事）
     * - タイポ訂正系: 誤変換・文脈依存変換（えいご → 英語）
     */
    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun a4LiveResolverStrategyComparison() {
        val apiKey = BuildKonfig.OPENAI_API_KEY
        val liveTestEnabled = getenv("A4_LIVE_TEST_ENABLED")?.toKString() == "true"

        if (apiKey.isEmpty()) {
            println("=== A-4 Live Resolver Comparison: SKIPPED (OPENAI_API_KEY is not configured) ===")
            return
        }

        if (!liveTestEnabled) {
            println("=== A-4 Live Resolver Comparison: SKIPPED (A4_LIVE_TEST_ENABLED != true) ===")
            return
        }

        val conversionProvider = defaultConversionProvider()
        val applier = ProposalApplier(lexicon = lexicon, costProvider = costProvider)

        // corpus: reading → 期待 top-1 表層形（§8 の戦略差が出やすいケース）
        val corpus = listOf(
            // 通常変換
            "てんき" to "天気",
            "わたし" to "私",
            "にほんご" to "日本語",
            "とうきょう" to "東京",
            // 同音異義語（辞書で複数候補）
            "かんじ" to "漢字",
            "へんかん" to "変換",
            // タイポ訂正系（文脈依存変換）
            "えいご" to "英語",
            "がっこう" to "学校",
        )

        // 5 戦略のリスト（Rerank を追加）
        val strategies = listOf(
            StrategyConfig("FakeDeterministic", FakeDeterministicResolver()),
            StrategyConfig("LegacyFullText", LegacyFullTextResolver(conversionProvider)),
            StrategyConfig("OneShotJointPatch", OneShotJointPatchResolver(conversionProvider)),
            StrategyConfig("TwoStepToolLoop", TwoStepToolLoopResolver(conversionProvider)),
            StrategyConfig("Rerank", RerankResolver(conversionProvider, lexicon, costProvider)),
        )

        println("=== A-4 Live Resolver Strategy Comparison ===")
        println("model=${BuildKonfig.OPENAI_MODEL}")
        println("corpus size=${corpus.size}")

        for (strategyConfig in strategies) {
            val result = runStrategyOnCorpus(
                strategy = strategyConfig.resolver,
                corpus = corpus,
                applier = applier,
                strategyName = strategyConfig.name,
            )

            println()
            println("--- ${strategyConfig.name} ---")
            println("top-1 accuracy=${(result.accuracy * 100).toInt()}% (${result.correctCount}/${corpus.size})")
            println("latency p50=${result.p50Ms}ms p95=${result.p95Ms}ms")
            println("all latencies (ms): ${result.latenciesMs}")
        }
    }

    // region: helper

    /**
     * 1 戦略を corpus 全体で走らせて結果を集計する。
     */
    private fun runStrategyOnCorpus(
        strategy: ConversionResolver,
        corpus: List<Pair<String, String>>,
        applier: ProposalApplier,
        strategyName: String,
    ): StrategyResult {
        val timeSource = TimeSource.Monotonic
        val latencies = mutableListOf<Long>()
        var correctCount = 0

        for ((reading, expectedTop1) in corpus) {
            val state = buildStateWithReading(reading)
            val request = buildRequest(state)
            val startMark = timeSource.markNow()

            val proposal = runBlocking { strategy.propose(request) }

            val resultState = applier.apply(
                proposal = proposal,
                request = request,
                state = state,
                currentInputRevision = 0,
                currentGraphRevision = 0,
                currentCandidatePackDigest = 0,
            )

            val elapsed = startMark.elapsedNow()
            val elapsedMs = elapsed.inWholeMilliseconds

            latencies.add(elapsedMs)

            val resultSurface = extractSurface(resultState)
            val isCorrect = resultSurface == expectedTop1

            if (isCorrect) correctCount++

            println("[$strategyName] reading=$reading expected=$expectedTop1 result=$resultSurface correct=$isCorrect elapsed=$elapsed")
        }

        val sortedLatencies = latencies.sorted()
        val p50Index = (sortedLatencies.size - 1) / 2
        val p95Index = (sortedLatencies.size * 95 / 100).coerceAtMost(sortedLatencies.size - 1)

        return StrategyResult(
            correctCount = correctCount,
            accuracy = correctCount.toDouble() / corpus.size,
            p50Ms = sortedLatencies.getOrElse(p50Index) { 0 },
            p95Ms = sortedLatencies.getOrElse(p95Index) { 0 },
            latenciesMs = sortedLatencies,
        )
    }

    /**
     * [CompositionState] から現在の表層形を取り出す。
     *
     * 変換済みの場合は segments の surface 連結、未変換の場合は reading をそのまま返す。
     */
    private fun extractSurface(state: CompositionState): String {
        if (!state.isConverted) {
            return state.reading
        }

        val builder = StringBuilder()

        for (segment in state.segments) {
            builder.append(segment.displaySurface)
        }

        return builder.toString()
    }

    /**
     * reading から実辞書・実 costProvider で [CompositionGraph] を構築した [CompositionState] を返す。
     *
     * graph を空にしないことで [TwoStepToolLoopResolver.checkNeedsSecondPass] の
     * `existsInGraph` チェックが正しく機能し、条件付き 2 往復の挙動が計測できる。
     */
    private fun buildStateWithReading(reading: String): CompositionState {
        val graph = CompositionGraph.build(
            reading = reading,
            lexicon = lexicon,
            costProvider = costProvider,
        )

        return CompositionState.empty().copy(
            source = CompositionSource.withFrozenPrefix(frozenPrefix = reading, revision = 0),
            graph = graph,
            selectedPathId = if (graph.allPathIds.isNotEmpty()) CompositionGraph.PathId(0) else null,
        )
    }

    private fun buildRequest(state: CompositionState): ResolutionRequest {
        return ResolutionRequest(
            state = state,
            inputRevision = 0,
            graphRevision = 0,
            candidatePackDigest = 0,
        )
    }

    // endregion
}

/**
 * 戦略設定（名前 + resolver インスタンス）。
 */
private data class StrategyConfig(
    /** 戦略名（ログ / レポート用）。 */
    val name: String,
    /** resolver インスタンス。 */
    val resolver: ConversionResolver,
)

/**
 * 1 戦略の評価結果。
 */
private data class StrategyResult(
    /** 正解数。 */
    val correctCount: Int,
    /** top-1 精度（0.0〜1.0）。 */
    val accuracy: Double,
    /** レイテンシ p50（ミリ秒）。 */
    val p50Ms: Long,
    /** レイテンシ p95（ミリ秒）。 */
    val p95Ms: Long,
    /** 全レイテンシ（ミリ秒・昇順）。 */
    val latenciesMs: List<Long>,
)
