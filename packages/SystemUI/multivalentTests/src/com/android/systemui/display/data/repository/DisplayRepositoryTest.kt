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

package com.android.systemui.display.data.repository

import android.hardware.display.DisplayManager
import android.os.Looper
import android.testing.TestableLooper
import android.view.Display
import android.view.Display.DEFAULT_DISPLAY
import android.view.Display.TYPE_EXTERNAL
import android.view.Display.TYPE_INTERNAL
import android.view.IWindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.app.displaylib.DisplayRepository.PendingDisplay
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.FlowValue
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.utils.os.FakeHandler
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
@SmallTest
class DisplayRepositoryTest : SysuiTestCase() {

    private val displayManager = mock<DisplayManager>()
    private val commandQueue = mock<CommandQueue>()
    private val windowManager = mock<IWindowManager>()

    private val displayListener = kotlinArgumentCaptor<DisplayManager.DisplayListener>()
    private val commandQueueCallbacks = kotlinArgumentCaptor<CommandQueue.Callbacks>()
    private val connectedDisplayListener = kotlinArgumentCaptor<DisplayManager.DisplayListener>()

    private val testHandler = FakeHandler(Looper.getMainLooper())
    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val defaultDisplay =
        display(type = TYPE_INTERNAL, id = DEFAULT_DISPLAY, state = Display.STATE_ON)

    // This is Lazy as displays could be set before the instance is created, and we want to verify
    // that the initial state (soon after construction) contains the expected ones set in every
    // test.
    private val displayRepository: DisplayRepositoryImpl by lazy {
        // TODO b/401305290 - move this to kosmos
        val displayRepositoryFromLib =
            com.android.app.displaylib.DisplayRepositoryImpl(
                displayManager,
                testHandler,
                testScope.backgroundScope,
                UnconfinedTestDispatcher(),
            )
        val displaysWithDecorRepository =
            DisplaysWithDecorationsRepositoryImpl(
                commandQueue,
                windowManager,
                testScope.backgroundScope,
                displayRepositoryFromLib,
            )
        DisplayRepositoryImpl(displayRepositoryFromLib, displaysWithDecorRepository).also {
            verify(displayManager, never()).registerDisplayListener(any(), any())
            // It needs to be called, just once, for the initial value.
            verify(displayManager).getDisplays()
        }
    }

    @Before
    fun setup() {
        setDisplays(listOf(defaultDisplay))
        setAllDisplaysIncludingDisabled(DEFAULT_DISPLAY)
    }

    @Test
    fun onFlowCollection_displayListenerRegistered() =
        testScope.runTest {
            val value by latestDisplayFlowValue()

            assertThat(value?.ids()).containsExactly(DEFAULT_DISPLAY)

            verify(displayManager).registerDisplayListener(any(), eq(testHandler), anyLong())
        }

    @Test
    fun afterFlowCollection_displayListenerUnregistered() {
        testScope.runTest {
            val value by latestDisplayFlowValue()

            assertThat(value?.ids()).containsExactly(DEFAULT_DISPLAY)

            verify(displayManager).registerDisplayListener(any(), eq(testHandler), anyLong())
        }
        verify(displayManager).unregisterDisplayListener(any())
    }

    @Test
    fun afterFlowCollection_multipleSusbcriptions_oneRemoved_displayListenerNotUnregistered() {
        testScope.runTest {
            val firstSubscriber by latestDisplayFlowValue()

            assertThat(firstSubscriber).hasSize(1) // Default display only
            verify(displayManager, times(1))
                .registerDisplayListener(displayListener.capture(), eq(testHandler), anyLong())

            val innerScope = TestScope()
            innerScope.runTest {
                val secondSubscriber by latestDisplayFlowValue()
                assertThat(secondSubscriber).hasSize(1)

                // No new registration, just the precedent one.
                verify(displayManager, times(1))
                    .registerDisplayListener(any(), eq(testHandler), anyLong())
            }

            // Let's make sure it has *NOT* been unregistered, as there is still a subscriber.
            setDisplays(1)
            sendOnDisplayAdded(1)
            assertThat(firstSubscriber?.ids()).contains(1)
        }

        // All subscribers are done, unregister should have been called.
        verify(displayManager).unregisterDisplayListener(any())
    }

