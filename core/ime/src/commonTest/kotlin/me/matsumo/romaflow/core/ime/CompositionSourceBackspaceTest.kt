package me.matsumo.romaflow.core.ime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A-1 backspace 削除契約のゴールデンコーパステスト。
 *
 * イベント列（input("kyo")→backspace→expect("き")等）で削除写像を固定し、
 * [CompositionSource] / [TransliterationTrace] 上の削除が正しい結果を返すことを検証する。
 *
 * ## 削除契約の要点
 * - pending 非空時: pending の末尾1文字を削る（raw pending dropLast 相当）。
 * - pending 空時: committed 末尾エッジの最後のかなを削る。
 *   - エッジの reading が1文字なら丸ごとエッジを削除（`か` → 空）。
 *   - エッジの reading が2文字以上なら最後の文字だけ削り残す（`きょ` → `き`）。
 * - `kyo→きょ→BS→き` は raw atom 単純削除では再現不可。[LiteralReading] エッジとして再表現する。
 *
 * これらのケースは [RomaFlowEngine] レベルでも整合性を確認する。
 */
class CompositionSourceBackspaceTest {

    private val converter = RomajiKanaConverter()

    // ---- CompositionSource 単体テスト ----

    @Test
    fun backspace_onEmptySourceIsNoOp() {
        val source = CompositionSource.empty()
        val after = source.deleteLastKana(nextRevision = 1)

        assertEquals("", after.readingInput)
        assertEquals("", after.pendingRomaji)
    }

    @Test
    fun backspace_deletesPendingTailFirst() {
        // "ky" → pending="ky"
        val source = CompositionSource.empty().appendRomaji(converter, "ky", nextRevision = 1, nextAtomId = 0)

        assertEquals("", source.readingInput)
        assertEquals("ky", source.pendingRomaji)

        // BS → pending="k"
        val after = source.deleteLastKana(nextRevision = 2)

        assertEquals("", after.readingInput)
        assertEquals("k", after.pendingRomaji)
    }

    @Test
    fun backspace_deletesAllOfPendingWhenSingleChar() {
        // "k" → pending="k"
        val source = CompositionSource.empty().appendRomaji(converter, "k", nextRevision = 1, nextAtomId = 0)

        assertEquals("", source.readingInput)
        assertEquals("k", source.pendingRomaji)

        // BS → pending=""
        val after = source.deleteLastKana(nextRevision = 2)

        assertEquals("", after.readingInput)
        assertEquals("", after.pendingRomaji)
    }

    @Test
    fun backspace_onSingleKanaEdgeRemovesIt() {
        // "ka" → committed="か" pending=""
        val source = CompositionSource.empty().appendRomaji(converter, "ka", nextRevision = 1, nextAtomId = 0)

        assertEquals("か", source.readingInput)
        assertEquals("", source.pendingRomaji)

        // BS → committed="" (かが1文字なので丸ごと削除)
        val after = source.deleteLastKana(nextRevision = 2)

        assertEquals("", after.readingInput)
        assertEquals("", after.pendingRomaji)
    }

    @Test
    fun backspace_kyoToKyo_backspace_givesKi() {
        // "kyo" → committed="きょ" pending=""
        val source = CompositionSource.empty().appendRomaji(converter, "kyo", nextRevision = 1, nextAtomId = 0)

        assertEquals("きょ", source.readingInput)
        assertEquals("", source.pendingRomaji)

        // BS → committed="き" (2文字エッジの末尾1文字だけ削除)
        val after = source.deleteLastKana(nextRevision = 2)

        assertEquals("き", after.readingInput)
        assertEquals("", after.pendingRomaji)
    }

    @Test
    fun backspace_sokuon_ttaToTta_backspace_givesU() {
        // "tta" → committed="った" pending=""（促音＋た は1エッジ）
        val source = CompositionSource.empty().appendRomaji(converter, "tta", nextRevision = 1, nextAtomId = 0)

        assertEquals("った", source.readingInput)
        assertEquals("", source.pendingRomaji)

        // BS → committed="っ"（2文字エッジの末尾1文字だけ削除）
        val after = source.deleteLastKana(nextRevision = 2)

        assertEquals("っ", after.readingInput)
        assertEquals("", after.pendingRomaji)
    }

    @Test
    fun backspace_shiToShi_backspace_givesEmpty() {
        // "shi" → committed="し" pending=""（1文字エッジ）
        val source = CompositionSource.empty().appendRomaji(converter, "shi", nextRevision = 1, nextAtomId = 0)

        assertEquals("し", source.readingInput)
        assertEquals("", source.pendingRomaji)

        // BS → committed=""（1文字エッジなので丸ごと削除）
        val after = source.deleteLastKana(nextRevision = 2)

        assertEquals("", after.readingInput)
        assertEquals("", after.pendingRomaji)
    }

    @Test
    fun backspace_multipleKana_deletesOneByOne() {
        // "kaki" → committed="かき" pending=""（2エッジ）
        var source = CompositionSource.empty().appendRomaji(converter, "ka", nextRevision = 1, nextAtomId = 0)

        source = source.appendRomaji(converter, "ki", nextRevision = 2, nextAtomId = 2)

        assertEquals("かき", source.readingInput)

        // BS1 → "か"
        val after1 = source.deleteLastKana(nextRevision = 3)
        assertEquals("か", after1.readingInput)

        // BS2 → ""
        val after2 = after1.deleteLastKana(nextRevision = 4)
        assertEquals("", after2.readingInput)
    }

