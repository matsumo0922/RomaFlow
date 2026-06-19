package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.morphology.ConnectionCostProvider
import me.matsumo.romaflow.core.morphology.CrossHypothesisDecoder
import me.matsumo.romaflow.core.morphology.LexemeEntry
import me.matsumo.romaflow.core.morphology.ReadingHypothesis
import me.matsumo.romaflow.core.morphology.ReadingLexicon
import me.matsumo.romaflow.core.morphology.buildReadingLexiconWithFallback

/**
 * [ResolutionProposal] を §6 の検証フローに従って [CompositionState] に適用する。
 *
 * ## §6 検証フローの概要（手順順）
 * 1. stale 判定（inputRevision / graphRevision / candidatePackDigest が一致するか）
 * 2. reading 空チェック（空なら即 KeepCurrent）
 * 3. proposal の種別に応じた検証・適用
 *    - [ResolutionProposal.KeepCurrent]: no-op
 *    - [ResolutionProposal.Failure]: no-op（状態変更なし）
 *    - [ResolutionProposal.SelectExistingPath]: graph 上の pathId 存在検証 → selectedPathId 原子的交換
 *    - [ResolutionProposal.ProposeJointCorrection]: reading 正規化 → ReadingHypothesis 生成 →
 *      OOV fallback 付き格子 → preferredSurface 一致の完全経路探索 → cross-hypothesis argmin →
 *      selectedPathId / graph 原子的交換
 *
 * ## 不変条件（§3.2/§7）
 * - cross-hypothesis 比較は必ず [CrossHypothesisDecoder] で行う。hypothesis 内に閉じる実装は禁止。
 * - literal/OOV fallback は [buildReadingLexiconWithFallback] を使い、辞書外1文字でもデッドロックしない。
 * - preferredSurface が格子上に存在しなければ不採用（捏造は採用しない）。
 * - LLM の局所提案は active span 全体を覆う完全 reading へ splice して materialize する（§3.2）。
 *   A-4 では span 全体 = reading 全体と等価で扱う（prefix lock 範囲を除いた tail reading）。
 *
 * ## A の argmin の意味（§7）
 * - 辞書 cost は candidate pack の生成・順序付けに使う。
 * - 最終 decision は「検証済みの LLM path ID」。SelectExistingPath の後に辞書 argmin を回して別 path へ戻さない。
 * - LLM の自己申告数値を辞書 cost へ足さない（B の領域）。
 *
 * ## stale リクエスト
 * [apply] は [ResolutionRequest] の revision と現在の [currentInputRevision] / [currentGraphRevision] /
 * [currentCandidatePackDigest] を比較する。不一致の場合は [ResolutionProposal.Failure] と同じ扱い
 * （状態変更なし）とし、現在の state をそのまま返す。
 */
