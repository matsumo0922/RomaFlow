package me.matsumo.romaflow.core.morphology

import io.github.tokuhirom.momiji.core.matrix.Matrix
import io.github.tokuhirom.momiji.ipadic.momijiLoadMatrix

/**
 * momiji IPADIC の連接コスト行列を使った [ConnectionCostProvider] の macosArm64 実装。
 *
 * [momijiLoadMatrix] で得た [Matrix] を保持し、[Matrix.find] で連接コストを取得する。
 * インスタンス生成はコストが高いため、[load] を一度だけ呼ぶこと。
 */
class MomijiConnectionCostProvider(
    private val matrix: Matrix,
) : ConnectionCostProvider {

    override fun transitionCost(previousRightContextId: Int, nextLeftContextId: Int): Int {
        return matrix.find(previousRightContextId, nextLeftContextId).toInt()
    }

    companion object {

        /** IPADIC 行列をロードして [MomijiConnectionCostProvider] を生成する。重い処理のため一度だけ呼ぶこと。 */
        fun load(): MomijiConnectionCostProvider = MomijiConnectionCostProvider(momijiLoadMatrix())
    }
}
