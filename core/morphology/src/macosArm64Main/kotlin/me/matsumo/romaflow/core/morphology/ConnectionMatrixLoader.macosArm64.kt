package me.matsumo.romaflow.core.morphology

/** macosArm64: base64 埋め込みの momiji IPADIC 行列をロードする。 */
internal actual fun loadConnectionMatrix(): ConnectionCostProvider = MomijiConnectionCostProvider.load()
