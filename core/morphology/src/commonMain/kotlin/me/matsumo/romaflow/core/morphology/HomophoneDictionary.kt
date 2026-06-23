package me.matsumo.romaflow.core.morphology

/**
 * 読み（ひらがな）から同音異義の表層候補を引く逆引き辞書の境界。
 *
 * engine はこの interface に依存し、本番実装 [MozcHomophoneDictionary] と
 * テスト用の fake を差し替えられるようにする。具体的な辞書ソース（Mozc か否か）は
 * 利用側に漏らさない。
 */
interface HomophoneDictionary {

    /**
     * 重い逆引き index を構築し、以降の [homophoneCandidates] が候補を返せる状態にする。
     *
     * 本番実装では数十万件の辞書を parse する重い処理になるため、呼び出し側は main 以外の
     * スレッドで呼ぶこと。複数回呼んでも構築は1回だけ走る（冪等）。
     */
    fun ensureReady()

    /**
     * [reading]（ひらがな）に対応する表層候補を優先度順で返す。
     *
     * 優先度は出現しやすさ（単語コスト昇順）に基づく。該当する候補が無い場合は空リストを返す。
     * [ensureReady] 完了前は main をブロックしないために常に空リストを返す（non-blocking）。
     */
    fun homophoneCandidates(reading: String): List<String>
}
