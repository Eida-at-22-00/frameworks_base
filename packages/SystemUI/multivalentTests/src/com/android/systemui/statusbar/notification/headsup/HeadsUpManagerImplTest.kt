/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.systemui.statusbar.notification.headsup

import android.app.Notification
import android.app.Notification.FLAG_PROMOTED_ONGOING
import android.app.PendingIntent
import android.app.Person
import android.os.Handler
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.view.accessibility.accessibilityManager
import android.view.accessibility.accessibilityManagerWrapper
import androidx.test.filters.SmallTest
import com.android.internal.logging.uiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.dump.dumpManager
import com.android.systemui.flags.BrokenWithSceneContainer
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.log.assertLogsWtfs
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.provider.visualStabilityProvider
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager
import com.android.systemui.statusbar.notification.promoted.PromotedNotificationUi
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.NotificationTestHelper
import com.android.systemui.statusbar.notification.shared.NotificationThrottleHun
import com.android.systemui.statusbar.phone.keyguardBypassController
import com.android.systemui.statusbar.policy.configurationController
import com.android.systemui.statusbar.sysuiStatusBarStateController
import com.android.systemui.testKosmos
import com.android.systemui.util.concurrency.mockExecutorHandler
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.systemui.util.settings.fakeGlobalSettings
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
@RunWithLooper
class HeadsUpManagerImplTest(flags: FlagsParameterization) : SysuiTestCase() {
    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val testScope = kosmos.testScope

    private val groupManager = mock<GroupMembershipManager>()
    private val bgHandler = mock<Handler>()
    private val headsUpManagerLogger = mock<HeadsUpManagerLogger>()

    val statusBarStateController = kosmos.sysuiStatusBarStateController
    private val globalSettings = kosmos.fakeGlobalSettings
    private val systemClock = kosmos.fakeSystemClock
    private val executor = kosmos.fakeExecutor
    private val uiEventLoggerFake = kosmos.uiEventLoggerFake
    private val javaAdapter: JavaAdapter = JavaAdapter(testScope.backgroundScope)

    private lateinit var testHelper: NotificationTestHelper
    private lateinit var avalancheController: AvalancheController
    private lateinit var underTest: HeadsUpManagerImpl

    @Before
    fun setUp() {
        mContext.getOrCreateTestableResources().apply {
            this.addOverride(R.integer.ambient_notification_extension_time, TEST_EXTENSION_TIME)
            this.addOverride(R.integer.touch_acceptance_delay, TEST_TOUCH_ACCEPTANCE_TIME)
            this.addOverride(
                R.integer.heads_up_notification_minimum_time,
                TEST_MINIMUM_DISPLAY_TIME_DEFAULT,
            )
            this.addOverride(
                R.integer.heads_up_notification_minimum_time_with_throttling,
                TEST_MINIMUM_DISPLAY_TIME_DEFAULT,
            )
            this.addOverride(
                R.integer.heads_up_notification_minimum_time_for_user_initiated,
                TEST_MINIMUM_DISPLAY_TIME_FOR_USER_INITIATED,
            )
            this.addOverride(R.integer.heads_up_notification_decay, TEST_AUTO_DISMISS_TIME)
            this.addOverride(
                R.integer.sticky_heads_up_notification_time,
                TEST_STICKY_AUTO_DISMISS_TIME,
            )
        }

        allowTestableLooperAsMainThread()
        testHelper = NotificationTestHelper(mContext, mDependency, TestableLooper.get(this))

        whenever(kosmos.keyguardBypassController.bypassEnabled).thenReturn(false)
        kosmos.visualStabilityProvider.isReorderingAllowed = true
        avalancheController =
            AvalancheController(
                kosmos.dumpManager,
                uiEventLoggerFake,
                headsUpManagerLogger,
                bgHandler,
            )
        underTest =
            HeadsUpManagerImpl(
                mContext,
                headsUpManagerLogger,
                statusBarStateController,
                kosmos.keyguardBypassController,
                groupManager,
                kosmos.visualStabilityProvider,
                kosmos.configurationController,
                mockExecutorHandler(executor),
                globalSettings,
                systemClock,
                executor,
                kosmos.accessibilityManagerWrapper,
                uiEventLoggerFake,
                javaAdapter,
                kosmos.shadeInteractor,
                avalancheController,
            )
    }

    @Test
    fun testHasNotifications_headsUpManagerMapNotEmpty_true() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(entry)

