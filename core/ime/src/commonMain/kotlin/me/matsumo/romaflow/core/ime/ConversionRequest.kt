package me.matsumo.romaflow.core.ime

/**
 * call1（かな漢字変換）の provider への入力。
 *
 * prefix-commit（Option A）方式の lock に対応する。lock 無しの初回変換では [readingInput] に打った通りの
 * かな全体、[prefixContext] は空文字。lock 有りの再変換では先頭から連続する確定済み（Locked）文節を prefix
 * として切り出し、その surface 結合を [prefixContext]、残りの未確定読み（tail）を [readingInput] に渡す。
 * provider は [prefixContext] を前方文脈として [readingInput] を変換する。
 */
internal data class ConversionRequest(
    val readingInput: String,
    val prefixContext: String,
)