    @Test
    fun onDisplayAdded_propagated() =
        testScope.runTest {
            val value by latestDisplayFlowValue()

            setDisplays(1)
            sendOnDisplayAdded(1)

            assertThat(value?.ids()).contains(1)
        }

    @Test
    fun onDisplayRemoved_propagated() =
        testScope.runTest {
            val value by latestDisplayFlowValue()

            setDisplays(1, 2, 3, 4)
            sendOnDisplayAdded(1)
            sendOnDisplayAdded(2)
            sendOnDisplayAdded(3)
            sendOnDisplayAdded(4)

            setDisplays(1, 2, 3)
            sendOnDisplayRemoved(4)

            assertThat(value?.ids()).containsExactly(DEFAULT_DISPLAY, 1, 2, 3)
        }

    @Test
    fun onDisplayChanged_propagated() =
        testScope.runTest {
            val value by latestDisplayFlowValue()

            setDisplays(1, 2, 3, 4)
            sendOnDisplayAdded(1)
            sendOnDisplayAdded(2)
            sendOnDisplayAdded(3)
            sendOnDisplayAdded(4)

            displayListener.value.onDisplayChanged(4)

            assertThat(value?.ids()).containsExactly(DEFAULT_DISPLAY, 1, 2, 3, 4)
        }

