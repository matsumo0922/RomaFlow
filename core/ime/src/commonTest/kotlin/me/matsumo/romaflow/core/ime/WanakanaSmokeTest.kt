package me.matsumo.romaflow.core.ime

import dev.esnault.wanakana.core.Wanakana
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WanaKana (romaji→kana の決定的変換ライブラリ) を :core:ime から呼び出せることを確認する smoke テスト。
 *
 * テストは macosArm64 上で実行し、romaji→kana 変換が動くことを検証する。Android ターゲットは host unit test を
 * 有効化していないため本テストは実行されず、依存がコンパイル時に解決されること (compileAndroidMain) のみ確認する。
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
