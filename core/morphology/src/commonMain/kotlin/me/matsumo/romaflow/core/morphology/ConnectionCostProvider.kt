package me.matsumo.romaflow.core.morphology

/**
 * 語と語の連接コストを返す platform adapter。
 *
 * [previousRightContextId] は前の語の rcAttr、[nextLeftContextId] は次の語の lcAttr。
 * 文頭→先頭語: previousRightContextId=0、最終語→文末: nextLeftContextId=0。
 * 実装は各プラットフォームの行列取得経路を分ける。
 */
interface ConnectionCostProvider {

    /**
     * [previousRightContextId]（前の語の右文脈 ID）と [nextLeftContextId]（次の語の左文脈 ID）
     * の組み合わせによる連接コストを返す。
     */
    fun transitionCost(previousRightContextId: Int, nextLeftContextId: Int): Int
}

/**
 * テスト用のゼロコスト実装。連接コストを常に 0 とする。
 */
object ZeroConnectionCostProvider : ConnectionCostProvider {

    override fun transitionCost(previousRightContextId: Int, nextLeftContextId: Int): Int = 0
}
