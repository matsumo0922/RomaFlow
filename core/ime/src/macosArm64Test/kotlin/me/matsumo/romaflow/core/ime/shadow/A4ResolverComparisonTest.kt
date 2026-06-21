package me.matsumo.romaflow.core.ime.shadow

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import me.matsumo.romaflow.core.ime.BuildKonfig
import me.matsumo.romaflow.core.ime.CompositionSource
import me.matsumo.romaflow.core.ime.ConversionProvider
import me.matsumo.romaflow.core.ime.ConversionRequest
import me.matsumo.romaflow.core.ime.CorpusCategory
import me.matsumo.romaflow.core.ime.CorpusEntry
import me.matsumo.romaflow.core.ime.EvaluationCorpus
import me.matsumo.romaflow.core.ime.FakeConversionProvider
import me.matsumo.romaflow.core.ime.RerankRequest
import me.matsumo.romaflow.core.ime.WordCandidateRequest
import me.matsumo.romaflow.core.ime.defaultConversionProvider
import me.matsumo.romaflow.core.morphology.ConnectionCostProvider
import me.matsumo.romaflow.core.morphology.IpadicReadingLexicon
import me.matsumo.romaflow.core.morphology.MomijiConnectionCostProvider
import me.matsumo.romaflow.core.morphology.ReadingLatticeDecoder
import me.matsumo.romaflow.core.morphology.ReadingLexicon
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * A-4 resolver 戦略の実測比較ハーネス（macosArm64）。
 *
 * A-0 の [BatchEvaluationHarnessTest][me.matsumo.romaflow.core.ime.BatchEvaluationHarnessTest] を
 * 拡張し、複数戦略（FakeDeterministic / LegacyFullText / OneShotJointPatch / TwoStepToolLoop /
 * Rerank / FactorizedSurface / FullTailSurface）を同一 corpus で走らせて
 * top-1 精度・latency p50/p95・invalid-proposal 率を戦略別・カテゴリ別に集計する。
 *
 * ## Fake テスト（通常実行）
 * [a4FakeResolverEvaluationApplierWorkflow] は実辞書を使うが LLM を呼ばない。
 * [FakeDeterministicResolver] + [ProposalApplier] の検証フロー（§6）全体を実辞書で確認する。
 *
 * ## 戦略比較ライブテスト（二重 opt-in）
 * [a4LiveResolverStrategyComparison] は以下の両条件が揃った場合のみ実行する:
 * 1. `BuildKonfig.OPENAI_API_KEY` が非空
 * 2. 環境変数 `A4_LIVE_TEST_ENABLED=true` が明示されている
 *
 * ## invalid-proposal 率の計測方式（PR-B）
 * production コードにメトリクス専用フックを追加せず、テスト側の [RecordingConversionProvider]
 * で提案 request/response を捕捉し、[ReadingLatticeDecoder.findMinCostPathForSurface] で再検証する。
 * - FullTailSurface: `proposeFullTailSurface` の戻り値を reading に対して再検証 → null なら invalid。
 * - FactorizedSurface: `rerankFactorized` の decisions を各 region reading に対して再検証 → null なら invalid。
 *
 * 手動実行例:
 * ```
 * A4_LIVE_TEST_ENABLED=true ./gradlew :core:ime:macosArm64Test --no-configuration-cache -i 2>&1 | grep -E "\[A4|==="
 * ```
 */
class A4ResolverComparisonTest {

    private val lexicon by lazy { IpadicReadingLexicon() }
    private val costProvider by lazy { MomijiConnectionCostProvider.load() }

    /**
     * A-4 Fake 評価: FakeDeterministicResolver + ProposalApplier が実辞書で正しく動作することを確認する。
     *
     * Fake 経路（CI 既定）で全戦略が走り、per-category 集計と invalid 率が print されること。
     */
    @Test
    fun a4FakeResolverEvaluationApplierWorkflow() {
        val fakeProvider = FakeConversionProvider()
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

            val proposal = runBlocking { FakeDeterministicResolver().propose(request) }

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

            println(
                "reading=$reading expected=$expectedSurface result=$resultSurface correct=$isCorrect",
            )
        }

        val accuracy = correctCount.toDouble() / corpus.size

        println("=== Fake Resolver Accuracy: ${(accuracy * 100).toInt()}% ($correctCount/${corpus.size}) ===")