        assertThat(underTest.mHeadsUpEntryMap).isNotEmpty()
        assertThat(underTest.hasNotifications()).isTrue()
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testHasNotifications_avalancheMapNotEmpty_true() {
        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        val headsUpEntry = underTest.createHeadsUpEntry(notifEntry)
        avalancheController.addToNext(headsUpEntry) {}

        assertThat(avalancheController.getWaitingEntryList()).isNotEmpty()
        assertThat(underTest.hasNotifications()).isTrue()
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testHasNotifications_false() {
        assertThat(underTest.mHeadsUpEntryMap).isEmpty()
        assertThat(avalancheController.getWaitingEntryList()).isEmpty()
        assertThat(underTest.hasNotifications()).isFalse()
    }

    @Test
    fun pinnedHeadsUpStatuses_noHeadsUp() {
        assertThat(underTest.hasPinnedHeadsUp()).isFalse()
        assertThat(underTest.pinnedHeadsUpStatus()).isEqualTo(PinnedStatus.NotPinned)
    }

    @Test
    fun pinnedHeadsUpStatuses_pinnedBySystem() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        entry.row = testHelper.createRow()
        underTest.showNotification(entry, isPinnedByUser = false)

        assertThat(underTest.hasPinnedHeadsUp()).isTrue()
        assertThat(underTest.pinnedHeadsUpStatus()).isEqualTo(PinnedStatus.PinnedBySystem)
    }

