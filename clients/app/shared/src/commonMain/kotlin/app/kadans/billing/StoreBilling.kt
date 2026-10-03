package app.kadans.billing

/** The subscription as the store sells it here: in the buyer's currency, with the trial they are offered. */
data class StoreProduct(
    /** "$0.99", "HTG 130,00": the store's own formatting. */
    val monthlyPrice: String,
    /** Free days before the first payment; null when this account already had its trial. */
    val trialDays: Int?,
)

sealed interface PurchaseOutcome {
    /** Paid (or the trial started): the token goes to the server, which checks it with the store. */
    data class Purchased(val token: String) : PurchaseOutcome

    /**
     * Bought with cash or a bank transfer that has not cleared yet. The token goes to the server too: it waits there,
     * and Google's notification that the payment cleared finds it.
     */
    data class Pending(val token: String) : PurchaseOutcome

    /** This store account already holds the subscription: restore it instead. */
    data object AlreadyOwned : PurchaseOutcome

    data object Cancelled : PurchaseOutcome

    data class Failed(val reason: String?) : PurchaseOutcome
}

/**
 * The phone's app store, as far as Kadans uses it: show the price, sell the subscription, and find purchases this
 * store account already made (a new phone, a reinstall). Google Play on Android; none on desktop, which is free;
 * Apple comes with the iPhone app.
 */
interface StoreBilling {
    /** False where there is no store to buy from (desktop, or a phone without Google Play). */
    val isSupported: Boolean

    suspend fun product(productId: String): StoreProduct?

    /** [accountHash] goes into the purchase, so it can only ever count for this Kadans account. */
    suspend fun purchase(productId: String, accountHash: String): PurchaseOutcome

    /** Purchases of [productId] this store account holds (paid or pending), for the server to check and link again. */
    suspend fun ownedPurchaseTokens(productId: String): List<String>

    /** The store's page where the subscription is cancelled or its payment fixed. */
    fun manageUrl(productId: String): String?
}

/** Desktop (free) and, until the iPhone app's StoreKit, iOS. */
object NoStoreBilling : StoreBilling {
    override val isSupported = false
    override suspend fun product(productId: String): StoreProduct? = null
    override suspend fun purchase(productId: String, accountHash: String): PurchaseOutcome = PurchaseOutcome.Failed("No store on this device")
    override suspend fun ownedPurchaseTokens(productId: String): List<String> = emptyList()
    override fun manageUrl(productId: String): String? = null
}

expect fun platformStoreBilling(): StoreBilling

/** A store's ISO 8601 period in days, as Google Play writes a trial: P14D, P2W, P1M (a month counts 30). */
internal fun isoPeriodDays(period: String): Int? {
    val match = Regex("""P(\d+)([DWM])""").matchEntire(period) ?: return null
    val n = match.groupValues[1].toInt()
    return when (match.groupValues[2]) {
        "D" -> n
        "W" -> n * 7
        else -> n * 30
    }
}
