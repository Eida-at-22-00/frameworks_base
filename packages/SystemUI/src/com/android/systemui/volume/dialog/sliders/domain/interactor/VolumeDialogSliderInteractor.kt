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

package com.android.systemui.volume.dialog.sliders.domain.interactor

import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogStateInteractor
import com.android.systemui.volume.dialog.shared.model.VolumeDialogStreamModel
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderScope
import com.android.systemui.volume.dialog.sliders.domain.model.VolumeDialogSliderType
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn

/** Operates a state of particular slider of the Volume Dialog. */
@VolumeDialogSliderScope
class VolumeDialogSliderInteractor
@Inject
constructor(
    private val sliderType: VolumeDialogSliderType,
    @VolumeDialog private val coroutineScope: CoroutineScope,
    volumeDialogStateInteractor: VolumeDialogStateInteractor,
    private val volumeDialogController: VolumeDialogController,
    zenModeInteractor: ZenModeInteractor,
) {

    val isDisabledByZenMode: Flow<Boolean> =
        if (zenModeInteractor.canBeBlockedByZenMode(sliderType)) {
            zenModeInteractor.activeModesBlockingStream(AudioStream(sliderType.audioStream)).map {
                it.mainMode != null
            }
        } else {
            flowOf(false)
        }
    val slider: Flow<VolumeDialogStreamModel> =
        volumeDialogStateInteractor.volumeDialogState
            .mapNotNull {
                it.streamModels[sliderType.audioStream]?.run {
                    if (level < levelMin || level > levelMax) {
                        copy(level = level.coerceIn(levelMin, levelMax))
                    } else {
                        this
                    }
                }
            }
            .stateIn(coroutineScope, SharingStarted.Eagerly, null)
            .filterNotNull()

    fun setStreamVolume(userLevel: Int) {
        with(volumeDialogController) {
            setStreamVolume(sliderType.audioStream, userLevel)
            setActiveStream(sliderType.audioStream)
        }
    }
}

private fun ZenModeInteractor.canBeBlockedByZenMode(sliderType: VolumeDialogSliderType): Boolean {
    return sliderType is VolumeDialogSliderType.Stream &&
        canBeBlockedByZenMode(AudioStream(sliderType.audioStream))
}
