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

data class TipProduct(
    val productId: String,
    val title: String,
    val description: String,
    val formattedPrice: String,
)

object TipSkus {
    const val COFFEE = "tip_coffee"
    const val BEER = "tip_beer"
    const val LUNCH = "tip_lunch"
    const val SUPPORTER = "tip_supporter"

    val ALL: List<String> = listOf(COFFEE, BEER, LUNCH, SUPPORTER)

    fun fallbackTitle(productId: String): String = when (productId) {
        COFFEE -> "Coffee"
        BEER -> "Beer / snack"
        LUNCH -> "Lunch"
        SUPPORTER -> "Big thanks"
        else -> productId
    }
}

enum class TipPurchaseStatus {
    Idle,
    Pending,
    Success,
    Cancelled,
    Unavailable,
    Error,
}

data class TipPurchaseEvent(
    val status: TipPurchaseStatus = TipPurchaseStatus.Idle,
    val productId: String? = null,
    val message: String? = null,
)
