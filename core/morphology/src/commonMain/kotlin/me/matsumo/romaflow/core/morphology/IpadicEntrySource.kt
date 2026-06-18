package me.matsumo.romaflow.core.morphology

/**
 * IPADIC 辞書エントリの供給元を表す境界。
 *
 * 逆引き辞書 [IpadicHomophoneDictionary] はこの interface に依存し、本番実装
 * [IpadicDictReader] と、テスト用の合成エントリを返す fake を差し替えられるようにする。
 */
interface IpadicEntrySource {

    /** 列挙可能な全辞書エントリを返す。 */
    fun readEntries(): List<IpadicEntry>
}
