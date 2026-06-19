package me.matsumo.romaflow.core.morphology

/**
 * OOV フォールバック付きの [ReadingLexicon] を構築する factory 関数。
 *
 * [primary] 辞書に [LiteralLexicon] を fallback として結合した [CompositeLexicon] を返す。
 * これにより全位置に最低 1 件の arc が保証されるため、辞書外文字でもデッドロックしない。
 *
 * shadow エンジン（A-3）が `:core:ime` から呼び出すために公開する。
 * [CompositeLexicon] と [LiteralLexicon] 自体は `internal` のまま変更しない。
 */
fun buildReadingLexiconWithFallback(primary: ReadingLexicon): ReadingLexicon {
    return CompositeLexicon(
        primary = primary,
        fallback = LiteralLexicon(),
    )
}
