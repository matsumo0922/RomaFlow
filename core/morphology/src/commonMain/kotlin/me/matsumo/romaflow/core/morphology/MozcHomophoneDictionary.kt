package me.matsumo.romaflow.core.morphology

import kotlin.concurrent.Volatile

/**
 * Mozc OSS 辞書を素材にした [HomophoneDictionary] 実装。
 *
 * 従来の IPADIC 実装と同じく「読み→表層候補」の逆引き index を1回だけ構築してキャッシュするが、
 * Mozc は reading が既に hiragana のためカタカナ正規化は防御的に通すだけになる。index 構築は重い
 * （~1.29M 件）ため [lazyIndex] を `by lazy` とし [ensureReady] からのみ走らせる。[indexProvider] は
 * [lazyIndex] のクロージャ内だけで参照し property 保持しないため、構築完了後にエントリ列が GC 可能になる。
 *
 * ## streaming 経路（U2c）
 * [fromCompactLexicon] factory で構築すると、[MozcCompactLexicon.buildStreamingHomophoneIndex]
 * 経由でインデックスが作られる。LexemeEntry の全 List も中間 groupBy Map も作らない（heap 削減）。
 *
 * ## コンストラクタ
 * `entriesProvider: () -> List<LexemeEntry>` と `compactLexiconProvider: () -> MozcCompactLexicon` は
 * ラムダ型が衝突して overload 解決できないため、factory method（[fromEntries] / [fromCompactLexicon]）を使う。
 */
class MozcHomophoneDictionary private constructor(
    indexProvider: () -> Map<String, List<String>>,
) : HomophoneDictionary {

    private val lazyIndex: Lazy<Map<String, List<String>>> = lazy { indexProvider() }

    @Volatile
    private var isReady = false

    override fun ensureReady() {
        // lazy は thread-safe かつ once。重い構築をここで走らせ（呼び出し側が off-main）、ready を立てる。
        lazyIndex.value

        isReady = true
    }

    override fun homophoneCandidates(reading: String): List<String> {
        if (!isReady) {
            return emptyList()
        }

        val normalizedKey = normalizeReadingKey(reading)

        return lazyIndex.value[normalizedKey].orEmpty()
    }

    companion object {

        /**
         * 1 つの読みに対して保持する表層候補数の上限。
         *
         * 候補窓を無闇に膨らませないための cap。低頻度（高 wcost）まで含めると候補が膨大になる読みが
         * あるため、出現しやすい上位（単語コスト昇順）のみを残す。IPADIC 実装と同値。
         */
        const val MAX_CANDIDATES_PER_READING = 30

        /**
         * [LexemeEntry] リストから [MozcHomophoneDictionary] を生成する factory。
         *
         * [buildReverseIndex] で groupBy ベースのインデックスを構築する既存経路。
         * [MozcCompactDictionaryReader.readEntries] 等で取得したエントリ列を渡す。
         */
        fun fromEntries(entriesProvider: () -> List<LexemeEntry>): MozcHomophoneDictionary =
            MozcHomophoneDictionary { buildReverseIndex(entriesProvider()) }

        /**
         * [MozcCompactLexicon] を使う streaming 経路（U2c）から [MozcHomophoneDictionary] を生成する factory。
         *
         * [MozcCompactLexicon.buildStreamingHomophoneIndex] が sortedOrder の 1 パスグループ化で
         * 同音語 index を構築するため、LexemeEntry の List も中間 groupBy Map も生成しない。
         */
        fun fromCompactLexicon(compactLexiconProvider: () -> MozcCompactLexicon): MozcHomophoneDictionary =
            MozcHomophoneDictionary { compactLexiconProvider().buildStreamingHomophoneIndex() }

        /**
         * [entries] から、ひらがなキー → 表層候補リストの逆引き index を構築する純関数。
         *
         * 各読みの候補は単語コスト昇順（同コストは安定）に並べ、同一表層形は最初の1件のみ残し、
         * [MAX_CANDIDATES_PER_READING] 件で打ち切る。表層形が読みと同一の素通しエントリ（変換価値なし）は除外する。
         */
        internal fun buildReverseIndex(entries: List<LexemeEntry>): Map<String, List<String>> {
            val grouped = groupConvertibleEntriesByReadingKey(entries)

            return grouped.mapValues { (_, readingEntries) -> selectSurfaces(readingEntries) }
        }

        /**
         * [reading] をひらがなに正規化したキーを返す。
         *
         * Mozc の reading は hiragana だが、堅牢性のためカタカナ（[ReadingNormalizer.katakanaToHiragana]）も
         * ひらがな化して引数 reading と辞書側で同じ正規化を通す。
         */
        internal fun normalizeReadingKey(reading: String): String {
            return ReadingNormalizer.katakanaToHiragana(reading)
        }

        private fun groupConvertibleEntriesByReadingKey(entries: List<LexemeEntry>): Map<String, List<LexemeEntry>> {
            return entries.filter(::isConvertibleEntry)
                .groupBy { entry -> normalizeReadingKey(entry.reading) }
        }

        /**
         * 変換価値のあるエントリかを判定する。
         *
         * 表層形が読みと文字列として完全一致するエントリ（例「は」→「は」）は変換結果が入力と同じになるため除外する。
         * カタカナ表層（例 ああると→アアルト）は読みと一致しないため変換候補として残す。
         */
        private fun isConvertibleEntry(entry: LexemeEntry): Boolean {
            return entry.surface != entry.reading
        }

        private fun selectSurfaces(readingEntries: List<LexemeEntry>): List<String> {
            return readingEntries.sortedBy(LexemeEntry::wcost)
                .map(LexemeEntry::surface)
                .distinct()
                .take(MAX_CANDIDATES_PER_READING)
        }
    }
}
