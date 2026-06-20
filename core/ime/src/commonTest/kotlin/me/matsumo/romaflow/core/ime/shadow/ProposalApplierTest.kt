package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.ime.SourceSpan
import me.matsumo.romaflow.core.morphology.LexemeEntry
import me.matsumo.romaflow.core.morphology.LexemeMatch
import me.matsumo.romaflow.core.morphology.ReadingLexicon
import me.matsumo.romaflow.core.morphology.ZeroConnectionCostProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [ProposalApplier] の検証フロー（§6）のユニットテスト。
 *
 * 実辞書・LLM は使わず、stub [ReadingLexicon] と [ZeroConnectionCostProvider] で
 * 決定論的に挙動を検証する。
 */
class ProposalApplierTest {

    // region: KeepCurrent / Failure は no-op

    /**
     * [ResolutionProposal.KeepCurrent] は状態を変えないことを確認する。
     */
    @Test
    fun keepCurrentReturnsUnchangedState() {
        val applier = buildApplier()
        val state = buildStateWithGraph("てんき", "天気", "テンキ")
        val request = buildRequest(state)

        val result = applier.apply(
            proposal = ResolutionProposal.KeepCurrent,
            request = request,
            state = state,
            currentInputRevision = 0,
            currentGraphRevision = 0,
            currentCandidatePackDigest = 0,
        )

        assertEquals(state, result, "KeepCurrent は状態を変えないこと")
    }

    /**
     * [ResolutionProposal.Failure] は状態を変えないことを確認する。
     */
    @Test
    fun failureReturnsUnchangedState() {
        val applier = buildApplier()
        val state = buildStateWithGraph("てんき", "天気", "テンキ")
        val request = buildRequest(state)

        val result = applier.apply(
            proposal = ResolutionProposal.Failure(FailureKind.NetworkError),
            request = request,
            state = state,
            currentInputRevision = 0,
            currentGraphRevision = 0,
            currentCandidatePackDigest = 0,
        )

        assertEquals(state, result, "Failure は状態を変えないこと")
    }

    // endregion

    // region: stale リクエスト

    /**
     * inputRevision が不一致の stale リクエストは状態を変えないことを確認する。
     */
    @Test
    fun staleInputRevisionReturnsUnchangedState() {
        val applier = buildApplier()
        val state = buildStateWithGraph("てんき", "天気", "テンキ")
        val request = buildRequest(state, inputRevision = 0)

        val result = applier.apply(
            proposal = ResolutionProposal.SelectExistingPath(CompositionGraph.PathId(0)),
            request = request,
            state = state,
            currentInputRevision = 1, // 不一致
            currentGraphRevision = 0,
            currentCandidatePackDigest = 0,
        )

        assertEquals(state, result, "inputRevision 不一致で no-op になること")
    }

    /**
     * graphRevision が不一致の stale リクエストは状態を変えないことを確認する。
     */
    @Test
    fun staleGraphRevisionReturnsUnchangedState() {
        val applier = buildApplier()
        val state = buildStateWithGraph("てんき", "天気", "テンキ")
        val request = buildRequest(state, graphRevision = 0)

        val result = applier.apply(
            proposal = ResolutionProposal.SelectExistingPath(CompositionGraph.PathId(0)),
            request = request,
            state = state,
            currentInputRevision = 0,
            currentGraphRevision = 1, // 不一致
            currentCandidatePackDigest = 0,
        )

        assertEquals(state, result, "graphRevision 不一致で no-op になること")
    }

    /**
     * candidatePackDigest が不一致の stale リクエストは状態を変えないことを確認する。
     *
     * inputRevision / graphRevision が一致していても、candidatePackDigest が異なる場合は
     * stale と判定して no-op になることを確認する（3-way revision 全要素の検証）。
     */
    @Test
    fun staleCandidatePackDigestReturnsUnchangedState() {
        val applier = buildApplier()
        val state = buildStateWithGraph("てんき", "天気", "テンキ")
        val request = buildRequest(state, candidatePackDigest = 0)

        val result = applier.apply(
            proposal = ResolutionProposal.SelectExistingPath(CompositionGraph.PathId(0)),
            request = request,
            state = state,
            currentInputRevision = 0,
            currentGraphRevision = 0,
            currentCandidatePackDigest = 1, // 不一致
        )

        assertEquals(state, result, "candidatePackDigest 不一致で no-op になること")
    }

    // endregion

    // region: SelectExistingPath

