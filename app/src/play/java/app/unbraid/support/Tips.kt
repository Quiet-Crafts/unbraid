package app.unbraid.support

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
import com.android.billingclient.api.BillingClient.BillingResponseCode.OK
import com.android.billingclient.api.BillingClient.ProductType.INAPP
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Google Play build: tips through Play Billing, the only way Play allows an app to take money. */
object Tips {
    fun jar(ctx: Context): TipJar = PlayTipJar(ctx.applicationContext)
    /** Play allows no payment link besides Play Billing, so this build carries none. */
    val sponsors: String? = null
}

/**
 * Tips are consumable one-time products: each purchase is consumed right away so the same tip can be given again,
 * and nothing is unlocked. The client is built when the interface starts; it connects, checks open purchases and loads
 * prices ([refresh]) when About & support opens or comes back, and during a purchase. It stays connected until the
 * interface is cleared ([close]), and Google's library may do work of its own meanwhile.
 * Purchases that completed while the app was away (pending payments, an interrupted flow) are consumed the next time
 * that screen opens; unconsumed ones Play refunds after three days.
 */
private class PlayTipJar(ctx: Context) : TipJar, PurchasesUpdatedListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val details = HashMap<String, ProductDetails>()
    override val tips = MutableStateFlow<List<Tip>>(emptyList())
    override val thanked = MutableStateFlow(false)
    private val client = BillingClient.newBuilder(ctx).setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private var connecting = false

    private suspend fun load() {
        val products = TIP_IDS.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(INAPP).build() }
        val found = client.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(products).build())
            .productDetailsList.orEmpty().filter { it.oneTimePurchaseOfferDetails != null }
        details.clear()
        found.forEach { details[it.productId] = it }
        tips.value = found.sortedBy { it.oneTimePurchaseOfferDetails!!.priceAmountMicros }
            .map { Tip(it.productId, it.oneTimePurchaseOfferDetails!!.formattedPrice) }
    }

    /** Connects on first use; then products if not loaded yet, and any purchase left unconsumed (a pending payment, a failed consume). */
    override fun refresh() {
        if (client.isReady) scope.launch { if (details.isEmpty()) load(); settle() }
        else if (!connecting) {
            connecting = true
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(r: BillingResult) { connecting = false; if (r.responseCode == OK) refresh() }
                override fun onBillingServiceDisconnected() = Unit // reconnects by itself on the next call
            })
        }
    }

    private suspend fun settle() {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(INAPP).build()).purchasesList.forEach { consume(it) }
    }

    /** Only completed payments are consumed; a pending one is left to Play and settled when it completes. */
    private suspend fun consume(p: Purchase) {
        if (p.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val r = client.consumePurchase(ConsumeParams.newBuilder().setPurchaseToken(p.purchaseToken).build())
        if (r.billingResult.responseCode == OK) thanked.value = true
    }

    override fun buy(activity: Activity, id: String) {
        val d = details[id] ?: return
        val product = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()
        val r = client.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(product)).build())
        // An earlier tip of this size is still unconsumed: Play refuses the new one until it is.
        if (r.responseCode == ITEM_ALREADY_OWNED) refresh()
    }

    override fun onPurchasesUpdated(r: BillingResult, purchases: MutableList<Purchase>?) {
        when (r.responseCode) {
            OK -> purchases?.forEach { scope.launch { consume(it) } }
            ITEM_ALREADY_OWNED -> refresh()
        }
    }

    override fun close() {
        scope.cancel()
        client.endConnection()
    }
}
