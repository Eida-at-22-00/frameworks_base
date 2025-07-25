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

import com.android.systemui.Flags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromAodTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.BlurConfig
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition
import com.android.systemui.scene.shared.model.Overlays
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Breaks down AOD->PRIMARY BOUNCER transition into discrete steps for corresponding views to
 * consume.
 */
@SysUISingleton
class AodToPrimaryBouncerTransitionViewModel
@Inject
constructor(blurConfig: BlurConfig, animationFlow: KeyguardTransitionAnimationFlow) :
    DeviceEntryIconTransition, PrimaryBouncerTransition {
    private val transitionAnimation =
        animationFlow
            .setup(
                duration = FromAodTransitionInteractor.TO_PRIMARY_BOUNCER_DURATION,
                edge = Edge.create(from = AOD, to = Overlays.Bouncer),
            )
            .setupWithoutSceneContainer(edge = Edge.create(from = AOD, to = PRIMARY_BOUNCER))

    override val deviceEntryParentViewAlpha: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(0f)

    override val windowBlurRadius: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(blurConfig.maxBlurRadiusPx)

    val lockscreenAlpha: Flow<Float> =
        if (Flags.bouncerUiRevamp()) transitionAnimation.immediatelyTransitionTo(0.0f)
        else emptyFlow()

    val notificationAlpha = lockscreenAlpha

    override val notificationBlurRadius: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(0.0f)
}
