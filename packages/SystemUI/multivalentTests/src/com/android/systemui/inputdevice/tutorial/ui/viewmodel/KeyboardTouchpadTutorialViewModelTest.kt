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

package com.android.systemui.inputdevice.tutorial.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.inputdevice.tutorial.InputDeviceTutorialLogger
import com.android.systemui.inputdevice.tutorial.domain.interactor.KeyboardTouchpadConnectionInteractor
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_SCOPE_KEY
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_SCOPE_KEYBOARD
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_SCOPE_TOUCHPAD
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_SCOPE_TOUCHPAD_BACK
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_SCOPE_TOUCHPAD_HOME
import com.android.systemui.inputdevice.tutorial.ui.viewmodel.Screen.ACTION_KEY
import com.android.systemui.inputdevice.tutorial.ui.viewmodel.Screen.BACK_GESTURE
import com.android.systemui.inputdevice.tutorial.ui.viewmodel.Screen.HOME_GESTURE
import com.android.systemui.keyboard.data.repository.keyboardRepository
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.model.sysUiState
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_TOUCHPAD_GESTURES_DISABLED
import com.android.systemui.testKosmos
import com.android.systemui.touchpad.data.repository.TouchpadRepository
import com.android.systemui.touchpad.tutorial.touchpadGesturesInteractor
import com.android.systemui.util.coroutines.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyboardTouchpadTutorialViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val sysUiState = kosmos.sysUiState
    private val touchpadRepo = PrettyFakeTouchpadRepository()
    private val keyboardRepo = kosmos.keyboardRepository
    private var tutorialScope = INTENT_TUTORIAL_SCOPE_TOUCHPAD
    private val viewModel by lazy { createViewModel(tutorialScope) }

    @get:Rule val mainDispatcherRule = MainDispatcherRule(kosmos.testDispatcher)

    private fun createViewModel(
        scope: String = INTENT_TUTORIAL_SCOPE_TOUCHPAD,
        hasTouchpadTutorialScreens: Boolean = true,
    ): KeyboardTouchpadTutorialViewModel {
        val viewModel =
            KeyboardTouchpadTutorialViewModel(
                Optional.of(kosmos.touchpadGesturesInteractor),
                KeyboardTouchpadConnectionInteractor(keyboardRepo, touchpadRepo),
                hasTouchpadTutorialScreens,
                mock<InputDeviceTutorialLogger>(),
                SavedStateHandle(mapOf(INTENT_TUTORIAL_SCOPE_KEY to scope)),
            )
        return viewModel
    }

    @Test
    fun screensOrder_whenTouchpadAndKeyboardConnected() =
        testScope.runTest {
            val screens by collectValues(viewModel.screen)
            val closeActivity by collectLastValue(viewModel.closeActivity)
            peripheralsState(keyboardConnected = true, touchpadConnected = true)

            goToNextScreen()
            goToNextScreen()
            // reached the last screen

            assertThat(screens).containsExactly(BACK_GESTURE, HOME_GESTURE, ACTION_KEY).inOrder()
            assertThat(closeActivity).isFalse()
        }

    @Test
    fun screensOrder_whenKeyboardDisconnectsDuringTutorial() =
        testScope.runTest {
            val screens by collectValues(viewModel.screen)
            val closeActivity by collectLastValue(viewModel.closeActivity)
            peripheralsState(keyboardConnected = true, touchpadConnected = true)

            // back gesture screen
            goToNextScreen()
            // home gesture screen
            peripheralsState(keyboardConnected = false, touchpadConnected = true)
            goToNextScreen()
            // no action key screen because keyboard disconnected

            assertThat(screens).containsExactly(BACK_GESTURE, HOME_GESTURE).inOrder()
            assertThat(closeActivity).isTrue()
        }

    @Test
    fun screensOrderUntilFinish_whenTouchpadAndKeyboardConnected() =
        testScope.runTest {
            val screens by collectValues(viewModel.screen)
            val closeActivity by collectLastValue(viewModel.closeActivity)

            peripheralsState(keyboardConnected = true, touchpadConnected = true)

            goToNextScreen()
            goToNextScreen()
            // we're at the last screen so "next screen" should be actually closing activity
            goToNextScreen()

            assertThat(screens).containsExactly(BACK_GESTURE, HOME_GESTURE, ACTION_KEY).inOrder()
            assertThat(closeActivity).isTrue()
        }

    @Test
    fun screensOrder_whenGoingBackToPreviousScreens() =
        testScope.runTest {
            val screens by collectValues(viewModel.screen)
            val closeActivity by collectLastValue(viewModel.closeActivity)
            peripheralsState(keyboardConnected = true, touchpadConnected = true)

            // back gesture
            goToNextScreen()
            // home gesture
            goToNextScreen()
            // action key

            goBack()
            // home gesture
            goBack()
            // back gesture
            goBack()
            // finish activity

            assertThat(screens)
                .containsExactly(BACK_GESTURE, HOME_GESTURE, ACTION_KEY, HOME_GESTURE, BACK_GESTURE)
                .inOrder()
            assertThat(closeActivity).isTrue()
        }

    @Test
    fun screensOrder_whenGoingBackAndOnlyKeyboardConnected() =
        testScope.runTest {
            tutorialScope = INTENT_TUTORIAL_SCOPE_KEYBOARD
            val screens by collectValues(viewModel.screen)
            val closeActivity by collectLastValue(viewModel.closeActivity)
            peripheralsState(keyboardConnected = true, touchpadConnected = false)

            // action key screen
            goBack()
            // activity finished

            assertThat(screens).containsExactly(ACTION_KEY).inOrder()
            assertThat(closeActivity).isTrue()
        }

    @Test
    fun screensOrder_whenTouchpadConnected() =
        testScope.runTest {
            tutorialScope = INTENT_TUTORIAL_SCOPE_TOUCHPAD
            val screens by collectValues(viewModel.screen)
            val closeActivity by collectLastValue(viewModel.closeActivity)

            peripheralsState(keyboardConnected = false, touchpadConnected = true)

            goToNextScreen()
            goToNextScreen()

            assertThat(screens).containsExactly(BACK_GESTURE, HOME_GESTURE).inOrder()
            assertThat(closeActivity).isTrue()
        }

    @Test
    fun screensOrder_withBackGestureScope() =
        testScope.runTest {
            tutorialScope = INTENT_TUTORIAL_SCOPE_TOUCHPAD_BACK
            val screens by collectValues(viewModel.screen)
            val closeActivity by collectLastValue(viewModel.closeActivity)
            peripheralsState(touchpadConnected = true)

            goToNextScreen()

            assertThat(screens).containsExactly(BACK_GESTURE).inOrder()
            assertThat(closeActivity).isTrue()
        }

    @Test
    fun screensOrder_withHomeGestureScope() =
        testScope.runTest {
            tutorialScope = INTENT_TUTORIAL_SCOPE_TOUCHPAD_HOME
            val screens by collectValues(viewModel.screen)
            val closeActivity by collectLastValue(viewModel.closeActivity)
            peripheralsState(touchpadConnected = true)

            goToNextScreen()

            assertThat(screens).containsExactly(HOME_GESTURE).inOrder()
            assertThat(closeActivity).isTrue()
        }

    @Test
    fun screensOrder_withKeyboardScope() =
        testScope.runTest {
            tutorialScope = INTENT_TUTORIAL_SCOPE_KEYBOARD
            val screens by collectValues(viewModel.screen)
            val closeActivity by collectLastValue(viewModel.closeActivity)
            peripheralsState(keyboardConnected = true)

            goToNextScreen()

            assertThat(screens).containsExactly(ACTION_KEY).inOrder()
            assertThat(closeActivity).isTrue()
        }

    @Test
    fun touchpadGesturesDisabled_onlyDuringTouchpadTutorial() =
        testScope.runTest {
            tutorialScope = INTENT_TUTORIAL_SCOPE_TOUCHPAD
            collectValues(viewModel.screen) // just to initialize viewModel
            peripheralsState(keyboardConnected = true, touchpadConnected = true)

            assertGesturesDisabled()
            goToNextScreen()
            goToNextScreen()
            // end of touchpad tutorial, keyboard tutorial starts
            assertGesturesNotDisabled()
        }

    @Test
    fun screensOrderUntilFinish_whenAutoProceed() =
        testScope.runTest {
            val screens by collectValues(viewModel.screen)
            val closeActivity by collectLastValue(viewModel.closeActivity)

            peripheralsState(keyboardConnected = true, touchpadConnected = true)

            autoProceed()
            autoProceed()
            // No autoproceeding at the last screen
            goToNextScreen()

            assertThat(screens).containsExactly(BACK_GESTURE, HOME_GESTURE, ACTION_KEY).inOrder()
            assertThat(closeActivity).isTrue()
        }

    @Test
    fun activityFinishes_ifTouchpadModuleIsNotPresent() =
        testScope.runTest {
            val viewModel =
                createViewModel(
                    scope = INTENT_TUTORIAL_SCOPE_TOUCHPAD,
                    hasTouchpadTutorialScreens = false,
                )
            val screens by collectValues(viewModel.screen)
            val closeActivity by collectLastValue(viewModel.closeActivity)
            peripheralsState(touchpadConnected = true)

            assertThat(screens).isEmpty()
            assertThat(closeActivity).isTrue()
        }

    @Test
    fun touchpadGesturesDisabled_whenTutorialGoesToForeground() =
        testScope.runTest {
            tutorialScope = INTENT_TUTORIAL_SCOPE_TOUCHPAD
            collectValues(viewModel.screen) // just to initialize viewModel
            peripheralsState(touchpadConnected = true)

            viewModel.onStart(TestLifecycleOwner())

            assertGesturesDisabled()
        }

    @Test
    fun touchpadGesturesNotDisabled_whenTutorialGoesToBackground() =
        testScope.runTest {
            tutorialScope = INTENT_TUTORIAL_SCOPE_TOUCHPAD
            collectValues(viewModel.screen)
            peripheralsState(touchpadConnected = true)

            viewModel.onStart(TestLifecycleOwner())
            viewModel.onStop(TestLifecycleOwner())

            assertGesturesNotDisabled()
        }

    @Test
    fun keyboardShortcutsDisabled_onlyDuringKeyboardTutorial() =
        testScope.runTest {
            // TODO(b/358587037)
        }

    private fun TestScope.goToNextScreen() {
        viewModel.onDoneButtonClicked()
        runCurrent()
    }

    private suspend fun TestScope.autoProceed() {
        viewModel.onAutoProceed()
        runCurrent()
    }

    private fun TestScope.goBack() {
        viewModel.onBack()
        runCurrent()
    }

    private fun TestScope.peripheralsState(
        keyboardConnected: Boolean = false,
        touchpadConnected: Boolean = false,
    ) {
        keyboardRepo.setIsAnyKeyboardConnected(keyboardConnected)
        touchpadRepo.setIsAnyTouchpadConnected(touchpadConnected)
        runCurrent()
    }

    private fun TestScope.assertGesturesNotDisabled() = assertFlagEnabled(enabled = false)

    private fun TestScope.assertGesturesDisabled() = assertFlagEnabled(enabled = true)

    private fun TestScope.assertFlagEnabled(enabled: Boolean) {
        // sysui state is changed on background scope so let's make sure it's executed
        runCurrent()
        assertThat(sysUiState.isFlagEnabled(SYSUI_STATE_TOUCHPAD_GESTURES_DISABLED))
            .isEqualTo(enabled)
    }

    // replace below when we have better fake
    internal class PrettyFakeTouchpadRepository : TouchpadRepository {

        private val _isAnyTouchpadConnected = MutableStateFlow(false)
        override val isAnyTouchpadConnected: Flow<Boolean> = _isAnyTouchpadConnected

        fun setIsAnyTouchpadConnected(connected: Boolean) {
            _isAnyTouchpadConnected.value = connected
        }
    }
}
