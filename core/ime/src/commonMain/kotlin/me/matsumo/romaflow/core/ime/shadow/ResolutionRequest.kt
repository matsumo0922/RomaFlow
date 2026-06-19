package me.matsumo.romaflow.core.ime.shadow

/**
 * resolver への入力リクエスト。
 *
 * shadow エンジンの現在の変換状態を resolver に渡す際に使う。
 * 生 JSON や wire-id を含めず、typed-id のみを載せる（§5 Nit）。
 *
 * ## revision / digest の分離（§5）
 * - [inputRevision]: source の更新回数。source が変わるたびにインクリメントされる。
 * - [graphRevision]: graph の再構築回数。buildGraph のたびにインクリメントされる。
 * - [candidatePackDigest]: 候補窓セッション ID（CandidateSession.sessionId）。
 *   inputRevision 一つでは source・graph・候補窓の区別が付かないため 3 分離している。
 *
 * ## core 内境界
 * この型は shadow パッケージの `internal` であり、Swift Export に出ない。
 */
internal data class ResolutionRequest(
    /** 現在の変換状態（source / graph / selectedPathId 含む）。 */
    val state: CompositionState,
    /**
     * source 更新カウンタ（単調増加）。
     *
     * source が変更されるたびにインクリメントされる。
     * resolver はこの値を記録しておき、applier 時に一致を確認することで
     * stale な提案の適用を防ぐ。
     */
    val inputRevision: Int,
    /**
     * graph 再構築カウンタ（単調増加）。
     *
     * buildGraph が呼ばれるたびにインクリメントされる。
     * graph と提案の整合を保証するために使う。
     */
    val graphRevision: Int,
    /**
     * 候補窓セッション ID（CandidateSession.sessionId と同値）。
     *
     * 候補窓を開閉するたびに変わる。stale 判定の追加情報として使う。
     */
    val candidatePackDigest: Int,
)
