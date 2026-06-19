package me.matsumo.romaflow.core.morphology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * literal/OOV arc（A-2）の検証テスト。
 *
 * ひらがな/カタカナ素通し、ASCII、数字、記号の arc が正しく生成されること、
 * および辞書外文字によるデコード経路消失（デッドロック）が防がれることを確認する。
 */
class LiteralLexiconTest {

    private val literalLexicon = LiteralLexicon()

    @Test
    fun hiraganaCharacterGeneratesPassThroughArc() {
        val reading = "あ"
        val matches = literalLexicon.commonPrefixSearch(reading, 0)

        assertEquals(1, matches.size, "ひらがな 1 文字に 1 件の arc が生成されること")

        val match = matches[0]
        assertEquals(1, match.readingEndOffset, "終端オフセットが 1 であること")
        assertEquals("あ", match.lexeme.surface, "surface がひらがなそのままであること")
        assertEquals("あ", match.lexeme.reading, "reading がひらがなそのままであること")
        assertEquals(LiteralLexicon.HIRAGANA_WCOST, match.lexeme.wcost, "wcost がひらがなコストであること")
    }

    @Test
    fun katakanaCharacterGeneratesPassThroughArc() {
        val reading = "ア"
        val matches = literalLexicon.commonPrefixSearch(reading, 0)

        assertEquals(1, matches.size, "カタカナ 1 文字に 1 件の arc が生成されること")

        val match = matches[0]
        assertEquals("ア", match.lexeme.surface, "surface がカタカナそのままであること")
        assertEquals(LiteralLexicon.HIRAGANA_WCOST, match.lexeme.wcost, "カタカナもひらがなと同コストであること")
    }

    @Test
    fun digitCharacterGeneratesDigitArc() {
        val reading = "3"
        val matches = literalLexicon.commonPrefixSearch(reading, 0)

        assertEquals(1, matches.size, "数字 1 文字に 1 件の arc が生成されること")
        assertEquals(LiteralLexicon.DIGIT_WCOST, matches[0].lexeme.wcost, "wcost が数字コストであること")
    }

    @Test
    fun asciiLetterGeneratesAsciiArc() {
        val reading = "a"
        val matches = literalLexicon.commonPrefixSearch(reading, 0)

        assertEquals(1, matches.size, "ASCII 1 文字に 1 件の arc が生成されること")
        assertEquals(LiteralLexicon.ASCII_WCOST, matches[0].lexeme.wcost, "wcost が ASCII コストであること")
    }

    @Test
    fun symbolCharacterGeneratesSymbolArc() {
        val reading = "！"
        val matches = literalLexicon.commonPrefixSearch(reading, 0)

        assertEquals(1, matches.size, "記号 1 文字に 1 件の arc が生成されること")
        assertEquals(LiteralLexicon.SYMBOL_WCOST, matches[0].lexeme.wcost, "wcost が記号コストであること")
    }

    @Test
    fun literalContextIdsAreNonZero() {
        val hiraganaMatch = literalLexicon.commonPrefixSearch("い", 0).first()
        val symbolMatch = literalLexicon.commonPrefixSearch("!", 0).first()

        assertTrue(hiraganaMatch.lexeme.lcAttr != 0, "ひらがな arc の lcAttr が非ゼロであること")
        assertTrue(hiraganaMatch.lexeme.rcAttr != 0, "ひらがな arc の rcAttr が非ゼロであること")
        assertTrue(symbolMatch.lexeme.lcAttr != 0, "記号 arc の lcAttr が非ゼロであること")
        assertTrue(symbolMatch.lexeme.rcAttr != 0, "記号 arc の rcAttr が非ゼロであること")
    }

    @Test
    fun compositeLexiconPreventsDeadlockForOovCharacter() {
        // 辞書外文字: "亜" は辞書に存在するが、ここでは辞書を空にして OOV をシミュレート
        val emptyPrimaryLexicon = object : ReadingLexicon {
            override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> =
                emptyList()
        }

        val compositeLexicon = CompositeLexicon(
            primary = emptyPrimaryLexicon,
            fallback = literalLexicon,
        )

        val oovReading = "あX"
        val costProvider = ZeroConnectionCostProvider

        // OOV 文字を含む reading でも経路が構築できること
        val paths = ReadingLatticeDecoder.nBest(oovReading, compositeLexicon, costProvider, n = 1)

        assertFalse(paths.isEmpty(), "OOV 文字を含む reading で経路が存在すること（デッドロック防止）")

        val surfaces = paths[0].second.map { it.surface }
        println("OOV deadlock prevention: $surfaces")
        assertEquals(listOf("あ", "X"), surfaces, "2 文字がそれぞれ literal arc でデコードされること")
    }

    @Test
    fun compositeLexiconPrefersRealDictionaryEntryOverLiteral() {
        // 辞書に "あ" を持つ fake lexicon
        val dictLexeme = LexemeEntry(
            surface = "亜",
            reading = "あ",
            lcAttr = 100,
            rcAttr = 100,
            posId = 0,
            wcost = 100,
        )
        val fakeDict = object : ReadingLexicon {
            override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> =
                if (startOffset == 0 && reading.startsWith("あ")) {
                    listOf(LexemeMatch(readingEndOffset = 1, lexeme = dictLexeme))
                } else {
                    emptyList()
                }
        }

        val compositeLexicon = CompositeLexicon(primary = fakeDict, fallback = literalLexicon)
        val matches = compositeLexicon.commonPrefixSearch("あ", 0)

        // primary（辞書語 "亜"）と fallback（literal "あ"）の両方が返る
        assertTrue(matches.size >= 2, "辞書語と literal arc の両方が返ること")

        val surfaces = matches.map { it.lexeme.surface }.toSet()
        assertTrue("亜" in surfaces, "辞書語 亜 が含まれること")
        assertTrue("あ" in surfaces, "literal arc あ が含まれること")

        // 辞書語は literal より低コスト（wcost=100 < HIRAGANA_WCOST=8000）
        val dictEntry = matches.first { it.lexeme.surface == "亜" }.lexeme
        val literalEntry = matches.first { it.lexeme.surface == "あ" }.lexeme
        assertTrue(dictEntry.wcost < literalEntry.wcost, "辞書語は literal より低コストであること")
    }
}
