package me.matsumo.romaflow.core.ime

/**
 * 打った通りのかな（[ConversionRequest.readingInput]）を漢字交じりの変換結果へ、また選択文節の読みを単語候補へ
 * 変換する provider。
 *
 * Tab 起動の全文変換（call1）と、文節選択中の候補列挙（call2）の両方を担う差し替え可能な seam。AI provider は
 * この interface を実装する。IME プロセスのメインスレッドを塞がないよう各メソッドは suspend とし、実装側で
 * ネットワーク I/O を非同期に行う。公開境界（Swift Export）には出さない module 内部の抽象なので、戻り値の制約は
 * 受けない。
 */
internal interface ConversionProvider {

    /**
     * [request] の readingInput を変換し、表示用の変換結果文字列を返す。
     *
     * 変換に失敗した場合（API key 未設定・ネットワークエラー・タイムアウト等）は空文字を返す。
     * 呼び出し側は空文字を「変換せず据え置き」として扱う。[ConversionRequest.prefixContext] が空でなければ、
     * 前方文脈として加味した上で readingInput を変換する。
     */
    suspend fun convert(request: ConversionRequest): String

    /**
     * call2（単語候補生成）。選択文節の読み（[WordCandidateRequest.reading]）と文脈
     * （[WordCandidateRequest.context]）から、同音異義語・別変換候補を Structured Outputs の生 JSON 文字列
     * `{"candidates":[...]}` として返す。
     *
     * 戻り値はモデルの生出力文字列で、パース・正規化・自明候補とのマージは呼び出し側が行う。失敗した場合
     * （API key 未設定・ネットワークエラー・タイムアウト・blank reading 等）は空文字を返し、呼び出し側は
     * 「候補なし」として扱う。
     */
    suspend fun candidates(request: WordCandidateRequest): String

    /**
     * rerank（call3）。graph 由来の N-best 候補一覧から最も自然な候補の index を1つ選ぶ。
     *
     * LLM に変換文字列を「生成」させず、格子から導出した候補一覧を「index で選ばせる」ことで
     * 構造的に捏造を不可にする（§A-rerank 設計）。
     *
     * 失敗した場合（API key 未設定・ネットワークエラー・タイムアウト・候補空等）は -1 を返す。
     * 呼び出し側は -1 を「辞書 Viterbi 1位（rank-0）で代替」として扱う。
     * 返却 index が候補リストの範囲外の場合も呼び出し側が -1 と同様に扱う。
     */
    suspend fun rerank(request: RerankRequest): Int
}

/**
 * rerank（call3）のリクエスト。
 *
 * graph 由来の N-best 候補を番号付きで提示し、前方文脈に最も自然な候補を1つ選ばせる。
 *
 * @param reading 変換対象のひらがな読み（tail のみ、prefix lock 分は除く）。
 * @param prefixContext 確定済み prefix の表層文字列（lock なしの場合は空文字）。
 * @param candidates 格子から導出した N-best 候補の表層文字列リスト（cost 昇順、dedup 済み）。
 */
internal data class RerankRequest(
    /** 変換対象のひらがな読み（tail のみ）。 */
    val reading: String,
    /** 確定済み prefix の表層（lock なしは空文字）。 */
    val prefixContext: String,
    /** 格子由来の N-best 候補表層リスト（cost 昇順、dedup 済み）。 */
    val candidates: List<String>,
)
