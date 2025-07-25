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

package com.android.systemui.communal.data.repository

import android.app.admin.devicePolicyManager
import android.content.res.mainResources
import com.android.systemui.Flags
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.communal.shared.model.CommunalBackgroundType
import com.android.systemui.flags.featureFlagsClassic
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.util.settings.fakeSettings

val Kosmos.communalDefaultBackground: CommunalBackgroundType by
    Kosmos.Fixture {
        if (Flags.glanceableHubBlurredBackground()) {
            CommunalBackgroundType.BLUR
        } else {
            CommunalBackgroundType.ANIMATED
        }
    }

val Kosmos.communalSettingsRepository: CommunalSettingsRepository by
    Kosmos.Fixture {
        CommunalSettingsRepositoryImpl(
            bgDispatcher = testDispatcher,
            resources = mainResources,
            featureFlagsClassic = featureFlagsClassic,
            secureSettings = fakeSettings,
            broadcastDispatcher = broadcastDispatcher,
            devicePolicyManager = devicePolicyManager,
            defaultBackgroundType = communalDefaultBackground,
        )
    }