    @Test
    fun onDisplayConnected_pendingDisplayReceived() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1)

            assertThat(pendingDisplay!!.id).isEqualTo(1)
        }

    @Test
    fun onDisplayDisconnected_pendingDisplayNull() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()
            sendOnDisplayConnected(1)

            assertThat(pendingDisplay).isNotNull()

            sendOnDisplayDisconnected(1)

            assertThat(pendingDisplay).isNull()
        }

    @Test
    fun onDisplayDisconnected_unknownDisplay_doesNotSendNull() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()
            sendOnDisplayConnected(1)

            assertThat(pendingDisplay).isNotNull()

            sendOnDisplayDisconnected(2)

            assertThat(pendingDisplay).isNotNull()
        }

    @Test
    fun onDisplayConnected_multipleTimes_sendsOnlyTheMaximum() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1)
            sendOnDisplayConnected(2)

            assertThat(pendingDisplay!!.id).isEqualTo(2)
        }

    @Test
    fun onPendingDisplay_enable_displayEnabled() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1)
            pendingDisplay!!.enable()

            verify(displayManager).enableConnectedDisplay(eq(1))
        }

    @Test
    fun onPendingDisplay_enableBySysui_disabledBySomeoneElse_pendingDisplayStillIgnored() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1)
            pendingDisplay!!.enable()
            // to mock the display being really enabled:
            sendOnDisplayAdded(1)

            // Simulate the display being disabled by someone else. Now, sysui will have it in the
            // "pending displays" list again, but it should be ignored.
            sendOnDisplayRemoved(1)

            assertThat(pendingDisplay).isNull()
        }

    @Test
    fun onPendingDisplay_ignoredBySysui_enabledDisabledBySomeoneElse_pendingDisplayStillIgnored() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1)
            pendingDisplay!!.ignore()

            // to mock the display being enabled and disabled by someone else:
            sendOnDisplayAdded(1)
            sendOnDisplayRemoved(1)

            // Sysui already decided to ignore it, so the pending display should be null.
            assertThat(pendingDisplay).isNull()
        }

    @Test
    fun onPendingDisplay_disable_displayDisabled() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1)
            pendingDisplay!!.disable()

            verify(displayManager).disableConnectedDisplay(eq(1))
        }

    @Test
    fun onPendingDisplay_ignore_pendingDisplayNull() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()
            sendOnDisplayConnected(1)

            pendingDisplay!!.ignore()

            assertThat(pendingDisplay).isNull()
            verify(displayManager, never()).disableConnectedDisplay(eq(1))
            verify(displayManager, never()).enableConnectedDisplay(eq(1))
        }

    @Test
    fun onPendingDisplay_enabled_pendingDisplayNull() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1)
            assertThat(pendingDisplay).isNotNull()

            setDisplays(1)
            sendOnDisplayAdded(1)

            assertThat(pendingDisplay).isNull()
        }

    @Test
    fun onPendingDisplay_multipleConnected_oneEnabled_pendingDisplayNotNull() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1)
            sendOnDisplayConnected(2)

            assertThat(pendingDisplay).isNotNull()

            setDisplays(1)
            sendOnDisplayAdded(1)

            assertThat(pendingDisplay).isNotNull()
            assertThat(pendingDisplay!!.id).isEqualTo(2)

            setDisplays(1, 2)
            sendOnDisplayAdded(2)

            assertThat(pendingDisplay).isNull()
        }

    @Test
    fun pendingDisplay_connectedDisconnectedAndReconnected_expectedPendingDisplayState() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            // Plug the cable
            sendOnDisplayConnected(1)

            // Enable it
            assertThat(pendingDisplay).isNotNull()
            pendingDisplay!!.enable()

            // Enabled
            verify(displayManager).enableConnectedDisplay(1)
            setDisplays(1)
            sendOnDisplayAdded(1)

            // No more pending displays
            assertThat(pendingDisplay).isNull()

            // Let's disconnect the cable
            setDisplays()
            sendOnDisplayRemoved(1)
            sendOnDisplayDisconnected(1)

            assertThat(pendingDisplay).isNull()

            // Let's reconnect it
            sendOnDisplayConnected(1)

            assertThat(pendingDisplay).isNotNull()
        }

    @Test
    fun initialState_onePendingDisplayOnBoot_notNull() =
        testScope.runTest {
            // 1 is not enabled, but just connected. It should be seen as pending
            setAllDisplaysIncludingDisabled(0, 1)
            setDisplays(0) // 0 is enabled.
            verify(displayManager, never()).getDisplays(any())

            val pendingDisplay by collectLastValue(displayRepository.pendingDisplay)

            verify(displayManager).getDisplays(any())

            assertThat(pendingDisplay).isNotNull()
            assertThat(pendingDisplay!!.id).isEqualTo(1)
        }

    @Test
    fun onPendingDisplay_internalDisplay_ignored() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1, Display.TYPE_INTERNAL)

            assertThat(pendingDisplay).isNull()
        }

    @Test
    fun pendingDisplay_afterConfigChanged_doesNotChange() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1, TYPE_EXTERNAL)
            val initialPendingDisplay: PendingDisplay? = pendingDisplay
            assertThat(pendingDisplay).isNotNull()
            sendOnDisplayChanged(1)

            assertThat(initialPendingDisplay).isEqualTo(pendingDisplay)
        }

    @Test
    fun pendingDisplay_afterNewHigherDisplayConnected_changes() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1, TYPE_EXTERNAL)
            val initialPendingDisplay: PendingDisplay? = pendingDisplay
            assertThat(pendingDisplay).isNotNull()
            sendOnDisplayConnected(2, TYPE_EXTERNAL)

            assertThat(initialPendingDisplay).isNotEqualTo(pendingDisplay)
        }

    @Test
    fun onPendingDisplay_OneInternalAndOneExternalDisplay_internalIgnored() =
        testScope.runTest {
            val pendingDisplay by lastPendingDisplay()

            sendOnDisplayConnected(1, TYPE_EXTERNAL)
            sendOnDisplayConnected(2, Display.TYPE_INTERNAL)

            assertThat(pendingDisplay!!.id).isEqualTo(1)
        }

    @Test
    fun onDisplayAdded_emitsDisplayAdditionEvent() =
        testScope.runTest {
            val display by lastDisplayAdditionEvent()

            sendOnDisplayAdded(1, TYPE_EXTERNAL)

            assertThat(display!!.displayId).isEqualTo(1)
            assertThat(display!!.type).isEqualTo(TYPE_EXTERNAL)
        }

    @Test
    fun displayAdditionEvent_emptyByDefault() =
        testScope.runTest {
            setDisplays(1, 2, 3)

            val lastAddedDisplay by lastDisplayAdditionEvent()

            assertThat(lastAddedDisplay).isNull()
        }

    @Test
    fun displayAdditionEvent_displaysAdded_doesNotReplayEventsToNewSubscribers() =
        testScope.runTest {
            val priorDisplayAdded by lastDisplayAdditionEvent()
            setDisplays(1)
            sendOnDisplayAdded(1)
            assertThat(priorDisplayAdded?.displayId).isEqualTo(1)

            val lastAddedDisplay by collectLastValue(displayRepository.displayAdditionEvent)
            assertThat(lastAddedDisplay).isNull()
        }

    @Test
    fun defaultDisplayOff_changes() =
        testScope.runTest {
            val defaultDisplayOff by latestDefaultDisplayOffFlowValue()

            whenever(defaultDisplay.state).thenReturn(Display.STATE_OFF)
            displayListener.value.onDisplayChanged(DEFAULT_DISPLAY)
            assertThat(defaultDisplayOff).isTrue()

            whenever(defaultDisplay.state).thenReturn(Display.STATE_ON)
            displayListener.value.onDisplayChanged(DEFAULT_DISPLAY)
            assertThat(defaultDisplayOff).isFalse()
        }

    @Test
    fun displayFlow_startsWithDefaultDisplayBeforeAnyEvent() =
        testScope.runTest {
            setDisplays(DEFAULT_DISPLAY)

            val value by latestDisplayFlowValue()

            assertThat(value?.ids()).containsExactly(DEFAULT_DISPLAY)
        }

    @Test
    fun displayFlow_emitsCorrectDisplaysAtFirst() =
        testScope.runTest {
            setDisplays(0, 1, 2)

            val values: List<Set<Display>> by collectValues(displayRepository.displays)

            assertThat(values.toIdSets()).containsExactly(setOf(0, 1, 2))
        }

    @Test
    fun displayFlow_onlyDefaultDisplayAvailable_neverEmitsEmptySet() =
        testScope.runTest {
            setDisplays(0)

            val values: List<Set<Display>> by collectValues(displayRepository.displays)

            assertThat(values.toIdSets()).containsExactly(setOf(0))
        }

    @Test
    fun displayIdToId() =
        testScope.runTest {
            setDisplays(0, 1)

            assertThat(displayRepository.getDisplay(0)?.displayId).isEqualTo(0)
            assertThat(displayRepository.getDisplay(1)?.displayId).isEqualTo(1)
            assertThat(displayRepository.getDisplay(2)).isNull()
        }

    @Test
    fun displayIdsWithSystemDecorations_onStart_emitsDisplaysWithSystemDecorations() =
        testScope.runTest {
            setDisplays(0, 1, 2)
            whenever(windowManager.shouldShowSystemDecors(0)).thenReturn(true)
            whenever(windowManager.shouldShowSystemDecors(1)).thenReturn(false)
            whenever(windowManager.shouldShowSystemDecors(2)).thenReturn(true)

            val displayIdsWithSystemDecorations by latestDisplayIdsWithSystemDecorationsValue()

            assertThat(displayIdsWithSystemDecorations).containsExactly(0, 2)
        }

    @Test
    fun displayIdsWithSystemDecorations_systemDecorationAdded_emitsIncludingNewDisplayIds() =
        testScope.runTest {
            setDisplays(0)
            whenever(windowManager.shouldShowSystemDecors(0)).thenReturn(true)
            val lastDisplayIdsWithSystemDecorations by latestDisplayIdsWithSystemDecorationsValue()

            sendOnDisplayAddSystemDecorations(2)
            sendOnDisplayAddSystemDecorations(3)

            assertThat(lastDisplayIdsWithSystemDecorations).containsExactly(0, 2, 3)
        }

    @Test
    fun displayIdsWithSystemDecorations_systemDecorationAdded_emitsToNewSubscribers() =
        testScope.runTest {
            setDisplays(0)
            whenever(windowManager.shouldShowSystemDecors(0)).thenReturn(true)

            val priorDisplayIdsWithSystemDecorations by latestDisplayIdsWithSystemDecorationsValue()
            sendOnDisplayAddSystemDecorations(1)
            assertThat(priorDisplayIdsWithSystemDecorations).containsExactly(0, 1)

            val lastDisplayIdsWithSystemDecorations by
                collectLastValue(displayRepository.displayIdsWithSystemDecorations)
            assertThat(lastDisplayIdsWithSystemDecorations).containsExactly(0, 1)
        }

    @Test
    fun displayIdsWithSystemDecorations_systemDecorationRemoved_doesNotEmitRemovedDisplayId() =
        testScope.runTest {
            val lastDisplayIdsWithSystemDecorations by latestDisplayIdsWithSystemDecorationsValue()

            sendOnDisplayAddSystemDecorations(1)
            sendOnDisplayAddSystemDecorations(2)
            sendOnDisplayRemoveSystemDecorations(2)

            assertThat(lastDisplayIdsWithSystemDecorations).containsExactly(1)
        }

    @Test
    fun displayIdsWithSystemDecorations_systemDecorationsRemoved_nonExistentDisplay_noEffect() =
        testScope.runTest {
            val lastDisplayIdsWithSystemDecorations by latestDisplayIdsWithSystemDecorationsValue()

            sendOnDisplayAddSystemDecorations(1)
            sendOnDisplayRemoveSystemDecorations(2)

            assertThat(lastDisplayIdsWithSystemDecorations).containsExactly(1)
        }

    @Test
    fun displayIdsWithSystemDecorations_displayRemoved_doesNotEmitRemovedDisplayId() =
        testScope.runTest {
            val lastDisplayIdsWithSystemDecorations by latestDisplayIdsWithSystemDecorationsValue()

            sendOnDisplayAddSystemDecorations(1)
            sendOnDisplayAddSystemDecorations(2)
            sendOnDisplayRemoved(2)

            assertThat(lastDisplayIdsWithSystemDecorations).containsExactly(1)
        }

    @Test
    fun displayIdsWithSystemDecorations_displayRemoved_nonExistentDisplay_noEffect() =
        testScope.runTest {
            val lastDisplayIdsWithSystemDecorations by latestDisplayIdsWithSystemDecorationsValue()

            sendOnDisplayAddSystemDecorations(1)
            sendOnDisplayRemoved(2)

            assertThat(lastDisplayIdsWithSystemDecorations).containsExactly(1)
        }

    @Test
    fun displayIdsWithSystemDecorations_onFlowCollection_commandQueueCallbackRegistered() =
        testScope.runTest {
            val lastDisplayIdsWithSystemDecorations by latestDisplayIdsWithSystemDecorationsValue()

            assertThat(lastDisplayIdsWithSystemDecorations).isEmpty()

            verify(commandQueue, times(1)).addCallback(any())
        }

    @Test
    fun displayIdsWithSystemDecorations_afterFlowCollection_commandQueueCallbackUnregistered() {
        testScope.runTest {
            val lastDisplayIdsWithSystemDecorations by latestDisplayIdsWithSystemDecorationsValue()

            assertThat(lastDisplayIdsWithSystemDecorations).isEmpty()

            verify(commandQueue, times(1)).addCallback(any())
        }
        verify(commandQueue, times(1)).removeCallback(any())
    }

    private fun Iterable<Display>.ids(): List<Int> = map { it.displayId }

    private fun Iterable<Set<Display>>.toIdSets(): List<Set<Int>> = map { it.ids().toSet() }

    // Wrapper to capture the displayListener.
    private fun TestScope.latestDisplayFlowValue(): FlowValue<Set<Display>?> {
        val flowValue = collectLastValue(displayRepository.displays)
        captureAddedRemovedListener()
        return flowValue
    }

    // Wrapper to capture the displayListener.
    private fun TestScope.latestDefaultDisplayOffFlowValue(): FlowValue<Boolean?> {
        val flowValue = collectLastValue(displayRepository.defaultDisplayOff)
        captureAddedRemovedListener()
        return flowValue
    }

    private fun TestScope.lastPendingDisplay(): FlowValue<PendingDisplay?> {
        val flowValue = collectLastValue(displayRepository.pendingDisplay)
        captureAddedRemovedListener()
        verify(displayManager)
            .registerDisplayListener(
                connectedDisplayListener.capture(),
                eq(testHandler),
                eq(0),
                eq(DisplayManager.PRIVATE_EVENT_TYPE_DISPLAY_CONNECTION_CHANGED),
            )
        return flowValue
    }

    private fun TestScope.lastDisplayAdditionEvent(): FlowValue<Display?> {
        val flowValue = collectLastValue(displayRepository.displayAdditionEvent)
        captureAddedRemovedListener()
        return flowValue
    }

    // Wrapper to capture the displayListener and commandQueueCallbacks.
    private fun TestScope.latestDisplayIdsWithSystemDecorationsValue(): FlowValue<Set<Int>?> {
        val flowValue = collectLastValue(displayRepository.displayIdsWithSystemDecorations)
        captureAddedRemovedListener()
        captureCommandQueueCallbacks()
        return flowValue
    }

    private fun captureAddedRemovedListener() {
        verify(displayManager)
            .registerDisplayListener(
                displayListener.capture(),
                eq(testHandler),
                eq(
                    DisplayManager.EVENT_TYPE_DISPLAY_ADDED or
                        DisplayManager.EVENT_TYPE_DISPLAY_CHANGED or
                        DisplayManager.EVENT_TYPE_DISPLAY_REMOVED
                ),
            )
    }

    private fun captureCommandQueueCallbacks() {
        verify(commandQueue).addCallback(commandQueueCallbacks.capture())
    }

    private fun sendOnDisplayAdded(id: Int, displayType: Int) {
        val mockDisplay = display(id = id, type = displayType)
        whenever(displayManager.getDisplay(eq(id))).thenReturn(mockDisplay)
        displayListener.value.onDisplayAdded(id)
    }

    private fun sendOnDisplayAdded(id: Int) {
        displayListener.value.onDisplayAdded(id)
    }

    private fun sendOnDisplayRemoved(id: Int) {
        displayListener.value.onDisplayRemoved(id)
    }

    private fun sendOnDisplayDisconnected(id: Int) {
        connectedDisplayListener.value.onDisplayDisconnected(id)
        whenever(displayManager.getDisplay(eq(id))).thenReturn(null)
    }

    private fun sendOnDisplayConnected(id: Int, displayType: Int = TYPE_EXTERNAL) {
        val mockDisplay = display(id = id, type = displayType)
        whenever(displayManager.getDisplay(eq(id))).thenReturn(mockDisplay)
        connectedDisplayListener.value.onDisplayConnected(id)
    }

    private fun sendOnDisplayChanged(id: Int) {
        connectedDisplayListener.value.onDisplayChanged(id)
    }

    private fun sendOnDisplayRemoveSystemDecorations(id: Int) {
        commandQueueCallbacks.value.onDisplayRemoveSystemDecorations(id)
    }

    private fun sendOnDisplayAddSystemDecorations(id: Int) {
        commandQueueCallbacks.value.onDisplayAddSystemDecorations(id)
    }

    private fun setDisplays(displays: List<Display>) {
        whenever(displayManager.displays).thenReturn(displays.toTypedArray())
        displays.forEach { display ->
            whenever(displayManager.getDisplay(eq(display.displayId))).thenReturn(display)
        }
    }

    private fun setAllDisplaysIncludingDisabled(vararg ids: Int) {
        val displays =
            (ids.toSet() - DEFAULT_DISPLAY) // Default display always added.
                .map { display(type = TYPE_EXTERNAL, id = it) }
                .toTypedArray() + defaultDisplay
        whenever(
                displayManager.getDisplays(
                    eq(DisplayManager.DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED)
                )
            )
            .thenReturn(displays)
        displays.forEach { display ->
            whenever(displayManager.getDisplay(eq(display.displayId))).thenReturn(display)
        }
    }

    private fun setDisplays(vararg ids: Int) {
        // DEFAULT_DISPLAY always there
        val idsToSet = ids.toSet() + DEFAULT_DISPLAY
        setDisplays(idsToSet.map { display(type = TYPE_EXTERNAL, id = it) })
    }
}
