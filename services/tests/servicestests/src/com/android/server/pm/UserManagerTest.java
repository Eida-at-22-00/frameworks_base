/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.pm;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.pm.UserProperties;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.IpcDataCache;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Postsubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Slog;

import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Test {@link UserManager} functionality.
 *
 *  atest com.android.server.pm.UserManagerTest
 */
@Postsubmit
@RunWith(AndroidJUnit4.class)
public final class UserManagerTest {
    // Taken from UserManagerService
    private static final long EPOCH_PLUS_30_YEARS = 30L * 365 * 24 * 60 * 60 * 1000L; // 30 years

    private static final int SWITCH_USER_TIMEOUT_SECONDS = 180; // 180 seconds
    private static final int REMOVE_USER_TIMEOUT_SECONDS = 180; // 180 seconds

    // Packages which are used during tests.
    private static final String[] PACKAGES = new String[] {
            "com.android.egg",
            "com.google.android.webview"
    };
    private static final String TAG = UserManagerTest.class.getSimpleName();

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    private UserManager mUserManager = null;
    private ActivityManager mActivityManager;
    private PackageManager mPackageManager;
    private ArraySet<Integer> mUsersToRemove;
    private UserSwitchWaiter mUserSwitchWaiter;
    private UserRemovalWaiter mUserRemovalWaiter;
    private int mOriginalCurrentUserId;

    @Before
    public void setUp() throws Exception {
        // Disable binder caches in this process.
        IpcDataCache.disableForTestMode();

        mOriginalCurrentUserId = ActivityManager.getCurrentUser();
        mUserManager = UserManager.get(mContext);
        mActivityManager = mContext.getSystemService(ActivityManager.class);
        mPackageManager = mContext.getPackageManager();
        mUserSwitchWaiter = new UserSwitchWaiter(TAG, SWITCH_USER_TIMEOUT_SECONDS);
        mUserRemovalWaiter = new UserRemovalWaiter(mContext, TAG, REMOVE_USER_TIMEOUT_SECONDS);

        mUsersToRemove = new ArraySet<>();
        removeExistingUsers();
    }

    @After
    public void tearDown() throws Exception {
        if (mOriginalCurrentUserId != ActivityManager.getCurrentUser()) {
            switchUser(mOriginalCurrentUserId);
        }
        mUserSwitchWaiter.close();

        // Making a copy of mUsersToRemove to avoid ConcurrentModificationException
        mUsersToRemove.stream().toList().forEach(this::removeUser);
        mUserRemovalWaiter.close();

        mUserManager.setUserRestriction(UserManager.DISALLOW_GRANT_ADMIN, false,
                mContext.getUser());
    }

    private void removeExistingUsers() {
        int currentUser = ActivityManager.getCurrentUser();

        UserHandle communalProfile = mUserManager.getCommunalProfile();
        int communalProfileId = communalProfile != null
                ? communalProfile.getIdentifier() : UserHandle.USER_NULL;

        List<UserInfo> list = mUserManager.getUsers();
        for (UserInfo user : list) {
            // Keep system and current user
            if (user.id != UserHandle.USER_SYSTEM &&
                    user.id != currentUser &&
                    user.id != communalProfileId &&
                    !user.isMain()) {
                removeUser(user.id);
            }
        }
    }

    @SmallTest
    @Test
    public void testHasSystemUser() throws Exception {
        assertThat(hasUser(UserHandle.USER_SYSTEM)).isTrue();
    }

    @MediumTest
    @Test
    public void testAddGuest() throws Exception {
        UserInfo userInfo = createUser("Guest 1", UserInfo.FLAG_GUEST);
        assertThat(userInfo).isNotNull();

        List<UserInfo> list = mUserManager.getUsers();
        for (UserInfo user : list) {
            if (user.id == userInfo.id && user.name.equals("Guest 1")
                    && user.isGuest()
                    && !user.isAdmin()
                    && !user.isPrimary()) {
                return;
            }
        }
        fail("Didn't find a guest: " + list);
    }

    @Test
    public void testCloneUser() throws Exception {
        assumeCloneEnabled();
        UserHandle mainUser = mUserManager.getMainUser();
        assumeTrue("Main user is null", mainUser != null);
        // Get the default properties for clone user type.
        final UserTypeDetails userTypeDetails =
                UserTypeFactory.getUserTypes().get(UserManager.USER_TYPE_PROFILE_CLONE);
        assertWithMessage("No %s type on device", UserManager.USER_TYPE_PROFILE_CLONE)
                .that(userTypeDetails).isNotNull();
        final UserProperties typeProps = userTypeDetails.getDefaultUserPropertiesReference();

        // Test that only one clone user can be created
        final int mainUserId = mainUser.getIdentifier();
        UserInfo cloneProfileUser = createProfileForUser("Clone user1",
                UserManager.USER_TYPE_PROFILE_CLONE,
                mainUserId);
        assertThat(cloneProfileUser).isNotNull();
        UserInfo cloneProfileUser2 = createProfileForUser("Clone user2",
                UserManager.USER_TYPE_PROFILE_CLONE,
                mainUserId);
        assertThat(cloneProfileUser2).isNull();

        final Context profileUserContest = mContext.createPackageContextAsUser("system", 0,
                UserHandle.of(cloneProfileUser.id));
        final UserManager profileUM = UserManager.get(profileUserContest);
        assertThat(profileUserContest.getSystemService(
                UserManager.class).isMediaSharedWithParent()).isTrue();
        assertThat(Settings.Secure.getInt(profileUserContest.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0)).isEqualTo(1);

        List<UserInfo> list = mUserManager.getUsers();
        List<UserInfo> cloneUsers = list.stream().filter(
                user -> (user.id == cloneProfileUser.id && user.name.equals("Clone user1")
                        && user.isCloneProfile()))
                .collect(Collectors.toList());
        assertThat(cloneUsers.size()).isEqualTo(1);

        // Check that the new clone user has the expected properties (relative to the defaults)
        // provided that the test caller has the necessary permissions.
        UserProperties cloneUserProperties =
                mUserManager.getUserProperties(UserHandle.of(cloneProfileUser.id));
        assertThat(typeProps.getUseParentsContacts())
                .isEqualTo(cloneUserProperties.getUseParentsContacts());
        assertThat(typeProps.getShowInLauncher())
                .isEqualTo(cloneUserProperties.getShowInLauncher());
        assertThrows(SecurityException.class, cloneUserProperties::getStartWithParent);
        assertThrows(SecurityException.class,
                cloneUserProperties::getCrossProfileIntentFilterAccessControl);
        assertThrows(SecurityException.class,
                cloneUserProperties::getCrossProfileIntentResolutionStrategy);
        assertThat(typeProps.isMediaSharedWithParent())
                .isEqualTo(cloneUserProperties.isMediaSharedWithParent());
        assertThat(typeProps.isCredentialShareableWithParent())
                .isEqualTo(cloneUserProperties.isCredentialShareableWithParent());
        assertThat(typeProps.getCrossProfileContentSharingStrategy())
                .isEqualTo(cloneUserProperties.getCrossProfileContentSharingStrategy());
        assertThrows(SecurityException.class, cloneUserProperties::getDeleteAppWithParent);
        assertThrows(SecurityException.class, cloneUserProperties::getAlwaysVisible);
        assertThat(typeProps.getProfileApiVisibility()).isEqualTo(
                cloneUserProperties.getProfileApiVisibility());
        compareDrawables(profileUM.getUserBadge(),
                Resources.getSystem().getDrawable(userTypeDetails.getBadgePlain()));

        // Verify clone user parent
        assertThat(mUserManager.getProfileParent(mainUserId)).isNull();
        UserInfo parentProfileInfo = mUserManager.getProfileParent(cloneProfileUser.id);
        assertThat(parentProfileInfo).isNotNull();
        assertThat(mainUserId).isEqualTo(parentProfileInfo.id);
        removeUser(cloneProfileUser.id);
        assertThat(mUserManager.getProfileParent(mainUserId)).isNull();
        assertThat(mUserManager.getProfileAccessibilityString(cloneProfileUser.id)).isEqualTo(
                Resources.getSystem().getString(userTypeDetails.getAccessibilityString()));
    }

    @Test
    public void testCommunalProfile() throws Exception {
        assumeTrue("Device doesn't support communal profiles ",
                mUserManager.isUserTypeEnabled(UserManager.USER_TYPE_PROFILE_COMMUNAL));

        // Create communal profile if needed
        if (mUserManager.getCommunalProfile() == null) {
            Slog.i(TAG, "Attempting to create a communal profile for a test");
            createUser("Communal", UserManager.USER_TYPE_PROFILE_COMMUNAL, /*flags*/ 0);
        }
        final UserHandle communal = mUserManager.getCommunalProfile();
        assertWithMessage("Couldn't create communal profile").that(communal).isNotNull();

        final UserTypeDetails userTypeDetails =
                UserTypeFactory.getUserTypes().get(UserManager.USER_TYPE_PROFILE_COMMUNAL);
        assertWithMessage("No communal user type on device").that(userTypeDetails).isNotNull();

        // Test that only one communal profile can be created
        final UserInfo secondCommunalProfile =
                createUser("Communal", UserManager.USER_TYPE_PROFILE_COMMUNAL, /*flags*/ 0);
        assertThat(secondCommunalProfile).isNull();

        // Verify that communal profile doesn't have a parent
        assertThat(mUserManager.getProfileParent(communal.getIdentifier())).isNull();

        // Make sure that, when switching users, the communal profile remains visible.
        final boolean isStarted = mActivityManager.startProfile(communal);
        assertWithMessage("Unable to start communal profile").that(isStarted).isTrue();
        final UserManager umCommunal = (UserManager) mContext.createPackageContextAsUser(
                        "android", 0, communal).getSystemService(Context.USER_SERVICE);
        final int originalCurrent = ActivityManager.getCurrentUser();
        final UserInfo testUser = createUser("TestUser", /* flags= */ 0);
        assertWithMessage("Communal profile not visible").that(umCommunal.isUserVisible()).isTrue();
        switchUser(testUser.id);
        assertWithMessage("Communal profile not visible").that(umCommunal.isUserVisible()).isTrue();
        switchUser(originalCurrent);
        assertWithMessage("Communal profile not visible").that(umCommunal.isUserVisible()).isTrue();
        assertThat(mUserManager.getProfileAccessibilityString(communal.getIdentifier()))
                .isEqualTo(Resources.getSystem()
                        .getString(userTypeDetails.getAccessibilityString()));
    }

    @Test
    @RequiresFlagsEnabled(android.multiuser.Flags.FLAG_SUPPORT_COMMUNAL_PROFILE)
    public void testGetProfilesIncludingCommunal() throws Exception {
        int mainUserId = mUserManager.getMainUser().getIdentifier();
        final UserInfo otherUser = createUser("TestUser", /* flags= */ 0);
        final UserInfo profile = createProfileForUser("Profile",
                UserManager.USER_TYPE_PROFILE_MANAGED, mainUserId);

        final UserHandle communalProfile = mUserManager.getCommunalProfile();

        final List<UserInfo> mainsActual = mUserManager.getProfilesIncludingCommunal(mainUserId);
        final List<UserInfo> othersActual = mUserManager.getProfilesIncludingCommunal(otherUser.id);

        final List<Integer> mainsExpected = new ArrayList<>();
        mainsExpected.add(mainUserId);
        if (profile != null) mainsExpected.add(profile.id);
        if (communalProfile != null) mainsExpected.add(communalProfile.getIdentifier());
        assertEquals(mainsExpected.stream().sorted().toList(),
                mainsActual.stream().map(ui -> ui.id).sorted().toList());


        final List<Integer> othersExpected = new ArrayList<>();
        othersExpected.add(otherUser.id);
        if (communalProfile != null) othersExpected.add(communalProfile.getIdentifier());
        assertEquals(othersExpected.stream().sorted().toList(),
                othersActual.stream().map(ui -> ui.id).sorted().toList());
    }

