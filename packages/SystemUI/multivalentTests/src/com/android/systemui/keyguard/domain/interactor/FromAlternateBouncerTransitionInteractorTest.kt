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

package com.android.systemui.keyguard.domain.interactor

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.Flags.FLAG_GLANCEABLE_HUB_V2
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.communal.data.repository.fakeCommunalSceneRepository
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.domain.interactor.communalSceneInteractor
import com.android.systemui.communal.domain.interactor.setCommunalV2ConfigEnabled
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepositorySpy
import com.android.systemui.keyguard.data.repository.keyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.util.KeyguardTransitionRepositorySpySubject.Companion.assertThat
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.testKosmos
import com.google.common.truth.Truth
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.reset
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class FromAlternateBouncerTransitionInteractorTest(flags: FlagsParameterization) : SysuiTestCase() {

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf(FLAG_GLANCEABLE_HUB_V2)
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    private val kosmos =
        testKosmos().apply {
            this.keyguardTransitionRepository = fakeKeyguardTransitionRepositorySpy
        }
    private val testScope = kosmos.testScope
    private lateinit var underTest: FromAlternateBouncerTransitionInteractor
    private lateinit var transitionRepository: FakeKeyguardTransitionRepository

    @Before
    fun setup() {
        transitionRepository = kosmos.fakeKeyguardTransitionRepositorySpy
        underTest = kosmos.fromAlternateBouncerTransitionInteractor
        kosmos.setCommunalV2ConfigEnabled(true)
        underTest.start()
    }

    @Test
    @DisableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun transitionToGone_keyguardOccluded_biometricAuthenticated() =
        testScope.runTest {
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.OCCLUDED,
                to = KeyguardState.ALTERNATE_BOUNCER,
                testScope,
            )
            reset(transitionRepository)

            kosmos.fakeKeyguardBouncerRepository.setKeyguardAuthenticatedBiometrics(null)
            kosmos.fakeKeyguardRepository.setKeyguardOccluded(true)
            runCurrent()
            assertThat(transitionRepository).noTransitionsStarted()

            kosmos.fakeKeyguardBouncerRepository.setKeyguardAuthenticatedBiometrics(true)
            runCurrent()
            kosmos.fakeKeyguardBouncerRepository.setKeyguardAuthenticatedBiometrics(null)
            runCurrent()

            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.ALTERNATE_BOUNCER, to = KeyguardState.GONE)
        }

    @Test
    @EnableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun transitionToGone_keyguardOccludedThenAltBouncer_authed_wmStateRefactor() =
        testScope.runTest {
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.OCCLUDED,
                to = KeyguardState.ALTERNATE_BOUNCER,
                testScope,
            )
            reset(transitionRepository)

            // Authentication results in calling startDismissKeyguardTransition.
            kosmos.keyguardDismissTransitionInteractor.startDismissKeyguardTransition()
            runCurrent()

            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.ALTERNATE_BOUNCER, to = KeyguardState.GONE)
        }

    @Test
    fun noTransition_keyguardNotOccluded_biometricAuthenticated() =
        testScope.runTest {
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.OCCLUDED,
                to = KeyguardState.ALTERNATE_BOUNCER,
                testScope,
            )
            reset(transitionRepository)

            kosmos.fakeKeyguardRepository.setKeyguardOccluded(false)
            kosmos.fakeKeyguardBouncerRepository.setKeyguardAuthenticatedBiometrics(true)
            runCurrent()
            kosmos.fakeKeyguardBouncerRepository.setKeyguardAuthenticatedBiometrics(null)
            runCurrent()

            assertThat(transitionRepository).noTransitionsStarted()
        }

    @Test
    fun transitionToOccluded() =
        testScope.runTest {
            kosmos.fakePowerRepository.updateWakefulness(
                WakefulnessState.AWAKE,
                WakeSleepReason.POWER_BUTTON,
                WakeSleepReason.POWER_BUTTON,
                false,
            )
            kosmos.fakeKeyguardRepository.setKeyguardOccluded(true)
            kosmos.fakeKeyguardBouncerRepository.setAlternateVisible(true)
            runCurrent()

            transitionRepository.sendTransitionSteps(
                from = KeyguardState.OCCLUDED,
                to = KeyguardState.ALTERNATE_BOUNCER,
                testScope,
            )
            reset(transitionRepository)

            kosmos.fakeKeyguardBouncerRepository.setAlternateVisible(false)
            advanceTimeBy(200) // advance past delay

            assertThat(transitionRepository)
                .startedTransition(
                    from = KeyguardState.ALTERNATE_BOUNCER,
                    to = KeyguardState.OCCLUDED,
                )
        }

    @Test
    @EnableFlags(FLAG_GLANCEABLE_HUB_V2)
    fun transitionToOccluded_glanceableHubShowing() =
        kosmos.runTest {
            val currentScene by collectLastValue(communalSceneInteractor.currentScene)

            fakePowerRepository.updateWakefulness(
                WakefulnessState.AWAKE,
                WakeSleepReason.POWER_BUTTON,
                WakeSleepReason.POWER_BUTTON,
                false,
            )
            fakeKeyguardRepository.setKeyguardOccluded(false)
            fakeKeyguardBouncerRepository.setAlternateVisible(true)
            fakeCommunalSceneRepository.changeScene(CommunalScenes.Communal)
            runCurrent()

            Truth.assertThat(currentScene).isEqualTo(CommunalScenes.Communal)

            transitionRepository.sendTransitionSteps(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.ALTERNATE_BOUNCER,
                testScope,
            )
            reset(transitionRepository)

            fakeKeyguardRepository.setKeyguardOccluded(true)
            fakeKeyguardBouncerRepository.setAlternateVisible(false)
            testScope.advanceTimeBy(200) // advance past delay

            Truth.assertThat(currentScene).isEqualTo(CommunalScenes.Blank)
        }

    @Test
    fun transitionToDreaming() =
        kosmos.runTest {
            fakePowerRepository.updateWakefulness(
                WakefulnessState.AWAKE,
                WakeSleepReason.POWER_BUTTON,
                WakeSleepReason.POWER_BUTTON,
                false,
            )
            fakeKeyguardRepository.setKeyguardOccluded(false)
            fakeKeyguardBouncerRepository.setAlternateVisible(true)
            runCurrent()

            transitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.ALTERNATE_BOUNCER,
                testScope,
            )
            reset(transitionRepository)

            fakeKeyguardRepository.setKeyguardOccluded(true)
            fakeKeyguardRepository.setDreaming(true)
            fakeKeyguardBouncerRepository.setAlternateVisible(false)
            testScope.advanceTimeBy(200) // advance past delay

            assertThat(transitionRepository)
                .startedTransition(
                    from = KeyguardState.ALTERNATE_BOUNCER,
                    to = KeyguardState.DREAMING,
                )
        }

    @Test
    @DisableFlags(Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    fun transitionToGone_whenOpeningGlanceableHubEditMode() =
        testScope.runTest {
            kosmos.fakeKeyguardBouncerRepository.setAlternateVisible(true)
            runCurrent()

            // On Glanceable hub and edit mode activity is started
            transitionRepository.sendTransitionSteps(
                from = KeyguardState.GLANCEABLE_HUB,
                to = KeyguardState.ALTERNATE_BOUNCER,
                testScope,
            )
            reset(transitionRepository)

            kosmos.communalInteractor.setEditModeOpen(true)
            runCurrent()

            // Auth and alternate bouncer is hidden
            kosmos.fakeKeyguardBouncerRepository.setAlternateVisible(false)
            advanceTimeBy(200) // advance past delay

            // Then no transition should occur yet
            assertThat(transitionRepository).noTransitionsStarted()

            // When keyguard is going away
            kosmos.fakeKeyguardRepository.setKeyguardGoingAway(true)
            runCurrent()

            // Then transition to GONE should occur
            assertThat(transitionRepository)
                .startedTransition(from = KeyguardState.ALTERNATE_BOUNCER, to = KeyguardState.GONE)
        }
}