        // Fake 経路で FullTailSurface / FactorizedSurface も per-category 集計が出ることを検証
        runFakeCategoryComparison(fakeProvider, applier)

        assertTrue(
            accuracy >= 0.6,
            "Fake resolver の top-1 精度が 60% 以上であること（実際: ${(accuracy * 100).toInt()}%）",
        )
    }

    /**
     * Fake 経路（CI 既定）で per-category 集計と invalid 率が print されることを確認する。
     *
     * LLM を呼ばずに全戦略が走り、HOMOPHONE_HARD を含む全カテゴリの集計が出る。
     * invalid-proposal 率は RecordingConversionProvider で計測する。
     */
    private fun runFakeCategoryComparison(
        fakeProvider: FakeConversionProvider,
        applier: ProposalApplier,
    ) {
        val strategies = buildFakeStrategies(fakeProvider)

        val corpus = EvaluationCorpus.all

        println()
        println("=== A-4 Fake Per-Category Comparison (${corpus.size} entries) ===")

        for (strategyConfig in strategies) {
            val categoryResults = runStrategyOnCorpusByCategory(
                strategyConfig = strategyConfig,
                corpus = corpus,
                applier = applier,
            )

            println()
            println("--- ${strategyConfig.name} ---")
            printCategoryResults(strategyConfig.name, categoryResults)
        }
    }

    /**
     * A-4 ライブテスト（二重 opt-in）: 複数戦略を同一 corpus で走らせ per-category 集計を比較する。
     *
     * 以下の両条件が揃った場合のみ実行する（A-0 live test と同じ opt-in 方式）:
     * 1. `BuildKonfig.OPENAI_API_KEY` が非空
     * 2. 環境変数 `A4_LIVE_TEST_ENABLED=true` が明示
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
        val corpus = EvaluationCorpus.all

        val strategies = buildLiveStrategies(conversionProvider)

        println("=== A-4 Live Resolver Strategy Comparison ===")
        println("model=${BuildKonfig.OPENAI_MODEL}")
        println("corpus size=${corpus.size}")

        for (strategyConfig in strategies) {
            val categoryResults = runStrategyOnCorpusByCategory(
                strategyConfig = strategyConfig,
                corpus = corpus,
                applier = applier,
            )

            println()
            println("--- ${strategyConfig.name} ---")
            printCategoryResults(strategyConfig.name, categoryResults)
        }
    }

    // region: strategy builders

    private fun buildFakeStrategies(
        fakeProvider: FakeConversionProvider,
    ): List<StrategyConfig> {
        val fullTailRecording = RecordingConversionProvider(fakeProvider, lexicon, costProvider)
        val factorizedRecording = RecordingConversionProvider(fakeProvider, lexicon, costProvider)

        return listOf(
            StrategyConfig(
                name = "FakeDeterministic",
                resolver = FakeDeterministicResolver(),
                recordingProvider = null,
            ),
            StrategyConfig(
                name = "FullTailSurface",
                resolver = FullTailSurfaceResolver(fullTailRecording, lexicon, costProvider),
                recordingProvider = fullTailRecording,
            ),
            StrategyConfig(
                name = "FactorizedSurface",
                resolver = FactorizedRerankResolver(factorizedRecording, lexicon, costProvider),
                recordingProvider = factorizedRecording,
            ),
        )
    }

    private fun buildLiveStrategies(conversionProvider: ConversionProvider): List<StrategyConfig> {
        val fullTailRecording = RecordingConversionProvider(conversionProvider, lexicon, costProvider)
        val factorizedRecording = RecordingConversionProvider(conversionProvider, lexicon, costProvider)

        return listOf(
            StrategyConfig(
                name = "FakeDeterministic",
                resolver = FakeDeterministicResolver(),
                recordingProvider = null,
            ),
            StrategyConfig(
                name = "LegacyFullText",
                resolver = LegacyFullTextResolver(conversionProvider),
                recordingProvider = null,
            ),
            StrategyConfig(
                name = "OneShotJointPatch",
                resolver = OneShotJointPatchResolver(conversionProvider),
                recordingProvider = null,
            ),
            StrategyConfig(
                name = "TwoStepToolLoop",
                resolver = TwoStepToolLoopResolver(conversionProvider),
                recordingProvider = null,
            ),
            StrategyConfig(
                name = "Rerank",
                resolver = RerankResolver(conversionProvider, lexicon, costProvider),
                recordingProvider = null,
            ),
            StrategyConfig(
                name = "FullTailSurface",
                resolver = FullTailSurfaceResolver(fullTailRecording, lexicon, costProvider),
                recordingProvider = fullTailRecording,
            ),
            StrategyConfig(
                name = "FactorizedSurface",
                resolver = FactorizedRerankResolver(factorizedRecording, lexicon, costProvider),
                recordingProvider = factorizedRecording,
            ),
        )
    }

    // endregion

    // region: corpus runner

    /**
     * 1 戦略を corpus 全体で走らせ、カテゴリ別に結果を集計する。
     */
    private fun runStrategyOnCorpusByCategory(
        strategyConfig: StrategyConfig,
        corpus: List<CorpusEntry>,
        applier: ProposalApplier,
    ): Map<CorpusCategory, StrategyResult> {
        val byCategory = corpus.groupBy { it.category }
        val results = mutableMapOf<CorpusCategory, StrategyResult>()

        for ((category, entries) in byCategory) {
            strategyConfig.recordingProvider?.resetCounts()

            val result = runStrategyOnEntries(
                strategyConfig = strategyConfig,
                entries = entries,
                applier = applier,
            )

            results[category] = result
        }

        return results
    }

    private fun runStrategyOnEntries(
        strategyConfig: StrategyConfig,
        entries: List<CorpusEntry>,
        applier: ProposalApplier,
    ): StrategyResult {
        val timeSource = TimeSource.Monotonic
        val latencies = mutableListOf<Long>()
        var correctCount = 0

        for (entry in entries) {
            val state = buildStateWithReading(entry.reading)
            val request = buildRequest(state)
            val startMark = timeSource.markNow()

            val proposal = runBlocking { strategyConfig.resolver.propose(request) }

            val resultState = applier.apply(
                proposal = proposal,
                request = request,
                state = state,
                currentInputRevision = 0,
                currentGraphRevision = 0,
                currentCandidatePackDigest = 0,
            )

            val elapsed = startMark.elapsedNow()

            latencies.add(elapsed.inWholeMilliseconds)

            val resultSurface = extractSurface(resultState)
            val isCorrect = resultSurface == entry.expectedSurface

            if (isCorrect) correctCount++

            println(
                "[${strategyConfig.name}] reading=${entry.reading} expected=${entry.expectedSurface}" +
                    " result=$resultSurface correct=$isCorrect elapsed=$elapsed",
            )
        }

        val sortedLatencies = latencies.sorted()
        val p50Index = (sortedLatencies.size - 1) / 2
        val p95Index = (sortedLatencies.size * 95 / 100).coerceAtMost(sortedLatencies.size - 1)

        val invalidProposalRate = strategyConfig.recordingProvider?.computeInvalidRate() ?: Double.NaN

        return StrategyResult(
            correctCount = correctCount,
            accuracy = if (entries.isEmpty()) 0.0 else correctCount.toDouble() / entries.size,
            p50Ms = sortedLatencies.getOrElse(p50Index) { 0L },
            p95Ms = sortedLatencies.getOrElse(p95Index) { 0L },
            latenciesMs = sortedLatencies,
            invalidProposalRate = invalidProposalRate,
        )
    }

    // endregion

    // region: print helpers

    private fun printCategoryResults(
        strategyName: String,
        categoryResults: Map<CorpusCategory, StrategyResult>,
    ) {
        var totalCorrect = 0
        var totalCount = 0

        for (category in CorpusCategory.entries) {
            val result = categoryResults[category] ?: continue
            val count = result.latenciesMs.size
            val invalidInfo = if (result.invalidProposalRate.isNaN()) {
                ""
            } else {
                " invalid=${(result.invalidProposalRate * 100).toInt()}%"
            }

            println(
                "[$strategyName][$category] accuracy=${(result.accuracy * 100).toInt()}%" +
                    " (${result.correctCount}/$count) p50=${result.p50Ms}ms p95=${result.p95Ms}ms$invalidInfo",
            )

            totalCorrect += result.correctCount
            totalCount += count
        }

        val overallAccuracy = if (totalCount > 0) totalCorrect.toDouble() / totalCount else 0.0

        println(
            "[$strategyName][TOTAL] accuracy=${(overallAccuracy * 100).toInt()}% ($totalCorrect/$totalCount)",
        )
    }

    // endregion

    // region: state builders

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
 * 戦略設定（名前 + resolver + recording provider）。
 *
 * [recordingProvider] が非 null の場合、invalid-proposal 率を計測する。
 */