    @Test
    public void testPrivateProfile() throws Exception {
        UserHandle mainUser = mUserManager.getMainUser();
        assumeTrue("Main user is null", mainUser != null);
        // Get the default properties for private profile user type.
        final UserTypeDetails userTypeDetails =
                UserTypeFactory.getUserTypes().get(UserManager.USER_TYPE_PROFILE_PRIVATE);
        assertWithMessage("No %s type on device", UserManager.USER_TYPE_PROFILE_PRIVATE)
                .that(userTypeDetails).isNotNull();
        final UserProperties typeProps = userTypeDetails.getDefaultUserPropertiesReference();

        // Only run the test if private profile creation is enabled on the device
        assumeTrue("Private profile not enabled on the device",
                mUserManager.canAddPrivateProfile());

        // Test that only one private profile  can be created
        final int mainUserId = mainUser.getIdentifier();
        UserInfo privateProfileUser = createProfileForUser("Private profile1",
                UserManager.USER_TYPE_PROFILE_PRIVATE,
                mainUserId);
        assertThat(privateProfileUser).isNotNull();
        UserInfo privateProfileUser2 = createProfileForUser("Private profile2",
                UserManager.USER_TYPE_PROFILE_PRIVATE,
                mainUserId);
        assertThat(privateProfileUser2).isNull();
        final UserManager profileUM = UserManager.get(
                mContext.createPackageContextAsUser("android", 0,
                        UserHandle.of(privateProfileUser.id)));

        // Check that the new private profile has the expected properties (relative to the defaults)
        // provided that the test caller has the necessary permissions.
        UserProperties privateProfileUserProperties =
                mUserManager.getUserProperties(UserHandle.of(privateProfileUser.id));
        assertThat(typeProps.getShowInLauncher())
                .isEqualTo(privateProfileUserProperties.getShowInLauncher());
        assertThrows(SecurityException.class, privateProfileUserProperties::getStartWithParent);
        assertThrows(SecurityException.class,
                privateProfileUserProperties::getCrossProfileIntentFilterAccessControl);
        assertThat(typeProps.isMediaSharedWithParent())
                .isEqualTo(privateProfileUserProperties.isMediaSharedWithParent());
        assertThat(typeProps.isCredentialShareableWithParent())
                .isEqualTo(privateProfileUserProperties.isCredentialShareableWithParent());
        assertThat(typeProps.isAuthAlwaysRequiredToDisableQuietMode())
                .isEqualTo(privateProfileUserProperties
                        .isAuthAlwaysRequiredToDisableQuietMode());
        assertThat(typeProps.getCrossProfileContentSharingStrategy())
                .isEqualTo(privateProfileUserProperties.getCrossProfileContentSharingStrategy());
        assertThrows(SecurityException.class, privateProfileUserProperties::getDeleteAppWithParent);
        assertThrows(SecurityException.class,
                privateProfileUserProperties::getAllowStoppingUserWithDelayedLocking);
        assertThat(typeProps.getProfileApiVisibility()).isEqualTo(
                privateProfileUserProperties.getProfileApiVisibility());
        assertThat(typeProps.areItemsRestrictedOnHomeScreen())
                .isEqualTo(privateProfileUserProperties.areItemsRestrictedOnHomeScreen());
        compareDrawables(profileUM.getUserBadge(),
                Resources.getSystem().getDrawable(userTypeDetails.getBadgePlain()));

        // Verify private profile parent
        assertThat(mUserManager.getProfileParent(mainUserId)).isNull();
        UserInfo parentProfileInfo = mUserManager.getProfileParent(privateProfileUser.id);
        assertThat(parentProfileInfo).isNotNull();
        assertThat(mainUserId).isEqualTo(parentProfileInfo.id);
        removeUser(privateProfileUser.id);
        assertThat(mUserManager.getProfileParent(mainUserId)).isNull();
        assertThat(profileUM.getProfileLabel()).isEqualTo(
                Resources.getSystem().getString(userTypeDetails.getLabel(0)));
        assertThat(mUserManager.getProfileAccessibilityString(privateProfileUser.id)).isEqualTo(
                Resources.getSystem().getString(userTypeDetails.getAccessibilityString()));
    }

    @Test
    public void testSupervisingProfile() throws Exception {
        assumeTrue("Device doesn't support supervising profiles ",
                mUserManager.isUserTypeEnabled(UserManager.USER_TYPE_PROFILE_SUPERVISING));

        final UserTypeDetails userTypeDetails =
                UserTypeFactory.getUserTypes().get(UserManager.USER_TYPE_PROFILE_SUPERVISING);
        assertWithMessage("No supervising user type on device").that(userTypeDetails).isNotNull();


        // Create supervising profile if it doesn't exist
        UserInfo supervisingUser = getSupervisingProfile();
        if (supervisingUser == null) {
            supervisingUser = createUser("Supervising",
                    UserManager.USER_TYPE_PROFILE_SUPERVISING, /*flags*/ 0);
        }
        assertWithMessage("Couldn't create supervising profile").that(supervisingUser).isNotNull();
        UserHandle supervisingHandle = supervisingUser.getUserHandle();

        // Test that only one supervising profile can be created
        final UserInfo secondSupervisingProfile =
                createUser("Supervising", UserManager.USER_TYPE_PROFILE_SUPERVISING,
                        /*flags*/ 0);
        assertThat(secondSupervisingProfile).isNull();

        // Verify that the supervising profile doesn't have a parent
        assertThat(mUserManager.getProfileParent(supervisingHandle.getIdentifier())).isNull();

        // Make sure that the supervising profile can be started in the background, and that it
        // is visible
        final boolean isStarted = mActivityManager.startProfile(supervisingHandle);
        assertWithMessage("Unable to start supervising profile").that(isStarted).isTrue();
        final UserManager umSupervising = (UserManager) mContext.createPackageContextAsUser(
                "android", 0, supervisingHandle).getSystemService(Context.USER_SERVICE);
        assertWithMessage("Supervising profile not visible").that(
                umSupervising.isUserVisible()).isTrue();
    }

    @Test
    public void testGetProfileAccessibilityString_throwsExceptionForNonProfileUser() {
        UserInfo user1 = createUser("Guest 1", UserInfo.FLAG_GUEST);
        assertThat(user1).isNotNull();
        assertThrows(Resources.NotFoundException.class,
                () -> mUserManager.getProfileAccessibilityString(user1.id));
        assertThrows(Resources.NotFoundException.class,
                () -> mUserManager.getProfileAccessibilityString(UserHandle.USER_SYSTEM));
    }

    @MediumTest
    @Test
    public void testAdd2Users() throws Exception {
        UserInfo user1 = createUser("Guest 1", UserInfo.FLAG_GUEST);
        UserInfo user2 = createUser("User 2", UserInfo.FLAG_ADMIN);

        assertThat(user1).isNotNull();
        assertThat(user2).isNotNull();

        assertThat(hasUser(UserHandle.USER_SYSTEM)).isTrue();
        assertThat(hasUser(user1.id)).isTrue();
        assertThat(hasUser(user2.id)).isTrue();
    }

    /**
     * Tests that UserManager knows how many users can be created.
     *
     * We can only test this with regular secondary users, since some other user types have weird
     * rules about when or if they count towards the max.
     */
    @MediumTest
    @Test
    public void testAddTooManyUsers() throws Exception {
        final String userType = UserManager.USER_TYPE_FULL_SECONDARY;
        final UserTypeDetails userTypeDetails = UserTypeFactory.getUserTypes().get(userType);

        final int maxUsersForType = userTypeDetails.getMaxAllowed();
        final int maxUsersOverall = UserManager.getMaxSupportedUsers();

        int currentUsersOfType = 0;
        int currentUsersOverall = 0;
        final List<UserInfo> userList = mUserManager.getAliveUsers();
        for (UserInfo user : userList) {
            currentUsersOverall++;
            if (userType.equals(user.userType)) {
                currentUsersOfType++;
            }
        }

        final int remainingUserType = maxUsersForType == UserTypeDetails.UNLIMITED_NUMBER_OF_USERS ?
                Integer.MAX_VALUE : maxUsersForType - currentUsersOfType;
        final int remainingOverall = maxUsersOverall - currentUsersOverall;
        final int remaining = Math.min(remainingUserType, remainingOverall);

        Slog.v(TAG, "maxUsersForType=" + maxUsersForType
                + ", maxUsersOverall=" + maxUsersOverall
                + ", currentUsersOfType=" + currentUsersOfType
                + ", currentUsersOverall=" + currentUsersOverall
                + ", remaining=" + remaining);

        assumeTrue("Device supports too many users for this test to be practical", remaining < 20);

        int usersAdded;
        for (usersAdded = 0; usersAdded < remaining; usersAdded++) {
            Slog.v(TAG, "Adding user " + usersAdded);
            assertThat(mUserManager.canAddMoreUsers()).isTrue();
            assertThat(mUserManager.canAddMoreUsers(userType)).isTrue();

            final UserInfo user = createUser("User " + usersAdded, userType, 0);
            assertThat(user).isNotNull();
            assertThat(hasUser(user.id)).isTrue();
        }
        Slog.v(TAG, "Added " + usersAdded + " users.");

        assertWithMessage("Still thinks more users of that type can be added")
                .that(mUserManager.canAddMoreUsers(userType)).isFalse();
        if (currentUsersOverall + usersAdded >= maxUsersOverall) {
            assertThat(mUserManager.canAddMoreUsers()).isFalse();
        }

        assertThat(createUser("User beyond", userType, 0)).isNull();

        assertThat(mUserManager.canAddMoreUsers(mUserManager.USER_TYPE_FULL_GUEST)).isTrue();
    }

    @MediumTest
    @Test
    public void testRemoveUser() throws Exception {
        UserInfo userInfo = createUser("Guest 1", UserInfo.FLAG_GUEST);
        removeUser(userInfo.id);

        assertThat(hasUser(userInfo.id)).isFalse();
    }

    @MediumTest
    @Test
    public void testRemoveUserByHandle() {
        UserInfo userInfo = createUser("Guest 1", UserInfo.FLAG_GUEST);

        removeUser(userInfo.getUserHandle());

        assertThat(hasUser(userInfo.id)).isFalse();
    }

