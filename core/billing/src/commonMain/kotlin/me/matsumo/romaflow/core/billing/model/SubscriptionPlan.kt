package me.matsumo.romaflow.core.billing.model

import kotlinx.serialization.Serializable

/**
 * サブスクリプションプランの種類を表す列挙型。
 * 月額・年額のサブスクと、買い切りのライフタイムプランを含む。
 * Android (Google Play) と iOS (App Store) で製品IDの形式が異なるため、両方を保持する。
 */
@Serializable
enum class SubscriptionPlan(
    val androidProductId: String,
    val iosProductId: String,
) {
    MONTHLY(
        androidProductId = "romaflow_pro:monthly",
        iosProductId = "romaflow_pro_monthly",
    ),
    YEARLY(
        androidProductId = "romaflow_pro:yearly",
        iosProductId = "romaflow_pro_yearly",
    ),
    LIFETIME(
        androidProductId = "romaflow_pro_lifetime",
        iosProductId = "romaflow_pro_lifetime",
    ),
    ;

    companion object {
        const val ENTITLEMENT_PRO = "RomaFlow Pro"

        fun fromProductId(productId: String): SubscriptionPlan? {
            return entries.find { it.androidProductId == productId || it.iosProductId == productId }
        }
    }
}
