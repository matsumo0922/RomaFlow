package me.matsumo.romaflow.core.ime

/**
 * 打った通りのかな（[ConversionRequest.readingInput]）を、漢字交じりの変換結果へ変換する provider。
 *
 * Tab 起動の全文変換（call1）でのみ呼ばれる差し替え可能な seam。AI provider はこの interface を実装する。
 * IME プロセスのメインスレッドを塞がないよう [convert] は suspend とし、実装側でネットワーク I/O を非同期に行う。
 * 公開境界（Swift Export）には出さない module 内部の抽象なので、戻り値の制約は受けない。
 */
internal interface ConversionProvider {

    /**
     * [request] の readingInput を変換し、表示用の変換結果文字列を返す。
     *
     * 変換に失敗した場合（API key 未設定・ネットワークエラー・タイムアウト等）は空文字を返す。
     * 呼び出し側は空文字を「変換せず据え置き」として扱う。[ConversionRequest.locked] は B2 の lock 制約で、
     * B1a では常に空のため無視してよい。
     */
    suspend fun convert(request: ConversionRequest): String
}
