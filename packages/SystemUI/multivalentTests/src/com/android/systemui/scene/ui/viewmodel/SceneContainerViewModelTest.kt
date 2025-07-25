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

package com.android.systemui.scene.ui.viewmodel

import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_OUTSIDE
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.DefaultEdgeDetector
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.fakeFalsingManager
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.fakeOverlaysByKeys
import com.android.systemui.scene.sceneContainerConfig
import com.android.systemui.scene.sceneContainerViewModelFactory
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.fakeSceneDataSource
import com.android.systemui.shade.domain.interactor.enableDualShade
import com.android.systemui.shade.domain.interactor.enableSingleShade
import com.android.systemui.shade.domain.interactor.enableSplitShade
import com.android.systemui.shade.domain.interactor.shadeMode
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.statusbar.data.repository.fakeRemoteInputRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableSceneContainer
class SceneContainerViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope by lazy { kosmos.testScope }
    private val falsingManager by lazy { kosmos.fakeFalsingManager }
    private val view = mock<View>()

    private lateinit var underTest: SceneContainerViewModel

    private lateinit var activationJob: Job
    private var motionEventHandler: SceneContainerViewModel.MotionEventHandler? = null

    @Before
    fun setUp() {
        underTest =
            kosmos.sceneContainerViewModelFactory.create(
                view,
                { motionEventHandler ->
                    this@SceneContainerViewModelTest.motionEventHandler = motionEventHandler
                },
            )
        activationJob = Job()
        underTest.activateIn(testScope, activationJob)
    }

    @Test
    fun activate_setsMotionEventHandler() =
        kosmos.runTest {
            runCurrent()
            assertThat(motionEventHandler).isNotNull()
        }

    @Test
    fun deactivate_clearsMotionEventHandler() =
        kosmos.runTest {
            activationJob.cancel()
            runCurrent()

            assertThat(motionEventHandler).isNull()
        }

    @Test
    fun isVisible() =
        kosmos.runTest {
            assertThat(underTest.isVisible).isTrue()

            sceneInteractor.setVisible(false, "reason")
            runCurrent()
            assertThat(underTest.isVisible).isFalse()

            sceneInteractor.setVisible(true, "reason")
            runCurrent()
            assertThat(underTest.isVisible).isTrue()
        }

    @Test
    fun sceneTransition() =
        kosmos.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            fakeSceneDataSource.changeScene(Scenes.Shade)

            assertThat(currentScene).isEqualTo(Scenes.Shade)
        }

    @Test
    fun canChangeScene_whenAllowed_switchingFromGone_returnsTrue() =
        kosmos.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            fakeSceneDataSource.changeScene(toScene = Scenes.Gone)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Gone)

            sceneContainerConfig.sceneKeys
                .filter { it != currentScene }
                .forEach { toScene ->
                    assertWithMessage("Scene $toScene incorrectly protected when allowed")
                        .that(underTest.canChangeScene(toScene = toScene))
                        .isTrue()
                }
        }

    @Test
    fun canChangeScene_whenAllowed_switchingFromLockscreen_returnsTrue() =
        kosmos.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            fakeSceneDataSource.changeScene(toScene = Scenes.Lockscreen)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            sceneContainerConfig.sceneKeys
                .filter { it != currentScene }
                .forEach { toScene ->
                    assertWithMessage("Scene $toScene incorrectly protected when allowed")
                        .that(underTest.canChangeScene(toScene = toScene))
                        .isTrue()
                }
        }

    @Test
    fun canChangeScene_whenNotAllowed_fromLockscreen_toFalsingProtectedScenes_returnsFalse() =
        kosmos.runTest {
            falsingManager.setIsFalseTouch(true)
            val currentScene by collectLastValue(underTest.currentScene)
            fakeSceneDataSource.changeScene(toScene = Scenes.Lockscreen)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            sceneContainerConfig.sceneKeys
                .filter { it != currentScene }
                .filter {
                    // Moving to the Communal and Dream scene is not currently falsing protected.
                    it != Scenes.Communal && it != Scenes.Dream
                }
                .forEach { toScene ->
                    assertWithMessage("Protected scene $toScene not properly protected")
                        .that(underTest.canChangeScene(toScene = toScene))
                        .isFalse()
                }
        }

    @Test
    fun canChangeScene_whenNotAllowed_fromLockscreen_toFalsingUnprotectedScenes_returnsTrue() =
        kosmos.runTest {
            falsingManager.setIsFalseTouch(true)
            val currentScene by collectLastValue(underTest.currentScene)
            fakeSceneDataSource.changeScene(toScene = Scenes.Lockscreen)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            sceneContainerConfig.sceneKeys
                .filter {
                    // Moving to the Communal scene is not currently falsing protected.
                    it == Scenes.Communal
                }
                .forEach { toScene ->
                    assertWithMessage("Unprotected scene $toScene is incorrectly protected")
                        .that(underTest.canChangeScene(toScene = toScene))
                        .isTrue()
                }
        }

    @Test
    fun canChangeScene_whenNotAllowed_fromGone_toAnyOtherScene_returnsTrue() =
        kosmos.runTest {
            falsingManager.setIsFalseTouch(true)
            val currentScene by collectLastValue(underTest.currentScene)
            fakeSceneDataSource.changeScene(toScene = Scenes.Gone)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Gone)

            sceneContainerConfig.sceneKeys
                .filter { it != currentScene }
                .forEach { toScene ->
                    assertWithMessage("Protected scene $toScene not properly protected")
                        .that(underTest.canChangeScene(toScene = toScene))
                        .isTrue()
                }
        }

    @Test
    fun canShowOrReplaceOverlay_whenAllowed_showingWhileOnGone_returnsTrue() =
        kosmos.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            fakeSceneDataSource.changeScene(toScene = Scenes.Gone)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Gone)

            sceneContainerConfig.overlayKeys.forEach { overlay ->
                assertWithMessage("Overlay $overlay incorrectly protected when allowed")
                    .that(underTest.canShowOrReplaceOverlay(newlyShown = overlay))
                    .isTrue()
            }
        }

    @Test
    fun canShowOrReplaceOverlay_whenAllowed_showingWhileOnLockscreen_returnsTrue() =
        kosmos.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            fakeSceneDataSource.changeScene(toScene = Scenes.Lockscreen)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            sceneContainerConfig.overlayKeys.forEach { overlay ->
                assertWithMessage("Overlay $overlay incorrectly protected when allowed")
                    .that(underTest.canShowOrReplaceOverlay(newlyShown = overlay))
                    .isTrue()
            }
        }

    @Test
    fun canShowOrReplaceOverlay_whenNotAllowed_whileOnLockscreen_returnsFalse() =
        kosmos.runTest {
            falsingManager.setIsFalseTouch(true)
            val currentScene by collectLastValue(underTest.currentScene)
            fakeSceneDataSource.changeScene(toScene = Scenes.Lockscreen)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            sceneContainerConfig.overlayKeys.forEach { overlay ->
                assertWithMessage("Protected overlay $overlay not properly protected")
                    .that(underTest.canShowOrReplaceOverlay(newlyShown = overlay))
                    .isFalse()
            }
        }

    @Test
    fun canShowOrReplaceOverlay_whenNotAllowed_whileOnGone_returnsTrue() =
        kosmos.runTest {
            falsingManager.setIsFalseTouch(true)
            val currentScene by collectLastValue(underTest.currentScene)
            fakeSceneDataSource.changeScene(toScene = Scenes.Gone)
            runCurrent()
            assertThat(currentScene).isEqualTo(Scenes.Gone)

            sceneContainerConfig.overlayKeys.forEach { overlay ->
                assertWithMessage("Protected overlay $overlay not properly protected")
                    .that(underTest.canShowOrReplaceOverlay(newlyShown = overlay))
                    .isTrue()
            }
        }

    @Test
    fun userInput() =
        kosmos.runTest {
            assertThat(kosmos.fakePowerRepository.userTouchRegistered).isFalse()
            underTest.onMotionEvent(mock())
            assertThat(kosmos.fakePowerRepository.userTouchRegistered).isTrue()
        }

    @Test
    fun userInputOnEmptySpace_insideEvent() =
        kosmos.runTest {
            assertThat(fakeRemoteInputRepository.areRemoteInputsClosed).isFalse()
            val insideMotionEvent = MotionEvent.obtain(0, 0, ACTION_DOWN, 0f, 0f, 0)
            underTest.onEmptySpaceMotionEvent(insideMotionEvent)
            assertThat(fakeRemoteInputRepository.areRemoteInputsClosed).isFalse()
        }

    @Test
    fun userInputOnEmptySpace_outsideEvent_remoteInputActive() =
        kosmos.runTest {
            fakeRemoteInputRepository.isRemoteInputActive.value = true
            assertThat(fakeRemoteInputRepository.areRemoteInputsClosed).isFalse()
            val outsideMotionEvent = MotionEvent.obtain(0, 0, ACTION_OUTSIDE, 0f, 0f, 0)
            underTest.onEmptySpaceMotionEvent(outsideMotionEvent)
            assertThat(fakeRemoteInputRepository.areRemoteInputsClosed).isTrue()
        }

    @Test
    fun userInputOnEmptySpace_outsideEvent_remoteInputInactive() =
        kosmos.runTest {
            fakeRemoteInputRepository.isRemoteInputActive.value = false
            assertThat(fakeRemoteInputRepository.areRemoteInputsClosed).isFalse()
            val outsideMotionEvent = MotionEvent.obtain(0, 0, ACTION_OUTSIDE, 0f, 0f, 0)
            underTest.onEmptySpaceMotionEvent(outsideMotionEvent)
            assertThat(fakeRemoteInputRepository.areRemoteInputsClosed).isFalse()
        }

    @Test
    fun remoteUserInteraction_keepsContainerVisible() =
        kosmos.runTest {
            sceneInteractor.setVisible(false, "reason")
            runCurrent()
            assertThat(underTest.isVisible).isFalse()
            sceneInteractor.onRemoteUserInputStarted("reason")
            runCurrent()
            assertThat(underTest.isVisible).isTrue()

            underTest.onMotionEvent(mock { on { actionMasked } doReturn MotionEvent.ACTION_UP })
            runCurrent()

            assertThat(underTest.isVisible).isFalse()
        }

    @Test
    fun getActionableContentKey_noOverlays_returnsCurrentScene() =
        kosmos.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).isEmpty()

            val actionableContentKey =
                underTest.getActionableContentKey(
                    currentScene = checkNotNull(currentScene),
                    currentOverlays = checkNotNull(currentOverlays),
                    overlayByKey = kosmos.fakeOverlaysByKeys,
                )

            assertThat(actionableContentKey).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun getActionableContentKey_multipleOverlays_returnsTopOverlay() =
        kosmos.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            fakeSceneDataSource.showOverlay(Overlays.QuickSettingsShade)
            fakeSceneDataSource.showOverlay(Overlays.NotificationsShade)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays)
                .containsExactly(Overlays.QuickSettingsShade, Overlays.NotificationsShade)

            val actionableContentKey =
                underTest.getActionableContentKey(
                    currentScene = checkNotNull(currentScene),
                    currentOverlays = checkNotNull(currentOverlays),
                    overlayByKey = kosmos.fakeOverlaysByKeys,
                )

            assertThat(actionableContentKey).isEqualTo(Overlays.QuickSettingsShade)
        }

    @Test
    fun edgeDetector_singleShade_usesDefaultEdgeDetector() =
        kosmos.runTest {
            val shadeMode by collectLastValue(kosmos.shadeMode)
            kosmos.enableSingleShade()

            assertThat(shadeMode).isEqualTo(ShadeMode.Single)
            assertThat(underTest.swipeSourceDetector).isEqualTo(DefaultEdgeDetector)
        }

    @Test
    fun edgeDetector_splitShade_usesDefaultEdgeDetector() =
        kosmos.runTest {
            val shadeMode by collectLastValue(kosmos.shadeMode)
            kosmos.enableSplitShade()

            assertThat(shadeMode).isEqualTo(ShadeMode.Split)
            assertThat(underTest.swipeSourceDetector).isEqualTo(DefaultEdgeDetector)
        }

    @Test
    fun edgeDetector_dualShade_narrowScreen_usesSceneContainerSwipeDetector() =
        kosmos.runTest {
            val shadeMode by collectLastValue(kosmos.shadeMode)
            kosmos.enableDualShade(wideLayout = false)

            assertThat(shadeMode).isEqualTo(ShadeMode.Dual)
            assertThat(underTest.swipeSourceDetector)
                .isInstanceOf(SceneContainerSwipeDetector::class.java)
        }

    @Test
    fun edgeDetector_dualShade_wideScreen_usesSceneContainerSwipeDetector() =
        kosmos.runTest {
            val shadeMode by collectLastValue(kosmos.shadeMode)
            kosmos.enableDualShade(wideLayout = true)

            assertThat(shadeMode).isEqualTo(ShadeMode.Dual)
            assertThat(underTest.swipeSourceDetector)
                .isInstanceOf(SceneContainerSwipeDetector::class.java)
        }
}