private data class StrategyConfig(
    /** 戦略名（ログ / レポート用）。 */
    val name: String,
    /** resolver インスタンス。 */
    val resolver: ConversionResolver,
    /**
     * invalid-proposal 率計測用の recording provider。
     *
     * null の場合は invalid 率を計測しない（NaN として出力）。
     */
    val recordingProvider: RecordingConversionProvider?,
)

/**
 * 1 戦略のカテゴリ別評価結果。
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
    /**
     * invalid-proposal 率（0.0〜1.0）。
     *
     * 提案が格子検証で拒否されて baseline に落ちた割合。
     * [RecordingConversionProvider] を使わない戦略では [Double.NaN]。
     */
    val invalidProposalRate: Double,
)

/**
 * [ConversionProvider] をラップし、request/response を捕捉して invalid-proposal 率を計測する decorator。
 *
 * ## FullTailSurface の計測
 * `proposeFullTailSurface(reading, prefixContext)` の戻り値を
 * `findMinCostPathForSurface(reading, returned)` で再検証し、null なら invalid として数える。
 *
 * ## FactorizedSurface の計測
 * `rerankFactorized(request)` の decisions を各 region の reading に対して
 * `findMinCostPathForSurface(region.reading, surface)` で再検証し、null なら invalid として数える。
 *
 * production コードにメトリクス専用の変更を加えず、公開 API [ReadingLatticeDecoder.findMinCostPathForSurface]
 * で再検証する（PR-B 設計）。
 */
