package me.matsumo.romaflow.core.ime

/**
 * かな（と英単語混じり）の未確定テキストを、漢字交じりの変換結果へ変換する provider。
 *
 * Tab 起動の変換でのみ呼ばれる差し替え可能な seam。将来の AI provider はこの interface を実装して差し替える。
 * Swift Export の保守的な制約に合わせ、入出力は String のみとする。
 */
interface ConversionProvider {

    /**
     * [kana] を変換し、表示用の変換結果文字列を返す。
     *
     * 変換できない部分は [kana] のまま残す。同じ入力には常に同じ結果を返すこと（決定的）。
     */
    fun convert(kana: String): String
}
