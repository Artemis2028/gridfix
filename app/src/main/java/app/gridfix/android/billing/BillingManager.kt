package app.gridfix.android.billing

import android.app.Activity
import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.android.billingclient.api.AcknowledgePurchaseParams
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.billingStore by preferencesDataStore(
    name = "billing",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * MGRS GPS Pro subscription state via Google Play Billing.
 *
 * Entitlement model: an active (purchased) subscription unlocks the app. The
 * last known result is cached in DataStore so a subscriber who opens the app
 * offline — days into a field problem — is never locked out; the cache is
 * corrected the next time Play answers. Free trials are configured on the
 * products in Play Console, not here: eligible users simply see an offer
 * whose first pricing phase is free.
 *
 * Robustness rules: the startup check is time-bounded (a Play Store that never
 * answers falls back to the cache instead of a spinner forever), every Play
 * error path lands on the cache, only one BillingClient exists at a time, and
 * every user action that can fail produces a readable notice.
 */
class BillingManager(private val context: Context) : PurchasesUpdatedListener {

    enum class State { CHECKING, ENTITLED, LOCKED }

    enum class PlansStatus { LOADING, LOADED, EMPTY, ERROR }

    data class Plan(
        val productId: String,
        val title: String,          // "Monthly" / "Annual"
        val price: String,          // formatted recurring price, e.g. "$2.99"
        val period: String,         // "month" / "year" / "week"
        val priceMicros: Long,      // recurring price in micros, for savings math
        val trialDays: Int,         // 0 when the user has no free-trial offer
        val offerToken: String,
        val details: ProductDetails,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val stateFlow = MutableStateFlow(State.CHECKING)
    val state: StateFlow<State> = stateFlow
    private val plansFlow = MutableStateFlow<List<Plan>>(emptyList())
    val plans: StateFlow<List<Plan>> = plansFlow
    private val plansStatusFlow = MutableStateFlow(PlansStatus.LOADING)
    val plansStatus: StateFlow<PlansStatus> = plansStatusFlow
    private val noticeFlow = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = noticeFlow

    private var client: BillingClient? = null
    private var connecting = false
    private var userRestore = false

    companion object {
        const val MONTHLY = "gridfix_pro_monthly"
        const val ANNUAL = "gridfix_pro_annual"
        const val MANAGE_URL =
            "https://play.google.com/store/account/subscriptions?package=app.gridfix.android"
        private val ENTITLED_KEY = booleanPreferencesKey("entitled")
        private val CONFIRMED_KEY = longPreferencesKey("entitled_confirmed_at")
        private val EMPTY_STREAK_KEY = intPreferencesKey("entitled_empty_streak")
        private const val STARTUP_TIMEOUT_MS = 10_000L

        /**
         * How long a confirmed subscription is honoured after Play last said yes.
         * Long enough to cover a rotation into the field with no signal, or a Play
         * outage; short enough that a genuine cancellation takes effect.
         */
        private const val ENTITLEMENT_GRACE_MS = 14L * 24 * 60 * 60 * 1000
    }

    private suspend fun cachedEntitled(): Boolean =
        runCatching { context.billingStore.data.first()[ENTITLED_KEY] ?: false }.getOrDefault(false)

    private suspend fun confirmedAt(): Long =
        runCatching { context.billingStore.data.first()[CONFIRMED_KEY] ?: 0L }.getOrDefault(0L)

    private suspend fun emptyStreak(): Int =
        runCatching { context.billingStore.data.first()[EMPTY_STREAK_KEY] ?: 0 }.getOrDefault(0)

    fun start() {
        scope.launch {
            // Seed from cache first so a known subscriber is unlocked instantly,
            // network or not; Play's answer then confirms or corrects it.
            if (cachedEntitled() && stateFlow.value == State.CHECKING) {
                stateFlow.value = State.ENTITLED
            }
            connect()
            delay(STARTUP_TIMEOUT_MS)
            if (stateFlow.value == State.CHECKING) fallBackToCache()
        }
    }

    fun close() {
        client?.endConnection()
        client = null
        connecting = false
    }

    /** Re-check purchases when the app comes back to the foreground. */
    fun refresh() {
        val c = client
        if (c != null && c.isReady) refreshPurchases() else if (!connecting) connect()
    }

    /** Paywall "Restore purchases" / retry: reconnect if needed, re-query everything. */
    fun restore() {
        noticeFlow.value = null
        userRestore = true
        plansStatusFlow.value = PlansStatus.LOADING
        val c = client
        if (c != null && c.isReady) {
            refreshPurchases()
            queryPlans()
        } else {
            connect()
        }
    }

    private fun connect() {
        if (connecting) return
        client?.let { old ->
            if (old.isReady) {
                refreshPurchases()
                queryPlans()
                return
            }
            old.endConnection()
        }
        connecting = true
        val c = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            // Billing 8+ reconnects with its own backoff; without this the app races it
            // and testers see "Google Play disconnected" far more often than they should.
            .enableAutoServiceReconnection()
            .build()
        client = c
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (c !== client) return   // superseded by a newer client
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    refreshPurchases()
                    queryPlans()
                } else {
                    plansStatusFlow.value = PlansStatus.ERROR
                    fallBackToCache(describe(result))
                }
            }

            override fun onBillingServiceDisconnected() {
                connecting = false
                if (c !== client) return
                if (stateFlow.value == State.CHECKING) fallBackToCache(null)
                if (plansStatusFlow.value == PlansStatus.LOADING) {
                    plansStatusFlow.value = PlansStatus.ERROR
                    noticeFlow.value = "Google Play disconnected — tap Retry"
                }
            }
        })
        armPlansTimeout()
    }

    /** A plan query that never answers must not leave the paywall spinning. */
    /** Bumped by every query; a timeout only fires for the query that armed it. */
    private var plansGeneration = 0

    private fun armPlansTimeout() {
        val generation = ++plansGeneration
        scope.launch {
            delay(STARTUP_TIMEOUT_MS)
            // A later query may already have answered; only the newest timer may speak.
            if (generation == plansGeneration && plansStatusFlow.value == PlansStatus.LOADING) {
                plansStatusFlow.value = PlansStatus.ERROR
                if (noticeFlow.value == null) {
                    noticeFlow.value = "Google Play is not answering — check your connection and tap Retry"
                }
            }
        }
    }

    private fun refreshPurchases() {
        val c = client ?: return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        c.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                applyPurchases(purchases)
            } else {
                fallBackToCache(describe(result))
            }
        }
    }

    private fun applyPurchases(purchases: List<Purchase>) {
        val active = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        // Google refunds unacknowledged purchases after 3 days — acknowledge promptly.
        active.filter { !it.isAcknowledged }.forEach { p ->
            val c = client ?: return@forEach
            val ack = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(p.purchaseToken)
                .build()
            // Google refunds a purchase that is never acknowledged, so a failure here
            // has to be visible and retried on the next refresh, not swallowed.
            c.acknowledgePurchase(ack) { r ->
                if (r.responseCode != BillingClient.BillingResponseCode.OK) {
                    noticeFlow.value =
                        "Could not confirm the purchase with Google Play \u2014 reopen the app while online"
                }
            }
        }
        val entitled = active.isNotEmpty()
        val pending = purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        val fromRestore = userRestore
        userRestore = false
        val now = System.currentTimeMillis()
        scope.launch {
            // Play answering "no purchases" is not always the truth: the wrong Google
            // account can be signed in, a work profile can be active, an account can be
            // briefly deauthorised. Locking a paying subscriber out of a navigation app
            // over one such answer is the worst thing this class can do.
            //
            // The policy, exactly: a previously-entitled user keeps access while EITHER
            // this is the first empty answer in a row, OR the last confirmed purchase is
            // inside the grace window. So a spurious empty is always absorbed, and a
            // genuine cancellation costs one more session before the app locks - two
            // empty answers past the grace window locks it for good. That one session is
            // the price of never locking out someone who actually paid.
            val wasEntitled = cachedEntitled()
            val streak = if (entitled) 0 else emptyStreak() + 1
            val withinGrace = now - confirmedAt() < ENTITLEMENT_GRACE_MS
            val keepOnTrust = !entitled && wasEntitled && (streak < 2 || withinGrace)
            runCatching {
                context.billingStore.edit {
                    it[ENTITLED_KEY] = entitled || keepOnTrust
                    it[EMPTY_STREAK_KEY] = streak
                    if (entitled) it[CONFIRMED_KEY] = now
                }
            }
            stateFlow.value = if (entitled || keepOnTrust) State.ENTITLED else State.LOCKED
            noticeFlow.value = when {
                entitled -> null
                keepOnTrust -> "Google Play did not report your subscription \u2014 access continues for now. Open the app with a signal to confirm it."
                pending -> "Purchase pending \u2014 finish payment, then tap Restore purchases"
                fromRestore -> "No active subscription found for this Google account"
                else -> null
            }
        }
    }

    private fun fallBackToCache(reason: String? = null) {
        scope.launch {
            val cached = cachedEntitled()
            stateFlow.value = if (cached) State.ENTITLED else State.LOCKED
            if (!cached) {
                noticeFlow.value = reason ?: "Google Play billing is not reachable right now"
            }
        }
    }

    private fun queryPlans() {
        val c = client ?: return
        plansStatusFlow.value = PlansStatus.LOADING
        armPlansTimeout()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(MONTHLY, ANNUAL).map { id ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                }
            )
            .build()
        c.queryProductDetailsAsync(params) { result, detailsResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                plansStatusFlow.value = PlansStatus.ERROR
                noticeFlow.value = "Google Play could not load the plans — " + describe(result)
                return@queryProductDetailsAsync
            }
            val list = detailsResult.productDetailsList.mapNotNull { toPlan(it) }
            plansFlow.value = list.sortedBy { if (it.productId == MONTHLY) 0 else 1 }
            if (list.isEmpty()) {
                plansStatusFlow.value = PlansStatus.EMPTY
                noticeFlow.value = "No plans are available for this install. Install MGRS GPS " +
                    "from the Play Store and make sure you are signed in to Google Play."
            } else {
                plansStatusFlow.value = PlansStatus.LOADED
            }
        }
    }

    /**
     * Pick the offer to sell: prefer one containing a free phase (the trial —
     * Play only returns offers this user is eligible for), else the base plan.
     * The recurring price is the last non-free pricing phase.
     */
    private fun toPlan(pd: ProductDetails): Plan? {
        val offers = pd.subscriptionOfferDetails ?: return null
        if (offers.isEmpty()) return null
        val chosen = offers.firstOrNull { o ->
            o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
        } ?: offers.first()
        val phases = chosen.pricingPhases.pricingPhaseList
        val paid = phases.lastOrNull { it.priceAmountMicros > 0L } ?: return null
        val trial = phases.firstOrNull { it.priceAmountMicros == 0L }
        val iso = paid.billingPeriod
        val count = iso.filter { it.isDigit() }.toIntOrNull() ?: 1
        val period = when {
            iso.endsWith("Y") -> "year"
            iso.endsWith("W") -> "week"
            else -> "month"
        }
        val title = when {
            period == "year" && count == 1 -> "Annual"
            period == "month" && count == 1 -> "Monthly"
            period == "week" && count == 1 -> "Weekly"
            else -> "Every $count ${period}s"
        }
        return Plan(
            productId = pd.productId,
            title = title,
            price = paid.formattedPrice,
            period = if (count == 1) period else "$count ${period}s",
            priceMicros = paid.priceAmountMicros,
            trialDays = trial?.let { periodDays(it.billingPeriod) } ?: 0,
            offerToken = chosen.offerToken,
            details = pd,
        )
    }

    private fun periodDays(iso: String): Int {
        val n = iso.filter { it.isDigit() }.toIntOrNull() ?: 0
        return when {
            iso.endsWith("W") -> n * 7
            iso.endsWith("M") -> n * 30
            iso.endsWith("Y") -> n * 365
            else -> n
        }
    }

    fun launchPurchase(activity: Activity, plan: Plan) {
        noticeFlow.value = null
        val c = client
        if (c == null || !c.isReady) {
            noticeFlow.value = "Reconnecting to Google Play — try again in a moment"
            connect()
            return
        }
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(plan.details)
                        .setOfferToken(plan.offerToken)
                        .build()
                )
            )
            .build()
        val r = c.launchBillingFlow(activity, flow)
        if (r.responseCode != BillingClient.BillingResponseCode.OK) {
            noticeFlow.value = "Could not open Google Play checkout — " + describe(r)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases.isNullOrEmpty()) refreshPurchases() else applyPurchases(purchases)
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refreshPurchases()
            else -> noticeFlow.value = "Purchase did not complete — " + describe(result)
        }
    }

    /** Human wording for the Play response codes a user can actually act on. */
    private fun describe(r: BillingResult): String = when (r.responseCode) {
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
            "sign in to Google Play on this device and try again"
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.NETWORK_ERROR ->
            "no connection to Google Play (check your network)"
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED ->
            "Google Play disconnected, tap Retry"
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
            "this plan is not available in your country yet"
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED ->
            "this device's Play Store does not support subscriptions"
        BillingClient.BillingResponseCode.DEVELOPER_ERROR ->
            "configuration problem (Play error ${r.responseCode})"
        else -> "Play error ${r.responseCode}"
    }
}