    @Test
    fun backspace_nAtomThenBs_givesEmpty() {
        // "n" → pending="n"（lone n は pending）
        val source = CompositionSource.empty().appendRomaji(converter, "n", nextRevision = 1, nextAtomId = 0)

        assertEquals("", source.readingInput)
        assertEquals("n", source.pendingRomaji)

        // BS → pending=""（pending の末尾1文字削除）
        val after = source.deleteLastKana(nextRevision = 2)

        assertEquals("", after.readingInput)
        assertEquals("", after.pendingRomaji)
    }

    @Test
    fun backspace_nnThenBs_givesEmpty() {
        // "nn" → committed="ん" pending=""（nn は1エッジ）
        val source = CompositionSource.empty().appendRomaji(converter, "nn", nextRevision = 1, nextAtomId = 0)

        assertEquals("ん", source.readingInput)
        assertEquals("", source.pendingRomaji)

        // BS → committed=""（1文字エッジなので丸ごと削除）
        val after = source.deleteLastKana(nextRevision = 2)

        assertEquals("", after.readingInput)
        assertEquals("", after.pendingRomaji)
    }

    @Test
    fun backspace_finalizePendingThenBs_givesEmpty() {
        // "n" → pending="n" → finalize → committed="ん"
        var source = CompositionSource.empty().appendRomaji(converter, "n", nextRevision = 1, nextAtomId = 0)

        assertEquals("n", source.pendingRomaji)

        source = source.finalizePending(converter)

        assertEquals("ん", source.readingInput)
        assertEquals("", source.pendingRomaji)

        // BS → committed=""（ん は1文字エッジなので丸ごと削除）
        val after = source.deleteLastKana(nextRevision = 2)

        assertEquals("", after.readingInput)
        assertEquals("", after.pendingRomaji)
    }

    @Test
    fun backspace_kyoPendingStep_pendingDropsCharByChar() {
        // "k" → pending="k"
        var source = CompositionSource.empty().appendRomaji(converter, "k", nextRevision = 1, nextAtomId = 0)
        assertEquals("k", source.pendingRomaji)

        // "ky" → pending="ky"
        source = source.appendRomaji(converter, "y", nextRevision = 2, nextAtomId = 1)
        assertEquals("ky", source.pendingRomaji)

        // BS → pending="k"（pending の末尾1文字削除）
        val afterBs = source.deleteLastKana(nextRevision = 3)
        assertEquals("k", afterBs.pendingRomaji)
        assertEquals("", afterBs.readingInput)

        // "ya" → pending="k"+"ya" → rebuild [k,y,a] → "kya"="きゃ"
        // まず "y" を追記 → pending="ky"
        val withY = afterBs.appendRomaji(converter, "y", nextRevision = 4, nextAtomId = 2)
        assertEquals("ky", withY.pendingRomaji)

        // さらに "a" → "きゃ"
        val withA = withY.appendRomaji(converter, "a", nextRevision = 5, nextAtomId = 3)
        assertEquals("きゃ", withA.readingInput)
        assertEquals("", withA.pendingRomaji)
    }

    // ---- RomaFlowEngine レベルの golden corpus テスト ----

    @Test
    fun engine_kyo_backspace_givesKi() {
        val engine = newEngine()
        engine.inputRomaji("kyo")

        // "kyo" → preedit = "きょ"
        assertEquals("きょ", engine.preeditText())

        // BS → preedit = "き"（きょ の末尾1かな削除）
        assertEquals("き", engine.deleteBackward())
    }

    @Test
    fun engine_ky_backspace_givesK() {
        val engine = newEngine()
        engine.inputRomaji("ky")

        // pending="ky"（未確定）→ preedit = "ky"
        assertEquals("ky", engine.preeditText())

        // BS → pending="k"（pending 末尾1文字削除）
        assertEquals("k", engine.deleteBackward())
    }

    @Test
    fun engine_tta_backspace_givesSmallTsu() {
        val engine = newEngine()
        engine.inputRomaji("tta")

        assertEquals("った", engine.preeditText())

        // BS → "っ"
        assertEquals("っ", engine.deleteBackward())
    }

    @Test
    fun engine_n_backspace_givesEmpty() {
        val engine = newEngine()
        engine.inputRomaji("n")

        assertEquals("n", engine.preeditText())

        // BS → "" (lone n pending の末尾1文字削除)
        assertEquals("", engine.deleteBackward())
        assertFalse(engine.hasComposition())
    }

    @Test
    fun engine_ka_backspace_givesEmpty() {
        val engine = newEngine()
        engine.inputRomaji("ka")

        assertEquals("か", engine.preeditText())

        // BS → "" (か は1文字エッジなので丸ごと削除)
        assertEquals("", engine.deleteBackward())
        assertFalse(engine.hasComposition())
    }

    @Test
    fun engine_kaki_backspace_twice_givesEmpty() {
        val engine = newEngine()
        engine.inputRomaji("kaki")

        assertEquals("かき", engine.preeditText())

        assertEquals("か", engine.deleteBackward())
        assertEquals("", engine.deleteBackward())
        assertFalse(engine.hasComposition())
    }

    @Test
    fun engine_nn_backspace_givesEmpty() {
        val engine = newEngine()
        engine.inputRomaji("nn")

        assertEquals("ん", engine.preeditText())

        assertEquals("", engine.deleteBackward())
        assertFalse(engine.hasComposition())
    }

    @Test
    fun engine_nFollowedByConsonant_backspace_givesConsonantPending() {
        val engine = newEngine()
        engine.inputRomaji("nb")

        // "n"+"b" → ん + pending "b"
        assertEquals("んb", engine.preeditText())

        // BS → "ん"（pending "b" の末尾1文字削除）
        assertEquals("ん", engine.deleteBackward())
    }

    private fun newEngine(): RomaFlowEngine {
        return RomaFlowEngine(FakeConversionProvider(), FakeSegmenter(), FakeAligner())
    }
}
