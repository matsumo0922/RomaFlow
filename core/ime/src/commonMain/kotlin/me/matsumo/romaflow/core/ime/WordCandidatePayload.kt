package me.matsumo.romaflow.core.ime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * call2 の出力 JSON `{"candidates":[...]}` に対応する payload。
 *
 * provider 実装が候補列をシリアライズし、engine 側が [kotlinx.serialization] でパースするための共有型。
 * モデル出力の余分なキーを許容できるよう、デコード側は `ignoreUnknownKeys` を有効にして読む。
 */
@Serializable
internal data class WordCandidatePayload(
    @SerialName("candidates")
    val candidates: List<String>,
)
