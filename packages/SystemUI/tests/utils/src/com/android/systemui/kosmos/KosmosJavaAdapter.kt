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

package com.android.systemui.kosmos

import android.content.applicationContext
import android.os.fakeExecutorHandler
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.bouncerRepository
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.bouncer.domain.interactor.alternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.simBouncerInteractor
import com.android.systemui.classifier.falsingCollector
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.common.ui.domain.interactor.configurationInteractor
import com.android.systemui.communal.data.repository.fakeCommunalSceneRepository
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.domain.interactor.communalSceneInteractor
import com.android.systemui.communal.domain.interactor.communalSettingsInteractor
import com.android.systemui.communal.ui.viewmodel.communalTransitionViewModel
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryUdfpsInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.globalactions.domain.interactor.globalActionsInteractor
import com.android.systemui.haptics.msdl.bouncerHapticPlayer
import com.android.systemui.haptics.msdl.fakeMSDLPlayer
import com.android.systemui.haptics.qs.qsLongPressEffect
import com.android.systemui.jank.interactionJankMonitor
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.fromGoneTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.fromLockscreenTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.fromOccludedTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.fromPrimaryBouncerTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardClockInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.pulseExpansionInteractor
import com.android.systemui.keyguard.ui.viewmodel.glanceableHubToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.lockscreenToGlanceableHubTransitionViewModel
import com.android.systemui.model.sceneContainerPlugin
import com.android.systemui.model.sysUIStateDispatcher
import com.android.systemui.model.sysUiState
import com.android.systemui.plugins.statusbar.statusBarStateController
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.domain.interactor.sceneBackInteractor
import com.android.systemui.scene.domain.interactor.sceneContainerOcclusionInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.domain.startable.scrimStartable
import com.android.systemui.scene.sceneContainerConfig
import com.android.systemui.scene.shared.model.sceneDataSource
import com.android.systemui.scene.ui.view.mockWindowRootViewProvider
import com.android.systemui.settings.brightness.data.repository.brightnessMirrorShowingRepository
import com.android.systemui.settings.displayTracker
import com.android.systemui.shade.data.repository.fakeShadeDisplaysRepository
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shade.domain.interactor.shadeLayoutParams
import com.android.systemui.shade.domain.interactor.shadeModeInteractor
import com.android.systemui.shade.shadeController
import com.android.systemui.shade.ui.viewmodel.notificationShadeWindowModel
import com.android.systemui.statusbar.chips.ui.viewmodel.ongoingActivityChipsViewModel
import com.android.systemui.statusbar.data.repository.fakeStatusBarModePerDisplayRepository
import com.android.systemui.statusbar.disableflags.data.repository.fakeDisableFlagsRepository
import com.android.systemui.statusbar.disableflags.domain.interactor.disableFlagsInteractor
import com.android.systemui.statusbar.notification.collection.provider.visualStabilityProvider
import com.android.systemui.statusbar.notification.domain.interactor.activeNotificationsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.seenNotificationsInteractor
import com.android.systemui.statusbar.notification.row.entryAdapterFactory
import com.android.systemui.statusbar.notification.stack.domain.interactor.headsUpNotificationInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.sharedNotificationContainerInteractor
import com.android.systemui.statusbar.phone.fakeAutoHideControllerStore
import com.android.systemui.statusbar.phone.keyguardBypassController
import com.android.systemui.statusbar.phone.scrimController
import com.android.systemui.statusbar.pipeline.mobile.data.repository.mobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.fakeWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.wifiInteractor
import com.android.systemui.statusbar.policy.configurationController
import com.android.systemui.statusbar.policy.data.repository.fakeDeviceProvisioningRepository
import com.android.systemui.statusbar.policy.domain.interactor.deviceProvisioningInteractor
import com.android.systemui.statusbar.policy.keyguardStateController
import com.android.systemui.statusbar.ui.viewmodel.keyguardStatusBarViewModel
import com.android.systemui.util.time.systemClock
import com.android.systemui.volume.domain.interactor.volumeDialogInteractor
import com.android.systemui.window.domain.interactor.windowRootViewBlurInteractor

