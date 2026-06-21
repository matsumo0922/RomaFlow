package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.ime.ConversionProvider
import me.matsumo.romaflow.core.ime.SourceSpan
import me.matsumo.romaflow.core.morphology.ConnectionCostProvider
import me.matsumo.romaflow.core.morphology.ReadingLatticeDecoder
import me.matsumo.romaflow.core.morphology.ReadingLexicon

/**
 * 全文 tail surface を1個の表層として LLM に提案させ、full lattice で検証する resolver（PR-B）。
 *
 * ## 動作フロー
 * 1. tail reading（locked prefix を除いた変換対象）を取得する。
 * 2. `CompositionGraph.build` で baseline（Viterbi rank-0 経路）を取得する。
 * 3. [ConversionProvider.proposeFullTailSurface] で tail 全体の表層を1個提案させる（call1 相当）。
 * 4. 提案 surface を [ReadingLatticeDecoder.findMinCostPathForSurface] で格子検証する。
 *    - 非 null（格子内に合法な path あり）→ 提案 surface を採用。
 *    - null（格子外・読み長不一致）→ baseline surface に fallback。
 * 5. 採用 surface を [ResolutionProposal.ProposeJointCorrection.preferredSurface] で返す。
 *
 * ## open-surface の格子検証
 * LLM が生成した表層は Reading 長や語彙制約を満たさない可能性がある。
 * `findMinCostPathForSurface` で reading 全体をちょうど実現する path が存在するかを確認し、
 * 存在しない場合は捏造として拒否して baseline に fallback する。
 * trim() を提案前に適用し、付随空白によるミスマッチを防ぐ（PR-A の教訓）。
 *
 * ## fallback
 * - reading / tail が空: [ResolutionProposal.KeepCurrent] を返す。
 * - baseline 取得失敗（graph 無効）: [ResolutionProposal.Failure] を返す。
 * - LLM 失敗 / 空文字 / 格子外提案: baseline surface で [ProposeJointCorrection] を返す。
 *
 * @param conversionProvider LLM 呼び出しを担う provider。proposeFullTailSurface を呼ぶ。
 * @param lexicon 辞書（CompositionGraph 構築用・格子検証用）。
 * @param costProvider 連接コスト provider（Viterbi デコード用・格子検証用）。
 */
internal class FullTailSurfaceResolver(
    private val conversionProvider: ConversionProvider,
    private val lexicon: ReadingLexicon,
    private val costProvider: ConnectionCostProvider,
) : ConversionResolver {

    override suspend fun propose(request: ResolutionRequest): ResolutionProposal {
        val state = request.state
        val reading = state.reading

        if (reading.isBlank()) {
            return ResolutionProposal.KeepCurrent
        }

        val prefixContext = state.pinnedConstraint.pinnedSurface
        val prefixBoundary = state.pinnedConstraint.lockedPrefixBoundary.coerceIn(0, reading.length)
        val tailReading = reading.substring(prefixBoundary)

        if (tailReading.isBlank()) {
            return ResolutionProposal.KeepCurrent
        }

        val graph = CompositionGraph.build(
            reading = tailReading,
            lexicon = lexicon,
            costProvider = costProvider,
        )

        if (!graph.hasValidPath) {
            return ResolutionProposal.Failure(FailureKind.NoSuitablePath)
        }

        val baselinePath = graph.bestPathId?.let { graph.pathOrNull(it) }
            ?: return ResolutionProposal.Failure(FailureKind.NoSuitablePath)
        val baselineSurface = buildSurfaceFromPath(baselinePath)

        val accepted = resolveAcceptedSurface(tailReading, prefixContext, baselineSurface)

        return ResolutionProposal.ProposeJointCorrection(
            sourceSpan = SourceSpan(fromAtomIndex = prefixBoundary, toAtomIndex = reading.length),
            intendedReading = tailReading,
            preferredSurface = accepted,
        )
    }

    /**
     * LLM に tail surface を提案させ、格子検証を通過した surface を返す。
     * 提案が空・格子外・読み長不一致の場合は [baselineSurface] を返す。
     */
    private suspend fun resolveAcceptedSurface(
        tailReading: String,
        prefixContext: String,
        baselineSurface: String,
    ): String {
        val proposed = runCatching {
            conversionProvider.proposeFullTailSurface(tailReading, prefixContext)
        }.getOrElse { "" }.trim()

        if (proposed.isBlank()) {
            return baselineSurface
        }

        val isValidPath = ReadingLatticeDecoder.findMinCostPathForSurface(
            reading = tailReading,
            surface = proposed,
            lexicon = lexicon,
            costProvider = costProvider,
        ) != null

        return if (isValidPath) proposed else baselineSurface
    }

    private fun buildSurfaceFromPath(path: List<me.matsumo.romaflow.core.morphology.LexemeEntry>): String {
        val builder = StringBuilder()

        for (lexeme in path) {
            builder.append(lexeme.surface)
        }

        return builder.toString()
    }
}
