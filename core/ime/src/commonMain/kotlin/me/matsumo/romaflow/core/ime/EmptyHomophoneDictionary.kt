package me.matsumo.romaflow.core.ime

import me.matsumo.romaflow.core.morphology.HomophoneDictionary

/**
 * 何も返さない no-op の [HomophoneDictionary]。
 *
 * [RomaFlowEngine] の internal constructor の既定値として使い、テストや辞書を注入しない呼び出しで
 * 本番の [me.matsumo.romaflow.core.morphology.IpadicHomophoneDictionary]（数十 MB の index 構築）を
 * 走らせないための軽量フォールバック。
 */
internal object EmptyHomophoneDictionary : HomophoneDictionary {

    override fun ensureReady() {
        // 構築すべき index を持たないため no-op。
    }

    override fun homophoneCandidates(reading: String): List<String> {
        return emptyList()
    }
}