/**
 * Helper for using [Kosmos] from Java.
 *
 * If your test class extends [SysuiTestCase], you may use the secondary constructor so that
 * [Kosmos.applicationContext] and [Kosmos.testCase] are automatically set.
 */
@Deprecated("Please convert your test to Kotlin and use [Kosmos] directly.")
class KosmosJavaAdapter() {
    constructor(testCase: SysuiTestCase) : this() {
        kosmos.applicationContext = testCase.context
        kosmos.testCase = testCase
    }

    private val kosmos = Kosmos()

    val testDispatcher by lazy { kosmos.testDispatcher }
    val testScope by lazy { kosmos.testScope }
    val fakeExecutor by lazy { kosmos.fakeExecutor }
    val fakeExecutorHandler by lazy { kosmos.fakeExecutorHandler }
    val configurationController by lazy { kosmos.configurationController }
    val configurationRepository by lazy { kosmos.fakeConfigurationRepository }
    val configurationInteractor by lazy { kosmos.configurationInteractor }
    val bouncerRepository by lazy { kosmos.bouncerRepository }
    val communalRepository by lazy { kosmos.fakeCommunalSceneRepository }
    val communalTransitionViewModel by lazy { kosmos.communalTransitionViewModel }
    val activeNotificationsInteractor by lazy { kosmos.activeNotificationsInteractor }
    val headsUpNotificationInteractor by lazy { kosmos.headsUpNotificationInteractor }
    val seenNotificationsInteractor by lazy { kosmos.seenNotificationsInteractor }
    val keyguardRepository by lazy { kosmos.fakeKeyguardRepository }
    val keyguardBouncerRepository by lazy { kosmos.fakeKeyguardBouncerRepository }
    val keyguardBypassController by lazy { kosmos.keyguardBypassController }
    val keyguardInteractor by lazy { kosmos.keyguardInteractor }
    val keyguardTransitionRepository by lazy { kosmos.fakeKeyguardTransitionRepository }
    val keyguardTransitionInteractor by lazy { kosmos.keyguardTransitionInteractor }
    val keyguardStateController by lazy { kosmos.keyguardStateController }
    val keyguardStatusBarViewModel by lazy { kosmos.keyguardStatusBarViewModel }
    val powerRepository by lazy { kosmos.fakePowerRepository }
    val clock by lazy { kosmos.systemClock }
    val mobileConnectionsRepository by lazy { kosmos.mobileConnectionsRepository }
    val simBouncerInteractor by lazy { kosmos.simBouncerInteractor }
    val statusBarStateController by lazy { kosmos.statusBarStateController }
    val statusBarModePerDisplayRepository by lazy { kosmos.fakeStatusBarModePerDisplayRepository }
    val shadeLayoutParams by lazy { kosmos.shadeLayoutParams }
    val autoHideControllerStore by lazy { kosmos.fakeAutoHideControllerStore }
    val interactionJankMonitor by lazy { kosmos.interactionJankMonitor }
    val fakeSceneContainerConfig by lazy { kosmos.sceneContainerConfig }
    val sceneInteractor by lazy { kosmos.sceneInteractor }
    val sceneBackInteractor by lazy { kosmos.sceneBackInteractor }
    val falsingCollector by lazy { kosmos.falsingCollector }
    val powerInteractor by lazy { kosmos.powerInteractor }
    val pulseExpansionInteractor by lazy { kosmos.pulseExpansionInteractor }
    val deviceEntryInteractor by lazy { kosmos.deviceEntryInteractor }
    val deviceEntryUdfpsInteractor by lazy { kosmos.deviceEntryUdfpsInteractor }
    val deviceUnlockedInteractor by lazy { kosmos.deviceUnlockedInteractor }
    val communalInteractor by lazy { kosmos.communalInteractor }
    val communalSceneInteractor by lazy { kosmos.communalSceneInteractor }
    val communalSettingsInteractor by lazy { kosmos.communalSettingsInteractor }
    val sceneContainerPlugin by lazy { kosmos.sceneContainerPlugin }
    val deviceProvisioningInteractor by lazy { kosmos.deviceProvisioningInteractor }
    val fakeDeviceProvisioningRepository by lazy { kosmos.fakeDeviceProvisioningRepository }
    val fromLockscreenTransitionInteractor by lazy { kosmos.fromLockscreenTransitionInteractor }
    val fromOccludedTransitionInteractor by lazy { kosmos.fromOccludedTransitionInteractor }
    val fromPrimaryBouncerTransitionInteractor by lazy {
        kosmos.fromPrimaryBouncerTransitionInteractor
    }
    val fromGoneTransitionInteractor by lazy { kosmos.fromGoneTransitionInteractor }
    val globalActionsInteractor by lazy { kosmos.globalActionsInteractor }
    val sceneDataSource by lazy { kosmos.sceneDataSource }
    val keyguardClockInteractor by lazy { kosmos.keyguardClockInteractor }
    val sharedNotificationContainerInteractor by lazy {
        kosmos.sharedNotificationContainerInteractor
    }
    val brightnessMirrorShowingRepository by lazy { kosmos.brightnessMirrorShowingRepository }
    val qsLongPressEffect by lazy { kosmos.qsLongPressEffect }
    val shadeController by lazy { kosmos.shadeController }
    val shadeRepository by lazy { kosmos.shadeRepository }
    val shadeInteractor by lazy { kosmos.shadeInteractor }
    val notificationShadeWindowModel by lazy { kosmos.notificationShadeWindowModel }
    val visualStabilityProvider by lazy { kosmos.visualStabilityProvider }
    val wifiInteractor by lazy { kosmos.wifiInteractor }
    val fakeWifiRepository by lazy { kosmos.fakeWifiRepository }
    val volumeDialogInteractor by lazy { kosmos.volumeDialogInteractor }
    val alternateBouncerInteractor by lazy { kosmos.alternateBouncerInteractor }

