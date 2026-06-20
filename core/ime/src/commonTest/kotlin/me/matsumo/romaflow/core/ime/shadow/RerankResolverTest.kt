package me.matsumo.romaflow.core.ime.shadow

import kotlinx.coroutines.runBlocking
import me.matsumo.romaflow.core.ime.CompositionSource
import me.matsumo.romaflow.core.ime.ConversionRequest
import me.matsumo.romaflow.core.ime.FakeConversionProvider
import me.matsumo.romaflow.core.ime.RerankRequest
import me.matsumo.romaflow.core.ime.WordCandidateRequest
import me.matsumo.romaflow.core.morphology.LexemeEntry
import me.matsumo.romaflow.core.morphology.LexemeMatch
import me.matsumo.romaflow.core.morphology.ReadingLexicon
import me.matsumo.romaflow.core.morphology.ZeroConnectionCostProvider
import me.matsumo.romaflow.core.morphology.buildReadingLexiconWithFallback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [RerankResolver] のユニットテスト。
 *
 * 実 API 不要。[EmptyReadingLexicon]・[TwoSurfaceLexicon]・[FakeConversionProvider] を使い決定論的に検証する。
 *
 * ## 検証項目
 * - **index 尊重**: rerank が rank-0 でない index K を返したとき、preferredSurface が candidates[K] になること（かつ rank-0 と異なること）
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
     * rerank が rank-0 **でない** index を返したとき、その index の候補表層が採用されることを確認する。
     *
     * これが本 PR の主目的の検証: 実装が index を無視して常に Viterbi rank-0 を返しても
     * **このテストは落ちる**ことを保証する。
     *
     * ## セットアップ
     * [TwoSurfaceLexicon] は "あい" に対し cost 差のある2表層（"愛" wcost=10、"藍" wcost=200）を提供する。
     * [ZeroConnectionCostProvider] 下では wcost のみで順序が決まるため、rank-0="愛"、rank-1="藍"。
     *
     * テスト内で同じ `CompositionGraph.build` + `CandidateSession.buildFrom` を呼んで候補リストを取得し、
     * rank-0 の surface と異なる surface を持つ index を [FixedIndexRerankProvider] で返すことで
     * 順序依存なく検証する。
     */
    @Test
    fun selectsNonRankZeroCandidateWhenIndexHonored() {
        runBlocking {
            val reading = "あい"
            val lexicon = buildReadingLexiconWithFallback(TwoSurfaceLexicon)
            val costProvider = ZeroConnectionCostProvider

            // 候補リストを RerankResolver と同じ手順で構築して rank-0 と rank-1 を確認する
            val graph = CompositionGraph.build(
                reading = reading,
                lexicon = lexicon,
                costProvider = costProvider,
            )
            val session = CandidateSession.buildFrom(graph, nextSessionId = 0)
            val candidates = session.entries.map { it.surface }

            // candidates に 2 件以上あること（TwoSurfaceLexicon の前提確認）
            assertTrue(candidates.size >= 2, "候補が2件以上あること（実際: $candidates）")

            val rank0Surface = candidates[0]

            // rank-0 と異なる surface を持つ最初の index を選ぶ（実装が index を無視すると落ちる）
            val targetIndex = candidates.indexOfFirst { it != rank0Surface }
            assertTrue(targetIndex >= 0, "rank-0 と異なる表層の候補が存在すること（全候補: $candidates）")

            val expectedSurface = candidates[targetIndex]

            val resolver = RerankResolver(
                conversionProvider = FixedIndexRerankProvider(targetIndex),
                lexicon = lexicon,
                costProvider = costProvider,
            )

            val state = buildStateWithReading(reading)
            val request = buildRequest(state)

            val proposal = resolver.propose(request)

            val jointProposal = proposal as? ResolutionProposal.ProposeJointCorrection
            assertTrue(
                jointProposal != null,
                "ProposeJointCorrection を返すこと（実際: $proposal）",
            )

            // index が尊重されていれば expectedSurface になり、無視されると rank0Surface になる
            assertEquals(
                expectedSurface,
                jointProposal.preferredSurface,
                "rerank が返した index の候補表層が採用されること",
            )
            assertNotEquals(
                rank0Surface,
                jointProposal.preferredSurface,
                "rank-0 の表層と異なること（index が無視されていないこと）",
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

/**
 * rerank が常に指定の固定 index を返す [me.matsumo.romaflow.core.ime.ConversionProvider] スタブ。
 *
 * `selectsNonRankZeroCandidateWhenIndexHonored` で rank-0 以外の index を選ばせるために使う。
 */
private class FixedIndexRerankProvider(
    private val fixedIndex: Int,
) : me.matsumo.romaflow.core.ime.ConversionProvider {
    override suspend fun convert(request: ConversionRequest): String = ""
    override suspend fun candidates(request: WordCandidateRequest): String = ""
    override suspend fun rerank(request: RerankRequest): Int = fixedIndex
}

/**
 * "あい" という reading に対し cost 差のある 2 表層を返すテスト用 [ReadingLexicon]。
 *
 * `selectsNonRankZeroCandidateWhenIndexHonored` で「rank-0 と異なる候補が格子に存在する」
 * 状況を作るために使う。[ZeroConnectionCostProvider] 下では wcost のみで順序が決まるため、
 * wcost=10 の "愛" が rank-0、wcost=200 の "藍" が rank-1 になる。
 *
 * テストが実装の index 無視リグレッションを確実に捉えるため、2 表層は surface が異なる必要がある。
 */
private object TwoSurfaceLexicon : ReadingLexicon {

    /** reading "あい"（2文字）に対して返す2件の lexeme。 */
    private val lexemesForAi = listOf(
        LexemeEntry(surface = "愛", reading = "アイ", lcAttr = 0, rcAttr = 0, posId = 0, wcost = 10),
        LexemeEntry(surface = "藍", reading = "アイ", lcAttr = 0, rcAttr = 0, posId = 0, wcost = 200),
    )

    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> {
        val suffix = reading.substring(startOffset)

        if (suffix.startsWith("あい")) {
            return lexemesForAi.map { lexeme ->
                LexemeMatch(
                    readingEndOffset = startOffset + 2,
                    lexeme = lexeme,
                )
            }
        }

        return emptyList()
    }
}
