package me.matsumo.romaflow.core.morphology

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [MozcCompactLexicon] と [EntryListReadingLexicon] の commonPrefixSearch 結果が完全一致することを検証する。
 *
 * 手組みの MZD1 ByteArray を素材として両実装に渡し、エントリ集合・順序（endOffset 昇順・wcost 昇順）が
 * 一致することを確認する。次の edge cases を網羅する:
 * - 空のマッチ範囲（マッチしない prefix）
 * - 複数 endOffset へのマッチ
 * - 同 reading に複数 wcost エントリ（wcost 昇順が保たれること）
 * - 同 wcost の安定順（入力順が保たれること）
 * - カタカナ表層（surface != reading）エントリ
 *
 * homophone streaming（[MozcCompactLexicon.buildStreamingHomophoneIndex]）も
 * 同データで [MozcHomophoneDictionary.buildReverseIndex] と一致することを検証する。
 */
class MozcCompactLexiconParityTest {

    // ---- テストデータ ----

    /**
     * parity 検証に使う代表エントリ集合。
     *
     * - 以下 / 医科 / 異化: 同読み「いか」で wcost 順が保たれることを確認する。
     * - 以外 / 意外: 読み「いがい」で複数 endOffset が生じることを確認する（「い」→「いが」→「いがい」）。
     * - アアルト: カタカナ表層（surface != reading）。homophone 候補に残ることを確認する。
     * - ああると: surface == reading の素通しエントリ。homophone から除外されることを確認する。
     * - 文: 「ぶん」で読み前後に別の reading エントリが挟まる場合の二分探索が正しいことを確認する。
     */
    private val testEntries = listOf(
        entry(surface = "以下", reading = "いか", lcAttr = 1845, rcAttr = 1845, wcost = 1801),
        entry(surface = "医科", reading = "いか", lcAttr = 1851, rcAttr = 1851, wcost = 5631),
        entry(surface = "異化", reading = "いか", lcAttr = 1851, rcAttr = 1851, wcost = 9000),
        entry(surface = "以外", reading = "いがい", lcAttr = 1845, rcAttr = 1845, wcost = 2100),
        entry(surface = "意外", reading = "いがい", lcAttr = 1851, rcAttr = 1851, wcost = 2100),
        entry(surface = "アアルト", reading = "ああると", lcAttr = 1851, rcAttr = 1851, wcost = 7129),
        entry(surface = "ああると", reading = "ああると", lcAttr = 1851, rcAttr = 1851, wcost = 8000),
        entry(surface = "文", reading = "ぶん", lcAttr = 1845, rcAttr = 1845, wcost = 500),
    )

    // ---- コンパクト lexicon / エントリリスト lexicon の構築 ----

    private fun buildCompactLexicon(): MozcCompactLexicon =
        MozcCompactLexicon(encodeDictionary(testEntries))

    private fun buildEntryListLexicon(): EntryListReadingLexicon =
        EntryListReadingLexicon(testEntries)

    // ---- commonPrefixSearch parity テスト ----

    @Test
    fun commonPrefixSearchMatchesSingleReading() {
        val compact = buildCompactLexicon()
        val entryList = buildEntryListLexicon()

        val compactResult = compact.commonPrefixSearch("いか", 0)
        val entryListResult = entryList.commonPrefixSearch("いか", 0)

        assertMatchListEquals(entryListResult, compactResult, "いか startOffset=0")
    }

    @Test
    fun commonPrefixSearchMultipleEndOffsets() {
        val compact = buildCompactLexicon()
        val entryList = buildEntryListLexicon()

        // 「いがい」は endOffset=2（いが: 未登録）、endOffset=3（いがい）で複数 endOffset が生じる。
        val compactResult = compact.commonPrefixSearch("いがい", 0)
        val entryListResult = entryList.commonPrefixSearch("いがい", 0)

        assertMatchListEquals(entryListResult, compactResult, "いがい startOffset=0")
    }

