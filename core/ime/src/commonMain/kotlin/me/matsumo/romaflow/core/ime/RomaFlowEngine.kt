package me.matsumo.romaflow.core.ime

/**
 * RomaFlow IME core と Swift host の接続、および最小 composition 状態を担当するエンジン。
 */
class RomaFlowEngine {
    private val composingText = StringBuilder()

    fun smokeText(): String {
        return buildSmokeText("KMP")
    }

    fun inputText(text: String): String {
        if (text.isEmpty()) return currentComposition()

        composingText.append(text)

        return currentComposition()
    }

    fun deleteBackward(): String {
        val deleteIndex = composingText.length - 1
        if (deleteIndex < 0) return currentComposition()

        composingText.deleteAt(deleteIndex)

        return currentComposition()
    }

    fun clearComposition(): String {
        composingText.clear()

        return currentComposition()
    }

    fun commitComposition(): String {
        val committedText = currentComposition()

        clearComposition()

        return committedText
    }

    fun currentComposition(): String {
        return composingText.toString()
    }

    fun hasComposition(): Boolean {
        return composingText.isNotEmpty()
    }

    private fun buildSmokeText(platformName: String): String {
        return "RomaFlow $platformName connected"
    }
}
