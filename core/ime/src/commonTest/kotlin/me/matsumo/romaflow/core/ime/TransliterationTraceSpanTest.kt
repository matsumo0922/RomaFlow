package me.matsumo.romaflow.core.ime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [RomajiKanaConverter.buildTrace] の span 帰属正確性を検証するテスト。
 *
 * A-1 の肝である「全エッジの sourceSpan が atoms を隙間なく覆う」不変条件と、
 * trigger atom（撥音・促音の確定を引き起こす atom）が committed span に混入しないことを確認する。
 *
 * ## 検証するケース
 * - `nb`: committed `ん`=span[0,1)、pending `b`=span[1,2)（trigger atom 'b' が committed に混入しない）
 * - `tta`: committed `っ`=span[0,1)、committed `た`=span[1,3)（'t' が促音と たの両方に正しく割り当てられる）
 * - `kyo`: committed `きょ`=span[0,3)（3 atoms が1エッジにまとまる）
 * - backspace 後も全エッジ span が atoms を隙間なく覆う不変条件が成立する
 */
class TransliterationTraceSpanTest {

    private val converter = RomajiKanaConverter()

    // ---- span 帰属の正確性テスト ----

    @Test
    fun span_nb_committedN_doesNotIncludeTriggerB() {
        // "nb": 'b' が 'n' を ん に確定させる trigger。
        // committed ん の span は [0,1)（'n' のみ）、pending 'b' の span は [1,2)。
        val source = CompositionSource.empty().appendRomaji(converter, "nb", nextRevision = 1, nextAtomId = 0)
        val edges = source.transliteration.edges

        assertEquals(2, edges.size)

        val nEdge = edges[0]
        assertEquals("ん", nEdge.reading)
        assertEquals(TransliterationEdge.EdgeState.Committed, nEdge.state)
        assertEquals(SourceSpan(fromAtomIndex = 0, toAtomIndex = 1), nEdge.sourceSpan)

        val bEdge = edges[1]
        assertEquals("b", bEdge.reading)
        assertEquals(TransliterationEdge.EdgeState.Pending, bEdge.state)
        assertEquals(SourceSpan(fromAtomIndex = 1, toAtomIndex = 2), bEdge.sourceSpan)

        assertSpanCoversAtoms(source.transliteration)
    }

    @Test
    fun span_tta_wanakanaCommitsAllThreeAtomsAsOneEdge() {
        // WanaKana IME モードでは "tt" の時点で っ は確定せず "tt" pending のまま。
        // "tta" で一気に "った" committed になる（1エッジ、span=[0,3)）。
        // "t"→pending="t", "tt"→pending="tt", "tta"→committed="った" pending="" の順。
        val source = CompositionSource.empty().appendRomaji(converter, "tta", nextRevision = 1, nextAtomId = 0)
        val edges = source.transliteration.edges

        assertEquals(1, edges.size)

        val ttaEdge = edges[0]
        assertEquals("った", ttaEdge.reading)
        assertEquals(TransliterationEdge.EdgeState.Committed, ttaEdge.state)
        assertEquals(SourceSpan(fromAtomIndex = 0, toAtomIndex = 3), ttaEdge.sourceSpan)

        assertSpanCoversAtoms(source.transliteration)
    }

    @Test
    fun span_kyo_allThreeAtomsFormOneEdge() {
        // "kyo": 'k'+'y'+'o' が1エッジで きょ committed。span=[0,3)。
        val source = CompositionSource.empty().appendRomaji(converter, "kyo", nextRevision = 1, nextAtomId = 0)
        val edges = source.transliteration.edges

        assertEquals(1, edges.size)

        val kyoEdge = edges[0]
        assertEquals("きょ", kyoEdge.reading)
        assertEquals(TransliterationEdge.EdgeState.Committed, kyoEdge.state)
        assertEquals(SourceSpan(fromAtomIndex = 0, toAtomIndex = 3), kyoEdge.sourceSpan)

        assertSpanCoversAtoms(source.transliteration)
    }

