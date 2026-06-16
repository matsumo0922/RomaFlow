package me.matsumo.romaflow.core.ime

/**
 * RomaFlow IME core の入力状態を保持するエンジン。
 *
 * IMKInputController インスタンスごとに1つ生成され、未確定のローマ字 buffer を保持する。
 * 公開 API は Swift Export の保守的な制約に合わせ String / Boolean のみを受け渡しする。
 */
class RomaFlowEngine {
    private val converter = RomajiKanaConverter()

    private val romajiBuffer = StringBuilder()

    fun smokeText(): String {
        return buildSmokeText("KMP")
    }

    fun inputRomaji(text: String): String {
        romajiBuffer.append(text)

        return displayKana()
    }

    fun deleteBackward(): String {
        if (romajiBuffer.isNotEmpty()) {
            romajiBuffer.deleteAt(romajiBuffer.length - 1)
        }

        return displayKana()
    }

    fun commit(): String {
        val committedKana = converter.toKana(romajiBuffer.toString(), finalizeTrailing = true)
        romajiBuffer.clear()

        return committedKana
    }

    fun cancel() {
        romajiBuffer.clear()
    }

    fun hasComposition(): Boolean {
        return romajiBuffer.isNotEmpty()
    }

    private fun displayKana(): String {
        return converter.toKana(romajiBuffer.toString(), finalizeTrailing = false)
    }

    private fun buildSmokeText(platformName: String): String {
        return "RomaFlow $platformName connected"
    }
}
