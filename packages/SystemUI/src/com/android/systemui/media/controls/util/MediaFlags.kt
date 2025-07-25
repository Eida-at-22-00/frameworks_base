/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.media.controls.util

import android.app.StatusBarManager
import android.os.UserHandle
import com.android.systemui.Flags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags as FlagsClassic
import javax.inject.Inject

@SysUISingleton
class MediaFlags @Inject constructor(private val featureFlags: FeatureFlagsClassic) {
    /**
     * Check whether media control actions should be based on PlaybackState instead of notification
     */
    fun areMediaSessionActionsEnabled(packageName: String, user: UserHandle): Boolean {
        return StatusBarManager.useMediaSessionActionsForApp(packageName, user)
    }

    /** Check whether media control actions should be derived from Media3 controller */
    fun areMedia3ActionsEnabled(packageName: String, user: UserHandle): Boolean {
        val compatFlag = StatusBarManager.useMedia3ControllerForApp(packageName, user)
        val featureFlag = Flags.mediaControlsButtonMedia3()
        return featureFlag && compatFlag
    }

    /**
     * If true, keep active media controls for the lifetime of the MediaSession, regardless of
     * whether the underlying notification was dismissed
     */
    fun isRetainingPlayersEnabled() = featureFlags.isEnabled(FlagsClassic.MEDIA_RETAIN_SESSIONS)
}