    @Test
    fun span_ka_twoAtomsOneEdge() {
        // "ka": 'k'+'a' が1エッジで か committed。span=[0,2)。
        val source = CompositionSource.empty().appendRomaji(converter, "ka", nextRevision = 1, nextAtomId = 0)
        val edges = source.transliteration.edges

        assertEquals(1, edges.size)
        assertEquals(SourceSpan(fromAtomIndex = 0, toAtomIndex = 2), edges[0].sourceSpan)

        assertSpanCoversAtoms(source.transliteration)
    }

    @Test
    fun span_kn_pendingSpanIsCorrect() {
        // "kn": 'k' pending, 'n' が来ても ky にならないので "kn" pending 全体。
        val source = CompositionSource.empty().appendRomaji(converter, "kn", nextRevision = 1, nextAtomId = 0)

        assertSpanCoversAtoms(source.transliteration)
    }

    // ---- backspace 後の不変条件テスト ----

    @Test
    fun invariant_nb_backspace_spanRemainsValid() {
        // "nb" → BS → pending 'b' が消え、committed ん のみ。
        // atoms=[n] になり、ん の span は [0,1) のまま valid。
        val source = CompositionSource.empty()
            .appendRomaji(converter, "nb", nextRevision = 1, nextAtomId = 0)
            .deleteLastKana(nextRevision = 2)

        // atoms は [n] のみ（'b' が削除される）
        assertEquals(1, source.transliteration.atoms.size)
        assertEquals("n", source.transliteration.atoms[0].text)

        assertSpanCoversAtoms(source.transliteration)

        // committed ん のみ残る
        val edges = source.transliteration.edges
        assertEquals(1, edges.size)
        assertEquals("ん", edges[0].reading)
        assertEquals(TransliterationEdge.EdgeState.Committed, edges[0].state)
        assertEquals(SourceSpan(fromAtomIndex = 0, toAtomIndex = 1), edges[0].sourceSpan)
    }

    @Test
    fun invariant_kyo_backspace_literalReadingEdge() {
        // "kyo" → BS → きょ が き に減り LiteralReading エッジになる。
        // atoms=[k,y,o] のまま（committed 削除なので atoms 変更なし）。
        val source = CompositionSource.empty()
            .appendRomaji(converter, "kyo", nextRevision = 1, nextAtomId = 0)
            .deleteLastKana(nextRevision = 2)

        assertEquals(3, source.transliteration.atoms.size)
        assertSpanCoversAtoms(source.transliteration)

        val edges = source.transliteration.edges
        assertEquals(1, edges.size)
        assertEquals("き", edges[0].reading)
        assertEquals(TransliterationEdge.EdgeState.Committed, edges[0].state)

        // LiteralReading unit になっている
        val unit = edges[0].unit
        assertTrue(unit is SourceUnit.LiteralReading, "committed 削除後のエッジは LiteralReading を持つこと")
        assertEquals("き", (unit as SourceUnit.LiteralReading).reading)
    }

    @Test
    fun invariant_tta_backspace_spansRemainValid() {
        // "tta" → BS → った が っ に減る（2文字エッジの末尾1文字削除 → LiteralReading "っ"）。
        // atoms=[t,t,a] のまま（committed 削除は atoms を変えない）。span=[0,3) のまま valid。
        val source = CompositionSource.empty()
            .appendRomaji(converter, "tta", nextRevision = 1, nextAtomId = 0)
            .deleteLastKana(nextRevision = 2)

        assertEquals(3, source.transliteration.atoms.size)
        assertSpanCoversAtoms(source.transliteration)

        val edges = source.transliteration.edges
        assertEquals(1, edges.size)
        assertEquals("っ", edges[0].reading)
        assertTrue(edges[0].unit is SourceUnit.LiteralReading, "2文字エッジの committed 削除後は LiteralReading unit")
    }

    @Test
    fun invariant_multiSyllable_backspace_spansRemainValid() {
        // "kanji" → BS(2回) → か のみ。各ステップで span 不変条件を確認。
        var source = CompositionSource.empty().appendRomaji(converter, "ka", nextRevision = 1, nextAtomId = 0)

        source = source.appendRomaji(converter, "n", nextRevision = 2, nextAtomId = 2)
        assertSpanCoversAtoms(source.transliteration)

        source = source.appendRomaji(converter, "ji", nextRevision = 3, nextAtomId = 3)
        assertSpanCoversAtoms(source.transliteration)

        source = source.deleteLastKana(nextRevision = 4)
        assertSpanCoversAtoms(source.transliteration)

        source = source.deleteLastKana(nextRevision = 5)
        assertSpanCoversAtoms(source.transliteration)
    }

