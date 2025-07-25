/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.app;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.swipeUp;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.internal.app.MatcherUtils.first;
import static com.android.internal.app.ResolverActivity.EXTRA_RESTRICT_TO_SINGLE_USER;
import static com.android.internal.app.ResolverDataProvider.createPackageManagerMockedInfo;
import static com.android.internal.app.ResolverWrapperActivity.sOverrides;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.fail;

import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.flag.junit.SetFlagsRule;
import android.text.TextUtils;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.test.InstrumentationRegistry;
import androidx.test.espresso.Espresso;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.android.internal.R;
import com.android.internal.app.ResolverActivity.ResolvedComponentInfo;
import com.android.internal.app.ResolverDataProvider.PackageManagerMockedInfo;
import com.android.internal.app.ResolverListAdapter.ActivityInfoPresentationGetter;
import com.android.internal.app.ResolverListAdapter.ResolveInfoPresentationGetter;
import com.android.internal.widget.ResolverDrawerLayout;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolver activity instrumentation tests
 */
@RunWith(AndroidJUnit4.class)
public class ResolverActivityTest {

    private static final UserHandle PERSONAL_USER_HANDLE = InstrumentationRegistry
            .getInstrumentation().getTargetContext().getUser();
    private static final int WORK_USER_ID = PERSONAL_USER_HANDLE.getIdentifier() + 1;
    private static final int CLONE_USER_ID = PERSONAL_USER_HANDLE.getIdentifier() + 2;
    private static final int PRIVATE_USER_ID = PERSONAL_USER_HANDLE.getIdentifier() + 3;

    @Rule
    public ActivityTestRule<ResolverWrapperActivity> mActivityRule =
            new ActivityTestRule<>(ResolverWrapperActivity.class, false,
                    false);
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Before
    public void cleanOverrideData() {
        sOverrides.reset();
    }

    @Test
    public void twoOptionsAndUserSelectsOne() throws InterruptedException {
        Intent sendIntent = createSendImageIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2,
                PERSONAL_USER_HANDLE);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        Espresso.registerIdlingResources(activity.getAdapter().getLabelIdlingResource());
        waitForIdle();

        assertThat(activity.getAdapter().getCount(), is(2));

        ResolveInfo[] chosen = new ResolveInfo[1];
        sOverrides.onSafelyStartInternalCallback = result -> {
            chosen[0] = result.first.getResolveInfo();
            return true;
        };

