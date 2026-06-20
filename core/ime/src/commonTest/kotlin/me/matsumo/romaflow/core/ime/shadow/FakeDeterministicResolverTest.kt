package me.matsumo.romaflow.core.ime.shadow

import kotlinx.coroutines.runBlocking
import me.matsumo.romaflow.core.ime.CompositionSource
import me.matsumo.romaflow.core.morphology.LexemeEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [FakeDeterministicResolver] のユニットテスト。
 *
 * 実 API 不要。決定的な変換表から正しい提案が返ることを検証する。
 */
class FakeDeterministicResolverTest {

    /**
     * 変換表に読みが存在する場合、[ResolutionProposal.ProposeJointCorrection] または
     * [ResolutionProposal.SelectExistingPath] が返ることを確認する。
     */
    @Test
    fun proposesForKnownReading() {
        runBlocking {
            val resolver = FakeDeterministicResolver()
            val state = buildStateWithReading("てんき")
            val request = buildRequest(state)

            val proposal = resolver.propose(request)

            val isSuitable = proposal is ResolutionProposal.ProposeJointCorrection ||
                proposal is ResolutionProposal.SelectExistingPath

            assertTrue(isSuitable, "知っている reading に対して提案を返すこと（実際: $proposal）")
        }
    }

    /**
     * 変換表にない読みの場合は [ResolutionProposal.KeepCurrent] が返ることを確認する。
     */
    @Test
    fun keepCurrentForUnknownReading() {
        runBlocking {
            val resolver = FakeDeterministicResolver()
            val state = buildStateWithReading("ふめいなよみ")
            val request = buildRequest(state)

            val proposal = resolver.propose(request)

            assertIs<ResolutionProposal.KeepCurrent>(
                proposal,
                "知らない reading には KeepCurrent を返すこと",
            )
        }
    }

    /**
     * reading が空の場合は [ResolutionProposal.KeepCurrent] が返ることを確認する。
     */
    @Test
    fun keepCurrentForEmptyReading() {
        runBlocking {
            val resolver = FakeDeterministicResolver()
            val state = CompositionState.empty()
            val request = buildRequest(state)

            val proposal = resolver.propose(request)

            assertIs<ResolutionProposal.KeepCurrent>(
                proposal,
                "空の reading には KeepCurrent を返すこと",
            )
        }
    }

    /**
     * graph に既に preferredSurface が存在する場合は [ResolutionProposal.SelectExistingPath] を返すことを確認する。
     */
    @Test
    fun selectExistingPathWhenGraphHasPreferredSurface() {
        runBlocking {
            val resolver = FakeDeterministicResolver()

            // graph に "天気" を含む経路を注入する
            val lexeme = LexemeEntry("天気", "テンキ", 0, 0, 0, 100)
            val graph = CompositionGraph.buildFromPaths(
                reading = "てんき",
                paths = listOf(0L to listOf(lexeme)),
            )
            val state = buildStateWithGraph("てんき", graph)
            val request = buildRequest(state)

            val proposal = resolver.propose(request)

            assertIs<ResolutionProposal.SelectExistingPath>(
                proposal,
                "graph に preferredSurface が存在するなら SelectExistingPath を返すこと",
            )
            assertEquals(
                CompositionGraph.PathId(0),
                (proposal as ResolutionProposal.SelectExistingPath).pathId,
                "rank-0 の経路 ID が返ること",
            )
        }
    }

    /**
     * graph に preferredSurface がない場合は [ResolutionProposal.ProposeJointCorrection] を返すことを確認する。
     */
    @Test
    fun proposeJointCorrectionWhenGraphLacksPreferredSurface() {
        runBlocking {
            val resolver = FakeDeterministicResolver()
            val state = buildStateWithReading("てんき") // graph は空（未変換）
            val request = buildRequest(state)

            val proposal = resolver.propose(request)

            assertIs<ResolutionProposal.ProposeJointCorrection>(
                proposal,
                "graph に preferredSurface がなければ ProposeJointCorrection を返すこと",
            )
            assertEquals(
                "天気",
                (proposal as ResolutionProposal.ProposeJointCorrection).preferredSurface,
            )
        }
    }

    // region: helper

    private fun buildStateWithReading(reading: String): CompositionState {
        return CompositionState.empty().copy(
            source = CompositionSource.withFrozenPrefix(frozenPrefix = reading, revision = 0),
        )
    }

    private fun buildStateWithGraph(
        reading: String,
        graph: CompositionGraph,
    ): CompositionState {
        return CompositionState.empty().copy(
            source = CompositionSource.withFrozenPrefix(frozenPrefix = reading, revision = 0),
            graph = graph,
            selectedPathId = CompositionGraph.PathId(0),
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
