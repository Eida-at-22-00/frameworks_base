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
package com.android.systemui.lowlightclock

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.shared.condition.Condition
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.google.common.truth.Truth
import java.io.PrintWriter
import java.util.Arrays
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ForceLowLightConditionTest : SysuiTestCase() {
    private val kosmos = Kosmos()

    @Mock private lateinit var commandRegistry: CommandRegistry

    @Mock private lateinit var callback: Condition.Callback

    @Mock private lateinit var printWriter: PrintWriter

    private lateinit var condition: ForceLowLightCondition
    private lateinit var command: Command

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        condition = ForceLowLightCondition(kosmos.testScope, commandRegistry)
        condition.addCallback(callback)
        val commandCaptor = argumentCaptor<() -> Command>()
        Mockito.verify(commandRegistry)
            .registerCommand(eq(ForceLowLightCondition.COMMAND_ROOT), commandCaptor.capture())
        command = commandCaptor.lastValue.invoke()
    }

    @Test
    fun testEnableLowLight() =
        kosmos.runTest {
            command.execute(
                printWriter,
                Arrays.asList(ForceLowLightCondition.COMMAND_ENABLE_LOW_LIGHT),
            )
            Mockito.verify(callback).onConditionChanged(condition)
            Truth.assertThat(condition.isConditionSet).isTrue()
            Truth.assertThat(condition.isConditionMet).isTrue()
        }

    @Test
    fun testDisableLowLight() =
        kosmos.runTest {
            command.execute(printWriter, listOf(ForceLowLightCondition.COMMAND_DISABLE_LOW_LIGHT))
            Mockito.verify(callback).onConditionChanged(condition)
            Truth.assertThat(condition.isConditionSet).isTrue()
            Truth.assertThat(condition.isConditionMet).isFalse()
        }

    @Test
    fun testClearEnableLowLight() =
        kosmos.runTest {
            command.execute(printWriter, listOf(ForceLowLightCondition.COMMAND_ENABLE_LOW_LIGHT))
            Mockito.verify(callback).onConditionChanged(condition)
            Truth.assertThat(condition.isConditionSet).isTrue()
            Truth.assertThat(condition.isConditionMet).isTrue()
            Mockito.clearInvocations(callback)
            command.execute(printWriter, listOf(ForceLowLightCondition.COMMAND_CLEAR_LOW_LIGHT))
            Mockito.verify(callback).onConditionChanged(condition)
            Truth.assertThat(condition.isConditionSet).isFalse()
            Truth.assertThat(condition.isConditionMet).isFalse()
        }
}
