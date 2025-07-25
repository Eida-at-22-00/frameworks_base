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

package com.android.systemui.underlay

import android.util.Log
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.underlay.shared.flag.UnderlayFlag
import javax.inject.Inject

/**
 * Core startable for the underlay.
 *
 * This is responsible for starting the underlay and its dependencies.
 */
@SysUISingleton
class UnderlayCoreStartable @Inject constructor() : CoreStartable {

    override fun start() {
        if (!UnderlayFlag.isEnabled) {
            Log.d(TAG, "Underlay flag is disabled, not starting.")
            return
        }

        Log.d(TAG, "start!")
    }

    private companion object {
        const val TAG = "UnderlayCoreStartable"
    }
}
