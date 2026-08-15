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
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import io.automated.ventures.everypods.R

class FOSSBillingProvider(context: Context): BillingProvider {
    private val _isPremium = MutableStateFlow(true)
    override val isPremium: StateFlow<Boolean> = _isPremium

    private val _price = MutableStateFlow(context.getString(R.string.name_your_own_price))
    override val price: StateFlow<String> = _price

    init {
        queryPurchases()
    }

    override fun purchase(activity: Activity) {
        Toast.makeText(activity, "EveryPods is free. Donations are optional.", Toast.LENGTH_SHORT).show()
    }

    override fun queryPurchases() {
        _isPremium.value = true
    }

    override fun restorePurchases() {
        _isPremium.value = true
    }
}
