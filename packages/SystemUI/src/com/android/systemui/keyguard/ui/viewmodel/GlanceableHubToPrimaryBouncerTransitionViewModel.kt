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
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.communal.shared.model.CommunalBackgroundType
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.FromGlanceableHubTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.GLANCEABLE_HUB
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.BlurConfig
import com.android.systemui.keyguard.ui.transitions.PrimaryBouncerTransition
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class GlanceableHubToPrimaryBouncerTransitionViewModel
@Inject
constructor(
    private val blurConfig: BlurConfig,
    animationFlow: KeyguardTransitionAnimationFlow,
    private val communalSettingsInteractor: CommunalSettingsInteractor,
    private val communalSceneInteractor: CommunalSceneInteractor,
    private val keyguardStateController: KeyguardStateController,
) : PrimaryBouncerTransition {
    private val transitionAnimation =
        animationFlow
            .setup(
                duration = FromGlanceableHubTransitionInteractor.TO_BOUNCER_DURATION,
                edge = Edge.INVALID,
            )
            .setupWithoutSceneContainer(edge = Edge.create(GLANCEABLE_HUB, PRIMARY_BOUNCER))

    override val windowBlurRadius: Flow<Float> =
        if (Flags.glanceableHubBlurredBackground()) {
            communalSettingsInteractor.communalBackground
                .filter { it != CommunalBackgroundType.BLUR }
                .flatMapLatest {
                    transitionAnimation.immediatelyTransitionTo(blurConfig.maxBlurRadiusPx)
                }
        } else {
            transitionAnimation.immediatelyTransitionTo(blurConfig.maxBlurRadiusPx)
        }

    /** Whether to delay the animation to fade in bouncer elements. */
    fun willDelayAppearAnimation(isLandscape: Boolean): Boolean =
        communalSettingsInteractor.isV2FlagEnabled() &&
            communalSceneInteractor.isIdleOnCommunal.value &&
            !keyguardStateController.isKeyguardScreenRotationAllowed() &&
            isLandscape

    override val notificationBlurRadius: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(0.0f)
}
