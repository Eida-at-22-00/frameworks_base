/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.Flags.FLAG_BOUNCER_UI_REVAMP
import com.android.systemui.Flags.FLAG_NOTIFICATION_SHADE_BLUR
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.BrokenWithSceneContainer
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.Flags
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.ui.transitions.blurConfig
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.data.repository.sceneContainerRepository
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.testKosmos
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class LockscreenToPrimaryBouncerTransitionViewModelTest(flags: FlagsParameterization) :
    SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            fakeFeatureFlagsClassic.apply { set(Flags.FULL_SCREEN_USER_SWITCHER, false) }
        }
    private val testScope = kosmos.testScope
    private val repository = kosmos.fakeKeyguardTransitionRepository
    private val shadeTestUtil by lazy { kosmos.shadeTestUtil }
    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private lateinit var underTest: LockscreenToPrimaryBouncerTransitionViewModel

    private val transitionState =
        MutableStateFlow<ObservableTransitionState>(
            ObservableTransitionState.Idle(Scenes.Lockscreen)
        )

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Before
    fun setup() {
        underTest = kosmos.lockscreenToPrimaryBouncerTransitionViewModel
    }

    @Test
    @BrokenWithSceneContainer(330311871)
    fun deviceEntryParentViewAlpha_shadeExpanded() =
        testScope.runTest {
            val actual by collectLastValue(underTest.deviceEntryParentViewAlpha)
            shadeExpanded(true)
            runCurrent()

            // immediately 0f
            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            assertThat(actual).isEqualTo(0f)

            repository.sendTransitionStep(step(.2f))
            assertThat(actual).isEqualTo(0f)

            repository.sendTransitionStep(step(0.8f))
            assertThat(actual).isEqualTo(0f)

            repository.sendTransitionStep(step(1f, TransitionState.FINISHED))
            assertThat(actual).isEqualTo(0f)
        }

    @Test
    @DisableSceneContainer
    fun lockscreenAlphaEndsWithZero() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.lockscreenAlpha)

            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            runCurrent()

            // Jump right to the end and validate the value
            repository.sendTransitionStep(step(1f, TransitionState.FINISHED))
            assertThat(alpha).isEqualTo(0f)
        }

    @Test
    fun deviceEntryParentViewAlpha_shadeNotExpanded() =
        testScope.runTest {
            val actual by collectLastValue(underTest.deviceEntryParentViewAlpha)
            shadeExpanded(false)
            runCurrent()

            kosmos.sceneContainerRepository.setTransitionState(transitionState)
            transitionState.value =
                ObservableTransitionState.Transition.showOverlay(
                    overlay = Overlays.Bouncer,
                    fromScene = Scenes.Lockscreen,
                    currentOverlays = emptyFlow(),
                    progress = emptyFlow(),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = emptyFlow(),
                )

            runCurrent()
            // fade out
            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            assertThat(actual).isEqualTo(1f)

            repository.sendTransitionStep(step(.1f))
            assertThat(actual).isIn(Range.open(.1f, .9f))

            // alpha is 1f before the full transition starts ending
            repository.sendTransitionStep(step(0.8f))
            assertThat(actual).isEqualTo(0f)

            repository.sendTransitionStep(step(1f, TransitionState.FINISHED))
            assertThat(actual).isEqualTo(0f)
        }

    @Test
    @EnableFlags(FLAG_NOTIFICATION_SHADE_BLUR)
    @BrokenWithSceneContainer(388068805)
    fun blurRadiusIsMaxWhenShadeIsExpanded() =
        testScope.runTest {
            val values by collectValues(underTest.windowBlurRadius)
            kosmos.keyguardWindowBlurTestUtil.shadeExpanded(true)

            kosmos.keyguardWindowBlurTestUtil.assertTransitionToBlurRadius(
                transitionProgress = listOf(0.0f, 0.2f, 0.3f, 0.65f, 0.7f, 1.0f),
                startValue = kosmos.blurConfig.maxBlurRadiusPx,
                endValue = kosmos.blurConfig.maxBlurRadiusPx,
                actualValuesProvider = { values },
                transitionFactory = ::step,
                checkInterpolatedValues = false,
            )
        }

    @Test
    @BrokenWithSceneContainer(388068805)
    fun blurRadiusGoesFromMinToMaxWhenShadeIsNotExpanded() =
        testScope.runTest {
            val values by collectValues(underTest.windowBlurRadius)
            kosmos.keyguardWindowBlurTestUtil.shadeExpanded(false)

            kosmos.keyguardWindowBlurTestUtil.assertTransitionToBlurRadius(
                transitionProgress = listOf(0.0f, 0.2f, 0.3f, 0.65f, 0.7f, 1.0f),
                startValue = kosmos.blurConfig.minBlurRadiusPx,
                endValue = kosmos.blurConfig.maxBlurRadiusPx,
                actualValuesProvider = { values },
                transitionFactory = ::step,
            )
        }

    @Test
    @EnableFlags(FLAG_BOUNCER_UI_REVAMP)
    @BrokenWithSceneContainer(388068805)
    fun notificationBlur_isNonZero_whenShadeIsExpanded() =
        testScope.runTest {
            val values by collectValues(underTest.notificationBlurRadius)
            kosmos.keyguardWindowBlurTestUtil.shadeExpanded(true)
            runCurrent()

            kosmos.keyguardWindowBlurTestUtil.assertTransitionToBlurRadius(
                transitionProgress = listOf(0f, 0f, 0.1f, 0.2f, 0.3f, 1f),
                startValue = kosmos.blurConfig.maxBlurRadiusPx,
                endValue = kosmos.blurConfig.maxBlurRadiusPx,
                transitionFactory = ::step,
                actualValuesProvider = { values },
                checkInterpolatedValues = false,
            )
        }

    @Test
    @EnableFlags(FLAG_BOUNCER_UI_REVAMP)
    @BrokenWithSceneContainer(388068805)
    fun notifications_areFullyVisible_whenShadeIsExpanded() =
        testScope.runTest {
            val values by collectValues(underTest.notificationAlpha)
            kosmos.keyguardWindowBlurTestUtil.shadeExpanded(true)
            runCurrent()

            kosmos.keyguardWindowBlurTestUtil.assertTransitionToBlurRadius(
                transitionProgress = listOf(0f, 0f, 0.1f, 0.2f, 0.3f, 1f),
                startValue = 1.0f,
                endValue = 1.0f,
                transitionFactory = ::step,
                actualValuesProvider = { values },
                checkInterpolatedValues = false,
            )
        }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING,
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.LOCKSCREEN,
            to =
                if (SceneContainerFlag.isEnabled) KeyguardState.UNDEFINED
                else KeyguardState.PRIMARY_BOUNCER,
            value = value,
            transitionState = state,
            ownerName = "LockscreenToPrimaryBouncerTransitionViewModelTest",
        )
    }

    private fun shadeExpanded(expanded: Boolean) {
        if (expanded) {
            shadeTestUtil.setQsExpansion(1f)
        } else {
            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            shadeTestUtil.setQsExpansion(0f)
            shadeTestUtil.setLockscreenShadeExpansion(0f)
        }
    }
}
