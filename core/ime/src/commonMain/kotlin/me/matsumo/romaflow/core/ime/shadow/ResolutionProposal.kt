package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.ime.SourceSpan

/**
 * resolver が返す変換提案。
 *
 * KEEP と失敗を明示分離した sealed interface（§5）。
 * `actions:[]` のような曖昧な空リストを返す設計は避け、各ケースを型で区別する。
 *
 * ## 型 ID 境界（§5 Nit）
 * 全 variant は typed-id（[CompositionGraph.PathId] / [SourceSpan]）のみを保持する。
 * String 化した wire-id やリモート adapter 向けの JSON は含めない。
 *
 * ## 適用順序
 * 提案は [ProposalApplier] で §6 の手順に従って検証・適用する。
 * resolver が提案を返した後、engine が applier を呼ぶ。
 */
internal sealed interface ResolutionProposal {

    /**
     * 現在の状態を変えない。
     *
     * graph 上の選択経路が既に最良であり、変更不要の場合に返す。
     */
    data object KeepCurrent : ResolutionProposal

    /**
     * graph 上の既存 path を typed-id で選択する。
     *
     * [pathId] は [CompositionGraph.allPathIds] の範囲内でなければならない。
     * applier が §6 step1 で graph との整合を確認する。
     *
     * @param pathId 選択する経路の ID（rank は cost 昇順）。
     * @param rationale 選択理由の説明（ログ / デバッグ用。core 処理には影響しない）。
     */
    data class SelectExistingPath(
        /** 選択する経路 ID。[CompositionGraph.PathId] は typed-id（wire-id でない）。 */
        val pathId: CompositionGraph.PathId,
        /** 選択理由（デバッグ / ログ用）。 */
        val rationale: String = "",
    ) : ResolutionProposal

    /**
     * graph に存在しない新しい reading で格子を再解決することを提案する（joint correction）。
     *
     * resolver が「入力 reading とは異なる reading で変換すべき」と判断した場合に返す。
     * [sourceSpan] は提案対象の source atoms の範囲（typed-id）。
     * [intendedReading] は resolver が意図する reading（ひらがな）。
     * [preferredSurface] が非 null の場合は、applier が格子探索時に優先的に採用する表層形。
     *
     * ## applier の責務（§6）
     * 1. [intendedReading] から [ReadingHypothesis] を生成する（typingCost = 固定ペナルティ）。
     * 2. 無修正 path（元の reading）との cross-hypothesis 比較を [CrossHypothesisDecoder] で行う。
     * 3. [preferredSurface] に到達できる完全経路が格子上に存在するか検索する。
     * 4. 見つかった経路を [CompositionState.selectedPathId] に原子的に交換する。
     *
     * ## 不採用条件
     * - [intendedReading] が元の reading と同一かつ [preferredSurface] が null → [KeepCurrent] 相当。
     * - [preferredSurface] が格子上に存在しない → 提案を不採用。捏造は採用しない（§8/§9）。
     */
    data class ProposeJointCorrection(
        /**
         * 提案対象の source atoms のスパン（typed-id）。
         *
         * A-4 では serialization 層スタブ相当として typed-id を保持するだけで、
         * 実際の atom 操作は A-5 cutover 後に実装する。
         */
        val sourceSpan: SourceSpan,
        /**
         * resolver が意図する reading（ひらがな）。
         *
         * 入力 reading と異なる場合、cross-hypothesis 比較を行う。
         */
        val intendedReading: String,
        /**
         * resolver が優先する表層形（漢字 or かな）。
         *
         * null の場合は格子上の最小コスト経路を採用する。
         * 非 null の場合は格子上に一致する完全経路があるか検索し、存在しなければ不採用。
         */
        val preferredSurface: String? = null,
    ) : ResolutionProposal

    /**
     * resolver が提案を生成できなかったことを示す。
     *
     * ネットワークエラー・タイムアウト・API キー未設定・読み空白などの場合に返す。
     * applier は [Failure] を受け取ったら [KeepCurrent] と同じ扱い（状態変更なし）とする。
     */
    data class Failure(
        /** 失敗の種別。 */
        val kind: FailureKind,
    ) : ResolutionProposal
}

/**
 * resolver の失敗種別。
 *
 * - [NetworkError]: ネットワーク呼び出しが失敗した（タイムアウト・通信エラー含む）。
 * - [ApiKeyNotConfigured]: API キーが未設定のため呼び出しをスキップした。
 * - [InvalidReading]: reading が空または無効で、解決できなかった。
 * - [NoSuitablePath]: 格子上に条件を満たす経路が存在しなかった（捏造不採用）。
 * - [StaleRequest]: リクエストの revision が現在の状態と不一致で、提案が陳腐化している。
 */
internal enum class FailureKind {
    /** ネットワーク呼び出しが失敗した。 */
    NetworkError,

    /** API キーが未設定。 */
    ApiKeyNotConfigured,

    /** reading が空または無効。 */
    InvalidReading,

    /** 格子上に条件を満たす経路が存在しなかった。 */
    NoSuitablePath,

    /** リクエストの revision が現在の状態と不一致（stale）。 */
    StaleRequest,
}