internal class ProposalApplier(
    private val lexicon: ReadingLexicon,
    private val costProvider: ConnectionCostProvider,
    /** LLM が reading を変更したときに加えるタイピングコストのペナルティ。 */
    private val llmTypingCostPenalty: Int = DEFAULT_LLM_TYPING_COST_PENALTY,
) {

    /**
     * [proposal] を §6 検証フローで [state] に適用し、新しい [CompositionState] を返す。
     *
     * 以下のいずれかの場合は [state] をそのまま返す（no-op）:
     * - [proposal] が [ResolutionProposal.KeepCurrent] または [ResolutionProposal.Failure]
     * - stale リクエスト（revision 不一致）
     * - 検証失敗（pathId が存在しない / preferredSurface が格子上に見つからない）
     *
     * @param proposal resolver が返した提案
     * @param request 提案を生成したときの [ResolutionRequest]（stale 判定に使う）
     * @param state 現在の [CompositionState]
     * @param currentInputRevision 現在の入力リビジョン
     * @param currentGraphRevision 現在の graph リビジョン
     * @param currentCandidatePackDigest 現在の候補窓セッション ID
     */
    fun apply(
        proposal: ResolutionProposal,
        request: ResolutionRequest,
        state: CompositionState,
        currentInputRevision: Int,
        currentGraphRevision: Int,
        currentCandidatePackDigest: Int,
    ): CompositionState {
        val isStale = checkIsStale(request, currentInputRevision, currentGraphRevision, currentCandidatePackDigest)

        if (isStale) {
            return state
        }

        return when (proposal) {
            is ResolutionProposal.KeepCurrent -> state
            is ResolutionProposal.Failure -> state
            is ResolutionProposal.SelectExistingPath -> applySelectExistingPath(proposal, state)
            is ResolutionProposal.ProposeJointCorrection -> applyJointCorrection(proposal, state)
        }
    }

    /**
     * stale 判定。
     *
     * リクエスト時の revision と現在の revision を比較して一致しない場合は true を返す。
     * inputRevision / graphRevision / candidatePackDigest の3要素を全て確認する（§5）。
     */
    private fun checkIsStale(
        request: ResolutionRequest,
        currentInputRevision: Int,
        currentGraphRevision: Int,
        currentCandidatePackDigest: Int,
    ): Boolean {
        val inputMismatch = request.inputRevision != currentInputRevision
        val graphMismatch = request.graphRevision != currentGraphRevision
        val digestMismatch = request.candidatePackDigest != currentCandidatePackDigest

        return inputMismatch || graphMismatch || digestMismatch
    }

    /**
     * [ResolutionProposal.SelectExistingPath] を適用する。
     *
     * § 6 step: pathId が graph 上に存在するか確認し、存在すれば selectedPathId を交換する。
     * 存在しない場合は no-op（[state] をそのまま返す）。
     * lock 違反（locked prefix boundary を跨ぐ path の差し替え）は A-4 では簡略化し、
     * path そのものの存在確認のみ行う（A-5 cutover で厳密化）。
     */
    private fun applySelectExistingPath(
        proposal: ResolutionProposal.SelectExistingPath,
        state: CompositionState,
    ): CompositionState {
        val pathExists = state.graph.pathOrNull(proposal.pathId) != null

        if (!pathExists) {
            return state
        }

        return state.copy(selectedPathId = proposal.pathId)
    }

    /**
     * [ResolutionProposal.ProposeJointCorrection] を適用する。
     *
     * §6 の手順（joint correction 分岐）:
     * 1. reading 正規化（trim。空なら no-op）
     * 2. lock 範囲を除いた tail reading を取得
     * 3. LLM 提案 reading を active span 全体へ splice（§3.2）
     * 4. [ReadingHypothesis] を生成（LLM 提案 + 無修正の2仮説）
     * 5. OOV fallback 付き格子（[buildReadingLexiconWithFallback]）で cross-hypothesis 比較
     * 6. preferredSurface が非 null の場合、格子上に一致する完全経路を探索（複数一致は最小コスト）
     * 7. 検証済み経路を [CompositionGraph] に包み selectedPathId と graph を原子的に交換
     * 8. preferredSurface に到達できる経路が存在しない場合は no-op（捏造不採用）
     */
    private fun applyJointCorrection(
        proposal: ResolutionProposal.ProposeJointCorrection,
        state: CompositionState,
    ): CompositionState {
        val intendedReading = proposal.intendedReading.trim()

        if (intendedReading.isEmpty()) {
            return state
        }

        // prefix lock を除いた tail reading（active span 全体）
        val tailReading = extractTailReading(state)

        // LLM 提案 reading を tail span 全体へ splice する（§3.2: 局所提案を完全 reading へ）
        val splicedReading = spliceIntendedReading(proposal, tailReading, intendedReading)

        // 無修正仮説（入力 reading そのまま）と LLM 提案仮説の2仮説を cross-hypothesis 比較
        val hypotheses = buildHypotheses(tailReading, splicedReading)

        // OOV fallback 付き格子で N-best を生成
        val compositeLexicon = buildReadingLexiconWithFallback(lexicon)

        return if (proposal.preferredSurface != null) {
            applyWithPreferredSurface(
                proposal = proposal,
                state = state,
                hypotheses = hypotheses,
                compositeLexicon = compositeLexicon,
            )
        } else {
            applyWithCrossHypothesisArgmin(
                state = state,
                hypotheses = hypotheses,
                compositeLexicon = compositeLexicon,
            )
        }
    }

    /**
     * prefix lock を除いた tail reading を取得する。
     *
     * locked prefix boundary が 0 の場合は reading 全体を返す。
     */
    private fun extractTailReading(state: CompositionState): String {
        val reading = state.reading
        val boundary = state.pinnedConstraint.lockedPrefixBoundary

        return if (boundary > 0) {
            reading.substring(boundary.coerceIn(0, reading.length))
        } else {
            reading
        }
    }

    /**
     * LLM が提案した reading を active span 全体へ splice する（§3.2）。
     *
     * A-4 では activeSpan = tail reading 全体。
     * [sourceSpan] の内部（atom 座標）は A-5 cutover まで使わないため、
     * ここでは intendedReading をそのまま tail span 全体の置換として扱う。
     *
     * intendedReading が tail reading と同じ場合は同一の文字列を返す。
     */
    private fun spliceIntendedReading(
        proposal: ResolutionProposal.ProposeJointCorrection,
        tailReading: String,
        intendedReading: String,
    ): String {
        // A-4: sourceSpan の atom 座標は A-5 まで使わない。
        // intendedReading を tail span 全体の読みとして使う。
        // sourceSpan は型の整合のためのみ存在する（typed-id 境界）。
        val spanIsEmpty = proposal.sourceSpan.isEmpty

        return if (spanIsEmpty) tailReading else intendedReading
    }

    /**
     * 2 つの [ReadingHypothesis] を構築する。
     *
     * - 無修正仮説（typingCost=0）: 入力 reading のまま
     * - LLM 提案仮説（typingCost=[llmTypingCostPenalty]）: spliced reading
     *
     * 両者が同一文字列の場合は無修正仮説1つだけにする（重複計算を避ける）。
     */
    private fun buildHypotheses(
        tailReading: String,
        splicedReading: String,
    ): List<ReadingHypothesis> {
        val baseHypothesis = ReadingHypothesis(reading = tailReading, typingCost = 0)

        if (splicedReading == tailReading) {
            return listOf(baseHypothesis)
        }

        val llmHypothesis = ReadingHypothesis(reading = splicedReading, typingCost = llmTypingCostPenalty)

        return listOf(baseHypothesis, llmHypothesis)
    }

    /**
     * preferredSurface が指定された場合の適用。
     *
     * 全仮説の N-best 経路を横断して preferredSurface に完全一致する経路を探す。
     * 複数一致の場合は最小コスト経路を採用（§7）。
     * 一致経路が存在しない場合は no-op（捏造不採用）。
     *
     * preferredSurface が見つかった場合は新しい [CompositionGraph] を包んで
     * graph と selectedPathId を原子的に交換する。
     */
    private fun applyWithPreferredSurface(
        proposal: ResolutionProposal.ProposeJointCorrection,
        state: CompositionState,
        hypotheses: List<ReadingHypothesis>,
        compositeLexicon: ReadingLexicon,
    ): CompositionState {
        val preferredSurface = requireNotNull(proposal.preferredSurface)
        val nBestCount = CROSS_HYPOTHESIS_N_BEST

        val allRanked = CrossHypothesisDecoder.rankAllCandidates(
            hypotheses = hypotheses,
            lexicon = compositeLexicon,
            costProvider = costProvider,
            n = nBestCount,
        )

        // preferredSurface に完全一致する候補を cost 昇順で検索
        val matchedEntry = allRanked.firstOrNull { (_, _, path) ->
            buildSurfaceFromPath(path) == preferredSurface
        }

        if (matchedEntry == null) {
            // preferredSurface が格子上に存在しない → 捏造不採用
            return state
        }

        val (totalCost, _, matchedPath) = matchedEntry
        val matchedReading = buildReadingFromPath(matchedPath)

        return rebuildGraphWithPath(state, matchedReading, matchedPath, totalCost)
    }

    /**
     * preferredSurface が指定されていない場合の適用。
     *
     * [CrossHypothesisDecoder.selectBest] で argmin を取り、最良仮説の経路を採用する。
     * 無修正 path と LLM 提案 path を跨いで比較する（§7: cross-hypothesis 不変条件）。
     *
     * デコード失敗（全仮説で経路なし）の場合は no-op。
     */
    private fun applyWithCrossHypothesisArgmin(
        state: CompositionState,
        hypotheses: List<ReadingHypothesis>,
        compositeLexicon: ReadingLexicon,
    ): CompositionState {
        val bestResult = CrossHypothesisDecoder.selectBest(
            hypotheses = hypotheses,
            lexicon = compositeLexicon,
            costProvider = costProvider,
        )

        if (bestResult == null) {
            return state
        }

        val bestPath = bestResult.path
        val bestReading = bestResult.hypothesis.reading

        return rebuildGraphWithPath(state, bestReading, bestPath, bestResult.totalCost)
    }

    /**
     * 検証済みの経路で [CompositionGraph] を再構築し [CompositionState] を原子的に更新する。
     *
     * [path] を単一 rank-0 の経路として新しい [CompositionGraph] に包み、
     * [state] の graph と selectedPathId を同時に交換する。
     * clauseAnchor は経路変更で無効になるため null にリセットする。
     */
    private fun rebuildGraphWithPath(
        state: CompositionState,
        reading: String,
        path: List<LexemeEntry>,
        totalCost: Long,
    ): CompositionState {
        val newGraph = CompositionGraph.buildFromPaths(
            reading = reading,
            paths = listOf(totalCost to path),
        )
        val newSelectedPathId = CompositionGraph.PathId(0)

        return state.copy(
            graph = newGraph,
            selectedPathId = newSelectedPathId,
            clauseAnchor = null,
        )
    }

    /**
     * lexeme 経路の surface を連結して表示文字列を作る。
     */
    private fun buildSurfaceFromPath(path: List<LexemeEntry>): String {
        val builder = StringBuilder()

        for (lexeme in path) {
            builder.append(lexeme.surface)
        }

        return builder.toString()
    }

    /**
     * lexeme 経路の reading（カタカナ）を連結して全 reading を復元する。
     *
     * [LexemeEntry.reading] はカタカナで格納されるが、reading の比較は格子の reading 文字列ベースで行うため
     * ここでは連結のみ行う。実際の格子 reading との整合は reading 正規化（trim）で対応済み。
     */
    private fun buildReadingFromPath(path: List<LexemeEntry>): String {
        val builder = StringBuilder()

        for (lexeme in path) {
            builder.append(lexeme.reading)
        }

        return builder.toString()
    }

    private companion object {
        /** LLM が reading を変更した場合に加えるタイピングコストのデフォルトペナルティ。 */
        const val DEFAULT_LLM_TYPING_COST_PENALTY = 5_000

        /** cross-hypothesis 比較で生成する N-best の件数。 */
        const val CROSS_HYPOTHESIS_N_BEST = 16
    }
}
