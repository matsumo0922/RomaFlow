package me.matsumo.romaflow.core.ime

/**
 * RomaFlow IME core の入力状態を保持するエンジン。
 *
 * IMKInputController インスタンスごとに1つ生成され、未確定のローマ字 buffer と Tab 変換後の状態を保持する。
 * 「未変換かな」と「変換済み」の2状態を持つ簡易 state machine として振る舞う。
 * 公開 API は Swift Export の保守的な制約に合わせ String / Boolean のみを受け渡しする。
 */
class RomaFlowEngine internal constructor(
    private val conversionProvider: ConversionProvider,
) {

    private val converter = RomajiKanaConverter()

    private val romajiBuffer = StringBuilder()

    // Tab 変換後の表示テキスト。null の間は未変換かな状態を表す。
    private var convertedText: String? = null

    /** Swift Export / 本番経路向けに [FakeConversionProvider] を使う既定の constructor。 */
    constructor() : this(FakeConversionProvider())

    fun smokeText(): String {
        return buildSmokeText("KMP")
    }

    fun inputRomaji(text: String): String {
        // 変換済み状態からの追加入力は呼び出し側が commit してから渡す想定。念のため変換状態を解除する。
        convertedText = null
        romajiBuffer.append(text)

        return displayKana()
    }

    fun deleteBackward(): String {
        // 変換済み状態での Backspace は変換を取り消し、raw 編集できるかな状態へ戻す。
        if (convertedText != null) {
            convertedText = null

            return displayKana()
        }

        if (romajiBuffer.isNotEmpty()) {
            romajiBuffer.deleteAt(romajiBuffer.length - 1)
        }

        return displayKana()
    }

    fun convert(): String {
        if (romajiBuffer.isEmpty()) {
            return ""
        }

        val converted = conversionProvider.convert(finalizedKana())
        convertedText = converted

        return converted
    }

    fun commit(): String {
        // WYSIWYG で確定する。変換済みなら変換結果、未変換なら末尾 n を解決したかなを返す。
        val committed = convertedText ?: finalizedKana()
        clearState()

        return committed
    }

    fun cancel() {
        clearState()
    }

    fun hasComposition(): Boolean {
        return romajiBuffer.isNotEmpty()
    }

    fun isConverted(): Boolean {
        return convertedText != null
    }

    private fun displayKana(): String {
        return converter.toKana(romajiBuffer.toString(), finalizeTrailing = false)
    }

    private fun finalizedKana(): String {
        return converter.toKana(romajiBuffer.toString(), finalizeTrailing = true)
    }

    private fun clearState() {
        romajiBuffer.clear()
        convertedText = null
    }

    private fun buildSmokeText(platformName: String): String {
        return "RomaFlow $platformName connected"
    }
}
