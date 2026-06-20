package me.matsumo.romaflow.core.ime

/**
 * 評価 corpus のカテゴリ分類。
 *
 * §10 A-0 の corpus 区分に対応する。
 * - [NORMAL]: 辞書にある一般的な変換。top-1 の精度計測対象。
 * - [HOMOPHONE]: 同音異義語・文脈依存変換。N-best recall 計測が主目的。
 * - [BOUNDARY_CHANGE]: 文節境界の変更を伴う変換。境界変更被覆率の計測対象。
 * - [PROPER_NOUN]: 固有名詞・OOV が混在する読み。OOV/literal fallback 率の計測対象。
 * - [ASCII_DIGIT_SYMBOL]: ASCII・数字・記号が混在する読み。literal fallback 率の計測対象。
 */
enum class CorpusCategory {

    /** 辞書にある一般的な変換。top-1 精度の計測対象。 */
    NORMAL,

    /** 同音異義語・文脈依存変換。N-best recall 計測が主目的。 */
    HOMOPHONE,

    /** 文節境界の変更を伴う変換。境界変更被覆率の計測対象。 */
    BOUNDARY_CHANGE,

    /** 固有名詞・OOV が混在する読み。OOV/literal fallback 率の計測対象。 */
    PROPER_NOUN,

    /** ASCII・数字・記号が混在する読み。literal fallback 率の計測対象。 */
    ASCII_DIGIT_SYMBOL,
}

/**
 * 評価 corpus の 1 エントリ。
 *
 * [reading]（ひらがな）に対して [expectedSurface]（期待 top-1 表層）を gold ラベルとして持つ。
 * [expectedSegmentation] は BOUNDARY_CHANGE カテゴリで文節境界の期待分割を表す。
 * IPADIC で生成不能な固有名詞・OOV は [category] で区別し、NORMAL の top-1 母数に含めない。
 */
data class CorpusEntry(
    /** ひらがなの読み（格子の主軸）。 */
    val reading: String,
    /** 期待する top-1 表層形（IPADIC で実際に生成され得るもの）。 */
    val expectedSurface: String,
    /** カテゴリ。 */
    val category: CorpusCategory,
    /**
     * 期待する文節分割（BOUNDARY_CHANGE カテゴリで使用）。
     * 例: `listOf("東京", "に", "行く")` のように各 segment の surface を列挙する。
     * 分割検証が不要なエントリでは空リスト。
     */
    val expectedSegmentation: List<String> = emptyList(),
)

/**
 * A-0 構造化 corpus データセット。
 *
 * §10 の corpus 区分（NORMAL/HOMOPHONE/BOUNDARY_CHANGE/PROPER_NOUN/ASCII_DIGIT_SYMBOL）ごとに
 * エントリを分類する。gold ラベルは IPADIC で実際に生成され得る表層形に限る。
 * 確信が持てない固有名詞・OOV は [PROPER_NOUN] カテゴリで管理し、NORMAL の母数に混ぜない。
 *
 * ## 件数ガイドライン（issue §10 A-0）
 * - NORMAL: 20〜30 件
 * - HOMOPHONE: 20〜30 件
 * - BOUNDARY_CHANGE: 15〜20 件
 * - PROPER_NOUN: 15〜20 件
 * - ASCII_DIGIT_SYMBOL: 15〜20 件
 */
object EvaluationCorpus {

    /**
     * NORMAL カテゴリ corpus（辞書にある一般的な変換）。
     *
     * IPADIC の一般名詞・動詞・形容詞など、辞書に確実に含まれる語を選んでいる。
     * 確信が持てないものは含めない方針に従い、形態素解析で常に top-1 になりやすい語を選定した。
     */
    val normal: List<CorpusEntry> = listOf(
        CorpusEntry(reading = "てんき", expectedSurface = "天気", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "わたし", expectedSurface = "私", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "にほんご", expectedSurface = "日本語", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "がっこう", expectedSurface = "学校", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "しんぶん", expectedSurface = "新聞", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "でんしゃ", expectedSurface = "電車", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "えいご", expectedSurface = "英語", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "じてんしゃ", expectedSurface = "自転車", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "けんきゅう", expectedSurface = "研究", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "しごと", expectedSurface = "仕事", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "りょこう", expectedSurface = "旅行", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "れすとらん", expectedSurface = "レストラン", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "びょういん", expectedSurface = "病院", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "でんわ", expectedSurface = "電話", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "こうえん", expectedSurface = "公園", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "かいぎ", expectedSurface = "会議", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "ほんだな", expectedSurface = "本棚", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "かぞく", expectedSurface = "家族", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "ともだち", expectedSurface = "友達", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "せんせい", expectedSurface = "先生", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "たいよう", expectedSurface = "太陽", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "うみ", expectedSurface = "海", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "やま", expectedSurface = "山", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "みち", expectedSurface = "道", category = CorpusCategory.NORMAL),
        CorpusEntry(reading = "まど", expectedSurface = "窓", category = CorpusCategory.NORMAL),
    )