        ResolveInfo toChoose = resolvedComponentInfos.get(0).getResolveInfoAt(0);
        onView(withText(toChoose.activityInfo.name))
                .perform(click());
        onView(withId(R.id.button_once))
                .perform(click());
        waitForIdle();
        assertThat(chosen[0], is(toChoose));
    }

    @Ignore // Failing - b/144929805
    @Test
    public void setMaxHeight() throws Exception {
        Intent sendIntent = createSendImageIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2,
                PERSONAL_USER_HANDLE);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        waitForIdle();

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        final View viewPager = activity.findViewById(R.id.profile_pager);
        final int initialResolverHeight = viewPager.getHeight();

        activity.runOnUiThread(() -> {
            ResolverDrawerLayout layout = (ResolverDrawerLayout)
                    activity.findViewById(
                            R.id.contentPanel);
            ((ResolverDrawerLayout.LayoutParams) viewPager.getLayoutParams()).maxHeight
                = initialResolverHeight - 1;
            // Force a relayout
            layout.invalidate();
            layout.requestLayout();
        });
        waitForIdle();
        assertThat("Drawer should be capped at maxHeight",
                viewPager.getHeight() == (initialResolverHeight - 1));

        activity.runOnUiThread(() -> {
            ResolverDrawerLayout layout = (ResolverDrawerLayout)
                    activity.findViewById(
                            R.id.contentPanel);
            ((ResolverDrawerLayout.LayoutParams) viewPager.getLayoutParams()).maxHeight
                = initialResolverHeight + 1;
            // Force a relayout
            layout.invalidate();
            layout.requestLayout();
        });
        waitForIdle();
        assertThat("Drawer should not change height if its height is less than maxHeight",
                viewPager.getHeight() == initialResolverHeight);
    }

    @Ignore // Failing - b/144929805
    @Test
    public void setShowAtTopToTrue() throws Exception {
        Intent sendIntent = createSendImageIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2,
                PERSONAL_USER_HANDLE);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        waitForIdle();

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        final View viewPager = activity.findViewById(R.id.profile_pager);
        final View divider = activity.findViewById(R.id.divider);
        final RelativeLayout profileView =
            (RelativeLayout) activity.findViewById(R.id.profile_button).getParent();
        assertThat("Drawer should show at bottom by default",
                profileView.getBottom() + divider.getHeight() == viewPager.getTop()
                        && profileView.getTop() > 0);

        activity.runOnUiThread(() -> {
            ResolverDrawerLayout layout = (ResolverDrawerLayout)
                    activity.findViewById(
                            R.id.contentPanel);
            layout.setShowAtTop(true);
        });
        waitForIdle();
        assertThat("Drawer should show at top with new attribute",
            profileView.getBottom() + divider.getHeight() == viewPager.getTop()
                    && profileView.getTop() == 0);
    }

    @Test
    public void hasLastChosenActivity() throws Exception {
        Intent sendIntent = createSendImageIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos = createResolvedComponentsForTest(2,
                PERSONAL_USER_HANDLE);
        ResolveInfo toChoose = resolvedComponentInfos.get(0).getResolveInfoAt(0);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        when(sOverrides.resolverListController.getLastChosen())
                .thenReturn(resolvedComponentInfos.get(0).getResolveInfoAt(0));

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();

        // The other entry is filtered to the last used slot
        assertThat(activity.getAdapter().getCount(), is(1));
        assertThat(activity.getAdapter().getPlaceholderCount(), is(1));

        ResolveInfo[] chosen = new ResolveInfo[1];
        sOverrides.onSafelyStartInternalCallback = result -> {
            chosen[0] = result.first.getResolveInfo();
            return true;
        };

        onView(withId(R.id.button_once)).perform(click());
        waitForIdle();
        assertThat(chosen[0], is(toChoose));
    }

    @Test
    public void hasOtherProfileOneOption() throws Exception {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(2, WORK_USER_ID,
                        PERSONAL_USER_HANDLE);
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4,
                sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);

        ResolveInfo toChoose = personalResolvedComponentInfos.get(1).getResolveInfoAt(0);
        Intent sendIntent = createSendImageIntent();
        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        Espresso.registerIdlingResources(activity.getAdapter().getLabelIdlingResource());
        waitForIdle();

        // The other entry is filtered to the last used slot
        assertThat(activity.getAdapter().getCount(), is(1));

        ResolveInfo[] chosen = new ResolveInfo[1];
        sOverrides.onSafelyStartInternalCallback = result -> {
            chosen[0] = result.first.getResolveInfo();
            return true;
        };
        // Make a stable copy of the components as the original list may be modified
        List<ResolvedComponentInfo> stableCopy =
                createResolvedComponentsForTestWithOtherProfile(2, WORK_USER_ID,
                        PERSONAL_USER_HANDLE);
        // We pick the first one as there is another one in the work profile side
        onView(first(withText(stableCopy.get(1).getResolveInfoAt(0).activityInfo.name)))
                .perform(click());
        onView(withId(R.id.button_once))
                .perform(click());
        waitForIdle();
        assertThat(chosen[0], is(toChoose));
    }

    @Test
    public void hasOtherProfileTwoOptionsAndUserSelectsOne() throws Exception {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;

        Intent sendIntent = createSendImageIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, PERSONAL_USER_HANDLE);
        ResolveInfo toChoose = resolvedComponentInfos.get(1).getResolveInfoAt(0);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        Espresso.registerIdlingResources(activity.getAdapter().getLabelIdlingResource());
        waitForIdle();

        // The other entry is filtered to the other profile slot
        assertThat(activity.getAdapter().getCount(), is(2));

        ResolveInfo[] chosen = new ResolveInfo[1];
        sOverrides.onSafelyStartInternalCallback = result -> {
            chosen[0] = result.first.getResolveInfo();
            return true;
        };

        // Confirm that the button bar is disabled by default
        onView(withId(R.id.button_once)).check(matches(not(isEnabled())));

        // Make a stable copy of the components as the original list may be modified
        List<ResolvedComponentInfo> stableCopy =
                createResolvedComponentsForTestWithOtherProfile(2, PERSONAL_USER_HANDLE);

        onView(withText(stableCopy.get(1).getResolveInfoAt(0).activityInfo.name))
                .perform(click());
        onView(withId(R.id.button_once)).perform(click());
        waitForIdle();
        assertThat(chosen[0], is(toChoose));
    }


    @Test
    public void hasLastChosenActivityAndOtherProfile() throws Exception {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;

        // In this case we prefer the other profile and don't display anything about the last
        // chosen activity.
        Intent sendIntent = createSendImageIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, PERSONAL_USER_HANDLE);
        ResolveInfo toChoose = resolvedComponentInfos.get(1).getResolveInfoAt(0);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        when(sOverrides.resolverListController.getLastChosen())
                .thenReturn(resolvedComponentInfos.get(1).getResolveInfoAt(0));

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        Espresso.registerIdlingResources(activity.getAdapter().getLabelIdlingResource());
        waitForIdle();

        // The other entry is filtered to the other profile slot
        assertThat(activity.getAdapter().getCount(), is(2));

        ResolveInfo[] chosen = new ResolveInfo[1];
        sOverrides.onSafelyStartInternalCallback = result -> {
            chosen[0] = result.first.getResolveInfo();
            return true;
        };

        // Confirm that the button bar is disabled by default
        onView(withId(R.id.button_once)).check(matches(not(isEnabled())));

        // Make a stable copy of the components as the original list may be modified
        List<ResolvedComponentInfo> stableCopy =
                createResolvedComponentsForTestWithOtherProfile(2, PERSONAL_USER_HANDLE);

        onView(withText(stableCopy.get(1).getResolveInfoAt(0).activityInfo.name))
                .perform(click());
        onView(withId(R.id.button_once)).perform(click());
        waitForIdle();
        assertThat(chosen[0], is(toChoose));
    }

    @Test
    public void getActivityLabelAndSubLabel() throws Exception {
        ActivityInfoPresentationGetter pg;
        PackageManagerMockedInfo info;

        info = createPackageManagerMockedInfo(false);
        pg = new ActivityInfoPresentationGetter(
                info.ctx, 0, info.activityInfo);
        assertThat("Label should match app label", pg.getLabel().equals(
                info.setAppLabel));
        assertThat("Sublabel should match activity label if set",
                pg.getSubLabel().equals(info.setActivityLabel));

        info = createPackageManagerMockedInfo(true);
        pg = new ActivityInfoPresentationGetter(
                info.ctx, 0, info.activityInfo);
        assertThat("With override permission label should match activity label if set",
                pg.getLabel().equals(info.setActivityLabel));
        assertThat("With override permission sublabel should be empty",
                TextUtils.isEmpty(pg.getSubLabel()));
    }

    @Test
    public void getResolveInfoLabelAndSubLabel() throws Exception {
        ResolveInfoPresentationGetter pg;
        PackageManagerMockedInfo info;

        info = createPackageManagerMockedInfo(false);
        pg = new ResolveInfoPresentationGetter(
                info.ctx, 0, info.resolveInfo);
        assertThat("Label should match app label", pg.getLabel().equals(
                info.setAppLabel));
        assertThat("Sublabel should match resolve info label if set",
                pg.getSubLabel().equals(info.setResolveInfoLabel));

        info = createPackageManagerMockedInfo(true);
        pg = new ResolveInfoPresentationGetter(
                info.ctx, 0, info.resolveInfo);
        assertThat("With override permission label should match activity label if set",
                pg.getLabel().equals(info.setActivityLabel));
        assertThat("With override permission the sublabel should be the resolve info label",
                pg.getSubLabel().equals(info.setResolveInfoLabel));
    }

    @Test
    public void testWorkTab_displayedWhenWorkProfileUserAvailable() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        Intent sendIntent = createSendImageIntent();
        markWorkProfileUserAvailable();

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();

        onView(withId(R.id.tabs)).check(matches(isDisplayed()));
    }

    @Test
    public void testWorkTab_hiddenWhenWorkProfileUserNotAvailable() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        Intent sendIntent = createSendImageIntent();

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();

        onView(withId(R.id.tabs)).check(matches(not(isDisplayed())));
    }

    @Test
    public void testWorkTab_workTabListPopulatedBeforeGoingToTab() throws InterruptedException {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, WORK_USER_ID,
                        PERSONAL_USER_HANDLE);
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4,
                sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos,
                new ArrayList<>(workResolvedComponentInfos));
        Intent sendIntent = createSendImageIntent();

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();

        assertThat(activity.getCurrentUserHandle(), is(PERSONAL_USER_HANDLE));
        // The work list adapter must be populated in advance before tapping the other tab
        assertThat(activity.getWorkListAdapter().getCount(), is(4));
    }

    @Test
    public void testWorkTab_workTabUsesExpectedAdapter() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, WORK_USER_ID,
                        PERSONAL_USER_HANDLE);
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4,
                sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(withText(R.string.resolver_work_tab)).perform(click());

        assertThat(activity.getCurrentUserHandle().getIdentifier(), is(WORK_USER_ID));
        assertThat(activity.getWorkListAdapter().getCount(), is(4));
    }

    @Test
    public void testWorkTab_personalTabUsesExpectedAdapter() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, PERSONAL_USER_HANDLE);
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4,
                sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(withText(R.string.resolver_work_tab)).perform(click());

        assertThat(activity.getCurrentUserHandle().getIdentifier(), is(WORK_USER_ID));
        assertThat(activity.getPersonalListAdapter().getCount(), is(2));
    }

    @Test
    public void testWorkTab_workProfileHasExpectedNumberOfTargets() throws InterruptedException {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, WORK_USER_ID,
                        PERSONAL_USER_HANDLE);
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4,
                sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();

        onView(withText(R.string.resolver_work_tab))
                .perform(click());
        waitForIdle();
        assertThat(activity.getWorkListAdapter().getCount(), is(4));
    }

    @Test
    public void testWorkTab_selectingWorkTabAppOpensAppInWorkProfile() throws InterruptedException {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, WORK_USER_ID,
                        PERSONAL_USER_HANDLE);
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4,
                sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();
        ResolveInfo[] chosen = new ResolveInfo[1];
        sOverrides.onSafelyStartInternalCallback = result -> {
            chosen[0] = result.first.getResolveInfo();
            return true;
        };

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(withText(R.string.resolver_work_tab))
                .perform(click());
        waitForIdle();
        onView(first(allOf(withText(workResolvedComponentInfos.get(0)
                .getResolveInfoAt(0).activityInfo.applicationInfo.name), isCompletelyDisplayed())))
                .perform(click());
        onView(withId(R.id.button_once))
                .perform(click());

        waitForIdle();
        assertThat(chosen[0], is(workResolvedComponentInfos.get(0).getResolveInfoAt(0)));
    }

    @Test
    public void testWorkTab_noPersonalApps_workTabHasExpectedNumberOfTargets()
            throws InterruptedException {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(1, PERSONAL_USER_HANDLE);
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4,
                sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(withText(R.string.resolver_work_tab))
                .perform(click());

        waitForIdle();
        assertThat(activity.getWorkListAdapter().getCount(), is(4));
    }

    @Test
    public void testWorkTab_headerIsVisibleInPersonalTab() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(1, PERSONAL_USER_HANDLE);
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4,
                sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createOpenWebsiteIntent();

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        TextView headerText = activity.findViewById(R.id.title);
        String initialText = headerText.getText().toString();
        assertFalse(initialText.isEmpty(), "Header text is empty.");
        assertThat(headerText.getVisibility(), is(View.VISIBLE));
    }

    @Test
    public void testWorkTab_switchTabs_headerStaysSame() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(1, PERSONAL_USER_HANDLE);
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4,
                sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createOpenWebsiteIntent();

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        TextView headerText = activity.findViewById(R.id.title);
        String initialText = headerText.getText().toString();
        onView(withText(R.string.resolver_work_tab))
                .perform(click());

        waitForIdle();
        String currentText = headerText.getText().toString();
        assertThat(headerText.getVisibility(), is(View.VISIBLE));
        assertThat(String.format("Header text is not the same when switching tabs, personal profile"
                        + " header was %s but work profile header is %s", initialText, currentText),
                TextUtils.equals(initialText, currentText));
    }

    @Test
    public void testWorkTab_noPersonalApps_canStartWorkApps()
            throws InterruptedException {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, WORK_USER_ID,
                        PERSONAL_USER_HANDLE);
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4,
                sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();
        ResolveInfo[] chosen = new ResolveInfo[1];
        sOverrides.onSafelyStartInternalCallback = result -> {
            chosen[0] = result.first.getResolveInfo();
            return true;
        };

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(withText(R.string.resolver_work_tab))
                .perform(click());
        waitForIdle();
        onView(first(allOf(
                withText(workResolvedComponentInfos.get(0)
                        .getResolveInfoAt(0).activityInfo.applicationInfo.name),
                isDisplayed())))
                .perform(click());
        onView(withId(R.id.button_once))
                .perform(click());
        waitForIdle();

        assertThat(chosen[0], is(workResolvedComponentInfos.get(0).getResolveInfoAt(0)));
    }

    @Test
    public void testWorkTab_crossProfileIntentsDisabled_personalToWork_emptyStateShown() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        int workProfileTargets = 4;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, WORK_USER_ID,
                        PERSONAL_USER_HANDLE);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(workProfileTargets,
                        sOverrides.workProfileUserHandle);
        sOverrides.hasCrossProfileIntents = false;
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();
        sendIntent.setType("TestType");

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(withText(R.string.resolver_work_tab)).perform(click());
        waitForIdle();
        onView(withId(R.id.contentPanel))
                .perform(swipeUp());

        onView(withText(R.string.resolver_cross_profile_blocked))
                .check(matches(isDisplayed()));
    }

    @Test
    public void testWorkTab_workProfileDisabled_emptyStateShown() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        int workProfileTargets = 4;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, WORK_USER_ID,
                        PERSONAL_USER_HANDLE);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(workProfileTargets,
                        sOverrides.workProfileUserHandle);
        sOverrides.isQuietModeEnabled = true;
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();
        sendIntent.setType("TestType");

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(withId(R.id.contentPanel))
                .perform(swipeUp());
        onView(withText(R.string.resolver_work_tab)).perform(click());
        waitForIdle();

        onView(withText(R.string.resolver_turn_on_work_apps))
                .check(matches(isDisplayed()));
    }

    @Test
    public void testWorkTab_noWorkAppsAvailable_emptyStateShown() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTest(3, PERSONAL_USER_HANDLE);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(0, sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();
        sendIntent.setType("TestType");

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(withId(R.id.contentPanel))
                .perform(swipeUp());
        onView(withText(R.string.resolver_work_tab)).perform(click());
        waitForIdle();

        onView(withText(R.string.resolver_no_work_apps_available))
                .check(matches(isDisplayed()));
    }

    @Test
    public void testWorkTab_xProfileOff_noAppsAvailable_workOff_xProfileOffEmptyStateShown() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTest(3, PERSONAL_USER_HANDLE);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(0, sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();
        sendIntent.setType("TestType");
        sOverrides.isQuietModeEnabled = true;
        sOverrides.hasCrossProfileIntents = false;

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(withId(R.id.contentPanel))
                .perform(swipeUp());
        onView(withText(R.string.resolver_work_tab)).perform(click());
        waitForIdle();

        onView(withText(R.string.resolver_cross_profile_blocked))
                .check(matches(isDisplayed()));
    }

    @Test
    public void testMiniResolver() {
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTest(1, PERSONAL_USER_HANDLE);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(1, sOverrides.workProfileUserHandle);
        // Personal profile only has a browser
        personalResolvedComponentInfos.get(0).getResolveInfoAt(0).handleAllWebDataURI = true;
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();
        sendIntent.setType("TestType");

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(withId(R.id.open_cross_profile)).check(matches(isDisplayed()));
    }

    @Test
    public void testMiniResolver_noCurrentProfileTarget() {
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTest(0, PERSONAL_USER_HANDLE);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(1, sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();
        sendIntent.setType("TestType");

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();

        // Need to ensure mini resolver doesn't trigger here.
        assertNotMiniResolver();
    }

    private void assertNotMiniResolver() {
        try {
            onView(withId(R.id.open_cross_profile)).check(matches(isDisplayed()));
        } catch (NoMatchingViewException e) {
            return;
        }
        fail("Mini resolver present but shouldn't be");
    }

    @Test
    public void testWorkTab_noAppsAvailable_workOff_noAppsAvailableEmptyStateShown() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTest(3, PERSONAL_USER_HANDLE);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(0, sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();
        sendIntent.setType("TestType");
        sOverrides.isQuietModeEnabled = true;

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(withId(R.id.contentPanel))
                .perform(swipeUp());
        onView(withText(R.string.resolver_work_tab)).perform(click());
        waitForIdle();

        onView(withText(R.string.resolver_no_work_apps_available))
                .check(matches(isDisplayed()));
    }

    @Test
    public void testAutolaunch_singleTarget_withWorkProfileAndTabbedViewOff_noAutolaunch() {
        ResolverActivity.ENABLE_TABBED_VIEW = false;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(2, WORK_USER_ID,
                        PERSONAL_USER_HANDLE);
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class)))
                .thenReturn(new ArrayList<>(personalResolvedComponentInfos));
        Intent sendIntent = createSendImageIntent();
        sendIntent.setType("TestType");
        ResolveInfo[] chosen = new ResolveInfo[1];
        sOverrides.onSafelyStartInternalCallback = result -> {
            chosen[0] = result.first.getResolveInfo();
            return true;
        };
        waitForIdle();

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();

        assertTrue(chosen[0] == null);
    }

    @Test
    public void testAutolaunch_singleTarget_noWorkProfile_autolaunch() {
        ResolverActivity.ENABLE_TABBED_VIEW = false;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTest(1, PERSONAL_USER_HANDLE);
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class)))
                .thenReturn(new ArrayList<>(personalResolvedComponentInfos));
        Intent sendIntent = createSendImageIntent();
        sendIntent.setType("TestType");
        ResolveInfo[] chosen = new ResolveInfo[1];
        sOverrides.onSafelyStartInternalCallback = result -> {
            chosen[0] = result.first.getResolveInfo();
            return true;
        };
        waitForIdle();

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();

        assertThat(chosen[0], is(personalResolvedComponentInfos.get(0).getResolveInfoAt(0)));
    }

    @Test
    public void testWorkTab_onePersonalTarget_emptyStateOnWorkTarget_autolaunch() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        int workProfileTargets = 4;
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(2, WORK_USER_ID,
                        PERSONAL_USER_HANDLE);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(workProfileTargets,
                        sOverrides.workProfileUserHandle);
        sOverrides.hasCrossProfileIntents = false;
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();
        sendIntent.setType("TestType");
        ResolveInfo[] chosen = new ResolveInfo[1];
        sOverrides.onSafelyStartInternalCallback = result -> {
            chosen[0] = result.first.getResolveInfo();
            return true;
        };

        mActivityRule.launchActivity(sendIntent);
        waitForIdle();

        assertThat(chosen[0], is(personalResolvedComponentInfos.get(1).getResolveInfoAt(0)));
    }

    @Test
    public void testLayoutWithDefault_withWorkTab_neverShown() throws RemoteException {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();

        // In this case we prefer the other profile and don't display anything about the last
        // chosen activity.
        Intent sendIntent = createSendImageIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos =
                createResolvedComponentsForTest(2, PERSONAL_USER_HANDLE);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        when(sOverrides.resolverListController.getLastChosen())
                .thenReturn(resolvedComponentInfos.get(1).getResolveInfoAt(0));

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        Espresso.registerIdlingResources(activity.getAdapter().getLabelIdlingResource());
        waitForIdle();

        // The other entry is filtered to the last used slot
        assertThat(activity.getAdapter().hasFilteredItem(), is(false));
        assertThat(activity.getAdapter().getCount(), is(2));
        assertThat(activity.getAdapter().getPlaceholderCount(), is(2));
    }

    @Test
    public void testClonedProfilePresent_personalAdapterIsSetWithPersonalProfile() {
        // enable cloneProfile
        markCloneProfileUserAvailable();
        List<ResolvedComponentInfo> resolvedComponentInfos =
                createResolvedComponentsWithCloneProfileForTest(
                        3,
                        PERSONAL_USER_HANDLE,
                        sOverrides.cloneProfileUserHandle);
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();

        assertThat(activity.getCurrentUserHandle(), is(activity.getPersonalProfileUserHandle()));
        assertThat(activity.getAdapter().getCount(), is(3));
    }

    @Test
    public void testClonedProfilePresent_personalTabUsesExpectedAdapter() {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        // enable cloneProfile
        markCloneProfileUserAvailable();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsWithCloneProfileForTest(
                        3,
                        PERSONAL_USER_HANDLE,
                        sOverrides.cloneProfileUserHandle);
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4,
                sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();

        assertThat(activity.getCurrentUserHandle(), is(activity.getPersonalProfileUserHandle()));
        assertThat(activity.getAdapter().getCount(), is(3));
    }

    @Test
    public void testClonedProfilePresent_layoutWithDefault_neverShown() throws Exception {
        // enable cloneProfile
        markCloneProfileUserAvailable();
        Intent sendIntent = createSendImageIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos =
                createResolvedComponentsWithCloneProfileForTest(
                        2,
                PERSONAL_USER_HANDLE,
                        sOverrides.cloneProfileUserHandle);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        when(sOverrides.resolverListController.getLastChosen())
                .thenReturn(resolvedComponentInfos.get(0).getResolveInfoAt(0));

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        Espresso.registerIdlingResources(activity.getAdapter().getLabelIdlingResource());
        waitForIdle();

        assertThat(activity.getAdapter().hasFilteredItem(), is(false));
        assertThat(activity.getAdapter().getCount(), is(2));
        assertThat(activity.getAdapter().getPlaceholderCount(), is(2));
    }

    @Test
    public void testClonedProfilePresent_alwaysButtonDisabled() throws Exception {
        // enable cloneProfile
        markCloneProfileUserAvailable();
        Intent sendIntent = createSendImageIntent();
        List<ResolvedComponentInfo> resolvedComponentInfos =
                createResolvedComponentsWithCloneProfileForTest(
                        3,
                        PERSONAL_USER_HANDLE,
                        sOverrides.cloneProfileUserHandle);

        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        when(sOverrides.resolverListController.getLastChosen())
                .thenReturn(resolvedComponentInfos.get(0).getResolveInfoAt(0));

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();

        // Confirm that the button bar is disabled by default
        onView(withId(R.id.button_once)).check(matches(not(isEnabled())));
        onView(withId(R.id.button_always)).check(matches(not(isEnabled())));

        // Make a stable copy of the components as the original list may be modified
        List<ResolvedComponentInfo> stableCopy =
                createResolvedComponentsForTestWithOtherProfile(2, PERSONAL_USER_HANDLE);

        onView(withText(stableCopy.get(1).getResolveInfoAt(0).activityInfo.name))
                .perform(click());

        onView(withId(R.id.button_once)).check(matches(isEnabled()));
        onView(withId(R.id.button_always)).check(matches(not(isEnabled())));
    }

    @Test
    public void testClonedProfilePresent_personalProfileActivityIsStartedInCorrectUser()
            throws Exception {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        // enable cloneProfile
        markCloneProfileUserAvailable();

        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsWithCloneProfileForTest(
                        3,
                        PERSONAL_USER_HANDLE,
                        sOverrides.cloneProfileUserHandle);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(3, sOverrides.workProfileUserHandle);
        sOverrides.hasCrossProfileIntents = false;
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();
        sendIntent.setType("TestType");
        final UserHandle[] selectedActivityUserHandle = new UserHandle[1];
        sOverrides.onSafelyStartInternalCallback = result -> {
            selectedActivityUserHandle[0] = result.second;
            return true;
        };

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(first(allOf(withText(personalResolvedComponentInfos.get(0)
                .getResolveInfoAt(0).activityInfo.applicationInfo.name), isCompletelyDisplayed())))
                .perform(click());
        onView(withId(R.id.button_once))
                .perform(click());
        waitForIdle();

        assertThat(selectedActivityUserHandle[0], is(activity.getAdapter().getUserHandle()));
    }

    @Test
    public void testClonedProfilePresent_workProfileActivityIsStartedInCorrectUser()
            throws Exception {
        // enable the work tab feature flag
        ResolverActivity.ENABLE_TABBED_VIEW = true;
        markWorkProfileUserAvailable();
        // enable cloneProfile
        markCloneProfileUserAvailable();

        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsWithCloneProfileForTest(
                        3,
                        PERSONAL_USER_HANDLE,
                        sOverrides.cloneProfileUserHandle);
        List<ResolvedComponentInfo> workResolvedComponentInfos =
                createResolvedComponentsForTest(3, sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();
        sendIntent.setType("TestType");
        final UserHandle[] selectedActivityUserHandle = new UserHandle[1];
        sOverrides.onSafelyStartInternalCallback = result -> {
            selectedActivityUserHandle[0] = result.second;
            return true;
        };

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(withText(R.string.resolver_work_tab))
                .perform(click());
        waitForIdle();
        onView(first(allOf(withText(workResolvedComponentInfos.get(0)
                .getResolveInfoAt(0).activityInfo.applicationInfo.name), isCompletelyDisplayed())))
                .perform(click());
        onView(withId(R.id.button_once))
                .perform(click());
        waitForIdle();

        assertThat(selectedActivityUserHandle[0], is(activity.getAdapter().getUserHandle()));
    }

    @Test
    public void testClonedProfilePresent_personalProfileResolverComparatorHasCorrectUsers()
            throws Exception {
        // enable cloneProfile
        markCloneProfileUserAvailable();
        List<ResolvedComponentInfo> resolvedComponentInfos =
                createResolvedComponentsWithCloneProfileForTest(
                        3,
                        PERSONAL_USER_HANDLE,
                        sOverrides.cloneProfileUserHandle);
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(resolvedComponentInfos);
        Intent sendIntent = createSendImageIntent();

        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        List<UserHandle> result = activity
                .getResolverRankerServiceUserHandleList(PERSONAL_USER_HANDLE);

        assertTrue(result.containsAll(Lists.newArrayList(PERSONAL_USER_HANDLE,
                sOverrides.cloneProfileUserHandle)));
    }

    @Test
    public void testTriggerFromPrivateProfile_withoutWorkProfile() throws RemoteException {
        mSetFlagsRule.enableFlags(android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE,
                android.multiuser.Flags.FLAG_ALLOW_RESOLVER_SHEET_FOR_PRIVATE_SPACE,
                android.multiuser.Flags.FLAG_ENABLE_PRIVATE_SPACE_FEATURES);
        markPrivateProfileUserAvailable();
        Intent sendIntent = createSendImageIntent();
        List<ResolvedComponentInfo> privateResolvedComponentInfos =
                createResolvedComponentsForTest(3, sOverrides.privateProfileUserHandle);
        setupResolverControllers(privateResolvedComponentInfos);
        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        onView(withId(R.id.tabs)).check(matches(not(isDisplayed())));
        assertThat(activity.getPersonalListAdapter().getCount(), is(3));
        onView(withId(R.id.button_once)).check(matches(not(isEnabled())));
        onView(withId(R.id.button_always)).check(matches(not(isEnabled())));
        for (ResolvedComponentInfo resolvedInfo : privateResolvedComponentInfos) {
            assertEquals(resolvedInfo.getResolveInfoAt(0).userHandle,
                    sOverrides.privateProfileUserHandle);
        }
    }

    @Test
    public void testTriggerFromPrivateProfile_withWorkProfilePresent(){
        mSetFlagsRule.enableFlags(android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE,
                android.multiuser.Flags.FLAG_ALLOW_RESOLVER_SHEET_FOR_PRIVATE_SPACE,
                android.multiuser.Flags.FLAG_ENABLE_PRIVATE_SPACE_FEATURES);
        ResolverActivity.ENABLE_TABBED_VIEW = false;
        markPrivateProfileUserAvailable();
        markWorkProfileUserAvailable();
        Intent sendIntent = createSendImageIntent();
        List<ResolvedComponentInfo> privateResolvedComponentInfos =
                createResolvedComponentsForTest(3, sOverrides.privateProfileUserHandle);
        setupResolverControllers(privateResolvedComponentInfos);
        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        assertThat(activity.getPersonalListAdapter().getCount(), is(3));
        onView(withId(R.id.tabs)).check(matches(not(isDisplayed())));
        assertEquals(activity.getMultiProfilePagerAdapterCount(), 1);
        for (ResolvedComponentInfo resolvedInfo : privateResolvedComponentInfos) {
            assertEquals(resolvedInfo.getResolveInfoAt(0).userHandle,
                    sOverrides.privateProfileUserHandle);
        }
    }

    @Test
    public void testPrivateProfile_triggerFromPrimaryUser_withWorkProfilePresent(){
        mSetFlagsRule.enableFlags(android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE,
                android.multiuser.Flags.FLAG_ALLOW_RESOLVER_SHEET_FOR_PRIVATE_SPACE,
                android.multiuser.Flags.FLAG_ENABLE_PRIVATE_SPACE_FEATURES);
        markPrivateProfileUserAvailable();
        markWorkProfileUserAvailable();
        Intent sendIntent = createSendImageIntent();
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, PERSONAL_USER_HANDLE);
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4,
                sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        assertThat(activity.getAdapter().getCount(), is(2));
        assertThat(activity.getWorkListAdapter().getCount(), is(4));
        onView(withId(R.id.tabs)).check(matches(isDisplayed()));
        for (ResolvedComponentInfo resolvedInfo : personalResolvedComponentInfos) {
            assertEquals(resolvedInfo.getResolveInfoAt(0).userHandle,
                    activity.getPersonalProfileUserHandle());
        }
    }

    @Test
    public void testPrivateProfile_triggerFromWorkProfile(){
        mSetFlagsRule.enableFlags(android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE,
                android.multiuser.Flags.FLAG_ALLOW_RESOLVER_SHEET_FOR_PRIVATE_SPACE,
                android.multiuser.Flags.FLAG_ENABLE_PRIVATE_SPACE_FEATURES);
        markPrivateProfileUserAvailable();
        markWorkProfileUserAvailable();
        Intent sendIntent = createSendImageIntent();

        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, PERSONAL_USER_HANDLE);
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4,
                sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        assertThat(activity.getAdapter().getCount(), is(2));
        assertThat(activity.getWorkListAdapter().getCount(), is(4));
        onView(withId(R.id.tabs)).check(matches(isDisplayed()));
        for (ResolvedComponentInfo resolvedInfo : personalResolvedComponentInfos) {
            assertTrue(resolvedInfo.getResolveInfoAt(0).userHandle.equals(
                    activity.getPersonalProfileUserHandle()) || resolvedInfo.getResolveInfoAt(
                    0).userHandle.equals(activity.getWorkProfileUserHandle()));
        }
    }

    @Test
    public void testTriggerFromMainProfile_inSingleUserMode_withWorkProfilePresent() {
        mSetFlagsRule.enableFlags(android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE,
                android.multiuser.Flags.FLAG_ALLOW_RESOLVER_SHEET_FOR_PRIVATE_SPACE,
                android.multiuser.Flags.FLAG_ENABLE_PRIVATE_SPACE_FEATURES);
        markWorkProfileUserAvailable();
        setTabOwnerUserHandleForLaunch(PERSONAL_USER_HANDLE);
        Intent sendIntent = createSendImageIntent();
        sendIntent.putExtra(EXTRA_RESTRICT_TO_SINGLE_USER, true);
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTestWithOtherProfile(3, PERSONAL_USER_HANDLE);
        List<ResolvedComponentInfo> workResolvedComponentInfos = createResolvedComponentsForTest(4,
                sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos, workResolvedComponentInfos);
        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        assertThat(activity.getPersonalListAdapter().getCount(), is(2));
        onView(withId(R.id.tabs)).check(matches(not(isDisplayed())));
        assertEquals(activity.getMultiProfilePagerAdapterCount(), 1);
        for (ResolvedComponentInfo resolvedInfo : personalResolvedComponentInfos) {
            assertEquals(resolvedInfo.getResolveInfoAt(0).userHandle, PERSONAL_USER_HANDLE);
        }
    }

    @Test
    public void testTriggerFromWorkProfile_inSingleUserMode() {
        mSetFlagsRule.enableFlags(android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE,
                android.multiuser.Flags.FLAG_ALLOW_RESOLVER_SHEET_FOR_PRIVATE_SPACE,
                android.multiuser.Flags.FLAG_ENABLE_PRIVATE_SPACE_FEATURES);
        markWorkProfileUserAvailable();
        setTabOwnerUserHandleForLaunch(sOverrides.workProfileUserHandle);
        Intent sendIntent = createSendImageIntent();
        sendIntent.putExtra(EXTRA_RESTRICT_TO_SINGLE_USER, true);
        List<ResolvedComponentInfo> personalResolvedComponentInfos =
                createResolvedComponentsForTest(3, sOverrides.workProfileUserHandle);
        setupResolverControllers(personalResolvedComponentInfos);
        final ResolverWrapperActivity activity = mActivityRule.launchActivity(sendIntent);
        waitForIdle();
        assertThat(activity.getPersonalListAdapter().getCount(), is(3));
        onView(withId(R.id.tabs)).check(matches(not(isDisplayed())));
        assertEquals(activity.getMultiProfilePagerAdapterCount(), 1);
        for (ResolvedComponentInfo resolvedInfo : personalResolvedComponentInfos) {
            assertEquals(resolvedInfo.getResolveInfoAt(0).userHandle,
                    sOverrides.workProfileUserHandle);
        }
    }

    private Intent createSendImageIntent() {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, "testing intent sending");
        sendIntent.setType("image/jpeg");
        return sendIntent;
    }

    private Intent createOpenWebsiteIntent() {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_VIEW);
        sendIntent.setData(Uri.parse("https://google.com"));
        return sendIntent;
    }

    private List<ResolvedComponentInfo> createResolvedComponentsForTest(int numberOfResults,
            UserHandle resolvedForUser) {
        List<ResolvedComponentInfo> infoList = new ArrayList<>(numberOfResults);
        for (int i = 0; i < numberOfResults; i++) {
            infoList.add(ResolverDataProvider.createResolvedComponentInfo(i, resolvedForUser));
        }
        return infoList;
    }

    private List<ResolvedComponentInfo> createResolvedComponentsWithCloneProfileForTest(
            int numberOfResults,
            UserHandle resolvedForPersonalUser,
            UserHandle resolvedForClonedUser) {
        List<ResolvedComponentInfo> infoList = new ArrayList<>(numberOfResults);
        for (int i = 0; i < 1; i++) {
            infoList.add(ResolverDataProvider.createResolvedComponentInfo(i,
                    resolvedForPersonalUser));
        }
        for (int i = 1; i < numberOfResults; i++) {
            infoList.add(ResolverDataProvider.createResolvedComponentInfo(i,
                    resolvedForClonedUser));
        }
        return infoList;
    }

    private List<ResolvedComponentInfo> createResolvedComponentsForTestWithOtherProfile(
            int numberOfResults, UserHandle resolvedForUser) {
        List<ResolvedComponentInfo> infoList = new ArrayList<>(numberOfResults);
        for (int i = 0; i < numberOfResults; i++) {
            if (i == 0) {
                infoList.add(ResolverDataProvider.createResolvedComponentInfoWithOtherId(i,
                        resolvedForUser));
            } else {
                infoList.add(ResolverDataProvider.createResolvedComponentInfo(i, resolvedForUser));
            }
        }
        return infoList;
    }

    private List<ResolvedComponentInfo> createResolvedComponentsForTestWithOtherProfile(
            int numberOfResults, int userId, UserHandle resolvedForUser) {
        List<ResolvedComponentInfo> infoList = new ArrayList<>(numberOfResults);
        for (int i = 0; i < numberOfResults; i++) {
            if (i == 0) {
                infoList.add(
                        ResolverDataProvider.createResolvedComponentInfoWithOtherId(i, userId,
                                resolvedForUser));
            } else {
                infoList.add(ResolverDataProvider.createResolvedComponentInfo(i, resolvedForUser));
            }
        }
        return infoList;
    }

    private void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void markWorkProfileUserAvailable() {
        ResolverWrapperActivity.sOverrides.workProfileUserHandle = UserHandle.of(WORK_USER_ID);
    }

    private void markCloneProfileUserAvailable() {
        ResolverWrapperActivity.sOverrides.cloneProfileUserHandle = UserHandle.of(CLONE_USER_ID);
    }

    private void markPrivateProfileUserAvailable() {
        ResolverWrapperActivity.sOverrides.privateProfileUserHandle =
                UserHandle.of(PRIVATE_USER_ID);
    }

    private void setTabOwnerUserHandleForLaunch(UserHandle tabOwnerUserHandleForLaunch) {
        sOverrides.tabOwnerUserHandleForLaunch = tabOwnerUserHandleForLaunch;
    }

    private void setupResolverControllers(
            List<ResolvedComponentInfo> personalResolvedComponentInfos,
            List<ResolvedComponentInfo> workResolvedComponentInfos) {
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class)))
                .thenReturn(new ArrayList<>(personalResolvedComponentInfos));
        when(sOverrides.workResolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class))).thenReturn(workResolvedComponentInfos);
        when(sOverrides.workResolverListController.getResolversForIntentAsUser(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class),
                eq(UserHandle.SYSTEM)))
                .thenReturn(new ArrayList<>(personalResolvedComponentInfos));
    }

    private void setupResolverControllers(
            List<ResolvedComponentInfo> resolvedComponentInfos) {
        when(sOverrides.resolverListController.getResolversForIntent(Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.anyBoolean(),
                Mockito.isA(List.class)))
                .thenReturn(new ArrayList<>(resolvedComponentInfos));
    }
}
