/*
 * Copyright 2023 The Android Open Source Project
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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.domain.startable

import android.app.StatusBarManager
import android.hardware.face.FaceManager
import android.os.PowerManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneKey
import com.android.internal.logging.uiEventLoggerFake
import com.android.internal.policy.IKeyguardDismissCallback
import com.android.keyguard.AuthInteractionProperties
import com.android.keyguard.keyguardUpdateMonitor
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.activityTransitionAnimator
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.bouncer.domain.interactor.alternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.bouncerInteractor
import com.android.systemui.bouncer.shared.logging.BouncerUiEvent
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.classifier.falsingCollector
import com.android.systemui.classifier.falsingManager
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.domain.interactor.deviceEntryHapticsInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.deviceentry.shared.model.DeviceUnlockStatus
import com.android.systemui.deviceentry.shared.model.FailedFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.SuccessFaceAuthenticationStatus
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.haptics.msdl.fakeMSDLPlayer
import com.android.systemui.haptics.vibratorHelper
import com.android.systemui.keyevent.data.repository.fakeKeyEventRepository
import com.android.systemui.keyguard.KeyguardViewMediator
import com.android.systemui.keyguard.data.repository.biometricSettingsRepository
import com.android.systemui.keyguard.data.repository.deviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeTrustRepository
import com.android.systemui.keyguard.data.repository.keyguardRepository
import com.android.systemui.keyguard.data.repository.keyguardTransitionRepository
import com.android.systemui.keyguard.dismissCallbackRegistry
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.dozeInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardEnabledInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.scenetransition.lockscreenSceneTransitionInteractor
import com.android.systemui.keyguard.shared.model.FailFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.model.sysUiState
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.data.repository.powerRepository
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.scene.data.model.asIterable
import com.android.systemui.scene.data.repository.Transition
import com.android.systemui.scene.domain.interactor.sceneBackInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.fakeSceneDataSource
import com.android.systemui.shade.domain.interactor.disableDualShade
import com.android.systemui.shade.domain.interactor.enableDualShade
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shared.system.QuickStepContract
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.disableflags.data.repository.fakeDisableFlagsRepository
import com.android.systemui.statusbar.disableflags.shared.model.DisableFlagsModel
import com.android.systemui.statusbar.domain.interactor.keyguardOcclusionInteractor
import com.android.systemui.statusbar.notification.data.repository.FakeHeadsUpRowRepository
import com.android.systemui.statusbar.notification.data.repository.HeadsUpRowRepository
import com.android.systemui.statusbar.notification.stack.data.repository.headsUpNotificationRepository
import com.android.systemui.statusbar.notificationShadeWindowController
import com.android.systemui.statusbar.phone.centralSurfaces
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fakeMobileConnectionsRepository
import com.android.systemui.statusbar.policy.data.repository.fakeDeviceProvisioningRepository
import com.android.systemui.statusbar.sysuiStatusBarStateController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.settings.data.repository.userAwareSecureSettingsRepository
import com.google.android.msdl.data.model.MSDLToken
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableSceneContainer
class SceneContainerStartableTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val deviceEntryHapticsInteractor by lazy { kosmos.deviceEntryHapticsInteractor }
    private val dozeInteractor by lazy { kosmos.dozeInteractor }
    private val keyguardInteractor by lazy { kosmos.keyguardInteractor }
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val sceneBackInteractor by lazy { kosmos.sceneBackInteractor }
    private val bouncerInteractor by lazy { kosmos.bouncerInteractor }
    private val faceAuthRepository by lazy { kosmos.fakeDeviceEntryFaceAuthRepository }
    private val bouncerRepository by lazy { kosmos.fakeKeyguardBouncerRepository }
    private val sysUiState = kosmos.sysUiState
    private val falsingCollector = mock<FalsingCollector>().also { kosmos.falsingCollector = it }
    private val vibratorHelper = mock<VibratorHelper>().also { kosmos.vibratorHelper = it }
    private val fakeSceneDataSource = kosmos.fakeSceneDataSource
    private val windowController = kosmos.notificationShadeWindowController
    private val centralSurfaces = kosmos.centralSurfaces
    private val powerInteractor = kosmos.powerInteractor
    private val fakeTrustRepository = kosmos.fakeTrustRepository
    private val uiEventLoggerFake = kosmos.uiEventLoggerFake
    private val msdlPlayer = kosmos.fakeMSDLPlayer
    private val authInteractionProperties = AuthInteractionProperties()
    private val mockActivityTransitionAnimator = mock<ActivityTransitionAnimator>()

    private lateinit var underTest: SceneContainerStartable

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(kosmos.keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean()))
            .thenReturn(true)
        kosmos.activityTransitionAnimator = mockActivityTransitionAnimator

        underTest = kosmos.sceneContainerStartable
    }

    @Test
    fun hydrateVisibility() =
        testScope.runTest {
            kosmos.disableDualShade()
            val currentDesiredSceneKey by collectLastValue(sceneInteractor.currentScene)
            val isVisible by collectLastValue(sceneInteractor.isVisible)
            val transitionStateFlow =
                prepareState(
                    authenticationMethod = AuthenticationMethodModel.Pin,
                    isDeviceUnlocked = true,
                    initialSceneKey = Scenes.Gone,
                )
            assertThat(currentDesiredSceneKey).isEqualTo(Scenes.Gone)
            assertThat(isVisible).isTrue()

            underTest.start()
            assertThat(isVisible).isFalse()

            fakeSceneDataSource.pause()
            sceneInteractor.changeScene(Scenes.Shade, "reason")
            transitionStateFlow.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Gone,
                    toScene = Scenes.Shade,
                    currentScene = flowOf(Scenes.Shade),
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            assertThat(isVisible).isTrue()
            fakeSceneDataSource.unpause(expectedScene = Scenes.Shade)
            transitionStateFlow.value = ObservableTransitionState.Idle(Scenes.Shade)
            assertThat(isVisible).isTrue()

            fakeSceneDataSource.pause()
            sceneInteractor.changeScene(Scenes.Gone, "reason")
            transitionStateFlow.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Shade,
                    toScene = Scenes.Gone,
                    currentScene = flowOf(Scenes.Gone),
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            assertThat(isVisible).isTrue()
            fakeSceneDataSource.unpause(expectedScene = Scenes.Gone)
            transitionStateFlow.value = ObservableTransitionState.Idle(Scenes.Gone)
            assertThat(isVisible).isFalse()

            kosmos.headsUpNotificationRepository.setNotifications(
                buildNotificationRows(isPinned = true)
            )
            assertThat(isVisible).isTrue()

            kosmos.headsUpNotificationRepository.setNotifications(
                buildNotificationRows(isPinned = false)
            )
            assertThat(isVisible).isFalse()
        }

    @Test
    fun hydrateVisibility_dualShade() =
        testScope.runTest {
            kosmos.enableDualShade()
            val currentDesiredSceneKey by collectLastValue(sceneInteractor.currentScene)
            val currentDesiredOverlays by collectLastValue(sceneInteractor.currentOverlays)
            val isVisible by collectLastValue(sceneInteractor.isVisible)
            val transitionStateFlow =
                prepareState(
                    authenticationMethod = AuthenticationMethodModel.Pin,
                    isDeviceUnlocked = true,
                    initialSceneKey = Scenes.Gone,
                )
            assertThat(currentDesiredSceneKey).isEqualTo(Scenes.Gone)
            assertThat(currentDesiredOverlays).isEmpty()
            assertThat(isVisible).isTrue()

            underTest.start()
            assertThat(isVisible).isFalse()

            // Expand the notifications shade.
            fakeSceneDataSource.pause()
            sceneInteractor.showOverlay(Overlays.NotificationsShade, "reason")
            transitionStateFlow.value =
                ObservableTransitionState.Transition.ShowOrHideOverlay(
                    overlay = Overlays.NotificationsShade,
                    fromContent = Scenes.Gone,
                    toContent = Overlays.NotificationsShade,
                    currentScene = Scenes.Gone,
                    currentOverlays = flowOf(emptySet()),
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                    previewProgress = flowOf(0f),
                    isInPreviewStage = flowOf(false),
                )
            assertThat(isVisible).isTrue()
            fakeSceneDataSource.unpause(expectedScene = Scenes.Gone)
            transitionStateFlow.value =
                ObservableTransitionState.Idle(
                    currentScene = Scenes.Gone,
                    currentOverlays = setOf(Overlays.NotificationsShade),
                )
            assertThat(isVisible).isTrue()

            // Collapse the notifications shade.
            fakeSceneDataSource.pause()
            sceneInteractor.hideOverlay(Overlays.NotificationsShade, "reason")
            transitionStateFlow.value =
                ObservableTransitionState.Transition.ShowOrHideOverlay(
                    overlay = Overlays.NotificationsShade,
                    fromContent = Overlays.NotificationsShade,
                    toContent = Scenes.Gone,
                    currentScene = Scenes.Gone,
                    currentOverlays = flowOf(setOf(Overlays.NotificationsShade)),
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                    previewProgress = flowOf(0f),
                    isInPreviewStage = flowOf(false),
                )
            assertThat(isVisible).isTrue()
            fakeSceneDataSource.unpause(expectedScene = Scenes.Gone)
            transitionStateFlow.value =
                ObservableTransitionState.Idle(
                    currentScene = Scenes.Gone,
                    currentOverlays = emptySet(),
                )
            assertThat(isVisible).isFalse()

            kosmos.headsUpNotificationRepository.setNotifications(
                buildNotificationRows(isPinned = true)
            )
            assertThat(isVisible).isTrue()

            kosmos.headsUpNotificationRepository.setNotifications(
                buildNotificationRows(isPinned = false)
            )
            assertThat(isVisible).isFalse()
        }

    @Test
    fun hydrateVisibility_basedOnDeviceProvisioning() =
        testScope.runTest {
            val isVisible by collectLastValue(sceneInteractor.isVisible)
            prepareState(
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = true,
                initialSceneKey = Scenes.Lockscreen,
                isDeviceProvisioned = false,
            )

            underTest.start()
            assertThat(isVisible).isFalse()

            kosmos.fakeDeviceProvisioningRepository.setDeviceProvisioned(true)
            assertThat(isVisible).isTrue()
        }

    @Test
    fun hydrateVisibility_basedOnOcclusion() =
        testScope.runTest {
            val isVisible by collectLastValue(sceneInteractor.isVisible)
            prepareState(isDeviceUnlocked = true, initialSceneKey = Scenes.Lockscreen)

            underTest.start()
            assertThat(isVisible).isTrue()

            kosmos.keyguardOcclusionInteractor.setWmNotifiedShowWhenLockedActivityOnTop(
                true,
                mock(),
            )
            assertThat(isVisible).isFalse()

            kosmos.keyguardOcclusionInteractor.setWmNotifiedShowWhenLockedActivityOnTop(false)
            assertThat(isVisible).isTrue()
        }

    @Test
    fun hydrateVisibility_basedOnAlternateBouncer() =
        testScope.runTest {
            val isVisible by collectLastValue(sceneInteractor.isVisible)
            prepareState(isDeviceUnlocked = false, initialSceneKey = Scenes.Lockscreen)

            underTest.start()
            assertThat(isVisible).isTrue()

            // WHEN the device is occluded,
            kosmos.keyguardOcclusionInteractor.setWmNotifiedShowWhenLockedActivityOnTop(
                true,
                mock(),
            )
            // THEN scenes are not visible
            assertThat(isVisible).isFalse()

            // WHEN the alternate bouncer is visible
            kosmos.fakeKeyguardBouncerRepository.setAlternateVisible(true)
            // THEN scenes visible
            assertThat(isVisible).isTrue()
        }

    @Test
    fun hydrateVisibility_whileDreaming() =
        testScope.runTest {
            val isVisible by collectLastValue(sceneInteractor.isVisible)

            // GIVEN the device is dreaming
            val transitionState =
                prepareState(isDeviceUnlocked = false, initialSceneKey = Scenes.Dream)
            underTest.start()
            assertThat(isVisible).isFalse()
        }

    @Test
    fun hydrateVisibility_onCommunalWhileOccluded() =
        testScope.runTest {
            val isVisible by collectLastValue(sceneInteractor.isVisible)

            kosmos.keyguardOcclusionInteractor.setWmNotifiedShowWhenLockedActivityOnTop(
                true,
                mock(),
            )
            prepareState(isDeviceUnlocked = false, initialSceneKey = Scenes.Communal)
            underTest.start()
            runCurrent()
            assertThat(isVisible).isTrue()
        }

    @Test
    fun hydrateVisibility_inCommunalTransition() =
        testScope.runTest {
            val isVisible by collectLastValue(sceneInteractor.isVisible)

            // GIVEN the device is dreaming
            val transitionState =
                prepareState(
                    authenticationMethod = AuthenticationMethodModel.Pin,
                    isDeviceUnlocked = false,
                    initialSceneKey = Scenes.Dream,
                )
            underTest.start()
            assertThat(isVisible).isFalse()

            // WHEN a transition starts to the communal hub
            sceneInteractor.changeScene(Scenes.Dream, "switching to dream for test")
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Dream,
                    toScene = Scenes.Communal,
                    currentScene = flowOf(Scenes.Dream),
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(false),
                )
            runCurrent()
            // THEN scenes are visible
            assertThat(isVisible).isTrue()
        }

    @Test
    fun startsInLockscreenScene() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState()

            underTest.start()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun switchToLockscreenWhenDeviceLocks() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = true,
                initialSceneKey = Scenes.Gone,
                startsAwake = false,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
            underTest.start()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun switchFromBouncerToGoneWhenDeviceUnlocked() =
        testScope.runTest {
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            prepareState(
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
                initialSceneKey = Scenes.Lockscreen,
                initialOverlays = setOf(Overlays.Bouncer),
            )
            assertThat(currentOverlays).contains(Overlays.Bouncer)
            underTest.start()

            updateFingerprintAuthStatus(isSuccess = true)
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
        }

    @Test
    fun switchFromLockscreenToGoneAndHideAltBouncerWhenDeviceUnlocked() =
        testScope.runTest {
            val alternateBouncerVisible by
                collectLastValue(bouncerRepository.alternateBouncerVisible)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)

            bouncerRepository.setAlternateVisible(true)
            assertThat(alternateBouncerVisible).isTrue()

            prepareState(
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
                initialSceneKey = Scenes.Lockscreen,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()

            updateFingerprintAuthStatus(isSuccess = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
            assertThat(alternateBouncerVisible).isFalse()
        }

    @Test
    fun stayOnCurrentSceneAndHideAltBouncerWhenDeviceUnlocked_whenLeaveOpenShade() =
        testScope.runTest {
            val alternateBouncerVisible by
                collectLastValue(bouncerRepository.alternateBouncerVisible)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)

            kosmos.sysuiStatusBarStateController.leaveOpen = true // leave shade open
            bouncerRepository.setAlternateVisible(true)
            assertThat(alternateBouncerVisible).isTrue()

            val transitionState =
                prepareState(
                    authenticationMethod = AuthenticationMethodModel.Pin,
                    isDeviceUnlocked = false,
                    initialSceneKey = Scenes.Lockscreen,
                )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            runCurrent()

            sceneInteractor.changeScene(Scenes.QuickSettings, "switching to qs for test")
            transitionState.value = ObservableTransitionState.Idle(Scenes.QuickSettings)
            runCurrent()
            assertThat(currentSceneKey).isEqualTo(Scenes.QuickSettings)

            updateFingerprintAuthStatus(isSuccess = true)
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.QuickSettings)
            assertThat(alternateBouncerVisible).isFalse()
        }

    @Test
    fun switchFromBouncerToQuickSettingsWhenDeviceUnlocked_whenLeaveOpenShade() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            val backStack by collectLastValue(sceneBackInteractor.backStack)
            kosmos.sysuiStatusBarStateController.leaveOpen = true // leave shade open

            val transitionState =
                prepareState(
                    authenticationMethod = AuthenticationMethodModel.Pin,
                    isDeviceUnlocked = false,
                    initialSceneKey = Scenes.Lockscreen,
                )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            runCurrent()

            sceneInteractor.changeScene(Scenes.QuickSettings, "switching to qs for test")
            transitionState.value = ObservableTransitionState.Idle(Scenes.QuickSettings)
            runCurrent()
            assertThat(currentSceneKey).isEqualTo(Scenes.QuickSettings)

            sceneInteractor.showOverlay(Overlays.Bouncer, "showing bouncer for test")
            transitionState.value =
                ObservableTransitionState.Idle(Scenes.QuickSettings, setOf(Overlays.Bouncer))
            runCurrent()
            assertThat(currentOverlays).contains(Overlays.Bouncer)
            assertThat(backStack?.asIterable()?.last()).isEqualTo(Scenes.Lockscreen)

            updateFingerprintAuthStatus(isSuccess = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.QuickSettings)
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
            assertThat(backStack?.asIterable()?.last()).isEqualTo(Scenes.Gone)
        }

    @Test
    fun switchFromBouncerToGoneWhenDeviceUnlocked_whenDoNotLeaveOpenShade() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            kosmos.sysuiStatusBarStateController.leaveOpen = false // don't leave shade open

            val transitionState =
                prepareState(
                    authenticationMethod = AuthenticationMethodModel.Pin,
                    isDeviceUnlocked = false,
                    initialSceneKey = Scenes.Lockscreen,
                )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            runCurrent()

            sceneInteractor.changeScene(Scenes.QuickSettings, "switching to qs for test")
            transitionState.value = ObservableTransitionState.Idle(Scenes.QuickSettings)
            runCurrent()
            assertThat(currentSceneKey).isEqualTo(Scenes.QuickSettings)

            sceneInteractor.showOverlay(Overlays.Bouncer, "showing bouncer for test")
            transitionState.value =
                ObservableTransitionState.Idle(Scenes.QuickSettings, setOf(Overlays.Bouncer))
            runCurrent()
            assertThat(currentOverlays).contains(Overlays.Bouncer)

            updateFingerprintAuthStatus(isSuccess = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
        }

    @Test
    fun switchFromLockscreenToGoneWhenDeviceUnlocksWithBypassOn() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                authenticationMethod = AuthenticationMethodModel.Pin,
                isBypassEnabled = true,
                initialSceneKey = Scenes.Lockscreen,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()

            updateFingerprintAuthStatus(isSuccess = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    fun stayOnLockscreenWhenDeviceUnlocksWithBypassOff() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(isBypassEnabled = false, initialSceneKey = Scenes.Lockscreen)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()

            // Authenticate using a passive auth method like face auth while bypass is disabled.
            faceAuthRepository.isAuthenticated.value = true

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun stayOnCurrentSceneWhenDeviceIsUnlockedAndUserIsNotOnLockscreen() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val transitionStateFlowValue =
                prepareState(
                    isBypassEnabled = true,
                    authenticationMethod = AuthenticationMethodModel.Pin,
                    initialSceneKey = Scenes.Lockscreen,
                )
            underTest.start()
            runCurrent()

            sceneInteractor.changeScene(Scenes.Shade, "switch to shade")
            transitionStateFlowValue.value = ObservableTransitionState.Idle(Scenes.Shade)
            assertThat(currentSceneKey).isEqualTo(Scenes.Shade)

            updateFingerprintAuthStatus(isSuccess = true)
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Shade)
        }

    @Test
    fun switchToGoneWhenDeviceIsUnlockedAndUserIsOnBouncerWithBypassDisabled() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            prepareState(
                isBypassEnabled = false,
                initialSceneKey = Scenes.Lockscreen,
                initialOverlays = setOf(Overlays.Bouncer),
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).contains(Overlays.Bouncer)
            underTest.start()

            // Authenticate using a passive auth method like face auth while bypass is disabled.
            faceAuthRepository.isAuthenticated.value = true

            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
        }

    @Test
    fun hideAlternateBouncerAndNotifyDismissCancelledWhenDeviceSleeps() =
        testScope.runTest {
            val alternateBouncerVisible by
                collectLastValue(bouncerRepository.alternateBouncerVisible)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(isDeviceUnlocked = false, initialSceneKey = Scenes.Shade)
            assertThat(currentSceneKey).isEqualTo(Scenes.Shade)
            bouncerRepository.setAlternateVisible(true)
            underTest.start()

            // run all pending dismiss succeeded/cancelled calls from setup:
            kosmos.fakeExecutor.runAllReady()

            val dismissCallback: IKeyguardDismissCallback = mock()
            kosmos.dismissCallbackRegistry.addCallback(dismissCallback)
            powerInteractor.setAsleepForTest()
            runCurrent()
            kosmos.fakeExecutor.runAllReady()

            assertThat(alternateBouncerVisible).isFalse()
            verify(dismissCallback).onDismissCancelled()
        }

    @Test
    fun switchToLockscreenWhenDeviceSleepsLocked() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(isDeviceUnlocked = false, initialSceneKey = Scenes.Shade)
            assertThat(currentSceneKey).isEqualTo(Scenes.Shade)
            underTest.start()
            powerInteractor.setAsleepForTest()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    @DisableFlags(Flags.FLAG_TRANSITION_RACE_CONDITION)
    fun switchToAOD_whenAvailable_whenDeviceSleepsLocked() =
        testScope.runTest {
            kosmos.lockscreenSceneTransitionInteractor.start()
            val asleepState by collectLastValue(keyguardInteractor.asleepKeyguardState)
            val currentTransitionInfo by
                collectLastValue(kosmos.keyguardTransitionRepository.currentTransitionInfoInternal)
            val transitionState =
                prepareState(isDeviceUnlocked = false, initialSceneKey = Scenes.Shade)
            kosmos.keyguardRepository.setAodAvailable(true)
            runCurrent()
            assertThat(asleepState).isEqualTo(KeyguardState.AOD)
            underTest.start()
            powerInteractor.setAsleepForTest()
            runCurrent()
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Shade,
                    toScene = Scenes.Lockscreen,
                    currentScene = flowOf(Scenes.Lockscreen),
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(false),
                )
            runCurrent()

            assertThat(currentTransitionInfo?.to).isEqualTo(KeyguardState.AOD)
        }

    @Test
    @DisableFlags(Flags.FLAG_TRANSITION_RACE_CONDITION)
    fun switchToDozing_whenAodUnavailable_whenDeviceSleepsLocked() =
        testScope.runTest {
            kosmos.lockscreenSceneTransitionInteractor.start()
            val asleepState by collectLastValue(keyguardInteractor.asleepKeyguardState)
            val currentTransitionInfo by
                collectLastValue(kosmos.keyguardTransitionRepository.currentTransitionInfoInternal)
            val transitionState =
                prepareState(isDeviceUnlocked = false, initialSceneKey = Scenes.Shade)
            kosmos.keyguardRepository.setAodAvailable(false)
            runCurrent()
            assertThat(asleepState).isEqualTo(KeyguardState.DOZING)
            underTest.start()
            powerInteractor.setAsleepForTest()
            runCurrent()
            transitionState.value = Transition(from = Scenes.Shade, to = Scenes.Lockscreen)
            runCurrent()

            assertThat(currentTransitionInfo?.to).isEqualTo(KeyguardState.DOZING)
        }

    @Test
    @EnableFlags(Flags.FLAG_TRANSITION_RACE_CONDITION)
    fun switchToAOD_whenAvailable_whenDeviceSleepsLocked_transitionFlagEnabled() =
        testScope.runTest {
            kosmos.lockscreenSceneTransitionInteractor.start()
            val asleepState by collectLastValue(kosmos.keyguardInteractor.asleepKeyguardState)
            val transitionState =
                prepareState(isDeviceUnlocked = false, initialSceneKey = Scenes.Shade)
            kosmos.keyguardRepository.setAodAvailable(true)
            runCurrent()
            assertThat(asleepState).isEqualTo(KeyguardState.AOD)
            underTest.start()
            powerInteractor.setAsleepForTest()
            runCurrent()
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Shade,
                    toScene = Scenes.Lockscreen,
                    currentScene = flowOf(Scenes.Lockscreen),
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(false),
                )
            runCurrent()

            assertThat(kosmos.keyguardTransitionRepository.currentTransitionInfo.to)
                .isEqualTo(KeyguardState.AOD)
        }

    @Test
    @EnableFlags(Flags.FLAG_TRANSITION_RACE_CONDITION)
    fun switchToDozing_whenAodUnavailable_whenDeviceSleepsLocked_transitionFlagEnabled() =
        testScope.runTest {
            kosmos.lockscreenSceneTransitionInteractor.start()
            val asleepState by collectLastValue(kosmos.keyguardInteractor.asleepKeyguardState)
            val transitionState =
                prepareState(isDeviceUnlocked = false, initialSceneKey = Scenes.Shade)
            kosmos.keyguardRepository.setAodAvailable(false)
            runCurrent()
            assertThat(asleepState).isEqualTo(KeyguardState.DOZING)
            underTest.start()
            powerInteractor.setAsleepForTest()
            runCurrent()
            transitionState.value = Transition(from = Scenes.Shade, to = Scenes.Lockscreen)
            runCurrent()

            assertThat(kosmos.keyguardTransitionRepository.currentTransitionInfo.to)
                .isEqualTo(KeyguardState.DOZING)
        }

    @Test
    fun switchToGoneWhenDoubleTapPowerGestureIsTriggeredFromGone() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val transitionStateFlow =
                prepareState(
                    authenticationMethod = AuthenticationMethodModel.Pin,
                    isDeviceUnlocked = true,
                    initialSceneKey = Scenes.Gone,
                )
            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
            underTest.start()
            runCurrent()

            kosmos.fakePowerRepository.updateWakefulness(
                rawState = WakefulnessState.STARTING_TO_SLEEP,
                lastSleepReason = WakeSleepReason.POWER_BUTTON,
                powerButtonLaunchGestureTriggered = false,
            )
            transitionStateFlow.value = Transition(from = Scenes.Gone, to = Scenes.Lockscreen)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)

            kosmos.fakePowerRepository.updateWakefulness(
                rawState = WakefulnessState.STARTING_TO_WAKE,
                lastSleepReason = WakeSleepReason.POWER_BUTTON,
                powerButtonLaunchGestureTriggered = true,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playSuccessHaptics_onSuccessfulLockscreenAuth_udfps() =
        testScope.runTest {
            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playSuccessHaptic by
                collectLastValue(deviceEntryHapticsInteractor.playSuccessHapticOnDeviceEntry)

            setupBiometricAuth(hasUdfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            // unlock with fingerprint
            updateFingerprintAuthStatus(isSuccess = true)

            assertThat(playSuccessHaptic).isNotNull()
            verify(vibratorHelper).vibrateAuthSuccess(anyString())
            verify(vibratorHelper, never()).vibrateAuthError(anyString())
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playSuccessMSDLHaptics_onSuccessfulLockscreenAuth_udfps() =
        testScope.runTest {
            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playSuccessHaptic by
                collectLastValue(deviceEntryHapticsInteractor.playSuccessHapticOnDeviceEntry)

            setupBiometricAuth(hasUdfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            // unlock with fingerprint
            updateFingerprintAuthStatus(isSuccess = true)

            assertThat(playSuccessHaptic).isNotNull()
            assertThat(msdlPlayer.latestTokenPlayed).isEqualTo(MSDLToken.UNLOCK)
            assertThat(msdlPlayer.latestPropertiesPlayed).isEqualTo(authInteractionProperties)
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playSuccessHaptics_onSuccessfulLockscreenAuth_sfps() =
        testScope.runTest {
            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playSuccessHaptic by
                collectLastValue(deviceEntryHapticsInteractor.playSuccessHapticOnDeviceEntry)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            allowHapticsOnSfps()
            // unlock with fingerprint
            updateFingerprintAuthStatus(isSuccess = true)

            assertThat(playSuccessHaptic).isNotNull()
            verify(vibratorHelper).vibrateAuthSuccess(anyString())
            verify(vibratorHelper, never()).vibrateAuthError(anyString())
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playSuccessMSDLHaptics_onSuccessfulLockscreenAuth_sfps() =
        testScope.runTest {
            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playSuccessHaptic by
                collectLastValue(deviceEntryHapticsInteractor.playSuccessHapticOnDeviceEntry)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            allowHapticsOnSfps()
            // unlock with fingerprint
            updateFingerprintAuthStatus(isSuccess = true)

            assertThat(playSuccessHaptic).isNotNull()
            assertThat(msdlPlayer.latestTokenPlayed).isEqualTo(MSDLToken.UNLOCK)
            assertThat(msdlPlayer.latestPropertiesPlayed).isEqualTo(authInteractionProperties)
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playErrorHaptics_onFailedLockscreenAuth_udfps() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playErrorHaptic by collectLastValue(deviceEntryHapticsInteractor.playErrorHaptic)

            setupBiometricAuth(hasUdfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            updateFingerprintAuthStatus(isSuccess = false)

            assertThat(playErrorHaptic).isNotNull()
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            verify(vibratorHelper).vibrateAuthError(anyString())
            verify(vibratorHelper, never()).vibrateAuthSuccess(anyString())
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playMSDLErrorHaptics_onFailedLockscreenAuth_udfps() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playErrorHaptic by collectLastValue(deviceEntryHapticsInteractor.playErrorHaptic)

            setupBiometricAuth(hasUdfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            updateFingerprintAuthStatus(isSuccess = false)

            assertThat(playErrorHaptic).isNotNull()
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(msdlPlayer.latestTokenPlayed).isEqualTo(MSDLToken.FAILURE)
            assertThat(msdlPlayer.latestPropertiesPlayed).isEqualTo(authInteractionProperties)
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playErrorHaptics_onFailedLockscreenAuth_sfps() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playErrorHaptic by collectLastValue(deviceEntryHapticsInteractor.playErrorHaptic)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            updateFingerprintAuthStatus(isSuccess = false)

            assertThat(playErrorHaptic).isNotNull()
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            verify(vibratorHelper).vibrateAuthError(anyString())
            verify(vibratorHelper, never()).vibrateAuthSuccess(anyString())
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun playMSDLErrorHaptics_onFailedLockscreenAuth_sfps() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playErrorHaptic by collectLastValue(deviceEntryHapticsInteractor.playErrorHaptic)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            updateFingerprintAuthStatus(isSuccess = false)

            assertThat(playErrorHaptic).isNotNull()
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(msdlPlayer.latestTokenPlayed).isEqualTo(MSDLToken.FAILURE)
            assertThat(msdlPlayer.latestPropertiesPlayed).isEqualTo(authInteractionProperties)
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun skipsSuccessHaptics_whenPowerButtonDown_sfps() =
        testScope.runTest {
            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playSuccessHaptic by
                collectLastValue(deviceEntryHapticsInteractor.playSuccessHapticOnDeviceEntry)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            allowHapticsOnSfps(isPowerButtonDown = true)
            // unlock with fingerprint
            updateFingerprintAuthStatus(isSuccess = true)

            assertThat(playSuccessHaptic).isNull()
            verify(vibratorHelper, never()).vibrateAuthSuccess(anyString())
            verify(vibratorHelper, never()).vibrateAuthError(anyString())
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun skipsMSDLSuccessHaptics_whenPowerButtonDown_sfps() =
        testScope.runTest {
            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playSuccessHaptic by
                collectLastValue(deviceEntryHapticsInteractor.playSuccessHapticOnDeviceEntry)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            allowHapticsOnSfps(isPowerButtonDown = true)
            // unlock with fingerprint
            updateFingerprintAuthStatus(isSuccess = true)

            assertThat(playSuccessHaptic).isNull()
            assertThat(msdlPlayer.latestTokenPlayed).isNull()
            assertThat(msdlPlayer.latestPropertiesPlayed).isNull()
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun skipsSuccessHaptics_whenPowerButtonRecentlyPressed_sfps() =
        testScope.runTest {
            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playSuccessHaptic by
                collectLastValue(deviceEntryHapticsInteractor.playSuccessHapticOnDeviceEntry)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            allowHapticsOnSfps(lastPowerPress = 50)
            // unlock with fingerprint
            updateFingerprintAuthStatus(isSuccess = true)

            assertThat(playSuccessHaptic).isNull()
            verify(vibratorHelper, never()).vibrateAuthSuccess(anyString())
            verify(vibratorHelper, never()).vibrateAuthError(anyString())
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun skipsMSDLSuccessHaptics_whenPowerButtonRecentlyPressed_sfps() =
        testScope.runTest {
            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playSuccessHaptic by
                collectLastValue(deviceEntryHapticsInteractor.playSuccessHapticOnDeviceEntry)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            allowHapticsOnSfps(lastPowerPress = 50)
            // unlock with fingerprint
            updateFingerprintAuthStatus(isSuccess = true)

            assertThat(playSuccessHaptic).isNull()
            assertThat(msdlPlayer.latestTokenPlayed).isNull()
            assertThat(msdlPlayer.latestPropertiesPlayed).isNull()
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun skipsErrorHaptics_whenPowerButtonDown_sfps() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playErrorHaptic by collectLastValue(deviceEntryHapticsInteractor.playErrorHaptic)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            kosmos.fakeKeyEventRepository.setPowerButtonDown(true)
            updateFingerprintAuthStatus(isSuccess = false)

            assertThat(playErrorHaptic).isNull()
            verify(vibratorHelper, never()).vibrateAuthError(anyString())
            verify(vibratorHelper, never()).vibrateAuthSuccess(anyString())
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun skipsMSDLErrorHaptics_whenPowerButtonDown_sfps() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playErrorHaptic by collectLastValue(deviceEntryHapticsInteractor.playErrorHaptic)

            setupBiometricAuth(hasSfps = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            kosmos.fakeKeyEventRepository.setPowerButtonDown(true)
            updateFingerprintAuthStatus(isSuccess = false)

            assertThat(playErrorHaptic).isNull()
            assertThat(msdlPlayer.latestTokenPlayed).isNull()
            assertThat(msdlPlayer.latestPropertiesPlayed).isNull()
        }

    @Test
    @DisableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun skipsFaceErrorHaptics_nonSfps_coEx() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playErrorHaptic by collectLastValue(deviceEntryHapticsInteractor.playErrorHaptic)

            setupBiometricAuth(hasUdfps = true, hasFace = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            updateFaceAuthStatus(isSuccess = false)

            assertThat(playErrorHaptic).isNull()
            verify(vibratorHelper, never()).vibrateAuthError(anyString())
            verify(vibratorHelper, never()).vibrateAuthSuccess(anyString())
        }

    @Test
    @EnableFlags(Flags.FLAG_MSDL_FEEDBACK)
    fun skipsMSDLFaceErrorHaptics_nonSfps_coEx() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val playErrorHaptic by collectLastValue(deviceEntryHapticsInteractor.playErrorHaptic)

            setupBiometricAuth(hasUdfps = true, hasFace = true)
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isFalse()

            underTest.start()
            updateFaceAuthStatus(isSuccess = false)

            assertThat(playErrorHaptic).isNull()
            assertThat(msdlPlayer.latestTokenPlayed).isNull()
            assertThat(msdlPlayer.latestPropertiesPlayed).isNull()
        }

    @Test
    fun hydrateSystemUiState() =
        testScope.runTest {
            val transitionStateFlow = prepareState()
            underTest.start()
            runCurrent()
            clearInvocations(sysUiState)

            listOf(Scenes.Gone, Scenes.Lockscreen, Scenes.Gone, Scenes.Shade, Scenes.QuickSettings)
                .forEachIndexed { index, sceneKey ->
                    if (sceneKey == Scenes.Gone) {
                        updateFingerprintAuthStatus(isSuccess = true)
                        runCurrent()
                    }
                    fakeSceneDataSource.pause()
                    sceneInteractor.changeScene(sceneKey, "reason")
                    runCurrent()
                    verify(sysUiState, times(index)).commitUpdate()

                    fakeSceneDataSource.unpause(expectedScene = sceneKey)
                    runCurrent()
                    verify(sysUiState, times(index)).commitUpdate()

                    transitionStateFlow.value = ObservableTransitionState.Idle(sceneKey)
                    runCurrent()
                    verify(sysUiState, times(index + 1)).commitUpdate()
                }
        }

    @Test
    fun hydrateSystemUiState_onLockscreen_basedOnOcclusion() =
        testScope.runTest {
            prepareState(initialSceneKey = Scenes.Lockscreen)
            underTest.start()
            runCurrent()
            clearInvocations(sysUiState)

            kosmos.keyguardOcclusionInteractor.setWmNotifiedShowWhenLockedActivityOnTop(
                true,
                mock(),
            )
            runCurrent()
            assertThat(
                    sysUiState.flags and
                        QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED != 0L
                )
                .isTrue()
            assertThat(
                    sysUiState.flags and
                        QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING != 0L
                )
                .isFalse()
            assertThat(
                    sysUiState.flags and
                        QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED != 0L
                )
                .isFalse()

            kosmos.keyguardOcclusionInteractor.setWmNotifiedShowWhenLockedActivityOnTop(false)
            runCurrent()
            assertThat(
                    sysUiState.flags and
                        QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING_OCCLUDED != 0L
                )
                .isFalse()
            assertThat(
                    sysUiState.flags and
                        QuickStepContract.SYSUI_STATE_STATUS_BAR_KEYGUARD_SHOWING != 0L
                )
                .isTrue()
            assertThat(
                    sysUiState.flags and
                        QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED != 0L
                )
                .isTrue()
        }

    @Test
    fun switchToGoneWhenDeviceStartsToWakeUp_authMethodNone() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.None,
                isLockscreenEnabled = false,
            )
            powerInteractor.setAsleepForTest()
            underTest.start()
            runCurrent()
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)

            powerInteractor.setAwakeForTest()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    fun stayOnLockscreenWhenDeviceStartsToWakeUp_authMethodSwipe() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.None,
                isLockscreenEnabled = true,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            powerInteractor.setAwakeForTest()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun doesNotSwitchToGone_whenDeviceStartsToWakeUp_authMethodSecure() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            powerInteractor.setAwakeForTest()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun doesNotSwitchToGone_whenDeviceStartsToWakeUp_ifAlreadyTransitioningToLockscreen() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val transitioningTo by collectLastValue(sceneInteractor.transitioningTo)
            val transitionStateFlow =
                prepareState(
                    isDeviceUnlocked = true,
                    initialSceneKey = Scenes.Gone,
                    authenticationMethod = AuthenticationMethodModel.Pin,
                )
            transitionStateFlow.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Gone,
                    toScene = Scenes.Lockscreen,
                    currentScene = flowOf(Scenes.Lockscreen),
                    progress = flowOf(0.1f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
            assertThat(transitioningTo).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            powerInteractor.setAwakeForTest()

            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
            assertThat(transitioningTo).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun switchToGoneWhenDeviceStartsToWakeUp_authMethodSecure_deviceUnlocked() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
                startsAwake = false,
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            underTest.start()

            updateFingerprintAuthStatus(isSuccess = true)
            runCurrent()
            powerInteractor.setAwakeForTest()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    fun collectFalsingSignals_onSuccessfulUnlock() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)

            val transitionStateFlow =
                prepareState(
                    initialSceneKey = Scenes.Lockscreen,
                    authenticationMethod = AuthenticationMethodModel.Pin,
                    isDeviceUnlocked = false,
                )
            underTest.start()
            runCurrent()
            verify(falsingCollector, never()).onSuccessfulUnlock()

            // Move around scenes without unlocking.
            listOf(Scenes.Shade, Scenes.QuickSettings, Scenes.Shade, Scenes.Lockscreen).forEach {
                sceneKey ->
                sceneInteractor.changeScene(sceneKey, "reason")
                transitionStateFlow.value = ObservableTransitionState.Idle(sceneKey)
                runCurrent()
                verify(falsingCollector, never()).onSuccessfulUnlock()
            }

            // Changing to the Gone scene should report a successful unlock.
            kosmos.authenticationInteractor.authenticate(FakeAuthenticationRepository.DEFAULT_PIN)
            runCurrent()
            // Make sure that the startable changed the scene to Gone because the device unlocked.
            assertThat(currentScene).isEqualTo(Scenes.Gone)
            // Make the transition state match the current state
            transitionStateFlow.value = ObservableTransitionState.Idle(Scenes.Gone)
            runCurrent()
            verify(falsingCollector).onSuccessfulUnlock()

            // Move around scenes without changing back to Lockscreen, shouldn't report another
            // unlock.
            listOf(Scenes.Shade, Scenes.QuickSettings, Scenes.Shade, Scenes.Gone).forEach { sceneKey
                ->
                sceneInteractor.changeScene(sceneKey, "reason")
                transitionStateFlow.value = ObservableTransitionState.Idle(sceneKey)
                runCurrent()
                verify(falsingCollector, times(1)).onSuccessfulUnlock()
            }

            // Putting the device to sleep to lock it again, which shouldn't report another
            // successful unlock.
            kosmos.powerInteractor.setAsleepForTest()
            advanceTimeBy(
                kosmos.userAwareSecureSettingsRepository
                    .getInt(
                        Settings.Secure.LOCK_SCREEN_LOCK_AFTER_TIMEOUT,
                        KeyguardViewMediator.KEYGUARD_LOCK_AFTER_DELAY_DEFAULT,
                    )
                    .toLong()
            )
            // Verify that the startable changed the scene to Lockscreen because the device locked
            // following the sleep.
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            // Make the transition state match the current state
            transitionStateFlow.value = ObservableTransitionState.Idle(Scenes.Lockscreen)
            // Wake up the device again before continuing with the test.
            kosmos.powerInteractor.setAwakeForTest()
            runCurrent()
            // Verify that the current scene is still the Lockscreen scene, now that the device is
            // still locked.
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            verify(falsingCollector, times(1)).onSuccessfulUnlock()

            // Move around scenes without unlocking.
            listOf(Scenes.Shade, Scenes.QuickSettings, Scenes.Shade, Scenes.Lockscreen).forEach {
                sceneKey ->
                sceneInteractor.changeScene(sceneKey, "reason")
                transitionStateFlow.value = ObservableTransitionState.Idle(sceneKey)
                runCurrent()
                verify(falsingCollector, times(1)).onSuccessfulUnlock()
            }

            kosmos.authenticationInteractor.authenticate(FakeAuthenticationRepository.DEFAULT_PIN)
            runCurrent()
            // Make sure that the startable changed the scene to Gone because the device unlocked.
            assertThat(currentScene).isEqualTo(Scenes.Gone)
            // Make the transition state match the current scene.
            transitionStateFlow.value = ObservableTransitionState.Idle(Scenes.Gone)
            runCurrent()
            verify(falsingCollector, times(2)).onSuccessfulUnlock()
        }

    @Test
    fun collectFalsingSignals_setShowingAod() =
        testScope.runTest {
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()
            verify(falsingCollector).setShowingAod(false)

            kosmos.fakeKeyguardRepository.setIsDozing(true)
            runCurrent()
            verify(falsingCollector).setShowingAod(true)

            kosmos.fakeKeyguardRepository.setIsDozing(false)
            runCurrent()
            verify(falsingCollector, times(2)).setShowingAod(false)
        }

    @Test
    fun bouncerImeHidden_shouldTransitionBackToLockscreen() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Password,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()

            bouncerInteractor.onImeHiddenByUser()
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun collectFalsingSignals_screenOnAndOff() =
        testScope.runTest {
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
                startsAwake = false,
            )
            underTest.start()
            runCurrent()
            verify(falsingCollector, never()).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, times(1)).onScreenOff()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_POWER_BUTTON)
            runCurrent()
            verify(falsingCollector, times(1)).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, times(1)).onScreenOff()

            powerInteractor.setAsleepForTest()
            runCurrent()
            verify(falsingCollector, times(1)).onScreenTurningOn()
            verify(falsingCollector, never()).onScreenOnFromTouch()
            verify(falsingCollector, times(2)).onScreenOff()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_TAP)
            runCurrent()
            verify(falsingCollector, times(1)).onScreenTurningOn()
            verify(falsingCollector, times(1)).onScreenOnFromTouch()
            verify(falsingCollector, times(2)).onScreenOff()

            powerInteractor.setAsleepForTest()
            runCurrent()
            verify(falsingCollector, times(1)).onScreenTurningOn()
            verify(falsingCollector, times(1)).onScreenOnFromTouch()
            verify(falsingCollector, times(3)).onScreenOff()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_POWER_BUTTON)
            runCurrent()
            verify(falsingCollector, times(2)).onScreenTurningOn()
            verify(falsingCollector, times(1)).onScreenOnFromTouch()
            verify(falsingCollector, times(3)).onScreenOff()
        }

    @Test
    fun collectFalsingSignals_bouncerVisibility() =
        testScope.runTest {
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()
            verify(falsingCollector).onBouncerHidden()

            sceneInteractor.showOverlay(Overlays.Bouncer, "reason")
            runCurrent()
            verify(falsingCollector).onBouncerShown()

            updateFingerprintAuthStatus(isSuccess = true)
            runCurrent()
            sceneInteractor.hideOverlay(Overlays.Bouncer, "reason")
            runCurrent()
            verify(falsingCollector, times(2)).onBouncerHidden()
        }

    @Test
    fun switchesToBouncer_whenSimBecomesLocked() =
        testScope.runTest {
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)

            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()

            kosmos.fakeMobileConnectionsRepository.isAnySimSecure.value = true
            runCurrent()

            assertThat(currentOverlays).contains(Overlays.Bouncer)
        }

    @Test
    fun switchesToLockscreen_whenSimBecomesUnlocked() =
        testScope.runTest {
            kosmos.fakeMobileConnectionsRepository.isAnySimSecure.value = true
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)

            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                initialOverlays = setOf(Overlays.Bouncer),
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
            )
            underTest.start()
            runCurrent()
            kosmos.fakeMobileConnectionsRepository.isAnySimSecure.value = false
            runCurrent()

            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
        }

    @Test
    fun switchesToGone_whenSimBecomesUnlocked_ifDeviceUnlockedAndLockscreenDisabled() =
        testScope.runTest {
            kosmos.fakeMobileConnectionsRepository.isAnySimSecure.value = true
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)

            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.None,
                isDeviceUnlocked = true,
                isLockscreenEnabled = false,
            )
            underTest.start()
            runCurrent()
            kosmos.fakeMobileConnectionsRepository.isAnySimSecure.value = false
            runCurrent()

            assertThat(currentSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    fun hydrateWindowController_setNotificationShadeFocusable() =
        testScope.runTest {
            val currentDesiredSceneKey by collectLastValue(sceneInteractor.currentScene)
            val transitionStateFlow =
                prepareState(
                    authenticationMethod = AuthenticationMethodModel.Pin,
                    isDeviceUnlocked = true,
                    initialSceneKey = Scenes.Gone,
                )
            assertThat(currentDesiredSceneKey).isEqualTo(Scenes.Gone)
            verify(windowController, never()).setNotificationShadeFocusable(anyBoolean())

            underTest.start()
            runCurrent()
            verify(windowController, times(1)).setNotificationShadeFocusable(false)

            fakeSceneDataSource.pause()
            sceneInteractor.changeScene(Scenes.Shade, "reason")
            transitionStateFlow.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Gone,
                    toScene = Scenes.Shade,
                    currentScene = flowOf(Scenes.Shade),
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            runCurrent()
            verify(windowController, times(1)).setNotificationShadeFocusable(false)

            fakeSceneDataSource.unpause(expectedScene = Scenes.Shade)
            transitionStateFlow.value = ObservableTransitionState.Idle(Scenes.Shade)
            runCurrent()
            verify(windowController, times(1)).setNotificationShadeFocusable(true)

            fakeSceneDataSource.pause()
            sceneInteractor.changeScene(Scenes.Gone, "reason")
            transitionStateFlow.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Shade,
                    toScene = Scenes.Gone,
                    currentScene = flowOf(Scenes.Gone),
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            runCurrent()
            verify(windowController, times(1)).setNotificationShadeFocusable(true)

            fakeSceneDataSource.unpause(expectedScene = Scenes.Gone)
            transitionStateFlow.value = ObservableTransitionState.Idle(Scenes.Gone)
            runCurrent()
            verify(windowController, times(2)).setNotificationShadeFocusable(false)
        }

    @Test
    fun hydrateWindowController_setKeyguardShowing() =
        testScope.runTest {
            underTest.start()
            val notificationShadeWindowController = kosmos.notificationShadeWindowController
            val transitionStateFlow = prepareState(initialSceneKey = Scenes.Lockscreen)
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            verify(notificationShadeWindowController).setKeyguardShowing(true)

            emulateOverlayTransition(transitionStateFlow, Overlays.Bouncer)
            verify(notificationShadeWindowController, times(1)).setKeyguardShowing(true)

            emulateSceneTransition(transitionStateFlow, Scenes.Lockscreen)
            verify(notificationShadeWindowController, times(1)).setKeyguardShowing(true)

            emulateSceneTransition(transitionStateFlow, Scenes.Shade)
            verify(notificationShadeWindowController, times(1)).setKeyguardShowing(true)

            emulateSceneTransition(transitionStateFlow, Scenes.Lockscreen)
            verify(notificationShadeWindowController, times(1)).setKeyguardShowing(true)
        }

    @Test
    fun hydrateWindowController_setKeyguardOccluded() =
        testScope.runTest {
            underTest.start()
            val notificationShadeWindowController = kosmos.notificationShadeWindowController
            prepareState(initialSceneKey = Scenes.Lockscreen)
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            verify(notificationShadeWindowController, never()).setKeyguardOccluded(true)
            verify(notificationShadeWindowController, times(1)).setKeyguardOccluded(false)

            kosmos.keyguardOcclusionInteractor.setWmNotifiedShowWhenLockedActivityOnTop(
                true,
                mock(),
            )
            runCurrent()
            verify(notificationShadeWindowController, times(1)).setKeyguardOccluded(true)
            verify(notificationShadeWindowController, times(1)).setKeyguardOccluded(false)

            kosmos.keyguardOcclusionInteractor.setWmNotifiedShowWhenLockedActivityOnTop(false)
            runCurrent()
            verify(notificationShadeWindowController, times(1)).setKeyguardOccluded(true)
            verify(notificationShadeWindowController, times(2)).setKeyguardOccluded(false)
        }

    @Test
    fun hydrateInteractionState_whileLocked() =
        testScope.runTest {
            kosmos.disableDualShade()
            val transitionStateFlow = prepareState(initialSceneKey = Scenes.Lockscreen)
            underTest.start()
            runCurrent()
            verify(centralSurfaces).setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true)

            clearInvocations(centralSurfaces)
            emulateOverlayTransition(
                transitionStateFlow = transitionStateFlow,
                toOverlay = Overlays.Bouncer,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces)
                        .setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false)
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces).setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true)
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.Shade,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces)
                        .setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false)
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces).setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true)
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.QuickSettings,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )
        }

    @Test
    fun hydrateInteractionState_whileUnlocked() =
        testScope.runTest {
            kosmos.disableDualShade()
            val transitionStateFlow =
                prepareState(
                    authenticationMethod = AuthenticationMethodModel.Pin,
                    isDeviceUnlocked = true,
                    initialSceneKey = Scenes.Gone,
                )
            underTest.start()
            verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())

            clearInvocations(centralSurfaces)
            emulateOverlayTransition(
                transitionStateFlow = transitionStateFlow,
                toOverlay = Overlays.Bouncer,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.Shade,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.QuickSettings,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )
        }

    @Test
    fun hydrateInteractionState_dualShade_whileLocked() =
        testScope.runTest {
            kosmos.enableDualShade()
            val currentDesiredOverlays by collectLastValue(sceneInteractor.currentOverlays)
            val transitionStateFlow = prepareState(initialSceneKey = Scenes.Lockscreen)
            underTest.start()
            runCurrent()
            verify(centralSurfaces).setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true)
            assertThat(currentDesiredOverlays).isEmpty()

            clearInvocations(centralSurfaces)
            emulateOverlayTransition(
                transitionStateFlow = transitionStateFlow,
                toOverlay = Overlays.Bouncer,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces)
                        .setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false)
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces).setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true)
                },
            )

            clearInvocations(centralSurfaces)
            emulateOverlayTransition(
                transitionStateFlow = transitionStateFlow,
                toOverlay = Overlays.NotificationsShade,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces)
                        .setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false)
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces).setInteracting(StatusBarManager.WINDOW_STATUS_BAR, true)
                },
            )

            clearInvocations(centralSurfaces)
            emulateOverlayTransition(
                transitionStateFlow = transitionStateFlow,
                toOverlay = Overlays.QuickSettingsShade,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )
        }

    @Test
    fun hydrateInteractionState_dualShade_whileUnlocked() =
        testScope.runTest {
            kosmos.enableDualShade()
            val currentDesiredOverlays by collectLastValue(sceneInteractor.currentOverlays)
            val transitionStateFlow =
                prepareState(
                    authenticationMethod = AuthenticationMethodModel.Pin,
                    isDeviceUnlocked = true,
                    initialSceneKey = Scenes.Gone,
                )
            underTest.start()
            verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
            assertThat(currentDesiredOverlays).isEmpty()

            clearInvocations(centralSurfaces)
            emulateOverlayTransition(
                transitionStateFlow = transitionStateFlow,
                toOverlay = Overlays.Bouncer,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateOverlayTransition(
                transitionStateFlow = transitionStateFlow,
                toOverlay = Overlays.NotificationsShade,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateSceneTransition(
                transitionStateFlow = transitionStateFlow,
                toScene = Scenes.Lockscreen,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )

            clearInvocations(centralSurfaces)
            emulateOverlayTransition(
                transitionStateFlow = transitionStateFlow,
                toOverlay = Overlays.QuickSettingsShade,
                verifyBeforeTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyDuringTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
                verifyAfterTransition = {
                    verify(centralSurfaces, never()).setInteracting(anyInt(), anyBoolean())
                },
            )
        }

    @Test
    fun respondToFalsingDetections() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            val transitionStateFlow = prepareState()
            underTest.start()
            runCurrent()
            emulateOverlayTransition(
                transitionStateFlow = transitionStateFlow,
                toOverlay = Overlays.Bouncer,
            )
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).contains(Overlays.Bouncer)

            kosmos.falsingManager.sendFalsingBelief()
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).isEmpty()
        }

    @Test
    fun handleBouncerOverscroll() =
        testScope.runTest {
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            val transitionStateFlow = prepareState()
            underTest.start()
            runCurrent()
            emulateOverlayTransition(
                transitionStateFlow = transitionStateFlow,
                toOverlay = Overlays.Bouncer,
            )
            assertThat(currentOverlays).contains(Overlays.Bouncer)

            transitionStateFlow.value =
                ObservableTransitionState.Transition.hideOverlay(
                    overlay = Overlays.Bouncer,
                    toScene = Scenes.Lockscreen,
                    currentOverlays = flowOf(setOf(Overlays.Bouncer)),
                    progress = flowOf(-0.4f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(true),
                )
            runCurrent()

            assertThat(kosmos.fakeDeviceEntryFaceAuthRepository.isAuthRunning.value).isTrue()
        }

    @Test
    fun switchToLockscreen_whenShadeBecomesNotTouchable() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            val isShadeTouchable by collectLastValue(kosmos.shadeInteractor.isShadeTouchable)
            val transitionStateFlow = prepareState()
            underTest.start()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            // Flung to bouncer, 90% of the way there:
            transitionStateFlow.value =
                ObservableTransitionState.Transition.showOverlay(
                    overlay = Overlays.Bouncer,
                    fromScene = Scenes.Lockscreen,
                    currentOverlays = flowOf(setOf(Overlays.Bouncer)),
                    progress = flowOf(0.9f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(false),
                )
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            kosmos.fakePowerRepository.updateWakefulness(WakefulnessState.ASLEEP)
            runCurrent()
            assertThat(isShadeTouchable).isFalse()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
        }

    @Test
    fun switchToGone_extendUnlock() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            prepareState(
                initialSceneKey = Scenes.Lockscreen,
                initialOverlays = setOf(Overlays.Bouncer),
                authenticationMethod = AuthenticationMethodModel.Pin,
            )
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).contains(Overlays.Bouncer)

            underTest.start()
            fakeTrustRepository.setCurrentUserTrusted(true)

            assertThat(currentScene).isEqualTo(Scenes.Gone)
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
            assertThat(uiEventLoggerFake[0].eventId)
                .isEqualTo(BouncerUiEvent.BOUNCER_DISMISS_EXTENDED_ACCESS.id)
            assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
        }

    @Test
    fun switchToGone_whenKeyguardBecomesDisabled() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            underTest.start()

            kosmos.keyguardEnabledInteractor.notifyKeyguardEnabled(false)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun switchToGone_whenKeyguardBecomesDisabled_whenOnShadeScene() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState(initialSceneKey = Scenes.Shade)
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            underTest.start()

            kosmos.keyguardEnabledInteractor.notifyKeyguardEnabled(false)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun doesNotSwitchToGone_whenKeyguardBecomesDisabled_whenInLockdownMode() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            underTest.start()

            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
            kosmos.fakeBiometricSettingsRepository.setIsUserInLockdown(true)
            kosmos.keyguardEnabledInteractor.notifyKeyguardEnabled(false)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun doesNotSwitchToGone_whenKeyguardBecomesDisabled_whenDeviceEntered() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState(isDeviceUnlocked = true, initialSceneKey = Scenes.Gone)
            assertThat(currentScene).isEqualTo(Scenes.Gone)
            assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isTrue()
            underTest.start()
            runCurrent()
            sceneInteractor.changeScene(Scenes.Shade, "")
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(kosmos.deviceEntryInteractor.isDeviceEntered.value).isTrue()

            kosmos.keyguardEnabledInteractor.notifyKeyguardEnabled(false)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Shade)
        }

    @Test
    fun switchToLockscreen_whenKeyguardBecomesEnabled_afterHidingWhenDisabled() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            kosmos.keyguardEnabledInteractor.notifyKeyguardEnabled(false)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Gone)

            kosmos.keyguardEnabledInteractor.notifyKeyguardEnabled(true)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun doesNotSwitchToLockscreen_whenKeyguardBecomesEnabled_ifAuthMethodBecameInsecure() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            underTest.start()
            kosmos.keyguardEnabledInteractor.notifyKeyguardEnabled(false)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Gone)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            runCurrent()

            kosmos.keyguardEnabledInteractor.notifyKeyguardEnabled(true)
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun notifyKeyguardDismissCallbacks_whenUnlockingFromBouncer_onDismissSucceeded() =
        testScope.runTest {
            val currentSceneKey by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            prepareState(
                authenticationMethod = AuthenticationMethodModel.Pin,
                isDeviceUnlocked = false,
                initialSceneKey = Scenes.Lockscreen,
                initialOverlays = setOf(Overlays.Bouncer),
            )
            assertThat(currentSceneKey).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).contains(Overlays.Bouncer)
            underTest.start()

            // run all pending dismiss succeeded/cancelled calls from setup:
            runCurrent()
            kosmos.fakeExecutor.runAllReady()

            val dismissCallback: IKeyguardDismissCallback = mock()
            kosmos.dismissCallbackRegistry.addCallback(dismissCallback)

            updateFingerprintAuthStatus(isSuccess = true)
            runCurrent()
            kosmos.fakeExecutor.runAllReady()

            verify(dismissCallback).onDismissSucceeded()
        }

    @Test
    fun notifyKeyguardDismissCallbacks_whenLeavingBouncer_onDismissCancelled() =
        testScope.runTest {
            val isUnlocked by collectLastValue(kosmos.deviceEntryInteractor.isUnlocked)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            prepareState()
            underTest.start()
            runCurrent()

            // run all pending dismiss succeeded/cancelled calls from setup:
            kosmos.fakeExecutor.runAllReady()

            val dismissCallback: IKeyguardDismissCallback = mock()
            kosmos.dismissCallbackRegistry.addCallback(dismissCallback)

            // Switch to bouncer:
            sceneInteractor.showOverlay(Overlays.Bouncer, "")
            assertThat(currentOverlays).contains(Overlays.Bouncer)
            runCurrent()

            // Return to lockscreen when isUnlocked=false:
            sceneInteractor.hideOverlay(Overlays.Bouncer, "")
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
            assertThat(isUnlocked).isFalse()
            runCurrent()
            kosmos.fakeExecutor.runAllReady()

            verify(dismissCallback).onDismissCancelled()
        }

    @Test
    fun refreshLockscreenEnabled() =
        testScope.runTest {
            val transitionState =
                prepareState(isDeviceUnlocked = true, initialSceneKey = Scenes.Gone)
            underTest.start()
            val isLockscreenEnabled by
                collectLastValue(kosmos.deviceEntryInteractor.isLockscreenEnabled)
            assertThat(isLockscreenEnabled).isTrue()

            kosmos.fakeDeviceEntryRepository.setPendingLockscreenEnabled(false)
            runCurrent()
            // Pending value didn't propagate yet.
            assertThat(isLockscreenEnabled).isTrue()

            // Starting a transition to Lockscreen should refresh the value, causing the pending
            // value
            // to propagate to the real flow:
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Gone,
                    toScene = Scenes.Lockscreen,
                    currentScene = flowOf(Scenes.Gone),
                    progress = flowOf(0.1f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            runCurrent()
            assertThat(isLockscreenEnabled).isFalse()

            kosmos.fakeDeviceEntryRepository.setPendingLockscreenEnabled(true)
            runCurrent()
            // Pending value didn't propagate yet.
            assertThat(isLockscreenEnabled).isFalse()
            transitionState.value = ObservableTransitionState.Idle(Scenes.Gone)
            runCurrent()
            assertThat(isLockscreenEnabled).isFalse()

            // Starting another transition to Lockscreen should refresh the value, causing the
            // pending
            // value to propagate to the real flow:
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Gone,
                    toScene = Scenes.Lockscreen,
                    currentScene = flowOf(Scenes.Gone),
                    progress = flowOf(0.1f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            runCurrent()
            assertThat(isLockscreenEnabled).isTrue()
        }

    @Test
    fun stayOnLockscreen_whenDozingStarted() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            underTest.start()

            // Stay on Lockscreen when dozing and dreaming
            dozeInteractor.setIsDozing(true)
            keyguardInteractor.setDreaming(true)
            kosmos.fakeKeyguardRepository.setDreamingWithOverlay(false)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun switchFromLockscreenToDream_whenDreamStarted() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            underTest.start()

            powerInteractor.setAwakeForTest()
            keyguardInteractor.setDreaming(true)
            // Move past initial delay with [KeyguardInteractor#isAbleToDream]
            advanceTimeBy(600L)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Dream)
        }

    @Test
    fun switchFromDreamToLockscreen_whenLockedAndDreamStopped() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState(initialSceneKey = Scenes.Dream)
            underTest.start()
            advanceTimeBy(KeyguardInteractor.IS_ABLE_TO_DREAM_DELAY_MS)
            runCurrent()
            keyguardInteractor.setDreaming(true)
            advanceTimeBy(KeyguardInteractor.IS_ABLE_TO_DREAM_DELAY_MS)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Dream)

            keyguardInteractor.setDreaming(false)
            advanceTimeBy(KeyguardInteractor.IS_ABLE_TO_DREAM_DELAY_MS)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun switchFromDreamToGone_whenUnlockedAndDreamStopped() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            prepareState(initialSceneKey = Scenes.Dream, isDeviceUnlocked = true)
            underTest.start()
            advanceTimeBy(KeyguardInteractor.IS_ABLE_TO_DREAM_DELAY_MS)
            runCurrent()
            keyguardInteractor.setDreaming(true)
            advanceTimeBy(KeyguardInteractor.IS_ABLE_TO_DREAM_DELAY_MS)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Dream)

            keyguardInteractor.setDreaming(false)
            advanceTimeBy(KeyguardInteractor.IS_ABLE_TO_DREAM_DELAY_MS)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun replacesLockscreenSceneOnBackStack_whenUnlockdViaAlternateBouncer_fromShade() =
        testScope.runTest {
            val transitionState =
                prepareState(
                    isDeviceUnlocked = false,
                    initialSceneKey = Scenes.Lockscreen,
                    authenticationMethod = AuthenticationMethodModel.Pin,
                )
            underTest.start()

            val isUnlocked by
                collectLastValue(
                    kosmos.deviceUnlockedInteractor.deviceUnlockStatus.map { it.isUnlocked }
                )
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val backStack by collectLastValue(sceneBackInteractor.backStack)
            val isAlternateBouncerVisible by
                collectLastValue(kosmos.alternateBouncerInteractor.isVisible)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(isAlternateBouncerVisible).isFalse()

            // Change to shade.
            sceneInteractor.changeScene(Scenes.Shade, "")
            transitionState.value = ObservableTransitionState.Idle(Scenes.Shade)
            runCurrent()
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(backStack?.asIterable()?.first()).isEqualTo(Scenes.Lockscreen)
            assertThat(isAlternateBouncerVisible).isFalse()

            // Show the alternate bouncer.
            kosmos.alternateBouncerInteractor.forceShow()
            kosmos.sysuiStatusBarStateController.leaveOpen = true // leave shade open
            runCurrent()
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(backStack?.asIterable()?.first()).isEqualTo(Scenes.Lockscreen)
            assertThat(isAlternateBouncerVisible).isTrue()

            // Trigger a fingerprint unlock.
            kosmos.deviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()
            assertThat(isUnlocked).isTrue()
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(backStack?.asIterable()?.first()).isEqualTo(Scenes.Gone)
            assertThat(isAlternateBouncerVisible).isFalse()
        }

    @Test
    fun handleDeviceUnlockStatus_deviceLockedWhileOnDream_stayOnDream() =
        testScope.runTest {
            val transitionState =
                prepareState(
                    isDeviceUnlocked = false,
                    initialSceneKey = Scenes.Lockscreen,
                    authenticationMethod = AuthenticationMethodModel.Pin,
                )
            underTest.start()

            val isUnlocked by
                collectLastValue(
                    kosmos.deviceUnlockedInteractor.deviceUnlockStatus.map { it.isUnlocked }
                )
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            // Unlock device.
            kosmos.deviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            assertThat(isUnlocked).isTrue()
            assertThat(currentScene).isEqualTo(Scenes.Gone)

            // Change to Dream.
            sceneInteractor.changeScene(Scenes.Dream, "test")
            transitionState.value = ObservableTransitionState.Idle(Scenes.Dream)
            runCurrent()
            assertThat(isUnlocked).isTrue()
            assertThat(currentScene).isEqualTo(Scenes.Dream)

            // Lock device, and verify stay on dream.
            kosmos.fakeDeviceEntryRepository.deviceUnlockStatus.value =
                DeviceUnlockStatus(isUnlocked = false, deviceUnlockSource = null)
            runCurrent()
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Dream)
        }

    @Test
    fun handleDeviceUnlockStatus_deviceLockedWhileOnCommunal_stayOnCommunal() =
        testScope.runTest {
            val transitionState =
                prepareState(
                    isDeviceUnlocked = false,
                    initialSceneKey = Scenes.Lockscreen,
                    authenticationMethod = AuthenticationMethodModel.Pin,
                )
            underTest.start()

            val isUnlocked by
                collectLastValue(
                    kosmos.deviceUnlockedInteractor.deviceUnlockStatus.map { it.isUnlocked }
                )
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            // Unlock device.
            kosmos.deviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            assertThat(isUnlocked).isTrue()
            assertThat(currentScene).isEqualTo(Scenes.Gone)

            // Change to Communal.
            sceneInteractor.changeScene(Scenes.Communal, "test")
            transitionState.value = ObservableTransitionState.Idle(Scenes.Communal)
            runCurrent()
            assertThat(isUnlocked).isTrue()
            assertThat(currentScene).isEqualTo(Scenes.Communal)

            // Lock device, and verify stay on Communal.
            kosmos.fakeDeviceEntryRepository.deviceUnlockStatus.value =
                DeviceUnlockStatus(isUnlocked = false, deviceUnlockSource = null)
            runCurrent()
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Communal)
        }

    @Test
    fun handleDeviceUnlockStatus_returnsToLsFromBouncer_whenGoesToSleep() =
        testScope.runTest {
            val authMethod by collectLastValue(kosmos.authenticationInteractor.authenticationMethod)
            val isUnlocked by
                collectLastValue(
                    kosmos.deviceUnlockedInteractor.deviceUnlockStatus.map { it.isUnlocked }
                )
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            val isAwake by collectLastValue(powerInteractor.isAwake)
            prepareState(
                isDeviceUnlocked = false,
                initialSceneKey = Scenes.Lockscreen,
                authenticationMethod = AuthenticationMethodModel.Pin,
                startsAwake = true,
            )
            underTest.start()
            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.Pin)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
            assertThat(isAwake).isTrue()

            sceneInteractor.showOverlay(Overlays.Bouncer, "")
            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.Pin)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).contains(Overlays.Bouncer)
            assertThat(isAwake).isTrue()

            powerInteractor.setAsleepForTest()
            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.Pin)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
            assertThat(isAwake).isFalse()
        }

    @Test
    fun hidesBouncer_whenAuthMethodChangesToNonSecure() =
        testScope.runTest {
            val authMethod by collectLastValue(kosmos.authenticationInteractor.authenticationMethod)
            val currentScene by collectLastValue(kosmos.sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(kosmos.sceneInteractor.currentOverlays)
            prepareState(
                authenticationMethod = AuthenticationMethodModel.Password,
                initialSceneKey = Scenes.Lockscreen,
            )
            underTest.start()
            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.Password)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)

            sceneInteractor.showOverlay(Overlays.Bouncer, "")
            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.Password)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).contains(Overlays.Bouncer)

            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            runCurrent()

            assertThat(authMethod).isEqualTo(AuthenticationMethodModel.None)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).doesNotContain(Overlays.Bouncer)
        }

    @Test
    fun replacesLockscreenSceneOnBackStack_whenFaceUnlocked_fromShade_noAlternateBouncer() =
        testScope.runTest {
            val transitionState =
                prepareState(
                    isDeviceUnlocked = false,
                    initialSceneKey = Scenes.Lockscreen,
                    authenticationMethod = AuthenticationMethodModel.Pin,
                )
            underTest.start()

            val isUnlocked by
                collectLastValue(
                    kosmos.deviceUnlockedInteractor.deviceUnlockStatus.map { it.isUnlocked }
                )
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val backStack by collectLastValue(sceneBackInteractor.backStack)
            val isAlternateBouncerVisible by
                collectLastValue(kosmos.alternateBouncerInteractor.isVisible)
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(isAlternateBouncerVisible).isFalse()

            // Change to shade.
            sceneInteractor.changeScene(Scenes.Shade, "")
            transitionState.value = ObservableTransitionState.Idle(Scenes.Shade)
            runCurrent()
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(backStack?.asIterable()?.first()).isEqualTo(Scenes.Lockscreen)
            assertThat(isAlternateBouncerVisible).isFalse()

            // Show the alternate bouncer.
            kosmos.alternateBouncerInteractor.forceShow()
            kosmos.sysuiStatusBarStateController.leaveOpen = true // leave shade open
            runCurrent()
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(backStack?.asIterable()?.first()).isEqualTo(Scenes.Lockscreen)
            assertThat(isAlternateBouncerVisible).isTrue()

            // Simulate race condition by hiding the alternate bouncer *before* the face unlock:
            kosmos.alternateBouncerInteractor.hide()
            runCurrent()
            assertThat(isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(backStack?.asIterable()?.first()).isEqualTo(Scenes.Lockscreen)
            assertThat(isAlternateBouncerVisible).isFalse()

            // Trigger a face unlock.
            updateFaceAuthStatus(isSuccess = true)
            runCurrent()
            assertThat(isUnlocked).isTrue()
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(backStack?.asIterable()?.first()).isEqualTo(Scenes.Gone)
            assertThat(isAlternateBouncerVisible).isFalse()
        }

    @Test
    fun handleDisableFlags_singleShade() =
        kosmos.runTest {
            underTest.start()
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            disableDualShade()
            runCurrent()
            sceneInteractor.changeScene(Scenes.Shade, "reason")
            assertThat(currentScene).isEqualTo(Scenes.Shade)

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(disable2 = StatusBarManager.DISABLE2_NOTIFICATION_SHADE)
            runCurrent()

            assertThat(currentScene).isNotEqualTo(Scenes.Shade)
        }

    @Test
    fun handleDisableFlags_dualShade() =
        kosmos.runTest {
            underTest.start()
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            enableDualShade()
            runCurrent()
            sceneInteractor.showOverlay(Overlays.NotificationsShade, "reason")
            assertThat(currentOverlays).contains(Overlays.NotificationsShade)

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(disable2 = StatusBarManager.DISABLE2_NOTIFICATION_SHADE)
            runCurrent()

            assertThat(currentOverlays).isEmpty()
        }

    @Test
    fun hydrateActivityTransitionAnimationState() =
        kosmos.runTest {
            underTest.start()

            val isVisible by collectLastValue(sceneInteractor.isVisible)
            assertThat(isVisible).isTrue()

            sceneInteractor.setVisible(false, "reason")
            assertThat(isVisible).isFalse()

            val argumentCaptor = argumentCaptor<ActivityTransitionAnimator.Listener>()
            verify(mockActivityTransitionAnimator).addListener(argumentCaptor.capture())

            val listeners = argumentCaptor.allValues
            listeners.forEach { it.onTransitionAnimationStart() }
            assertThat(isVisible).isTrue()
            listeners.forEach { it.onTransitionAnimationEnd() }
            assertThat(isVisible).isFalse()
        }

    @Test
    fun deviceLocks_whenNoLongerTrusted_whileDeviceNotEntered() =
        testScope.runTest {
            prepareState(isDeviceUnlocked = true, initialSceneKey = Scenes.Gone)
            underTest.start()

            val isDeviceEntered by collectLastValue(kosmos.deviceEntryInteractor.isDeviceEntered)
            val deviceUnlockStatus by
                collectLastValue(kosmos.deviceUnlockedInteractor.deviceUnlockStatus)
            val currentScene by collectLastValue(kosmos.sceneInteractor.currentScene)
            assertThat(isDeviceEntered).isTrue()
            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            assertThat(currentScene).isEqualTo(Scenes.Gone)
            kosmos.fakeTrustRepository.setCurrentUserTrusted(true)
            kosmos.sceneInteractor.changeScene(Scenes.Lockscreen, "reason")
            runCurrent()
            assertThat(isDeviceEntered).isFalse()
            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            kosmos.fakeTrustRepository.setCurrentUserTrusted(false)
            runCurrent()

            assertThat(isDeviceEntered).isFalse()
            assertThat(deviceUnlockStatus?.isUnlocked).isFalse()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    private fun TestScope.emulateSceneTransition(
        transitionStateFlow: MutableStateFlow<ObservableTransitionState>,
        toScene: SceneKey,
        verifyBeforeTransition: (() -> Unit)? = null,
        verifyDuringTransition: (() -> Unit)? = null,
        verifyAfterTransition: (() -> Unit)? = null,
    ) {
        val fromScene = sceneInteractor.currentScene.value
        val fromOverlays = sceneInteractor.currentOverlays.value
        sceneInteractor.changeScene(toScene, "reason")
        runCurrent()
        verifyBeforeTransition?.invoke()

        transitionStateFlow.value =
            if (fromOverlays.isEmpty()) {
                // Regular scene-to-scene transition.
                ObservableTransitionState.Transition.ChangeScene(
                    fromScene = fromScene,
                    toScene = toScene,
                    currentScene = flowOf(fromScene),
                    currentOverlays = fromOverlays,
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(true),
                    previewProgress = flowOf(0f),
                    isInPreviewStage = flowOf(false),
                )
            } else {
                // An overlay is present; hide it.
                ObservableTransitionState.Transition.ShowOrHideOverlay(
                    overlay = fromOverlays.first(),
                    fromContent = fromOverlays.first(),
                    toContent = toScene,
                    currentScene = fromScene,
                    currentOverlays = sceneInteractor.currentOverlays,
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(true),
                    previewProgress = flowOf(0f),
                    isInPreviewStage = flowOf(false),
                )
            }
        runCurrent()
        verifyDuringTransition?.invoke()

        transitionStateFlow.value = ObservableTransitionState.Idle(currentScene = toScene)
        runCurrent()
        verifyAfterTransition?.invoke()
    }

    private fun TestScope.emulateOverlayTransition(
        transitionStateFlow: MutableStateFlow<ObservableTransitionState>,
        toOverlay: OverlayKey,
        verifyBeforeTransition: (() -> Unit)? = null,
        verifyDuringTransition: (() -> Unit)? = null,
        verifyAfterTransition: (() -> Unit)? = null,
    ) {
        val fromScene = sceneInteractor.currentScene.value
        val fromOverlays = sceneInteractor.currentOverlays.value
        sceneInteractor.showOverlay(toOverlay, "reason")
        runCurrent()
        verifyBeforeTransition?.invoke()

        transitionStateFlow.value =
            if (fromOverlays.isEmpty()) {
                // Show a new overlay.
                ObservableTransitionState.Transition.ShowOrHideOverlay(
                    overlay = toOverlay,
                    fromContent = fromScene,
                    toContent = toOverlay,
                    currentScene = fromScene,
                    currentOverlays = sceneInteractor.currentOverlays,
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(true),
                    previewProgress = flowOf(0f),
                    isInPreviewStage = flowOf(false),
                )
            } else {
                // Overlay-to-overlay transition.
                ObservableTransitionState.Transition.ReplaceOverlay(
                    fromOverlay = fromOverlays.first(),
                    toOverlay = toOverlay,
                    currentScene = fromScene,
                    currentOverlays = sceneInteractor.currentOverlays,
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(true),
                    previewProgress = flowOf(0f),
                    isInPreviewStage = flowOf(false),
                )
            }
        runCurrent()
        verifyDuringTransition?.invoke()

        transitionStateFlow.value =
            ObservableTransitionState.Idle(
                currentScene = fromScene,
                currentOverlays = setOf(toOverlay),
            )
        runCurrent()
        verifyAfterTransition?.invoke()
    }

    private fun TestScope.prepareState(
        isDeviceUnlocked: Boolean = false,
        isBypassEnabled: Boolean = false,
        initialSceneKey: SceneKey? = null,
        initialOverlays: Set<OverlayKey> = emptySet(),
        authenticationMethod: AuthenticationMethodModel? = null,
        isLockscreenEnabled: Boolean = true,
        startsAwake: Boolean = true,
        isDeviceProvisioned: Boolean = true,
        isInteractive: Boolean = true,
    ): MutableStateFlow<ObservableTransitionState> {
        if (isDeviceUnlocked) {
            kosmos.deviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
        }

        check(initialSceneKey != Scenes.Gone || isDeviceUnlocked) {
            "Cannot start on the Gone scene and have the device be locked at the same time."
        }

        kosmos.fakeDeviceEntryRepository.setBypassEnabled(isBypassEnabled)
        authenticationMethod?.let {
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(authenticationMethod)
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(
                isLockscreenEnabled = isLockscreenEnabled
            )
        }
        runCurrent()
        val transitionStateFlow =
            MutableStateFlow<ObservableTransitionState>(
                ObservableTransitionState.Idle(Scenes.Lockscreen, initialOverlays)
            )
        sceneInteractor.setTransitionState(transitionStateFlow)
        initialSceneKey?.let {
            if (isDeviceUnlocked && initialSceneKey != Scenes.Gone) {
                // Pass through the Gone scene to populate device entry state properly.
                transitionStateFlow.value = ObservableTransitionState.Idle(Scenes.Gone)
                sceneInteractor.changeScene(Scenes.Gone, "prepareState, passing through Gone scene")
                runCurrent()
            }

            transitionStateFlow.value = ObservableTransitionState.Idle(it, initialOverlays)
            sceneInteractor.changeScene(it, "prepareState, initialSceneKey isn't null")
        }
        for (overlay in initialOverlays) {
            sceneInteractor.instantlyShowOverlay(
                overlay,
                "prepareState, initialOverlays isn't empty",
            )
        }
        if (startsAwake) {
            powerInteractor.setAwakeForTest()
        } else {
            powerInteractor.setAsleepForTest()
        }
        kosmos.fakePowerRepository.setInteractive(isInteractive)

        kosmos.fakeDeviceProvisioningRepository.setDeviceProvisioned(isDeviceProvisioned)

        runCurrent()

        return transitionStateFlow
    }

    private fun buildNotificationRows(isPinned: Boolean = false): List<HeadsUpRowRepository> =
        listOf(
            fakeHeadsUpRowRepository(key = "0", isPinned = isPinned),
            fakeHeadsUpRowRepository(key = "1", isPinned = isPinned),
            fakeHeadsUpRowRepository(key = "2", isPinned = isPinned),
            fakeHeadsUpRowRepository(key = "3", isPinned = isPinned),
        )

    private fun fakeHeadsUpRowRepository(key: String, isPinned: Boolean) =
        FakeHeadsUpRowRepository(key = key, elementKey = Any(), isPinned = isPinned)

    private fun setFingerprintSensorType(fingerprintSensorType: FingerprintSensorType) {
        kosmos.fingerprintPropertyRepository.setProperties(
            sensorId = 0,
            strength = SensorStrength.STRONG,
            sensorType = fingerprintSensorType,
            sensorLocations = mapOf(),
        )
        kosmos.biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
    }

    private fun setFaceEnrolled() {
        kosmos.biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
    }

    private fun TestScope.allowHapticsOnSfps(
        isPowerButtonDown: Boolean = false,
        lastPowerPress: Long = 10000,
    ) {
        kosmos.fakeKeyEventRepository.setPowerButtonDown(isPowerButtonDown)

        kosmos.powerRepository.updateWakefulness(
            WakefulnessState.AWAKE,
            WakeSleepReason.POWER_BUTTON,
            WakeSleepReason.POWER_BUTTON,
            powerButtonLaunchGestureTriggered = false,
        )

        advanceTimeBy(lastPowerPress)
        runCurrent()
    }

    private fun TestScope.setupBiometricAuth(
        hasSfps: Boolean = false,
        hasUdfps: Boolean = false,
        hasFace: Boolean = false,
    ) {
        if (hasSfps) {
            setFingerprintSensorType(FingerprintSensorType.POWER_BUTTON)
        }

        if (hasUdfps) {
            setFingerprintSensorType(FingerprintSensorType.UDFPS_ULTRASONIC)
        }

        if (hasFace) {
            setFaceEnrolled()
        }

        prepareState(
            authenticationMethod = AuthenticationMethodModel.Pin,
            isDeviceUnlocked = false,
            initialSceneKey = Scenes.Lockscreen,
        )
    }

    private fun updateFingerprintAuthStatus(isSuccess: Boolean) {
        if (isSuccess) {
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
        } else {
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                FailFingerprintAuthenticationStatus
            )
        }
    }

    private fun updateFaceAuthStatus(isSuccess: Boolean) {
        with(kosmos.fakeDeviceEntryFaceAuthRepository) {
            isAuthenticated.value = isSuccess
            setAuthenticationStatus(
                if (isSuccess) {
                    SuccessFaceAuthenticationStatus(
                        successResult = Mockito.mock(FaceManager.AuthenticationResult::class.java)
                    )
                } else {
                    FailedFaceAuthenticationStatus()
                }
            )
        }
    }
}
