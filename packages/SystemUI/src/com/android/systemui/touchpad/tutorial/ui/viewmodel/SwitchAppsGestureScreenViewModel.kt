/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.touchpad.tutorial.ui.viewmodel

import android.view.MotionEvent
import com.android.systemui.inputdevice.tutorial.ui.composable.TutorialActionState
import com.android.systemui.res.R
import com.android.systemui.touchpad.tutorial.ui.gesture.handleTouchpadMotionEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SwitchAppsGestureScreenViewModel(private val gestureRecognizer: GestureRecognizerAdapter) :
    TouchpadTutorialScreenViewModel {

    override val tutorialState: Flow<TutorialActionState> =
        gestureRecognizer.gestureState
            .map {
                it to
                    TutorialAnimationProperties(
                        progressStartMarker = "gesture to R",
                        progressEndMarker = "end of gesture",
                        successAnimation = R.raw.trackpad_switch_apps_success,
                    )
            }
            .mapToTutorialState()

    override fun handleEvent(event: MotionEvent): Boolean {
        return gestureRecognizer.handleTouchpadMotionEvent(event)
    }
}
