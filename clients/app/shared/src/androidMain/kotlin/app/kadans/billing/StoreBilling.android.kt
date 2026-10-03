package app.kadans.billing

import app.kadans.auth.AndroidActivityHolder
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

actual fun platformStoreBilling(): StoreBilling = GooglePlayBilling

/**
 * Google Play Billing. The purchase is not acknowledged here: the server does it once it has checked the purchase
 * with Google (an unacknowledged purchase is refunded after 3 days, so a purchase that never reaches the server is
 * not kept). The purchase carries the account's hash ([BillingFlowParams.Builder.setObfuscatedAccountId]).
 */
private object GooglePlayBilling : StoreBilling {
    private var client: BillingClient? = null
    private var waiting: CompletableDeferred<PurchaseOutcome>? = null
    private val connecting = Mutex()

    override val isSupported = true

    private val listener = PurchasesUpdatedListener { result, purchases ->
        val outcome = when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val purchase = purchases?.firstOrNull()
                when {
                    purchase == null -> PurchaseOutcome.Failed(null)
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED -> PurchaseOutcome.Purchased(purchase.purchaseToken)
                    else -> PurchaseOutcome.Pending(purchase.purchaseToken)
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseOutcome.Cancelled
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseOutcome.AlreadyOwned
            else -> PurchaseOutcome.Failed(result.describe())
        }
        waiting?.complete(outcome)
        waiting = null
    }

    override suspend fun product(productId: String): StoreProduct? {
        val details = details(productId) ?: return null
        val offer = bestOffer(details) ?: return null
        val phases = offer.pricingPhases.pricingPhaseList
        val recurring = phases.lastOrNull() ?: return null
        val free = phases.firstOrNull { it.priceAmountMicros == 0L }
        return StoreProduct(recurring.formattedPrice, free?.billingPeriod?.let(::isoPeriodDays))
    }

    override suspend fun purchase(productId: String, accountHash: String): PurchaseOutcome {
        val client = connected() ?: return PurchaseOutcome.Failed("Google Play is not available on this phone")
        val activity = AndroidActivityHolder.get() ?: return PurchaseOutcome.Failed("No foreground activity")
        val details = details(productId) ?: return PurchaseOutcome.Failed("The subscription is not on sale")
        val offer = bestOffer(details) ?: return PurchaseOutcome.Failed("The subscription is not on sale")

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).setOfferToken(offer.offerToken).build())
            )
            .setObfuscatedAccountId(accountHash)
            .build()
        val outcome = CompletableDeferred<PurchaseOutcome>().also { waiting = it }
        val launched = withContext(Dispatchers.Main) { client.launchBillingFlow(activity, params) }
        if (launched.responseCode != BillingClient.BillingResponseCode.OK) {
            waiting = null
            return when (launched.responseCode) {
                BillingClient.BillingResponseCode.USER_CANCELED -> PurchaseOutcome.Cancelled
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseOutcome.AlreadyOwned
                else -> PurchaseOutcome.Failed(launched.describe())
            }
        }
        return outcome.await()
    }

    override suspend fun ownedPurchaseTokens(productId: String): List<String> {
        val client = connected() ?: return emptyList()
        val result = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
        return result.purchasesList
            .filter { productId in it.products && it.purchaseState != Purchase.PurchaseState.UNSPECIFIED_STATE }
            .map { it.purchaseToken }
    }

    override fun manageUrl(productId: String): String? {
        val packageName = AndroidActivityHolder.get()?.packageName ?: return null
        return "https://play.google.com/store/account/subscriptions?sku=$productId&package=$packageName"
    }

    private suspend fun details(productId: String): ProductDetails? {
        val client = connected() ?: return null
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()
        return client.queryProductDetails(params).productDetailsList?.firstOrNull()
    }

    /** The trial when this Google account may still have it (Google only lists offers it is eligible for), else the plain plan. */
    private fun bestOffer(details: ProductDetails): ProductDetails.SubscriptionOfferDetails? {
        val offers = details.subscriptionOfferDetails ?: return null
        return offers.firstOrNull { offer -> offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } }
            ?: offers.firstOrNull { it.offerId == null }
            ?: offers.firstOrNull()
    }

    private suspend fun connected(): BillingClient? = connecting.withLock {
        client?.takeIf { it.isReady }?.let { return it }
        val context = AndroidActivityHolder.get()?.applicationContext ?: return null
        val billing = client ?: BillingClient.newBuilder(context)
            .setListener(listener)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
            .also { client = it }

        val ready = CompletableDeferred<Boolean>()
        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                ready.complete(result.responseCode == BillingClient.BillingResponseCode.OK)
            }

            override fun onBillingServiceDisconnected() {
                ready.complete(false)
            }
        })
        if (ready.await()) billing else null
    }

    private fun BillingResult.describe(): String = debugMessage.ifBlank { "Google Play answered $responseCode" }
}