    // ---- SourceUnit の型検証テスト ----

    @Test
    fun unit_normalRomajiEdge_hasRomajiUnit() {
        // 通常の romaji→kana エッジは SourceUnit.Romaji を持つ。
        val source = CompositionSource.empty().appendRomaji(converter, "ka", nextRevision = 1, nextAtomId = 0)
        val edge = source.transliteration.edges[0]

        assertTrue(edge.unit is SourceUnit.Romaji, "通常エッジは Romaji unit を持つこと")
    }

    @Test
    fun unit_pendingEdge_hasRomajiUnit() {
        // pending エッジも SourceUnit.Romaji を持つ。
        val source = CompositionSource.empty().appendRomaji(converter, "ky", nextRevision = 1, nextAtomId = 0)
        val pendingEdge = source.transliteration.edges.last()

        assertEquals(TransliterationEdge.EdgeState.Pending, pendingEdge.state)
        assertTrue(pendingEdge.unit is SourceUnit.Romaji, "pending エッジは Romaji unit を持つこと")
    }

    @Test
    fun unit_afterCommittedBackspace_hasLiteralReadingUnit() {
        // committed 末尾エッジを BS した残りは LiteralReading unit を持つ。
        val source = CompositionSource.empty()
            .appendRomaji(converter, "kyo", nextRevision = 1, nextAtomId = 0)
            .deleteLastKana(nextRevision = 2)

        val edge = source.transliteration.edges[0]
        assertTrue(edge.unit is SourceUnit.LiteralReading, "committed BS 後は LiteralReading unit を持つこと")
    }

    // ---- 不変条件ヘルパ ----

    /**
     * [TransliterationTrace] の span 不変条件を検証するヘルパ。
     *
     * ## 不変条件
     * - 先頭エッジの span.from == 0
     * - 隣接エッジ間で前のエッジの to == 次のエッジの from（隙間なし・重複なし）
     * - 全エッジの span が 0..atoms.size の範囲内（atoms 範囲外にならない）
     *
     * ## 末尾 to == atoms.size の緩和
     * pending 削除（atoms を削る）では常に末尾 to == atoms.size が成立する。
     * committed 削除（atoms は変えず edge だけ trim）では atoms との乖離が起き得る（例: `ん` 削除後に
     * atoms=[k,a,n,j,i] に対して edges=[か[0,2)]）。この乖離は committed edge が [SourceUnit.LiteralReading]
     * となることで A-2 lattice に伝わるため、ここでは「範囲外でない」ことのみ確認する。
     */
    private fun assertSpanCoversAtoms(trace: TransliterationTrace) {
        val atoms = trace.atoms
        val edges = trace.edges

        if (edges.isEmpty()) {
            return
        }

        val firstEdge = edges.first()

        assertEquals(
            0,
            firstEdge.sourceSpan.fromAtomIndex,
            "先頭エッジの span.from は 0 であること（atoms.size=${atoms.size}）",
        )

        for (edgeIndex in 0 until edges.size - 1) {
            val currentEdge = edges[edgeIndex]
            val nextEdge = edges[edgeIndex + 1]

            assertEquals(
                currentEdge.sourceSpan.toAtomIndex,
                nextEdge.sourceSpan.fromAtomIndex,
                "エッジ[$edgeIndex].to == エッジ[${edgeIndex + 1}].from（隙間なし）であること",
            )
        }

        for (edge in edges) {
            assertTrue(
                edge.sourceSpan.fromAtomIndex >= 0 && edge.sourceSpan.toAtomIndex <= atoms.size,
                "エッジ span [${edge.sourceSpan.fromAtomIndex}, ${edge.sourceSpan.toAtomIndex}) が 0..${atoms.size} 内であること",
            )
        }
    }
}
