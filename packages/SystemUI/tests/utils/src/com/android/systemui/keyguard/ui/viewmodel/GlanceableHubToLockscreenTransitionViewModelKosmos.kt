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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.common.ui.domain.interactor.configurationInteractor
import com.android.systemui.communal.domain.interactor.communalSceneInteractor
import com.android.systemui.communal.domain.interactor.communalSettingsInteractor
import com.android.systemui.keyguard.ui.glanceableHubBlurComponentFactory
import com.android.systemui.keyguard.ui.keyguardTransitionAnimationFlow
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.applicationCoroutineScope

val Kosmos.glanceableHubToLockscreenTransitionViewModel by Fixture {
    GlanceableHubToLockscreenTransitionViewModel(
        applicationScope = applicationCoroutineScope,
        configurationInteractor = configurationInteractor,
        animationFlow = keyguardTransitionAnimationFlow,
        communalSceneInteractor = communalSceneInteractor,
        communalSettingsInteractor = communalSettingsInteractor,
        blurFactory = glanceableHubBlurComponentFactory,
    )
}
