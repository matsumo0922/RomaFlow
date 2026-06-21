package me.matsumo.romaflow.core.morphology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ReadingLatticeDecoder.arcMarginalCosts] のユニットテスト。
 *
 * ## 検証項目
 * - **arc 最小全文コストが forward+suffix と一致すること**（合成 lattice + 合成 costProvider）
 * - **到達不能アークが結果に含まれないこと**
 * - **同一 [ArcKey] が複数 node から出た場合は最小コストが採用されること**
 * - **reading が空のとき空 map を返すこと**
 */
@Suppress("FunctionNaming")
class ArcMarginalCostsTest {

    /**
     * 単一 lexeme の reading に対して、arcMarginalCosts が forward+suffix と一致するコストを返すことを確認する。
     *
     * セットアップ:
     * - reading = "てんき"（3文字 mora）
     * - lexeme: surface="天気", reading="テンキ"（3文字）, wcost=100
     * - ZeroConnectionCostProvider → BOS 連接=0、EOS 連接=0
     * - forward: BOS→天気 の wcost = 100（BOS 連接 0）
     * - suffix: EOS 連接 0（天気から EOS へ直接）
     * - 全文コスト = 100 + 0 = 100
     */
    @Test
    fun singleLexemeArcCostEqualsForwardPlusSuffix() {
        val lexeme = LexemeEntry(
            surface = "天気",
            reading = "テンキ",
            lcAttr = 0,
            rcAttr = 0,
            posId = 0,
            wcost = 100,
        )
        val lexicon = SingleLexemeArcLexicon(reading = "てんき", lexeme = lexeme)

        val result = ReadingLatticeDecoder.arcMarginalCosts(
            reading = "てんき",
            lexicon = lexicon,
            costProvider = ZeroConnectionCostProvider,
        )

        val arcKey = ArcKey(startOffset = 0, endOffset = 3, surface = "天気")
        assertTrue(result.containsKey(arcKey), "ArcKey(0,3,'天気') が含まれること")
        assertEquals(100L, result[arcKey], "全文コストが wcost=100 と一致すること（BOS/EOS 連接=0）")
    }

    /**
     * 2 lexeme のチェーンで、各アークのコストが forward + suffix と一致することを確認する。
     *
     * セットアップ:
     * - reading = "ab"（2文字 mora）
     * - lexeme A: surface="X", reading="ア"（1文字）, wcost=50
     * - lexeme B: surface="Y", reading="イ"（1文字）, wcost=30
     * - ZeroConnectionCostProvider → 全連接コスト=0
     * - A の forward = 50（BOS→A）, suffix = 30（A→B→EOS）= 全文コスト 80
     * - B の forward = 80（BOS→A→B）, suffix = 0（B→EOS）= 全文コスト 80
     */
    @Test
    fun twoLexemeChainArcCostsMatchForwardPlusSuffix() {
        val lexemeA = LexemeEntry(
            surface = "X",
            reading = "ア",
            lcAttr = 0,
            rcAttr = 0,
            posId = 0,
            wcost = 50,
        )
        val lexemeB = LexemeEntry(
            surface = "Y",
            reading = "イ",
            lcAttr = 0,
            rcAttr = 0,
            posId = 0,
            wcost = 30,
        )
        val lexicon = TwoLexemeChainLexicon(first = lexemeA, second = lexemeB)

        val result = ReadingLatticeDecoder.arcMarginalCosts(
            reading = "ab",
            lexicon = lexicon,
            costProvider = ZeroConnectionCostProvider,
        )

        val arcKeyA = ArcKey(startOffset = 0, endOffset = 1, surface = "X")
        val arcKeyB = ArcKey(startOffset = 1, endOffset = 2, surface = "Y")

        assertTrue(result.containsKey(arcKeyA), "ArcKey(0,1,'X') が含まれること")
        assertTrue(result.containsKey(arcKeyB), "ArcKey(1,2,'Y') が含まれること")
        assertEquals(80L, result[arcKeyA], "A のアーク全文コスト: forward=50 + suffix=30 = 80")
        assertEquals(80L, result[arcKeyB], "B のアーク全文コスト: forward=80 + suffix=0 = 80")
    }

    /**
     * EOS に到達できないアークが結果に含まれないことを確認する。
     *
     * セットアップ:
     * - reading = "ab"（2文字）
     * - offset 0 のみに lexeme があり、offset 1 への arc が存在しない
     * - → offset 0 の lexeme は endOffset=1 で、reading.length=2 に達しない（suffix = Long.MAX_VALUE = 到達不能）
     */
    @Test
    fun unreachableArcIsExcludedFromResult() {
        val lexemeOnlyAtStart = LexemeEntry(
            surface = "X",
            reading = "ア",
            lcAttr = 0,
            rcAttr = 0,
            posId = 0,
            wcost = 10,
        )
        // offset 0 のみに lexeme を返す（offset 1 以降は空）
        val lexicon = SingleOffsetLexicon(
            targetReading = "a",
            lexeme = lexemeOnlyAtStart,
            targetOffset = 0,
        )

        val result = ReadingLatticeDecoder.arcMarginalCosts(
            reading = "ab",
            lexicon = lexicon,
            costProvider = ZeroConnectionCostProvider,
        )

        // offset 0 の lexeme は endOffset=1 で EOS(offset=2) に到達できない → 除外
        assertFalse(
            result.containsKey(ArcKey(startOffset = 0, endOffset = 1, surface = "X")),
            "EOS 到達不能なアークは結果に含まれないこと",
        )
    }

