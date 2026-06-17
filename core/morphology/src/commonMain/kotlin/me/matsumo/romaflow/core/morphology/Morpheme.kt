package me.matsumo.romaflow.core.morphology

/**
 * 形態素解析で得られた 1 形態素分の結果。
 *
 * LLM が出力した日本語テキストを分かち書きした結果の最小単位を表す。
 * 単語ごとの同音異義候補を引くためのキーとして [reading] を用いる。
 */
data class Morpheme(
    /** 表層形（テキスト中に現れた文字列）。 */
    val surface: String,
    /** 読み（カタカナ）。辞書に読みが無い未知語などでは null。 */
    val reading: String?,
    /** 品詞（IPADIC の品詞分類）。取得できない場合は null。 */
    val partOfSpeech: String?,
)
