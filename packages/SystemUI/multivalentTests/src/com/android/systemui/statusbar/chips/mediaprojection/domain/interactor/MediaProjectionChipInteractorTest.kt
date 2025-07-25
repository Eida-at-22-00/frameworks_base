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

package com.android.systemui.statusbar.chips.mediaprojection.domain.interactor

import android.Manifest
import android.content.Intent
import android.content.packageManager
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_STATUS_BAR_SHOW_AUDIO_ONLY_PROJECTION_CHIP
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.statusbar.chips.mediaprojection.domain.model.ProjectionChipModel
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class MediaProjectionChipInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val testScope = kosmos.testScope
    private val mediaProjectionRepo = kosmos.fakeMediaProjectionRepository

    @Before
    fun setUp() {
        setUpPackageManagerForMediaProjection(kosmos)
    }

    private val underTest = kosmos.mediaProjectionChipInteractor

    @Test
    fun projectionStartedDuringCallAndActivePostCallEvent_eventEmitted_isUnit() =
        kosmos.runTest {
            val latest by
                collectLastValue(underTest.projectionStartedDuringCallAndActivePostCallEvent)

            fakeMediaProjectionRepository.emitProjectionStartedDuringCallAndActivePostCallEvent()

            assertThat(latest).isEqualTo(Unit)
        }

    @Test
    fun projectionStartedDuringCallAndActivePostCallEvent_noEventEmitted_isNull() =
        kosmos.runTest {
            val latest by
                collectLastValue(underTest.projectionStartedDuringCallAndActivePostCallEvent)

            assertThat(latest).isNull()
        }

    @Test
    fun projection_notProjectingState_isNotProjecting() =
        testScope.runTest {
            val latest by collectLastValue(underTest.projection)

            mediaProjectionRepo.mediaProjectionState.value = MediaProjectionState.NotProjecting

            assertThat(latest).isInstanceOf(ProjectionChipModel.NotProjecting::class.java)
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SHOW_AUDIO_ONLY_PROJECTION_CHIP)
    fun projection_noScreenState_otherDevicesPackage_isCastToOtherAndAudio() =
        testScope.runTest {
            val latest by collectLastValue(underTest.projection)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.NoScreen(CAST_TO_OTHER_DEVICES_PACKAGE)

            assertThat(latest).isInstanceOf(ProjectionChipModel.Projecting::class.java)
            assertThat((latest as ProjectionChipModel.Projecting).receiver)
                .isEqualTo(ProjectionChipModel.Receiver.CastToOtherDevice)
            assertThat((latest as ProjectionChipModel.Projecting).contentType)
                .isEqualTo(ProjectionChipModel.ContentType.Audio)
        }

    @Test
    fun projection_singleTaskState_otherDevicesPackage_isCastToOtherAndScreen() =
        testScope.runTest {
            val latest by collectLastValue(underTest.projection)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    CAST_TO_OTHER_DEVICES_PACKAGE,
                    hostDeviceName = null,
                    createTask(taskId = 1),
                )

            assertThat(latest).isInstanceOf(ProjectionChipModel.Projecting::class.java)
            assertThat((latest as ProjectionChipModel.Projecting).receiver)
                .isEqualTo(ProjectionChipModel.Receiver.CastToOtherDevice)
            assertThat((latest as ProjectionChipModel.Projecting).contentType)
                .isEqualTo(ProjectionChipModel.ContentType.Screen)
        }

    @Test
    fun projection_entireScreenState_otherDevicesPackage_isCastToOtherAndScreen() =
        testScope.runTest {
            val latest by collectLastValue(underTest.projection)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(CAST_TO_OTHER_DEVICES_PACKAGE)

            assertThat(latest).isInstanceOf(ProjectionChipModel.Projecting::class.java)
            assertThat((latest as ProjectionChipModel.Projecting).receiver)
                .isEqualTo(ProjectionChipModel.Receiver.CastToOtherDevice)
            assertThat((latest as ProjectionChipModel.Projecting).contentType)
                .isEqualTo(ProjectionChipModel.ContentType.Screen)
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SHOW_AUDIO_ONLY_PROJECTION_CHIP)
    fun projection_noScreenState_normalPackage_isShareToAppAndAudio() =
        testScope.runTest {
            val latest by collectLastValue(underTest.projection)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.NoScreen(NORMAL_PACKAGE)

            assertThat(latest).isInstanceOf(ProjectionChipModel.Projecting::class.java)
            assertThat((latest as ProjectionChipModel.Projecting).receiver)
                .isEqualTo(ProjectionChipModel.Receiver.ShareToApp)
            assertThat((latest as ProjectionChipModel.Projecting).contentType)
                .isEqualTo(ProjectionChipModel.ContentType.Audio)
        }

    @Test
    fun projection_singleTaskState_normalPackage_isShareToAppAndScreen() =
        testScope.runTest {
            val latest by collectLastValue(underTest.projection)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    NORMAL_PACKAGE,
                    hostDeviceName = null,
                    createTask(taskId = 1),
                )

            assertThat(latest).isInstanceOf(ProjectionChipModel.Projecting::class.java)
            assertThat((latest as ProjectionChipModel.Projecting).receiver)
                .isEqualTo(ProjectionChipModel.Receiver.ShareToApp)
            assertThat((latest as ProjectionChipModel.Projecting).contentType)
                .isEqualTo(ProjectionChipModel.ContentType.Screen)
        }

    @Test
    fun projection_entireScreenState_normalPackage_isShareToAppAndScreen() =
        testScope.runTest {
            val latest by collectLastValue(underTest.projection)

            mediaProjectionRepo.mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            assertThat(latest).isInstanceOf(ProjectionChipModel.Projecting::class.java)
            assertThat((latest as ProjectionChipModel.Projecting).receiver)
                .isEqualTo(ProjectionChipModel.Receiver.ShareToApp)
            assertThat((latest as ProjectionChipModel.Projecting).contentType)
                .isEqualTo(ProjectionChipModel.ContentType.Screen)
        }

    companion object {
        const val CAST_TO_OTHER_DEVICES_PACKAGE = "other.devices.package"
        const val NORMAL_PACKAGE = "some.normal.package"

        /**
         * Sets up [kosmos.packageManager] so that [CAST_TO_OTHER_DEVICES_PACKAGE] is marked as a
         * package that casts to other devices, and [NORMAL_PACKAGE] is *not* marked as casting to
         * other devices.
         */
        fun setUpPackageManagerForMediaProjection(kosmos: Kosmos) {
            kosmos.packageManager.apply {
                whenever(
                        this.checkPermission(
                            Manifest.permission.REMOTE_DISPLAY_PROVIDER,
                            CAST_TO_OTHER_DEVICES_PACKAGE,
                        )
                    )
                    .thenReturn(PackageManager.PERMISSION_GRANTED)
                whenever(
                        this.checkPermission(
                            Manifest.permission.REMOTE_DISPLAY_PROVIDER,
                            NORMAL_PACKAGE,
                        )
                    )
                    .thenReturn(PackageManager.PERMISSION_DENIED)

                doAnswer {
                        // See Utils.isHeadlessRemoteDisplayProvider
                        if (
                            (it.arguments[0] as Intent).`package` == CAST_TO_OTHER_DEVICES_PACKAGE
                        ) {
                            emptyList<ResolveInfo>()
                        } else {
                            listOf(mock<ResolveInfo>())
                        }
                    }
                    .whenever(this)
                    .queryIntentActivities(any(), anyInt())
            }
        }
    }
}
