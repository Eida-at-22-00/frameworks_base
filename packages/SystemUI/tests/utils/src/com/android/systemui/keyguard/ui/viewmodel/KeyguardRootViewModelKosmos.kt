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
package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.dump.dumpManager
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.pulseExpansionInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shade.ui.viewmodel.notificationShadeWindowModel
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.notificationIconContainerAlwaysOnDisplayViewModel
import com.android.systemui.statusbar.notification.promoted.domain.interactor.aodPromotedNotificationInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.notificationsKeyguardInteractor
import com.android.systemui.statusbar.phone.dozeParameters
import com.android.systemui.statusbar.phone.screenOffAnimationController
import com.android.systemui.wallpapers.domain.interactor.wallpaperFocalAreaInteractor

val Kosmos.keyguardRootViewModel by Fixture {
    KeyguardRootViewModel(
        applicationScope = applicationCoroutineScope,
        deviceEntryInteractor = deviceEntryInteractor,
        dozeParameters = dozeParameters,
        keyguardInteractor = keyguardInteractor,
        communalInteractor = communalInteractor,
        keyguardTransitionInteractor = keyguardTransitionInteractor,
        notificationsKeyguardInteractor = notificationsKeyguardInteractor,
        pulseExpansionInteractor = pulseExpansionInteractor,
        aodPromotedNotificationInteractor = aodPromotedNotificationInteractor,
        aodNotificationIconViewModel = notificationIconContainerAlwaysOnDisplayViewModel,
        notificationShadeWindowModel = notificationShadeWindowModel,
        alternateBouncerToAodTransitionViewModel = alternateBouncerToAodTransitionViewModel,
        alternateBouncerToGoneTransitionViewModel = alternateBouncerToGoneTransitionViewModel,
        alternateBouncerToLockscreenTransitionViewModel =
            alternateBouncerToLockscreenTransitionViewModel,
        alternateBouncerToOccludedTransitionViewModel =
            alternateBouncerToOccludedTransitionViewModel,
        alternateBouncerToPrimaryBouncerTransitionViewModel =
            alternateBouncerToPrimaryBouncerTransitionViewModel,
        aodToGoneTransitionViewModel = aodToGoneTransitionViewModel,
        aodToLockscreenTransitionViewModel = aodToLockscreenTransitionViewModel,
        aodToOccludedTransitionViewModel = aodToOccludedTransitionViewModel,
        aodToPrimaryBouncerTransitionViewModel = aodToPrimaryBouncerTransitionViewModel,
        dozingToDreamingTransitionViewModel = dozingToDreamingTransitionViewModel,
        dozingToGoneTransitionViewModel = dozingToGoneTransitionViewModel,
        dozingToLockscreenTransitionViewModel = dozingToLockscreenTransitionViewModel,
        dozingToOccludedTransitionViewModel = dozingToOccludedTransitionViewModel,
        dozingToPrimaryBouncerTransitionViewModel = dozingToPrimaryBouncerTransitionViewModel,
        dreamingToAodTransitionViewModel = dreamingToAodTransitionViewModel,
        dreamingToGoneTransitionViewModel = dreamingToGoneTransitionViewModel,
        dreamingToLockscreenTransitionViewModel = dreamingToLockscreenTransitionViewModel,
        glanceableHubToLockscreenTransitionViewModel = glanceableHubToLockscreenTransitionViewModel,
        goneToAodTransitionViewModel = goneToAodTransitionViewModel,
        goneToDozingTransitionViewModel = goneToDozingTransitionViewModel,
        goneToDreamingTransitionViewModel = goneToDreamingTransitionViewModel,
        goneToLockscreenTransitionViewModel = goneToLockscreenTransitionViewModel,
        goneToGlanceableHubTransitionViewModel = goneToGlanceableHubTransitionViewModel,
        lockscreenToAodTransitionViewModel = lockscreenToAodTransitionViewModel,
        lockscreenToDozingTransitionViewModel = lockscreenToDozingTransitionViewModel,
        lockscreenToDreamingTransitionViewModel = lockscreenToDreamingTransitionViewModel,
        lockscreenToGlanceableHubTransitionViewModel = lockscreenToGlanceableHubTransitionViewModel,
        lockscreenToGoneTransitionViewModel = lockscreenToGoneTransitionViewModel,
        lockscreenToOccludedTransitionViewModel = lockscreenToOccludedTransitionViewModel,
        lockscreenToPrimaryBouncerTransitionViewModel =
            lockscreenToPrimaryBouncerTransitionViewModel,
        occludedToAlternateBouncerTransitionViewModel =
            occludedToAlternateBouncerTransitionViewModel,
        occludedToAodTransitionViewModel = occludedToAodTransitionViewModel,
        occludedToDozingTransitionViewModel = occludedToDozingTransitionViewModel,
        occludedToLockscreenTransitionViewModel = occludedToLockscreenTransitionViewModel,
        occludedToPrimaryBouncerTransitionViewModel = occludedToPrimaryBouncerTransitionViewModel,
        offToLockscreenTransitionViewModel = offToLockscreenTransitionViewModel,
        primaryBouncerToAodTransitionViewModel = primaryBouncerToAodTransitionViewModel,
        primaryBouncerToGoneTransitionViewModel = primaryBouncerToGoneTransitionViewModel,
        primaryBouncerToLockscreenTransitionViewModel =
            primaryBouncerToLockscreenTransitionViewModel,
        screenOffAnimationController = screenOffAnimationController,
        aodBurnInViewModel = aodBurnInViewModel,
        shadeInteractor = shadeInteractor,
        wallpaperFocalAreaInteractor = wallpaperFocalAreaInteractor,
        dumpManager = dumpManager,
        glanceableHubToAodTransitionViewModel = glanceableHubToAodTransitionViewModel,
        aodToGlanceableHubTransitionViewModel = aodToGlanceableHubTransitionViewModel,
    )
}
