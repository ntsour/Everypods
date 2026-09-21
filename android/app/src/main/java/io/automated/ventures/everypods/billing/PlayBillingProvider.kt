/*
    EveryPods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 EveryPods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package io.automated.ventures.everypods.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import io.automated.ventures.everypods.R

/**
 * Play Billing tip jar — consumable one-time SKUs only.
 * Features stay unlocked ([isPremium] is always true).
 */
class PlayBillingProvider(private val context: Context) : BillingProvider, PurchasesUpdatedListener {

    private val _isPremium = MutableStateFlow(true)
    override val isPremium: StateFlow<Boolean> = _isPremium

    private val _price = MutableStateFlow("")
    override val price: StateFlow<String> = _price

    private val _tipProducts = MutableStateFlow<List<TipProduct>>(emptyList())
    override val tipProducts: StateFlow<List<TipProduct>> = _tipProducts

    private val _tipPurchaseEvent = MutableStateFlow(TipPurchaseEvent())
    override val tipPurchaseEvent: StateFlow<TipPurchaseEvent> = _tipPurchaseEvent

    private val _billingAvailable = MutableStateFlow(false)
    override val billingAvailable: StateFlow<Boolean> = _billingAvailable

    private val _tipProductsLoaded = MutableStateFlow(false)
    override val tipProductsLoaded: StateFlow<Boolean> = _tipProductsLoaded

    private val productDetailsById = mutableMapOf<String, ProductDetails>()
    private val offerTokenById = mutableMapOf<String, String>()

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    init {
        startConnection()
    }

