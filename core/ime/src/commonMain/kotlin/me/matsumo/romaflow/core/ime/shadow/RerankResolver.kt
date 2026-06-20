package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.ime.ConversionProvider
import me.matsumo.romaflow.core.ime.RerankRequest
import me.matsumo.romaflow.core.ime.SourceSpan
import me.matsumo.romaflow.core.morphology.ConnectionCostProvider
import me.matsumo.romaflow.core.morphology.ReadingLexicon

/**
 * graph 由来 N-best から LLM に index で選ばせる rerank 戦略 resolver（§A-rerank）。
 *
 * ## 設計原則（surface-carry）
 * LLM に変換文字列を「生成」させず、格子から導出した候補一覧の中から「index で選ばせる」ことで
 * 構造的に捏造を不可にする。返ってきた index に対応する表層文字列を
 * [ResolutionProposal.ProposeJointCorrection]（preferredSurface）として返す。
 *
 * ## 失敗時の挙動
 * rerank 呼び出し失敗（ネットワークエラー・API キー未設定等）または index が範囲外の場合は、
 * graph の Viterbi 1位（rank-0 最安経路）の表層を採用する。
 *
 * ## 既定 resolver
 * A-5 cutover 後は [LegacyFullTextResolver] に代わり本 resolver を live 経路で使用する。
 * [LegacyFullTextResolver] は A-4 比較用に残す。
 *
 * @param conversionProvider LLM 呼び出しを担う provider。rerank（call3）を呼ぶ。
 * @param lexicon 辞書（[CompositionGraph] 構築用）。
 * @param costProvider 連接コスト provider（Viterbi デコード用）。
 * @param nBest N-best 件数（デフォルト: 16）。
 */
internal class RerankResolver(
    private val conversionProvider: ConversionProvider,
    private val lexicon: ReadingLexicon,
    private val costProvider: ConnectionCostProvider,
    private val nBest: Int = DEFAULT_N_BEST,
) : ConversionResolver {

    override suspend fun propose(request: ResolutionRequest): ResolutionProposal {
        val state = request.state
        val reading = state.reading

        if (reading.isBlank()) {
            return ResolutionProposal.KeepCurrent
        }

        // prefix lock がある場合は tail だけを変換対象とする（LegacyFullTextResolver と同様）
        val prefixContext = state.pinnedConstraint.pinnedSurface
        val prefixBoundary = state.pinnedConstraint.lockedPrefixBoundary.coerceIn(0, reading.length)
        val tailReading = reading.substring(prefixBoundary)

        if (tailReading.isBlank()) {
            return ResolutionProposal.KeepCurrent
        }

        // tail の reading で graph を構築し N-best 候補を取得する
        val graph = CompositionGraph.build(
            reading = tailReading,
            lexicon = lexicon,
            costProvider = costProvider,
            nBest = nBest,
        )

        if (!graph.hasValidPath) {
            return ResolutionProposal.Failure(kind = FailureKind.NoSuitablePath)
        }

        // graph から重複なし候補表層リストを構築する
        val candidateSession = CandidateSession.buildFrom(graph, nextSessionId = 0)
        val candidates = candidateSession.entries.map { it.surface }

        // LLM に index で選ばせる（失敗時・範囲外は -1）
        val rerankRequest = RerankRequest(
            reading = tailReading,
            prefixContext = prefixContext,
            candidates = candidates,
        )
        val rerankResult = runCatching { conversionProvider.rerank(rerankRequest) }
        val selectedIndex = rerankResult.getOrElse { -1 }

        val preferredSurface = selectPreferredSurface(graph, candidateSession, selectedIndex)

        return ResolutionProposal.ProposeJointCorrection(
            sourceSpan = SourceSpan(
                fromAtomIndex = prefixBoundary,
                toAtomIndex = reading.length,
            ),
            intendedReading = tailReading,
            preferredSurface = preferredSurface,
        )
    }

    /**
     * rerank index から優先表層を決定する。
     *
     * [selectedIndex] が有効範囲内（0 以上かつ候補数未満）なら対応する候補表層を返す。
     * 無効（-1 またはその他の範囲外）の場合は [graph] の Viterbi 1位（rank-0）の表層を返す。
     * graph が空の場合は null を返す（呼び出し側で ProposeJointCorrection の preferredSurface = null 扱い）。
     */
    private fun selectPreferredSurface(
        graph: CompositionGraph,
        candidateSession: CandidateSession,
        selectedIndex: Int,
    ): String? {
        val isValidIndex = selectedIndex in 0 until candidateSession.count

        if (isValidIndex) {
            return candidateSession.surfaceOrNull(selectedIndex)
        }

        // 失敗時フォールバック: Viterbi 1位（rank-0）の表層
        val bestPathId = graph.bestPathId ?: return null
        val bestPath = graph.pathOrNull(bestPathId) ?: return null

        return buildSurfaceFromPath(bestPath)
    }

    /**
     * lexeme 経路の surface を連結して表示文字列を構築する。
     */
    private fun buildSurfaceFromPath(path: List<me.matsumo.romaflow.core.morphology.LexemeEntry>): String {
        val builder = StringBuilder()

        for (lexeme in path) {
            builder.append(lexeme.surface)
        }

        return builder.toString()
    }

    private companion object {
        /** デフォルトの N-best 件数。goal ファイルの確定設計（K≈16）に従う。 */
        const val DEFAULT_N_BEST = 16
    }
}
