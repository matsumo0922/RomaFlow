package me.matsumo.romaflow.core.morphology

import kotlin.test.Test
import kotlin.test.assertTrue

class IpadicHomophoneDictionaryIntegrationTest {

    private val dictionary = IpadicHomophoneDictionary()

    @Test
    fun homophoneCandidates_returnsMultipleKanjiForKoushiReading() {
        dictionary.ensureReady()

        val candidates = dictionary.homophoneCandidates(READING_KOUSHI)
        val matchedCandidates = KOUSHI_CANDIDATES.filter(candidates::contains)

        assertTrue(
            matchedCandidates.size >= MINIMUM_KOUSHI_MATCHES,
            "読み「$READING_KOUSHI」の候補に漢字候補が複数含まれること（一致=$matchedCandidates）",
        )
    }

    @Test
    fun homophoneCandidates_includesTokyoForTokyoReading() {
        dictionary.ensureReady()

        val candidates = dictionary.homophoneCandidates(READING_TOKYO)

        assertTrue(
            candidates.contains(SURFACE_TOKYO),
            "読み「$READING_TOKYO」の候補に「$SURFACE_TOKYO」が含まれること（候補=${candidates.take(SAMPLE_LIMIT)}）",
        )
    }

    @Test
    fun homophoneCandidates_capsCandidatesPerReading() {
        dictionary.ensureReady()

        val candidates = dictionary.homophoneCandidates(READING_KOUSHI)

        assertTrue(
            candidates.size <= IpadicHomophoneDictionary.MAX_CANDIDATES_PER_READING,
            "候補数が cap 以下であること（実測=${candidates.size}）",
        )
    }

    private companion object {
        /** 検証に使う読み（こうし）。 */
        const val READING_KOUSHI = "こうし"

        /** 「こうし」の同音異義候補のうち期待されるもの。 */
        val KOUSHI_CANDIDATES = listOf("講師", "格子", "孔子")

        /** 「こうし」候補で一致を要求する最小件数。 */
        const val MINIMUM_KOUSHI_MATCHES = 2

        /** 検証に使う読み（とうきょう）。 */
        const val READING_TOKYO = "とうきょう"

        /** 「とうきょう」に含まれているべき表層形。 */
        const val SURFACE_TOKYO = "東京"

        /** アサーションメッセージに載せる候補のサンプル件数。 */
        const val SAMPLE_LIMIT = 10
    }
}