    @MediumTest
    @Test
    public void testRemoveUserByHandle_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> mUserManager.removeUser(null));
    }

    @MediumTest
    @Test
    public void testRemoveUserShouldNotRemoveCurrentUser() {
        final int startUser = ActivityManager.getCurrentUser();
        final UserInfo testUser = createUser("TestUser", /* flags= */ 0);
        // Switch to the user just created.
        switchUser(testUser.id);

        assertWithMessage("Current user should not be removed")
                .that(mUserManager.removeUser(testUser.id))
                .isFalse();

        // Switch back to the starting user.
        switchUser(startUser);

        // Now we can remove the user
        removeUser(testUser.id);
    }

    @MediumTest
    @Test
    public void testRemoveUserShouldNotRemoveCurrentUser_DuringUserSwitch() {
        final int startUser = ActivityManager.getCurrentUser();
        final UserInfo testUser = createUser("TestUser", /* flags= */ 0);
        // Switch to the user just created.
        switchUser(testUser.id);

        switchUserThenRun(startUser, () -> {
            // While the user switch is happening, call removeUser for the current user.
            assertWithMessage("Current user should not be removed during user switch")
                    .that(mUserManager.removeUser(testUser.id))
                    .isFalse();
        });
        assertThat(hasUser(testUser.id)).isTrue();

        // Now we can remove the user
        removeUser(testUser.id);
    }

    @MediumTest
    @Test
    public void testRemoveUser_shouldRemovePrivateUser() {
        UserInfo privateProfileUser =
                createProfileForUser(
                        "Private profile",
                        UserManager.USER_TYPE_PROFILE_PRIVATE,
                        mUserManager.getMainUser().getIdentifier());
        assertThat(privateProfileUser).isNotNull();
        assertThat(hasUser(privateProfileUser.id)).isTrue();

        removeUser(privateProfileUser.id);

        assertThat(hasUser(privateProfileUser.id)).isFalse();
    }

    @MediumTest
    @Test
    @RequiresFlagsEnabled(
            android.multiuser.Flags.FLAG_IGNORE_RESTRICTIONS_WHEN_DELETING_PRIVATE_PROFILE)
    public void testRemoveUser_shouldRemovePrivateUser_withDisallowRemoveUserRestriction() {
        UserHandle mainUser = mUserManager.getMainUser();
        mUserManager.setUserRestriction(
                UserManager.DISALLOW_REMOVE_USER, /* value= */ true, mainUser);
        try {
            UserInfo privateProfileUser =
                    createProfileForUser(
                            "Private profile",
                            UserManager.USER_TYPE_PROFILE_PRIVATE,
                            mainUser.getIdentifier());
            assertThat(privateProfileUser).isNotNull();
            assertThat(hasUser(privateProfileUser.id)).isTrue();
            removeUser(privateProfileUser.id);

            assertThat(hasUser(privateProfileUser.id)).isFalse();
        } finally {
            mUserManager.setUserRestriction(
                    UserManager.DISALLOW_REMOVE_USER, /* value= */ false, mainUser);
        }
    }

    @MediumTest
    @Test
    public void testRemoveUser_withDisallowRemoveUserRestrictionAndMultipleUsersPresent() {
        UserInfo privateProfileUser =
                createProfileForUser(
                        "Private profile",
                        UserManager.USER_TYPE_PROFILE_PRIVATE,
                        mUserManager.getMainUser().getIdentifier());
        assertThat(privateProfileUser).isNotNull();
        assertThat(hasUser(privateProfileUser.id)).isTrue();
        UserInfo testUser = createUser("TestUser", /* flags= */ 0);
        assertThat(testUser).isNotNull();
        assertThat(hasUser(testUser.id)).isTrue();
        UserHandle mainUser = mUserManager.getMainUser();
        mUserManager.setUserRestriction(
                UserManager.DISALLOW_REMOVE_USER, /* value= */ true, mainUser);
        try {
            assertThat(
                    mUserManager.removeUserWhenPossible(
                            testUser.getUserHandle(), /* overrideDevicePolicy= */ false))
                    .isEqualTo(UserManager.REMOVE_RESULT_ERROR_USER_RESTRICTION);

            // Non private profile users should be prevented from being removed.
            assertThat(mUserManager.removeUser(testUser.id)).isEqualTo(false);

            assertThat(hasUser(testUser.id)).isTrue();
        } finally {
            mUserManager.setUserRestriction(
                    UserManager.DISALLOW_REMOVE_USER, /* value= */ false, mainUser);
        }
    }

    @MediumTest
    @Test
    public void testRemoveUserShouldNotRemoveTargetUser_DuringUserSwitch() {
        final int startUser = ActivityManager.getCurrentUser();
        final UserInfo testUser = createUser("TestUser", /* flags= */ 0);

        switchUserThenRun(testUser.id, () -> {
            // While the user switch is happening, call removeUser for the target user.
            assertWithMessage("Target user should not be removed during user switch")
                    .that(mUserManager.removeUser(testUser.id))
                    .isFalse();
        });
        assertThat(hasUser(testUser.id)).isTrue();

        // Switch back to the starting user.
        switchUser(startUser);

        // Now we can remove the user
        removeUser(testUser.id);
    }

    @MediumTest
    @Test
    public void testRemoveUserWhenPossible_restrictedReturnsError() throws Exception {
        final int currentUser = ActivityManager.getCurrentUser();
        final UserInfo user1 = createUser("User 1", /* flags= */ 0);
        mUserManager.setUserRestriction(UserManager.DISALLOW_REMOVE_USER, /* value= */ true,
                asHandle(currentUser));
        try {
            assertThat(mUserManager.removeUserWhenPossible(user1.getUserHandle(),
                    /* overrideDevicePolicy= */ false))
                            .isEqualTo(UserManager.REMOVE_RESULT_ERROR_USER_RESTRICTION);
        } finally {
            mUserManager.setUserRestriction(UserManager.DISALLOW_REMOVE_USER, /* value= */ false,
                    asHandle(currentUser));
        }

        assertThat(hasUser(user1.id)).isTrue();
        assertThat(getUser(user1.id).isEphemeral()).isFalse();
    }

    @MediumTest
    @Test
    public void testRemoveUserWhenPossible_evenWhenRestricted() throws Exception {
        final int currentUser = ActivityManager.getCurrentUser();
        final UserInfo user1 = createUser("User 1", /* flags= */ 0);
        mUserManager.setUserRestriction(UserManager.DISALLOW_REMOVE_USER, /* value= */ true,
                asHandle(currentUser));
        try {
            assertThat(mUserManager.removeUserWhenPossible(user1.getUserHandle(),
                    /* overrideDevicePolicy= */ true))
                    .isEqualTo(UserManager.REMOVE_RESULT_REMOVED);
            waitForUserRemoval(user1.id);
        } finally {
            mUserManager.setUserRestriction(UserManager.DISALLOW_REMOVE_USER, /* value= */ false,
                    asHandle(currentUser));
        }

        assertThat(hasUser(user1.id)).isFalse();
    }

    @MediumTest
    @Test
    public void testRemoveUserWhenPossible_systemUserReturnsError() throws Exception {
        assertThat(mUserManager.removeUserWhenPossible(UserHandle.SYSTEM,
                /* overrideDevicePolicy= */ false))
                        .isEqualTo(UserManager.REMOVE_RESULT_ERROR_SYSTEM_USER);

        assertThat(hasUser(UserHandle.USER_SYSTEM)).isTrue();
    }

    @MediumTest
    @Test
    public void testRemoveUserWhenPossible_permanentAdminMainUserReturnsError() throws Exception {
        assumeHeadlessModeEnabled();
        assumeTrue("Main user is not permanent admin", isMainUserPermanentAdmin());

        int currentUser = ActivityManager.getCurrentUser();
        final UserInfo otherUser = createUser("User 1", /* flags= */ UserInfo.FLAG_ADMIN);
        UserHandle mainUser = mUserManager.getMainUser();

        switchUser(otherUser.id);

        assertThat(mUserManager.removeUserWhenPossible(mainUser,
                /* overrideDevicePolicy= */ false))
                .isEqualTo(UserManager.REMOVE_RESULT_ERROR_MAIN_USER_PERMANENT_ADMIN);


        assertThat(hasUser(mainUser.getIdentifier())).isTrue();

        // Switch back to the starting user.
        switchUser(currentUser);
    }

    @MediumTest
    @Test
    public void testRemoveUserWhenPossible_invalidUserReturnsError() throws Exception {
        assertThat(hasUser(Integer.MAX_VALUE)).isFalse();
        assertThat(mUserManager.removeUserWhenPossible(UserHandle.of(Integer.MAX_VALUE),
                /* overrideDevicePolicy= */ false))
                        .isEqualTo(UserManager.REMOVE_RESULT_ERROR_USER_NOT_FOUND);
    }

    @MediumTest
    @Test
    public void testRemoveUserWhenPossible_currentUserSetEphemeral() throws Exception {
        final int startUser = ActivityManager.getCurrentUser();
        final UserInfo user1 = createUser("User 1", /* flags= */ 0);
        // Switch to the user just created.
        switchUser(user1.id);

        assertThat(mUserManager.removeUserWhenPossible(user1.getUserHandle(),
                /* overrideDevicePolicy= */ false)).isEqualTo(UserManager.REMOVE_RESULT_DEFERRED);

        assertThat(hasUser(user1.id)).isTrue();
        assertThat(getUser(user1.id).isEphemeral()).isTrue();

        // Switch back to the starting user.
        switchUser(startUser);
        // User will be removed once switch is complete
        waitForUserRemoval(user1.id);

        assertThat(hasUser(user1.id)).isFalse();
    }

    @MediumTest
    @Test
    public void testRemoveUserWhenPossible_currentUserSetEphemeral_duringUserSwitch() {
        final int startUser = ActivityManager.getCurrentUser();
        final UserInfo testUser = createUser("TestUser", /* flags= */ 0);
        // Switch to the user just created.
        switchUser(testUser.id);

        switchUserThenRun(startUser, () -> {
            // While the switch is happening, call removeUserWhenPossible for the current user.
            assertThat(mUserManager.removeUserWhenPossible(testUser.getUserHandle(),
                    /* overrideDevicePolicy= */ false))
                    .isEqualTo(UserManager.REMOVE_RESULT_DEFERRED);

            assertThat(hasUser(testUser.id)).isTrue();
            assertThat(getUser(testUser.id).isEphemeral()).isTrue();
        }); // wait for user switch - startUser
        // User will be removed once switch is complete
        waitForUserRemoval(testUser.id);

        assertThat(hasUser(testUser.id)).isFalse();
    }

    @MediumTest
    @Test
    public void testRemoveUserWhenPossible_targetUserSetEphemeral_duringUserSwitch() {
        final int startUser = ActivityManager.getCurrentUser();
        final UserInfo testUser = createUser("TestUser", /* flags= */ 0);

        switchUserThenRun(testUser.id, () -> {
            // While the user switch is happening, call removeUserWhenPossible for the target user.
            assertThat(mUserManager.removeUserWhenPossible(testUser.getUserHandle(),
                    /* overrideDevicePolicy= */ false))
                    .isEqualTo(UserManager.REMOVE_RESULT_DEFERRED);

            assertThat(hasUser(testUser.id)).isTrue();
            assertThat(getUser(testUser.id).isEphemeral()).isTrue();
        }); // wait for user switch - testUser

        // Switch back to the starting user.
        switchUser(startUser);
        // User will be removed once switch is complete
        waitForUserRemoval(testUser.id);

        assertThat(hasUser(testUser.id)).isFalse();
    }

    @MediumTest
    @Test
    public void testRemoveUserWhenPossible_nonCurrentUserRemoved() throws Exception {
        final UserInfo user1 = createUser("User 1", /* flags= */ 0);

        assertThat(mUserManager.removeUserWhenPossible(user1.getUserHandle(),
                /* overrideDevicePolicy= */ false))
                .isEqualTo(UserManager.REMOVE_RESULT_REMOVED);
        waitForUserRemoval(user1.id);

        assertThat(hasUser(user1.id)).isFalse();
    }

    @MediumTest
    @Test
    public void testRemoveUserWhenPossible_withProfiles() throws Exception {
        assumeHeadlessModeEnabled();
        assumeCloneEnabled();
        final List<String> profileTypesToCreate = Arrays.asList(
                UserManager.USER_TYPE_PROFILE_CLONE,
                UserManager.USER_TYPE_PROFILE_MANAGED
        );

        final UserInfo parentUser = createUser("Human User", /* flags= */ 0);
        assertWithMessage("Could not create parent user")
                .that(parentUser).isNotNull();

        final List<Integer> profileIds = new ArrayList<>();
        for (String profileType : profileTypesToCreate) {
            final String name = profileType.substring(profileType.lastIndexOf('.') + 1);
            if (mUserManager.canAddMoreProfilesToUser(profileType, parentUser.id)) {
                final UserInfo profile = createProfileForUser(name, profileType, parentUser.id);
                assertWithMessage("Could not create " + name)
                        .that(profile).isNotNull();
                profileIds.add(profile.id);
            } else {
                Slog.w(TAG, "Can not add " + name + " to user #" + parentUser.id);
            }
        }

        // Test shouldn't pass or fail unless it's allowed to add profiles to secondary users.
        assumeTrue("Not possible to create any profiles to user #" + parentUser.id,
                profileIds.size() > 0);

        assertThat(mUserManager.removeUserWhenPossible(parentUser.getUserHandle(),
                /* overrideDevicePolicy= */ false))
                .isEqualTo(UserManager.REMOVE_RESULT_REMOVED);
        waitForUserRemoval(parentUser.id);

        assertWithMessage("Parent user still exists")
                .that(hasUser(parentUser.id)).isFalse();
        profileIds.forEach(id ->
                assertWithMessage("Profile still exists")
                        .that(hasUser(id)).isFalse());
    }

    /** Tests creating a FULL user via specifying userType. */
    @MediumTest
    @Test
    public void testCreateUserViaTypes() throws Exception {
        createUserWithTypeAndCheckFlags(UserManager.USER_TYPE_FULL_GUEST,
                UserInfo.FLAG_GUEST | UserInfo.FLAG_FULL);

        createUserWithTypeAndCheckFlags(UserManager.USER_TYPE_FULL_DEMO,
                UserInfo.FLAG_DEMO | UserInfo.FLAG_FULL);

        createUserWithTypeAndCheckFlags(UserManager.USER_TYPE_FULL_SECONDARY,
                UserInfo.FLAG_FULL);
    }

    /** Tests creating a FULL user via specifying user flags. */
    @MediumTest
    @Test
    public void testCreateUserViaFlags() throws Exception {
        createUserWithFlagsAndCheckType(UserInfo.FLAG_GUEST, UserManager.USER_TYPE_FULL_GUEST,
                UserInfo.FLAG_FULL);

        createUserWithFlagsAndCheckType(0, UserManager.USER_TYPE_FULL_SECONDARY,
                UserInfo.FLAG_FULL);

        createUserWithFlagsAndCheckType(UserInfo.FLAG_FULL, UserManager.USER_TYPE_FULL_SECONDARY,
                0);

        createUserWithFlagsAndCheckType(UserInfo.FLAG_DEMO, UserManager.USER_TYPE_FULL_DEMO,
                UserInfo.FLAG_FULL);
    }

    /** Creates a user of the given user type and checks that the result has the requiredFlags. */
    private void createUserWithTypeAndCheckFlags(String userType,
            @UserIdInt int requiredFlags) {
        final UserInfo userInfo = createUser("Name", userType, 0);
        assertWithMessage("Wrong user type").that(userInfo.userType).isEqualTo(userType);
        assertWithMessage("Flags %s did not contain expected %s", userInfo.flags, requiredFlags)
                .that(userInfo.flags & requiredFlags).isEqualTo(requiredFlags);
        removeUser(userInfo.id);
    }

    /**
     * Creates a user of the given flags and checks that the result is of the expectedUserType type
     * and that it has the expected flags (including both flags and any additionalRequiredFlags).
     */
    private void createUserWithFlagsAndCheckType(@UserIdInt int flags, String expectedUserType,
            @UserIdInt int additionalRequiredFlags) {
        final UserInfo userInfo = createUser("Name", flags);
        assertWithMessage("Wrong user type").that(userInfo.userType).isEqualTo(expectedUserType);
        additionalRequiredFlags |= flags;
        assertWithMessage("Flags %s did not contain expected %s", userInfo.flags,
                additionalRequiredFlags).that(userInfo.flags & additionalRequiredFlags)
                        .isEqualTo(additionalRequiredFlags);
        removeUser(userInfo.id);
    }

    private void requireSingleGuest() throws Exception {
        assumeTrue("device supports single guest",
                UserTypeFactory.getUserTypes().get(UserManager.USER_TYPE_FULL_GUEST)
                .getMaxAllowed() == 1);
    }

    private void requireMultipleGuests() throws Exception {
        assumeTrue("device supports multiple guests",
                UserTypeFactory.getUserTypes().get(UserManager.USER_TYPE_FULL_GUEST)
                .getMaxAllowed() > 1);
    }

    @MediumTest
    @Test
    public void testThereCanBeOnlyOneGuest_singleGuest() throws Exception {
        requireSingleGuest();
        assertThat(mUserManager.canAddMoreUsers(mUserManager.USER_TYPE_FULL_GUEST)).isTrue();
        UserInfo userInfo1 = createUser("Guest 1", UserInfo.FLAG_GUEST);
        assertThat(userInfo1).isNotNull();
        assertThat(mUserManager.canAddMoreUsers(mUserManager.USER_TYPE_FULL_GUEST)).isFalse();
        UserInfo userInfo2 = createUser("Guest 2", UserInfo.FLAG_GUEST);
        assertThat(userInfo2).isNull();
    }

    @MediumTest
    @Test
    public void testThereCanBeMultipleGuests_multipleGuests() throws Exception {
        requireMultipleGuests();
        assertThat(mUserManager.canAddMoreUsers(mUserManager.USER_TYPE_FULL_GUEST)).isTrue();
        UserInfo userInfo1 = createUser("Guest 1", UserInfo.FLAG_GUEST);
        assertThat(userInfo1).isNotNull();
        assertThat(mUserManager.canAddMoreUsers(mUserManager.USER_TYPE_FULL_GUEST)).isTrue();
        UserInfo userInfo2 = createUser("Guest 2", UserInfo.FLAG_GUEST);
        assertThat(userInfo2).isNotNull();
    }

    @MediumTest
    @Test
    public void testFindExistingGuest_guestExists() throws Exception {
        UserInfo userInfo1 = createUser("Guest", UserInfo.FLAG_GUEST);
        assertThat(userInfo1).isNotNull();
        UserInfo foundGuest = mUserManager.findCurrentGuestUser();
        assertThat(foundGuest).isNotNull();
    }

    @MediumTest
    @Test
    public void testGetGuestUsers_singleGuest() throws Exception {
        requireSingleGuest();
        UserInfo userInfo1 = createUser("Guest1", UserInfo.FLAG_GUEST);
        assertThat(userInfo1).isNotNull();
        List<UserInfo> guestsFound = mUserManager.getGuestUsers();
        assertThat(guestsFound).hasSize(1);
        assertThat(guestsFound.get(0).name).isEqualTo("Guest1");
    }

    @MediumTest
    @Test
    public void testGetGuestUsers_multipleGuests() throws Exception {
        requireMultipleGuests();
        UserInfo userInfo1 = createUser("Guest1", UserInfo.FLAG_GUEST);
        assertThat(userInfo1).isNotNull();
        UserInfo userInfo2 = createUser("Guest2", UserInfo.FLAG_GUEST);
        assertThat(userInfo2).isNotNull();

        List<UserInfo> guestsFound = mUserManager.getGuestUsers();
        assertThat(guestsFound).hasSize(2);
        assertThat(ImmutableList.of(guestsFound.get(0).name, guestsFound.get(1).name))
            .containsExactly("Guest1", "Guest2");
    }

    @MediumTest
    @Test
    public void testGetGuestUsers_markGuestForDeletion() throws Exception {
        requireMultipleGuests();
        UserInfo userInfo1 = createUser("Guest1", UserInfo.FLAG_GUEST);
        assertThat(userInfo1).isNotNull();
        UserInfo userInfo2 = createUser("Guest2", UserInfo.FLAG_GUEST);
        assertThat(userInfo2).isNotNull();

        boolean markedForDeletion1 = mUserManager.markGuestForDeletion(userInfo1.id);
        assertThat(markedForDeletion1).isTrue();

        List<UserInfo> guestsFound = mUserManager.getGuestUsers();
        assertThat(guestsFound.size()).isEqualTo(1);

        boolean markedForDeletion2 = mUserManager.markGuestForDeletion(userInfo2.id);
        assertThat(markedForDeletion2).isTrue();

        guestsFound = mUserManager.getGuestUsers();
        assertThat(guestsFound).isEmpty();
    }

    @SmallTest
    @Test
    public void testFindExistingGuest_guestDoesNotExist() throws Exception {
        UserInfo foundGuest = mUserManager.findCurrentGuestUser();
        assertThat(foundGuest).isNull();
    }

    @SmallTest
    @Test
    public void testGetGuestUsers_guestDoesNotExist() throws Exception {
        List<UserInfo> guestsFound = mUserManager.getGuestUsers();
        assertThat(guestsFound).isEmpty();
    }

    @MediumTest
    @Test
    public void testSetUserAdmin() throws Exception {
        UserInfo userInfo = createUser("SecondaryUser", /*flags=*/ 0);
        assertThat(userInfo.isAdmin()).isFalse();

        mUserManager.setUserAdmin(userInfo.id);

        userInfo = mUserManager.getUserInfo(userInfo.id);
        assertThat(userInfo.isAdmin()).isTrue();
    }

    @MediumTest
    @Test
    @RequiresFlagsEnabled(android.multiuser.Flags.FLAG_UNICORN_MODE_REFACTORING_FOR_HSUM_READ_ONLY)
    public void testSetUserAdminThrowsSecurityException() throws Exception {
        UserInfo targetUser = createUser("SecondaryUser", /*flags=*/ 0);
        assertThat(targetUser.isAdmin()).isFalse();

        try {
            // 1. Target User Restriction
            mUserManager.setUserRestriction(UserManager.DISALLOW_GRANT_ADMIN, true,
                    targetUser.getUserHandle());
            assertThrows(SecurityException.class, () -> mUserManager.setUserAdmin(targetUser.id));

            // 2. Current User Restriction
            mUserManager.setUserRestriction(UserManager.DISALLOW_GRANT_ADMIN, false,
                    targetUser.getUserHandle());
            mUserManager.setUserRestriction(UserManager.DISALLOW_GRANT_ADMIN, true,
                    mContext.getUser());
            assertThrows(SecurityException.class, () -> mUserManager.setUserAdmin(targetUser.id));

        } finally {
            // Ensure restriction is removed even if test fails
            mUserManager.setUserRestriction(UserManager.DISALLOW_GRANT_ADMIN, false,
                    targetUser.getUserHandle());
            mUserManager.setUserRestriction(UserManager.DISALLOW_GRANT_ADMIN, false,
                    mContext.getUser());
        }
    }

    @MediumTest
    @Test
    public void testRevokeUserAdmin() throws Exception {
        UserInfo userInfo = createUser("Admin", /*flags=*/ UserInfo.FLAG_ADMIN);
        assertThat(userInfo.isAdmin()).isTrue();

        mUserManager.revokeUserAdmin(userInfo.id);

        userInfo = mUserManager.getUserInfo(userInfo.id);
        assertThat(userInfo.isAdmin()).isFalse();
    }

    @MediumTest
    @Test
    public void testRevokeUserAdminFromNonAdmin() throws Exception {
        UserInfo userInfo = createUser("NonAdmin", /*flags=*/ 0);
        assertThat(userInfo.isAdmin()).isFalse();

        mUserManager.revokeUserAdmin(userInfo.id);

        userInfo = mUserManager.getUserInfo(userInfo.id);
        assertThat(userInfo.isAdmin()).isFalse();
    }

    @MediumTest
    @Test
    @RequiresFlagsEnabled(android.multiuser.Flags.FLAG_UNICORN_MODE_REFACTORING_FOR_HSUM_READ_ONLY)
    public void testRevokeUserAdminThrowsSecurityException() throws Exception {
        UserInfo targetUser = createUser("SecondaryUser", /*flags=*/ 0);
        assertThat(targetUser.isAdmin()).isFalse();

        try {
            // 1. Target User Restriction
            mUserManager.setUserRestriction(UserManager.DISALLOW_GRANT_ADMIN, true,
                    targetUser.getUserHandle());
            assertThrows(SecurityException.class, () -> mUserManager
                    .revokeUserAdmin(targetUser.id));

            // 2. Current User Restriction
            mUserManager.setUserRestriction(UserManager.DISALLOW_GRANT_ADMIN, false,
                    targetUser.getUserHandle());
            mUserManager.setUserRestriction(UserManager.DISALLOW_GRANT_ADMIN, true,
                    mContext.getUser());
            assertThrows(SecurityException.class, () -> mUserManager
                    .revokeUserAdmin(targetUser.id));

        } finally {
            // Ensure restriction is removed even if test fails
            mUserManager.setUserRestriction(UserManager.DISALLOW_GRANT_ADMIN, false,
                    targetUser.getUserHandle());
            mUserManager.setUserRestriction(UserManager.DISALLOW_GRANT_ADMIN, false,
                    mContext.getUser());
        }
    }

    @MediumTest
    @Test
    public void testGetProfileParent() throws Exception {
        assumeManagedUsersSupported();
        int mainUserId = mUserManager.getMainUser().getIdentifier();
        UserInfo userInfo = createProfileForUser("Profile",
                UserManager.USER_TYPE_PROFILE_MANAGED, mainUserId);
        assertThat(userInfo).isNotNull();
        assertThat(mUserManager.getProfileParent(mainUserId)).isNull();
        UserInfo parentProfileInfo = mUserManager.getProfileParent(userInfo.id);
        assertThat(parentProfileInfo).isNotNull();
        assertThat(mainUserId).isEqualTo(parentProfileInfo.id);
        removeUser(userInfo.id);
        assertThat(mUserManager.getProfileParent(mainUserId)).isNull();
    }

    /** Test that UserManager returns the correct badge information for a managed profile. */
    @MediumTest
    @Test
    public void testProfileTypeInformation() throws Exception {
        assumeManagedUsersSupported();
        final UserTypeDetails userTypeDetails =
                UserTypeFactory.getUserTypes().get(UserManager.USER_TYPE_PROFILE_MANAGED);
        assertWithMessage("No %s type on device", UserManager.USER_TYPE_PROFILE_MANAGED)
                .that(userTypeDetails).isNotNull();
        assertThat(userTypeDetails.getName()).isEqualTo(UserManager.USER_TYPE_PROFILE_MANAGED);

        int mainUserId = mUserManager.getMainUser().getIdentifier();
        UserInfo managedProfileUser = createProfileForUser("Managed",
                UserManager.USER_TYPE_PROFILE_MANAGED, mainUserId);
        assertThat(managedProfileUser).isNotNull();
        final int userId = managedProfileUser.id;
        final UserManager profileUM = UserManager.get(
                mContext.createPackageContextAsUser("android", 0,
                        UserHandle.of(managedProfileUser.id)));

        assertThat(mUserManager.hasBadge(userId)).isEqualTo(userTypeDetails.hasBadge());
        assertThat(mUserManager.getUserIconBadgeResId(userId))
                .isEqualTo(userTypeDetails.getIconBadge());
        assertThat(mUserManager.getUserBadgeResId(userId))
                .isEqualTo(userTypeDetails.getBadgePlain());
        assertThat(mUserManager.getUserBadgeNoBackgroundResId(userId))
                .isEqualTo(userTypeDetails.getBadgeNoBackground());
        assertThat(mUserManager.getUserStatusBarIconResId(userId))
                .isEqualTo(userTypeDetails.getStatusBarIcon());
        compareDrawables(profileUM.getUserBadge(),
                Resources.getSystem().getDrawable(userTypeDetails.getBadgePlain()));

        final int badgeIndex = managedProfileUser.profileBadge;
        assertThat(mUserManager.getUserBadgeColor(userId)).isEqualTo(
                Resources.getSystem().getColor(userTypeDetails.getBadgeColor(badgeIndex), null));
        assertThat(mUserManager.getUserBadgeDarkColor(userId)).isEqualTo(
                Resources.getSystem().getColor(userTypeDetails.getDarkThemeBadgeColor(badgeIndex),
                        null));

        assertThat(mUserManager.getBadgedLabelForUser("Test", asHandle(userId))).isEqualTo(
                Resources.getSystem().getString(userTypeDetails.getBadgeLabel(badgeIndex), "Test"));

        // Test @UserHandleAware methods
        final UserManager userManagerForUser = UserManager.get(mContext.createPackageContextAsUser(
                "android", 0, asHandle(userId)));
        assertThat(userManagerForUser.isUserOfType(userTypeDetails.getName())).isTrue();
        assertThat(userManagerForUser.isProfile()).isEqualTo(userTypeDetails.isProfile());
        assertThat(mUserManager.getProfileAccessibilityString(managedProfileUser.id)).isEqualTo(
                Resources.getSystem().getString(userTypeDetails.getAccessibilityString()));
    }

    /** Test that UserManager returns the correct UserProperties for a new managed profile. */
    @MediumTest
    @Test
    public void testUserProperties() throws Exception {
        assumeManagedUsersSupported();

        // Get the default properties for a user type.
        final UserTypeDetails userTypeDetails =
                UserTypeFactory.getUserTypes().get(UserManager.USER_TYPE_PROFILE_MANAGED);
        assertWithMessage("No %s type on device", UserManager.USER_TYPE_PROFILE_MANAGED)
                .that(userTypeDetails).isNotNull();
        final UserProperties typeProps = userTypeDetails.getDefaultUserPropertiesReference();

        // Create an actual user (of this user type) and get its properties.
        int mainUserId = mUserManager.getMainUser().getIdentifier();
        final UserInfo managedProfileUser = createProfileForUser("Managed",
                UserManager.USER_TYPE_PROFILE_MANAGED, mainUserId);
        assertThat(managedProfileUser).isNotNull();
        final int userId = managedProfileUser.id;
        final UserProperties userProps = mUserManager.getUserProperties(UserHandle.of(userId));

        // Check that this new user has the expected properties (relative to the defaults)
        // provided that the test caller has the necessary permissions.
        assertThat(userProps.getShowInLauncher()).isEqualTo(typeProps.getShowInLauncher());
        assertThat(userProps.getShowInSettings()).isEqualTo(typeProps.getShowInSettings());
        assertThat(userProps.getUseParentsContacts()).isFalse();
        assertThrows(SecurityException.class, userProps::getCrossProfileIntentFilterAccessControl);
        assertThrows(SecurityException.class, userProps::getCrossProfileIntentResolutionStrategy);
        assertThrows(SecurityException.class, userProps::getStartWithParent);
        assertThrows(SecurityException.class, userProps::getInheritDevicePolicy);
        assertThat(userProps.isMediaSharedWithParent()).isFalse();
        assertThat(userProps.isCredentialShareableWithParent()).isTrue();
        assertThrows(SecurityException.class, userProps::getDeleteAppWithParent);
        assertThrows(SecurityException.class, userProps::getAlwaysVisible);
    }

    /**
     * Test that UserManager.getUserProperties throws the IllegalArgumentException for unsupported
     * arguments such as UserHandle.NULL, UserHandle.CURRENT or UserHandle.ALL.
     **/
    @MediumTest
    @Test
    public void testThrowUserPropertiesForUnsupportedUserHandles() throws Exception {
        assertThrows(IllegalArgumentException.class, () ->
            mUserManager.getUserProperties(UserHandle.of(UserHandle.USER_NULL)));
        assertThrows(IllegalArgumentException.class, () ->
            mUserManager.getUserProperties(UserHandle.CURRENT));
        assertThrows(IllegalArgumentException.class, () ->
            mUserManager.getUserProperties(UserHandle.CURRENT_OR_SELF));
        assertThrows(IllegalArgumentException.class, () ->
            mUserManager.getUserProperties(UserHandle.ALL));
    }

    // Make sure only max managed profiles can be created
    @MediumTest
    @Test
    public void testAddManagedProfile() throws Exception {
        assumeManagedUsersSupported();
        int mainUserId = mUserManager.getMainUser().getIdentifier();
        UserInfo userInfo1 = createProfileForUser("Managed 1",
                UserManager.USER_TYPE_PROFILE_MANAGED, mainUserId);
        UserInfo userInfo2 = createProfileForUser("Managed 2",
                UserManager.USER_TYPE_PROFILE_MANAGED, mainUserId);

        assertThat(userInfo1).isNotNull();
        assertThat(userInfo2).isNull();

        assertThat(userInfo1.userType).isEqualTo(UserManager.USER_TYPE_PROFILE_MANAGED);
        int requiredFlags = UserInfo.FLAG_MANAGED_PROFILE | UserInfo.FLAG_PROFILE;
        assertWithMessage("Wrong flags %s", userInfo1.flags).that(userInfo1.flags & requiredFlags)
                .isEqualTo(requiredFlags);

        // Verify that current user is not a managed profile
        assertThat(mUserManager.isManagedProfile()).isFalse();
    }

    // Verify that disallowed packages are not installed in the managed profile.
    @MediumTest
    @Test
    public void testAddManagedProfile_withDisallowedPackages() throws Exception {
        assumeManagedUsersSupported();
        int mainUserId = mUserManager.getMainUser().getIdentifier();
        UserInfo userInfo1 = createProfileForUser("Managed1",
                UserManager.USER_TYPE_PROFILE_MANAGED, mainUserId);
        // Verify that the packagesToVerify are installed by default.
        for (String pkg : PACKAGES) {
            if (!mPackageManager.isPackageAvailable(pkg)) {
                Slog.w(TAG, "Package is not available " + pkg);
                continue;
            }

            assertWithMessage("Package should be installed in managed profile: %s", pkg)
                    .that(isPackageInstalledForUser(pkg, userInfo1.id)).isTrue();
        }
        removeUser(userInfo1.id);

        UserInfo userInfo2 = createProfileForUser("Managed2",
                UserManager.USER_TYPE_PROFILE_MANAGED, mainUserId, PACKAGES);
        // Verify that the packagesToVerify are not installed by default.
        for (String pkg : PACKAGES) {
            if (!mPackageManager.isPackageAvailable(pkg)) {
                Slog.w(TAG, "Package is not available " + pkg);
                continue;
            }

            assertWithMessage(
                    "Package should not be installed in managed profile when disallowed: %s", pkg)
                            .that(isPackageInstalledForUser(pkg, userInfo2.id)).isFalse();
        }
    }

    // Verify that if any packages are disallowed to install during creation of managed profile can
    // still be installed later.
    @MediumTest
    @Test
    public void testAddManagedProfile_disallowedPackagesInstalledLater() throws Exception {
        assumeManagedUsersSupported();
        final int mainUserId = mUserManager.getMainUser().getIdentifier();
        UserInfo userInfo = createProfileForUser("Managed",
                UserManager.USER_TYPE_PROFILE_MANAGED, mainUserId, PACKAGES);
        // Verify that the packagesToVerify are not installed by default.
        for (String pkg : PACKAGES) {
            if (!mPackageManager.isPackageAvailable(pkg)) {
                Slog.w(TAG, "Package is not available " + pkg);
                continue;
            }

            assertWithMessage("Pkg should not be installed in managed profile when disallowed: %s",
                    pkg).that(isPackageInstalledForUser(pkg, userInfo.id)).isFalse();
        }

        // Verify that the disallowed packages during profile creation can be installed now.
        for (String pkg : PACKAGES) {
            if (!mPackageManager.isPackageAvailable(pkg)) {
                Slog.w(TAG, "Package is not available " + pkg);
                continue;
            }

            assertWithMessage("Package could not be installed: %s", pkg)
                    .that(mPackageManager.installExistingPackageAsUser(pkg, userInfo.id))
                    .isEqualTo(PackageManager.INSTALL_SUCCEEDED);
        }
    }

    // Make sure createUser would fail if we have DISALLOW_ADD_USER.
    @MediumTest
    @Test
    public void testCreateUser_disallowAddUser() throws Exception {
        final int creatorId = ActivityManager.getCurrentUser();
        final UserHandle creatorHandle = asHandle(creatorId);
        mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_USER, true, creatorHandle);
        try {
            UserInfo createadInfo = createUser("SecondaryUser", /*flags=*/ 0);
            assertThat(createadInfo).isNull();
        } finally {
            mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_USER, false,
                    creatorHandle);
        }
    }

    // Make sure createUser for ADMIN would fail if we have DISALLOW_GRANT_ADMIN.
    @MediumTest
    @Test
    @RequiresFlagsEnabled(android.multiuser.Flags.FLAG_UNICORN_MODE_REFACTORING_FOR_HSUM_READ_ONLY)
    public void testCreateAdminUser_disallowGrantAdmin() throws Exception {
        final int creatorId = ActivityManager.getCurrentUser();
        final UserHandle creatorHandle = asHandle(creatorId);
        mUserManager.setUserRestriction(UserManager.DISALLOW_GRANT_ADMIN, true, creatorHandle);
        try {
            UserInfo createdInfo = createUser("SecondaryUser", /*flags=*/ UserInfo.FLAG_ADMIN);
            assertThat(createdInfo).isNull();
        } finally {
            mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_USER, false,
                    creatorHandle);
        }
    }

    // Make sure createProfile would fail if we have DISALLOW_ADD_CLONE_PROFILE.
    @MediumTest
    @Test
    public void testCreateUser_disallowAddClonedUserProfile() throws Exception {
        final int mainUserId = ActivityManager.getCurrentUser();
        final UserHandle mainUserHandle = asHandle(mainUserId);
        mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_CLONE_PROFILE,
                true, mainUserHandle);
        try {
            UserInfo cloneProfileUserInfo = createProfileForUser("Clone",
                    UserManager.USER_TYPE_PROFILE_CLONE, mainUserId);
            assertThat(cloneProfileUserInfo).isNull();
        } finally {
            mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_CLONE_PROFILE, false,
                    mainUserHandle);
        }
    }

    // Make sure createProfile would fail if we have DISALLOW_ADD_MANAGED_PROFILE.
    @MediumTest
    @Test
    public void testCreateProfileForUser_disallowAddManagedProfile() throws Exception {
        assumeManagedUsersSupported();
        final int mainUserId = mUserManager.getMainUser().getIdentifier();
        final UserHandle currentUserHandle = asHandle(ActivityManager.getCurrentUser());
        mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_MANAGED_PROFILE, true,
                currentUserHandle);
        try {
            UserInfo userInfo = createProfileForUser("Managed",
                    UserManager.USER_TYPE_PROFILE_MANAGED, mainUserId);
            assertThat(userInfo).isNull();
        } finally {
            mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_MANAGED_PROFILE, false,
                    currentUserHandle);
        }
    }

    // Make sure createProfileEvenWhenDisallowedForUser bypass DISALLOW_ADD_MANAGED_PROFILE.
    @MediumTest
    @Test
    public void testCreateProfileForUserEvenWhenDisallowed() throws Exception {
        assumeManagedUsersSupported();
        final int mainUserId = mUserManager.getMainUser().getIdentifier();
        final UserHandle mainUserHandle = asHandle(mainUserId);
        mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_MANAGED_PROFILE, true,
                mainUserHandle);
        try {
            UserInfo userInfo = createProfileEvenWhenDisallowedForUser("Managed",
                    UserManager.USER_TYPE_PROFILE_MANAGED, mainUserId);
            assertThat(userInfo).isNotNull();
        } finally {
            mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_MANAGED_PROFILE, false,
                    mainUserHandle);
        }
    }

    // createProfile succeeds even if DISALLOW_ADD_USER is set
    @MediumTest
    @Test
    public void testCreateProfileForUser_disallowAddUser() throws Exception {
        assumeManagedUsersSupported();
        final int mainUserId = mUserManager.getMainUser().getIdentifier();
        final UserHandle mainUserHandle = asHandle(mainUserId);
        mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_USER, true, mainUserHandle);
        try {
            UserInfo userInfo = createProfileForUser("Managed",
                    UserManager.USER_TYPE_PROFILE_MANAGED, mainUserId);
            assertThat(userInfo).isNotNull();
        } finally {
            mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_USER, false,
                    mainUserHandle);
        }
    }

    // Make sure the creation of a private profile fails if DISALLOW_ADD_PRIVATE_PROFILE is true.
    @MediumTest
    @Test
    public void testCreateProfileForUser_disallowAddPrivateProfile() {
        final int mainUserId = ActivityManager.getCurrentUser();
        final UserHandle mainUserHandle = asHandle(mainUserId);
        mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_PRIVATE_PROFILE,
                true, mainUserHandle);
        try {
            UserInfo privateProfileInfo = createProfileForUser("Private",
                    UserManager.USER_TYPE_PROFILE_PRIVATE, mainUserId);
            assertThat(privateProfileInfo).isNull();
        } finally {
            mUserManager.setUserRestriction(UserManager.DISALLOW_ADD_PRIVATE_PROFILE, false,
                    mainUserHandle);
        }
    }

    @MediumTest
    @Test
    public void testPrivateProfileCreationRestrictions() {
        assumeTrue(mUserManager.canAddPrivateProfile());
        final int mainUserId = ActivityManager.getCurrentUser();
        try {
            UserInfo privateProfileInfo = createProfileForUser("Private",
                            UserManager.USER_TYPE_PROFILE_PRIVATE, mainUserId);
            assertThat(privateProfileInfo).isNotNull();
        } catch (Exception e) {
            fail("Creation of private profile failed due to " + e.getMessage());
        }
    }

    @MediumTest
    @Test
    public void testDefaultUserRestrictionsForPrivateProfile() {
        assumeTrue(mUserManager.canAddPrivateProfile());
        final int currentUserId = ActivityManager.getCurrentUser();
        UserInfo privateProfileInfo = null;
        try {
            privateProfileInfo = createProfileForUser("Private",
                    UserManager.USER_TYPE_PROFILE_PRIVATE, currentUserId);
            assertThat(privateProfileInfo).isNotNull();
        } catch (Exception e) {
            fail("Creation of private profile failed due to " + e.getMessage());
        }
        assertDefaultPrivateProfileRestrictions(privateProfileInfo.getUserHandle());
    }

    private void assertDefaultPrivateProfileRestrictions(UserHandle userHandle) {
        Bundle defaultPrivateProfileRestrictions =
                UserTypeFactory.getDefaultPrivateProfileRestrictions();
        for (String restriction : defaultPrivateProfileRestrictions.keySet()) {
            assertThat(mUserManager.hasUserRestrictionForUser(restriction, userHandle)).isTrue();
        }
    }

    @MediumTest
    @Test
    public void testAddRestrictedProfile() throws Exception {
        if (isAutomotive() || UserManager.isHeadlessSystemUserMode()) return;
        assertWithMessage("There should be no associated restricted profiles before the test")
                .that(mUserManager.hasRestrictedProfiles()).isFalse();
        UserInfo userInfo = createRestrictedProfile("Profile");
        assertThat(userInfo).isNotNull();

        Bundle restrictions = mUserManager.getUserRestrictions(UserHandle.of(userInfo.id));
        assertWithMessage(
                "Restricted profile should have DISALLOW_MODIFY_ACCOUNTS restriction by default")
                        .that(restrictions.getBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS))
                        .isTrue();
        assertWithMessage(
                "Restricted profile should have DISALLOW_SHARE_LOCATION restriction by default")
                        .that(restrictions.getBoolean(UserManager.DISALLOW_SHARE_LOCATION))
                        .isTrue();

        int locationMode = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_HIGH_ACCURACY,
                userInfo.id);
        assertWithMessage("Restricted profile should have setting LOCATION_MODE set to "
                + "LOCATION_MODE_OFF by default").that(locationMode)
                        .isEqualTo(Settings.Secure.LOCATION_MODE_OFF);

        assertWithMessage("Newly created profile should be associated with the current user")
                .that(mUserManager.hasRestrictedProfiles()).isTrue();
    }

    @MediumTest
    @Test
    public void testGetManagedProfileCreationTime() throws Exception {
        assumeManagedUsersSupported();
        assumeTrue("User does not have access to creation time", mUserManager.isMainUser());
        final int mainUserId = mUserManager.getMainUser().getIdentifier();
        final long startTime = System.currentTimeMillis();
        UserInfo profile = createProfileForUser("Managed 1",
                UserManager.USER_TYPE_PROFILE_MANAGED, mainUserId);
        final long endTime = System.currentTimeMillis();
        assertThat(profile).isNotNull();
        if (System.currentTimeMillis() > EPOCH_PLUS_30_YEARS) {
            assertWithMessage("creationTime must be set when the profile is created")
                    .that(profile.creationTime).isIn(Range.closed(startTime, endTime));
        } else {
            assertWithMessage("creationTime must be 0 if the time is not > EPOCH_PLUS_30_years")
                    .that(profile.creationTime).isEqualTo(0);
        }
        assertThat(mUserManager.getUserCreationTime(asHandle(profile.id)))
                .isEqualTo(profile.creationTime);

        long ownerCreationTime = mUserManager.getUserInfo(mainUserId).creationTime;
        assertThat(mUserManager.getUserCreationTime(asHandle(mainUserId)))
            .isEqualTo(ownerCreationTime);
    }

    @MediumTest
    @Test
    public void testGetUserCreationTime() throws Exception {
        long startTime = System.currentTimeMillis();
        UserInfo user = createUser("User", /* flags= */ 0);
        long endTime = System.currentTimeMillis();
        assertThat(user).isNotNull();
        assertWithMessage("creationTime must be set when the user is created")
            .that(user.creationTime).isIn(Range.closed(startTime, endTime));
    }

    @SmallTest
    @Test
    public void testGetUserCreationTime_nonExistentUser() throws Exception {
        int noSuchUserId = 100500;
        assertThrows(SecurityException.class,
                () -> mUserManager.getUserCreationTime(asHandle(noSuchUserId)));
    }

    @SmallTest
    @Test
    public void testGetUserCreationTime_otherUser() throws Exception {
        UserInfo user = createUser("User 1", 0);
        assertThat(user).isNotNull();
        assertThrows(SecurityException.class,
                () -> mUserManager.getUserCreationTime(asHandle(user.id)));
    }

    @Nullable
    private UserInfo getUser(int id) {
        List<UserInfo> list = mUserManager.getUsers();

        for (UserInfo user : list) {
            if (user.id == id) {
                return user;
            }
        }
        return null;
    }

    private boolean hasUser(int id) {
        return getUser(id) != null;
    }

    @MediumTest
    @Test
    public void testSerialNumber() {
        UserInfo user1 = createUser("User 1", 0);
        int serialNumber1 = user1.serialNumber;
        assertThat(mUserManager.getUserSerialNumber(user1.id)).isEqualTo(serialNumber1);
        assertThat(mUserManager.getUserHandle(serialNumber1)).isEqualTo(user1.id);
        UserInfo user2 = createUser("User 2", 0);
        int serialNumber2 = user2.serialNumber;
        assertThat(serialNumber1 == serialNumber2).isFalse();
        assertThat(mUserManager.getUserSerialNumber(user2.id)).isEqualTo(serialNumber2);
        assertThat(mUserManager.getUserHandle(serialNumber2)).isEqualTo(user2.id);
    }

    @MediumTest
    @Test
    public void testGetSerialNumbersOfUsers() {
        UserInfo user1 = createUser("User 1", 0);
        UserInfo user2 = createUser("User 2", 0);
        long[] serialNumbersOfUsers = mUserManager.getSerialNumbersOfUsers(false);
        assertThat(serialNumbersOfUsers).asList().containsAtLeast(
                (long) user1.serialNumber, (long) user2.serialNumber);
    }

    @MediumTest
    @Test
    public void testSerialNumberAfterUserRemoval() {
        final UserInfo user = mUserManager.createUser("Test User", 0);
        assertThat(user).isNotNull();

        final int userId = user.id;
        assertThat(mUserManager.getUserSerialNumber(userId))
            .isEqualTo(user.serialNumber);
        mUsersToRemove.add(userId);
        removeUser(userId);
        int serialNumber = mUserManager.getUserSerialNumber(userId);
        int timeout = REMOVE_USER_TIMEOUT_SECONDS * 5; // called every 200ms

        // Wait for the user to be removed from memory
        while(serialNumber > 0 && timeout > 0){
          sleep(200);
          timeout--;
          serialNumber = mUserManager.getUserSerialNumber(userId);
        }
        assertThat(serialNumber).isEqualTo(-1);
    }


    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    @MediumTest
    @Test
    public void testMaxUsers() {
        int N = UserManager.getMaxSupportedUsers();
        int count = mUserManager.getUsers().size();
        // Create as many users as permitted and make sure creation passes
        while (count < N) {
            UserInfo ui = createUser("User " + count, 0);
            assertThat(ui).isNotNull();
            count++;
        }
        // Try to create one more user and make sure it fails
        UserInfo extra = createUser("One more", 0);
        assertThat(extra).isNull();
    }

    @MediumTest
    @Test
    public void testGetUserCount() {
        int count = mUserManager.getUsers().size();
        UserInfo user1 = createUser("User 1", 0);
        assertThat(user1).isNotNull();
        UserInfo user2 = createUser("User 2", 0);
        assertThat(user2).isNotNull();
        assertThat(mUserManager.getUserCount()).isEqualTo(count + 2);
    }

    @MediumTest
    @Test
    public void testRestrictions() {
        UserInfo testUser = createUser("User 1", 0);

        mUserManager.setUserRestriction(
                UserManager.DISALLOW_INSTALL_APPS, true, asHandle(testUser.id));
        mUserManager.setUserRestriction(
                UserManager.DISALLOW_CONFIG_WIFI, false, asHandle(testUser.id));

        Bundle stored = mUserManager.getUserRestrictions(asHandle(testUser.id));
        // Note this will fail if DO already sets those restrictions.
        assertThat(stored.getBoolean(UserManager.DISALLOW_CONFIG_WIFI)).isFalse();
        assertThat(stored.getBoolean(UserManager.DISALLOW_UNINSTALL_APPS)).isFalse();
        assertThat(stored.getBoolean(UserManager.DISALLOW_INSTALL_APPS)).isTrue();
    }

    @MediumTest
    @Test
    public void testDefaultRestrictionsApplied() throws Exception {
        final UserInfo userInfo = createUser("Useroid", UserManager.USER_TYPE_FULL_SECONDARY, 0);
        final UserTypeDetails userTypeDetails =
                UserTypeFactory.getUserTypes().get(UserManager.USER_TYPE_FULL_SECONDARY);
        final Bundle expectedRestrictions = userTypeDetails.getDefaultRestrictions();
        // Note this can fail if DO unset those restrictions.
        for (String restriction : expectedRestrictions.keySet()) {
            if (expectedRestrictions.getBoolean(restriction)) {
                assertThat(mUserManager.hasUserRestriction(restriction, UserHandle.of(userInfo.id)))
                        .isTrue();
            }
        }
    }

    @MediumTest
    @Test
    public void testSetDefaultGuestRestrictions() {
        final Bundle origGuestRestrictions = mUserManager.getDefaultGuestRestrictions();
        Bundle restrictions = new Bundle();
        restrictions.putBoolean(UserManager.DISALLOW_FUN, true);
        mUserManager.setDefaultGuestRestrictions(restrictions);

        try {
            UserInfo guest = createUser("Guest", UserInfo.FLAG_GUEST);
            assertThat(guest).isNotNull();
            assertThat(mUserManager.hasUserRestriction(UserManager.DISALLOW_FUN,
                    guest.getUserHandle())).isTrue();
        } finally {
            mUserManager.setDefaultGuestRestrictions(origGuestRestrictions);
        }
    }

    @Test
    public void testGetUserSwitchability() {
        int userSwitchable = mUserManager.getUserSwitchability();
        assertWithMessage("Expected users to be switchable").that(userSwitchable)
                .isEqualTo(UserManager.SWITCHABILITY_STATUS_OK);
    }

    @LargeTest
    @Test
    public void testSwitchUser() {
        final int startUser = ActivityManager.getCurrentUser();
        UserInfo user = createUser("User", 0);
        assertThat(user).isNotNull();
        // Switch to the user just created.
        switchUser(user.id);
        // Switch back to the starting user.
        switchUser(startUser);
    }

    @LargeTest
    @Test
    public void testSwitchUserByHandle() {
        final int startUser = ActivityManager.getCurrentUser();
        UserInfo user = createUser("User", 0);
        assertThat(user).isNotNull();
        // Switch to the user just created.
        switchUser(user.getUserHandle());
        // Switch back to the starting user.
        switchUser(UserHandle.of(startUser));
    }

    @Test
    public void testSwitchUserByHandle_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> mActivityManager.switchUser(null));
    }

    @MediumTest
    @Test
    public void testConcurrentUserSwitch() {
        final int startUser = ActivityManager.getCurrentUser();
        final UserInfo user1 = createUser("User 1", 0);
        assertThat(user1).isNotNull();
        final UserInfo user2 = createUser("User 2", 0);
        assertThat(user2).isNotNull();
        final UserInfo user3 = createUser("User 3", 0);
        assertThat(user3).isNotNull();

        // Switch to the users just created without waiting for the completion of the previous one.
        switchUserThenRun(user1.id, () -> switchUserThenRun(user2.id, () -> switchUser(user3.id)));

        // Switch back to the starting user.
        switchUser(startUser);
    }

    @MediumTest
    @Test
    public void testConcurrentUserCreate() throws Exception {
        int userCount = mUserManager.getUsers().size();
        int maxSupportedUsers = UserManager.getMaxSupportedUsers();
        int canBeCreatedCount = maxSupportedUsers - userCount;
        // Test exceeding the limit while running in parallel
        int createUsersCount = canBeCreatedCount + 5;
        ExecutorService es = Executors.newCachedThreadPool();
        AtomicInteger created = new AtomicInteger();
        for (int i = 0; i < createUsersCount; i++) {
            final String userName = "testConcUser" + i;
            es.submit(() -> {
                UserInfo user = mUserManager.createUser(userName, 0);
                if (user != null) {
                    created.incrementAndGet();
                    mUsersToRemove.add(user.id);
                }
            });
        }
        es.shutdown();
        int timeout = createUsersCount * 20;
        assertWithMessage(
                "Could not create " + createUsersCount + " users in " + timeout + " seconds")
                .that(es.awaitTermination(timeout, TimeUnit.SECONDS))
                .isTrue();
        assertThat(mUserManager.getUsers().size()).isEqualTo(maxSupportedUsers);
        assertThat(created.get()).isEqualTo(canBeCreatedCount);
    }

    @MediumTest
    @Test
    public void testGetUserHandles_createNewUser_shouldFindNewUser() {
        UserInfo user = createUser("Guest 1", UserManager.USER_TYPE_FULL_GUEST, /*flags*/ 0);

        boolean found = false;
        List<UserHandle> userHandles = mUserManager.getUserHandles(/* excludeDying= */ true);
        for (UserHandle userHandle: userHandles) {
            if (userHandle.getIdentifier() == user.id) {
                found = true;
            }
        }

        assertThat(found).isTrue();
    }

    @Test
    public void testCreateProfile_withContextUserId() throws Exception {
        assumeManagedUsersSupported();
        final int mainUserId = mUserManager.getMainUser().getIdentifier();

        UserInfo userProfile = createProfileForUser("Managed 1",
                UserManager.USER_TYPE_PROFILE_MANAGED, mainUserId);
        assertThat(userProfile).isNotNull();

        UserManager um = (UserManager) mContext.createPackageContextAsUser(
                "android", 0, mUserManager.getMainUser())
                .getSystemService(Context.USER_SERVICE);

        List<UserHandle> profiles = um.getAllProfiles();
        assertThat(profiles.size()).isEqualTo(2);
        assertThat(profiles.get(0).equals(userProfile.getUserHandle())
                || profiles.get(1).equals(userProfile.getUserHandle())).isTrue();
    }

    @Test
    public void testSetUserName_withContextUserId() throws Exception {
        assumeManagedUsersSupported();
        final int mainUserId = mUserManager.getMainUser().getIdentifier();

        UserInfo userInfo1 = createProfileForUser("Managed 1",
                UserManager.USER_TYPE_PROFILE_MANAGED, mainUserId);
        assertThat(userInfo1).isNotNull();

        UserManager um = (UserManager) mContext.createPackageContextAsUser(
                "android", 0, userInfo1.getUserHandle())
                .getSystemService(Context.USER_SERVICE);

        final String newName = "Managed_user 1";
        um.setUserName(newName);

        UserInfo userInfo = mUserManager.getUserInfo(userInfo1.id);
        assertThat(userInfo.name).isEqualTo(newName);

        // get user name from getUserName using context.getUserId
        assertThat(um.getUserName()).isEqualTo(newName);
    }

    @Test
    public void testGetUserName_withContextUserId() throws Exception {
        final String userName = "User 2";
        UserInfo user2 = createUser(userName, 0);
        assertThat(user2).isNotNull();

        UserManager um = (UserManager) mContext.createPackageContextAsUser(
                "android", 0, user2.getUserHandle())
                .getSystemService(Context.USER_SERVICE);

        assertThat(um.getUserName()).isEqualTo(userName);
    }

    @Test
    public void testGetUserName_shouldReturnTranslatedTextForNullNamedGuestUser() throws Exception {
        UserInfo guestWithNullName = createUser(null, UserManager.USER_TYPE_FULL_GUEST, 0);
        assertThat(guestWithNullName).isNotNull();

        UserManager um = (UserManager) mContext.createPackageContextAsUser(
                "android", 0, guestWithNullName.getUserHandle())
                .getSystemService(Context.USER_SERVICE);

        assertThat(um.getUserName()).isEqualTo(
                mContext.getString(com.android.internal.R.string.guest_name));
    }

    @Test
    public void testGetUserIcon_withContextUserId() throws Exception {
        assumeManagedUsersSupported();
        final int mainUserId = mUserManager.getMainUser().getIdentifier();

        UserInfo userInfo1 = createProfileForUser("Managed 1",
                UserManager.USER_TYPE_PROFILE_MANAGED, mainUserId);
        assertThat(userInfo1).isNotNull();

        UserManager um = (UserManager) mContext.createPackageContextAsUser(
                "android", 0, userInfo1.getUserHandle())
                .getSystemService(Context.USER_SERVICE);

        final String newName = "Managed_user 1";
        um.setUserName(newName);

        UserInfo userInfo = mUserManager.getUserInfo(userInfo1.id);
        assertThat(userInfo.name).isEqualTo(newName);
    }

    @Test
    public void testCannotCreateAdditionalMainUser() {
        UserHandle mainUser = mUserManager.getMainUser();
        assumeTrue("There is no main user", mainUser != null);

        // Users with FLAG_MAIN can't be removed, so no point using the local createUser method.
        UserInfo newMainUser = mUserManager.createUser("test", UserInfo.FLAG_MAIN);
        assertThat(newMainUser).isNull();

        List<UserInfo> users = mUserManager.getUsers();
        int mainUserCount = 0;
        for (UserInfo user : users) {
            if (user.isMain()) {
                mainUserCount++;
            }
        }
        assertThat(mainUserCount).isEqualTo(1);
    }

    @Test
    public void testAddUserAccountData_validStringValuesAreSaved_validBundleIsSaved() {
        assumeManagedUsersSupported();

        String userName = "User";
        String accountName = "accountName";
        String accountType = "accountType";
        String arrayKey = "StringArrayKey";
        String stringKey = "StringKey";
        String intKey = "IntKey";
        String nestedBundleKey = "PersistableBundleKey";
        String value1 = "Value 1";
        String value2 = "Value 2";
        String value3 = "Value 3";

        UserInfo userInfo = mUserManager.createUser(userName,
                UserManager.USER_TYPE_FULL_SECONDARY, 0);

        PersistableBundle accountOptions = new PersistableBundle();
        String[] stringArray = {value1, value2};
        accountOptions.putInt(intKey, 1234);
        PersistableBundle nested = new PersistableBundle();
        nested.putString(stringKey, value3);
        accountOptions.putPersistableBundle(nestedBundleKey, nested);
        accountOptions.putStringArray(arrayKey, stringArray);

        mUserManager.clearSeedAccountData();
        mUserManager.setSeedAccountData(mContext.getUserId(), accountName,
                accountType, accountOptions);

        //assert userName accountName and accountType were saved correctly
        assertTrue(mUserManager.getUserInfo(userInfo.id).name.equals(userName));
        assertTrue(mUserManager.getSeedAccountName().equals(accountName));
        assertTrue(mUserManager.getSeedAccountType().equals(accountType));

        //assert bundle with correct values was added
        assertThat(mUserManager.getSeedAccountOptions().containsKey(arrayKey)).isTrue();
        assertThat(mUserManager.getSeedAccountOptions().getPersistableBundle(nestedBundleKey)
                .getString(stringKey)).isEqualTo(value3);
        assertThat(mUserManager.getSeedAccountOptions().getStringArray(arrayKey)[0])
                .isEqualTo(value1);

        mUserManager.removeUser(userInfo.id);
    }

    @Test
    public void testAddUserAccountData_invalidStringValuesAreTruncated_invalidBundleIsDropped() {
        assumeManagedUsersSupported();

        String tooLongString = generateLongString();
        String userName = "User " + tooLongString;
        String accountType = "Account Type " + tooLongString;
        String accountName = "accountName " + tooLongString;
        String arrayKey = "StringArrayKey";
        String stringKey = "StringKey";
        String intKey = "IntKey";
        String nestedBundleKey = "PersistableBundleKey";
        String value1 = "Value 1";
        String value2 = "Value 2";

        UserInfo userInfo = mUserManager.createUser(userName,
                UserManager.USER_TYPE_FULL_SECONDARY, 0);

        PersistableBundle accountOptions = new PersistableBundle();
        String[] stringArray = {value1, value2};
        accountOptions.putInt(intKey, 1234);
        PersistableBundle nested = new PersistableBundle();
        nested.putString(stringKey, tooLongString);
        accountOptions.putPersistableBundle(nestedBundleKey, nested);
        accountOptions.putStringArray(arrayKey, stringArray);
        mUserManager.clearSeedAccountData();
        mUserManager.setSeedAccountData(mContext.getUserId(), accountName,
                accountType, accountOptions);

        //assert userName was truncated
        assertTrue(mUserManager.getUserInfo(userInfo.id).name.length()
                == UserManager.MAX_USER_NAME_LENGTH);

        //assert accountName and accountType got truncated
        assertTrue(mUserManager.getSeedAccountName().length()
                == UserManager.MAX_ACCOUNT_STRING_LENGTH);
        assertTrue(mUserManager.getSeedAccountType().length()
                == UserManager.MAX_ACCOUNT_STRING_LENGTH);

        //assert bundle with invalid values was dropped
        assertThat(mUserManager.getSeedAccountOptions() == null).isTrue();

        mUserManager.removeUser(userInfo.id);
    }

    @Test
    @RequiresFlagsEnabled(android.multiuser.Flags.FLAG_ENABLE_HIDING_PROFILES)
    public void testGetProfileIdsExcludingHidden() throws Exception {
        int mainUserId = mUserManager.getMainUser().getIdentifier();
        final UserInfo profile = createProfileForUser("Profile",
                UserManager.USER_TYPE_PROFILE_PRIVATE, mainUserId);

        final int[] allProfiles = mUserManager.getProfileIds(mainUserId, /* enabledOnly */ false);
        final int[] profilesExcludingHidden = mUserManager.getProfileIdsExcludingHidden(
                mainUserId, /* enabledOnly */ false);

        assertThat(allProfiles).asList().contains(profile.id);
        assertThat(profilesExcludingHidden).asList().doesNotContain(profile.id);
    }

    /**
     * Test that UserManager.isQuietModeEnabled return false for unsupported
     * arguments such as UserHandle.NULL, UserHandle.CURRENT or UserHandle.ALL.
     **/
    @MediumTest
    @Test
    public void testQuietModeEnabledForUnsupportedUserHandles() throws Exception {
        assumeManagedUsersSupported();
        final int mainUserId = mUserManager.getMainUser().getIdentifier();
        UserInfo userInfo = createProfileForUser("Profile",
                UserManager.USER_TYPE_PROFILE_MANAGED, mainUserId);
        mUserManager.requestQuietModeEnabled(true, userInfo.getUserHandle());
        assertThat(mUserManager.isQuietModeEnabled(userInfo.getUserHandle())).isTrue();
        assertThat(mUserManager.isQuietModeEnabled(UserHandle.of(UserHandle.USER_NULL))).isFalse();
        assertThat(mUserManager.isQuietModeEnabled(UserHandle.CURRENT)).isFalse();
        assertThat(mUserManager.isQuietModeEnabled(UserHandle.CURRENT_OR_SELF)).isFalse();
        assertThat(mUserManager.isQuietModeEnabled(UserHandle.ALL)).isFalse();
    }

    private String generateLongString() {
        String partialString = "Test Name Test Name Test Name Test Name Test Name Test Name Test "
                + "Name Test Name Test Name Test Name "; //String of length 100
        StringBuilder resultString = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            resultString.append(partialString);
        }
        return resultString.toString();
    }

    private boolean isPackageInstalledForUser(String packageName, int userId) {
        try {
            return mPackageManager.getPackageInfoAsUser(packageName, 0, userId) != null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Starts the given user in the foreground. And waits for the user switch to be complete.
     **/
    private void switchUser(UserHandle user) {
        final int userId = user.getIdentifier();
        Slog.d(TAG, "Switching to user " + userId);

        mUserSwitchWaiter.runThenWaitUntilSwitchCompleted(userId, () -> {
            assertWithMessage("Could not start switching to user " + userId)
                    .that(mActivityManager.switchUser(user)).isTrue();
        }, /* onFail= */ () -> {
            throw new AssertionError("Could not complete switching to user " + userId);
        });
    }

    /**
     * Starts the given user in the foreground. And waits for the user switch to be complete.
     **/
    private void switchUser(int userId) {
        switchUserThenRun(userId, null);
    }

    /**
     * Starts the given user in the foreground. And runs the given Runnable right after
     * am.switchUser call, before waiting for the actual user switch to be complete.
     **/
    private void switchUserThenRun(int userId, Runnable runAfterSwitchBeforeWait) {
        Slog.d(TAG, "Switching to user " + userId);
        mUserSwitchWaiter.runThenWaitUntilSwitchCompleted(userId, () -> {
            // Start switching to user
            assertWithMessage("Could not start switching to user " + userId)
                    .that(mActivityManager.switchUser(userId)).isTrue();

            // While the user switch is happening, call runAfterSwitchBeforeWait.
            if (runAfterSwitchBeforeWait != null) {
                runAfterSwitchBeforeWait.run();
            }
        }, () -> fail("Could not complete switching to user " + userId));
    }

    private void removeUser(UserHandle userHandle) {
        mUserManager.removeUser(userHandle);
        waitForUserRemoval(userHandle.getIdentifier());
    }

    private void removeUser(int userId) {
        mUserManager.removeUser(userId);
        waitForUserRemoval(userId);
    }

    private void waitForUserRemoval(int userId) {
        mUserRemovalWaiter.waitFor(userId);
        mUsersToRemove.remove(userId);
    }

    private UserInfo createUser(String name, int flags) {
        UserInfo user = mUserManager.createUser(name, flags);
        if (user != null) {
            mUsersToRemove.add(user.id);
        }
        return user;
    }

    private UserInfo createUser(String name, String userType, int flags) {
        UserInfo user = mUserManager.createUser(name, userType, flags);
        if (user != null) {
            mUsersToRemove.add(user.id);
        }
        return user;
    }

    private UserInfo createProfileForUser(String name, String userType, int userHandle) {
        return createProfileForUser(name, userType, userHandle, null);
    }

    private UserInfo createProfileForUser(String name, String userType, int userHandle,
            String[] disallowedPackages) {
        UserInfo profile = mUserManager.createProfileForUser(
                name, userType, 0, userHandle, disallowedPackages);
        if (profile != null) {
            mUsersToRemove.add(profile.id);
        }
        return profile;
    }

    private UserInfo createProfileEvenWhenDisallowedForUser(String name, String userType,
            int userHandle) {
        UserInfo profile = mUserManager.createProfileForUserEvenWhenDisallowed(
                name, userType, 0, userHandle, null);
        if (profile != null) {
            mUsersToRemove.add(profile.id);
        }
        return profile;
    }

    private UserInfo createRestrictedProfile(String name) {
        UserInfo profile = mUserManager.createRestrictedProfile(name);
        if (profile != null) {
            mUsersToRemove.add(profile.id);
        }
        return profile;
    }

    private void assumeManagedUsersSupported() {
        // In Automotive, if headless system user is enabled, a managed user cannot be created
        // under a primary user.
        assumeTrue("device doesn't support managed users",
                mPackageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS)
                && (!isAutomotive() || !UserManager.isHeadlessSystemUserMode()));
    }

    private void assumeHeadlessModeEnabled() {
        // assume headless mode is enabled
        assumeTrue("Device doesn't have headless mode enabled",
                UserManager.isHeadlessSystemUserMode());
    }

    private void assumeCloneEnabled() {
        // assume clone profile is supported on the device
        assumeTrue("Device doesn't support clone profiles ",
                mUserManager.isUserTypeEnabled(UserManager.USER_TYPE_PROFILE_CLONE));
    }

    private boolean isAutomotive() {
        return mPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    private static UserHandle asHandle(int userId) {
        return new UserHandle(userId);
    }

    private boolean isMainUserPermanentAdmin() {
        return Resources.getSystem()
                .getBoolean(com.android.internal.R.bool.config_isMainUserPermanentAdmin);
    }

    private void compareDrawables(Drawable actual, Drawable expected){
        assertEquals(actual.getIntrinsicWidth(), expected.getIntrinsicWidth());
        assertEquals(actual.getIntrinsicHeight(), expected.getIntrinsicHeight());
        assertEquals(actual.getLevel(), expected.getLevel());
    }

    @Nullable
    private UserInfo getSupervisingProfile() {
        for (UserInfo user : mUserManager.getUsers()) {
            if (user.userType.equals(UserManager.USER_TYPE_PROFILE_SUPERVISING)) {
                return user;
            }
        }
        return null;
    }
}
