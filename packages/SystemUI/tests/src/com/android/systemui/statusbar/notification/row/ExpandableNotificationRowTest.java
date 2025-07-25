/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import static android.app.Flags.FLAG_NOTIFICATIONS_REDESIGN_TEMPLATES;

import static com.android.systemui.log.LogAssertKt.assertRunnableLogsWtf;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_ALL;
import static com.android.systemui.statusbar.notification.row.NotificationTestHelper.PKG;
import static com.android.systemui.statusbar.notification.row.NotificationTestHelper.USER_HANDLE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.ImageView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.widget.CachingIconView;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.SysuiTestableContext;
import com.android.systemui.flags.FakeFeatureFlagsClassic;
import com.android.systemui.flags.Flags;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips;
import com.android.systemui.statusbar.notification.AboveShelfChangedListener;
import com.android.systemui.statusbar.notification.FeedbackIcon;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.SourceType;
import com.android.systemui.statusbar.notification.collection.EntryAdapter;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryAdapter;
import com.android.systemui.statusbar.notification.collection.coordinator.VisualStabilityCoordinator;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager;
import com.android.systemui.statusbar.notification.headsup.PinnedStatus;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.notification.promoted.PromotedNotificationUi;
import com.android.systemui.statusbar.notification.row.ExpandableView.OnHeightChangedListener;
import com.android.systemui.statusbar.notification.row.icon.NotificationIconStyleProvider;
import com.android.systemui.statusbar.notification.row.wrapper.NotificationViewWrapper;
import com.android.systemui.statusbar.notification.shared.NotificationBundleUi;
import com.android.systemui.statusbar.notification.shared.NotificationContentAlphaOptimization;
import com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer;
import com.android.systemui.statusbar.phone.KeyguardBypassController;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

@SmallTest
@RunWith(AndroidJUnit4.class)
@RunWithLooper
public class ExpandableNotificationRowTest extends SysuiTestCase {

    private final FakeFeatureFlagsClassic mFeatureFlags = new FakeFeatureFlagsClassic();
    private NotificationTestHelper mNotificationTestHelper;
    @Rule public MockitoRule mockito = MockitoJUnit.rule();