private class RecordingConversionProvider(
    private val delegate: ConversionProvider,
    private val lexicon: ReadingLexicon,
    private val costProvider: ConnectionCostProvider,
) : ConversionProvider {

    private var totalProposalCount = 0
    private var invalidProposalCount = 0

    override suspend fun convert(request: ConversionRequest): String = delegate.convert(request)

    override suspend fun candidates(request: WordCandidateRequest): String =
        delegate.candidates(request)

    override suspend fun rerank(request: RerankRequest): Int = delegate.rerank(request)

    override suspend fun rerankFactorized(request: FactorizedRerankRequest): FactorizedRerankResult {
        val result = delegate.rerankFactorized(request)
        recordFactorizedDecisions(request, result)
        return result
    }

    override suspend fun proposeFullTailSurface(reading: String, prefixContext: String): String {
        val proposed = delegate.proposeFullTailSurface(reading, prefixContext)
        recordFullTailProposal(reading, proposed)
        return proposed
    }

    /** invalid-proposal 率を計算する（0 提案の場合は 0.0）。 */
    fun computeInvalidRate(): Double {
        if (totalProposalCount == 0) return 0.0
        return invalidProposalCount.toDouble() / totalProposalCount
    }

    /** カウンタをリセットする（戦略の各 corpus 実行前に呼ぶ）。 */
    fun resetCounts() {
        totalProposalCount = 0
        invalidProposalCount = 0
    }

    private fun recordFullTailProposal(reading: String, proposed: String) {
        // resolver は採用前に trim するため、invalid 判定も trim 後で行い実挙動と一致させる。
        val trimmedSurface = proposed.trim()

        if (trimmedSurface.isBlank()) return

        totalProposalCount++

        val isValid = ReadingLatticeDecoder.findMinCostPathForSurface(
            reading = reading,
            surface = trimmedSurface,
            lexicon = lexicon,
            costProvider = costProvider,
        ) != null

        if (!isValid) invalidProposalCount++
    }

    private fun recordFactorizedDecisions(
        request: FactorizedRerankRequest,
        result: FactorizedRerankResult,
    ) {
        for (region in request.regions) {
            // resolver は採用前に trim するため、invalid 判定も trim 後で行い実挙動と一致させる。
            val trimmedSurface = result.decisions[region.id]?.trim() ?: continue

            if (trimmedSurface.isBlank()) continue

            totalProposalCount++

            val isValid = ReadingLatticeDecoder.findMinCostPathForSurface(
                reading = region.reading,
                surface = trimmedSurface,
                lexicon = lexicon,
                costProvider = costProvider,
            ) != null

            if (!isValid) invalidProposalCount++
        }
    }
}
