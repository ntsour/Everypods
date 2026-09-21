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

package io.automated.ventures.everypods.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.chrisbanes.haze.hazeSource
import io.automated.ventures.everypods.R
import io.automated.ventures.everypods.billing.TipProduct
import io.automated.ventures.everypods.billing.TipPurchaseStatus
import io.automated.ventures.everypods.presentation.components.StyledButton
import io.automated.ventures.everypods.presentation.components.StyledScaffold
import io.automated.ventures.everypods.presentation.viewmodel.PurchaseViewModel

@Composable
fun PurchaseScreen(
    viewModel: PurchaseViewModel = viewModel(),
    navController: NavController
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val state by viewModel.uiState.collectAsState()
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) Color(0xFF000000) else Color(0xFFF2F2F7)
    val cardBackgroundColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
    val textColor = if (isDarkTheme) Color.White else Color.Black
    val accent = if (isDarkTheme) Color(0xFF0091FF) else Color(0xFF0088FF)

    val backdrop = rememberLayerBackdrop()
    val thankYou = state.tipEvent.status == TipPurchaseStatus.Success

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    StyledScaffold(
        title = stringResource(R.string.support_everypods_title)
    ) { topPadding, hazeState, bottomPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .hazeSource(state = hazeState)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(topPadding))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardBackgroundColor, RoundedCornerShape(28.dp))
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.VolunteerActivism,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    text = stringResource(R.string.support_everypods_title),
                    style = TextStyle(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = SfPro,
                        color = textColor,
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.support_everypods_body),
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        fontFamily = SfPro,
                        color = textColor.copy(alpha = 0.65f),
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (thankYou) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBackgroundColor, RoundedCornerShape(28.dp))
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.tip_thank_you_title),
                        style = TextStyle(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = SfPro,
                            color = textColor,
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(R.string.tip_thank_you_body),
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontFamily = SfPro,
                            color = textColor.copy(alpha = 0.65f),
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    StyledButton(
                        onClick = { viewModel.acknowledgeTipEvent() },
                        backdrop = rememberLayerBackdrop(),
                        modifier = Modifier.fillMaxWidth(),
                        maxScale = 0.05f,
                        surfaceColor = accent
                    ) {
                        Text(
                            stringResource(R.string.tip_again),
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = SfPro,
                                color = Color.White
                            ),
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .background(backgroundColor)
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.tip_choose_amount),
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor.copy(alpha = 0.6f),
                            fontFamily = SfPro
                        )
                    )
                }

                when {
                    state.tipProducts.isNotEmpty() -> {
                        TipTierList(
                            products = state.tipProducts,
                            purchasingSku = state.purchasingSku,
                            textColor = textColor,
                            cardBackgroundColor = cardBackgroundColor,
                            accent = accent,
                            onTip = { sku -> viewModel.tip(context, sku) }
                        )
                    }
                    // Loading only while the first (or retry) query is in flight.
                    !state.tipProductsLoaded -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(cardBackgroundColor, RoundedCornerShape(28.dp))
                                .padding(horizontal = 20.dp, vertical = 20.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.tip_loading_prices),
                                style = TextStyle(
                                    fontSize = 15.sp,
                                    fontFamily = SfPro,
                                    color = textColor.copy(alpha = 0.7f),
                                    textAlign = TextAlign.Center
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    else -> {
                        // Query finished with 0 products, or billing setup failed.
                        val emptyMessage = if (state.billingAvailable) {
                            stringResource(R.string.tip_products_unavailable)
                        } else {
                            stringResource(R.string.tip_billing_unavailable)
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(cardBackgroundColor, RoundedCornerShape(28.dp))
                                .padding(horizontal = 20.dp, vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = emptyMessage,
                                style = TextStyle(
                                    fontSize = 15.sp,
                                    fontFamily = SfPro,
                                    color = textColor.copy(alpha = 0.7f),
                                    textAlign = TextAlign.Center
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            StyledButton(
                                onClick = { viewModel.refresh() },
                                backdrop = rememberLayerBackdrop(),
                                modifier = Modifier.fillMaxWidth(),
                                maxScale = 0.05f,
                                surfaceColor = accent
                            ) {
                                Text(
                                    stringResource(R.string.tip_retry),
                                    style = TextStyle(
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = SfPro,
                                        color = Color.White
                                    ),
                                )
                            }
                        }
                    }
                }

                val errorMsg = when (state.tipEvent.status) {
                    TipPurchaseStatus.Error,
                    TipPurchaseStatus.Unavailable -> state.tipEvent.message
                    else -> null
                }
                if (!errorMsg.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = errorMsg,
                        modifier = Modifier.fillMaxWidth(),
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontFamily = SfPro,
                            color = textColor.copy(alpha = 0.55f),
                            textAlign = TextAlign.Center
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.tips_are_voluntary),
                modifier = Modifier.fillMaxWidth(),
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = SfPro,
                    color = textColor.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                ),
            )

            Spacer(modifier = Modifier.height(bottomPadding))
        }
    }
}

@Composable
private fun TipTierList(
    products: List<TipProduct>,
    purchasingSku: String?,
    textColor: Color,
    cardBackgroundColor: Color,
    accent: Color,
    onTip: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(cardBackgroundColor, RoundedCornerShape(28.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        products.forEachIndexed { index, product ->
            TipTierRow(
                product = product,
                busy = purchasingSku == product.productId,
                textColor = textColor,
                accent = accent,
                onTip = { onTip(product.productId) }
            )
            if (index < products.lastIndex) {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color(0x40888888),
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun TipTierRow(
    product: TipProduct,
    busy: Boolean,
    textColor: Color,
    accent: Color,
    onTip: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = product.title,
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = SfPro,
                    color = textColor
                )
            )
            Text(
                text = product.formattedPrice,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontFamily = SfPro,
                    color = textColor.copy(alpha = 0.55f)
                )
            )
        }
        StyledButton(
            onClick = onTip,
            backdrop = rememberLayerBackdrop(),
            maxScale = 0.05f,
            surfaceColor = accent,
            enabled = !busy
        ) {
            Text(
                if (busy) stringResource(R.string.tip_pending)
                else stringResource(R.string.tip_button),
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = SfPro,
                    color = Color.White
                ),
            )
        }
    }
}