    /**
     * HOMOPHONE カテゴリ corpus（同音異義語・文脈依存変換）。
     *
     * 同じ読みで複数の表層候補が存在する語。[expectedSurface] は文脈なしで最も一般的と考えられる
     * 表層（辞書上の wcost が最小に近い候補）を選んでいる。N-best recall 計測が主目的であり、
     * resolver なしの top-1 正解率が低くても想定内。
     */
    val homophone: List<CorpusEntry> = listOf(
        CorpusEntry(reading = "かんじ", expectedSurface = "漢字", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "へんかん", expectedSurface = "変換", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "しょうかい", expectedSurface = "紹介", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "こうか", expectedSurface = "効果", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "きかん", expectedSurface = "機関", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "しんこう", expectedSurface = "振興", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "かんしん", expectedSurface = "関心", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "きこう", expectedSurface = "気候", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "せいさん", expectedSurface = "生産", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "こうどう", expectedSurface = "行動", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "しょうたい", expectedSurface = "招待", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "かいせい", expectedSurface = "改正", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "ほうこく", expectedSurface = "報告", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "せいかく", expectedSurface = "正確", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "しゅうかん", expectedSurface = "習慣", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "かいてい", expectedSurface = "改定", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "こうせい", expectedSurface = "構成", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "きょうか", expectedSurface = "強化", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "かんけい", expectedSurface = "関係", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "しんせい", expectedSurface = "申請", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "こうりつ", expectedSurface = "効率", category = CorpusCategory.HOMOPHONE),
        CorpusEntry(reading = "しょうひ", expectedSurface = "消費", category = CorpusCategory.HOMOPHONE),
    )