    /**
     * [ResolutionProposal.SelectExistingPath] が graph 上の有効な pathId の場合、
     * selectedPathId が更新されることを確認する。
     */
    @Test
    fun selectExistingPathUpdatesSelectedPathId() {
        val lexeme0 = buildLexeme("天気", "テンキ")
        val lexeme1 = buildLexeme("転機", "テンキ")
        val graph = CompositionGraph.buildFromPaths(
            reading = "てんき",
            paths = listOf(
                0L to listOf(lexeme0),
                100L to listOf(lexeme1),
            ),
        )
        val state = buildStateFromGraph(graph, selectedPathId = CompositionGraph.PathId(0))
        val applier = buildApplier()
        val request = buildRequest(state)

        val result = applier.apply(
            proposal = ResolutionProposal.SelectExistingPath(CompositionGraph.PathId(1)),
            request = request,
            state = state,
            currentInputRevision = 0,
            currentGraphRevision = 0,
            currentCandidatePackDigest = 0,
        )

        assertEquals(CompositionGraph.PathId(1), result.selectedPathId, "selectedPathId が rank-1 に変わること")
    }

    /**
     * [ResolutionProposal.SelectExistingPath] で存在しない pathId を指定した場合は no-op になることを確認する。
     */
    @Test
    fun selectExistingPathWithInvalidPathIdIsNoop() {
        val state = buildStateWithGraph("てんき", "天気", "テンキ")
        val applier = buildApplier()
        val request = buildRequest(state)

        val result = applier.apply(
            proposal = ResolutionProposal.SelectExistingPath(CompositionGraph.PathId(99)),
            request = request,
            state = state,
            currentInputRevision = 0,
            currentGraphRevision = 0,
            currentCandidatePackDigest = 0,
        )

        assertEquals(state, result, "存在しない pathId への SelectExistingPath は no-op になること")
    }

    // endregion

    // region: ProposeJointCorrection

    /**
     * [ResolutionProposal.ProposeJointCorrection] に preferredSurface がある場合、
     * 格子上に一致する経路が存在すれば selectedPathId が更新されることを確認する。
     */
    @Test
    fun jointCorrectionWithPreferredSurfaceUpdatesStateWhenPathExists() {
        val lexicon = FixedLexicon(
            mapOf(
                "てんき" to listOf(
                    LexemeEntry("天気", "テンキ", 0, 0, 0, 100),
                    LexemeEntry("転機", "テンキ", 0, 0, 0, 200),
                ),
            ),
        )
        val applier = buildApplier(lexicon = lexicon)
        val state = buildEmptyConvertedState(reading = "てんき")
        val request = buildRequest(state)

        val result = applier.apply(
            proposal = ResolutionProposal.ProposeJointCorrection(
                sourceSpan = SourceSpan(0, 3),
                intendedReading = "てんき",
                preferredSurface = "天気",
            ),
            request = request,
            state = state,
            currentInputRevision = 0,
            currentGraphRevision = 0,
            currentCandidatePackDigest = 0,
        )

        assertTrue(result.isConverted, "変換済みになること")

        val selectedPath = result.graph.pathOrNull(requireNotNull(result.selectedPathId))

        assertNotNull(selectedPath, "selectedPath が取得できること")

        val surface = buildSurface(selectedPath)

        assertEquals("天気", surface, "preferredSurface '天気' が採用されること")
    }

    /**
     * [ResolutionProposal.ProposeJointCorrection] で preferredSurface が格子上に存在しない場合は
     * no-op（捏造不採用）になることを確認する。
     */
    @Test
    fun jointCorrectionWithNonexistentPreferredSurfaceIsNoop() {
        val lexicon = FixedLexicon(
            mapOf(
                "てんき" to listOf(LexemeEntry("天気", "テンキ", 0, 0, 0, 100)),
            ),
        )
        val applier = buildApplier(lexicon = lexicon)
        val state = buildEmptyConvertedState(reading = "てんき")
        val request = buildRequest(state)

        val result = applier.apply(
            proposal = ResolutionProposal.ProposeJointCorrection(
                sourceSpan = SourceSpan(0, 3),
                intendedReading = "てんき",
                preferredSurface = "捏造された表層形", // 格子上に存在しない
            ),
            request = request,
            state = state,
            currentInputRevision = 0,
            currentGraphRevision = 0,
            currentCandidatePackDigest = 0,
        )

        assertEquals(state, result, "存在しない preferredSurface は no-op（捏造不採用）になること")
    }

