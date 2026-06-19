package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.ime.ConversionProvider
import me.matsumo.romaflow.core.ime.ConversionRequest
import me.matsumo.romaflow.core.ime.SourceSpan

/**
 * 全文 1 往復 resolver（§8(1) strategy 1）。
 *
 * 既存の [ConversionProvider.convert]（call1）をラップし、全文変換を 1 往復で行う。
 * 返ってきた漢字列を**直接採用せず**、[ResolutionProposal.ProposeJointCorrection] として
 * [ProposalApplier] へ渡すことで §6 の検証フロー（reading 格子に解決できる場合のみ採用）を経由させる（§8/§9）。
 *
 * ## 役割（§9 比較対象）
 * - A-5 cutover まで残す比較対象（call1 そのまま）。
 * - call1 が返す全文漢字列を preferredSurface として格子探索し、存在する場合のみ採用。
 * - 格子上に一致する完全経路がなければ不採用（捏造防止）。
 * - 戦略 (2)/(3) と同一 corpus で top-1 / latency を比較する（§10 の実測）。
 *
 * ## provider API の不変
 * [ConversionProvider] の API は変更しない。resolver から呼ぶのみ（§7 スコープ外）。
 */
internal class LegacyFullTextResolver(
    private val conversionProvider: ConversionProvider,
) : ConversionResolver {

    override suspend fun propose(request: ResolutionRequest): ResolutionProposal {
        val state = request.state
        val reading = state.reading

        if (reading.isBlank()) {
            return ResolutionProposal.KeepCurrent
        }

        // prefix lock がある場合は確定済み prefix を prefixContext として渡し、tail だけを変換させる
        val prefixContext = state.pinnedConstraint.pinnedSurface
        val conversionRequest = ConversionRequest(
            readingInput = reading.substring(state.pinnedConstraint.lockedPrefixBoundary.coerceIn(0, reading.length)),
            prefixContext = prefixContext,
        )

        val convertedResult = runCatching { conversionProvider.convert(conversionRequest) }
        val converted = convertedResult.getOrNull()

        if (converted == null) {
            return ResolutionProposal.Failure(kind = FailureKind.NetworkError)
        }

        if (converted.isBlank()) {
            return ResolutionProposal.Failure(kind = FailureKind.NoSuitablePath)
        }

        // 全文漢字列を preferredSurface として格子探索に渡す（直接採用しない）
        return ResolutionProposal.ProposeJointCorrection(
            sourceSpan = SourceSpan(
                fromAtomIndex = state.pinnedConstraint.lockedPrefixBoundary,
                toAtomIndex = reading.length,
            ),
            intendedReading = reading.substring(state.pinnedConstraint.lockedPrefixBoundary.coerceIn(0, reading.length)),
            preferredSurface = converted.trim(),
        )
    }
}
