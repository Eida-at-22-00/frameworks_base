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

package com.android.systemui.statusbar.policy.ui.dialog.viewmodel

import android.app.AutomaticZenRule
import android.content.Intent
import android.content.applicationContext
import android.provider.Settings
import android.service.notification.SystemZenRules
import android.service.notification.ZenModeConfig
import android.service.notification.ZenModeConfig.ScheduleInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.notification.modes.TestModeBuilder
import com.android.settingslib.notification.modes.TestModeBuilder.MANUAL_DND
import com.android.settingslib.notification.modes.ZenMode
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.policy.data.repository.fakeZenModeRepository
import com.android.systemui.statusbar.policy.domain.interactor.zenModeInteractor
import com.android.systemui.statusbar.policy.ui.dialog.mockModesDialogDelegate
import com.android.systemui.statusbar.policy.ui.dialog.mockModesDialogEventLogger
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class ModesDialogViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val repository = kosmos.fakeZenModeRepository
    private val interactor = kosmos.zenModeInteractor
    private val mockDialogDelegate = kosmos.mockModesDialogDelegate
    private val mockDialogEventLogger = kosmos.mockModesDialogEventLogger

    private lateinit var underTest: ModesDialogViewModel

    private lateinit var timeScheduleMode: ZenMode
    private lateinit var timeScheduleInfo: ScheduleInfo

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest =
            ModesDialogViewModel(
                kosmos.applicationContext,
                interactor,
                kosmos.testDispatcher,
                kosmos.mockModesDialogDelegate,
                kosmos.mockModesDialogEventLogger,
            )

        timeScheduleInfo = ScheduleInfo()
        timeScheduleInfo.days = intArrayOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY)
        timeScheduleInfo.startHour = 11
        timeScheduleInfo.endHour = 15
        timeScheduleMode =
            TestModeBuilder()
                .setPackage(SystemZenRules.PACKAGE_ANDROID)
                .setType(AutomaticZenRule.TYPE_SCHEDULE_TIME)
                .setManualInvocationAllowed(true)
                .setConditionId(ZenModeConfig.toScheduleConditionId(timeScheduleInfo))
                .setTriggerDescription(
                    SystemZenRules.getTriggerDescriptionForScheduleTime(mContext, timeScheduleInfo)
                )
                .build()
    }

    @Test
    fun tiles_filtersOutUserDisabledModes() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tiles)

            repository.addModes(
                listOf(
                    TestModeBuilder()
                        .setName("Disabled by user")
                        .setEnabled(false, /* byUser= */ true)
                        .build(),
                    TestModeBuilder()
                        .setName("Disabled by other")
                        .setEnabled(false, /* byUser= */ false)
                        .build(),
                    TestModeBuilder()
                        .setName("Enabled")
                        .setEnabled(true)
                        .setManualInvocationAllowed(true)
                        .build(),
                    TestModeBuilder()
                        .setName("Disabled with manual")
                        .setEnabled(false, /* byUser= */ true)
                        .setManualInvocationAllowed(true)
                        .build(),
                )
            )
            runCurrent()

            assertThat(tiles).hasSize(3)
            with(tiles?.elementAt(0)!!) {
                assertThat(this.text).isEqualTo("Do Not Disturb")
                assertThat(this.subtext).isEqualTo("Off")
                assertThat(this.enabled).isEqualTo(false)
            }
            with(tiles?.elementAt(1)!!) {
                assertThat(this.text).isEqualTo("Disabled by other")
                assertThat(this.subtext).isEqualTo("Not set")
                assertThat(this.enabled).isEqualTo(false)
            }
            with(tiles?.elementAt(2)!!) {
                assertThat(this.text).isEqualTo("Enabled")
                assertThat(this.subtext).isEqualTo("Off")
                assertThat(this.enabled).isEqualTo(false)
            }
        }

    @Test
    fun tiles_filtersOutInactiveModesWithoutManualInvocation() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tiles)

            repository.addModes(
                listOf(
                    TestModeBuilder()
                        .setName("Active without manual")
                        .setActive(true)
                        .setManualInvocationAllowed(false)
                        .build(),
                    TestModeBuilder()
                        .setName("Active with manual")
                        .setTriggerDescription("trigger description")
                        .setActive(true)
                        .setManualInvocationAllowed(true)
                        .build(),
                    TestModeBuilder()
                        .setName("Inactive with manual")
                        .setActive(false)
                        .setManualInvocationAllowed(true)
                        .build(),
                    TestModeBuilder()
                        .setName("Inactive without manual")
                        .setActive(false)
                        .setManualInvocationAllowed(false)
                        .build(),
                )
            )
            runCurrent()

            // Manual DND is included by default
            assertThat(tiles).hasSize(4)
            with(tiles?.elementAt(0)!!) {
                assertThat(this.text).isEqualTo("Do Not Disturb")
                assertThat(this.subtext).isEqualTo("Off")
                assertThat(this.enabled).isEqualTo(false)
            }
            with(tiles?.elementAt(1)!!) {
                assertThat(this.text).isEqualTo("Active without manual")
                assertThat(this.subtext).isEqualTo("On")
                assertThat(this.enabled).isEqualTo(true)
            }
            with(tiles?.elementAt(2)!!) {
                assertThat(this.text).isEqualTo("Active with manual")
                assertThat(this.subtext).isEqualTo("On • trigger description")
                assertThat(this.enabled).isEqualTo(true)
            }
            with(tiles?.elementAt(3)!!) {
                assertThat(this.text).isEqualTo("Inactive with manual")
                assertThat(this.subtext).isEqualTo("Off")
                assertThat(this.enabled).isEqualTo(false)
            }
        }

    @Test
    fun tiles_stableWhileCollecting() =
        testScope.runTest {
            val job = Job()
            val tiles by collectLastValue(underTest.tiles, context = job)

            repository.addModes(
                listOf(
                    TestModeBuilder()
                        .setName("Active without manual")
                        .setActive(true)
                        .setManualInvocationAllowed(false)
                        .build(),
                    TestModeBuilder()
                        .setName("Active with manual")
                        .setActive(true)
                        .setManualInvocationAllowed(true)
                        .build(),
                    TestModeBuilder()
                        .setName("Inactive with manual")
                        .setActive(false)
                        .setManualInvocationAllowed(true)
                        .build(),
                    TestModeBuilder()
                        .setName("Inactive without manual")
                        .setActive(false)
                        .setManualInvocationAllowed(false)
                        .build(),
                )
            )
            runCurrent()

            // Manual DND is included by default
            assertThat(tiles).hasSize(4)

            // Check that tile is initially present
            with(tiles?.elementAt(1)!!) {
                assertThat(this.text).isEqualTo("Active without manual")
                assertThat(this.subtext).isEqualTo("On")
                assertThat(this.enabled).isEqualTo(true)

                // Click tile to toggle it
                this.onClick()
                runCurrent()
            }
            // Check that tile is still present at the same location, but turned off
            assertThat(tiles).hasSize(4)
            with(tiles?.elementAt(1)!!) {
                assertThat(this.text).isEqualTo("Active without manual")
                assertThat(this.subtext).isEqualTo("Manage in settings")
                assertThat(this.enabled).isEqualTo(false)
            }

            // Stop collecting, then start again
            job.cancel()
            val tiles2 by collectLastValue(underTest.tiles)
            runCurrent()

            // Check that tile is now gone
            assertThat(tiles2).hasSize(3)
            assertThat(tiles2?.elementAt(1)!!.text).isEqualTo("Active with manual")
            assertThat(tiles2?.elementAt(2)!!.text).isEqualTo("Inactive with manual")
        }

    @Test
    fun tiles_filtersOutRemovedModes() =
        testScope.runTest {
            val job = Job()
            val tiles by collectLastValue(underTest.tiles, context = job)

            repository.addModes(
                listOf(
                    TestModeBuilder()
                        .setId("A")
                        .setName("Active without manual")
                        .setActive(true)
                        .setManualInvocationAllowed(false)
                        .build(),
                    TestModeBuilder()
                        .setId("B")
                        .setName("Active with manual")
                        .setActive(true)
                        .setManualInvocationAllowed(true)
                        .build(),
                    TestModeBuilder()
                        .setId("C")
                        .setName("Inactive with manual")
                        .setActive(false)
                        .setManualInvocationAllowed(true)
                        .build(),
                )
            )
            runCurrent()

            // Manual DND is included by default
            assertThat(tiles).hasSize(4)

            repository.removeMode("A")
            runCurrent()

            assertThat(tiles).hasSize(3)

            repository.removeMode("B")
            runCurrent()

            assertThat(tiles).hasSize(2)

            repository.removeMode("C")
            runCurrent()

            assertThat(tiles).hasSize(1)
        }

    @Test
    fun tiles_calculatesSubtext() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tiles)

            repository.addModes(
                listOf(
                    TestModeBuilder()
                        .setName("With description, inactive")
                        .setManualInvocationAllowed(true)
                        .setTriggerDescription("When the going gets tough")
                        .setActive(false)
                        .build(),
                    TestModeBuilder()
                        .setName("With description, active")
                        .setManualInvocationAllowed(true)
                        .setTriggerDescription("When in Rome")
                        .setActive(true)
                        .build(),
                    TestModeBuilder()
                        .setName("With description, needs setup")
                        .setManualInvocationAllowed(true)
                        .setTriggerDescription("When you find yourself in a hole")
                        .setEnabled(false, /* byUser= */ false)
                        .build(),
                    TestModeBuilder()
                        .setName("Without description, inactive")
                        .setManualInvocationAllowed(true)
                        .setTriggerDescription(null)
                        .setActive(false)
                        .build(),
                    TestModeBuilder()
                        .setName("Without description, active")
                        .setManualInvocationAllowed(true)
                        .setTriggerDescription(null)
                        .setActive(true)
                        .build(),
                    TestModeBuilder()
                        .setName("Without description, needs setup")
                        .setManualInvocationAllowed(true)
                        .setTriggerDescription(null)
                        .setEnabled(false, /* byUser= */ false)
                        .build(),
                    timeScheduleMode,
                )
            )
            runCurrent()

            // Manual DND is included by default
            assertThat(tiles!!).hasSize(8)
            assertThat(tiles!![1].subtext).isEqualTo("When the going gets tough")
            assertThat(tiles!![2].subtext).isEqualTo("On • When in Rome")
            assertThat(tiles!![3].subtext).isEqualTo("Not set")
            assertThat(tiles!![4].subtext).isEqualTo("Off")
            assertThat(tiles!![5].subtext).isEqualTo("On")
            assertThat(tiles!![6].subtext).isEqualTo("Not set")
            assertThat(tiles!![7].subtext).isEqualTo(timeScheduleMode.triggerDescription)
        }

    @Test
    fun tiles_populatesFieldsForAccessibility() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tiles)

            repository.addModes(
                listOf(
                    TestModeBuilder()
                        .setName("With description, inactive")
                        .setManualInvocationAllowed(true)
                        .setTriggerDescription("When the going gets tough")
                        .setActive(false)
                        .build(),
                    TestModeBuilder()
                        .setName("With description, active")
                        .setManualInvocationAllowed(true)
                        .setTriggerDescription("When in Rome")
                        .setActive(true)
                        .build(),
                    TestModeBuilder()
                        .setName("With description, needs setup")
                        .setManualInvocationAllowed(true)
                        .setTriggerDescription("When you find yourself in a hole")
                        .setEnabled(false, /* byUser= */ false)
                        .build(),
                    TestModeBuilder()
                        .setName("Without description, inactive")
                        .setManualInvocationAllowed(true)
                        .setTriggerDescription(null)
                        .setActive(false)
                        .build(),
                    TestModeBuilder()
                        .setName("Without description, active")
                        .setManualInvocationAllowed(true)
                        .setTriggerDescription(null)
                        .setActive(true)
                        .build(),
                    TestModeBuilder()
                        .setName("Without description, needs setup")
                        .setManualInvocationAllowed(true)
                        .setTriggerDescription(null)
                        .setEnabled(false, /* byUser= */ false)
                        .build(),
                    timeScheduleMode,
                )
            )
            runCurrent()

            // Manual DND is included by default
            assertThat(tiles!!).hasSize(8)
            with(tiles?.elementAt(1)!!) {
                assertThat(this.stateDescription).isEqualTo("Off")
                assertThat(this.subtextDescription).isEqualTo("When the going gets tough")
            }
            with(tiles?.elementAt(2)!!) {
                assertThat(this.stateDescription).isEqualTo("On")
                assertThat(this.subtextDescription).isEqualTo("When in Rome")
            }
            with(tiles?.elementAt(3)!!) {
                assertThat(this.stateDescription).isEqualTo("Off")
                assertThat(this.subtextDescription).isEqualTo("Not set")
            }
            with(tiles?.elementAt(4)!!) {
                assertThat(this.stateDescription).isEqualTo("Off")
                assertThat(this.subtextDescription).isEmpty()
            }
            with(tiles?.elementAt(5)!!) {
                assertThat(this.stateDescription).isEqualTo("On")
                assertThat(this.subtextDescription).isEmpty()
            }
            with(tiles?.elementAt(6)!!) {
                assertThat(this.stateDescription).isEqualTo("Off")
                assertThat(this.subtextDescription).isEqualTo("Not set")
            }
            with(tiles?.elementAt(7)!!) {
                assertThat(this.stateDescription).isEqualTo("Off")
                assertThat(this.subtextDescription)
                    .isEqualTo(
                        SystemZenRules.getDaysOfWeekFull(context, timeScheduleInfo) +
                            ", " +
                            SystemZenRules.getTimeSummary(context, timeScheduleInfo)
                    )
            }

            // All tiles have the same long click info
            tiles!!.forEach { assertThat(it.onLongClickLabel).isEqualTo("Open settings") }
        }

    @Test
    fun onClick_togglesTileState() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tiles)

            val modeId = "id"
            repository.addMode(
                TestModeBuilder()
                    .setId(modeId)
                    .setName("Test")
                    .setManualInvocationAllowed(true)
                    .build()
            )
            runCurrent()

            // Manual DND is included by default
            assertThat(tiles).hasSize(2)
            assertThat(tiles?.elementAt(1)?.enabled).isFalse()

            // Trigger onClick
            tiles?.elementAt(1)?.onClick?.let { it() }
            runCurrent()

            assertThat(tiles?.elementAt(1)?.enabled).isTrue()

            // Trigger onClick
            tiles?.elementAt(1)?.onClick?.let { it() }
            runCurrent()

            assertThat(tiles?.elementAt(1)?.enabled).isFalse()
        }

    @Test
    fun onClick_noManualActivation() =
        testScope.runTest {
            val job = Job()
            val tiles by collectLastValue(underTest.tiles, context = job)

            repository.addMode(
                TestModeBuilder()
                    .setName("Active without manual")
                    .setActive(true)
                    .setManualInvocationAllowed(false)
                    .build()
            )
            runCurrent()

            // Manual DND is included by default
            assertThat(tiles).hasSize(2)

            // Click tile to toggle it off
            tiles?.elementAt(1)!!.onClick()
            runCurrent()

            assertThat(tiles).hasSize(2)
            with(tiles?.elementAt(1)!!) {
                assertThat(this.text).isEqualTo("Active without manual")
                assertThat(this.subtext).isEqualTo("Manage in settings")
                assertThat(this.enabled).isEqualTo(false)

                // Press the tile again
                this.onClick()
                runCurrent()
            }

            // Check that nothing happened
            with(tiles?.elementAt(1)!!) {
                assertThat(this.text).isEqualTo("Active without manual")
                assertThat(this.subtext).isEqualTo("Manage in settings")
                assertThat(this.enabled).isEqualTo(false)
            }
        }

    @Test
    fun onClick_setUp() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tiles)

            repository.addMode(
                TestModeBuilder()
                    .setId("ID")
                    .setName("Disabled by other")
                    .setEnabled(false, /* byUser= */ false)
                    .build()
            )
            runCurrent()

            // Manual DND is included by default
            assertThat(tiles).hasSize(2)
            with(tiles?.elementAt(1)!!) {
                assertThat(this.text).isEqualTo("Disabled by other")
                assertThat(this.subtext).isEqualTo("Not set")
                assertThat(this.enabled).isEqualTo(false)

                // Click the tile
                this.onClick()
                runCurrent()
            }

            // Check that it launched the correct intent
            val intentCaptor = argumentCaptor<Intent>()
            verify(mockDialogDelegate).launchFromDialog(intentCaptor.capture())
            val intent = intentCaptor.lastValue
            assertThat(intent.action).isEqualTo(Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS)
            assertThat(intent.extras?.getString(Settings.EXTRA_AUTOMATIC_ZEN_RULE_ID))
                .isEqualTo("ID")

            // Check that nothing happened to the tile
            with(tiles?.elementAt(1)!!) {
                assertThat(this.text).isEqualTo("Disabled by other")
                assertThat(this.subtext).isEqualTo("Not set")
                assertThat(this.enabled).isEqualTo(false)
            }
        }

    @Test
    fun onLongClick_launchesIntent() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tiles)
            val intentCaptor = argumentCaptor<Intent>()

            val modeId = "id"
            repository.addModes(
                listOf(
                    TestModeBuilder()
                        .setId(modeId)
                        .setId("A")
                        .setActive(true)
                        .setManualInvocationAllowed(true)
                        .build(),
                    TestModeBuilder()
                        .setId(modeId)
                        .setId("B")
                        .setActive(false)
                        .setManualInvocationAllowed(true)
                        .build(),
                )
            )
            runCurrent()

            // Manual DND is included by default
            assertThat(tiles).hasSize(3)

            // Trigger onLongClick for A
            tiles?.elementAt(1)?.onLongClick?.let { it() }
            runCurrent()

            // Check that it launched the correct intent
            verify(mockDialogDelegate).launchFromDialog(intentCaptor.capture())
            var intent = intentCaptor.lastValue
            assertThat(intent.action).isEqualTo(Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS)
            assertThat(intent.extras?.getString(Settings.EXTRA_AUTOMATIC_ZEN_RULE_ID))
                .isEqualTo("A")

            clearInvocations(mockDialogDelegate)

            // Trigger onLongClick for B
            tiles?.last()?.onLongClick?.let { it() }
            runCurrent()

            // Check that it launched the correct intent
            verify(mockDialogDelegate).launchFromDialog(intentCaptor.capture())
            intent = intentCaptor.lastValue
            assertThat(intent.action).isEqualTo(Settings.ACTION_AUTOMATIC_ZEN_RULE_SETTINGS)
            assertThat(intent.extras?.getString(Settings.EXTRA_AUTOMATIC_ZEN_RULE_ID))
                .isEqualTo("B")
        }

    @Test
    fun onClick_logsOnOffEvents() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tiles)

            repository.activateMode(MANUAL_DND)
            repository.addModes(
                listOf(
                    TestModeBuilder()
                        .setId("id1")
                        .setName("Inactive Mode One")
                        .setActive(false)
                        .setManualInvocationAllowed(true)
                        .build(),
                    TestModeBuilder()
                        .setId("id2")
                        .setName("Active Non-Invokable Mode Two") // but can be turned off by tile
                        .setActive(true)
                        .setManualInvocationAllowed(false)
                        .build(),
                )
            )
            runCurrent()

            // Manual DND is included by default
            assertThat(tiles).hasSize(3)

            // Trigger onClick for each tile in sequence
            tiles?.forEach { it.onClick.invoke() }
            runCurrent()

            val onModeCaptor = argumentCaptor<ZenMode>()
            val offModeCaptor = argumentCaptor<ZenMode>()

            // manual mode and mode 2 should have turned off
            verify(mockDialogEventLogger, times(2)).logModeOff(offModeCaptor.capture())
            val off0 = offModeCaptor.firstValue
            assertThat(off0.isManualDnd).isTrue()

            val off1 = offModeCaptor.secondValue
            assertThat(off1.id).isEqualTo("id2")

            // should also have logged turning mode 1 on
            verify(mockDialogEventLogger).logModeOn(onModeCaptor.capture())
            val on = onModeCaptor.lastValue
            assertThat(on.id).isEqualTo("id1")
        }

    @Test
    fun onLongClick_logsSettingsEvents() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tiles)

            repository.addMode(
                TestModeBuilder()
                    .setId("id1")
                    .setName("Inactive Mode One")
                    .setActive(false)
                    .setManualInvocationAllowed(true)
                    .build()
            )
            runCurrent()

            // Manual DND is included by default
            assertThat(tiles).hasSize(2)
            val modeCaptor = argumentCaptor<ZenMode>()

            // long click manual DND and then automatic mode
            tiles?.forEach { it.onLongClick.invoke() }
            runCurrent()

            verify(mockDialogEventLogger, times(2)).logModeSettings(modeCaptor.capture())
            val manualMode = modeCaptor.firstValue
            assertThat(manualMode.isManualDnd).isTrue()

            val automaticMode = modeCaptor.lastValue
            assertThat(automaticMode.id).isEqualTo("id1")
        }
}
