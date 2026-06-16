package me.matsumo.romaflow.core.ime

import dev.esnault.wanakana.core.Wanakana

/**
 * ローマ字を決定的に かな へ変換する converter。
 *
 * WanaKana への依存をこのクラスに局所化し、将来の変換ルール拡張や実装差し替えを
 * このクラス内に閉じ込められるようにする。AI 変換は一切行わず、入力に対して常に同じ
 * かな を返す純粋な変換のみを担う。
 */
class RomajiKanaConverter {
    fun toKana(romaji: String): String {
        return Wanakana.toKana(romaji)
    }
}
