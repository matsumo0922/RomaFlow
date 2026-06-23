package me.matsumo.romaflow.core.morphology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [LiteralContextIds] による literal/OOV arc の連接 ID 差し替えと、[isLiteralFallbackArc] の
 * context 一致判定を検証する。既定（IPADIC）が不変であること＝ライブ非破壊も併せて確認する。
 */
class LiteralContextIdsTest {

    @Test
    fun defaultUsesIpadicContextIds() {
        val arc = LiteralLexicon.buildLiteralLexeme('あ')

        assertEquals(LiteralContextIds.Ipadic.kanaContextId, arc.lcAttr)
        assertEquals(LiteralContextIds.Ipadic.kanaContextId, arc.rcAttr)
    }

    @Test
    fun mozcContextIdsAppliedToKanaArc() {
        val arc = LiteralLexicon.buildLiteralLexeme('あ', LiteralContextIds.Mozc)

        assertEquals(LiteralContextIds.Mozc.kanaContextId, arc.lcAttr)
        assertEquals(LiteralContextIds.Mozc.kanaContextId, arc.rcAttr)
    }

    @Test
    fun fallbackArcDetectionRequiresMatchingContextIds() {
        val mozcArc = LiteralLexicon.buildLiteralLexeme('あ', LiteralContextIds.Mozc)

        assertTrue(isLiteralFallbackArc(mozcArc, LiteralContextIds.Mozc))
        assertFalse(isLiteralFallbackArc(mozcArc))
    }

    @Test
    fun ipadicFallbackArcDetectedByDefault() {
        val ipadicArc = LiteralLexicon.buildLiteralLexeme('ア')

        assertTrue(isLiteralFallbackArc(ipadicArc))
    }
}
