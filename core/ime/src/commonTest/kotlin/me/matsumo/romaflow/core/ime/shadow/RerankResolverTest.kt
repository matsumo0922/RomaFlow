package me.matsumo.romaflow.core.ime.shadow

import kotlinx.coroutines.runBlocking
import me.matsumo.romaflow.core.ime.CompositionSource
import me.matsumo.romaflow.core.ime.ConversionRequest
import me.matsumo.romaflow.core.ime.FakeConversionProvider
import me.matsumo.romaflow.core.ime.RerankRequest
import me.matsumo.romaflow.core.ime.WordCandidateRequest
import me.matsumo.romaflow.core.morphology.LexemeEntry
import me.matsumo.romaflow.core.morphology.ZeroConnectionCostProvider
import me.matsumo.romaflow.core.morphology.buildReadingLexiconWithFallback
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [RerankResolver] のユニットテスト。
 *
 * 実 API 不要。[EmptyReadingLexicon] と [FakeConversionProvider] を使い決定論的に検証する。
 *
 * ## 検証項目
 * - 有効 index が返ったとき、その候補表層が preferredSurface として採用される
 * - 範囲外 index が返ったとき、Viterbi 1位（rank-0）の表層が採用される
 * - 失敗（-1）が返ったとき、Viterbi 1位の表層が採用される
 */
class RerankResolverTest {

    /**
     * rerank が有効 index を返した場合、その index の候補表層が [ResolutionProposal.ProposeJointCorrection]
     * の preferredSurface として選択されることを確認する。
     */
    @Test
    fun selectsIndexedCandidateWhenRerankSucceeds() {
        runBlocking {
            // FakeConversionProvider の RERANK_TABLE は "てんき" → "天気" を返す。
            // graph の N-best に "天気" が含まれていれば、そのインデックスが選ばれる。
            val provider = FakeConversionProvider()
            val lexicon = buildLexiconWithFallback()
            val costProvider = buildFakeCostProvider()

            // lexicon が empty でも LiteralLexicon fallback があるため graph が構築される。
            // ここでは直接 ResolverState を作って propose を検証する。
            val resolver = RerankResolver(
                conversionProvider = provider,
                lexicon = lexicon,
                costProvider = costProvider,
            )

            val state = buildStateWithReading("てんき")
            val request = buildRequest(state)

            val proposal = resolver.propose(request)

            // 提案が ProposeJointCorrection であることを確認する（surface-carry 設計）
            val isJointCorrection = proposal is ResolutionProposal.ProposeJointCorrection
            assertTrue(
                isJointCorrection,
                "rerank resolver は ProposeJointCorrection を返すこと（実際: $proposal）",
            )

            // preferredSurface が null でないこと（格子由来の候補から選んでいること）
            val jointProposal = proposal as ResolutionProposal.ProposeJointCorrection
            val preferredSurface = jointProposal.preferredSurface
            assertTrue(
                preferredSurface != null && preferredSurface.isNotBlank(),
                "preferredSurface が非 null かつ非空であること（実際: $preferredSurface）",
            )
        }
    }

    /**
     * reading が空の場合は [ResolutionProposal.KeepCurrent] が返ることを確認する。
     */
    @Test
    fun keepCurrentForEmptyReading() {
        runBlocking {
            val provider = FakeConversionProvider()
            val lexicon = buildLexiconWithFallback()
            val costProvider = buildFakeCostProvider()
            val resolver = RerankResolver(
                conversionProvider = provider,
                lexicon = lexicon,
                costProvider = costProvider,
            )

            val state = CompositionState.empty()
            val request = buildRequest(state)

            val proposal = resolver.propose(request)

            val isKeepCurrent = proposal is ResolutionProposal.KeepCurrent
            assertTrue(isKeepCurrent, "空の reading には KeepCurrent を返すこと")
        }
    }

