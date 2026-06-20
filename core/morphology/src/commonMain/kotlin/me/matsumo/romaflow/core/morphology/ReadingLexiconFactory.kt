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

/**
 * プラットフォームに応じた連接コスト行列をロードして [ConnectionCostProvider] を返す public factory。
 *
 * macosArm64 は momiji IPADIC の base64 埋め込み行列、Android/JVM は classpath の
 * `mecab-ipadic/matrix.bin` をそれぞれ `internal expect` の [loadConnectionMatrix] で取得する。
 * ロードはコストが高いため、呼び出し側（[RomaFlowEngine] 等）は `by lazy` でキャッシュすること。
 * `:core:morphology` は Swift Export を持たないため、この関数は Swift 側に出ない。
 */
fun defaultConnectionCostProvider(): ConnectionCostProvider = loadConnectionMatrix()

/**
 * [entry] が [LiteralLexicon] 由来の fallback arc かどうかを厳密に判定する。
 *
 * [LiteralLexicon.buildLiteralLexeme] が生成する [LexemeEntry] と data class 等価比較を行うことで、
 * wcost 閾値による近似判定（IPADIC に wcost≥8000 の辞書語が在り得るため誤判定リスクあり）を避ける。
 * 評価ハーネスの OOV / literal fallback 率計測で使用することを想定している。
 *
 * [entry] の surface が 1 文字でない場合は即座に false を返す（LiteralLexicon は必ず 1 文字 arc）。
 */
fun isLiteralFallbackArc(entry: LexemeEntry): Boolean {
    if (entry.surface.length != 1) return false

    return entry == LiteralLexicon.buildLiteralLexeme(entry.surface[0])
}
