package me.matsumo.romaflow.core.morphology

/**
 * Mozc 連接表（row-major のフラット行列）に基づく [ConnectionCostProvider]。
 *
 * `cost[previousRightContextId * [dimension] + nextLeftContextId]` を引く。値は Mozc の
 * `connection_single_column.txt` 由来で値域 0〜15281＝[Short] に収まるため、ヒープ節約のため
 * [ShortArray] で保持する（Mozc は ~1.29M エントリで連接表 2672² も大きい）。BOS/EOS の連接 ID は 0。
 */
class MozcConnectionCostProvider(
    private val dimension: Int,
    private val costs: ShortArray,
) : ConnectionCostProvider {

    override fun transitionCost(previousRightContextId: Int, nextLeftContextId: Int): Int {
        return costs[previousRightContextId * dimension + nextLeftContextId].toInt()
    }
}
