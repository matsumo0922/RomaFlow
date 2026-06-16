package me.matsumo.romaflow.core.ime

import dev.esnault.wanakana.core.IMEMode
import dev.esnault.wanakana.core.Wanakana

/**
 * ローマ字を決定的に かな へ変換する converter。
 *
 * WanaKana への依存をこのクラスに局所化する。AI 変換は行わず、入力に対して常に同じ結果を返す。
 * 変換時の方針は次の通り:
 * - かな変換は WanaKana の IME モードを使い、`nn`→ん や `n`+子音→ん を日本語 IME と同じ規則で扱う。
 * - 大文字で始まる塊は「英単語」として丸ごと Latin のまま残し、次の空白までを1語とみなす（`Tokyo`→Tokyo）。
 * - 末尾の単独 `n` は入力途中では保留し（`おn`）、確定時のみ `ん` へ解決する。
 */
class RomajiKanaConverter {

    /**
     * [romaji] を表示用のかな（と英単語）へ変換する。
     *
     * [finalizeTrailing] が true の場合、末尾の active なかな部分の単独 `n` も `ん` へ解決する。
     * 確定（commit）時のみ true を渡し、入力途中の表示では false を渡す。
     */
    fun toKana(romaji: String, finalizeTrailing: Boolean): String {
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
}