    /**
     * 同一 [ArcKey]（同一 span・同一 surface）に複数の node がある場合、最小コストが採用されることを確認する。
     *
     * セットアップ:
     * - reading = "あ"（1文字）
     * - surface="A", reading="ア" の lexeme が 2 件：wcost=100 と wcost=200
     * - ZeroConnectionCostProvider → 全コスト差は wcost のみ
     * - 全文コスト: 100（安い方）と 200（高い方）→ min=100 を採用
     */
    @Test
    fun sameArcKeyKeepsMinimumCost() {
        val cheapLexeme = LexemeEntry(
            surface = "A",
            reading = "ア",
            lcAttr = 0,
            rcAttr = 0,
            posId = 0,
            wcost = 100,
        )
        val expensiveLexeme = LexemeEntry(
            surface = "A",
            reading = "ア",
            lcAttr = 0,
            rcAttr = 0,
            posId = 0,
            wcost = 200,
        )
        val lexicon = DuplicateSurfaceLexicon(listOf(cheapLexeme, expensiveLexeme))

        val result = ReadingLatticeDecoder.arcMarginalCosts(
            reading = "あ",
            lexicon = lexicon,
            costProvider = ZeroConnectionCostProvider,
        )

        val arcKey = ArcKey(startOffset = 0, endOffset = 1, surface = "A")
        assertTrue(result.containsKey(arcKey), "ArcKey(0,1,'A') が含まれること")
        assertEquals(100L, result[arcKey], "同一 ArcKey は最小コスト 100 を採用すること")
    }

    /**
     * reading が空のとき空 map を返すことを確認する。
     */
    @Test
    fun emptyReadingReturnsEmptyMap() {
        val result = ReadingLatticeDecoder.arcMarginalCosts(
            reading = "",
            lexicon = SingleLexemeArcLexicon(
                reading = "",
                lexeme = LexemeEntry("X", "ア", 0, 0, 0, 10),
            ),
            costProvider = ZeroConnectionCostProvider,
        )

        assertTrue(result.isEmpty(), "空 reading は空 map を返すこと")
    }
}

// ---- テスト用 lexicon ----

/**
 * 指定した [reading]（ひらがな）にのみ [lexeme] 1件を返す lexicon。
 *
 * startOffset=0 のときだけマッチし、reading 全体が prefix として一致する。
 */
private class SingleLexemeArcLexicon(
    private val reading: String,
    private val lexeme: LexemeEntry,
) : ReadingLexicon {

    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> {
        if (startOffset != 0) return emptyList()

        val endOffset = this.reading.length

        if (endOffset > reading.length) return emptyList()

        val slice = reading.substring(0, endOffset)

        if (slice != this.reading) return emptyList()

        return listOf(LexemeMatch(readingEndOffset = endOffset, lexeme = lexeme))
    }
}

/**
 * 2 lexeme のチェーン lexicon。
 *
 * offset 0 では [first]（endOffset=1）を、offset 1 では [second]（endOffset=2）を返す。
 * reading="ab" と組み合わせて 2 語チェーンの arc コストを検証するために使う。
 */
private class TwoLexemeChainLexicon(
    private val first: LexemeEntry,
    private val second: LexemeEntry,
) : ReadingLexicon {

    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> {
        return when (startOffset) {
            0 -> listOf(LexemeMatch(readingEndOffset = 1, lexeme = first))
            1 -> listOf(LexemeMatch(readingEndOffset = 2, lexeme = second))
            else -> emptyList()
        }
    }
}

/**
 * [targetOffset] でのみ [lexeme] を返す lexicon。
 *
 * 到達不能アーク（EOS に届かない）のテストに使う。
 */
private class SingleOffsetLexicon(
    private val targetReading: String,
    private val lexeme: LexemeEntry,
    private val targetOffset: Int,
) : ReadingLexicon {

    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> {
        if (startOffset != targetOffset) return emptyList()

        val suffix = reading.substring(startOffset)

        if (!suffix.startsWith(targetReading)) return emptyList()

        return listOf(LexemeMatch(readingEndOffset = startOffset + targetReading.length, lexeme = lexeme))
    }
}

/**
 * 同一 surface に複数 [LexemeEntry] を返す lexicon。
 *
 * 同一 [ArcKey] の最小コスト採用テストに使う。全 lexeme は offset=0 から1文字を覆う。
 */
private class DuplicateSurfaceLexicon(
    private val lexemes: List<LexemeEntry>,
) : ReadingLexicon {

    override fun commonPrefixSearch(reading: String, startOffset: Int): List<LexemeMatch> {
        if (startOffset != 0) return emptyList()
        if (reading.isEmpty()) return emptyList()

        return lexemes.map { lexeme ->
            LexemeMatch(readingEndOffset = 1, lexeme = lexeme)
        }
    }
}
