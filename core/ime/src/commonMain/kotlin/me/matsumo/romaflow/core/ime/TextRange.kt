package me.matsumo.romaflow.core.ime

/**
 * readingInput 上の文字範囲。
 *
 * [startInclusive] は開始位置（含む）、[endExclusive] は終了位置（含まない）。index は Kotlin String の
 * UTF-16 code unit 基準で数え、Swift へ渡す際は adapter が NSString 基準の NSRange へ変換する。
 * [confidence] は alignment の信頼度（0f=不明）。B1a では range を付与しないため未使用。
 */
internal data class TextRange(
    val startInclusive: Int,
    val endExclusive: Int,
    val confidence: Float,
)
