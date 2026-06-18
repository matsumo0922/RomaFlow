package me.matsumo.romaflow.core.morphology

/**
 * 読み（ひらがな）から同音異義の表層候補を引く逆引き辞書の境界。
 *
 * engine（後続タスク）はこの interface に依存し、本番実装 [IpadicHomophoneDictionary] と
 * テスト用の fake を差し替えられるようにする。具体的な辞書ソース（IPADIC か否か）は
 * 利用側に漏らさない。
 */
interface HomophoneDictionary {

    /**
     * [reading]（ひらがな）に対応する表層候補を優先度順で返す。
     *
     * 優先度は出現しやすさ（単語コスト昇順）に基づく。該当する候補が無い場合は空リストを返す。
     */
    fun homophoneCandidates(reading: String): List<String>
}
