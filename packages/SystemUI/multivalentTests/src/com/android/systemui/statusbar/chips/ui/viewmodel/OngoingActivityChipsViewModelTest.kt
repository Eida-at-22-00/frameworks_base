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

package com.android.systemui.statusbar.chips.ui.viewmodel

import android.content.Context
import android.content.DialogInterface
import android.content.packageManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.platform.test.annotations.DisableFlags
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.ContentDescription.Companion.loadContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.res.R
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.screenRecordRepository
import com.android.systemui.statusbar.chips.call.ui.viewmodel.CallChipViewModel
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.NORMAL_PACKAGE
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.setUpPackageManagerForMediaProjection
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.chips.screenrecord.ui.viewmodel.ScreenRecordChipViewModel
import com.android.systemui.statusbar.chips.sharetoapp.ui.viewmodel.ShareToAppChipViewModel
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.chips.ui.view.ChipBackgroundContainer
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.mockSystemUIDialogFactory
import com.android.systemui.statusbar.phone.ongoingcall.StatusBarChipsModernization
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallTestHelper.addOngoingCallState
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallTestHelper.removeOngoingCallState
import com.android.systemui.testKosmos
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Tests for [OngoingActivityChipsViewModel] when the [StatusBarNotifChips] flag is disabled. */
@SmallTest
@RunWith(AndroidJUnit4::class)
@DisableFlags(StatusBarNotifChips.FLAG_NAME)
class OngoingActivityChipsViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val systemClock = kosmos.fakeSystemClock

    private val screenRecordState = kosmos.screenRecordRepository.screenRecordState
    private val mediaProjectionState = kosmos.fakeMediaProjectionRepository.mediaProjectionState

    private val mockSystemUIDialog = mock<SystemUIDialog>()
    private val chipBackgroundView = mock<ChipBackgroundContainer>()
    private val chipView =
        mock<View>().apply {
            whenever(
                    this.requireViewById<ChipBackgroundContainer>(
                        R.id.ongoing_activity_chip_background
                    )
                )
                .thenReturn(chipBackgroundView)
        }
    private val mockExpandable: Expandable =
        mock<Expandable>().apply { whenever(dialogTransitionController(any())).thenReturn(mock()) }

    private val Kosmos.underTest by Kosmos.Fixture { ongoingActivityChipsViewModel }

    @Before
    fun setUp() {
        setUpPackageManagerForMediaProjection(kosmos)
        val icon =
            BitmapDrawable(
                context.resources,
                Bitmap.createBitmap(/* width= */ 100, /* height= */ 100, Bitmap.Config.ARGB_8888),
            )
        whenever(kosmos.packageManager.getApplicationIcon(any<String>())).thenReturn(icon)
    }

    @Test
    fun primaryChip_allHidden_hidden() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            removeOngoingCallState("testKey")

            val latest by collectLastValue(underTest.primaryChip)

            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Inactive::class.java)
        }

    @Test
    fun primaryChip_screenRecordShow_restHidden_screenRecordShown() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            removeOngoingCallState("testKey")

            val latest by collectLastValue(underTest.primaryChip)

            assertIsScreenRecordChip(latest)
        }

    @Test
    fun primaryChip_screenRecordShowAndCallShow_screenRecordShown() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.Recording

            addOngoingCallState(isAppVisible = false)

            val latest by collectLastValue(underTest.primaryChip)

            assertIsScreenRecordChip(latest)
        }

    @Test
    fun primaryChip_screenRecordShowAndShareToAppShow_screenRecordShown() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            removeOngoingCallState("testKey")

            val latest by collectLastValue(underTest.primaryChip)

            assertIsScreenRecordChip(latest)
        }

    @Test
    fun primaryChip_shareToAppShowAndCallShow_shareToAppShown() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            addOngoingCallState(isAppVisible = false)

            val latest by collectLastValue(underTest.primaryChip)

            assertIsShareToAppChip(latest)
        }

    @Test
    fun primaryChip_screenRecordAndShareToAppAndCastToOtherHideAndCallShown_callShown() =
        kosmos.runTest {
            val notificationKey = "call"
            screenRecordState.value = ScreenRecordModel.DoingNothing
            // MediaProjection covers both share-to-app and cast-to-other-device
            mediaProjectionState.value = MediaProjectionState.NotProjecting

            addOngoingCallState(key = notificationKey, isAppVisible = false)

            val latest by collectLastValue(underTest.primaryChip)

            assertIsCallChip(latest, notificationKey, context)
        }

    @Test
    fun primaryChip_higherPriorityChipAdded_lowerPriorityChipReplaced() =
        kosmos.runTest {
            // Start with just the lowest priority chip shown
            val callNotificationKey = "call"
            addOngoingCallState(key = callNotificationKey, isAppVisible = false)
            // And everything else hidden
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            screenRecordState.value = ScreenRecordModel.DoingNothing

            val latest by collectLastValue(underTest.primaryChip)

            assertIsCallChip(latest, callNotificationKey, context)

            // WHEN the higher priority media projection chip is added
            mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    NORMAL_PACKAGE,
                    hostDeviceName = null,
                    createTask(taskId = 1),
                )

            // THEN the higher priority media projection chip is used
            assertIsShareToAppChip(latest)

            // WHEN the higher priority screen record chip is added
            screenRecordState.value = ScreenRecordModel.Recording

            // THEN the higher priority screen record chip is used
            assertIsScreenRecordChip(latest)
        }

    @Test
    fun primaryChip_highestPriorityChipRemoved_showsNextPriorityChip() =
        kosmos.runTest {
            // WHEN all chips are active
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            val callNotificationKey = "call"
            addOngoingCallState(key = callNotificationKey, isAppVisible = false)

            val latest by collectLastValue(underTest.primaryChip)

            // THEN the highest priority screen record is used
            assertIsScreenRecordChip(latest)

            // WHEN the higher priority screen record is removed
            screenRecordState.value = ScreenRecordModel.DoingNothing

            // THEN the lower priority media projection is used
            assertIsShareToAppChip(latest)

            // WHEN the higher priority media projection is removed
            mediaProjectionState.value = MediaProjectionState.NotProjecting

            // THEN the lower priority call is used
            assertIsCallChip(latest, callNotificationKey, context)
        }

    /** Regression test for b/347726238. */
    @Test
    fun primaryChip_timerDoesNotResetAfterSubscribersRestart() =
        kosmos.runTest {
            var latest: OngoingActivityChipModel? = null

            val job1 = underTest.primaryChip.onEach { latest = it }.launchIn(kosmos.testScope)

            // Start a chip with a timer
            systemClock.setElapsedRealtime(1234)
            screenRecordState.value = ScreenRecordModel.Recording

            assertThat((latest as OngoingActivityChipModel.Active.Timer).startTimeMs)
                .isEqualTo(1234)

            // Stop subscribing to the chip flow
            job1.cancel()

            // Let time pass
            systemClock.setElapsedRealtime(5678)

            // WHEN we re-subscribe to the chip flow
            val job2 = underTest.primaryChip.onEach { latest = it }.launchIn(kosmos.testScope)

            // THEN the old start time is still used
            assertThat((latest as OngoingActivityChipModel.Active.Timer).startTimeMs)
                .isEqualTo(1234)

            job2.cancel()
        }

    @Test
    fun primaryChip_screenRecordStoppedViaDialog_chipHiddenWithoutAnimation() =
        kosmos.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(
                    NORMAL_PACKAGE,
                    hostDeviceName = "Recording Display",
                )
            removeOngoingCallState("testKey")

            val latest by collectLastValue(underTest.primaryChip)

            assertIsScreenRecordChip(latest)

            // WHEN screen record gets stopped via dialog
            val dialogStopAction =
                getStopActionFromDialog(
                    latest,
                    chipView,
                    mockExpandable,
                    mockSystemUIDialog,
                    kosmos,
                )
            dialogStopAction.onClick(mock<DialogInterface>(), 0)

            // THEN the chip is immediately hidden with no animation
            assertThat(latest).isEqualTo(OngoingActivityChipModel.Inactive(shouldAnimate = false))
        }

    @Test
    fun primaryChip_projectionStoppedViaDialog_chipHiddenWithoutAnimation() =
        kosmos.runTest {
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            screenRecordState.value = ScreenRecordModel.DoingNothing
            removeOngoingCallState("testKey")

            val latest by collectLastValue(underTest.primaryChip)

            assertIsShareToAppChip(latest)

            // WHEN media projection gets stopped via dialog
            val dialogStopAction =
                getStopActionFromDialog(
                    latest,
                    chipView,
                    mockExpandable,
                    mockSystemUIDialog,
                    kosmos,
                )
            dialogStopAction.onClick(mock<DialogInterface>(), 0)

            // THEN the chip is immediately hidden with no animation
            assertThat(latest).isEqualTo(OngoingActivityChipModel.Inactive(shouldAnimate = false))
        }

    companion object {
        /**
         * Assuming that the click listener in [latest] opens a dialog, this fetches the action
         * associated with the positive button, which we assume is the "Stop sharing" action.
         */
        fun getStopActionFromDialog(
            latest: OngoingActivityChipModel?,
            chipView: View,
            expandable: Expandable,
            dialog: SystemUIDialog,
            kosmos: Kosmos,
        ): DialogInterface.OnClickListener {
            // Capture the action that would get invoked when the user clicks "Stop" on the dialog
            lateinit var dialogStopAction: DialogInterface.OnClickListener
            Mockito.doAnswer {
                    val delegate = it.arguments[0] as SystemUIDialog.Delegate
                    delegate.beforeCreate(dialog, /* savedInstanceState= */ null)

                    val stopActionCaptor = argumentCaptor<DialogInterface.OnClickListener>()
                    verify(dialog).setPositiveButton(any(), stopActionCaptor.capture())
                    dialogStopAction = stopActionCaptor.firstValue

                    return@doAnswer dialog
                }
                .whenever(kosmos.mockSystemUIDialogFactory)
                .create(any<SystemUIDialog.Delegate>())
            whenever(kosmos.packageManager.getApplicationInfo(eq(NORMAL_PACKAGE), any<Int>()))
                .thenThrow(PackageManager.NameNotFoundException())

            if (StatusBarChipsModernization.isEnabled) {
                val clickBehavior = (latest as OngoingActivityChipModel.Active).clickBehavior
                (clickBehavior as OngoingActivityChipModel.ClickBehavior.ExpandAction).onClick(
                    expandable
                )
            } else {
                val clickListener =
                    ((latest as OngoingActivityChipModel.Active).onClickListenerLegacy)
                clickListener!!.onClick(chipView)
            }

            return dialogStopAction
        }

        fun assertIsScreenRecordChip(latest: OngoingActivityChipModel?) {
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Active::class.java)
            assertThat((latest as OngoingActivityChipModel.Active).key)
                .isEqualTo(ScreenRecordChipViewModel.KEY)
            val icon =
                ((latest.icon) as OngoingActivityChipModel.ChipIcon.SingleColorIcon).impl
                    as Icon.Resource
            assertThat(icon.res).isEqualTo(R.drawable.ic_screenrecord)
        }

        fun assertIsShareToAppChip(latest: OngoingActivityChipModel?) {
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Active::class.java)
            assertThat((latest as OngoingActivityChipModel.Active).key)
                .isEqualTo(ShareToAppChipViewModel.KEY)
            val icon =
                ((latest.icon) as OngoingActivityChipModel.ChipIcon.SingleColorIcon).impl
                    as Icon.Resource
            assertThat(icon.res).isEqualTo(R.drawable.ic_present_to_all)
        }

        fun assertIsCallChip(
            latest: OngoingActivityChipModel?,
            notificationKey: String,
            context: Context,
        ) {
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Active::class.java)
            assertThat((latest as OngoingActivityChipModel.Active).key)
                .isEqualTo("${CallChipViewModel.KEY_PREFIX}$notificationKey")

            if (StatusBarConnectedDisplays.isEnabled) {
                assertNotificationIcon(latest, notificationKey)
            } else {
                val contentDescription =
                    if (latest.icon is OngoingActivityChipModel.ChipIcon.SingleColorIcon) {
                        ((latest.icon) as OngoingActivityChipModel.ChipIcon.SingleColorIcon)
                            .impl
                            .contentDescription
                    } else {
                        (latest.icon as OngoingActivityChipModel.ChipIcon.StatusBarView)
                            .contentDescription
                    }
                assertThat(contentDescription.loadContentDescription(context))
                    .contains(context.getString(R.string.ongoing_call_content_description))
            }
        }

        private fun assertNotificationIcon(
            latest: OngoingActivityChipModel?,
            notificationKey: String,
        ) {
            val active = latest as OngoingActivityChipModel.Active
            val notificationIcon =
                active.icon as OngoingActivityChipModel.ChipIcon.StatusBarNotificationIcon
            assertThat(notificationIcon.notificationKey).isEqualTo(notificationKey)
        }
    }
}
