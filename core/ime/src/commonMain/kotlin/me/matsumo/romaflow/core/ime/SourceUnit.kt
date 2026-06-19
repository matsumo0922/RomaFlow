package me.matsumo.romaflow.core.ime

/**
 * romaji→kana の変換単位を表す sealed インターフェース。
 *
 * `TransliterationTrace` 上で backspace の削除写像を正規化するために導入する。raw atom の単純削除では
 * 再現できないケース（例: `kyo→きょ→BS→き`）を [LiteralReading] で表現する。
 *
 * - [Romaji]: 入力ローマ字の atoms を保持する変換単位（通常の romaji→kana エッジ）。
 * - [LiteralReading]: backspace 後の結果かなを直接保持する正規化単位（エッジの分割や末尾削除の表現に使う）。
 * - [LiteralText]: かな以外の表層文字列（英単語・記号等）を直接保持する単位。
 */
internal sealed interface SourceUnit {

    /**
     * ローマ字 atoms のシーケンスを保持する変換単位。
     *
     * [atoms] は対応する [InputAtom] の ID 列で、`kyo` なら `[k_id, y_id, o_id]` となる。
     * backspace の削除写像は [atoms] の末尾 atom 削除で表現できない場合に [LiteralReading] へ退化する。
     */
    data class Romaji(
        val atoms: List<Long>,
    ) : SourceUnit

    /**
     * backspace 後のかなを直接保持する正規化単位。
     *
     * `kyo→きょ` を1エッジとして扱い、`BS→き` は `LiteralReading("き")` で表現する。
     * atoms を失うため、A-2 lattice では OOV/literal arc として扱う。
     */
    data class LiteralReading(
        val reading: String,
    ) : SourceUnit

    /**
     * かな以外の表層テキスト（英単語・記号等）を直接保持する単位。
     *
     * 大文字から始まる英単語（`Tokyo` など）が Latin のまま残る場合に使用する。
     */
    data class LiteralText(
        val text: String,
    ) : SourceUnit
}
