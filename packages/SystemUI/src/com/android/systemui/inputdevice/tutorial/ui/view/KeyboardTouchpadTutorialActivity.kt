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

package com.android.systemui.inputdevice.tutorial.ui.view

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.compose.theme.PlatformTheme
import com.android.systemui.inputdevice.tutorial.InputDeviceTutorialLogger
import com.android.systemui.inputdevice.tutorial.InputDeviceTutorialLogger.TutorialContext
import com.android.systemui.inputdevice.tutorial.KeyboardTouchpadTutorialMetricsLogger
import com.android.systemui.inputdevice.tutorial.TouchpadTutorialScreensProvider
import com.android.systemui.inputdevice.tutorial.domain.interactor.TutorialSchedulerInteractor
import com.android.systemui.inputdevice.tutorial.domain.interactor.TutorialSchedulerInteractor.TutorialType
import com.android.systemui.inputdevice.tutorial.ui.composable.ActionKeyTutorialScreen
import com.android.systemui.inputdevice.tutorial.ui.viewmodel.KeyboardTouchpadTutorialViewModel
import com.android.systemui.inputdevice.tutorial.ui.viewmodel.KeyboardTouchpadTutorialViewModel.Factory.ViewModelFactoryAssistedProvider
import com.android.systemui.inputdevice.tutorial.ui.viewmodel.Screen.ACTION_KEY
import com.android.systemui.inputdevice.tutorial.ui.viewmodel.Screen.BACK_GESTURE
import com.android.systemui.inputdevice.tutorial.ui.viewmodel.Screen.HOME_GESTURE
import java.util.Optional
import javax.inject.Inject

/**
 * Activity for out of the box experience for keyboard and touchpad. Note that it's possible that
 * either of them are actually not connected when this is launched
 */
class KeyboardTouchpadTutorialActivity
@Inject
constructor(
    private val viewModelFactoryAssistedProvider: ViewModelFactoryAssistedProvider,
    private val touchpadTutorialScreensProvider: Optional<TouchpadTutorialScreensProvider>,
    private val schedulerInteractor: TutorialSchedulerInteractor,
    private val logger: InputDeviceTutorialLogger,
    private val metricsLogger: KeyboardTouchpadTutorialMetricsLogger,
) : ComponentActivity() {

    companion object {
        const val INTENT_TUTORIAL_SCOPE_KEY = "tutorial_scope"
        const val INTENT_TUTORIAL_SCOPE_TOUCHPAD = "touchpad"
        const val INTENT_TUTORIAL_SCOPE_TOUCHPAD_BACK = "touchpad_back"
        const val INTENT_TUTORIAL_SCOPE_TOUCHPAD_HOME = "touchpad_home"
        const val INTENT_TUTORIAL_SCOPE_KEYBOARD = "keyboard"
        const val INTENT_TUTORIAL_SCOPE_ALL = "all"

        const val INTENT_TUTORIAL_ENTRY_POINT_KEY = "entry_point"
        const val INTENT_TUTORIAL_ENTRY_POINT_SCHEDULER = "scheduler"
        const val INTENT_TUTORIAL_ENTRY_POINT_CONTEXTUAL_EDU = "contextual_edu"
    }

    private val vm by
        viewModels<KeyboardTouchpadTutorialViewModel>(
            factoryProducer = {
                viewModelFactoryAssistedProvider.create(touchpadTutorialScreensProvider.isPresent)
            }
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // required to handle 3+ fingers on touchpad
        window.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)
        window.addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_ALLOW_ACTION_KEY_EVENTS)
        lifecycle.addObserver(vm)
        lifecycleScope.launch {
            vm.closeActivity.collect { finish ->
                if (finish) {
                    logger.logCloseTutorial(TutorialContext.KEYBOARD_TOUCHPAD_TUTORIAL)
                    finish()
                }
            }
        }
        val entryPointExtra = intent.getStringExtra(INTENT_TUTORIAL_ENTRY_POINT_KEY)
        val isAutoProceed =
            if (entryPointExtra == null) true
            else entryPointExtra.equals(INTENT_TUTORIAL_ENTRY_POINT_SCHEDULER)
        val scopeExtra = intent.getStringExtra(INTENT_TUTORIAL_SCOPE_KEY)
        val isScopeAll = INTENT_TUTORIAL_SCOPE_ALL.equals(scopeExtra)
        setContent {
            PlatformTheme {
                KeyboardTouchpadTutorialContainer(
                    vm,
                    touchpadTutorialScreensProvider,
                    isAutoProceed,
                    isScopeAll,
                )
            }
        }
        if (savedInstanceState == null) {
            logger.logOpenTutorial(TutorialContext.KEYBOARD_TOUCHPAD_TUTORIAL)

            val tutorialTypeExtra = intent.getStringExtra(INTENT_TUTORIAL_SCOPE_KEY)
            metricsLogger.logPeripheralTutorialLaunched(entryPointExtra, tutorialTypeExtra)
            // We only update launched info when the tutorial is triggered by the scheduler
            if (INTENT_TUTORIAL_ENTRY_POINT_SCHEDULER.equals(entryPointExtra))
                updateLaunchInfo(tutorialTypeExtra)
        }
    }

    private fun updateLaunchInfo(tutorialTypeExtra: String?) {
        val type =
            when (tutorialTypeExtra) {
                INTENT_TUTORIAL_SCOPE_KEYBOARD -> TutorialType.KEYBOARD
                INTENT_TUTORIAL_SCOPE_TOUCHPAD -> TutorialType.TOUCHPAD
                INTENT_TUTORIAL_SCOPE_ALL -> TutorialType.BOTH
                else -> TutorialType.NONE
            }
        schedulerInteractor.updateLaunchInfo(type)
    }
}

@Composable
fun KeyboardTouchpadTutorialContainer(
    vm: KeyboardTouchpadTutorialViewModel,
    touchpadScreens: Optional<TouchpadTutorialScreensProvider>,
    isAutoProceed: Boolean = false,
    isScopeAll: Boolean = false,
) {
    val activeScreen by vm.screen.collectAsStateWithLifecycle(STARTED)
    when (activeScreen) {
        BACK_GESTURE ->
            touchpadScreens
                .get()
                .BackGesture(
                    onDoneButtonClicked = vm::onDoneButtonClicked,
                    onBack = vm::onBack,
                    onAutoProceed = if (isAutoProceed) vm::onAutoProceed else null,
                )
        HOME_GESTURE ->
            touchpadScreens
                .get()
                .HomeGesture(
                    onDoneButtonClicked = vm::onDoneButtonClicked,
                    onBack = vm::onBack,
                    onAutoProceed = if (isScopeAll) vm::onAutoProceed else null,
                )
        ACTION_KEY ->
            ActionKeyTutorialScreen(
                onDoneButtonClicked = vm::onDoneButtonClicked,
                onBack = vm::onBack,
            )
    }
}
