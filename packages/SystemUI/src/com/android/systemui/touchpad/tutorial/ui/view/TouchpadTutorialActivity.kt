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

package com.android.systemui.touchpad.tutorial.ui.view

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.theme.PlatformTheme
import com.android.systemui.inputdevice.tutorial.InputDeviceTutorialLogger
import com.android.systemui.inputdevice.tutorial.InputDeviceTutorialLogger.TutorialContext
import com.android.systemui.inputdevice.tutorial.KeyboardTouchpadTutorialMetricsLogger
import com.android.systemui.res.R
import com.android.systemui.touchpad.tutorial.ui.composable.BackGestureTutorialScreen
import com.android.systemui.touchpad.tutorial.ui.composable.HomeGestureTutorialScreen
import com.android.systemui.touchpad.tutorial.ui.composable.RecentAppsGestureTutorialScreen
import com.android.systemui.touchpad.tutorial.ui.composable.SwitchAppsGestureTutorialScreen
import com.android.systemui.touchpad.tutorial.ui.composable.TutorialSelectionScreen
import com.android.systemui.touchpad.tutorial.ui.viewmodel.BackGestureScreenViewModel
import com.android.systemui.touchpad.tutorial.ui.viewmodel.EasterEggGestureViewModel
import com.android.systemui.touchpad.tutorial.ui.viewmodel.HomeGestureScreenViewModel
import com.android.systemui.touchpad.tutorial.ui.viewmodel.RecentAppsGestureScreenViewModel
import com.android.systemui.touchpad.tutorial.ui.viewmodel.Screen.BACK_GESTURE
import com.android.systemui.touchpad.tutorial.ui.viewmodel.Screen.HOME_GESTURE
import com.android.systemui.touchpad.tutorial.ui.viewmodel.Screen.RECENT_APPS_GESTURE
import com.android.systemui.touchpad.tutorial.ui.viewmodel.Screen.SWITCH_APPS_GESTURE
import com.android.systemui.touchpad.tutorial.ui.viewmodel.Screen.TUTORIAL_SELECTION
import com.android.systemui.touchpad.tutorial.ui.viewmodel.SwitchAppsGestureScreenViewModel
import com.android.systemui.touchpad.tutorial.ui.viewmodel.TouchpadTutorialViewModel
import javax.inject.Inject

class TouchpadTutorialActivity
@Inject
constructor(
    private val viewModelFactory: TouchpadTutorialViewModel.Factory,
    private val logger: InputDeviceTutorialLogger,
    private val metricsLogger: KeyboardTouchpadTutorialMetricsLogger,
    private val backGestureViewModel: BackGestureScreenViewModel,
    private val homeGestureViewModel: HomeGestureScreenViewModel,
    private val recentAppsGestureViewModel: RecentAppsGestureScreenViewModel,
    private val switchAppsGestureScreenViewModel: SwitchAppsGestureScreenViewModel,
    private val easterEggGestureViewModel: EasterEggGestureViewModel,
) : ComponentActivity() {

    private val tutorialViewModel by
        viewModels<TouchpadTutorialViewModel>(factoryProducer = { viewModelFactory })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setTitle(getString(R.string.launch_touchpad_tutorial_notification_content))
        setContent {
            PlatformTheme {
                TouchpadTutorialScreen(
                    tutorialViewModel,
                    backGestureViewModel,
                    homeGestureViewModel,
                    recentAppsGestureViewModel,
                    switchAppsGestureScreenViewModel,
                    easterEggGestureViewModel,
                    closeTutorial = ::finishTutorial,
                )
            }
        }
        // required to handle 3+ fingers on touchpad
        window.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)
        metricsLogger.logPeripheralTutorialLaunchedFromSettings()
        logger.logOpenTutorial(TutorialContext.TOUCHPAD_TUTORIAL)
    }

    private fun finishTutorial() {
        logger.logCloseTutorial(TutorialContext.TOUCHPAD_TUTORIAL)
        finish()
    }

    override fun onResume() {
        super.onResume()
        tutorialViewModel.onOpened()
    }

    override fun onPause() {
        super.onPause()
        tutorialViewModel.onClosed()
    }
}

@Composable
fun TouchpadTutorialScreen(
    vm: TouchpadTutorialViewModel,
    backGestureViewModel: BackGestureScreenViewModel,
    homeGestureViewModel: HomeGestureScreenViewModel,
    recentAppsGestureViewModel: RecentAppsGestureScreenViewModel,
    switchAppsGestureScreenViewModel: SwitchAppsGestureScreenViewModel,
    easterEggGestureViewModel: EasterEggGestureViewModel,
    closeTutorial: () -> Unit,
) {
    val activeScreen by vm.screen.collectAsStateWithLifecycle(STARTED)
    var lastSelectedScreen by remember { mutableStateOf(TUTORIAL_SELECTION) }
    when (activeScreen) {
        TUTORIAL_SELECTION ->
            TutorialSelectionScreen(
                onBackTutorialClicked = {
                    lastSelectedScreen = BACK_GESTURE
                    vm.goTo(BACK_GESTURE)
                },
                onHomeTutorialClicked = {
                    lastSelectedScreen = HOME_GESTURE
                    vm.goTo(HOME_GESTURE)
                },
                onRecentAppsTutorialClicked = {
                    lastSelectedScreen = RECENT_APPS_GESTURE
                    vm.goTo(RECENT_APPS_GESTURE)
                },
                onSwitchAppsTutorialClicked = {
                    lastSelectedScreen = SWITCH_APPS_GESTURE
                    vm.goTo(SWITCH_APPS_GESTURE)
                },
                onDoneButtonClicked = closeTutorial,
                lastSelectedScreen,
            )
        BACK_GESTURE ->
            BackGestureTutorialScreen(
                backGestureViewModel,
                easterEggGestureViewModel,
                onDoneButtonClicked = { vm.goTo(TUTORIAL_SELECTION) },
                onBack = { vm.goTo(TUTORIAL_SELECTION) },
            )
        HOME_GESTURE ->
            HomeGestureTutorialScreen(
                homeGestureViewModel,
                easterEggGestureViewModel,
                onDoneButtonClicked = { vm.goTo(TUTORIAL_SELECTION) },
                onBack = { vm.goTo(TUTORIAL_SELECTION) },
            )
        RECENT_APPS_GESTURE ->
            RecentAppsGestureTutorialScreen(
                recentAppsGestureViewModel,
                easterEggGestureViewModel,
                onDoneButtonClicked = { vm.goTo(TUTORIAL_SELECTION) },
                onBack = { vm.goTo(TUTORIAL_SELECTION) },
            )
        SWITCH_APPS_GESTURE ->
            SwitchAppsGestureTutorialScreen(
                switchAppsGestureScreenViewModel,
                easterEggGestureViewModel,
                onDoneButtonClicked = { vm.goTo(TUTORIAL_SELECTION) },
                onBack = { vm.goTo(TUTORIAL_SELECTION) },
            )
    }
}