    @Test
    fun commonPrefixSearchWcostOrderPreserved() {
        val compact = buildCompactLexicon()
        val entryList = buildEntryListLexicon()

        val compactResult = compact.commonPrefixSearch("いか", 0)

        // wcost 昇順: 1801（以下）< 5631（医科）< 9000（異化）
        assertEquals(
            listOf("以下", "医科", "異化"),
            compactResult.map { match -> match.lexeme.surface },
            "wcost 昇順が保たれること",
        )

        val entryListResult = entryList.commonPrefixSearch("いか", 0)

        assertMatchListEquals(entryListResult, compactResult, "wcost 順の parity")
    }

    @Test
    fun commonPrefixSearchSameWcostStableOrder() {
        val compact = buildCompactLexicon()
        val entryList = buildEntryListLexicon()

        // 以外・意外は同 wcost（2100）。file 順（以外→意外）が保たれることを確認する。
        val compactResult = compact.commonPrefixSearch("いがい", 0)
        val entryListResult = entryList.commonPrefixSearch("いがい", 0)

        assertMatchListEquals(entryListResult, compactResult, "同 wcost 安定順の parity")
    }

    @Test
    fun commonPrefixSearchKatakanaSurface() {
        val compact = buildCompactLexicon()
        val entryList = buildEntryListLexicon()

        // カタカナ表層（アアルト）は reading=ああると で検索できること。
        val compactResult = compact.commonPrefixSearch("ああると", 0)
        val entryListResult = entryList.commonPrefixSearch("ああると", 0)

        assertMatchListEquals(entryListResult, compactResult, "ああると startOffset=0")
    }

    @Test
    fun commonPrefixSearchStartOffsetRespected() {
        val compact = buildCompactLexicon()
        val entryList = buildEntryListLexicon()

        // startOffset=1 で「あいか」の 2 文字目以降を検索。
        val reading = "あいか"
        val compactResult = compact.commonPrefixSearch(reading, 1)
        val entryListResult = entryList.commonPrefixSearch(reading, 1)

        assertMatchListEquals(entryListResult, compactResult, "startOffset=1")
    }

    @Test
    fun commonPrefixSearchNoMatchReturnsEmpty() {
        val compact = buildCompactLexicon()
        val entryList = buildEntryListLexicon()

        val compactResult = compact.commonPrefixSearch("zzzzz", 0)
        val entryListResult = entryList.commonPrefixSearch("zzzzz", 0)

        assertMatchListEquals(entryListResult, compactResult, "未登録 reading")
    }

    @Test
    fun commonPrefixSearchDifferentReading() {
        val compact = buildCompactLexicon()
        val entryList = buildEntryListLexicon()

        val compactResult = compact.commonPrefixSearch("ぶん", 0)
        val entryListResult = entryList.commonPrefixSearch("ぶん", 0)

        assertMatchListEquals(entryListResult, compactResult, "ぶん startOffset=0")
    }

    // ---- カタカナ混じり reading の正規化 parity テスト（修正1 ロック）----

    /**
     * reading にカタカナ（`ヶ`）が混じるエントリで、[MozcCompactLexicon] と [EntryListReadingLexicon] の
     * commonPrefixSearch 結果が完全一致することを検証する。
     *
     * [EntryListReadingLexicon.buildReverseIndex] は [ReadingNormalizer.katakanaToHiragana] で
     * reading を正規化してから index 化する。[MozcCompactLexicon] も同一正規化を通さないと、
     * `ヶ`（U+30F6）→ `ゖ`（U+3096）への変換が行われず parity が崩れる。
     * 実 Mozc dict に存在する `ヶ` 含む reading（例: 三ヶ月 → さんヶげつ のようなケース）を模したテスト。
     */
    @Test
    fun commonPrefixSearchKatakanaInReadingNormalized() {
        // reading に `ヶ` を含むエントリ。katakanaToHiragana で `ヶ`→`ゖ` に変換される。
        val katakanaReadingEntries = listOf(
            entry(surface = "一ヶ月", reading = "いちヶげつ", lcAttr = 1845, rcAttr = 1845, wcost = 2000),
            entry(surface = "三ヶ月", reading = "さんヶげつ", lcAttr = 1845, rcAttr = 1845, wcost = 2000),
        )

        val compact = MozcCompactLexicon(encodeDictionary(katakanaReadingEntries))
        val entryList = EntryListReadingLexicon(katakanaReadingEntries)

        // EntryListReadingLexicon は `いちゖげつ` をキーにするため、`いちヶげつ` では引けない。
        // MozcCompactLexicon も同じ正規化を通すため両者の結果が一致する（ともに空）。
        val queryKatakana = "いちヶげつ"
        val compactKatakana = compact.commonPrefixSearch(queryKatakana, 0)
        val entryListKatakana = entryList.commonPrefixSearch(queryKatakana, 0)

        assertMatchListEquals(entryListKatakana, compactKatakana, "カタカナ query（正規化前）")

        // 正規化済み hiragana クエリでは両者ともヒットする。
        val queryHiragana = "いちゖげつ"
        val compactHiragana = compact.commonPrefixSearch(queryHiragana, 0)
        val entryListHiragana = entryList.commonPrefixSearch(queryHiragana, 0)

        assertMatchListEquals(entryListHiragana, compactHiragana, "hiragana query（正規化後）")
    }

