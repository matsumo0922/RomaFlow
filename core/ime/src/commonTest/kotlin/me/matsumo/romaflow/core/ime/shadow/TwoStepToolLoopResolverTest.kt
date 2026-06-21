package me.matsumo.romaflow.core.ime.shadow

import kotlinx.coroutines.runBlocking
import me.matsumo.romaflow.core.ime.CompositionSource
import me.matsumo.romaflow.core.ime.ConversionProvider
import me.matsumo.romaflow.core.ime.ConversionRequest
import me.matsumo.romaflow.core.ime.WordCandidateRequest
import me.matsumo.romaflow.core.morphology.LexemeEntry
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TwoStepToolLoopResolver] の条件付き 2 往復ロジックのユニットテスト。
 *
 * graph に対象 surface が存在する場合は 2 往復目（candidates()）を呼ばないこと、
 * graph に存在しない場合は candidates() を呼ぶことを確認する。
 *
 * graph は [CompositionGraph.buildFromPaths] で target surface を含む経路を直接構築する。
 * 実辞書・LLM は使わない。
 */
class TwoStepToolLoopResolverTest {

    // region: 条件付き 2 往復目の呼び出し有無

    /**
     * 1 往復目（convert）が graph 上に存在する surface を返した場合は
     * 2 往復目（candidates）を呼ばないことを確認する。
     *
     * graph に "天気" を含む経路を事前構築し、convert() が "天気" を返すように設定する。
     * このとき candidates() は呼ばれないことを assert する。
     */
    @Test
    fun secondPassNotCalledWhenFirstPassResultExistsInGraph() {
        val targetSurface = "天気"
        val lexeme = buildLexeme(surface = targetSurface, reading = "テンキ")
        val graph = CompositionGraph.buildFromPaths(
            reading = "てんき",
            paths = listOf(100L to listOf(lexeme)),
        )

        val provider = RecordingConversionProvider(convertResult = targetSurface)
        val resolver = TwoStepToolLoopResolver(provider)
        val state = buildStateWithGraph(reading = "てんき", graph = graph)
        val request = buildRequest(state)

        runBlocking { resolver.propose(request) }

        assertFalse(
            provider.candidatesCalled,
            "graph 上に存在する surface を 1 往復目が返した場合は candidates() を呼ばないこと",
        )
    }

    /**
     * 1 往復目（convert）が graph 上に存在しない surface を返した場合は
     * 2 往復目（candidates）を呼ぶことを確認する。
     *
     * graph に "天気" のみを含む経路を構築し、convert() が "転機"（graph に無い）を返すように設定する。
     * このとき candidates() が呼ばれることを assert する。
     */
    @Test
    fun secondPassCalledWhenFirstPassResultNotInGraph() {
        val existingSurface = "天気"
        val lexeme = buildLexeme(surface = existingSurface, reading = "テンキ")
        val graph = CompositionGraph.buildFromPaths(
            reading = "てんき",
            paths = listOf(100L to listOf(lexeme)),
        )

        val provider = RecordingConversionProvider(convertResult = "転機") // graph に存在しない
        val resolver = TwoStepToolLoopResolver(provider)
        val state = buildStateWithGraph(reading = "てんき", graph = graph)
        val request = buildRequest(state)

        runBlocking { resolver.propose(request) }

        assertTrue(
            provider.candidatesCalled,
            "graph に存在しない surface を 1 往復目が返した場合は candidates() を呼ぶこと",
        )
    }

    // endregion

    // region: helper

    private fun buildStateWithGraph(reading: String, graph: CompositionGraph): CompositionState {
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

    private fun buildLexeme(surface: String, reading: String): LexemeEntry {
        return LexemeEntry(
            surface = surface,
            reading = reading,
            lcAttr = 0,
            rcAttr = 0,
            posId = 0,
            wcost = 100,
        )
    }

    // endregion
}

/**
 * テスト用の記録型 [ConversionProvider]。
 *
 * [convert] は [convertResult] を返す。[candidates] が呼ばれたかどうかを [candidatesCalled] で記録する。
 * candidates が呼ばれた場合は空 JSON を返し、テスト検証後に 2 往復目が起動されたことを確認できる。
 */
private class RecordingConversionProvider(
    private val convertResult: String,
) : ConversionProvider {

    /** candidates() が呼ばれたかどうかを記録するフラグ。 */
    var candidatesCalled: Boolean = false
        private set

    override suspend fun convert(request: ConversionRequest): String = convertResult

    override suspend fun candidates(request: WordCandidateRequest): String {
        candidatesCalled = true
        return """{"candidates":[]}"""
    }

    override suspend fun rerank(request: me.matsumo.romaflow.core.ime.RerankRequest): Int = -1

    override suspend fun rerankFactorized(
        request: me.matsumo.romaflow.core.ime.shadow.FactorizedRerankRequest,
    ): me.matsumo.romaflow.core.ime.shadow.FactorizedRerankResult {
        return me.matsumo.romaflow.core.ime.shadow.FactorizedRerankResult(choices = emptyMap())
    }
}