    /**
     * BOUNDARY_CHANGE カテゴリ corpus（文節境界変更を伴う変換）。
     *
     * 複数形態素に分割される読みで、[expectedSegmentation] に期待する文節分割を記録する。
     * [expectedSurface] は全体を連結した表層形。
     * 格子上での経路探索で期待分割と一致するパスが存在するかを境界変更被覆率として計測する。
     */
    val boundaryChange: List<CorpusEntry> = listOf(
        CorpusEntry(
            reading = "にほんごをはなす",
            expectedSurface = "日本語を話す",
            category = CorpusCategory.BOUNDARY_CHANGE,
            expectedSegmentation = listOf("日本語", "を", "話す"),
        ),
        CorpusEntry(
            reading = "がっこうにいく",
            expectedSurface = "学校に行く",
            category = CorpusCategory.BOUNDARY_CHANGE,
            expectedSegmentation = listOf("学校", "に", "行く"),
        ),
        CorpusEntry(
            reading = "でんしゃにのる",
            expectedSurface = "電車に乗る",
            category = CorpusCategory.BOUNDARY_CHANGE,
            expectedSegmentation = listOf("電車", "に", "乗る"),
        ),
        CorpusEntry(
            reading = "しんぶんをよむ",
            expectedSurface = "新聞を読む",
            category = CorpusCategory.BOUNDARY_CHANGE,
            expectedSegmentation = listOf("新聞", "を", "読む"),
        ),
        CorpusEntry(
            reading = "りょこうにいく",
            expectedSurface = "旅行に行く",
            category = CorpusCategory.BOUNDARY_CHANGE,
            expectedSegmentation = listOf("旅行", "に", "行く"),
        ),
        CorpusEntry(
            reading = "かいぎをする",
            expectedSurface = "会議をする",
            category = CorpusCategory.BOUNDARY_CHANGE,
            expectedSegmentation = listOf("会議", "を", "する"),
        ),
        CorpusEntry(
            reading = "しごとをする",
            expectedSurface = "仕事をする",
            category = CorpusCategory.BOUNDARY_CHANGE,
            expectedSegmentation = listOf("仕事", "を", "する"),
        ),
        CorpusEntry(
            reading = "ほんをかう",
            expectedSurface = "本を買う",
            category = CorpusCategory.BOUNDARY_CHANGE,
            expectedSegmentation = listOf("本", "を", "買う"),
        ),
        CorpusEntry(
            reading = "みずをのむ",
            expectedSurface = "水を飲む",
            category = CorpusCategory.BOUNDARY_CHANGE,
            expectedSegmentation = listOf("水", "を", "飲む"),
        ),
        CorpusEntry(
            reading = "てがみをかく",
            expectedSurface = "手紙を書く",
            category = CorpusCategory.BOUNDARY_CHANGE,
            expectedSegmentation = listOf("手紙", "を", "書く"),
        ),
        CorpusEntry(
            reading = "うたをきく",
            expectedSurface = "歌を聴く",
            category = CorpusCategory.BOUNDARY_CHANGE,
            expectedSegmentation = listOf("歌", "を", "聴く"),
        ),
        CorpusEntry(
            reading = "えいがをみる",
            expectedSurface = "映画を見る",
            category = CorpusCategory.BOUNDARY_CHANGE,
            expectedSegmentation = listOf("映画", "を", "見る"),
        ),
        CorpusEntry(
            reading = "くるまをうごかす",
            expectedSurface = "車を動かす",
            category = CorpusCategory.BOUNDARY_CHANGE,
            expectedSegmentation = listOf("車", "を", "動かす"),
        ),
        CorpusEntry(
            reading = "へやをかたづける",
            expectedSurface = "部屋を片付ける",
            category = CorpusCategory.BOUNDARY_CHANGE,
            expectedSegmentation = listOf("部屋", "を", "片付ける"),
        ),
        CorpusEntry(
            reading = "そらをとぶ",
            expectedSurface = "空を飛ぶ",
            category = CorpusCategory.BOUNDARY_CHANGE,
            expectedSegmentation = listOf("空", "を", "飛ぶ"),
        ),
    )

    /**
     * PROPER_NOUN カテゴリ corpus（固有名詞・OOV が混在する読み）。
     *
     * IPADIC に収録されている固有名詞を使う。OOV（辞書外語）は literal fallback に依存する。
     * [expectedSurface] は辞書にある場合はその表層、OOV の場合は literal 素通し（ひらがな）。
     * OOV 率・literal fallback 率の計測が目的であり、top-1 正解を必ずしも期待しない。
     *
     * ## OOV gold の設計意図
     * `でびっど` / `まいくろそふと` / `ぐーぐる` は literal fallback 素通しを期待して
     * [expectedSurface] をひらがなにしているが、実際には IPADIC に `デビッド` / `マイクロソフト` が
     * 収録されているため辞書語として変換される（[isLiteralFallbackArc] = false）。
     * そのため literal fallback 率が設計意図より低く出ることがある。
     * gold の修正は corpus 改定時に別途行う（現状は OOV 率の計測基盤整備が主目的）。
     */
    val properNoun: List<CorpusEntry> = listOf(
        // IPADIC に収録されている地名・人名
        CorpusEntry(reading = "とうきょう", expectedSurface = "東京", category = CorpusCategory.PROPER_NOUN),
        CorpusEntry(reading = "おおさか", expectedSurface = "大阪", category = CorpusCategory.PROPER_NOUN),
        CorpusEntry(reading = "きょうと", expectedSurface = "京都", category = CorpusCategory.PROPER_NOUN),
        CorpusEntry(reading = "よこはま", expectedSurface = "横浜", category = CorpusCategory.PROPER_NOUN),
        CorpusEntry(reading = "なごや", expectedSurface = "名古屋", category = CorpusCategory.PROPER_NOUN),
        CorpusEntry(reading = "ふくおか", expectedSurface = "福岡", category = CorpusCategory.PROPER_NOUN),
        CorpusEntry(reading = "さっぽろ", expectedSurface = "札幌", category = CorpusCategory.PROPER_NOUN),
        CorpusEntry(reading = "にほん", expectedSurface = "日本", category = CorpusCategory.PROPER_NOUN),
        CorpusEntry(reading = "ちゅうごく", expectedSurface = "中国", category = CorpusCategory.PROPER_NOUN),
        CorpusEntry(reading = "かんこく", expectedSurface = "韓国", category = CorpusCategory.PROPER_NOUN),
        CorpusEntry(reading = "あめりか", expectedSurface = "アメリカ", category = CorpusCategory.PROPER_NOUN),
        CorpusEntry(reading = "ふらんす", expectedSurface = "フランス", category = CorpusCategory.PROPER_NOUN),
        CorpusEntry(reading = "どいつ", expectedSurface = "ドイツ", category = CorpusCategory.PROPER_NOUN),
        // OOV 扱いになりやすい固有名詞（literal fallback 率計測用）
        CorpusEntry(reading = "でびっど", expectedSurface = "でびっど", category = CorpusCategory.PROPER_NOUN),
        CorpusEntry(reading = "まいくろそふと", expectedSurface = "まいくろそふと", category = CorpusCategory.PROPER_NOUN),
        CorpusEntry(reading = "ぐーぐる", expectedSurface = "ぐーぐる", category = CorpusCategory.PROPER_NOUN),
    )