    private fun startConnection() {
        if (billingClient.isReady) {
            queryProductDetails()
            queryPurchasesInternal()
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                Log.i(
                    TAG,
                    "Billing setup finished responseCode=${billingResult.responseCode} " +
                        "(${responseCodeName(billingResult.responseCode)}) " +
                        "debug=${billingResult.debugMessage}",
                )
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    _billingAvailable.value = true
                    // Keep tipProductsLoaded=false until queryProductDetails completes.
                    queryProductDetails()
                    queryPurchasesInternal()
                } else {
                    Log.w(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                    _billingAvailable.value = false
                    _tipProducts.value = emptyList()
                    _tipProductsLoaded.value = true
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected")
                _billingAvailable.value = false
                // Soft reconnect on next tip / queryPurchases
            }
        })
    }

    private fun queryProductDetails() {
        if (!billingClient.isReady) {
            Log.w(TAG, "queryProductDetails skipped: BillingClient not ready")
            return
        }

        val productList = TipSkus.ALL.map { sku ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(sku)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        Log.i(TAG, "queryProductDetails requesting SKUs=${TipSkus.ALL}")
        billingClient.queryProductDetailsAsync(params) { billingResult, result ->
            val code = billingResult.responseCode
            Log.i(
                TAG,
                "queryProductDetails responseCode=$code (${responseCodeName(code)}) " +
                    "debug=${billingResult.debugMessage}",
            )

            if (code != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "queryProductDetails failed; clearing tip products")
                productDetailsById.clear()
                offerTokenById.clear()
                _tipProducts.value = emptyList()
                _price.value = ""
                // Billing client may still be up, but tips are not usable — exit loading.
                _tipProductsLoaded.value = true
                return@queryProductDetailsAsync
            }

            val productDetailsList = result.productDetailsList
            val unfetched = result.unfetchedProductList
            Log.i(
                TAG,
                "queryProductDetails productCount=${productDetailsList.size} " +
                    "unfetchedCount=${unfetched.size}",
            )
            if (unfetched.isNotEmpty()) {
                Log.w(
                    TAG,
                    "queryProductDetails unfetched=" +
                        unfetched.joinToString { "${it.productId}(status=${it.statusCode})" },
                )
            }

            val returnedIds = productDetailsList.map { it.productId }.toSet()
            val missingSkus = TipSkus.ALL.filter { it !in returnedIds }
            if (missingSkus.isNotEmpty()) {
                Log.w(TAG, "queryProductDetails missing SKU IDs: $missingSkus")
            }

            productDetailsById.clear()
            offerTokenById.clear()
            val tips = productDetailsList
                .sortedBy { details ->
                    val idx = TipSkus.ALL.indexOf(details.productId)
                    if (idx < 0) Int.MAX_VALUE else idx
                }
                .mapNotNull { details ->
                    val offer = details.oneTimePurchaseOfferDetails
                        ?: details.oneTimePurchaseOfferDetailsList?.firstOrNull()
                        ?: return@mapNotNull null
                    productDetailsById[details.productId] = details
                    offer.offerToken?.let { token -> offerTokenById[details.productId] = token }
                    TipProduct(
                        productId = details.productId,
                        title = TipSkus.fallbackTitle(details.productId)
                            .ifBlank { details.title },
                        description = details.description.orEmpty(),
                        formattedPrice = offer.formattedPrice,
                    )
                }
            _tipProducts.value = tips
            _price.value = tips.firstOrNull()?.formattedPrice.orEmpty()
            _tipProductsLoaded.value = true
            if (tips.isEmpty()) {
                // Setup succeeded but Play returned no usable tip SKUs (common on
                // sideloaded playDebug before Active consumables exist / Play install).
                Log.w(
                    TAG,
                    "queryProductDetails returned 0 tip products — treating tips as unavailable",
                )
            } else {
                Log.i(TAG, "queryProductDetails loaded tips=${tips.map { it.productId }}")
            }
        }
    }

    override fun tip(activity: Activity, productId: String) {
        if (!billingClient.isReady) {
            startConnection()
            _tipPurchaseEvent.value = TipPurchaseEvent(
                status = TipPurchaseStatus.Unavailable,
                productId = productId,
                message = context.getString(R.string.tip_billing_unavailable),
            )
            return
        }

        val details = productDetailsById[productId]
        if (details == null) {
            queryProductDetails()
            _tipPurchaseEvent.value = TipPurchaseEvent(
                status = TipPurchaseStatus.Unavailable,
                productId = productId,
                message = context.getString(R.string.tip_billing_unavailable),
            )
            return
        }

        _tipPurchaseEvent.value = TipPurchaseEvent(
            status = TipPurchaseStatus.Pending,
            productId = productId,
        )

        val productParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
        offerTokenById[productId]?.let { productParamsBuilder.setOfferToken(it) }
        val productParams = productParamsBuilder.build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()

        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _tipPurchaseEvent.value = TipPurchaseEvent(
                status = TipPurchaseStatus.Error,
                productId = productId,
                message = result.debugMessage.ifBlank {
                    context.getString(R.string.tip_purchase_error)
                },
            )
        }
    }

    override fun purchase(activity: Activity) {
        val first = _tipProducts.value.firstOrNull()?.productId ?: TipSkus.COFFEE
        tip(activity, first)
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases.isNullOrEmpty()) return
                purchases.forEach { handlePurchase(it) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _tipPurchaseEvent.value = TipPurchaseEvent(
                    status = TipPurchaseStatus.Cancelled,
                    productId = _tipPurchaseEvent.value.productId,
                )
            }
            else -> {
                _tipPurchaseEvent.value = TipPurchaseEvent(
                    status = TipPurchaseStatus.Error,
                    productId = _tipPurchaseEvent.value.productId,
                    message = billingResult.debugMessage.ifBlank {
                        context.getString(R.string.tip_purchase_error)
                    },
                )
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        // Consumables: always consume so the same tip tier can be bought again.
        val token = purchase.purchaseToken
        val productId = purchase.products.firstOrNull()
        val consumeParams = ConsumeParams.newBuilder()
            .setPurchaseToken(token)
            .build()

        billingClient.consumeAsync(consumeParams) { result, _ ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                _tipPurchaseEvent.value = TipPurchaseEvent(
                    status = TipPurchaseStatus.Success,
                    productId = productId,
                )
            } else {
                Log.w(TAG, "consume failed: ${result.debugMessage}")
                // Still thank the user — purchase succeeded even if consume retry is needed.
                _tipPurchaseEvent.value = TipPurchaseEvent(
                    status = TipPurchaseStatus.Success,
                    productId = productId,
                    message = result.debugMessage,
                )
            }
        }
    }

    override fun queryPurchases() {
        // Used as screen refresh / Retry: re-show loading and re-query tip SKUs.
        _isPremium.value = true
        _tipProductsLoaded.value = false
        if (!billingClient.isReady) {
            startConnection()
            return
        }
        queryProductDetails()
        queryPurchasesInternal()
    }

    private fun queryPurchasesInternal() {
        if (!billingClient.isReady) return

        // Consume any leftover unconsumed tip purchases (e.g. process death mid-flow).
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            Log.i(
                TAG,
                "queryPurchases responseCode=${billingResult.responseCode} " +
                    "(${responseCodeName(billingResult.responseCode)}) " +
                    "count=${purchases.size}",
            )
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            purchases
                .filter { purchase ->
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                        purchase.products.any { it in TipSkus.ALL }
                }
                .forEach { handlePurchase(it) }
        }
    }

    override fun restorePurchases() {
        // Tips are not a license — nothing to restore.
        _isPremium.value = true
    }

    override fun acknowledgeTipEvent() {
        _tipPurchaseEvent.value = TipPurchaseEvent()
    }

    companion object {
        private const val TAG = "PlayBillingProvider"

        private fun responseCodeName(code: Int): String = when (code) {
            BillingClient.BillingResponseCode.SERVICE_TIMEOUT -> "SERVICE_TIMEOUT"
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> "FEATURE_NOT_SUPPORTED"
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> "SERVICE_DISCONNECTED"
            BillingClient.BillingResponseCode.OK -> "OK"
            BillingClient.BillingResponseCode.USER_CANCELED -> "USER_CANCELED"
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "BILLING_UNAVAILABLE"
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "ITEM_UNAVAILABLE"
            BillingClient.BillingResponseCode.DEVELOPER_ERROR -> "DEVELOPER_ERROR"
            BillingClient.BillingResponseCode.ERROR -> "ERROR"
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "ITEM_ALREADY_OWNED"
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> "ITEM_NOT_OWNED"
            BillingClient.BillingResponseCode.NETWORK_ERROR -> "NETWORK_ERROR"
            else -> "UNKNOWN($code)"
        }
    }
}