    /**
     * [ResolutionProposal.ProposeJointCorrection] に preferredSurface がない場合、
     * cross-hypothesis argmin で最良経路が採用されることを確認する。
     */
    @Test
    fun jointCorrectionWithoutPreferredSurfaceUsesArgmin() {
        val lexicon = FixedLexicon(
            mapOf(
                "てんき" to listOf(LexemeEntry("天気", "テンキ", 0, 0, 0, 100)),
            ),
        )
        val applier = buildApplier(lexicon = lexicon)
        val state = buildEmptyConvertedState(reading = "てんき")
        val request = buildRequest(state)

        val result = applier.apply(
            proposal = ResolutionProposal.ProposeJointCorrection(
                sourceSpan = SourceSpan(0, 3),
                intendedReading = "てんき",
                preferredSurface = null,
            ),
            request = request,
            state = state,
            currentInputRevision = 0,
            currentGraphRevision = 0,
            currentCandidatePackDigest = 0,
        )

        assertTrue(result.isConverted, "cross-hypothesis argmin で変換済みになること")
    }

    /**
     * intendedReading が空の場合は no-op になることを確認する。
     */
    @Test
    fun jointCorrectionWithEmptyIntendedReadingIsNoop() {
        val applier = buildApplier()
        val state = buildEmptyConvertedState(reading = "てんき")
        val request = buildRequest(state)

        val result = applier.apply(
            proposal = ResolutionProposal.ProposeJointCorrection(
                sourceSpan = SourceSpan(0, 3),
                intendedReading = "  ", // 空白のみ
                preferredSurface = null,
            ),
            request = request,
            state = state,
            currentInputRevision = 0,
            currentGraphRevision = 0,
            currentCandidatePackDigest = 0,
        )

        assertEquals(state, result, "空の intendedReading は no-op になること")
    }

    // endregion

    // region: N-best top-16 圏外の surface でも格子上に在れば採用される

    /**
     * N-best top-16 に入らなくても格子上に完全経路が存在する surface は採用されることを確認する。
     *
     * 同一 reading に対して distinct surface の lexeme を 17 件用意し、
     * wcost を 0..16 と設定する（wcost が大きいほど辞書コスト上位から遠い）。
     * target surface を wcost=16（最大 = N-best top-16 圏外）にする。
     *
     * [findMinCostPathForSurface] はこの target を見つけられるが、
     * 旧来の rankAllCandidates(n=16) ではベスト16が wcost 0..15 で埋まるため target が現れない。
     * applier が target を preferredSurface にした [ResolutionProposal.ProposeJointCorrection] を
     * 受け取ったとき、no-op にならず target surface を採用することを assert する。
     */
    @Test
    fun preferredSurfaceOutsideNBestTop16IsAdopted() {
        // wcost 0..16 の 17 件の lexeme。surface は "表層0"〜"表層16"。target は "表層16"（wcost 最大）。
        val surfaces = (0..16).map { index -> "表層$index" }
        val lexemes = surfaces.mapIndexed { index, surface ->
            LexemeEntry(
                surface = surface,
                reading = "ア",
                lcAttr = 0,
                rcAttr = 0,
                posId = 0,
                wcost = index * 100,
            )
        }
        val targetSurface = "表層16" // wcost=1600 = 17 件中最大 → N-best top-16 に入らない

        val lexicon = MultiLexemeOnReadingLexicon(reading = "あ", lexemes = lexemes)
        val applier = buildApplier(lexicon = lexicon)
        val state = buildEmptyConvertedState(reading = "あ")
        val request = buildRequest(state)

        val result = applier.apply(
            proposal = ResolutionProposal.ProposeJointCorrection(
                sourceSpan = SourceSpan(0, 1),
                intendedReading = "あ",
                preferredSurface = targetSurface,
            ),
            request = request,
            state = state,
            currentInputRevision = 0,
            currentGraphRevision = 0,
            currentCandidatePackDigest = 0,
        )

        assertTrue(result.isConverted, "top-16 圏外でも格子上に在れば変換済みになること")

        val selectedPath = result.graph.pathOrNull(requireNotNull(result.selectedPathId))

        assertNotNull(selectedPath, "selectedPath が取得できること")

        val surface = buildSurface(selectedPath)

        assertEquals(targetSurface, surface, "N-best top-16 圏外の target surface が採用されること")
    }

    // endregion

    // region: cross-hypothesis 不変条件

