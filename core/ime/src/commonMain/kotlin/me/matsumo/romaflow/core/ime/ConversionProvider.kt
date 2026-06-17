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
}
