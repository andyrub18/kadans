@file:UseSerializers(IsoInstantSerializer::class)

package app.kadans.api.model

import app.kadans.api.IsoInstantSerializer
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
enum class SubscriptionState { Pending, Trial, Active, GracePeriod, OnHold, Paused, Canceled, Expired, Revoked }

@Serializable
enum class BillingStore { Google, Apple, Fake }

/**
 * What the app reads before a paywall: whether phones need a subscription ([required]), whether this account is paid
 * up ([hasAccess]), and what a purchase must carry ([accountHash], [googleProductId]).
 */
@Serializable
data class SubscriptionStatusResponse(
    val required: Boolean = false,
    val hasAccess: Boolean = true,
    val state: SubscriptionState? = null,
    val store: BillingStore? = null,
    val expiresAt: Instant? = null,
    val autoRenewing: Boolean = false,
    val accountHash: String = "",
    val googleProductId: String = "",
    val fakeStore: Boolean = false,
)

@Serializable
data class GooglePurchaseRequest(val purchaseToken: String)

/** Development only (the server's fake store). */
@Serializable
data class FakePurchaseRequest(val state: SubscriptionState = SubscriptionState.Trial, val days: Int = 14)
