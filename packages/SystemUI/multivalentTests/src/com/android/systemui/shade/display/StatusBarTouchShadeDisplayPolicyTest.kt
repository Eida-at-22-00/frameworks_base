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

package com.android.systemui.shade.display

import android.platform.test.annotations.EnableFlags
import android.view.Display
import android.view.Display.TYPE_EXTERNAL
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.display.data.repository.display
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.shade.data.repository.statusBarTouchShadeDisplayPolicy
import com.android.systemui.shade.domain.interactor.notificationElement
import com.android.systemui.shade.domain.interactor.qsElement
import com.android.systemui.shade.shared.flag.ShadeWindowGoesAround
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(ShadeWindowGoesAround.FLAG_NAME)
class StatusBarTouchShadeDisplayPolicyTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val testScope = kosmos.testScope
    private val displayRepository = kosmos.displayRepository

    private val underTest = kosmos.statusBarTouchShadeDisplayPolicy

    private fun createMotionEventForDisplay(displayId: Int, xCoordinate: Float = 0f): MotionEvent {
        return mock<MotionEvent> {
            on { getX() } doReturn xCoordinate
            on { getDisplayId() } doReturn displayId
        }
    }

    @Test
    fun displayId_defaultToDefaultDisplay() {
        assertThat(underTest.displayId.value).isEqualTo(Display.DEFAULT_DISPLAY)
    }

    @Test
    fun onStatusBarTouched_called_updatesDisplayId() =
        testScope.runTest {
            val displayId by collectLastValue(underTest.displayId)

            displayRepository.addDisplays(display(id = 2, type = TYPE_EXTERNAL))
            underTest.onStatusBarTouched(createMotionEventForDisplay(2), STATUS_BAR_WIDTH)

            assertThat(displayId).isEqualTo(2)
        }

    @Test
    fun onStatusBarTouched_notExistentDisplay_displayIdNotUpdated() =
        testScope.runTest {
            val displayIds by collectValues(underTest.displayId)
            assertThat(displayIds).isEqualTo(listOf(Display.DEFAULT_DISPLAY))

            underTest.onStatusBarTouched(createMotionEventForDisplay(2), STATUS_BAR_WIDTH)

            // Never set, as 2 was not a display according to the repository.
            assertThat(displayIds).isEqualTo(listOf(Display.DEFAULT_DISPLAY))
        }

    @Test
    fun onStatusBarTouched_afterDisplayRemoved_goesBackToDefaultDisplay() =
        testScope.runTest {
            val displayId by collectLastValue(underTest.displayId)

            displayRepository.addDisplays(display(id = 2, type = TYPE_EXTERNAL))
            underTest.onStatusBarTouched(createMotionEventForDisplay(2), STATUS_BAR_WIDTH)

            assertThat(displayId).isEqualTo(2)

            displayRepository.removeDisplay(2)

            assertThat(displayId).isEqualTo(Display.DEFAULT_DISPLAY)
        }

    @Test
    fun onStatusBarTouched_leftSide_intentSetToNotifications() =
        testScope.runTest {
            underTest.onStatusBarTouched(
                createMotionEventForDisplay(2, STATUS_BAR_WIDTH * 0.1f),
                STATUS_BAR_WIDTH,
            )

            assertThat(underTest.consumeExpansionIntent()).isEqualTo(kosmos.notificationElement)
        }

    @Test
    fun onStatusBarTouched_rightSide_intentSetToQs() =
        testScope.runTest {
            underTest.onStatusBarTouched(
                createMotionEventForDisplay(2, STATUS_BAR_WIDTH * 0.95f),
                STATUS_BAR_WIDTH,
            )

            assertThat(underTest.consumeExpansionIntent()).isEqualTo(kosmos.qsElement)
        }

    @Test
    fun onStatusBarTouched_nullAfterConsumed() =
        testScope.runTest {
            underTest.onStatusBarTouched(
                createMotionEventForDisplay(2, STATUS_BAR_WIDTH * 0.1f),
                STATUS_BAR_WIDTH,
            )
            assertThat(underTest.consumeExpansionIntent()).isEqualTo(kosmos.notificationElement)

            assertThat(underTest.consumeExpansionIntent()).isNull()
        }

    companion object {
        private const val STATUS_BAR_WIDTH = 100
    }
}
