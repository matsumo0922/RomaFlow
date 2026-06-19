package me.matsumo.romaflow.core.ime.shadow

import me.matsumo.romaflow.core.morphology.LexemeEntry

/**
 * shadow パッケージ内 resolver 間で共有するヘルパー関数群。
 *
 * - [buildSurface]: lexeme 経路の surface を連結して表示文字列を作る。
 * - [extractFirstCandidateFromJson]: `{"candidates":["候補1","候補2",...]}` 形式の JSON から先頭候補を取り出す。
 *
 * これらは [FakeDeterministicResolver] / [TwoStepToolLoopResolver] / [ProposalApplier] で
 * 重複実装されていたため、top-level の internal 関数として統一する。
 * [CandidateSession] の既存 buildSurface（private）とは独立した実装であり、挙動は同一・変更なし。
 */

/**
 * lexeme 経路の surface を連結して表示文字列を作る。
 *
 * 候補窓表示・格子上の preferredSurface 検索・graph 照合に使う共通実装。
 */
internal fun buildSurface(path: List<LexemeEntry>): String {
    val builder = StringBuilder()

    for (lexeme in path) {
        builder.append(lexeme.surface)
    }

    return builder.toString()
}

/**
 * `{"candidates":["候補1","候補2",...]}` 形式の JSON から先頭候補を取り出す。
 *
 * 失敗（parse エラー・空配列）の場合は null を返す。
 * JSON ライブラリへの依存を避けるため、簡易な正規表現ベースの抽出を行う。
 * 生産品質では kotlinx.serialization を使うべきだが、A-4 の目的は戦略比較であり、
 * JSON parse の完全正確性は A-5 以降で整備する（§7 割り切り）。
 */
internal fun extractFirstCandidateFromJson(jsonText: String): String? {
    val candidatesPattern = Regex(""""candidates"\s*:\s*\[\s*"([^"]+)"""")
    val matchResult = candidatesPattern.find(jsonText)

    return matchResult?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
}