    @Before
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();
        mFeatureFlags.set(Flags.ENABLE_NOTIFICATIONS_SIMULATE_SLOW_MEASURE, false);
        mNotificationTestHelper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this),
                mFeatureFlags);
        mNotificationTestHelper.setDefaultInflationFlags(FLAG_CONTENT_VIEW_ALL);
    }

    @Test
    public void testCanShowHeadsUp_notOnKeyguard_true() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();

        row.setOnKeyguard(false);

        assertTrue(row.canShowHeadsUp());
    }

    @Test
    public void testCanShowHeadsUp_dozing_true() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();

        StatusBarStateController statusBarStateControllerMock =
                mNotificationTestHelper.getStatusBarStateController();
        when(statusBarStateControllerMock.isDozing()).thenReturn(true);

        assertTrue(row.canShowHeadsUp());
    }

    @Test
    public void testCanShowHeadsUp_bypassEnabled_true() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();

        KeyguardBypassController keyguardBypassControllerMock =
                mNotificationTestHelper.getKeyguardBypassController();
        when(keyguardBypassControllerMock.getBypassEnabled()).thenReturn(true);

        assertTrue(row.canShowHeadsUp());
    }

    @Test
    public void testCanShowHeadsUp_stickyAndNotDemoted_true() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createStickyRow();

        assertTrue(row.canShowHeadsUp());
    }

    @Test
    public void testCanShowHeadsUp_false() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();

        row.setOnKeyguard(true);

        StatusBarStateController statusBarStateControllerMock =
                mNotificationTestHelper.getStatusBarStateController();
        when(statusBarStateControllerMock.isDozing()).thenReturn(false);

        KeyguardBypassController keyguardBypassControllerMock =
                mNotificationTestHelper.getKeyguardBypassController();
        when(keyguardBypassControllerMock.getBypassEnabled()).thenReturn(false);

        assertFalse(row.canShowHeadsUp());
    }

    @Test
    @DisableFlags(NotificationBundleUi.FLAG_NAME)
    public void testUpdateBackgroundColors_isRecursive() throws Exception {
        ExpandableNotificationRow group = mNotificationTestHelper.createGroup();
        group.setTintColor(Color.RED);
        group.getChildNotificationAt(0).setTintColor(Color.GREEN);
        group.getChildNotificationAt(1).setTintColor(Color.BLUE);

        assertThat(group.getCurrentBackgroundTint()).isEqualTo(Color.RED);
        assertThat(group.getChildNotificationAt(0).getCurrentBackgroundTint())
                .isEqualTo(Color.GREEN);
        assertThat(group.getChildNotificationAt(1).getCurrentBackgroundTint())
                .isEqualTo(Color.BLUE);

        group.updateBackgroundColors();

        int resetTint = group.getCurrentBackgroundTint();
        assertThat(resetTint).isNotEqualTo(Color.RED);
        assertThat(group.getChildNotificationAt(0).getCurrentBackgroundTint())
                .isEqualTo(resetTint);
        assertThat(group.getChildNotificationAt(1).getCurrentBackgroundTint())
                .isEqualTo(resetTint);
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATIONS_REDESIGN_TEMPLATES)
    public void setSensitive_doesNothingIfCalledAgain() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        measureAndLayout(row);

        // GIVEN a mocked public layout
        NotificationContentView mockPublicLayout = mock(NotificationContentView.class);
        row.setPublicLayout(mockPublicLayout);

        // GIVEN a sensitive notification row that's currently redacted
        row.setHideSensitiveForIntrinsicHeight(true);
        row.setSensitive(true, true);
        assertThat(row.getShowingLayout()).isSameInstanceAs(row.getPublicLayout());
        verify(mockPublicLayout).requestSelectLayout(eq(true));
        clearInvocations(mockPublicLayout);

        // WHEN the row is set to the same sensitive settings
        row.setSensitive(true, true);

        // VERIFY that the layout is not updated again
        assertThat(row.getShowingLayout()).isSameInstanceAs(row.getPublicLayout());
        verify(mockPublicLayout, never()).requestSelectLayout(anyBoolean());
    }

    @Test
    @EnableFlags(FLAG_NOTIFICATIONS_REDESIGN_TEMPLATES)
    public void testSetSensitiveOnNotifRowUpdatesLayout() throws Exception {
        // GIVEN a sensitive notification row that's currently redacted
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        measureAndLayout(row);
        row.setHideSensitiveForIntrinsicHeight(true);
        row.setSensitive(true, true);
        assertThat(row.getShowingLayout()).isSameInstanceAs(row.getPublicLayout());

        // GIVEN a mocked private layout
        NotificationContentView mockPrivateLayout = mock(NotificationContentView.class);
        row.setPrivateLayout(mockPrivateLayout);

        // WHEN the row is set to no longer be sensitive
        row.setSensitive(false, true);

        // VERIFY that the layout is updated
        assertThat(row.getShowingLayout()).isSameInstanceAs(row.getPrivateLayout());
        verify(mockPrivateLayout).requestSelectLayout(eq(true));
    }

    @Test
    @DisableFlags(FLAG_NOTIFICATIONS_REDESIGN_TEMPLATES)
    public void testSetSensitiveOnNotifRowNotifiesOfHeightChange() throws Exception {
        // GIVEN a sensitive notification row that's currently redacted
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        measureAndLayout(row);
        row.setHideSensitiveForIntrinsicHeight(true);
        row.setSensitive(true, true);
        assertThat(row.getShowingLayout()).isSameInstanceAs(row.getPublicLayout());
        assertThat(row.getIntrinsicHeight()).isGreaterThan(0);

        // GIVEN that the row has a height change listener
        OnHeightChangedListener listener = mock(OnHeightChangedListener.class);
        row.setOnHeightChangedListener(listener);

        // WHEN the row is set to no longer be sensitive
        row.setSensitive(false, true);

        // VERIFY that the height change listener is invoked
        assertThat(row.getShowingLayout()).isSameInstanceAs(row.getPrivateLayout());
        assertThat(row.getIntrinsicHeight()).isGreaterThan(0);
        verify(listener).onHeightChanged(eq(row), eq(true));
    }

    @Test
    public void testSetSensitiveOnGroupRowNotifiesOfHeightChange() throws Exception {
        // GIVEN a sensitive group row that's currently redacted
        ExpandableNotificationRow group = mNotificationTestHelper.createGroup();
        measureAndLayout(group);
        group.setHideSensitiveForIntrinsicHeight(true);
        group.setSensitive(true, true);
        assertThat(group.getShowingLayout()).isSameInstanceAs(group.getPublicLayout());
        assertThat(group.getIntrinsicHeight()).isGreaterThan(0);

        // GIVEN that the row has a height change listener
        OnHeightChangedListener listener = mock(OnHeightChangedListener.class);
        group.setOnHeightChangedListener(listener);

        // WHEN the row is set to no longer be sensitive
        group.setSensitive(false, true);

        // VERIFY that the height change listener is invoked
        assertThat(group.getShowingLayout()).isSameInstanceAs(group.getPrivateLayout());
        assertThat(group.getIntrinsicHeight()).isGreaterThan(0);
        verify(listener).onHeightChanged(eq(group), eq(true));
    }

    @Test
    public void testSetSensitiveOnPublicRowDoesNotNotifyOfHeightChange() throws Exception {
        // create a notification row whose public version is identical
        Notification publicNotif = mNotificationTestHelper.createNotification();
        publicNotif.publicVersion = mNotificationTestHelper.createNotification();
        ExpandableNotificationRow publicRow = mNotificationTestHelper.createRow(publicNotif);

        // GIVEN a sensitive public row that's currently redacted
        measureAndLayout(publicRow);
        publicRow.setHideSensitiveForIntrinsicHeight(true);
        publicRow.setSensitive(true, true);
        assertThat(publicRow.getShowingLayout()).isSameInstanceAs(publicRow.getPublicLayout());
        assertThat(publicRow.getIntrinsicHeight()).isGreaterThan(0);

        // GIVEN that the row has a height change listener
        OnHeightChangedListener listener = mock(OnHeightChangedListener.class);
        publicRow.setOnHeightChangedListener(listener);

        // WHEN the row is set to no longer be sensitive
        publicRow.setSensitive(false, true);

        // VERIFY that the height change listener is not invoked, because the height didn't change
        assertThat(publicRow.getShowingLayout()).isSameInstanceAs(publicRow.getPrivateLayout());
        assertThat(publicRow.getIntrinsicHeight()).isGreaterThan(0);
        assertThat(publicRow.getPrivateLayout().getMinHeight())
                .isEqualTo(publicRow.getPublicLayout().getMinHeight());
        verify(listener, never()).onHeightChanged(eq(publicRow), anyBoolean());
    }

    private void measureAndLayout(ExpandableNotificationRow row) {
        DisplayMetrics dm = new DisplayMetrics();
        getContext().getDisplay().getRealMetrics(dm);
        int width = (int) Math.ceil(400f * dm.density);
        int height = (int) Math.ceil(600f * dm.density);

        row.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.UNSPECIFIED)
        );
        row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
    }

    @Test
    public void testGroupSummaryNotShowingIconWhenPublic() throws Exception {
        ExpandableNotificationRow group = mNotificationTestHelper.createGroup();

        group.setSensitive(true, true);
        group.setHideSensitiveForIntrinsicHeight(true);
        assertTrue(group.isSummaryWithChildren());
        assertFalse(group.isShowingIcon());
    }

    @Test
    public void testNotificationHeaderVisibleWhenAnimating() throws Exception {
        ExpandableNotificationRow group = mNotificationTestHelper.createGroup();

        group.setSensitive(true, true);
        group.setHideSensitive(true, false, 0, 0);
        group.setHideSensitive(false, true, 0, 0);
        assertEquals(View.VISIBLE, group.getChildrenContainer().getVisibleWrapper()
                .getNotificationHeader().getVisibility());
    }

    @Test
    public void testUserLockedResetEvenWhenNoChildren() throws Exception {
        ExpandableNotificationRow group = mNotificationTestHelper.createGroup();

        group.setUserLocked(true);
        group.setUserLocked(false);
        assertFalse("The childrencontainer should not be userlocked but is, the state "
                + "seems out of sync.", group.getChildrenContainer().isUserLocked());
    }

    @Test
    @EnableFlags(NotificationContentAlphaOptimization.FLAG_NAME)
    public void setHideSensitive_shouldNotDisturbAnimation() throws Exception {
        //Given: A row that is during alpha animation
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();

        assertEquals(row.getPrivateLayout(), row.getContentView());
        row.setContentAlpha(0.5f);

        //When: Set its hideSensitive without changing the content view to show
        row.setHideSensitive(
                /* hideSensitive= */ false,
                /* animated= */ false,
                /* delay=  */ 0L,
                /* duration=  */ 0L
        );
        assertEquals(row.getPrivateLayout(), row.getContentView());

        //Then: The alpha value should not be reset
        assertEquals(0.5f, row.getPrivateLayout().getAlpha(), 0);
    }

    @Test
    @EnableFlags(NotificationContentAlphaOptimization.FLAG_NAME)
    public void setHideSensitive_changeContent_shouldResetAlpha() throws Exception {

        // Given: A sensitive row that has public version but is not hiding sensitive,
        // and is during an animation that sets its alpha value to be 0.5f
        Notification publicNotif = mNotificationTestHelper.createNotification();
        publicNotif.publicVersion = mNotificationTestHelper.createNotification();
        ExpandableNotificationRow row = mNotificationTestHelper.createRow(publicNotif);
        row.setSensitive(true, false);
        row.setContentAlpha(0.5f);

        assertEquals(0.5f, row.getPrivateLayout().getAlpha(), 0);
        assertEquals(View.VISIBLE, row.getPrivateLayout().getVisibility());

        // When: Change its hideSensitive and changes the content view to show the public version
        row.setHideSensitive(
                /* hideSensitive= */ true,
                /* animated= */ false,
                /* delay=  */ 0L,
                /* duration=  */ 0L
        );

        // Then: The alpha value of private layout should be reset to 1, private layout be
        // INVISIBLE;
        // The alpha value of public layout should be reset to 1 to avoid remaining transparent,
        // public layout should be VISIBLE
        assertEquals(View.INVISIBLE, row.getPrivateLayout().getVisibility());
        assertEquals(1f, row.getPrivateLayout().getAlpha(), 0);
        assertEquals(View.VISIBLE, row.getPublicLayout().getVisibility());
        assertEquals(1f, row.getPublicLayout().getAlpha(), 0);
    }

    @Test
    public void testReinflatedOnDensityChange() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        NotificationChildrenContainer mockContainer = mock(NotificationChildrenContainer.class);
        row.setChildrenContainer(mockContainer);

        row.onDensityOrFontScaleChanged();

        verify(mockContainer).reInflateViews(any());
    }

    @Test
    public void testIconColorShouldBeUpdatedWhenSensitive() throws Exception {
        ExpandableNotificationRow row = spy(mNotificationTestHelper.createRow(
                FLAG_CONTENT_VIEW_ALL));
        row.setSensitive(true, true);
        row.setHideSensitive(true, false, 0, 0);
        verify(row).updateShelfIconColor();
    }

    @Test
    public void testAboveShelfChangedListenerCalled() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        AboveShelfChangedListener listener = mock(AboveShelfChangedListener.class);
        row.setAboveShelfChangedListener(listener);
        row.setHeadsUp(true);
        verify(listener).onAboveShelfStateChanged(true);
    }

    @Test
    public void testAboveShelfChangedListenerCalledPinned() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        AboveShelfChangedListener listener = mock(AboveShelfChangedListener.class);
        row.setAboveShelfChangedListener(listener);
        row.setPinnedStatus(PinnedStatus.PinnedBySystem);
        verify(listener).onAboveShelfStateChanged(true);
    }

    @Test
    public void testAboveShelfChangedListenerCalledHeadsUpAnimatingAway() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        AboveShelfChangedListener listener = mock(AboveShelfChangedListener.class);
        row.setAboveShelfChangedListener(listener);
        row.setHeadsUpAnimatingAway(true);
        verify(listener).onAboveShelfStateChanged(true);
    }
    @Test
    public void testAboveShelfChangedListenerCalledWhenGoingBelow() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        AboveShelfChangedListener listener = mock(AboveShelfChangedListener.class);
        row.setAboveShelfChangedListener(listener);
        Mockito.reset(listener);
        row.setHeadsUp(true);
        row.setAboveShelf(false);
        verify(listener).onAboveShelfStateChanged(false);
    }

    @Test
    public void testClickSound() throws Exception {
        ExpandableNotificationRow group = mNotificationTestHelper.createGroup();

        assertTrue("Should play sounds by default.", group.isSoundEffectsEnabled());
        StatusBarStateController mock = mNotificationTestHelper.getStatusBarStateController();
        when(mock.isDozing()).thenReturn(true);
        group.setSecureStateProvider(()-> false);
        assertFalse("Shouldn't play sounds when dark and trusted.",
                group.isSoundEffectsEnabled());
        group.setSecureStateProvider(()-> true);
        assertTrue("Should always play sounds when not trusted.",
                group.isSoundEffectsEnabled());
    }

    @Test
    public void testSetDismissed_longPressListenerRemoved() throws Exception {
        ExpandableNotificationRow group = mNotificationTestHelper.createGroup();

        ExpandableNotificationRow.LongPressListener listener =
                mock(ExpandableNotificationRow.LongPressListener.class);
        group.setLongPressListener(listener);
        group.doLongClickCallback(0, 0);
        verify(listener, times(1)).onLongPress(eq(group), eq(0), eq(0),
                any(NotificationMenuRowPlugin.MenuItem.class));
        reset(listener);

        group.dismiss(true);
        group.doLongClickCallback(0, 0);
        verify(listener, times(0)).onLongPress(eq(group), eq(0), eq(0),
                any(NotificationMenuRowPlugin.MenuItem.class));
    }

    @Test
    public void testFeedback_noHeader() throws Exception {
        ExpandableNotificationRow groupRow = mNotificationTestHelper.createGroup();

        // public notification is custom layout - no header
        groupRow.setSensitive(true, true);
        groupRow.setOnFeedbackClickListener(null);
        groupRow.setFeedbackIcon(null);
    }

    @Test
    public void testFeedback_header() throws Exception {
        ExpandableNotificationRow group = mNotificationTestHelper.createGroup();

        NotificationContentView publicLayout = mock(NotificationContentView.class);
        group.setPublicLayout(publicLayout);
        NotificationContentView privateLayout = mock(NotificationContentView.class);
        group.setPrivateLayout(privateLayout);
        NotificationChildrenContainer mockContainer = mock(NotificationChildrenContainer.class);
        when(mockContainer.getNotificationChildCount()).thenReturn(1);
        group.setChildrenContainer(mockContainer);

        final boolean show = true;
        final FeedbackIcon icon = new FeedbackIcon(
                R.drawable.ic_feedback_alerted, R.string.notification_feedback_indicator_alerted);
        group.setFeedbackIcon(icon);

        verify(mockContainer, times(1)).setFeedbackIcon(icon);
        verify(privateLayout, times(1)).setFeedbackIcon(icon);
        verify(publicLayout, times(1)).setFeedbackIcon(icon);
    }

    @Test
    public void testFeedbackOnClick() throws Exception {
        ExpandableNotificationRow group = mNotificationTestHelper.createGroup();

        ExpandableNotificationRow.CoordinateOnClickListener l = mock(
                ExpandableNotificationRow.CoordinateOnClickListener.class);
        View view = mock(View.class);

        group.setOnFeedbackClickListener(l);

        group.getFeedbackOnClickListener().onClick(view);
        verify(l, times(1)).onClick(any(), anyInt(), anyInt(), any());
    }

    @Test
    public void testHeadsUpAnimatingAwayListener() throws Exception {
        ExpandableNotificationRow group = mNotificationTestHelper.createGroup();
        Consumer<Boolean> headsUpListener = mock(Consumer.class);
        AboveShelfChangedListener aboveShelfChangedListener = mock(AboveShelfChangedListener.class);
        group.setHeadsUpAnimatingAwayListener(headsUpListener);
        group.setAboveShelfChangedListener(aboveShelfChangedListener);

        group.setHeadsUpAnimatingAway(true);
        verify(headsUpListener).accept(true);
        verify(aboveShelfChangedListener).onAboveShelfStateChanged(true);

        group.setHeadsUpAnimatingAway(false);
        verify(headsUpListener).accept(false);
        verify(aboveShelfChangedListener).onAboveShelfStateChanged(false);
    }

    @Test
    public void testIconScrollXAfterTranslationAndReset() throws Exception {
        ExpandableNotificationRow group = mNotificationTestHelper.createGroup();

        group.setDismissUsingRowTranslationX(false, false);
        group.setTranslation(50);
        assertEquals(50, -group.getEntry().getIcons().getShelfIcon().getScrollX());

        group.resetTranslation();
        assertEquals(0, group.getEntry().getIcons().getShelfIcon().getScrollX());
    }

    @Test
    public void testIsExpanded_userExpanded() throws Exception {
        ExpandableNotificationRow group = mNotificationTestHelper.createGroup();

        group.setExpandable(true);
        Assert.assertFalse(group.isExpanded());
        group.setUserExpanded(true);
        Assert.assertTrue(group.isExpanded());
    }

    @Test
    @DisableFlags(NotificationBundleUi.FLAG_NAME)
    public void testGetIsNonblockable() throws Exception {
        ExpandableNotificationRow row =
                mNotificationTestHelper.createRow(mNotificationTestHelper.createNotification());
        row.setEntryLegacy(null);

        assertTrue(row.getIsNonblockable());

        NotificationEntry entry = mock(NotificationEntry.class);

        Mockito.doReturn(false, true).when(entry).isBlockable();
        row.setEntryLegacy(entry);
        assertTrue(row.getIsNonblockable());
        assertFalse(row.getIsNonblockable());
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_NOTIFICATION_REENTRANT_DISMISS)
    public void testCanDismiss_immediately() throws Exception {
        ExpandableNotificationRow row =
                mNotificationTestHelper.createRow(mNotificationTestHelper.createNotification());
        when(mNotificationTestHelper.getDismissibilityProvider().isDismissable(
                row.getEntry().getKey()))
                .thenReturn(true);
        row.performDismiss(false);
        verify(mNotificationTestHelper.getOnUserInteractionCallback())
                .registerFutureDismissal(any(), anyInt());
    }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_NOTIFICATION_REENTRANT_DISMISS)
    public void testCanDismiss() throws Exception {
        ExpandableNotificationRow row =
                mNotificationTestHelper.createRow(mNotificationTestHelper.createNotification());
        when(mNotificationTestHelper.getDismissibilityProvider().isDismissable(
                row.getEntry().getKey()))
                .thenReturn(true);
        row.performDismiss(false);
        TestableLooper.get(this).processAllMessages();
        verify(mNotificationTestHelper.getOnUserInteractionCallback())
                .registerFutureDismissal(any(), anyInt());
    }

    @Test
    public void testCannotDismiss() throws Exception {
        ExpandableNotificationRow row =
                mNotificationTestHelper.createRow(mNotificationTestHelper.createNotification());
        when(mNotificationTestHelper.getDismissibilityProvider().isDismissable(
                row.getEntry().getKey()))
                .thenReturn(false);
        row.performDismiss(false);
        verify(mNotificationTestHelper.getOnUserInteractionCallback(), never())
                .registerFutureDismissal(any(), anyInt());
    }

    @Test
    public void testAddChildNotification() throws Exception {
        ExpandableNotificationRow group = mNotificationTestHelper.createGroup(0);
        ExpandableNotificationRow child = mNotificationTestHelper.createRow();

        group.addChildNotification(child);

        Assert.assertEquals(child, group.getChildNotificationAt(0));
        Assert.assertEquals(group, child.getNotificationParent());
        Assert.assertTrue(child.isChildInGroup());
    }

    @Test
    public void testAddChildNotification_childSkipped() throws Exception {
        ExpandableNotificationRow group = mNotificationTestHelper.createGroup(0);
        ExpandableNotificationRow child = mNotificationTestHelper.createRow();
        child.setKeepInParentForDismissAnimation(true);

        group.addChildNotification(child);

        Assert.assertTrue(group.getAttachedChildren().isEmpty());
        Assert.assertNotEquals(group, child.getNotificationParent());
        verify(mNotificationTestHelper.getMockLogger()).logSkipAttachingKeepInParentChild(
                /*child=*/ child.getLoggingKey(),
                /*newParent=*/ group.getLoggingKey()
        );
    }

    @Test
    public void testRemoveChildNotification() throws Exception {
        ExpandableNotificationRow group = mNotificationTestHelper.createGroup(1);
        ExpandableNotificationRow child = group.getAttachedChildren().get(0);
        child.setKeepInParentForDismissAnimation(true);

        group.removeChildNotification(child);

        Assert.assertNull(child.getParent());
        Assert.assertNull(child.getNotificationParent());
        Assert.assertFalse(child.keepInParentForDismissAnimation());
        verify(mNotificationTestHelper.getMockLogger())
                .logCancelAppearDrawing(child.getLoggingKey(), false);
        verifyNoMoreInteractions(mNotificationTestHelper.getMockLogger());
    }

    @Test
    public void testRemoveChildrenWithKeepInParent_removesChildWithKeepInParent() throws Exception {
        ExpandableNotificationRow group = mNotificationTestHelper.createGroup(1);
        ExpandableNotificationRow child = group.getAttachedChildren().get(0);
        child.setKeepInParentForDismissAnimation(true);

        group.removeChildrenWithKeepInParent();

        Assert.assertNull(child.getParent());
        Assert.assertNull(child.getNotificationParent());
        Assert.assertFalse(child.keepInParentForDismissAnimation());
        verify(mNotificationTestHelper.getMockLogger()).logKeepInParentChildDetached(
                /*child=*/ child.getLoggingKey(),
                /*oldParent=*/ group.getLoggingKey()
        );
    }

    @Test
    public void testRemoveChildrenWithKeepInParent_skipsChildrenWithoutKeepInParent()
            throws Exception {
        ExpandableNotificationRow group = mNotificationTestHelper.createGroup(1);
        ExpandableNotificationRow child = group.getAttachedChildren().get(0);

        group.removeChildrenWithKeepInParent();

        Assert.assertEquals(group, child.getNotificationParent());
        Assert.assertFalse(child.keepInParentForDismissAnimation());
        verify(mNotificationTestHelper.getMockLogger(), never()).logKeepInParentChildDetached(
                /*child=*/ any(),
                /*oldParent=*/ any()
        );
    }

    @Test
    public void applyRoundnessAndInvalidate_should_be_immediately_applied_on_childrenContainer()
            throws Exception {
        ExpandableNotificationRow group = mNotificationTestHelper.createGroup();
        Assert.assertEquals(0f, group.getBottomRoundness(), 0.001f);
        Assert.assertEquals(0f, group.getChildrenContainer().getBottomRoundness(), 0.001f);

        group.requestBottomRoundness(1f, SourceType.from(""), false);

        Assert.assertEquals(1f, group.getBottomRoundness(), 0.001f);
        Assert.assertEquals(1f, group.getChildrenContainer().getBottomRoundness(), 0.001f);
    }

    @Test
    public void testSetContentAnimationRunning_Run() throws Exception {
        // Create views for the notification row.
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        NotificationContentView publicLayout = mock(NotificationContentView.class);
        row.setPublicLayout(publicLayout);
        NotificationContentView privateLayout = mock(NotificationContentView.class);
        row.setPrivateLayout(privateLayout);

        row.setAnimationRunning(true);
        verify(publicLayout, times(1)).setContentAnimationRunning(true);
        verify(privateLayout, times(1)).setContentAnimationRunning(true);
    }

    @Test
    public void testSetContentAnimationRunning_Stop() throws Exception {
        // Create views for the notification row.
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        NotificationContentView publicLayout = mock(NotificationContentView.class);
        row.setPublicLayout(publicLayout);
        NotificationContentView privateLayout = mock(NotificationContentView.class);
        row.setPrivateLayout(privateLayout);

        row.setAnimationRunning(false);
        verify(publicLayout, times(1)).setContentAnimationRunning(false);
        verify(privateLayout, times(1)).setContentAnimationRunning(false);
    }

    @Test
    public void testSetContentAnimationRunningInGroupChild_Run() throws Exception {
        // Creates parent views on groupRow.
        ExpandableNotificationRow groupRow = mNotificationTestHelper.createGroup();
        NotificationContentView publicParentLayout = mock(NotificationContentView.class);
        groupRow.setPublicLayout(publicParentLayout);
        NotificationContentView privateParentLayout = mock(NotificationContentView.class);
        groupRow.setPrivateLayout(privateParentLayout);

        // Create child views on row.
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        NotificationContentView publicChildLayout = mock(NotificationContentView.class);
        row.setPublicLayout(publicChildLayout);
        NotificationContentView privateChildLayout = mock(NotificationContentView.class);
        row.setPrivateLayout(privateChildLayout);
        when(row.isGroupExpanded()).thenReturn(true);
        setMockChildrenContainer(groupRow, row);

        groupRow.setAnimationRunning(true);
        verify(publicParentLayout, times(1)).setContentAnimationRunning(true);
        verify(privateParentLayout, times(1)).setContentAnimationRunning(true);
        // The child layouts should be started too.
        verify(publicChildLayout, times(1)).setContentAnimationRunning(true);
        verify(privateChildLayout, times(1)).setContentAnimationRunning(true);
    }


    @Test
    public void testSetIconAnimationRunningGroup_Run() throws Exception {
        // Create views for a group row.
        ExpandableNotificationRow group = mNotificationTestHelper.createGroup();
        ExpandableNotificationRow child = mNotificationTestHelper.createRow();
        NotificationContentView publicParentLayout = mock(NotificationContentView.class);
        group.setPublicLayout(publicParentLayout);
        NotificationContentView privateParentLayout = mock(NotificationContentView.class);
        group.setPrivateLayout(privateParentLayout);
        when(group.isGroupExpanded()).thenReturn(true);

        // Add the child to the group.
        NotificationContentView publicChildLayout = mock(NotificationContentView.class);
        child.setPublicLayout(publicChildLayout);
        NotificationContentView privateChildLayout = mock(NotificationContentView.class);
        child.setPrivateLayout(privateChildLayout);
        when(child.isGroupExpanded()).thenReturn(true);

        NotificationChildrenContainer mockContainer =
                setMockChildrenContainer(group, child);

        // Mock the children view wrappers, and give them each an icon.
        NotificationViewWrapper mockViewWrapper = mock(NotificationViewWrapper.class);
        when(mockContainer.getNotificationViewWrapper()).thenReturn(mockViewWrapper);
        CachingIconView mockIcon = mock(CachingIconView.class);
        when(mockViewWrapper.getIcon()).thenReturn(mockIcon);

        NotificationViewWrapper mockLowPriorityViewWrapper = mock(NotificationViewWrapper.class);
        when(mockContainer.getMinimizedGroupHeaderWrapper()).thenReturn(mockLowPriorityViewWrapper);
        CachingIconView mockLowPriorityIcon = mock(CachingIconView.class);
        when(mockLowPriorityViewWrapper.getIcon()).thenReturn(mockLowPriorityIcon);

        // Give the icon image views drawables, so we can make sure they animate.
        // We use both AnimationDrawables and AnimatedVectorDrawables to ensure both work.
        AnimationDrawable drawable = mock(AnimationDrawable.class);
        AnimatedVectorDrawable vectorDrawable = mock(AnimatedVectorDrawable.class);
        setDrawableIconsInImageView(mockIcon, drawable, vectorDrawable);

        AnimationDrawable lowPriDrawable = mock(AnimationDrawable.class);
        AnimatedVectorDrawable lowPriVectorDrawable = mock(AnimatedVectorDrawable.class);
        setDrawableIconsInImageView(mockLowPriorityIcon, lowPriDrawable, lowPriVectorDrawable);

        group.setAnimationRunning(true);
        verify(drawable, times(1)).start();
        verify(vectorDrawable, times(1)).start();
        verify(lowPriDrawable, times(1)).start();
        verify(lowPriVectorDrawable, times(1)).start();
    }

    @Test
    public void isExpanded_hideSensitive_sensitiveNotExpanded()
            throws Exception {
        // GIVEN
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        row.setUserExpanded(true);
        row.setOnKeyguard(false);
        row.setSensitive(/* sensitive= */true, /* hideSensitive= */false);
        row.setHideSensitiveForIntrinsicHeight(/* hideSensitive= */true);

        // THEN
        assertThat(row.isExpanded()).isFalse();
    }

    @Test
    public void isExpanded_hideSensitive_nonSensitiveExpanded()
            throws Exception {
        // GIVEN
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        row.setUserExpanded(true);
        row.setOnKeyguard(false);
        row.setSensitive(/* sensitive= */true, /* hideSensitive= */false);
        row.setHideSensitiveForIntrinsicHeight(/* hideSensitive= */false);

        // THEN
        assertThat(row.isExpanded()).isTrue();
    }

    @Test
    public void isExpanded_onKeyguard_allowOnKeyguardExpanded() throws Exception {
        // GIVEN
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        row.setOnKeyguard(true);
        row.setUserExpanded(true);

        // THEN
        assertThat(row.isExpanded(/*allowOnKeyguard =*/ true)).isTrue();
    }
    @Test
    public void isExpanded_onKeyguard_notAllowOnKeyguardNotExpanded() throws Exception {
        // GIVEN
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        row.setOnKeyguard(true);
        row.setUserExpanded(true);

        // THEN
        assertThat(row.isExpanded(/*allowOnKeyguard =*/ false)).isFalse();
    }

    @Test
    public void isExpanded_systemExpanded_expanded() throws Exception {
        // GIVEN
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        row.setOnKeyguard(false);
        row.setSystemExpanded(true);

        // THEN
        assertThat(row.isExpanded()).isTrue();
    }

    @Test
    public void isExpanded_systemChildExpanded_expanded() throws Exception {
        // GIVEN
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        row.setOnKeyguard(false);
        row.setSystemChildExpanded(true);

        // THEN
        assertThat(row.isExpanded()).isTrue();
    }

    @Test
    public void isExpanded_userExpanded_expanded() throws Exception {
        // GIVEN
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        row.setOnKeyguard(false);
        row.setSystemExpanded(true);
        row.setUserExpanded(true);

        // THEN
        assertThat(row.isExpanded()).isTrue();
    }

    @Test
    public void isExpanded_userExpandedFalse_notExpanded() throws Exception {
        // GIVEN
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        row.setOnKeyguard(false);
        row.setSystemExpanded(true);
        row.setUserExpanded(false);

        // THEN
        assertThat(row.isExpanded()).isFalse();
    }

    @Test
    @EnableFlags(PromotedNotificationUi.FLAG_NAME)
    @DisableFlags(NotificationBundleUi.FLAG_NAME)
    public void isExpanded_sensitivePromotedNotification_notExpanded() throws Exception {
        // GIVEN
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        NotificationEntry entry = mock(NotificationEntry.class);
        when(entry.isPromotedOngoing()).thenReturn(true);
        row.setEntryLegacy(entry);
        setRowPromotedOngoing(row);
        row.setSensitive(/* sensitive= */true, /* hideSensitive= */false);
        row.setHideSensitiveForIntrinsicHeight(/* hideSensitive= */true);

        // THEN
        assertThat(row.isExpanded()).isFalse();
    }

    @Test
    @EnableFlags(PromotedNotificationUi.FLAG_NAME)
    @DisableFlags(NotificationBundleUi.FLAG_NAME)
    public void isExpanded_promotedNotificationNotOnKeyguard_expanded() throws Exception {
        // GIVEN
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        NotificationEntry entry = mock(NotificationEntry.class);
        when(entry.isPromotedOngoing()).thenReturn(true);
        row.setEntryLegacy(entry);
        setRowPromotedOngoing(row);
        row.setOnKeyguard(false);

        // THEN
        assertThat(row.isExpanded()).isTrue();
    }

    @Test
    @EnableFlags(PromotedNotificationUi.FLAG_NAME)
    @DisableFlags(NotificationBundleUi.FLAG_NAME)
    public void isExpanded_promotedNotificationAllowOnKeyguard_expanded() throws Exception {
        // GIVEN
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        NotificationEntry entry = mock(NotificationEntry.class);
        when(entry.isPromotedOngoing()).thenReturn(true);
        row.setEntryLegacy(entry);
        setRowPromotedOngoing(row);
        row.setOnKeyguard(true);

        // THEN
        assertThat(row.isExpanded(/* allowOnKeyguard = */ true)).isTrue();
    }

    @Test
    @EnableFlags(PromotedNotificationUi.FLAG_NAME)
    @DisableFlags(NotificationBundleUi.FLAG_NAME)
    public void isExpanded_promotedNotificationIgnoreLockscreenConstraints_expanded()
            throws Exception {
        // GIVEN
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        NotificationEntry entry = mock(NotificationEntry.class);
        when(entry.isPromotedOngoing()).thenReturn(true);
        row.setEntryLegacy(entry);
        setRowPromotedOngoing(row);
        row.setOnKeyguard(true);
        row.setIgnoreLockscreenConstraints(true);

        // THEN
        assertThat(row.isExpanded()).isTrue();
    }

    private static void setRowPromotedOngoing(ExpandableNotificationRow row) {
        final NotificationEntry entry = mock(NotificationEntry.class);
        when(entry.isPromotedOngoing()).thenReturn(true);
        if (NotificationBundleUi.isEnabled()) {
            final EntryAdapter entryAdapter = new NotificationEntryAdapter(
                    mock(NotificationActivityStarter.class),
                    mock(MetricsLogger.class),
                    mock(PeopleNotificationIdentifier.class),
                    mock(NotificationIconStyleProvider.class),
                    mock(VisualStabilityCoordinator.class),
                    mock(NotificationActionClickManager.class),
                    mock(HighPriorityProvider.class),
                    mock(HeadsUpManager.class),
                    entry);
            row.setEntryAdapter(entryAdapter);
        } else {
            row.setEntryLegacy(entry);
        }
    }

    @Test
    @EnableFlags(PromotedNotificationUi.FLAG_NAME)
    @DisableFlags(NotificationBundleUi.FLAG_NAME)
    public void isExpanded_promotedNotificationSaveSpaceOnLockScreen_notExpanded()
            throws Exception {
        // GIVEN
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        NotificationEntry entry = mock(NotificationEntry.class);
        when(entry.isPromotedOngoing()).thenReturn(true);
        row.setEntryLegacy(entry);
        setRowPromotedOngoing(row);
        row.setOnKeyguard(true);
        row.setSaveSpaceOnLockscreen(true);

        // THEN
        assertThat(row.isExpanded()).isFalse();
    }

    @Test
    @EnableFlags(PromotedNotificationUi.FLAG_NAME)
    @DisableFlags(NotificationBundleUi.FLAG_NAME)
    public void isExpanded_promotedNotificationNotSaveSpaceOnLockScreen_expanded()
            throws Exception {
        // GIVEN
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        NotificationEntry entry = mock(NotificationEntry.class);
        when(entry.isPromotedOngoing()).thenReturn(true);
        row.setEntryLegacy(entry);
        setRowPromotedOngoing(row);
        row.setOnKeyguard(true);
        row.setSaveSpaceOnLockscreen(false);

        // THEN
        assertThat(row.isExpanded()).isTrue();
    }

    @Test
    public void onDisappearAnimationFinished_shouldSetFalse_headsUpAnimatingAway()
            throws Exception {
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();

        // Initial state: suppose heads up animation in progress
        row.setHeadsUpAnimatingAway(true);
        assertThat(row.isHeadsUpAnimatingAway()).isTrue();

        // on disappear animation ends
        row.onAppearAnimationFinished(/* wasAppearing = */ false, /* cancelled = */ false);
        assertThat(row.isHeadsUpAnimatingAway()).isFalse();
    }

    @Test
    public void onHUNAppear_cancelAppearDrawing_shouldResetAnimationState() throws Exception {
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();

        row.performAddAnimation(/* delay */ 0, /* duration */ 1000, /* isHeadsUpAppear */ true);

        waitForIdleSync();
        assertThat(row.isDrawingAppearAnimation()).isTrue();

        row.cancelAppearDrawing();

        waitForIdleSync();
        assertThat(row.isDrawingAppearAnimation()).isFalse();
    }

    @Test
    public void onHUNDisappear_cancelAppearDrawing_shouldResetAnimationState() throws Exception {
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();

        row.performAddAnimation(/* delay */ 0, /* duration */ 1000, /* isHeadsUpAppear */ false);

        waitForIdleSync();
        assertThat(row.isDrawingAppearAnimation()).isTrue();

        row.cancelAppearDrawing();

        waitForIdleSync();
        assertThat(row.isDrawingAppearAnimation()).isFalse();
    }

    @Test
    public void imageResolver_sameNotificationUser_usesContext() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createRow(PKG,
                USER_HANDLE.getUid(1234), USER_HANDLE);

        assertThat(row.getImageResolver().getContext()).isSameInstanceAs(mContext);
    }

    @Test
    public void imageResolver_differentNotificationUser_createsUserContext() throws Exception {
        UserHandle user = new UserHandle(33);
        Context userContext = new SysuiTestableContext(mContext);
        mContext.prepareCreateContextAsUser(user, userContext);

        ExpandableNotificationRow row = mNotificationTestHelper.createRow(PKG,
                user.getUid(1234), user);

        assertThat(row.getImageResolver().getContext()).isSameInstanceAs(userContext);
    }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_NOTIFICATIONS_PINNED_HUN_IN_SHADE)
    public void mustStayOnScreen_false() throws Exception {
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        assertThat(row.mustStayOnScreen()).isFalse();
    }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_NOTIFICATIONS_PINNED_HUN_IN_SHADE)
    public void mustStayOnScreen_isHeadsUp_markedAsSeen() throws Exception {
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        // When the row is a HUN
        row.setHeadsUp(true);
        //Then it must stay on screen
        assertThat(row.mustStayOnScreen()).isTrue();
        // And when the user has seen it
        row.markHeadsUpSeen();
        // Then it should NOT stay on screen anymore
        assertThat(row.mustStayOnScreen()).isFalse();
    }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_NOTIFICATIONS_PINNED_HUN_IN_SHADE)
    public void mustStayOnScreen_isPinned_markedAsSeen() throws Exception {
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        // When a HUN is pinned
        row.setHeadsUp(true);
        row.setPinnedStatus(PinnedStatus.PinnedBySystem);
        //Then it must stay on screen
        assertThat(row.mustStayOnScreen()).isTrue();
        // And when the user has seen it
        row.markHeadsUpSeen();
        // Then it should still stay on screen
        assertThat(row.mustStayOnScreen()).isTrue();
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_NOTIFICATIONS_PINNED_HUN_IN_SHADE)
    public void mustStayOnScreen_isPinned_markedAsSeen_false() throws Exception {
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        // When a HUN is pinned
        row.setHeadsUp(true);
        row.setPinnedStatus(PinnedStatus.PinnedBySystem);
        //Then it must stay on screen
        assertThat(row.mustStayOnScreen()).isTrue();
        // And when the user has seen it
        row.markHeadsUpSeen();
        // Then it should NOT stay on screen anymore
        assertThat(row.mustStayOnScreen()).isFalse();
    }

    @Test
    @DisableFlags(StatusBarNotifChips.FLAG_NAME)
    public void hasStatusBarChipDuringHeadsUpAnimation_flagOff_false() throws Exception {
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();

        assertRunnableLogsWtf(() -> row.setHasStatusBarChipDuringHeadsUpAnimation(true));

        assertThat(row.hasStatusBarChipDuringHeadsUpAnimation()).isFalse();
    }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    public void hasStatusBarChipDuringHeadsUpAnimation_flagOn_returnsValue() throws Exception {
        final ExpandableNotificationRow row = mNotificationTestHelper.createRow();

        assertThat(row.hasStatusBarChipDuringHeadsUpAnimation()).isFalse();

        row.setHasStatusBarChipDuringHeadsUpAnimation(true);
        assertThat(row.hasStatusBarChipDuringHeadsUpAnimation()).isTrue();

        row.setHasStatusBarChipDuringHeadsUpAnimation(false);
        assertThat(row.hasStatusBarChipDuringHeadsUpAnimation()).isFalse();
    }

    private void setDrawableIconsInImageView(CachingIconView icon, Drawable iconDrawable,
            Drawable rightIconDrawable) {
        ImageView iconView = mock(ImageView.class);
        when(icon.findViewById(com.android.internal.R.id.icon)).thenReturn(iconView);
        when(iconView.getDrawable()).thenReturn(iconDrawable);

        ImageView rightIconView = mock(ImageView.class);
        when(icon.findViewById(com.android.internal.R.id.right_icon)).thenReturn(rightIconView);
        when(rightIconView.getDrawable()).thenReturn(rightIconDrawable);
    }

    private NotificationChildrenContainer setMockChildrenContainer(
            ExpandableNotificationRow parentRow, ExpandableNotificationRow childRow) {
        List<ExpandableNotificationRow> rowList = Arrays.asList(childRow);
        NotificationChildrenContainer mockContainer = mock(NotificationChildrenContainer.class);
        when(mockContainer.getNotificationChildCount()).thenReturn(1);
        when(mockContainer.getAttachedChildren()).thenReturn(rowList);
        parentRow.setChildrenContainer(mockContainer);
        return mockContainer;
    }
}
