package me.matsumo.romaflow.core.ime

/**
 * [RomajiKanaConverter.appendRomaji] の結果。
 *
 * [committedKanaDelta] は今回確定したかな（readingInput へ append する）、[pendingRomaji] は未確定のまま
 * 残る末尾ローマ字（次回 append の起点）、[pendingDisplay] は preedit 末尾に出す表示用文字列
 * （B1a では [pendingRomaji] と同一）。
 */
internal data class RomajiComposition(
    val committedKanaDelta: String,
    val pendingRomaji: String,
    val pendingDisplay: String,
)
