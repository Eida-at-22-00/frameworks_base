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

package com.android.systemui.dreams.ui.viewmodel

import com.android.keyguard.keyguardUpdateMonitor
import com.android.systemui.common.ui.domain.interactor.configurationInteractor
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.domain.interactor.communalSettingsInteractor
import com.android.systemui.dump.dumpManager
import com.android.systemui.keyguard.domain.interactor.fromDreamingTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.ui.viewmodel.dreamingToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.dreamingToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.glanceableHubToDreamingTransitionViewModel
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.settings.userTracker

val Kosmos.dreamViewModel by
    Kosmos.Fixture {
        DreamViewModel(
            communalInteractor = communalInteractor,
            communalSettingsInteractor = communalSettingsInteractor,
            configurationInteractor = configurationInteractor,
            keyguardTransitionInteractor = keyguardTransitionInteractor,
            fromGlanceableHubTransitionViewModel = glanceableHubToDreamingTransitionViewModel,
            toGlanceableHubTransitionViewModel = dreamingToGlanceableHubTransitionViewModel,
            toLockscreenTransitionViewModel = dreamingToLockscreenTransitionViewModel,
            fromDreamingTransitionInteractor = fromDreamingTransitionInteractor,
            keyguardUpdateMonitor = keyguardUpdateMonitor,
            userTracker = userTracker,
            dumpManager = dumpManager,
        )
    }
