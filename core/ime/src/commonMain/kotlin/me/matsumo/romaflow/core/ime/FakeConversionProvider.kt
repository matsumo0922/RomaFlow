package me.matsumo.romaflow.core.ime

/**
 * 決定的な rule-based の [ConversionProvider] スタブ。
 *
 * AI を使わず固定の変換表で既知のかな語を漢字へ置換し、表にない部分はかなのまま残す。
 * 実 AI provider を差し込むまでの開発・テスト用で、同じ入力には常に同じ結果を返す。
 */
class FakeConversionProvider : ConversionProvider {

    override fun convert(kana: String): String {
        var converted = kana

        for ((reading, kanji) in CONVERSION_TABLE) {
            converted = converted.replace(reading, kanji)
        }

        return converted
    }

    private companion object {
        /**
         * かなの読みと変換後の漢字の対応表。
         *
         * 長い読みを先に置換して部分一致による取りこぼしを避けるため、挿入順を保つ [LinkedHashMap] を使う。
         */
        val CONVERSION_TABLE = linkedMapOf(
            "にほんご" to "日本語",
            "とうきょう" to "東京",
            "へんかん" to "変換",
            "かんじ" to "漢字",
            "わたし" to "私",
        )
    }
}
