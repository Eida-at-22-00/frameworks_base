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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.transitions.blurConfig
import com.android.systemui.kosmos.collectValues
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class GlanceableHubToOccludedTransitionViewModelTest : SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope

    val configurationRepository by lazy { kosmos.fakeConfigurationRepository }
    val underTest by lazy { kosmos.glanceableHubToOccludedTransitionViewModel }

    @Test
    @DisableSceneContainer
    fun blurBecomesMinValueImmediately() =
        kosmos.runTest {
            val values by collectValues(underTest.windowBlurRadius)

            keyguardWindowBlurTestUtil.assertTransitionToBlurRadius(
                transitionProgress = listOf(0.0f, 0.2f, 0.3f, 0.65f, 0.7f, 1.0f),
                startValue = blurConfig.maxBlurRadiusPx,
                endValue = blurConfig.minBlurRadiusPx,
                actualValuesProvider = { values },
                transitionFactory = { step, transitionState ->
                    TransitionStep(
                        from = KeyguardState.GLANCEABLE_HUB,
                        to = KeyguardState.OCCLUDED,
                        value = step,
                        transitionState = transitionState,
                        ownerName = "GlanceableHubToOccludedTransitionViewModelTest",
                    )
                },
                checkInterpolatedValues = false,
            )
        }
}
