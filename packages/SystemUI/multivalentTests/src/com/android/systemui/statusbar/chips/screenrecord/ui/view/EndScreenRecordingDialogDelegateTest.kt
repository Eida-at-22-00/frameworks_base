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

package com.android.systemui.statusbar.chips.screenrecord.ui.view

import android.app.ActivityManager
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.applicationContext
import android.content.packageManager
import android.content.pm.ApplicationInfo
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.View
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.res.R
import com.android.systemui.screenrecord.data.repository.screenRecordRepository
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.endMediaProjectionDialogHelper
import com.android.systemui.statusbar.chips.screenrecord.domain.interactor.screenRecordChipInteractor
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class EndScreenRecordingDialogDelegateTest : SysuiTestCase() {
    private val kosmos = testKosmos().also { it.testCase = this }

    private val sysuiDialog = mock<SystemUIDialog>()

    private lateinit var underTest: EndScreenRecordingDialogDelegate

    @Test
    fun icon() {
        createAndSetDelegate(recordedTask = null)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog).setIcon(R.drawable.ic_screenrecord)
    }

    @Test
    fun title() {
        createAndSetDelegate(recordedTask = null)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog).setTitle(R.string.screenrecord_stop_dialog_title)
    }

    @Test
    fun message_noRecordedTask() {
        createAndSetDelegate(recordedTask = null)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog).setMessage(context.getString(R.string.screenrecord_stop_dialog_message))
    }

    @Test
    fun message_hasRecordedTask() {
        val baseIntent =
            Intent().apply { this.component = ComponentName("fake.task.package", "cls") }
        val appInfo = mock<ApplicationInfo>()
        whenever(appInfo.loadLabel(kosmos.packageManager)).thenReturn("Fake Package")
        whenever(kosmos.packageManager.getApplicationInfo(eq("fake.task.package"), any<Int>()))
            .thenReturn(appInfo)
        val task = createTask(taskId = 1, baseIntent = baseIntent)

        createAndSetDelegate(task)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog)
            .setMessage(
                context.getString(
                    R.string.screenrecord_stop_dialog_message_specific_app,
                    "Fake Package",
                )
            )
    }

    @Test
    fun negativeButton() {
        createAndSetDelegate(recordedTask = createTask(taskId = 1))

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog).setNegativeButton(R.string.close_dialog_button, null)
    }

    @Test
    fun positiveButton() =
        kosmos.testScope.runTest {
            createAndSetDelegate(recordedTask = null)

            underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

            val clickListener = argumentCaptor<DialogInterface.OnClickListener>()

            // Verify the button has the right text
            verify(sysuiDialog)
                .setPositiveButton(
                    eq(R.string.screenrecord_stop_dialog_button),
                    clickListener.capture(),
                )

            // Verify that clicking the button stops the recording
            assertThat(kosmos.screenRecordRepository.stopRecordingInvoked).isFalse()

            clickListener.firstValue.onClick(mock<DialogInterface>(), 0)
            runCurrent()

            assertThat(kosmos.screenRecordRepository.stopRecordingInvoked).isTrue()
        }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_SHOW_STOP_DIALOG_POST_CALL_END)
    fun accessibilityDataSensitive_flagEnabled_appliesSetting() {
        createAndSetDelegate(recordedTask = null)

        val window = mock<Window>()
        val decorView = mock<View>()
        whenever(sysuiDialog.window).thenReturn(window)
        whenever(window.decorView).thenReturn(decorView)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(decorView).setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
    }

    @Test
    @DisableFlags(com.android.media.projection.flags.Flags.FLAG_SHOW_STOP_DIALOG_POST_CALL_END)
    fun accessibilityDataSensitive_flagDisabled_doesNotApplySetting() {
        createAndSetDelegate(recordedTask = null)

        val window = mock<Window>()
        val decorView = mock<View>()
        whenever(sysuiDialog.window).thenReturn(window)
        whenever(window.decorView).thenReturn(decorView)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(decorView, never()).setAccessibilityDataSensitive(any())
    }

    private fun createAndSetDelegate(recordedTask: ActivityManager.RunningTaskInfo?) {
        underTest =
            EndScreenRecordingDialogDelegate(
                kosmos.endMediaProjectionDialogHelper,
                kosmos.applicationContext,
                stopAction = kosmos.screenRecordChipInteractor::stopRecording,
                recordedTask,
            )
    }
}
