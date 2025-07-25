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

package com.android.systemui.statusbar.chips.sharetoapp.ui.view

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
class EndShareScreenToAppDialogDelegateTest : SysuiTestCase() {
    private val kosmos = testKosmos().also { it.testCase = this }
    private val sysuiDialog = mock<SystemUIDialog>()
    private lateinit var underTest: EndShareScreenToAppDialogDelegate

    @Test
    fun icon() {
        createAndSetDelegate(SINGLE_TASK)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog).setIcon(R.drawable.ic_present_to_all)
    }

    @Test
    fun title() {
        createAndSetDelegate(ENTIRE_SCREEN)
        whenever(kosmos.packageManager.getApplicationInfo(eq(HOST_PACKAGE), any<Int>()))
            .thenThrow(PackageManager.NameNotFoundException())

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog).setTitle(R.string.share_to_app_stop_dialog_title)
    }

    @Test
    fun message_entireScreen_unknownHostPackage() {
        createAndSetDelegate(ENTIRE_SCREEN)
        whenever(kosmos.packageManager.getApplicationInfo(eq(HOST_PACKAGE), any<Int>()))
            .thenThrow(PackageManager.NameNotFoundException())

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog)
            .setMessage(context.getString(R.string.share_to_app_stop_dialog_message_entire_screen))
    }

    @Test
    fun message_entireScreen_hasHostPackage() {
        createAndSetDelegate(ENTIRE_SCREEN)
        val hostAppInfo = mock<ApplicationInfo>()
        whenever(hostAppInfo.loadLabel(kosmos.packageManager)).thenReturn("Host Package")
        whenever(kosmos.packageManager.getApplicationInfo(eq(HOST_PACKAGE), any<Int>()))
            .thenReturn(hostAppInfo)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog)
            .setMessage(
                context.getString(
                    R.string.share_to_app_stop_dialog_message_entire_screen_with_host_app,
                    "Host Package",
                )
            )
    }

    @Test
    fun message_singleTask_unknownAppName() {
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
                context.getString(R.string.share_to_app_stop_dialog_message_single_app_generic)
            )
    }

    @Test
    fun message_singleTask_hasAppName() {
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
                    R.string.share_to_app_stop_dialog_message_single_app_specific,
                    "Fake Package",
                )
            )
    }

    @Test
    fun negativeButton() {
        createAndSetDelegate(SINGLE_TASK)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(sysuiDialog).setNegativeButton(R.string.close_dialog_button, null)
    }

    @Test
    fun positiveButton() =
        kosmos.testScope.runTest {
            createAndSetDelegate(ENTIRE_SCREEN)
            whenever(kosmos.packageManager.getApplicationInfo(eq(HOST_PACKAGE), any<Int>()))
                .thenThrow(PackageManager.NameNotFoundException())

            underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

            val clickListener = argumentCaptor<DialogInterface.OnClickListener>()

            // Verify the button has the right text
            verify(sysuiDialog)
                .setPositiveButton(
                    eq(R.string.share_to_app_stop_dialog_button),
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
        whenever(kosmos.packageManager.getApplicationInfo(eq(HOST_PACKAGE), any<Int>()))
            .thenThrow(PackageManager.NameNotFoundException())

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
        whenever(kosmos.packageManager.getApplicationInfo(eq(HOST_PACKAGE), any<Int>()))
            .thenThrow(PackageManager.NameNotFoundException())

        val window = mock<Window>()
        val decorView = mock<View>()
        whenever(sysuiDialog.window).thenReturn(window)
        whenever(window.decorView).thenReturn(decorView)

        underTest.beforeCreate(sysuiDialog, /* savedInstanceState= */ null)

        verify(decorView, never()).setAccessibilityDataSensitive(any())
    }

    private fun createAndSetDelegate(state: MediaProjectionState.Projecting) {
        underTest =
            EndShareScreenToAppDialogDelegate(
                kosmos.endMediaProjectionDialogHelper,
                kosmos.applicationContext,
                stopAction = kosmos.mediaProjectionChipInteractor::stopProjecting,
                ProjectionChipModel.Projecting(
                    ProjectionChipModel.Receiver.ShareToApp,
                    ProjectionChipModel.ContentType.Screen,
                    state,
                ),
            )
    }

    companion object {
        private const val HOST_PACKAGE = "fake.host.package"
        private val ENTIRE_SCREEN = MediaProjectionState.Projecting.EntireScreen(HOST_PACKAGE)
        private val SINGLE_TASK =
            MediaProjectionState.Projecting.SingleTask(
                HOST_PACKAGE,
                hostDeviceName = null,
                createTask(taskId = 1),
            )
    }
}
