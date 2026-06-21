package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.morphology.LexemeEntry

/**
 * factorized rerank の 1 region（同一 reading span の選択肢集合）。
 *
 * baseline 経路の 1 span について、同一 reading span に複数 surface が存在する場合に生成する。
 * 1 surface しかない span は「確定部」としてテンプレートに固定され、DecisionRegion にはならない。
 *
 * @param id region の識別子。"r0"、"r1"、... の形式で付与する。
 * @param reading この region のひらがな読み。
 * @param readingStart tail reading 内での開始 mora offset（inclusive）。
 * @param readingEnd tail reading 内での終了 mora offset（exclusive）。
 * @param options この region の候補一覧。baseline 必須・surface dedup・旧字体降格・最大6件。
 */
internal data class DecisionRegion(
    /** region の識別子（例: "r0"、"r1"）。 */
    val id: String,
    /** この region のひらがな読み。 */
    val reading: String,
    /** tail reading 内での開始 mora offset（inclusive）。 */
    val readingStart: Int,
    /** tail reading 内での終了 mora offset（exclusive）。 */
    val readingEnd: Int,
    /** この region の候補一覧（baseline 必須・dedup・旧字体降格・最大6件）。 */
    val options: List<RegionOption>,
)

/**
 * region の 1 候補。
 *
 * LLM がこの候補を選択した場合、resolver は [lexemePath] から **surface** を取り出して baseline の当該 span に
 * 差し込み、連結した表層文字列を surface-carry で返す。[lexemePath] の path identity（連接 ID / POS /
 * lexeme 同一性）は最終状態へ pin されず、`ProposalApplier` が surface から格子上の path を再探索する。
 *
 * @param id 候補の識別子。"r0o0"、"r0o1"、... の形式で付与する。
 * @param surface この候補の表層形。
 * @param lexemePath この span を覆う subpath。通常は単一 LexemeEntry。本実装では surface 抽出にのみ使う。
 */
internal data class RegionOption(
    /** 候補の識別子（例: "r0o0"、"r0o1"）。 */
    val id: String,
    /** この候補の表層形。 */
    val surface: String,
    /** この span を覆う lexeme subpath（差し込み用）。 */
    val lexemePath: List<LexemeEntry>,
)

/**
 * LLM へ渡す factorized rerank リクエスト。
 *
 * 確定部をテンプレートに、各 region を {rN} プレースホルダに展開して1回の LLM 呼び出しで
 * 全 region を同時に提示する。
 *
 * @param template 確定部 + region プレースホルダで構成したテンプレート文字列（例: "勉強して{r0}を{r1}"）。
 * @param prefixContext locked prefix の表層文字列。lock なしの場合は空文字。
 * @param regions region の一覧。空の場合は LLM を呼ばず baseline をそのまま採用する。
 */
internal data class FactorizedRerankRequest(
    /** 確定部 + {rN} プレースホルダのテンプレート文字列（例: "勉強して{r0}を{r1}"）。 */
    val template: String,
    /** locked prefix の表層文字列（lock なしは空文字）。 */
    val prefixContext: String,
    /** region の一覧（空なら LLM 呼び出し不要）。 */
    val regions: List<DecisionRegion>,
)

/**
 * LLM の region ごとの表層提案（closed-set index ではなく自由表層）。
 *
 * [decisions] は regionId → 提案 surface。pack（hint）の option に限定されず、
 * 格子内の任意の表層を提案できる。空の場合は LLM 失敗・parse 失敗・API key 未設定を示す。
 * resolver が各 surface を full lattice で検証し、合法なら採用・非合法は baseline へ fallback する。
 *
 * @param decisions regionId → 提案 surface（parse 失敗時は空）。
 */
internal data class FactorizedRerankResult(
    /** regionId → 提案 surface（parse 失敗時は空）。 */
    val decisions: Map<String, String>,
)
