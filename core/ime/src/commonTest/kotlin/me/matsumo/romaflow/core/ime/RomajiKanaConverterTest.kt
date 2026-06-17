package me.matsumo.romaflow.core.ime

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [RomajiKanaConverter] の増分 API（[RomajiKanaConverter.appendRomaji] / [RomajiKanaConverter.finalize]）の
 * 確定境界規則を検証するテスト。romaji→kana の「どこで確定し、何を pending に残すか」を pin する B1a の最重要テスト。
 */
class RomajiKanaConverterTest {

    private val converter = RomajiKanaConverter()

    @Test
    fun appendRomaji_singleConsonantStaysPending() {
        val composition = converter.appendRomaji("", "k")

        assertEquals("", composition.committedKanaDelta)
        assertEquals("k", composition.pendingRomaji)
    }

    @Test
    fun appendRomaji_completedSyllableCommits() {
        val composition = converter.appendRomaji("", "ka")

        assertEquals("か", composition.committedKanaDelta)
        assertEquals("", composition.pendingRomaji)
    }

    @Test
    fun appendRomaji_loneNStaysPending() {
        val composition = converter.appendRomaji("", "n")

        assertEquals("", composition.committedKanaDelta)
        assertEquals("n", composition.pendingRomaji)
    }

    @Test
    fun appendRomaji_nFollowedByConsonantCommitsN() {
        val composition = converter.appendRomaji("n", "b")

        assertEquals("ん", composition.committedKanaDelta)
        assertEquals("b", composition.pendingRomaji)
    }

    @Test
    fun appendRomaji_doubleNCommitsN() {
        val composition = converter.appendRomaji("", "nn")

        assertEquals("ん", composition.committedKanaDelta)
        assertEquals("", composition.pendingRomaji)
    }

    @Test
    fun appendRomaji_youonCommitsWhenComplete() {
        val pendingY = converter.appendRomaji("k", "y")

        assertEquals("", pendingY.committedKanaDelta)
        assertEquals("ky", pendingY.pendingRomaji)

        val completed = converter.appendRomaji(pendingY.pendingRomaji, "a")

        assertEquals("きゃ", completed.committedKanaDelta)
        assertEquals("", completed.pendingRomaji)
    }

    @Test
    fun appendRomaji_sokuonResolvesOnlyWhenVowelCompletesSyllable() {
        // 二重子音だけでは促音を確定せず pending に積み、母音が来た時点で っ ごと確定する。
        val doubled = converter.appendRomaji("t", "t")

        assertEquals("", doubled.committedKanaDelta)
        assertEquals("tt", doubled.pendingRomaji)

        val completed = converter.appendRomaji(doubled.pendingRomaji, "a")

        assertEquals("った", completed.committedKanaDelta)
        assertEquals("", completed.pendingRomaji)
    }

    @Test
    fun appendRomaji_shiCommits() {
        val composition = converter.appendRomaji("", "shi")

        assertEquals("し", composition.committedKanaDelta)
        assertEquals("", composition.pendingRomaji)
    }

    @Test
    fun appendRomaji_uppercaseWordStaysPendingUntilSpace() {
        val typing = converter.appendRomaji("Toky", "o")

        assertEquals("", typing.committedKanaDelta)
        assertEquals("Tokyo", typing.pendingRomaji)

        val afterSpace = converter.appendRomaji("Tokyo", " ")

        assertEquals("Tokyo ", afterSpace.committedKanaDelta)
        assertEquals("", afterSpace.pendingRomaji)
    }

    @Test
    fun appendRomaji_pendingDisplayMatchesPendingRomaji() {
        val composition = converter.appendRomaji("", "ky")

        assertEquals(composition.pendingRomaji, composition.pendingDisplay)
    }

    @Test
    fun finalize_resolvesLoneNToHatsuon() {
        assertEquals("ん", converter.finalize("n"))
    }

    @Test
    fun finalize_emptyReturnsEmpty() {
        assertEquals("", converter.finalize(""))
    }

    @Test
    fun finalize_keepsUppercaseWordAsLatin() {
        assertEquals("Tokyo", converter.finalize("Tokyo"))
    }
}
