package me.matsumo.romaflow.core.ime

/**
 * RomaFlow IME core と Swift host の接続確認を担当するエンジン。
 */
class RomaFlowEngine {
    fun smokeText(): String {
        return buildSmokeText("KMP")
    }

    private fun buildSmokeText(platformName: String): String {
        return "RomaFlow $platformName connected"
    }
}
