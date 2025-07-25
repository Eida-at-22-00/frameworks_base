/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalKairosApi::class)

package com.android.systemui.shade.ui.viewmodel

import android.content.applicationContext
import com.android.systemui.battery.batteryMeterViewControllerFactory
import com.android.systemui.kairos.ExperimentalKairosApi
import com.android.systemui.kairos.kairos
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.plugins.activityStarter
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.shade.domain.interactor.privacyChipInteractor
import com.android.systemui.shade.domain.interactor.shadeHeaderClockInteractor
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shade.domain.interactor.shadeModeInteractor
import com.android.systemui.statusbar.phone.ui.StatusBarIconController
import com.android.systemui.statusbar.phone.ui.tintedIconManagerFactory
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.mobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.mobileIconsViewModel
import org.mockito.kotlin.mock

val Kosmos.shadeHeaderViewModel: ShadeHeaderViewModel by
    Kosmos.Fixture {
        ShadeHeaderViewModel(
            context = applicationContext,
            activityStarter = activityStarter,
            sceneInteractor = sceneInteractor,
            shadeInteractor = shadeInteractor,
            shadeModeInteractor = shadeModeInteractor,
            mobileIconsInteractor = mobileIconsInteractor,
            mobileIconsViewModel = mobileIconsViewModel,
            privacyChipInteractor = privacyChipInteractor,
            clockInteractor = shadeHeaderClockInteractor,
            tintedIconManagerFactory = tintedIconManagerFactory,
            batteryMeterViewControllerFactory = batteryMeterViewControllerFactory,
            statusBarIconController = mock<StatusBarIconController>(),
            kairosNetwork = kairos,
            mobileIconsViewModelKairos = mock(),
        )
    }

val Kosmos.shadeHeaderViewModelFactory: ShadeHeaderViewModel.Factory by
    Kosmos.Fixture {
        object : ShadeHeaderViewModel.Factory {
            override fun create(): ShadeHeaderViewModel {
                return shadeHeaderViewModel
            }
        }
    }
