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

package com.android.systemui.statusbar.chips.casttootherdevice.ui.view

import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.applicationContext
import android.content.packageManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.View
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.mediaProjectionChipInteractor
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.ProjectionChipModel
import com.android.systemui.statusbar.chips.mediaprojection.ui.view.endMediaProjectionDialogHelper
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
class EndCastScreenToOtherDeviceDialogDelegateTest : SysuiTestCase() {
    private val kosmos = testKosmos().also { it.testCase = this }
    private val sysuiDialog = mock<SystemUIDialog>()
    private lateinit var underTest: EndCastScreenToOtherDeviceDialogDelegate

    @Test
    fun icon() {
        createAndSetDelegate(ENTIRE_SCREEN)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog).setIcon(R.drawable.ic_cast_connected)
    }

    @Test
    fun title() {
        createAndSetDelegate(SINGLE_TASK)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog).setTitle(R.string.cast_to_other_device_stop_dialog_title)
    }

    @Test
    fun message_entireScreen_unknownDevice() {
        createAndSetDelegate(
            MediaProjectionState.Projecting.EntireScreen(HOST_PACKAGE, hostDeviceName = null)
        )

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog)
            .setMessage(
                context.getString(R.string.cast_to_other_device_stop_dialog_message_entire_screen)
            )
    }

    @Test
    fun message_entireScreen_hasDevice() {
        createAndSetDelegate(
            MediaProjectionState.Projecting.EntireScreen(
                HOST_PACKAGE,
                hostDeviceName = "My Favorite Device",
            )
        )

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog)
            .setMessage(
                context.getString(
                    R.string.cast_to_other_device_stop_dialog_message_entire_screen_with_device,
                    "My Favorite Device",
                )
            )
    }

    @Test
    fun message_singleTask_unknownAppName_unknownDevice() {
        val baseIntent =
            Intent().apply { this.component = ComponentName("fake.task.package", "cls") }
        whenever(kosmos.packageManager.getApplicationInfo(eq("fake.task.package"), any<Int>()))
            .thenThrow(PackageManager.NameNotFoundException())

        createAndSetDelegate(
            MediaProjectionState.Projecting.SingleTask(
                HOST_PACKAGE,
                hostDeviceName = null,
                createTask(taskId = 1, baseIntent = baseIntent),
            )
        )

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog)
            .setMessage(
                context.getString(R.string.cast_to_other_device_stop_dialog_message_generic)
            )
    }

    @Test
    fun message_singleTask_unknownAppName_hasDevice() {
        val baseIntent =
            Intent().apply { this.component = ComponentName("fake.task.package", "cls") }
        whenever(kosmos.packageManager.getApplicationInfo(eq("fake.task.package"), any<Int>()))
            .thenThrow(PackageManager.NameNotFoundException())

        createAndSetDelegate(
            MediaProjectionState.Projecting.SingleTask(
                HOST_PACKAGE,
                hostDeviceName = "My Favorite Device",
                createTask(taskId = 1, baseIntent = baseIntent),
            )
        )

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog)
            .setMessage(
                context.getString(
                    R.string.cast_to_other_device_stop_dialog_message_generic_with_device,
                    "My Favorite Device",
                )
            )
    }

    @Test
    fun message_singleTask_hasAppName_unknownDevice() {
        val baseIntent =
            Intent().apply { this.component = ComponentName("fake.task.package", "cls") }
        val appInfo = mock<ApplicationInfo>()
        whenever(appInfo.loadLabel(kosmos.packageManager)).thenReturn("Fake Package")
        whenever(kosmos.packageManager.getApplicationInfo(eq("fake.task.package"), any<Int>()))
            .thenReturn(appInfo)

        createAndSetDelegate(
            MediaProjectionState.Projecting.SingleTask(
                HOST_PACKAGE,
                hostDeviceName = null,
                createTask(taskId = 1, baseIntent = baseIntent),
            )
        )

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog)
            .setMessage(
                context.getString(
                    R.string.cast_to_other_device_stop_dialog_message_specific_app,
                    "Fake Package",
                )
            )
    }

    @Test
    fun message_singleTask_hasAppName_hasDevice() {
        val baseIntent =
            Intent().apply { this.component = ComponentName("fake.task.package", "cls") }
        val appInfo = mock<ApplicationInfo>()
        whenever(appInfo.loadLabel(kosmos.packageManager)).thenReturn("Fake Package")
        whenever(kosmos.packageManager.getApplicationInfo(eq("fake.task.package"), any<Int>()))
            .thenReturn(appInfo)

        createAndSetDelegate(
            MediaProjectionState.Projecting.SingleTask(
                HOST_PACKAGE,
                hostDeviceName = "My Favorite Device",
                createTask(taskId = 1, baseIntent = baseIntent),
            )
        )

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog)
            .setMessage(
                context.getString(
                    R.string.cast_to_other_device_stop_dialog_message_specific_app_with_device,
                    "Fake Package",
                    "My Favorite Device",
                )
            )
    }

    @Test
    fun negativeButton() {
        createAndSetDelegate(ENTIRE_SCREEN)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog).setNegativeButton(R.string.close_dialog_button, null)
    }

    @Test
    fun positiveButton() =
        kosmos.testScope.runTest {
            createAndSetDelegate(SINGLE_TASK)

            underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

            val clickListener = argumentCaptor<DialogInterface.OnClickListener>()

            // Verify the button has the right text
            verify(sysuiDialog)
                .setPositiveButton(
                    eq(R.string.cast_to_other_device_stop_dialog_button),
                    clickListener.capture(),
                )

            // Verify that clicking the button stops the recording
            assertThat(kosmos.fakeMediaProjectionRepository.stopProjectingInvoked).isFalse()

            clickListener.firstValue.onClick(mock<DialogInterface>(), 0)
            runCurrent()

            assertThat(kosmos.fakeMediaProjectionRepository.stopProjectingInvoked).isTrue()
        }

    @Test
    @EnableFlags(com.android.media.projection.flags.Flags.FLAG_SHOW_STOP_DIALOG_POST_CALL_END)
    fun accessibilityDataSensitive_flagEnabled_appliesSetting() {
        createAndSetDelegate(ENTIRE_SCREEN)

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
        createAndSetDelegate(ENTIRE_SCREEN)

        val window = mock<Window>()
        val decorView = mock<View>()
        whenever(sysuiDialog.window).thenReturn(window)
        whenever(window.decorView).thenReturn(decorView)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(decorView, never()).setAccessibilityDataSensitive(any())
    }

    private fun createAndSetDelegate(state: MediaProjectionState.Projecting) {
        underTest =
            EndCastScreenToOtherDeviceDialogDelegate(
                kosmos.endMediaProjectionDialogHelper,
                kosmos.applicationContext,
                stopAction = kosmos.mediaProjectionChipInteractor::stopProjecting,
                ProjectionChipModel.Projecting(
                    ProjectionChipModel.Receiver.CastToOtherDevice,
                    ProjectionChipModel.ContentType.Screen,
                    state,
                ),
            )
    }

    companion object {
        private const val HOST_PACKAGE = "fake.host.package"
        private val ENTIRE_SCREEN =
            MediaProjectionState.Projecting.EntireScreen(HOST_PACKAGE, hostDeviceName = null)
        private val SINGLE_TASK =
            MediaProjectionState.Projecting.SingleTask(
                HOST_PACKAGE,
                hostDeviceName = null,
                createTask(taskId = 1),
            )
    }
}
