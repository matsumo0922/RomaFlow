package me.matsumo.romaflow.core.ime

import dev.esnault.wanakana.core.IMEMode
import dev.esnault.wanakana.core.Wanakana

/**
 * ローマ字を決定的に かな へ変換する converter。
 *
 * WanaKana への依存をこのクラスに局所化する。AI 変換は行わず、入力に対して常に同じ結果を返す。
 * romaji→kana は whole-string ではなく増分の typed contract（[RomajiComposition]）で扱い、
 * 「どの時点で末尾ローマ字を確定かなへ移すか」を明示してテスト可能にする。方針は次の通り:
 * - かな変換は WanaKana の IME モードを使い、`nn`→ん や `n`+子音→ん を日本語 IME と同じ規則で扱う。
 * - 大文字で始まる塊は「英単語」として丸ごと Latin のまま残し、次の空白までを1語とみなす（`Tokyo`→Tokyo）。
 * - 末尾の未確定ローマ字（`k`/`ky`/lone `n` 等）は確定せず pending として残し、確定時のみ かな へ解決する。
 */
internal class RomajiKanaConverter {

    /**
     * 既存の [pendingRomaji] に新しい [input] を追記し、確定したかなと残る pending を切り分ける。
     *
     * 末尾の未確定ローマ字（WanaKana が変換しきれず Latin のまま残した部分）を pending とし、
     * その手前までを確定かな（[RomajiComposition.committedKanaDelta]）として返す。
     */
    fun appendRomaji(pendingRomaji: String, input: String): RomajiComposition {
        val combined = pendingRomaji + input

        if (combined.isEmpty()) {
            return RomajiComposition("", "", "")
        }

        val display = toKana(combined, finalizeTrailing = false)
        val pending = trailingLatinRun(display)
        val committed = display.substring(0, display.length - pending.length)

        return RomajiComposition(committed, pending, pending)
    }

    /**
     * [pendingRomaji] を確定かなへ解決する（Tab / commit で境界を固定するときに使う）。
     *
     * 末尾の単独 `n` は `ん` へ解決する。英単語はそのまま Latin で残る。
     */
    fun finalize(pendingRomaji: String): String {
        if (pendingRomaji.isEmpty()) {
            return ""
        }

        return toKana(pendingRomaji, finalizeTrailing = true)
    }

    private fun toKana(romaji: String, finalizeTrailing: Boolean): String {
        val output = StringBuilder()
        val kanaPending = StringBuilder()

        var index = 0
        while (index < romaji.length) {
            val char = romaji[index]

            if (isEnglishWordStart(char)) {
                appendKana(output, kanaPending.toString(), resolveTrailing = true)
                kanaPending.clear()

                val wordEnd = englishWordEnd(romaji, index)
                output.append(romaji.substring(index, wordEnd))
                index = wordEnd
            } else {
                kanaPending.append(char)
                index++
            }
        }

        appendKana(output, kanaPending.toString(), resolveTrailing = finalizeTrailing)

        return output.toString()
    }

    private fun isEnglishWordStart(char: Char): Boolean {
        return char in 'A'..'Z'
    }

    private fun englishWordEnd(romaji: String, start: Int): Int {
        var index = start

        while (index < romaji.length && romaji[index] != ' ') {
            index++
        }

        return index
    }

    private fun appendKana(
        output: StringBuilder,
        romaji: String,
        resolveTrailing: Boolean,
    ) {
        if (romaji.isEmpty()) {
            return
        }

        val kana = Wanakana.toKana(romaji, IMEMode.ENABLED)
        val resolved = if (resolveTrailing) resolveTrailingN(kana) else kana

        output.append(resolved)
    }

    private fun resolveTrailingN(kana: String): String {
        return if (kana.endsWith('n')) {
            kana.dropLast(1) + "ん"
        } else {
            kana
        }
    }

    private fun trailingLatinRun(text: String): String {
        var index = text.length

        while (index > 0 && isLatinLetter(text[index - 1])) {
            index--
        }

        return text.substring(index)
    }

    private fun isLatinLetter(character: Char): Boolean {
        val isLower = character in 'a'..'z'
        val isUpper = character in 'A'..'Z'

        return isLower || isUpper
    }
}
