package me.matsumo.romaflow.core.morphology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [MozcHomophoneDictionary] の逆引き挙動（hiragana キー・wcost 昇順・素通し除外・non-blocking）を検証する。
 */
class MozcHomophoneDictionaryTest {

    private fun lexemeOf(surface: String, reading: String, wcost: Int): LexemeEntry {
        return LexemeEntry(
            surface = surface,
            reading = reading,
            lcAttr = 1851,
            rcAttr = 1851,
            posId = 1851,
            wcost = wcost,
        )
    }

    @Test
    fun returnsEmptyBeforeEnsureReady() {
        val dictionary = MozcHomophoneDictionary.fromEntries { listOf(lexemeOf("以下", "いか", 1801)) }

        assertTrue(dictionary.homophoneCandidates("いか").isEmpty())
    }

    @Test
    fun returnsSurfacesSortedByWcostAfterReady() {
        val dictionary = MozcHomophoneDictionary.fromEntries {
            listOf(
                lexemeOf("医科", "いか", 5631),
                lexemeOf("以下", "いか", 1801),
                lexemeOf("異化", "いか", 9000),
            )
        }

        dictionary.ensureReady()

        assertEquals(listOf("以下", "医科", "異化"), dictionary.homophoneCandidates("いか"))
    }

    @Test
    fun keepsKatakanaSurfaceButExcludesPassthrough() {
        val dictionary = MozcHomophoneDictionary.fromEntries {
            listOf(
                lexemeOf("アアルト", "ああると", 7129),
                lexemeOf("ああると", "ああると", 8000),
            )
        }

        dictionary.ensureReady()

        assertEquals(listOf("アアルト"), dictionary.homophoneCandidates("ああると"))
    }

    @Test
    fun normalizesKatakanaQueryToHiraganaKey() {
        val dictionary = MozcHomophoneDictionary.fromEntries { listOf(lexemeOf("以下", "いか", 1801)) }

        dictionary.ensureReady()

        assertEquals(listOf("以下"), dictionary.homophoneCandidates("イカ"))
    }
}
