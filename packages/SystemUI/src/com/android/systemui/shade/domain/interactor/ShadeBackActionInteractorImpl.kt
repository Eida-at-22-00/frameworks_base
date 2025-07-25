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

package com.android.systemui.shade.domain.interactor

import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes
import javax.inject.Inject

/** Implementation of ShadeBackActionInteractor backed by scenes. */
class ShadeBackActionInteractorImpl
@Inject
constructor(
    val shadeInteractor: ShadeInteractor,
    val shadeModeInteractor: ShadeModeInteractor,
    val sceneInteractor: SceneInteractor,
    val deviceEntryInteractor: DeviceEntryInteractor,
) : ShadeBackActionInteractor {
    override fun animateCollapseQs(fullyCollapse: Boolean) {
        if (shadeInteractor.isQsExpanded.value) {
            val key =
                if (
                    fullyCollapse ||
                        shadeModeInteractor.isDualShade ||
                        shadeModeInteractor.isSplitShade
                ) {
                    SceneFamilies.Home
                } else {
                    Scenes.Shade
                }
            sceneInteractor.changeScene(key, "animateCollapseQs")
        }
    }

    override fun canBeCollapsed(): Boolean {
        return shadeInteractor.isAnyExpanded.value && !shadeInteractor.isUserInteracting.value
    }

    override fun onBackPressed() {
        animateCollapseQs(false)
    }

    @Deprecated("According to b/318376223, shade predictive back is not be supported.")
    override fun onBackProgressed(progressFraction: Float) {
        // Not supported. Do nothing.
    }
}
