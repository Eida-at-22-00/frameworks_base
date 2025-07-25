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

package com.android.systemui.qs.ui.viewmodel

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.AuthenticationResult
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.qs.composefragment.dagger.usingMediaInComposeFragment
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.domain.startable.sceneContainerStartable
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.enableDualShade
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimBounds
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimShape
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.notificationScrollViewModel
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
@EnableSceneContainer
class QuickSettingsShadeOverlayContentViewModelTest : SysuiTestCase() {

    private val kosmos =
        testKosmos().apply {
            usingMediaInComposeFragment = false // This is not for the compose fragment
        }
    private val testScope = kosmos.testScope
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val underTest by lazy { kosmos.quickSettingsShadeOverlayContentViewModel }

    @Before
    fun setUp() {
        kosmos.sceneContainerStartable.start()
        kosmos.enableDualShade()
        kosmos.runCurrent()
        underTest.activateIn(testScope)
    }

    @Test
    fun onScrimClicked_hidesShade() =
        testScope.runTest {
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            sceneInteractor.showOverlay(Overlays.QuickSettingsShade, "test")
            assertThat(currentOverlays).contains(Overlays.QuickSettingsShade)

            underTest.onScrimClicked()

            assertThat(currentOverlays).doesNotContain(Overlays.QuickSettingsShade)
        }

    @Test
    fun deviceLocked_hidesShade() =
        testScope.runTest {
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            unlockDevice()
            sceneInteractor.showOverlay(Overlays.QuickSettingsShade, "test")
            assertThat(currentOverlays).contains(Overlays.QuickSettingsShade)

            lockDevice()

            assertThat(currentOverlays).isEmpty()
        }

    @Test
    fun shadeNotTouchable_hidesShade() =
        testScope.runTest {
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            val isShadeTouchable by collectLastValue(kosmos.shadeInteractor.isShadeTouchable)
            assertThat(isShadeTouchable).isTrue()
            sceneInteractor.showOverlay(Overlays.QuickSettingsShade, "test")
            assertThat(currentOverlays).contains(Overlays.QuickSettingsShade)

            lockDevice()
            assertThat(isShadeTouchable).isFalse()
            assertThat(currentOverlays).doesNotContain(Overlays.QuickSettingsShade)
        }

    @Test
    fun onPanelShapeChanged() =
        testScope.runTest {
            var actual: ShadeScrimShape? = null
            kosmos.notificationScrollViewModel.setQsScrimShapeConsumer { shape -> actual = shape }

            val expected =
                ShadeScrimShape(
                    bounds = ShadeScrimBounds(left = 10f, top = 0f, right = 710f, bottom = 600f),
                    topRadius = 0,
                    bottomRadius = 100,
                )

            underTest.onPanelShapeChanged(expected)

            assertThat(actual).isEqualTo(expected)
        }

    private fun TestScope.lockDevice() {
        val currentScene by collectLastValue(sceneInteractor.currentScene)
        kosmos.powerInteractor.setAsleepForTest()
        runCurrent()

        assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
    }

    private suspend fun TestScope.unlockDevice() {
        val currentScene by collectLastValue(sceneInteractor.currentScene)
        kosmos.powerInteractor.setAwakeForTest()
        runCurrent()
        assertThat(
                kosmos.authenticationInteractor.authenticate(
                    FakeAuthenticationRepository.DEFAULT_PIN
                )
            )
            .isEqualTo(AuthenticationResult.SUCCEEDED)

        assertThat(currentScene).isEqualTo(Scenes.Gone)
    }
}
