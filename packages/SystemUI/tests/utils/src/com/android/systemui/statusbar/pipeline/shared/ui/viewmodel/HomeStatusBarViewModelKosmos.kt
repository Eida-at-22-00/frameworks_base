/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.shared.ui.viewmodel

import android.content.testableContext
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.backgroundScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.log.table.tableLogBufferFactory
import com.android.systemui.scene.domain.interactor.sceneContainerOcclusionInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.shade.domain.interactor.shadeDisplaysInteractor
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.chips.sharetoapp.ui.viewmodel.shareToAppChipViewModel
import com.android.systemui.statusbar.chips.ui.viewmodel.ongoingActivityChipsViewModel
import com.android.systemui.statusbar.chips.uievents.statusBarChipsUiEventLogger
import com.android.systemui.statusbar.events.domain.interactor.systemStatusEventAnimationInteractor
import com.android.systemui.statusbar.featurepods.popups.ui.viewmodel.statusBarPopupChipsViewModelFactory
import com.android.systemui.statusbar.layout.ui.viewmodel.multiDisplayStatusBarContentInsetsViewModelStore
import com.android.systemui.statusbar.notification.domain.interactor.activeNotificationsInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.headsUpNotificationInteractor
import com.android.systemui.statusbar.phone.domain.interactor.darkIconInteractor
import com.android.systemui.statusbar.phone.domain.interactor.lightsOutInteractor
import com.android.systemui.statusbar.pipeline.battery.ui.viewmodel.batteryViewModelFactory
import com.android.systemui.statusbar.pipeline.shared.domain.interactor.homeStatusBarIconBlockListInteractor
import com.android.systemui.statusbar.pipeline.shared.domain.interactor.homeStatusBarInteractor

var Kosmos.homeStatusBarViewModel: HomeStatusBarViewModel by
    Kosmos.Fixture { homeStatusBarViewModelFactory.invoke(testableContext.displayId) }
var Kosmos.homeStatusBarViewModelFactory: (Int) -> HomeStatusBarViewModel by
    Kosmos.Fixture {
        { displayId ->
            HomeStatusBarViewModelImpl(
                displayId,
                batteryViewModelFactory,
                tableLogBufferFactory,
                homeStatusBarInteractor,
                homeStatusBarIconBlockListInteractor,
                lightsOutInteractor,
                activeNotificationsInteractor,
                darkIconInteractor,
                headsUpNotificationInteractor,
                keyguardTransitionInteractor,
                keyguardInteractor,
                statusBarOperatorNameViewModel,
                sceneInteractor,
                sceneContainerOcclusionInteractor,
                shadeInteractor,
                shareToAppChipViewModel,
                ongoingActivityChipsViewModel,
                statusBarPopupChipsViewModelFactory,
                systemStatusEventAnimationInteractor,
                multiDisplayStatusBarContentInsetsViewModelStore,
                backgroundScope,
                testDispatcher,
                { shadeDisplaysInteractor },
                uiEventLogger = statusBarChipsUiEventLogger,
            )
        }
    }
