package me.matsumo.romaflow.core.morphology

/**
 * OOV フォールバック付きの [ReadingLexicon] を構築する factory 関数。
 *
 * [primary] 辞書に [LiteralLexicon] を fallback として結合した [CompositeLexicon] を返す。
 * これにより全位置に最低 1 件の arc が保証されるため、辞書外文字でもデッドロックしない。
 *
 * shadow エンジン（A-3）が `:core:ime` から呼び出すために公開する。
 * [CompositeLexicon] と [LiteralLexicon] 自体は `internal` のまま変更しない。
 *
 * [literalContextIds] で fallback arc の連接 ID を辞書（Mozc 等）に合わせて差し替える。
 * 既定は [LiteralContextIds.Ipadic]。Mozc 本番経路では呼び出し側が [LiteralContextIds.Mozc] を渡す。
 */
fun buildReadingLexiconWithFallback(
    primary: ReadingLexicon,
    literalContextIds: LiteralContextIds = LiteralContextIds.Ipadic,
): ReadingLexicon {
    return CompositeLexicon(
        primary = primary,
        fallback = LiteralLexicon(literalContextIds),
    )
}

/**
 * [entry] が [LiteralLexicon] 由来の fallback arc かどうかを厳密に判定する。
 *
 * [LiteralLexicon.buildLiteralLexeme] が生成する [LexemeEntry] と data class 等価比較を行うことで、
 * wcost 閾値による近似判定（IPADIC に wcost≥8000 の辞書語が在り得るため誤判定リスクあり）を避ける。
 * 評価ハーネスの OOV / literal fallback 率計測で使用することを想定している。
 *
 * [entry] の surface が 1 文字でない場合は即座に false を返す（LiteralLexicon は必ず 1 文字 arc）。
 * [literalContextIds] は判定対象の lexicon が使った連接 ID セットと一致させること（既定 IPADIC）。
 */
fun isLiteralFallbackArc(
    entry: LexemeEntry,
    literalContextIds: LiteralContextIds = LiteralContextIds.Ipadic,
): Boolean {
    if (entry.surface.length != 1) return false

    return entry == LiteralLexicon.buildLiteralLexeme(entry.surface[0], literalContextIds)
}
