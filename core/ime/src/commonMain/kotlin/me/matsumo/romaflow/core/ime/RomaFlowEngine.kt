package me.matsumo.romaflow.core.ime

/**
 * RomaFlow IME core の入力状態を保持するエンジン。
 *
 * IMKInputController インスタンスごとに1つ生成され、未確定のローマ字 buffer と Tab 変換後の状態を保持する。
 * 「未変換かな」と「変換済み」の2状態を持つ簡易 state machine として振る舞う。
 * 公開 API は Swift Export に合わせ String / Boolean / suspend のみを受け渡しする（List は出さない）。
 */
class RomaFlowEngine internal constructor(
    private val conversionProvider: ConversionProvider,
) {

    private val converter = RomajiKanaConverter()

    private val romajiBuffer = StringBuilder()

    // Tab 変換後の表示テキスト。null の間は未変換かな状態を表す。
    private var convertedText: String? = null

    // 変換済み状態で候補ウィンドウへ提示する候補リスト。未変換のときは空。
    private var candidates: List<String> = emptyList()

    // 入力状態のリビジョン。入力を変える操作のたびに増やし、非同期変換の stale 判定に使う。
    private var inputRevision = 0

    // 実行中の変換要求が発行されたときの入力リビジョン。結果適用時にこれが現在値と一致するか確認する。
    private var pendingConversionRevision = -1

    /** Swift Export / 本番経路向けに既定の AI [ConversionProvider] を使う constructor。 */
    constructor() : this(defaultConversionProvider())

    fun smokeText(): String {
        return buildSmokeText("KMP")
    }

    fun inputRomaji(text: String): String {
        // 変換済み状態からの追加入力は呼び出し側が commit してから渡す想定。念のため変換状態を解除する。
        markInputChanged()
        resetConversion()
        romajiBuffer.append(text)

        return displayKana()
    }

    fun deleteBackward(): String {
        markInputChanged()

        // 変換済み状態での Backspace は変換を取り消し、raw 編集できるかな状態へ戻す。
        if (convertedText != null) {
            resetConversion()

            return displayKana()
        }

        if (romajiBuffer.isNotEmpty()) {
            romajiBuffer.deleteAt(romajiBuffer.length - 1)
        }

        return displayKana()
    }

    suspend fun convert(): String {
        // サスペンド前に main で読みを確定し、その後 provider をネットワーク呼び出しする。
        // 状態はここでは変えない（結果適用は applyConversion で main から行い、競合を避ける）。
        if (romajiBuffer.isEmpty()) {
            return ""
        }

        pendingConversionRevision = inputRevision
        val reading = finalizedKana()

        return conversionProvider.convert(reading)
    }

    fun applyConversion(result: String): String {
        // 失敗(空文字)・buffer 消失・要求発行後に入力が変わった(stale)場合は据え置く。
        // stale 判定により、入力モード切替や追加入力で取り消したあとに遅れて返った結果の復活を防ぐ。
        if (result.isEmpty() || romajiBuffer.isEmpty() || inputRevision != pendingConversionRevision) {
            return ""
        }

        candidates = buildCandidates(result, finalizedKana())
        convertedText = result

        return result
    }

    fun candidatesText(): String {
        // 候補ウィンドウへ渡す候補リスト。Swift Export 越しに List を出さず改行区切りの String にする。
        return candidates.joinToString("\n")
    }

    fun hasMultipleCandidates(): Boolean {
        return candidates.size > 1
    }

    fun commitCandidate(text: String): String {
        // 候補ウィンドウで選択された候補を確定する。表示文字列をそのまま受け取り WYSIWYG で確定する。
        markInputChanged()
        clearState()

        return text
    }

    fun commit(): String {
        // WYSIWYG で確定する。変換済みなら変換結果、未変換なら末尾 n を解決したかなを返す。
        markInputChanged()
        val committed = convertedText ?: finalizedKana()
        clearState()

        return committed
    }

    fun cancel() {
        markInputChanged()
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

    private fun buildCandidates(bestConversion: String, reading: String): List<String> {
        // 変換結果・ひらがな読み・カタカナ読みを重複なく並べる。読みと一致する候補は distinct で畳む。
        return listOf(bestConversion, reading, toKatakana(reading)).distinct()
    }

    private fun toKatakana(hiragana: String): String {
        val katakana = StringBuilder(hiragana.length)

        for (character in hiragana) {
            katakana.append(toKatakanaChar(character))
        }

        return katakana.toString()
    }

    private fun toKatakanaChar(character: Char): Char {
        val codePoint = character.code

        if (codePoint in HIRAGANA_BLOCK_START..HIRAGANA_BLOCK_END) {
            return (codePoint + HIRAGANA_TO_KATAKANA_OFFSET).toChar()
        }

        return character
    }

    private fun markInputChanged() {
        inputRevision++
    }

    private fun resetConversion() {
        convertedText = null
        candidates = emptyList()
    }

    private fun clearState() {
        romajiBuffer.clear()
        resetConversion()
    }

    private fun buildSmokeText(platformName: String): String {
        return "RomaFlow $platformName connected"
    }

    private companion object {
        /** ひらがなブロックの開始コードポイント（U+3041 ぁ）。 */
        const val HIRAGANA_BLOCK_START = 0x3041

        /** ひらがなブロックの終了コードポイント（U+3096 ゖ）。 */
        const val HIRAGANA_BLOCK_END = 0x3096

        /** ひらがなからカタカナへ変換するときのコードポイント差分。 */
        const val HIRAGANA_TO_KATAKANA_OFFSET = 0x60
    }
}