    // ---- 壊れた binary の検証テスト（修正2 ロック）----

    /**
     * count が実際のエントリ数より少ない binary（末尾に余剰バイトがある）を渡すと
     * [MozcCompactLexicon] のコンストラクタが失敗することを検証する。
     *
     * [MozcCompactDictionaryReader.readEntries] と同水準の壊れた binary 検出を確認する。
     */
    @Test
    fun rejectsTrailingBytesAfterAllEntries() {
        val entry = entry(surface = "以下", reading = "いか", lcAttr = 1, rcAttr = 1, wcost = 100)
        val validBytes = encodeDictionary(listOf(entry))

        // 末尾に余剰バイトを追加する（count=1 だが実際には余剰データあり）。
        val withTrailing = validBytes + byteArrayOf(0x00, 0x01)

        assertFailsWith<IllegalArgumentException>(
            message = "余剰バイトがある binary は拒否されること",
        ) {
            MozcCompactLexicon(withTrailing)
        }
    }

    /**
     * count が実際のエントリ数より多い（データが途中で切れた）binary を渡すと
     * [MozcCompactLexicon] のコンストラクタが失敗することを検証する。
     */
    @Test
    fun rejectsTruncatedBinary() {
        val entry = entry(surface = "以下", reading = "いか", lcAttr = 1, rcAttr = 1, wcost = 100)
        val validBytes = encodeDictionary(listOf(entry))

        // 末尾 3 バイトを切り落として truncate する。
        val truncated = validBytes.copyOf(validBytes.size - 3)

        assertFailsWith<IllegalArgumentException>(
            message = "途中で終端した binary は拒否されること",
        ) {
            MozcCompactLexicon(truncated)
        }
    }

    // ---- homophone streaming parity テスト ----

    @Test
    fun streamingHomophoneIndexMatchesBuildReverseIndex() {
        val compact = buildCompactLexicon()

        val streamingIndex = compact.buildStreamingHomophoneIndex()
        val classicIndex = MozcHomophoneDictionary.buildReverseIndex(testEntries)

        // キー集合が一致すること。
        assertEquals(classicIndex.keys.sorted(), streamingIndex.keys.sorted(), "キー集合の parity")

        // 各キーの候補リストが一致すること。
        for (key in classicIndex.keys) {
            assertEquals(classicIndex[key], streamingIndex[key], "key=$key の候補リスト parity")
        }
    }

    @Test
    fun streamingHomophoneExcludesPassthrough() {
        val compact = buildCompactLexicon()

        val streamingIndex = compact.buildStreamingHomophoneIndex()

        // ああると（surface == reading）は除外され、アアルトのみ残ること。
        assertEquals(listOf("アアルト"), streamingIndex["ああると"], "素通しエントリの除外")
    }

