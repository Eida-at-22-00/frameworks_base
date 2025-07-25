/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.keyguard.ui.viewmodel

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.android.systemui.customization.R as customR
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardSmartspaceInteractor
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class KeyguardSmartspaceViewModel
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    smartspaceController: LockscreenSmartspaceController,
    keyguardClockViewModel: KeyguardClockViewModel,
    smartspaceInteractor: KeyguardSmartspaceInteractor,
    shadeModeInteractor: ShadeModeInteractor,
) {
    /** Whether the smartspace section is available in the build. */
    val isSmartspaceEnabled: Boolean = smartspaceController.isEnabled
    /** Whether the weather area is available in the build. */
    private val isWeatherEnabled: StateFlow<Boolean> = smartspaceInteractor.isWeatherEnabled

    /** Whether the data and weather areas are decoupled in the build. */
    val isDateWeatherDecoupled: Boolean = smartspaceController.isDateWeatherDecoupled

    /** Whether the date area should be visible. */
    val isDateVisible: StateFlow<Boolean> =
        keyguardClockViewModel.hasCustomWeatherDataDisplay
            .map { !it }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = !keyguardClockViewModel.hasCustomWeatherDataDisplay.value,
            )

    /** Whether the weather area should be visible. */
    val isWeatherVisible: StateFlow<Boolean> =
        combine(isWeatherEnabled, keyguardClockViewModel.hasCustomWeatherDataDisplay) {
                isWeatherEnabled,
                clockIncludesCustomWeatherDisplay ->
                isWeatherVisible(
                    clockIncludesCustomWeatherDisplay = clockIncludesCustomWeatherDisplay,
                    isWeatherEnabled = isWeatherEnabled,
                )
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue =
                    isWeatherVisible(
                        clockIncludesCustomWeatherDisplay =
                            keyguardClockViewModel.hasCustomWeatherDataDisplay.value,
                        isWeatherEnabled = isWeatherEnabled.value,
                    ),
            )

    private fun isWeatherVisible(
        clockIncludesCustomWeatherDisplay: Boolean,
        isWeatherEnabled: Boolean,
    ): Boolean {
        return !clockIncludesCustomWeatherDisplay && isWeatherEnabled
    }

    /* trigger clock and smartspace constraints change when smartspace appears */
    val bcSmartspaceVisibility: StateFlow<Int> = smartspaceInteractor.bcSmartspaceVisibility

    val isShadeLayoutWide: StateFlow<Boolean> = shadeModeInteractor.isShadeLayoutWide

    companion object {
        private const val TAG = "KeyguardSmartspaceVM"

        fun dateWeatherBelowSmallClock(
            configuration: Configuration,
            customDateWeather: Boolean = false,
        ): Boolean {
            return if (
                com.android.systemui.shared.Flags.clockReactiveSmartspaceLayout() &&
                    !customDateWeather
            ) {
                // font size to display size
                // These values come from changing the font size and display size on a non-foldable.
                // Visually looked at which configs cause the date/weather to push off of the screen
                val breakingPairs =
                    listOf(
                        0.85f to 320, // tiny font size but large display size
                        1f to 346,
                        1.15f to 346,
                        1.5f to 376,
                        1.8f to 411, // large font size but tiny display size
                    )
                val screenWidthDp = configuration.screenWidthDp
                val fontScale = configuration.fontScale
                var fallBelow = false
                for ((font, width) in breakingPairs) {
                    if (fontScale >= font && screenWidthDp <= width) {
                        fallBelow = true
                        break
                    }
                }
                Log.d(TAG, "Width: $screenWidthDp, Font: $fontScale, BelowClock: $fallBelow")
                return fallBelow
            } else {
                true
            }
        }

        fun getDateWeatherStartMargin(context: Context): Int {
            return context.resources.getDimensionPixelSize(R.dimen.below_clock_padding_start) +
                context.resources.getDimensionPixelSize(customR.dimen.status_view_margin_horizontal)
        }

        fun getDateWeatherEndMargin(context: Context): Int {
            return context.resources.getDimensionPixelSize(R.dimen.below_clock_padding_end) +
                context.resources.getDimensionPixelSize(customR.dimen.status_view_margin_horizontal)
        }

        fun getSmartspaceHorizontalMargin(context: Context): Int {
            return context.resources.getDimensionPixelSize(R.dimen.smartspace_padding_horizontal) +
                context.resources.getDimensionPixelSize(customR.dimen.status_view_margin_horizontal)
        }
    }
}
