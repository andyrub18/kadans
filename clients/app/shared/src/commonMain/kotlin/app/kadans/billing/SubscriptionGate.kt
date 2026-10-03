package app.kadans.billing

import app.kadans.api.KadansApi
import app.kadans.api.KadansApiException
import app.kadans.api.model.DevicePlatform
import app.kadans.api.model.SubscriptionStatusResponse
import app.kadans.config.devicePlatform
import kotlin.coroutines.cancellation.CancellationException

/**
 * Before Home on a phone: is this account paid up? The server answers; when it says no, the store's own purchases are
 * handed to it again first (a new phone, a reinstall, a purchase whose link failed: the server checks each one).
 * Desktop never asks: it is free. Nor does a phone with no store to buy from in the app (the iPhone, until its StoreKit
 * step): a paywall without a way to pay would only lock people out. Unreachable server: no paywall either, because a
 * network hiccup must not lock anyone out and what the subscription pays for on the server (reminders on the phone)
 * is gated there anyway.
 */
class SubscriptionGate(
    private val api: KadansApi,
    private val store: StoreBilling,
    private val isPhone: () -> Boolean = { devicePlatform() in setOf(DevicePlatform.Android, DevicePlatform.Ios) },
) {
    /** Only phones are sold a subscription, and only where the app can sell it. */
    val appliesHere: Boolean get() = isPhone() && store.isSupported

    suspend fun needsPaywall(): Boolean {
        if (!appliesHere) return false
        val status = try {
            api.billing.status()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        if (!blocks(status)) return false
        val restored = try {
            restore(status)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            status
        }
        return blocks(restored)
    }

    /**
     * The store's purchases, linked again; the server checks each one. A purchase it refuses (another account's) is
     * skipped; when nothing gave access and one was refused, that refusal is what the person needs to read.
     */
    suspend fun restore(status: SubscriptionStatusResponse): SubscriptionStatusResponse {
        if (!store.isSupported || status.googleProductId.isBlank()) return status
        var latest = status
        var refused: KadansApiException? = null
        for (token in store.ownedPurchaseTokens(status.googleProductId)) {
            try {
                latest = api.billing.linkGoogle(token)
            } catch (e: KadansApiException) {
                refused = e
            }
        }
        if (blocks(latest)) refused?.let { throw it }
        return latest
    }

    companion object {
        fun blocks(status: SubscriptionStatusResponse): Boolean = status.required && !status.hasAccess
    }
}
