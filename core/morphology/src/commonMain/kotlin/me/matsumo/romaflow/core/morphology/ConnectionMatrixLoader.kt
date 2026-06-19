package me.matsumo.romaflow.core.morphology

/**
 * プラットフォームごとに連接コスト行列を読み込む platform adapter。
 *
 * macosArm64: [io.github.tokuhirom.momiji.ipadic.momijiLoadMatrix] で base64 埋め込み行列を取得。
 * Android/JVM: [io.github.tokuhirom.momiji.ipadic.momijiLoadMatrix] で classpath の
 *   `mecab-ipadic/matrix.bin` を [io.github.tokuhirom.momiji.core.matrix.Matrix.parseBinary] 経由で取得。
 *
 * 結果は重い処理のため呼び出し側でキャッシュすること。
 */
internal expect fun loadConnectionMatrix(): ConnectionCostProvider
