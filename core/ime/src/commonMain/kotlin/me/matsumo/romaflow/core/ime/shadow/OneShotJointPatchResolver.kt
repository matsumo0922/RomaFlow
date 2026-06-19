package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.ime.ConversionProvider
import me.matsumo.romaflow.core.ime.SourceSpan
import me.matsumo.romaflow.core.ime.WordCandidateRequest

/**
 * joint proposal 1 往復 resolver（§8(2) strategy 2）。
 *
 * LLM に `{reading, preferred_surface}` の形式で 1 往復で提案を出させ、
 * backend（[ProposalApplier]）が「reading 格子に preferred_surface へ至る完全 path があるか」を
 * 検証して解決する（§8(2)）。
 *
 * 表層は「候補ヒント」であり、格子に到達できない捏造は不採用（§8/§9）。
 *
 * ## 実装方針（A-4 版）
 * [ConversionProvider.candidates]（call2）を候補生成に使う。
 * candidates が返す JSON（`{"candidates":[...]}` 形式）の先頭候補を preferredSurface として採用する。
 * JSON の parse に失敗した場合は [ResolutionProposal.Failure] を返す。
 *
 * A-4 では A 型最有力予想の戦略（§8(2)）として [FakeDeterministicResolver] と比較対象になる。
 * 実 LLM テストは二重 opt-in で実行する。通常ユニットテストは [FakeDeterministicResolver] で代替する。
 *
 * ## provider API の不変
 * [ConversionProvider.candidates] は API を変えずにラップのみ行う。
 */
internal class OneShotJointPatchResolver(
    private val conversionProvider: ConversionProvider,
) : ConversionResolver {

    override suspend fun propose(request: ResolutionRequest): ResolutionProposal {
        val state = request.state
        val reading = state.reading

        if (reading.isBlank()) {
            return ResolutionProposal.KeepCurrent
        }

        // prefix lock がある場合は tail reading だけを候補生成に渡す
        val boundary = state.pinnedConstraint.lockedPrefixBoundary.coerceIn(0, reading.length)
        val tailReading = reading.substring(boundary)

        if (tailReading.isBlank()) {
            return ResolutionProposal.KeepCurrent
        }

        val prefixSurface = state.pinnedConstraint.pinnedSurface
        val candidateRequest = WordCandidateRequest(
            reading = tailReading,
            context = prefixSurface,
        )

        val candidatesJson = runCatching { conversionProvider.candidates(candidateRequest) }
        val jsonText = candidatesJson.getOrNull()

        if (jsonText == null) {
            return ResolutionProposal.Failure(kind = FailureKind.NetworkError)
        }

        if (jsonText.isBlank()) {
            return ResolutionProposal.Failure(kind = FailureKind.NoSuitablePath)
        }

        val preferredSurface = extractFirstCandidate(jsonText)
            ?: return ResolutionProposal.Failure(kind = FailureKind.NoSuitablePath)

        return ResolutionProposal.ProposeJointCorrection(
            sourceSpan = SourceSpan(fromAtomIndex = boundary, toAtomIndex = reading.length),
            intendedReading = tailReading,
            preferredSurface = preferredSurface,
        )
    }

    /**
     * `{"candidates":["候補1","候補2",...]}` 形式の JSON から先頭候補を取り出す。
     *
     * 失敗（parse エラー・空配列）の場合は null を返す。
     * JSON ライブラリへの依存を避けるため、簡易な正規表現ベースの抽出を行う。
     * 生産品質では kotlinx.serialization を使うべきだが、A-4 の目的は戦略比較であり、
     * JSON parse の完全正確性は A-5 以降で整備する（§7 割り切り）。
     */
    private fun extractFirstCandidate(jsonText: String): String? {
        // `"candidates":["...","...",...` の最初の要素を取り出す
        val candidatesPattern = Regex(""""candidates"\s*:\s*\[\s*"([^"]+)"""")
        val matchResult = candidatesPattern.find(jsonText)

        return matchResult?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }
}
