package me.matsumo.romaflow.core.morphology

import kotlin.test.Test
import kotlin.test.assertTrue

class IpadicDictReaderTest {

    private val reader = IpadicDictReader()

    @Test
    fun readEntries_enumeratesHundredsOfThousandsOfEntries() {
        val entries = reader.readEntries()

        assertTrue(
            entries.size >= MINIMUM_ENTRY_COUNT,
            "全エントリ数が下限 $MINIMUM_ENTRY_COUNT 以上であること（実測=${entries.size}）",
        )
    }

    @Test
    fun readEntries_includesTokyoForTokyoReading() {
        val entries = reader.readEntries()
        val tokyoSurfaces = surfacesForReading(entries, READING_TOKYO)

        assertTrue(
            tokyoSurfaces.contains(SURFACE_TOKYO),
            "読み「$READING_TOKYO」の候補に「$SURFACE_TOKYO」が含まれること（候補=${tokyoSurfaces.take(SAMPLE_LIMIT)}）",
        )
    }

    @Test
    fun readEntries_includesMultipleHomophonesForKoushiReading() {
        val entries = reader.readEntries()
        val koushiSurfaces = surfacesForReading(entries, READING_KOUSHI)
        val matchedCandidates = KOUSHI_CANDIDATES.filter(koushiSurfaces::contains)

        assertTrue(
            matchedCandidates.size >= MINIMUM_KOUSHI_MATCHES,
            "読み「$READING_KOUSHI」の候補に漢字候補が複数含まれること（一致=$matchedCandidates）",
        )
    }

    @Test
    fun readEntries_excludesEntriesWithEmptyOrPlaceholderReading() {
        val entries = reader.readEntries()
        val hasInvalidReading = entries.any { entry -> isInvalidReading(entry.reading) }

        assertTrue(!hasInvalidReading, "読みが空または \"*\" のエントリが存在しないこと")
    }

    private fun surfacesForReading(entries: List<IpadicEntry>, reading: String): Set<String> {
        return entries.filter { entry -> entry.reading == reading }
            .map(IpadicEntry::surface)
            .toSet()
    }

    private fun isInvalidReading(reading: String): Boolean {
        return reading.isEmpty() || reading == FEATURE_EMPTY_MARK
    }

    private companion object {
        /** 全エントリ数の sanity 下限。 */
        const val MINIMUM_ENTRY_COUNT = 200_000

        /** 検証に使う読み（東京）。 */
        const val READING_TOKYO = "トウキョウ"

        /** 「トウキョウ」に含まれているべき表層形。 */
        const val SURFACE_TOKYO = "東京"

        /** 検証に使う読み（こうし）。 */
        const val READING_KOUSHI = "コウシ"

        /** 「コウシ」の同音異義候補のうち期待されるもの。 */
        val KOUSHI_CANDIDATES = listOf("講師", "格子", "孔子")

        /** 「コウシ」候補で一致を要求する最小件数。 */
        const val MINIMUM_KOUSHI_MATCHES = 2

        /** feature 中で値なしを表すマーク。 */
        const val FEATURE_EMPTY_MARK = "*"

        /** アサーションメッセージに載せる候補のサンプル件数。 */
        const val SAMPLE_LIMIT = 10
    }
}
