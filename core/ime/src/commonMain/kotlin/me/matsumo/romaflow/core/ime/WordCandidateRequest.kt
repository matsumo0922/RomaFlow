package me.matsumo.romaflow.core.ime

/**
 * call2（単語候補生成）の provider への入力。
 *
 * 選択中の文節 1 件について、同音異義語・別変換候補を文脈に沿って列挙させるための要求。
 * [reading] は候補を出す選択文節の読み（ひらがな）、[context] は文脈として渡す変換済み preedit 全文。
 * provider は [reading] に対する候補を [context] に整合する形で返す。
 */
internal data class WordCandidateRequest(
    val reading: String,
    val context: String,
)