    /**
     * reading にカタカナを含むエントリでも、素通し判定が classic [MozcHomophoneDictionary.buildReverseIndex]
     * と一致することを検証する（素通し判定は **正規化前の raw reading** で行う必要がある）。
     *
     * 素通し判定を正規化キー（`ゖ`）と比較すると、surface=`ヶ` / reading=`ヶ` のエントリが
     * 「surface != 正規化キー」と誤判定されて候補に残り、classic と乖離する。raw reading 比較ならば
     * 正しく素通しとして除外される。
     */
    @Test
    fun streamingHomophoneUsesRawReadingForKatakanaPassthrough() {
        val katakanaEntries = listOf(
            entry(surface = "ヶ", reading = "ヶ", lcAttr = 1, rcAttr = 1, wcost = 100),
            entry(surface = "箇", reading = "ヶ", lcAttr = 1, rcAttr = 1, wcost = 200),
        )

        val compact = MozcCompactLexicon(encodeDictionary(katakanaEntries))

        val streamingIndex = compact.buildStreamingHomophoneIndex()
        val classicIndex = MozcHomophoneDictionary.buildReverseIndex(katakanaEntries)

        assertEquals(classicIndex.keys.sorted(), streamingIndex.keys.sorted(), "カタカナ reading のキー集合 parity")

        // 正規化キー `ゖ` 配下は素通し `ヶ` を除外し `箇` のみ残ること。
        assertEquals(listOf("箇"), streamingIndex["ゖ"], "raw reading 基準の素通し除外")
        assertEquals(classicIndex["ゖ"], streamingIndex["ゖ"], "key=ゖ の候補リスト parity")
    }

    // ---- ヘルパー ----

    /**
     * [expected]（EntryListReadingLexicon の結果）と [actual]（MozcCompactLexicon の結果）が
     * 完全一致することを assert する。
     *
     * 順序（endOffset 昇順・同 endOffset 内は wcost 昇順・同 wcost は安定順）も含めて検証する。
     */
    private fun assertMatchListEquals(
        expected: List<LexemeMatch>,
        actual: List<LexemeMatch>,
        context: String,
    ) {
        assertEquals(expected.size, actual.size, "[$context] 件数が一致すること")

        for (index in expected.indices) {
            val expectedMatch = expected[index]
            val actualMatch = actual[index]

            assertEquals(expectedMatch.readingEndOffset, actualMatch.readingEndOffset, "[$context] index=$index endOffset")
            assertEquals(expectedMatch.lexeme, actualMatch.lexeme, "[$context] index=$index lexeme")
        }
    }

    private fun entry(
        surface: String,
        reading: String,
        lcAttr: Int,
        rcAttr: Int,
        wcost: Int,
    ): LexemeEntry = LexemeEntry(
        surface = surface,
        reading = reading,
        lcAttr = lcAttr,
        rcAttr = rcAttr,
        posId = lcAttr,
        wcost = wcost,
    )

    private fun encodeDictionary(entries: List<LexemeEntry>): ByteArray {
        val builder = ByteListBuilder()

        builder.putMagic("MZD1")
        builder.putInt32(entries.size)

        for (entry in entries) {
            builder.putString(entry.reading)
            builder.putString(entry.surface)
            builder.putUInt16(entry.lcAttr)
            builder.putUInt16(entry.rcAttr)
            builder.putUInt16(entry.wcost)
        }

        return builder.toByteArray()
    }

    /** little-endian でバイト列を組み立てるテスト用ヘルパー。 */
    private class ByteListBuilder {

        private val bytes = mutableListOf<Byte>()

        fun putMagic(magic: String) {
            bytes.addAll(magic.encodeToByteArray().toList())
        }

        fun putInt32(value: Int) {
            for (byteIndex in 0 until INT32_BYTES) {
                bytes.add(((value ushr (byteIndex * BITS_PER_BYTE)) and BYTE_MASK).toByte())
            }
        }

        fun putUInt16(value: Int) {
            bytes.add((value and BYTE_MASK).toByte())
            bytes.add(((value ushr BITS_PER_BYTE) and BYTE_MASK).toByte())
        }

        fun putString(text: String) {
            val encoded = text.encodeToByteArray()

            putUInt16(encoded.size)
            bytes.addAll(encoded.toList())
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    private companion object {

        /** i32 のバイト長。 */
        private const val INT32_BYTES = 4

        /** 1 バイト分のビット幅。 */
        private const val BITS_PER_BYTE = 8

        /** バイトマスク。 */
        private const val BYTE_MASK = 0xFF
    }
}
