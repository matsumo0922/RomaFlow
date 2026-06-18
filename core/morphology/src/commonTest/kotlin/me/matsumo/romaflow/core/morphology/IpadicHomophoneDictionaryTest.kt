package me.matsumo.romaflow.core.morphology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IpadicHomophoneDictionaryTest {

    @Test
    fun buildReverseIndex_normalizesKatakanaReadingToHiraganaKey() {
        val entries = listOf(
            IpadicEntry(surface = "東京", reading = "トウキョウ", wcost = 100),
        )

        val index = IpadicHomophoneDictionary.buildReverseIndex(entries)

        assertEquals(listOf("東京"), index["とうきょう"])
        assertTrue(index["トウキョウ"] == null, "カタカナキーでは引けないこと")
    }

    @Test
    fun buildReverseIndex_ordersSurfacesByWcostAscending() {
        val entries = listOf(
            IpadicEntry(surface = "格子", reading = "コウシ", wcost = 300),
            IpadicEntry(surface = "講師", reading = "コウシ", wcost = 100),
            IpadicEntry(surface = "孔子", reading = "コウシ", wcost = 200),
        )

        val index = IpadicHomophoneDictionary.buildReverseIndex(entries)

        assertEquals(listOf("講師", "孔子", "格子"), index["こうし"])
    }

    @Test
    fun buildReverseIndex_keepsInsertionOrderForEqualWcost() {
        val entries = listOf(
            IpadicEntry(surface = "甲", reading = "コウ", wcost = 100),
            IpadicEntry(surface = "乙", reading = "コウ", wcost = 100),
            IpadicEntry(surface = "丙", reading = "コウ", wcost = 100),
        )

        val index = IpadicHomophoneDictionary.buildReverseIndex(entries)

        assertEquals(listOf("甲", "乙", "丙"), index["こう"])
    }

    @Test
    fun buildReverseIndex_dedupesSameSurfaceKeepingMinimumWcost() {
        val entries = listOf(
            IpadicEntry(surface = "講師", reading = "コウシ", wcost = 500),
            IpadicEntry(surface = "格子", reading = "コウシ", wcost = 200),
            IpadicEntry(surface = "講師", reading = "コウシ", wcost = 100),
        )

        val index = IpadicHomophoneDictionary.buildReverseIndex(entries)

        assertEquals(listOf("講師", "格子"), index["こうし"])
    }

    @Test
    fun buildReverseIndex_capsCandidatesPerReading() {
        val overCap = IpadicHomophoneDictionary.MAX_CANDIDATES_PER_READING + 5
        val entries = (0 until overCap).map { index ->
            IpadicEntry(surface = "表層$index", reading = "ヨミ", wcost = index)
        }

        val candidates = IpadicHomophoneDictionary.buildReverseIndex(entries)["よみ"].orEmpty()

        assertEquals(IpadicHomophoneDictionary.MAX_CANDIDATES_PER_READING, candidates.size)
        assertEquals("表層0", candidates.first())
    }

    @Test
    fun buildReverseIndex_excludesSurfaceEqualToKatakanaReading() {
        val entries = listOf(
            IpadicEntry(surface = "ー", reading = "ー", wcost = 100),
            IpadicEntry(surface = "東京", reading = "トウキョウ", wcost = 200),
        )

        val index = IpadicHomophoneDictionary.buildReverseIndex(entries)

        assertTrue(index["ー"] == null, "表層形が読みと同一の素通しエントリは除外されること")
        assertEquals(listOf("東京"), index["とうきょう"])
    }

    @Test
    fun homophoneCandidates_returnsEmptyForUnknownReading() {
        val dictionary = IpadicHomophoneDictionary(FakeIpadicDictReader(emptyList()))

        assertEquals(emptyList(), dictionary.homophoneCandidates("みとうろく"))
    }

    @Test
    fun homophoneCandidates_normalizesKatakanaArgumentToHiraganaLookup() {
        val entries = listOf(
            IpadicEntry(surface = "東京", reading = "トウキョウ", wcost = 100),
        )
        val dictionary = IpadicHomophoneDictionary(FakeIpadicDictReader(entries))

        assertEquals(listOf("東京"), dictionary.homophoneCandidates("トウキョウ"))
        assertEquals(listOf("東京"), dictionary.homophoneCandidates("とうきょう"))
    }
}

/** 合成エントリを返すテスト用の [IpadicDictReader]。実 sys.dic を読まず即座に確定値を返す。 */
private class FakeIpadicDictReader(private val entries: List<IpadicEntry>) : IpadicDictReader() {

    override fun readEntries(): List<IpadicEntry> = entries
}
