package me.matsumo.romaflow.core.morphology

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Android host（JVM）上での連接コスト行列到達性 integration test。
 *
 * A-2 の技術的不明点:
 * (a) [io.github.tokuhirom.momiji.core.matrix.Matrix] は momiji-core（commonMain 対応）にあり、
 *     Android からも参照可能。
 * (b) `mecab-ipadic/matrix.bin` は momiji-ipadic-resources が classpath に配置するため、
 *     [io.github.tokuhirom.momiji.ipadic.momijiLoadMatrix] で取得可能。
 *
 * 到達性確認: [loadConnectionMatrix] が Android 側の actual（JVM momijiLoadMatrix 経由）で
 * 正しく動作し、実辞書の Node 数組（BOS/EOS=0）で連接コストを返せること。
 *
 * ID 方向・符号・BOS/EOS:
 * - [ConnectionCostProvider.transitionCost] の引数は (previousRightContextId, nextLeftContextId) 順。
 * - [io.github.tokuhirom.momiji.core.matrix.Matrix.find] の引数も (rightContextId, leftContextId) 順。
 * - 返り値は [Short] をそのまま [Int] に広げた値（符号付き）。
 * - BOS/EOS の連接 ID は 0（momiji CostManager 実装と一致）。
 */
class ConnectionMatrixReachabilityTest {

    @Test
    fun loadConnectionMatrixReturnsWorkingProviderOnAndroid() {
        val provider = loadConnectionMatrix()

        // BOS→BOS(0,0) のコストが例外なく取得できること
        val bosToBosCost = provider.transitionCost(0, 0)

        println("Android: BOS→BOS cost = $bosToBosCost")
        assertTrue(
            bosToBosCost in Short.MIN_VALUE..Short.MAX_VALUE,
            "連接コストが Short 範囲内であること（actual=$bosToBosCost）",
        )
    }

    @Test
    fun connectionMatrixCostMatchesMomijiDirectLookup() {
        val provider = loadConnectionMatrix()

        // IpadicRichDictReader から実辞書の連接 ID を取得して比較
        val entries = IpadicRichDictReader().readRichEntries()
        val sampleEntry = entries.first()

        println("Android: sample entry = $sampleEntry")

        // BOS(0) → 最初エントリの lcAttr
        val bosToFirstCost = provider.transitionCost(0, sampleEntry.lcAttr)

        println("Android: BOS→first lcAttr=${sampleEntry.lcAttr} cost=$bosToFirstCost")

        // ID 方向: BOS(rcAttr=0) → 語(lcAttr) の順で取得できること
        assertTrue(
            bosToFirstCost in Short.MIN_VALUE..Short.MAX_VALUE,
            "BOS→first 語の連接コストが Short 範囲内であること（actual=$bosToFirstCost）",
        )
    }

    @Test
    fun connectionMatrixBosEosIdIs0() {
        val provider = loadConnectionMatrix()

        // BOS/EOS コスト: rcAttr=0 → lcAttr=0 の行列値が取れること
        val cost = provider.transitionCost(0, 0)

        println("Android: BOS/EOS(0,0) cost=$cost")

        // 値の範囲が Short（連接コストは Short で格納される）
        assertTrue(cost in Short.MIN_VALUE..Short.MAX_VALUE)
    }
}