    /**
     * [CrossHypothesisDecoder] を経由して無修正 path と LLM 提案 path を cross-hypothesis で比較する。
     *
     * 無修正 reading（てんき）と LLM 提案 reading（てんき）が同一の場合は
     * 単一仮説でデコードし、正しく採用されることを確認する。
     */
    @Test
    fun crossHypothesisWithSameReadingUsesSingleHypothesis() {
        val lexicon = FixedLexicon(
            mapOf(
                "てんき" to listOf(LexemeEntry("天気", "テンキ", 0, 0, 0, 100)),
            ),
        )
        val applier = buildApplier(lexicon = lexicon)
        val state = buildEmptyConvertedState(reading = "てんき")
        val request = buildRequest(state)

        val result = applier.apply(
            proposal = ResolutionProposal.ProposeJointCorrection(
                sourceSpan = SourceSpan(0, 3),
                intendedReading = "てんき", // 無修正と同一
                preferredSurface = "天気",
            ),
            request = request,
            state = state,
            currentInputRevision = 0,
            currentGraphRevision = 0,
            currentCandidatePackDigest = 0,
        )

        assertTrue(result.isConverted, "同一 reading でも正しく採用されること")
    }

    // endregion

    // region: helper

    private fun buildApplier(lexicon: ReadingLexicon = EmptyReadingLexicon): ProposalApplier {
        return ProposalApplier(lexicon = lexicon, costProvider = ZeroConnectionCostProvider)
    }

    private fun buildStateWithGraph(
        reading: String,
        surface: String,
        katakanReading: String,
    ): CompositionState {
        val lexeme = buildLexeme(surface, katakanReading)
        val graph = CompositionGraph.buildFromPaths(
            reading = reading,
            paths = listOf(0L to listOf(lexeme)),
        )

        return buildStateFromGraph(graph, CompositionGraph.PathId(0))
    }

    private fun buildStateFromGraph(
        graph: CompositionGraph,
        selectedPathId: CompositionGraph.PathId,
    ): CompositionState {
        return CompositionState.empty().copy(
            graph = graph,
            selectedPathId = selectedPathId,
        )
    }

    private fun buildEmptyConvertedState(reading: String): CompositionState {
        return CompositionState.empty().copy(
            source = me.matsumo.romaflow.core.ime.CompositionSource.withFrozenPrefix(
                frozenPrefix = reading,
                revision = 0,
            ),
        )
    }

    private fun buildRequest(
        state: CompositionState,
        inputRevision: Int = 0,
        graphRevision: Int = 0,
        candidatePackDigest: Int = 0,
    ): ResolutionRequest {
        return ResolutionRequest(
            state = state,
            inputRevision = inputRevision,
            graphRevision = graphRevision,
            candidatePackDigest = candidatePackDigest,
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
 * テスト用: 指定した reading に対して複数の lexeme を返す [ReadingLexicon]。
 *
 * [reading] に完全一致する位置から始まる substring が [reading] と一致するときに
 * [lexemes] の全件を返す。同一 reading に対して distinct surface の lexeme を
 * 多数注入したいテストで使用する（N-best top-16 圏外テスト等）。
 */
private class MultiLexemeOnReadingLexicon(
    private val reading: String,
    private val lexemes: List<LexemeEntry>,
) : ReadingLexicon {

    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> {
        val endOffset = startOffset + this.reading.length

        if (endOffset > reading.length) return emptyList()

        val slice = reading.substring(startOffset, endOffset)

        if (slice != this.reading) return emptyList()

        return lexemes.map { lexeme -> LexemeMatch(readingEndOffset = endOffset, lexeme = lexeme) }
    }
}

/**
 * テスト用: 固定の lexeme リストを返す [ReadingLexicon]。
 *
 * [readingToLexemes] の reading に完全一致するときにのみ lexeme を返す。
 * 複数文字の reading でも対応する。
 */
private class FixedLexicon(
    private val readingToLexemes: Map<String, List<LexemeEntry>>,
) : ReadingLexicon {

    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> {
        val results = mutableListOf<LexemeMatch>()

        for ((key, lexemes) in readingToLexemes) {
            val endOffset = startOffset + key.length

            if (endOffset > reading.length) continue

            val slice = reading.substring(startOffset, endOffset)

            if (slice != key) continue

            for (lexeme in lexemes) {
                results.add(LexemeMatch(readingEndOffset = endOffset, lexeme = lexeme))
            }
        }

        return results
    }
}
