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

package com.android.systemui.volume.dialog.sliders.data.repository

import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderScope
import com.android.systemui.volume.dialog.sliders.shared.model.SliderInputEvent
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

@VolumeDialogSliderScope
class VolumeDialogSliderTouchEventsRepository @Inject constructor() {

    private val mutableSliderTouchEvents: MutableStateFlow<SliderInputEvent.Touch?> =
        MutableStateFlow(null)
    val sliderTouchEvent: Flow<SliderInputEvent.Touch> = mutableSliderTouchEvents.filterNotNull()

    fun update(touch: SliderInputEvent.Touch) {
        mutableSliderTouchEvents.value = touch
    }
}
