package me.matsumo.romaflow.core.ime

import dev.esnault.wanakana.core.Wanakana
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WanaKana (romaji→kana の決定的変換ライブラリ) を :core:ime から呼び出せること、
 * および macosArm64 / android で依存が解決し変換が動くことを確認する smoke テスト。
 */
class WanakanaSmokeTest {

    @Test
    fun toKana_basicVowels() {
        assertEquals("あいうえお", Wanakana.toKana("aiueo"))
    }

    @Test
    fun toKana_youon() {
        assertEquals("きょう", Wanakana.toKana("kyou"))
    }

    @Test
    fun toKana_sokuon() {
        assertEquals("きっぷ", Wanakana.toKana("kippu"))
    }

    @Test
    fun toKana_hatsuon() {
        assertEquals("にほん", Wanakana.toKana("nihon"))
    }

    @Test
    fun toKana_convertsPunctuation() {
        assertEquals("。", Wanakana.toKana("."))
    }
}
