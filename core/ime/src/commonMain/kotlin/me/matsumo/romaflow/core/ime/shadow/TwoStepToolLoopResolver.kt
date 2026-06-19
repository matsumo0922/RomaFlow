package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.ime.ConversionProvider
import me.matsumo.romaflow.core.ime.ConversionRequest
import me.matsumo.romaflow.core.ime.SourceSpan
import me.matsumo.romaflow.core.ime.WordCandidateRequest

/**
 * reading proposal + 条件付き 2 往復 resolver（§8(3)/(4) strategy 3）。
 *
 * 2 往復の構成（§8 に準拠）:
 * - 1 往復目: LLM に全文変換（call1）を依頼し、読みの提案（全文漢字列）を得る。
 * - 2 往復目（条件付き）: 1 往復目の結果が「再確認が必要」と判断された場合のみ、
 *   同音異義語候補（call2）を依頼して最終候補を絞り込む。
 *
 * ## 条件付き 2 往復目の条件（§8 列挙）
 * A-4 では §8 の列挙に従い以下の場合のみ 2 往復目を行う:
 * 1. 1 往復目の漢字列が reading と一致（未変換のまま返ってきた = 変換できなかった可能性）
 * 2. 1 往復目の漢字列が格子上に完全経路として存在しない
 *
 * ## 制限（A-4 版）
 * §5 の「1 レスポンス 1 decision」制限により、重複・交差 span・無限ループ規則が未整備のため、
 * この版は 1 往復目の結果を優先し、条件を満たす場合のみ 2 往復目を実行する。
 * 無限ループは 2 往復上限で防止する。
 *
 * ## provider API の不変
 * [ConversionProvider] の API は変更しない。resolver から呼ぶのみ。
 */
internal class TwoStepToolLoopResolver(
    private val conversionProvider: ConversionProvider,
) : ConversionResolver {

    override suspend fun propose(request: ResolutionRequest): ResolutionProposal {
        val state = request.state
        val reading = state.reading

        if (reading.isBlank()) {
            return ResolutionProposal.KeepCurrent
        }

        val boundary = state.pinnedConstraint.lockedPrefixBoundary.coerceIn(0, reading.length)
        val tailReading = reading.substring(boundary)

        if (tailReading.isBlank()) {
            return ResolutionProposal.KeepCurrent
        }

        val prefixSurface = state.pinnedConstraint.pinnedSurface

        // 1 往復目: 全文変換（call1）
        val firstPassResult = runFirstPass(tailReading, prefixSurface)

        if (firstPassResult == null) {
            return ResolutionProposal.Failure(kind = FailureKind.NetworkError)
        }

        if (firstPassResult.isBlank()) {
            return ResolutionProposal.Failure(kind = FailureKind.NoSuitablePath)
        }

        // 条件チェック: 2 往復目が必要かどうか
        val needsSecondPass = checkNeedsSecondPass(tailReading, firstPassResult, state)

        if (!needsSecondPass) {
            // 1 往復目の結果をそのまま ProposeJointCorrection として返す
            return buildProposal(
                sourceSpan = SourceSpan(fromAtomIndex = boundary, toAtomIndex = reading.length),
                tailReading = tailReading,
                preferredSurface = firstPassResult.trim(),
            )
        }

        // 2 往復目: 同音異義語候補（call2）で再確認
        val secondPassResult = runSecondPass(tailReading, context = prefixSurface + firstPassResult)

        if (secondPassResult != null && secondPassResult.isNotBlank()) {
            return buildProposal(
                sourceSpan = SourceSpan(fromAtomIndex = boundary, toAtomIndex = reading.length),
                tailReading = tailReading,
                preferredSurface = secondPassResult,
            )
        }

        // 2 往復目が失敗した場合は 1 往復目の結果で継続
        return buildProposal(
            sourceSpan = SourceSpan(fromAtomIndex = boundary, toAtomIndex = reading.length),
            tailReading = tailReading,
            preferredSurface = firstPassResult.trim(),
        )
    }

    /**
     * 1 往復目: 全文変換（call1）を実行する。
     *
     * 失敗した場合は null を返す。
     */
    private suspend fun runFirstPass(tailReading: String, prefixSurface: String): String? {
        val request = ConversionRequest(
            readingInput = tailReading,
            prefixContext = prefixSurface,
        )

        return runCatching { conversionProvider.convert(request) }.getOrNull()
    }

    /**
     * 2 往復目が必要かどうかを判定する。
     *
     * 以下の条件のいずれかを満たす場合に true を返す（§8 列挙）:
     * 1. 1 往復目の漢字列が tail reading と同一（変換されていない）
     * 2. 1 往復目の漢字列が graph 上の既存経路に存在しない
     */
    private fun checkNeedsSecondPass(
        tailReading: String,
        firstPassResult: String,
        state: CompositionState,
    ): Boolean {
        val trimmedResult = firstPassResult.trim()

        // 条件1: 変換されていない（読みと同一）
        val isUnchanged = trimmedResult == tailReading

        if (isUnchanged) {
            return true
        }

        // 条件2: graph 上の既存経路に存在しない（格子ミス）
        val existsInGraph = checkExistsInGraph(trimmedResult, state)

        return !existsInGraph
    }

    /**
     * [surface] が graph 上の既存経路に存在するかチェックする。
     *
     * graph が空または未変換の場合は false を返す。
     */
    private fun checkExistsInGraph(surface: String, state: CompositionState): Boolean {
        for (pathId in state.graph.allPathIds) {
            val path = state.graph.pathOrNull(pathId) ?: continue
            val pathSurface = buildSurface(path)

            if (pathSurface == surface) {
                return true
            }
        }

        return false
    }

    /**
     * 2 往復目: 同音異義語候補（call2）を実行し、先頭候補を返す。
     *
     * 失敗した場合は null を返す。
     */
    private suspend fun runSecondPass(tailReading: String, context: String): String? {
        val request = WordCandidateRequest(reading = tailReading, context = context)
        val jsonText = runCatching { conversionProvider.candidates(request) }.getOrNull()

        if (jsonText.isNullOrBlank()) {
            return null
        }

        return extractFirstCandidate(jsonText)
    }

    /**
     * 提案を構築する。preferredSurface を格子上の完全経路探索ヒントとして ProposeJointCorrection に包む。
     */
    private fun buildProposal(
        sourceSpan: SourceSpan,
        tailReading: String,
        preferredSurface: String,
    ): ResolutionProposal.ProposeJointCorrection {
        return ResolutionProposal.ProposeJointCorrection(
            sourceSpan = sourceSpan,
            intendedReading = tailReading,
            preferredSurface = preferredSurface,
        )
    }

    /**
     * lexeme 経路の surface を連結して表示文字列を作る。
     */
    private fun buildSurface(path: List<me.matsumo.romaflow.core.morphology.LexemeEntry>): String {
        val builder = StringBuilder()

        for (lexeme in path) {
            builder.append(lexeme.surface)
        }

        return builder.toString()
    }

    /**
     * `{"candidates":["候補1","候補2",...]}` 形式の JSON から先頭候補を取り出す。
     *
     * 失敗（parse エラー・空配列）の場合は null を返す。
     */
    private fun extractFirstCandidate(jsonText: String): String? {
        val candidatesPattern = Regex(""""candidates"\s*:\s*\[\s*"([^"]+)"""")
        val matchResult = candidatesPattern.find(jsonText)

        return matchResult?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }
}