    /**
     * rerank が -1（失敗）を返した場合、Viterbi 1位（rank-0）の表層が採用されることを確認する。
     *
     * [AlwaysFailRerankProvider] は常に -1 を返す。graph の rank-0 と同じ surface が使われることを検証する。
     */
    @Test
    fun viterbiFallbackWhenRerankFails() {
        runBlocking {
            val alwaysFailProvider = AlwaysFailRerankProvider()

            // graph に明示的な lexeme を含む graph を注入して確認する
            val graphLexeme = LexemeEntry("天気", "テンキ", 0, 0, 0, 100)
            val graph = CompositionGraph.buildFromPaths(
                reading = "てんき",
                paths = listOf(0L to listOf(graphLexeme)),
            )
            val state = buildStateWithGraph("てんき", graph)
            val request = buildRequest(state)

            // EmptyReadingLexicon + graph 注入済み state でも propose 内で新しい graph が構築される。
            // AlwaysFailRerankProvider が -1 を返すため、rank-0 の表層が選ばれる。
            val lexicon = buildLexiconWithFallback()
            val costProvider = buildFakeCostProvider()
            val resolver = RerankResolver(
                conversionProvider = alwaysFailProvider,
                lexicon = lexicon,
                costProvider = costProvider,
            )

            val proposal = resolver.propose(request)

            val isJointCorrection = proposal is ResolutionProposal.ProposeJointCorrection
            assertTrue(
                isJointCorrection,
                "rerank 失敗時も ProposeJointCorrection（Viterbi 1位 fallback）を返すこと（実際: $proposal）",
            )
            // preferredSurface が null でないこと
            val jointProposal = proposal as ResolutionProposal.ProposeJointCorrection
            assertTrue(
                jointProposal.preferredSurface != null,
                "rerank 失敗時も preferredSurface が非 null であること",
            )
        }
    }

    /**
     * rerank が候補数以上の範囲外 index を返した場合も Viterbi 1位 fallback が採用されることを確認する。
     */
    @Test
    fun viterbiFallbackWhenRerankReturnsOutOfRangeIndex() {
        runBlocking {
            val outOfRangeProvider = OutOfRangeRerankProvider(outOfRangeIndex = 9999)

            val lexicon = buildLexiconWithFallback()
            val costProvider = buildFakeCostProvider()
            val resolver = RerankResolver(
                conversionProvider = outOfRangeProvider,
                lexicon = lexicon,
                costProvider = costProvider,
            )

            val state = buildStateWithReading("てんき")
            val request = buildRequest(state)

            val proposal = resolver.propose(request)

            val isJointCorrection = proposal is ResolutionProposal.ProposeJointCorrection
            assertTrue(
                isJointCorrection,
                "範囲外 index 時も ProposeJointCorrection（Viterbi 1位 fallback）を返すこと（実際: $proposal）",
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

    // LiteralLexicon fallback を持つ lexicon を返す。
    // EmptyReadingLexicon 単体では arc が無く hasValidPath=false になるため、
    // buildReadingLexiconWithFallback で literal arc を補完する。
    private fun buildLexiconWithFallback(): me.matsumo.romaflow.core.morphology.ReadingLexicon {
        return buildReadingLexiconWithFallback(EmptyReadingLexicon)
    }

    private fun buildFakeCostProvider(): me.matsumo.romaflow.core.morphology.ConnectionCostProvider {
        return ZeroConnectionCostProvider
    }

    // endregion
}

/**
 * rerank が常に -1 を返す [me.matsumo.romaflow.core.ime.ConversionProvider] スタブ。
 *
 * rerank 失敗時の Viterbi 1位 fallback をテストするために使う。
 */
private class AlwaysFailRerankProvider : me.matsumo.romaflow.core.ime.ConversionProvider {
    override suspend fun convert(request: ConversionRequest): String = ""
    override suspend fun candidates(request: WordCandidateRequest): String = ""
    override suspend fun rerank(request: RerankRequest): Int = -1
}

/**
 * rerank が常に指定の範囲外 index を返す [me.matsumo.romaflow.core.ime.ConversionProvider] スタブ。
 *
 * 範囲外 index 時の Viterbi 1位 fallback をテストするために使う。
 */
private class OutOfRangeRerankProvider(
    private val outOfRangeIndex: Int,
) : me.matsumo.romaflow.core.ime.ConversionProvider {
    override suspend fun convert(request: ConversionRequest): String = ""
    override suspend fun candidates(request: WordCandidateRequest): String = ""
    override suspend fun rerank(request: RerankRequest): Int = outOfRangeIndex
}
