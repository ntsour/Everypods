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

package io.automated.ventures.everypods.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import io.automated.ventures.everypods.R

@Composable
fun HearingHealthSettings(
    navController: NavController,
    hasPPECapability: Boolean
) {
    if (hasPPECapability) {
        NavigationButton(
            to = "hearing_protection",
            name = stringResource(R.string.hearing_protection),
            title = stringResource(R.string.hearing_health),
            navController = navController
        )
    }
}
