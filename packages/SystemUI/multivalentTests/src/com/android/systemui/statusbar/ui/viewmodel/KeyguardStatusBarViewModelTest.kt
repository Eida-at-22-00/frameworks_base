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

package com.android.systemui.statusbar.ui.viewmodel

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.data.repository.sceneContainerRepository
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.domain.interactor.keyguardStatusBarInteractor
import com.android.systemui.statusbar.headsup.shared.StatusBarNoHunBehavior
import com.android.systemui.statusbar.notification.data.repository.FakeHeadsUpRowRepository
import com.android.systemui.statusbar.notification.stack.data.repository.headsUpNotificationRepository
import com.android.systemui.statusbar.notification.stack.domain.interactor.headsUpNotificationInteractor
import com.android.systemui.statusbar.policy.batteryController
import com.android.systemui.statusbar.policy.fake
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class KeyguardStatusBarViewModelTest(flags: FlagsParameterization) : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val faceAuthRepository by lazy { kosmos.fakeDeviceEntryFaceAuthRepository }
    private val headsUpRepository by lazy { kosmos.headsUpNotificationRepository }
    private val headsUpNotificationInteractor by lazy { kosmos.headsUpNotificationInteractor }
    private val keyguardRepository by lazy { kosmos.fakeKeyguardRepository }
    private val keyguardTransitionRepository by lazy { kosmos.fakeKeyguardTransitionRepository }
    private val keyguardInteractor by lazy { kosmos.keyguardInteractor }
    private val keyguardStatusBarInteractor by lazy { kosmos.keyguardStatusBarInteractor }
    private val batteryController = kosmos.batteryController

    lateinit var underTest: KeyguardStatusBarViewModel

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Before
    fun setup() {
        underTest =
            KeyguardStatusBarViewModel(
                testScope.backgroundScope,
                headsUpNotificationInteractor,
                kosmos.sceneInteractor,
                keyguardInteractor,
                keyguardStatusBarInteractor,
                batteryController,
            )
    }

    @Test
    fun isVisible_lockscreen_true() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isVisible)
            kosmos.sceneContainerRepository.snapToScene(Scenes.Lockscreen)

            assertThat(latest).isTrue()
        }

    @Test
    fun isVisible_dozing_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isVisible)
            kosmos.sceneContainerRepository.snapToScene(Scenes.Lockscreen)

            keyguardRepository.setIsDozing(true)

            assertThat(latest).isFalse()
        }

    @Test
    fun isVisible_sceneShade_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isVisible)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Shade)

            assertThat(latest).isFalse()
        }

    @Test
    fun isVisible_notificationsShadeOverlay_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isVisible)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Lockscreen)
            kosmos.sceneContainerRepository.showOverlay(Overlays.NotificationsShade)
            runCurrent()

            assertThat(latest).isFalse()
        }

    @Test
    fun isVisible_quickSettingsShadeOverlay_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isVisible)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Lockscreen)
            kosmos.sceneContainerRepository.showOverlay(Overlays.QuickSettingsShade)
            runCurrent()

            assertThat(latest).isFalse()
        }

    @Test
    fun isVisible_sceneBouncer_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isVisible)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Lockscreen)
            kosmos.sceneContainerRepository.showOverlay(Overlays.Bouncer)

            assertThat(latest).isFalse()
        }

    @Test
    @EnableSceneContainer
    @DisableFlags(StatusBarNoHunBehavior.FLAG_NAME)
    fun isVisible_headsUpShown_noHunBehaviorFlagOff_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isVisible)

            // WHEN HUN displayed on the bypass lock screen
            headsUpRepository.setNotifications(FakeHeadsUpRowRepository("key 0", isPinned = true))
            keyguardTransitionRepository.emitInitialStepsFromOff(
                KeyguardState.LOCKSCREEN,
                testSetup = true,
            )
            kosmos.sceneContainerRepository.snapToScene(Scenes.Lockscreen)
            faceAuthRepository.isBypassEnabled.value = true

            // THEN KeyguardStatusBar is NOT visible to make space for HeadsUpStatusBar
            assertThat(latest).isFalse()
        }

    @Test
    @EnableSceneContainer
    @EnableFlags(StatusBarNoHunBehavior.FLAG_NAME)
    fun isVisible_headsUpShown_noHunBehaviorFlagOn_true() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isVisible)

            // WHEN HUN displayed on the bypass lock screen
            headsUpRepository.setNotifications(FakeHeadsUpRowRepository("key 0", isPinned = true))
            keyguardTransitionRepository.emitInitialStepsFromOff(
                KeyguardState.LOCKSCREEN,
                testSetup = true,
            )
            kosmos.sceneContainerRepository.snapToScene(Scenes.Lockscreen)
            faceAuthRepository.isBypassEnabled.value = true

            // THEN KeyguardStatusBar is still visible because StatusBarNoHunBehavior is enabled
            assertThat(latest).isTrue()
        }

    @Test
    fun isVisible_sceneLockscreen_andNotDozing_andNotShowingHeadsUpStatusBar_true() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isVisible)

            kosmos.sceneContainerRepository.snapToScene(Scenes.Lockscreen)
            keyguardRepository.setIsDozing(false)

            assertThat(latest).isTrue()
        }

    @Test
    fun isBatteryCharging_matchesCallback() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isBatteryCharging)
            runCurrent()

            batteryController.fake._level = 2
            batteryController.fake._isPluggedIn = true

            assertThat(latest).isTrue()

            batteryController.fake._isPluggedIn = false

            assertThat(latest).isFalse()
        }

    @Test
    fun isBatteryCharging_unregistersWhenNotListening() =
        testScope.runTest {
            val job = underTest.isBatteryCharging.launchIn(this)
            runCurrent()

            job.cancel()
            runCurrent()

            assertThat(batteryController.fake.listeners).isEmpty()
        }
}
