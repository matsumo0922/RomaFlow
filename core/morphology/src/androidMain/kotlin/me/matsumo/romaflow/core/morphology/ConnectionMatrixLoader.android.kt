package me.matsumo.romaflow.core.morphology

import io.github.tokuhirom.momiji.core.matrix.Matrix

/**
 * Android/JVM: classpath 上の `mecab-ipadic/matrix.bin` を [Matrix.parseBinary] でロードする。
 *
 * momiji-ipadic の transitive 依存 `momiji-ipadic-resources` が classpath に `matrix.bin` を
 * 配置するため追加の依存宣言は不要。取得経路は [SysDicLoader.android.kt] の `sys.dic` と同様。
 * 到達性は [ConnectionMatrixReachabilityTest]（androidHostTest）で検証済み。
 *
 * ID 方向・符号・BOS/EOS:
 * - [Matrix.find] の引数は (previousRightContextId, nextLeftContextId) 順（rcAttr, lcAttr）。
 * - 返り値は [Short]（符号付き）をそのまま [Int] に広げて使う。
 * - BOS/EOS の連接 ID は 0。
 */
internal actual fun loadConnectionMatrix(): ConnectionCostProvider {
    val classLoader = object {}.javaClass.classLoader

    requireNotNull(classLoader) {
        "matrix.bin を読み込むための ClassLoader を取得できませんでした"
    }

    val inputStream = classLoader.getResourceAsStream(MATRIX_BIN_RESOURCE_PATH)

    requireNotNull(inputStream) {
        "classpath に $MATRIX_BIN_RESOURCE_PATH が見つかりませんでした（momiji-ipadic-resources を確認）"
    }

    val matrix = inputStream.use { stream -> Matrix.parseBinary(stream.readBytes()) }

    return MatrixConnectionCostProvider(matrix)
}

/** momiji-ipadic-resources が classpath に配置する matrix.bin のリソースパス。 */
private const val MATRIX_BIN_RESOURCE_PATH = "mecab-ipadic/matrix.bin"

/**
 * momiji [Matrix] をラップした [ConnectionCostProvider]。
 *
 * [Matrix.find] の返り値は [Short]（符号付き）で、そのまま [Int] に広げて使う。
 * BOS/EOS の連接 ID には 0 を使う（momiji CostManager の実装に倣う）。
 */
private class MatrixConnectionCostProvider(
    private val matrix: Matrix,
) : ConnectionCostProvider {

    override fun transitionCost(previousRightContextId: Int, nextLeftContextId: Int): Int {
        return matrix.find(previousRightContextId, nextLeftContextId).toInt()
    }
}
