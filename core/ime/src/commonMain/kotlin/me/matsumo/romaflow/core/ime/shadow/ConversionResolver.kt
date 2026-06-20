package me.matsumo.romaflow.core.ime.shadow

/**
 * shadow エンジンの変換 resolver 抽象（§5 seam）。
 *
 * [propose] を呼ぶと現在の変換状態（[ResolutionRequest]）を受け取り、
 * graph 上の経路選択や joint reading 修正の提案（[ResolutionProposal]）を返す。
 *
 * 提案は [ProposalApplier] で §6 の検証フローを通して [CompositionState] に適用する。
 * resolver 自体は状態を持たず、提案の受け入れ・適用は engine 側の責務とする。
 *
 * ## shadow mode 担保
 * このインターフェースは `me.matsumo.romaflow.core.ime.shadow` パッケージの `internal` であり、
 * A-5 cutover まで live の `RomaFlowEngine` とは完全に独立する。
 *
 * ## 4 戦略（§8/§10）
 * - [FakeDeterministicResolver]: 実 API 不要。テスト・ハーネスの主役。
 * - [LegacyFullTextResolver]: §8(1) 全文 1 往復（call1 ラップ）。
 * - [OneShotJointPatchResolver]: §8(2) joint proposal 1 往復。
 * - [TwoStepToolLoopResolver]: §8(3)/(4) reading proposal + 条件付き 2 往復。
 */
internal interface ConversionResolver {

    /**
     * [request] に対する変換提案を返す。
     *
     * - ネットワーク呼び出しを含む可能性があるため suspend 関数とする。
     * - 提案を生成できない場合（エラー・API キー未設定等）は [ResolutionProposal.Failure] を返す。
     * - 変更不要と判断した場合は [ResolutionProposal.KeepCurrent] を返す。
     * - 状態変更しない。状態の更新は engine が [ProposalApplier] を通して行う。
     */
    suspend fun propose(request: ResolutionRequest): ResolutionProposal
}