    val ongoingActivityChipsViewModel by lazy { kosmos.ongoingActivityChipsViewModel }
    val scrimController by lazy { kosmos.scrimController }
    val scrimStartable by lazy { kosmos.scrimStartable }
    val sceneContainerOcclusionInteractor by lazy { kosmos.sceneContainerOcclusionInteractor }
    val msdlPlayer by lazy { kosmos.fakeMSDLPlayer }

    val shadeModeInteractor by lazy { kosmos.shadeModeInteractor }

    val bouncerHapticHelper by lazy { kosmos.bouncerHapticPlayer }

    val glanceableHubToLockscreenTransitionViewModel by lazy {
        kosmos.glanceableHubToLockscreenTransitionViewModel
    }
    val lockscreenToGlanceableHubTransitionViewModel by lazy {
        kosmos.lockscreenToGlanceableHubTransitionViewModel
    }
    val disableFlagsInteractor by lazy { kosmos.disableFlagsInteractor }
    val fakeDisableFlagsRepository by lazy { kosmos.fakeDisableFlagsRepository }
    val mockWindowRootViewProvider by lazy { kosmos.mockWindowRootViewProvider }
    val windowRootViewBlurInteractor by lazy { kosmos.windowRootViewBlurInteractor }
    val sysuiState by lazy { kosmos.sysUiState }
    val displayTracker by lazy { kosmos.displayTracker }
    val fakeShadeDisplaysRepository by lazy { kosmos.fakeShadeDisplaysRepository }
    val sysUIStateDispatcher by lazy { kosmos.sysUIStateDispatcher }
    val entryAdapterFactory by lazy { kosmos.entryAdapterFactory }
}
