package me.matsumo.romaflow.core.morphology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [EntryListReadingLexicon] の逆引き挙動（ひらがなキー化・wcost 昇順・prefix 検索）を検証する。
 *
 * カタカナ読み（IPADIC 由来）とひらがな読み（Mozc 等 別辞書由来）の両方を引けることを確認する。
 */
class EntryListReadingLexiconTest {

    private fun lexemeOf(surface: String, reading: String, wcost: Int): LexemeEntry {
        return LexemeEntry(
            surface = surface,
            reading = reading,
            lcAttr = 0,
            rcAttr = 0,
            posId = 0,
            wcost = wcost,
        )
    }

    @Test
    fun commonPrefixSearchKeysKatakanaReadingByHiraganaSortedByWcost() {
        val lexicon = EntryListReadingLexicon(
            listOf(
                lexemeOf("医科", "イカ", 50),
                lexemeOf("以下", "イカ", 10),
            ),
        )

        val matches = lexicon.commonPrefixSearch("いか", 0)

        assertEquals(listOf("以下", "医科"), matches.map { match -> match.lexeme.surface })
        assertTrue(matches.all { match -> match.readingEndOffset == 2 })
    }

    @Test
    fun commonPrefixSearchSupportsHiraganaReadingEntries() {
        val lexicon = EntryListReadingLexicon(listOf(lexemeOf("文", "ぶん", 5)))

        val matches = lexicon.commonPrefixSearch("ぶん", 0)

        assertEquals(1, matches.size)
        assertEquals("文", matches[0].lexeme.surface)
    }

    @Test
    fun commonPrefixSearchReturnsEmptyForUnknownReading() {
        val lexicon = EntryListReadingLexicon(listOf(lexemeOf("以下", "イカ", 10)))

        assertTrue(lexicon.commonPrefixSearch("かんじ", 0).isEmpty())
    }

    @Test
    fun commonPrefixSearchRespectsStartOffset() {
        val lexicon = EntryListReadingLexicon(listOf(lexemeOf("以下", "イカ", 10)))

        val matches = lexicon.commonPrefixSearch("あいか", 1)

        assertEquals(1, matches.size)
        assertEquals(3, matches[0].readingEndOffset)
    }
}
