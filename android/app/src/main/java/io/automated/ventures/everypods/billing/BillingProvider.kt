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
import kotlinx.coroutines.flow.StateFlow

interface BillingProvider {
    /** Always true — tips never unlock or lock features. */
    val isPremium: StateFlow<Boolean>

    /** Legacy single-price field; unused by the tip UI. */
    val price: StateFlow<String>

    val tipProducts: StateFlow<List<TipProduct>>
    val tipPurchaseEvent: StateFlow<TipPurchaseEvent>
    val billingAvailable: StateFlow<Boolean>

    /** True after the first tip-product query attempt finishes (ok, empty, or error). */
    val tipProductsLoaded: StateFlow<Boolean>

    fun tip(activity: Activity, productId: String)

    /** @deprecated Prefer [tip]. Kept so older call sites still compile. */
    fun purchase(activity: Activity)

    fun queryPurchases()
    fun restorePurchases()
    fun acknowledgeTipEvent()
}
