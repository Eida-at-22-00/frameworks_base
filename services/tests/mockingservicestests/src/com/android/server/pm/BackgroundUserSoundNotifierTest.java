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

package com.android.server.pm;

import static android.media.AudioAttributes.USAGE_ALARM;

import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.testng.AssertJUnit.assertEquals;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.PlayerProxy;
import android.media.audiopolicy.AudioPolicy;
import android.multiuser.Flags;
import android.os.Build;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.ArraySet;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

@RunWith(JUnit4.class)
public class BackgroundUserSoundNotifierTest {
    private final Context mRealContext = androidx.test.InstrumentationRegistry.getInstrumentation()
            .getTargetContext();
    private Context mSpiedContext;
    private BackgroundUserSoundNotifier mBackgroundUserSoundNotifier;

    private UserManager mUserManager;
    private ArraySet<Integer> mUsersToRemove;

    @Mock
    private NotificationManager mNotificationManager;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mSpiedContext = spy(mRealContext);
        mUsersToRemove = new ArraySet<>();

        mUserManager = spy(mSpiedContext.getSystemService(UserManager.class));
        doReturn(mUserManager)
                .when(mSpiedContext).getSystemService(UserManager.class);
        doReturn(mNotificationManager)
                .when(mSpiedContext).getSystemService(NotificationManager.class);
        mBackgroundUserSoundNotifier = new BackgroundUserSoundNotifier(mSpiedContext);
    }

    @After
    public void tearDown() throws Exception {
        mUsersToRemove.stream().toList().forEach(this::removeUser);
    }
    @Test
    public void testAlarmOnBackgroundUser_foregroundUserNotified() throws RemoteException {
        assumeTrue(UserManager.supportsMultipleUsers());
        AudioAttributes aa = new AudioAttributes.Builder().setUsage(USAGE_ALARM).build();
        UserInfo user = createUser("User", UserManager.USER_TYPE_FULL_SECONDARY, 0);

        final int fgUserId = mSpiedContext.getUserId();
        final int bgUserUid = user.id * 100000;
        doReturn(UserHandle.of(fgUserId)).when(mSpiedContext).getUser();
        AudioFocusInfo afi = new AudioFocusInfo(aa, bgUserUid, "",
                /* packageName= */ "com.android.car.audio", AudioManager.AUDIOFOCUS_GAIN,
                AudioManager.AUDIOFOCUS_NONE, /* flags= */ 0, Build.VERSION.SDK_INT);
        clearInvocations(mNotificationManager);
        mBackgroundUserSoundNotifier.notifyForegroundUserAboutSoundIfNecessary(afi);
        verify(mNotificationManager)
                .notifyAsUser(eq(BackgroundUserSoundNotifier.class.getSimpleName()),
                        eq(afi.getClientUid()), any(Notification.class),
                        eq(UserHandle.of(fgUserId)));
    }

    @Test
    public void testMediaOnBackgroundUser_foregroundUserNotNotified() throws RemoteException {
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).build();
        final int bgUserUid = mSpiedContext.getUserId() * 100000;
        AudioFocusInfo afi = new AudioFocusInfo(aa, bgUserUid, "",
                /* packageName= */ "com.android.car.audio", AudioManager.AUDIOFOCUS_GAIN,
                AudioManager.AUDIOFOCUS_NONE, /* flags= */ 0, Build.VERSION.SDK_INT);
        clearInvocations(mNotificationManager);
        mBackgroundUserSoundNotifier.notifyForegroundUserAboutSoundIfNecessary(afi);
        verifyNoMoreInteractions(mNotificationManager);
    }

    @Test
    public void testAlarmOnForegroundUser_foregroundUserNotNotified() throws RemoteException {
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(USAGE_ALARM).build();
        final int fgUserId = mSpiedContext.getUserId();
        final int fgUserUid = fgUserId * 100000;
        doReturn(UserHandle.of(fgUserId)).when(mSpiedContext).getUser();
        AudioFocusInfo afi = new AudioFocusInfo(aa, fgUserUid, "", /* packageName= */ "",
                AudioManager.AUDIOFOCUS_GAIN, AudioManager.AUDIOFOCUS_NONE, /* flags= */ 0,
                Build.VERSION.SDK_INT);
        clearInvocations(mNotificationManager);
        mBackgroundUserSoundNotifier.notifyForegroundUserAboutSoundIfNecessary(afi);
        verifyNoMoreInteractions(mNotificationManager);
    }

    @Test
    public void testMuteAlarmSounds() {
        final int fgUserId = mSpiedContext.getUserId();
        int bgUserId = fgUserId + 1;
        int bgUserUid = bgUserId * 100000;
        if (Flags.multipleAlarmNotificationsSupport()) {
            mBackgroundUserSoundNotifier.mNotificationClientUids.add(bgUserUid);
        } else {
            mBackgroundUserSoundNotifier.mNotificationClientUid = bgUserUid;
        }
        AudioManager mockAudioManager = mock(AudioManager.class);
        when(mSpiedContext.getSystemService(AudioManager.class)).thenReturn(mockAudioManager);

        AudioPlaybackConfiguration apc1 = mock(AudioPlaybackConfiguration.class);
        when(apc1.getClientUid()).thenReturn(bgUserUid);
        when(apc1.getPlayerProxy()).thenReturn(mock(PlayerProxy.class));

        AudioPlaybackConfiguration apc2 = mock(AudioPlaybackConfiguration.class);
        when(apc2.getClientUid()).thenReturn(bgUserUid + 1);
        when(apc2.getPlayerProxy()).thenReturn(mock(PlayerProxy.class));

        List<AudioPlaybackConfiguration> configs = new ArrayList<>();
        configs.add(apc1);
        configs.add(apc2);
        when(mockAudioManager.getActivePlaybackConfigurations()).thenReturn(configs);

        AudioPolicy mockAudioPolicy = mock(AudioPolicy.class);

        AudioAttributes aa = new AudioAttributes.Builder().setUsage(USAGE_ALARM).build();
        AudioFocusInfo afi = new AudioFocusInfo(aa, bgUserUid, "", /* packageName= */ "",
                AudioManager.AUDIOFOCUS_GAIN, AudioManager.AUDIOFOCUS_NONE, /* flags= */ 0,
                Build.VERSION.SDK_INT);
        Stack<AudioFocusInfo> focusStack = new Stack<>();
        focusStack.add(afi);
        doReturn(focusStack).when(mockAudioPolicy).getFocusStack();
        mBackgroundUserSoundNotifier.mFocusControlAudioPolicy = mockAudioPolicy;

        mBackgroundUserSoundNotifier.muteAlarmSounds(bgUserUid);

        verify(apc1.getPlayerProxy()).stop();
        verify(mockAudioPolicy).sendFocusLossAndUpdate(afi);
        verify(apc2.getPlayerProxy(), never()).stop();
    }

    @Test
    public void testOnAudioFocusGrant_alarmOnBackgroundUser_notifiesForegroundUser() {
        assumeTrue(UserManager.supportsMultipleUsers());
        final int fgUserId = mSpiedContext.getUserId();
        UserInfo bgUser = createUser("Background User",  UserManager.USER_TYPE_FULL_SECONDARY, 0);
        int bgUserUid = bgUser.id * 100000;

        AudioAttributes aa = new AudioAttributes.Builder().setUsage(USAGE_ALARM).build();
        AudioFocusInfo afi = new AudioFocusInfo(aa, bgUserUid, "", "",
                AudioManager.AUDIOFOCUS_GAIN, 0, 0, Build.VERSION.SDK_INT);

        mBackgroundUserSoundNotifier.getAudioPolicyFocusListener()
                .onAudioFocusGrant(afi, AudioManager.AUDIOFOCUS_REQUEST_GRANTED);

        verify(mNotificationManager)
                .notifyAsUser(eq(BackgroundUserSoundNotifier.class.getSimpleName()),
                        eq(afi.getClientUid()), any(Notification.class),
                        eq(UserHandle.of(fgUserId)));
    }

    @Test
    public void testOnAudioFocusGrant_alarmOnProfileOfForegroundUser_foregroundUserNotNotified() {
        assumeTrue(UserManager.supportsMultipleUsers());
        final int fgUserId = mSpiedContext.getUserId();
        UserInfo fgUserProfile = createProfileForUser("Background profile",
                UserManager.USER_TYPE_PROFILE_MANAGED, fgUserId, null);
        assumeTrue("Cannot add a profile", fgUserProfile != null);
        int fgUserProfileUid = fgUserProfile.id * 100_000;

        AudioAttributes aa = new AudioAttributes.Builder().setUsage(USAGE_ALARM).build();
        AudioFocusInfo afi = new AudioFocusInfo(aa, fgUserProfileUid, "", "",
                AudioManager.AUDIOFOCUS_GAIN, 0, 0, Build.VERSION.SDK_INT);

        mBackgroundUserSoundNotifier.getAudioPolicyFocusListener()
                .onAudioFocusGrant(afi, AudioManager.AUDIOFOCUS_REQUEST_GRANTED);

        verify(mNotificationManager, never())
                .notifyAsUser(eq(BackgroundUserSoundNotifier.class.getSimpleName()),
                        eq(afi.getClientUid()), any(Notification.class),
                        eq(UserHandle.of(fgUserId)));
    }


    @Test
    public void testCreateNotification_UserSwitcherEnabled_bothActionsAvailable() {
        String userName = "BgUser";

        doReturn(true).when(mUserManager).isUserSwitcherEnabled();
        doReturn(UserManager.SWITCHABILITY_STATUS_OK)
                .when(mUserManager).getUserSwitchability(any());

        Notification notification = mBackgroundUserSoundNotifier.createNotification(userName,
                mSpiedContext, 101000);

        assertEquals("Alarm for BgUser", notification.extras.getString(
                Notification.EXTRA_TITLE));
        assertEquals(Notification.CATEGORY_REMINDER, notification.category);
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility);
        assertEquals(com.android.internal.R.drawable.ic_audio_alarm,
                notification.getSmallIcon().getResId());

        assertEquals(2, notification.actions.length);
        assertEquals(mSpiedContext.getString(
                com.android.internal.R.string.bg_user_sound_notification_button_mute),
                notification.actions[0].title);
        assertEquals(mSpiedContext.getString(
                com.android.internal.R.string.bg_user_sound_notification_button_switch_user),
                notification.actions[1].title);
    }

    @Test
    public void testCreateNotification_UserSwitcherDisabled_onlyMuteActionAvailable() {
        String userName = "BgUser";

        doReturn(false).when(mUserManager).isUserSwitcherEnabled();
        doReturn(UserManager.SWITCHABILITY_STATUS_USER_SWITCH_DISALLOWED)
                .when(mUserManager).getUserSwitchability(any());

        Notification notification = mBackgroundUserSoundNotifier.createNotification(userName,
                mSpiedContext, 101000);

        assertEquals(1, notification.actions.length);
        assertEquals(mSpiedContext.getString(
                com.android.internal.R.string.bg_user_sound_notification_button_mute),
                notification.actions[0].title);
    }

    @RequiresFlagsEnabled({Flags.FLAG_MULTIPLE_ALARM_NOTIFICATIONS_SUPPORT})
    @Test
    public void testMultipleAlarmsSameUid_OneNotificationCreated() throws RemoteException {
        assumeTrue(UserManager.supportsMultipleUsers());
        UserInfo user = createUser("User", UserManager.USER_TYPE_FULL_SECONDARY, 0);
        final int fgUserId = mSpiedContext.getUserId();
        final int bgUserUid = user.id * 100000;
        doReturn(UserHandle.of(fgUserId)).when(mSpiedContext).getUser();

        AudioAttributes aa = new AudioAttributes.Builder().setUsage(USAGE_ALARM).build();
        AudioFocusInfo afi1 = new AudioFocusInfo(aa, bgUserUid, "",
                /* packageName= */ "com.android.car.audio", AudioManager.AUDIOFOCUS_GAIN,
                AudioManager.AUDIOFOCUS_NONE, /* flags= */ 0, Build.VERSION.SDK_INT);

        mBackgroundUserSoundNotifier.notifyForegroundUserAboutSoundIfNecessary(afi1);
        verify(mNotificationManager)
                .notifyAsUser(eq(BackgroundUserSoundNotifier.class.getSimpleName()),
                        eq(afi1.getClientUid()), any(Notification.class),
                        eq(UserHandle.of(fgUserId)));

        AudioFocusInfo afi2 = new AudioFocusInfo(aa, bgUserUid, "",
                /* packageName= */ "com.android.car.audio", AudioManager.AUDIOFOCUS_GAIN,
                AudioManager.AUDIOFOCUS_NONE, /* flags= */ 0, Build.VERSION.SDK_INT);
        clearInvocations(mNotificationManager);
        mBackgroundUserSoundNotifier.notifyForegroundUserAboutSoundIfNecessary(afi2);
        verify(mNotificationManager, never())
                .notifyAsUser(eq(BackgroundUserSoundNotifier.class.getSimpleName()),
                        eq(afi2.getClientUid()), any(Notification.class),
                        eq(UserHandle.of(fgUserId)));
    }

    @RequiresFlagsEnabled({Flags.FLAG_MULTIPLE_ALARM_NOTIFICATIONS_SUPPORT})
    @Test
    public void testMultipleAlarmsDifferentUsers_multipleNotificationsCreated()
            throws RemoteException {
        assumeTrue(UserManager.supportsMultipleUsers());
        UserInfo user1 = createUser("User1", UserManager.USER_TYPE_FULL_SECONDARY, 0);
        UserInfo user2 = createUser("User2", UserManager.USER_TYPE_FULL_SECONDARY, 0);
        final int fgUserId = mSpiedContext.getUserId();
        final int bgUserUid1 = user1.id * 100000;
        final int bgUserUid2 = user2.id * 100000;
        doReturn(UserHandle.of(fgUserId)).when(mSpiedContext).getUser();

        AudioAttributes aa = new AudioAttributes.Builder().setUsage(USAGE_ALARM).build();
        AudioFocusInfo afi1 = new AudioFocusInfo(aa, bgUserUid1, "",
                /* packageName= */ "com.android.car.audio", AudioManager.AUDIOFOCUS_GAIN,
                AudioManager.AUDIOFOCUS_NONE, /* flags= */ 0, Build.VERSION.SDK_INT);

        mBackgroundUserSoundNotifier.notifyForegroundUserAboutSoundIfNecessary(afi1);
        verify(mNotificationManager)
                .notifyAsUser(eq(BackgroundUserSoundNotifier.class.getSimpleName()),
                        eq(afi1.getClientUid()), any(Notification.class),
                        eq(UserHandle.of(fgUserId)));

        AudioFocusInfo afi2 = new AudioFocusInfo(aa, bgUserUid2, "",
                /* packageName= */ "com.android.car.audio", AudioManager.AUDIOFOCUS_GAIN,
                AudioManager.AUDIOFOCUS_NONE, /* flags= */ 0, Build.VERSION.SDK_INT);
        clearInvocations(mNotificationManager);
        mBackgroundUserSoundNotifier.notifyForegroundUserAboutSoundIfNecessary(afi2);
        verify(mNotificationManager)
                .notifyAsUser(eq(BackgroundUserSoundNotifier.class.getSimpleName()),
                        eq(afi2.getClientUid()), any(Notification.class),
                        eq(UserHandle.of(fgUserId)));
    }


    private UserInfo createUser(String name, String userType, int flags) {
        UserInfo user = mUserManager.createUser(name, userType, flags);
        if (user != null) {
            mUsersToRemove.add(user.id);
        }
        return user;
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

    private void removeUser(int userId) {
        mUserManager.removeUser(userId);
    }

}