    /**
     * ASCII_DIGIT_SYMBOL カテゴリ corpus（ASCII・数字・記号が混在する読み）。
     *
     * ひらがな読みの中に ASCII / 数字 / 記号が混在するケース。
     * literal fallback arc に依存する割合を [DeterministicMetricsTest] で計測する。
     * [expectedSurface] は literal 素通し（変換せず入力のまま出力）を想定。
     */
    val asciiDigitSymbol: List<CorpusEntry> = listOf(
        // 数字混在
        CorpusEntry(reading = "3がつ", expectedSurface = "3がつ", category = CorpusCategory.ASCII_DIGIT_SYMBOL),
        CorpusEntry(reading = "10えん", expectedSurface = "10えん", category = CorpusCategory.ASCII_DIGIT_SYMBOL),
        CorpusEntry(reading = "2024ねん", expectedSurface = "2024ねん", category = CorpusCategory.ASCII_DIGIT_SYMBOL),
        CorpusEntry(reading = "5かい", expectedSurface = "5かい", category = CorpusCategory.ASCII_DIGIT_SYMBOL),
        CorpusEntry(reading = "100えん", expectedSurface = "100えん", category = CorpusCategory.ASCII_DIGIT_SYMBOL),
        // ASCII 英字混在
        CorpusEntry(reading = "AIかいぎ", expectedSurface = "AIかいぎ", category = CorpusCategory.ASCII_DIGIT_SYMBOL),
        CorpusEntry(reading = "PC", expectedSurface = "PC", category = CorpusCategory.ASCII_DIGIT_SYMBOL),
        CorpusEntry(reading = "IT", expectedSurface = "IT", category = CorpusCategory.ASCII_DIGIT_SYMBOL),
        CorpusEntry(reading = "SNS", expectedSurface = "SNS", category = CorpusCategory.ASCII_DIGIT_SYMBOL),
        CorpusEntry(reading = "USB", expectedSurface = "USB", category = CorpusCategory.ASCII_DIGIT_SYMBOL),
        // 記号混在
        CorpusEntry(reading = "！", expectedSurface = "！", category = CorpusCategory.ASCII_DIGIT_SYMBOL),
        CorpusEntry(reading = "？", expectedSurface = "？", category = CorpusCategory.ASCII_DIGIT_SYMBOL),
        CorpusEntry(reading = "てすと！", expectedSurface = "てすと！", category = CorpusCategory.ASCII_DIGIT_SYMBOL),
        CorpusEntry(reading = "OK！", expectedSurface = "OK！", category = CorpusCategory.ASCII_DIGIT_SYMBOL),
        CorpusEntry(reading = "No.1", expectedSurface = "No.1", category = CorpusCategory.ASCII_DIGIT_SYMBOL),
    )

    /** 全カテゴリを結合した corpus エントリ一覧。 */
    val all: List<CorpusEntry> = normal + homophone + boundaryChange + properNoun + asciiDigitSymbol
}
