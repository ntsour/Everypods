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

package io.automated.ventures.everypods.presentation.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.automated.ventures.everypods.billing.BillingManager
import io.automated.ventures.everypods.billing.TipProduct
import io.automated.ventures.everypods.billing.TipPurchaseEvent
import io.automated.ventures.everypods.billing.TipPurchaseStatus

data class PurchaseUiState(
    val isPremium: Boolean = true,
    val price: String = "",
    val tipProducts: List<TipProduct> = emptyList(),
    val billingAvailable: Boolean = false,
    val tipProductsLoaded: Boolean = false,
    val tipEvent: TipPurchaseEvent = TipPurchaseEvent(),
    val purchasingSku: String? = null,
)

class PurchaseViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(PurchaseUiState())
    val uiState = _uiState.asStateFlow()

    init {
        observeBilling()
    }

    private fun observeBilling() {
        val provider = BillingManager.provider
        viewModelScope.launch {
            provider.isPremium.collect { premium ->
                _uiState.update { it.copy(isPremium = premium) }
            }
        }
        viewModelScope.launch {
            provider.price.collect { price ->
                _uiState.update { it.copy(price = price) }
            }
        }
        viewModelScope.launch {
            provider.tipProducts.collect { products ->
                _uiState.update { it.copy(tipProducts = products) }
            }
        }
        viewModelScope.launch {
            provider.billingAvailable.collect { available ->
                _uiState.update { it.copy(billingAvailable = available) }
            }
        }
        viewModelScope.launch {
            provider.tipProductsLoaded.collect { loaded ->
                _uiState.update { it.copy(tipProductsLoaded = loaded) }
            }
        }
        viewModelScope.launch {
            provider.tipPurchaseEvent.collect { event ->
                _uiState.update {
                    it.copy(
                        tipEvent = event,
                        purchasingSku = if (event.status == TipPurchaseStatus.Pending) {
                            event.productId
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    fun tip(context: Context, productId: String) {
        BillingManager.provider.tip(context as Activity, productId)
    }

    fun purchase(context: Context) {
        BillingManager.provider.purchase(context as Activity)
    }

    fun restorePurchases() {
        BillingManager.provider.restorePurchases()
    }

    fun acknowledgeTipEvent() {
        BillingManager.provider.acknowledgeTipEvent()
    }

    fun refresh() {
        BillingManager.provider.queryPurchases()
    }
}