    @Test
    @DisableFlags(StatusBarNotifChips.FLAG_NAME)
    fun pinnedHeadsUpStatuses_pinnedByUser_butFlagOff_returnsNotPinned() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        entry.row = testHelper.createRow()
        assertLogsWtfs { underTest.showNotification(entry, isPinnedByUser = true) }
        assertThat(underTest.hasPinnedHeadsUp()).isFalse()
        assertThat(underTest.pinnedHeadsUpStatus()).isEqualTo(PinnedStatus.NotPinned)
    }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun pinnedHeadsUpStatuses_pinnedByUser_flagOn() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        entry.row = testHelper.createRow()
        underTest.showNotification(entry, isPinnedByUser = true)

        assertThat(underTest.hasPinnedHeadsUp()).isTrue()
        assertThat(underTest.pinnedHeadsUpStatus()).isEqualTo(PinnedStatus.PinnedByUser)
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testGetHeadsUpEntryList_includesAvalancheEntryList() {
        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        val headsUpEntry = underTest.createHeadsUpEntry(notifEntry)
        avalancheController.addToNext(headsUpEntry) {}

        assertThat(underTest.headsUpEntryList).contains(headsUpEntry)
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testGetHeadsUpEntry_returnsAvalancheEntry() {
        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        val headsUpEntry = underTest.createHeadsUpEntry(notifEntry)
        avalancheController.addToNext(headsUpEntry) {}

        assertThat(underTest.getHeadsUpEntry(notifEntry.key)).isEqualTo(headsUpEntry)
    }

    @Test
    fun testShowNotification_notPinnedByUser_addsEntry() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        underTest.showNotification(entry, isPinnedByUser = false)

        assertThat(underTest.isHeadsUpEntry(entry.key)).isTrue()
        assertThat(underTest.hasNotifications()).isTrue()
        assertThat(underTest.getEntry(entry.key)).isEqualTo(entry)
    }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun testShowNotification_isPinnedByUser_addsEntry() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        underTest.showNotification(entry, isPinnedByUser = true)

        assertThat(underTest.isHeadsUpEntry(entry.key)).isTrue()
        assertThat(underTest.hasNotifications()).isTrue()
        assertThat(underTest.getEntry(entry.key)).isEqualTo(entry)
    }

    @Test
    fun testShowNotification_notPinnedByUser_autoDismisses() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        underTest.showNotification(entry, isPinnedByUser = false)
        systemClock.advanceTime((TEST_AUTO_DISMISS_TIME * 3 / 2).toLong())

        assertThat(underTest.isHeadsUpEntry(entry.key)).isFalse()
    }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun testShowNotification_isPinnedByUser_autoDismisses() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        underTest.showNotification(entry, isPinnedByUser = true)
        systemClock.advanceTime((TEST_AUTO_DISMISS_TIME * 3 / 2).toLong())

        assertThat(underTest.isHeadsUpEntry(entry.key)).isFalse()
    }

    @Test
    fun testRemoveNotification_notPinnedByUser_removeDeferred() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        underTest.showNotification(entry, isPinnedByUser = false)

        val removedImmediately =
            underTest.removeNotification(
                entry.key,
                /* releaseImmediately= */ false,
                "removeDeferred",
            )
        assertThat(removedImmediately).isFalse()
        assertThat(underTest.isHeadsUpEntry(entry.key)).isTrue()
    }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun testRemoveNotification_isPinnedByUser_removeDeferred() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        underTest.showNotification(entry, isPinnedByUser = true)

        val removedImmediately =
            underTest.removeNotification(
                entry.key,
                /* releaseImmediately= */ false,
                "removeDeferred",
            )
        assertThat(removedImmediately).isFalse()
        assertThat(underTest.isHeadsUpEntry(entry.key)).isTrue()
    }

    @Test
    fun testRemoveNotification_notPinnedByUser_forceRemove() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        underTest.showNotification(entry, isPinnedByUser = false)

        val removedImmediately =
            underTest.removeNotification(entry.key, /* releaseImmediately= */ true, "forceRemove")
        assertThat(removedImmediately).isTrue()
        assertThat(underTest.isHeadsUpEntry(entry.key)).isFalse()
    }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun testRemoveNotification_isPinnedByUser_forceRemove() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        underTest.showNotification(entry, isPinnedByUser = true)

        val removedImmediately =
            underTest.removeNotification(entry.key, /* releaseImmediately= */ true, "forceRemove")
        assertThat(removedImmediately).isTrue()
        assertThat(underTest.isHeadsUpEntry(entry.key)).isFalse()
    }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun testReleaseAllImmediately() {
        for (i in 0 until 4) {
            val entry = HeadsUpManagerTestUtil.createEntry(i, mContext)
            entry.row = testHelper.createRow()
            val isPinnedByUser = i % 2 == 0
            underTest.showNotification(entry, isPinnedByUser)
        }

        underTest.releaseAllImmediately()

        assertThat(underTest.allEntries.count()).isEqualTo(0)
    }

    @Test
    fun testCanRemoveImmediately_notShownLongEnough_notPinnedByUser() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        underTest.showNotification(entry, isPinnedByUser = false)

        // The entry has just been added so we should not remove immediately.
        assertThat(underTest.canRemoveImmediately(entry.key)).isFalse()
    }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun testCanRemoveImmediately_notShownLongEnough_isPinnedByUser() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        underTest.showNotification(entry, isPinnedByUser = true)

        // The entry has just been added so we should not remove immediately.
        assertThat(underTest.canRemoveImmediately(entry.key)).isFalse()
    }

    @Test
    fun testHunRemovedLogging() {
        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        val headsUpEntry = underTest.HeadsUpEntry(notifEntry)
        headsUpEntry.setRowPinnedStatus(PinnedStatus.NotPinned)

        underTest.onEntryRemoved(headsUpEntry, "test")

        verify(headsUpManagerLogger, times(1)).logNotificationActuallyRemoved(eq(notifEntry))
    }

    @Test
    fun testShowNotification_autoDismissesIncludingTouchAcceptanceDelay() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        useAccessibilityTimeout(false)

        underTest.showNotification(entry)
        systemClock.advanceTime((TEST_TOUCH_ACCEPTANCE_TIME / 2 + TEST_AUTO_DISMISS_TIME).toLong())

        assertThat(underTest.isHeadsUpEntry(entry.key)).isTrue()
    }

    @Test
    fun testShowNotification_autoDismissesWithDefaultTimeout() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        useAccessibilityTimeout(false)

        underTest.showNotification(entry)
        systemClock.advanceTime(
            (TEST_TOUCH_ACCEPTANCE_TIME +
                    (TEST_AUTO_DISMISS_TIME + TEST_A11Y_AUTO_DISMISS_TIME) / 2)
                .toLong()
        )

        assertThat(underTest.isHeadsUpEntry(entry.key)).isFalse()
    }

    @Test
    fun testRemoveNotification_beforeMinimumDisplayTime_notUserInitiatedHun() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        useAccessibilityTimeout(false)

        underTest.showNotification(entry)

        val removedImmediately =
            underTest.removeNotification(
                entry.key,
                /* releaseImmediately = */ false,
                "beforeMinimumDisplayTime",
            )
        assertThat(removedImmediately).isFalse()
        assertThat(underTest.isHeadsUpEntry(entry.key)).isTrue()

        systemClock.advanceTime(
            ((TEST_MINIMUM_DISPLAY_TIME_DEFAULT + TEST_AUTO_DISMISS_TIME) / 2).toLong()
        )

        assertThat(underTest.isHeadsUpEntry(entry.key)).isFalse()
    }

    @Test
    fun testRemoveNotification_afterMinimumDisplayTime_notUserInitiatedHun() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        useAccessibilityTimeout(false)

        underTest.showNotification(entry)
        systemClock.advanceTime(
            ((TEST_MINIMUM_DISPLAY_TIME_DEFAULT + TEST_AUTO_DISMISS_TIME) / 2).toLong()
        )

        assertThat(underTest.isHeadsUpEntry(entry.key)).isTrue()

        val removedImmediately =
            underTest.removeNotification(
                entry.key,
                /* releaseImmediately = */ false,
                "afterMinimumDisplayTime",
            )
        assertThat(removedImmediately).isTrue()
        assertThat(underTest.isHeadsUpEntry(entry.key)).isFalse()
    }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun testRemoveNotification_beforeMinimumDisplayTime_forUserInitiatedHun() {
        useAccessibilityTimeout(false)

        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        entry.row = testHelper.createRow()
        underTest.showNotification(entry, isPinnedByUser = true)

        val removedImmediately =
            underTest.removeNotification(
                entry.key,
                /* releaseImmediately = */ false,
                "beforeMinimumDisplayTime",
            )
        assertThat(removedImmediately).isFalse()
        assertThat(underTest.isHeadsUpEntry(entry.key)).isTrue()

        systemClock.advanceTime(
            ((TEST_MINIMUM_DISPLAY_TIME_FOR_USER_INITIATED + TEST_AUTO_DISMISS_TIME) / 2).toLong()
        )

        assertThat(underTest.isHeadsUpEntry(entry.key)).isFalse()
    }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun testRemoveNotification_afterMinimumDisplayTime_forUserInitiatedHun() {
        useAccessibilityTimeout(false)

        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        entry.row = testHelper.createRow()
        underTest.showNotification(entry, isPinnedByUser = true)

        systemClock.advanceTime(
            ((TEST_MINIMUM_DISPLAY_TIME_FOR_USER_INITIATED + TEST_AUTO_DISMISS_TIME) / 2).toLong()
        )

        assertThat(underTest.isHeadsUpEntry(entry.key)).isTrue()

        val removedImmediately =
            underTest.removeNotification(
                entry.key,
                /* releaseImmediately = */ false,
                "afterMinimumDisplayTime",
            )

        assertThat(removedImmediately).isTrue()
        assertThat(underTest.isHeadsUpEntry(entry.key)).isFalse()
    }

    @Test
    fun testRemoveNotification_releaseImmediately() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        underTest.showNotification(entry)

        val removedImmediately =
            underTest.removeNotification(
                entry.key,
                /* releaseImmediately = */ true,
                "afterMinimumDisplayTime",
            )
        assertThat(removedImmediately).isTrue()
        assertThat(underTest.isHeadsUpEntry(entry.key)).isFalse()
    }

    @Test
    fun testSnooze_notPinnedByUser() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(entry, isPinnedByUser = false)

        underTest.snooze()

        assertThat(underTest.isSnoozed(entry.sbn.packageName)).isTrue()
    }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun testSnooze_isPinnedByUser() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(entry, isPinnedByUser = true)

        underTest.snooze()

        assertThat(underTest.isSnoozed(entry.sbn.packageName)).isTrue()
    }

    @Test
    fun testSwipedOutNotification_notPinnedByUser() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(entry, isPinnedByUser = false)
        underTest.addSwipedOutNotification(entry.key)

        // Remove should succeed because the notification is swiped out
        val removedImmediately =
            underTest.removeNotification(
                entry.key,
                /* releaseImmediately= */ false,
                /* reason= */ "swipe out",
            )
        assertThat(removedImmediately).isTrue()
        assertThat(underTest.isHeadsUpEntry(entry.key)).isFalse()
    }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun testSwipedOutNotification_isPinnedByUser() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(entry, isPinnedByUser = true)
        underTest.addSwipedOutNotification(entry.key)

        // Remove should succeed because the notification is swiped out
        val removedImmediately =
            underTest.removeNotification(
                entry.key,
                /* releaseImmediately= */ false,
                /* reason= */ "swipe out",
            )
        assertThat(removedImmediately).isTrue()
        assertThat(underTest.isHeadsUpEntry(entry.key)).isFalse()
    }

    @Test
    fun testCanRemoveImmediately_swipedOut() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(entry)
        underTest.addSwipedOutNotification(entry.key)

        // Notification is swiped so it can be immediately removed.
        assertThat(underTest.canRemoveImmediately(entry.key)).isTrue()
    }

    @Ignore("b/141538055")
    @Test
    fun testCanRemoveImmediately_notTopEntry() {
        val earlierEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        val laterEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 1, mContext)
        laterEntry.row = mock<ExpandableNotificationRow>()
        underTest.showNotification(earlierEntry)
        underTest.showNotification(laterEntry)

        // Notification is "behind" a higher priority notification so we can remove it immediately.
        assertThat(underTest.canRemoveImmediately(earlierEntry.key)).isTrue()
    }

    @Test
    fun testExtendHeadsUp_notPinnedByUser() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(entry, isPinnedByUser = false)

        underTest.extendHeadsUp()

        systemClock.advanceTime(((TEST_AUTO_DISMISS_TIME + TEST_EXTENSION_TIME) / 2).toLong())
        assertThat(underTest.isHeadsUpEntry(entry.key)).isTrue()
    }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    fun testExtendHeadsUp_isPinnedByUser() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(entry, isPinnedByUser = true)

        underTest.extendHeadsUp()

        systemClock.advanceTime(((TEST_AUTO_DISMISS_TIME + TEST_EXTENSION_TIME) / 2).toLong())
        assertThat(underTest.isHeadsUpEntry(entry.key)).isTrue()
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testShowNotification_removeWhenReorderingAllowedTrue() {
        kosmos.visualStabilityProvider.isReorderingAllowed = true

        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(notifEntry)
        assertThat(underTest.mEntriesToRemoveWhenReorderingAllowed.contains(notifEntry)).isTrue()
    }

    class TestAnimationStateHandler : AnimationStateHandler {
        override fun setHeadsUpGoingAwayAnimationsAllowed(allowed: Boolean) {}
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testReorderingAllowed_clearsListOfEntriesToRemove() {
        kosmos.visualStabilityProvider.isReorderingAllowed = true

        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(notifEntry)
        assertThat(underTest.mEntriesToRemoveWhenReorderingAllowed.contains(notifEntry)).isTrue()

        underTest.setAnimationStateHandler(TestAnimationStateHandler())
        underTest.mOnReorderingAllowedListener.onReorderingAllowed()
        assertThat(underTest.mEntriesToRemoveWhenReorderingAllowed.isEmpty()).isTrue()
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testShowNotification_reorderNotAllowed_seenInShadeTrue() {
        kosmos.visualStabilityProvider.isReorderingAllowed = false

        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(notifEntry)
        assertThat(notifEntry.isSeenInShade).isTrue()
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testShowNotification_reorderAllowed_seenInShadeFalse() {
        kosmos.visualStabilityProvider.isReorderingAllowed = true

        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        underTest.showNotification(notifEntry, isPinnedByUser = false)
        assertThat(notifEntry.isSeenInShade).isFalse()
    }

    @Test
    fun testShowNotification_sticky_neverAutoDismisses() {
        val entry = createStickyEntry(id = 0)
        useAccessibilityTimeout(false)

        underTest.showNotification(entry)
        systemClock.advanceTime(
            (TEST_TOUCH_ACCEPTANCE_TIME + 2 * TEST_A11Y_AUTO_DISMISS_TIME).toLong()
        )

        assertThat(underTest.isHeadsUpEntry(entry.key)).isTrue()
    }

    @Test
    fun testShowNotification_autoDismissesWithAccessibilityTimeout() {
        val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
        useAccessibilityTimeout(true)

        underTest.showNotification(entry)
        systemClock.advanceTime(
            (TEST_TOUCH_ACCEPTANCE_TIME +
                    (TEST_AUTO_DISMISS_TIME + TEST_A11Y_AUTO_DISMISS_TIME) / 2)
                .toLong()
        )

        assertThat(underTest.isHeadsUpEntry(entry.key)).isTrue()
    }

    @Test
    fun testShowNotification_stickyForSomeTime_autoDismissesWithStickyTimeout() {
        val entry = createStickyForSomeTimeEntry(id = 0)
        useAccessibilityTimeout(false)

        underTest.showNotification(entry)
        systemClock.advanceTime(
            (TEST_TOUCH_ACCEPTANCE_TIME +
                    (TEST_AUTO_DISMISS_TIME + TEST_STICKY_AUTO_DISMISS_TIME) / 2)
                .toLong()
        )

        assertThat(underTest.isHeadsUpEntry(entry.key)).isTrue()
    }

    @Test
    fun testShowNotification_stickyForSomeTime_autoDismissesWithAccessibilityTimeout() {
        val entry = createStickyForSomeTimeEntry(id = 0)
        useAccessibilityTimeout(true)

        underTest.showNotification(entry)
        systemClock.advanceTime(
            (TEST_TOUCH_ACCEPTANCE_TIME +
                    (TEST_STICKY_AUTO_DISMISS_TIME + TEST_A11Y_AUTO_DISMISS_TIME) / 2)
                .toLong()
        )

        assertThat(underTest.isHeadsUpEntry(entry.key)).isTrue()
    }

    @Test
    @DisableFlags(StatusBarNotifChips.FLAG_NAME, PromotedNotificationUi.FLAG_NAME)
    fun testIsSticky_promotedAndExpanded_notifChipsFlagOff_promotedUiFlagOff_true() {
        assertThat(getIsSticky_promotedAndExpanded()).isTrue()
    }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME, PromotedNotificationUi.FLAG_NAME)
    fun testIsSticky_promotedAndExpanded_notifChipsFlagOn_promotedUiFlagOn_false() {
        assertThat(getIsSticky_promotedAndExpanded()).isFalse()
    }

    private fun getIsSticky_promotedAndExpanded(): Boolean {
        val notif = Notification.Builder(mContext, "").setSmallIcon(R.drawable.ic_person).build()
        notif.flags = FLAG_PROMOTED_ONGOING
        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, notif)
        val row = testHelper.createRow().apply { setPinnedStatus(PinnedStatus.PinnedBySystem) }
        notifEntry.row = row

        underTest.showNotification(notifEntry)

        val headsUpEntry = underTest.getHeadsUpEntry(notifEntry.key)
        headsUpEntry!!.setExpanded(true)

        return underTest.isSticky(notifEntry.key)
    }

    @Test
    fun testIsSticky_remoteInputActive_true() {
        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        underTest.showNotification(notifEntry)

        val headsUpEntry = underTest.getHeadsUpEntry(notifEntry.key)
        headsUpEntry!!.mRemoteInputActive = true

        assertThat(underTest.isSticky(notifEntry.key)).isTrue()
    }

    @Test
    fun testIsSticky_hasFullScreenIntent_true() {
        val notifEntry = HeadsUpManagerTestUtil.createFullScreenIntentEntry(/* id= */ 0, mContext)

        underTest.showNotification(notifEntry)

        assertThat(underTest.isSticky(notifEntry.key)).isTrue()
    }

    @Test
    fun testIsSticky_stickyForSomeTime_false() {
        val entry = createStickyForSomeTimeEntry(id = 0)

        underTest.showNotification(entry)

        assertThat(underTest.isSticky(entry.key)).isFalse()
    }

    @Test
    fun testIsSticky_false() {
        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        underTest.showNotification(notifEntry)

        val headsUpEntry = underTest.getHeadsUpEntry(notifEntry.key)
        headsUpEntry!!.setExpanded(false)
        headsUpEntry.mRemoteInputActive = false

        assertThat(underTest.isSticky(notifEntry.key)).isFalse()
    }

    @Test
    fun testShouldHeadsUpBecomePinned_noFSI_false() =
        kosmos.runTest {
            statusBarStateController.setState(StatusBarState.KEYGUARD)

            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

            assertThat(underTest.shouldHeadsUpBecomePinned(entry)).isFalse()
        }

    @Test
    fun testShouldHeadsUpBecomePinned_hasFSI_notUnpinned_true() =
        kosmos.runTest {
            statusBarStateController.setState(StatusBarState.KEYGUARD)

            val notifEntry =
                HeadsUpManagerTestUtil.createFullScreenIntentEntry(/* id= */ 0, mContext)

            // Add notifEntry to ANM mAlertEntries map and make it NOT unpinned
            underTest.showNotification(notifEntry, isPinnedByUser = false)

            val headsUpEntry = underTest.getHeadsUpEntry(notifEntry.key)
            headsUpEntry!!.mWasUnpinned = false

            assertThat(underTest.shouldHeadsUpBecomePinned(notifEntry)).isTrue()
        }

    @Test
    fun testShouldHeadsUpBecomePinned_wasUnpinned_false() =
        kosmos.runTest {
            statusBarStateController.setState(StatusBarState.KEYGUARD)

            val notifEntry =
                HeadsUpManagerTestUtil.createFullScreenIntentEntry(/* id= */ 0, mContext)

            // Add notifEntry to ANM mAlertEntries map and make it unpinned
            underTest.showNotification(notifEntry, isPinnedByUser = false)

            val headsUpEntry = underTest.getHeadsUpEntry(notifEntry.key)
            headsUpEntry!!.mWasUnpinned = true

            assertThat(underTest.shouldHeadsUpBecomePinned(notifEntry)).isFalse()
        }

    @Test
    @BrokenWithSceneContainer(381869885) // because `ShadeTestUtil.setShadeExpansion(0f)`
    // still causes `ShadeInteractor.isAnyExpanded` to emit `true`, when it should emit `false`.
    fun shouldHeadsUpBecomePinned_shadeNotExpanded_true() =
        kosmos.runTest {
            // GIVEN
            // TODO(b/381869885): We should be able to use `ShadeTestUtil.setShadeExpansion(0f)`
            // instead.
            shadeTestUtil.setLegacyExpandedOrAwaitingInputTransfer(false)

            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.SHADE)

            // THEN
            assertThat(underTest.shouldHeadsUpBecomePinned(entry)).isTrue()
        }

    @Test
    fun shouldHeadsUpBecomePinned_shadeLocked_false() =
        kosmos.runTest {
            // GIVEN
            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.SHADE_LOCKED)

            // THEN
            assertThat(underTest.shouldHeadsUpBecomePinned(entry)).isFalse()
        }

    @Test
    fun shouldHeadsUpBecomePinned_shadeUnknown_false() =
        kosmos.runTest {
            // GIVEN
            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(1207)

            // THEN
            assertThat(underTest.shouldHeadsUpBecomePinned(entry)).isFalse()
        }

    @Test
    fun shouldHeadsUpBecomePinned_keyguardWithBypassOn_true() =
        kosmos.runTest {
            // GIVEN
            whenever(keyguardBypassController.bypassEnabled).thenReturn(true)

            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.KEYGUARD)

            // THEN
            assertThat(underTest.shouldHeadsUpBecomePinned(entry)).isTrue()
        }

    @Test
    fun shouldHeadsUpBecomePinned_keyguardWithBypassOff_false() =
        kosmos.runTest {
            // GIVEN
            whenever(keyguardBypassController.bypassEnabled).thenReturn(false)

            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.KEYGUARD)

            // THEN
            assertThat(underTest.shouldHeadsUpBecomePinned(entry)).isFalse()
        }

    @Test
    fun shouldHeadsUpBecomePinned_shadeExpanded_false() =
        kosmos.runTest {
            // GIVEN
            shadeTestUtil.setShadeExpansion(1f)
            // TODO(b/381869885): Determine why we need both of these ShadeTestUtil calls.
            shadeTestUtil.setLegacyExpandedOrAwaitingInputTransfer(true)

            val entry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)
            statusBarStateController.setState(StatusBarState.SHADE)

            // THEN
            assertThat(underTest.shouldHeadsUpBecomePinned(entry)).isFalse()
        }

    @Test
    fun testCompareTo_withNullEntries() {
        val alertEntry = NotificationEntryBuilder().setTag("alert").build()

        underTest.showNotification(alertEntry)

        assertThat(underTest.compare(alertEntry, null)).isLessThan(0)
        assertThat(underTest.compare(null, alertEntry)).isGreaterThan(0)
        assertThat(underTest.compare(null, null)).isEqualTo(0)
    }

    @Test
    fun testCompareTo_withNonAlertEntries() {
        val nonAlertEntry1 = NotificationEntryBuilder().setTag("nae1").build()
        val nonAlertEntry2 = NotificationEntryBuilder().setTag("nae2").build()
        val alertEntry = NotificationEntryBuilder().setTag("alert").build()
        underTest.showNotification(alertEntry)

        assertThat(underTest.compare(alertEntry, nonAlertEntry1)).isLessThan(0)
        assertThat(underTest.compare(nonAlertEntry1, alertEntry)).isGreaterThan(0)
        assertThat(underTest.compare(nonAlertEntry1, nonAlertEntry2)).isEqualTo(0)
    }

    @Test
    fun testAlertEntryCompareTo_ongoingCallLessThanActiveRemoteInput() {
        val ongoingCall =
            underTest.HeadsUpEntry(
                NotificationEntryBuilder()
                    .setSbn(
                        HeadsUpManagerTestUtil.createSbn(
                            /* id = */ 0,
                            Notification.Builder(mContext, "")
                                .setCategory(Notification.CATEGORY_CALL)
                                .setOngoing(true),
                        )
                    )
                    .build()
            )

        val activeRemoteInput =
            underTest.HeadsUpEntry(HeadsUpManagerTestUtil.createEntry(/* id= */ 1, mContext))
        activeRemoteInput.mRemoteInputActive = true

        assertThat(ongoingCall.compareTo(activeRemoteInput)).isLessThan(0)
        assertThat(activeRemoteInput.compareTo(ongoingCall)).isGreaterThan(0)
    }

    @Test
    fun testAlertEntryCompareTo_incomingCallLessThanActiveRemoteInput() {
        val person = Person.Builder().setName("person").build()
        val intent = mock<PendingIntent>()
        val incomingCall =
            underTest.HeadsUpEntry(
                NotificationEntryBuilder()
                    .setSbn(
                        HeadsUpManagerTestUtil.createSbn(
                            /* id = */ 0,
                            Notification.Builder(mContext, "")
                                .setStyle(
                                    Notification.CallStyle.forIncomingCall(person, intent, intent)
                                ),
                        )
                    )
                    .build()
            )

        val activeRemoteInput =
            underTest.HeadsUpEntry(HeadsUpManagerTestUtil.createEntry(/* id= */ 1, mContext))
        activeRemoteInput.mRemoteInputActive = true

        assertThat(incomingCall.compareTo(activeRemoteInput)).isLessThan(0)
        assertThat(activeRemoteInput.compareTo(incomingCall)).isGreaterThan(0)
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testPinEntry_logsPeek_throttleEnabled() {
        // Needs full screen intent in order to be pinned
        val entryToPin =
            underTest.HeadsUpEntry(
                HeadsUpManagerTestUtil.createFullScreenIntentEntry(/* id= */ 0, mContext)
            )

        // Note: the standard way to show a notification would be calling showNotification rather
        // than onEntryAdded. However, in practice showNotification in effect adds
        // the notification and then updates it; in order to not log twice, the entry needs
        // to have a functional ExpandableNotificationRow that can keep track of whether it's
        // pinned or not (via isRowPinned()). That feels like a lot to pull in to test this one bit.
        underTest.onEntryAdded(entryToPin, /* requestedPinnedStatus= */ PinnedStatus.PinnedBySystem)

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(2)
        assertThat(AvalancheController.ThrottleEvent.AVALANCHE_THROTTLING_HUN_SHOWN.getId())
            .isEqualTo(uiEventLoggerFake.eventId(0))
        assertThat(HeadsUpManagerImpl.NotificationPeekEvent.NOTIFICATION_PEEK.id)
            .isEqualTo(uiEventLoggerFake.eventId(1))
    }

    @Test
    @DisableFlags(NotificationThrottleHun.FLAG_NAME)
    fun testPinEntry_logsPeek_throttleDisabled() {
        // Needs full screen intent in order to be pinned
        val entryToPin =
            underTest.HeadsUpEntry(
                HeadsUpManagerTestUtil.createFullScreenIntentEntry(/* id= */ 0, mContext)
            )

        // Note: the standard way to show a notification would be calling showNotification rather
        // than onEntryAdded. However, in practice showNotification in effect adds
        // the notification and then updates it; in order to not log twice, the entry needs
        // to have a functional ExpandableNotificationRow that can keep track of whether it's
        // pinned or not (via isRowPinned()). That feels like a lot to pull in to test this one bit.
        underTest.onEntryAdded(entryToPin, /* requestedPinnedStatus= */ PinnedStatus.PinnedBySystem)

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
        assertThat(HeadsUpManagerImpl.NotificationPeekEvent.NOTIFICATION_PEEK.id)
            .isEqualTo(uiEventLoggerFake.eventId(0))
    }

    @Test
    fun testSetUserActionMayIndirectlyRemove() {
        val notifEntry = HeadsUpManagerTestUtil.createEntry(/* id= */ 0, mContext)

        underTest.showNotification(notifEntry)

        assertThat(underTest.canRemoveImmediately(notifEntry.key)).isFalse()

        underTest.setUserActionMayIndirectlyRemove(notifEntry.key)

        assertThat(underTest.canRemoveImmediately(notifEntry.key)).isTrue()
    }

    private fun createStickyEntry(id: Int): NotificationEntry {
        val notif =
            Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setFullScreenIntent(mock<PendingIntent>(), /* highPriority= */ true)
                .build()
        return HeadsUpManagerTestUtil.createEntry(id, notif)
    }

    private fun createStickyForSomeTimeEntry(id: Int): NotificationEntry {
        val notif =
            Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setFlag(Notification.FLAG_FSI_REQUESTED_BUT_DENIED, true)
                .build()
        return HeadsUpManagerTestUtil.createEntry(id, notif)
    }

    private fun useAccessibilityTimeout(use: Boolean) {
        if (use) {
            whenever(kosmos.accessibilityManager.getRecommendedTimeoutMillis(any(), any()))
                .thenReturn(TEST_A11Y_AUTO_DISMISS_TIME)
        } else {
            doAnswer { it.getArgument(0) as Int }
                .whenever(kosmos.accessibilityManager)
                .getRecommendedTimeoutMillis(any(), any())
        }
    }

    companion object {
        private const val TEST_TOUCH_ACCEPTANCE_TIME = 200
        private const val TEST_A11Y_AUTO_DISMISS_TIME = 1000
        private const val TEST_EXTENSION_TIME = 500

        private const val TEST_MINIMUM_DISPLAY_TIME_DEFAULT = 400
        private const val TEST_MINIMUM_DISPLAY_TIME_FOR_USER_INITIATED = 500
        private const val TEST_AUTO_DISMISS_TIME = 600
        private const val TEST_STICKY_AUTO_DISMISS_TIME = 800

        init {
            assertThat(TEST_MINIMUM_DISPLAY_TIME_DEFAULT)
                .isLessThan(TEST_MINIMUM_DISPLAY_TIME_FOR_USER_INITIATED)
            assertThat(TEST_MINIMUM_DISPLAY_TIME_DEFAULT).isLessThan(TEST_AUTO_DISMISS_TIME)
            assertThat(TEST_MINIMUM_DISPLAY_TIME_FOR_USER_INITIATED)
                .isLessThan(TEST_AUTO_DISMISS_TIME)
            assertThat(TEST_AUTO_DISMISS_TIME).isLessThan(TEST_STICKY_AUTO_DISMISS_TIME)
            assertThat(TEST_STICKY_AUTO_DISMISS_TIME).isLessThan(TEST_A11Y_AUTO_DISMISS_TIME)
        }

        @get:Parameters(name = "{0}")
        @JvmStatic
        val flags: List<FlagsParameterization>
            get() = buildList {
                addAll(
                    FlagsParameterization.allCombinationsOf(
                            NotificationThrottleHun.FLAG_NAME,
                            StatusBarNotifChips.FLAG_NAME,
                        )
                        .andSceneContainer()
                )
            }
    }
}
