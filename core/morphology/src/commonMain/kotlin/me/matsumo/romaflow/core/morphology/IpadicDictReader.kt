package me.matsumo.romaflow.core.morphology

/**
 * momiji 同梱 IPADIC の sys.dic を自前パースし、全辞書エントリを列挙する公開 API。
 *
 * 生バイト列の取得は [loadSysDicBytes]（target ごとの expect/actual）に委ね、解析自体は
 * [SysDicParser] の純関数で行う。読み（reading）→ 表層形の逆引き index 化は後続タスクの
 * 責務であり、ここでは「読みが空/"*" でない全エントリの列挙」までを提供する。
 *
 * decode + parse + DFS は重いため [readEntries] の結果を初回のみ計算し内部にキャッシュする。
 */
open class IpadicDictReader {

    private val cachedEntries: List<IpadicEntry> by lazy { parseAllEntries() }

    /**
     * sys.dic 全体を走査し、列挙可能な全エントリを返す。
     *
     * 読みが空または "*" のエントリは除外する。重複（同綴り・同読みの別 token など）は
     * この段階では除外しない。
     */
    open fun readEntries(): List<IpadicEntry> = cachedEntries

    private fun parseAllEntries(): List<IpadicEntry> {
        val bytes = loadSysDicBytes()

        return SysDicParser.parseEntries(bytes)
    }
}
