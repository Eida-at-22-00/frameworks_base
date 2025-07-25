/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.user.data.repository

import android.app.admin.DevicePolicyManager
import android.app.admin.devicePolicyManager
import android.content.Intent
import android.content.applicationContext
import android.content.pm.UserInfo
import android.internal.statusbar.fakeStatusBarService
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.res.R
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.testKosmos
import com.android.systemui.user.data.model.SelectedUserModel
import com.android.systemui.user.data.model.SelectionStatus
import com.android.systemui.user.data.model.UserSwitcherSettingsModel
import com.android.systemui.util.settings.fakeGlobalSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
class UserRepositoryImplTest : SysuiTestCase() {

    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val testDispatcher = kosmos.testDispatcher
    private val testScope = kosmos.testScope
    private val globalSettings = kosmos.fakeGlobalSettings
    private val broadcastDispatcher = kosmos.broadcastDispatcher
    private val devicePolicyManager = kosmos.devicePolicyManager
    private val statusBarService = kosmos.fakeStatusBarService

    @Mock private lateinit var manager: UserManager

    private lateinit var underTest: UserRepositoryImpl

    private lateinit var tracker: FakeUserTracker

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        tracker = FakeUserTracker()
        setUserSwitchingMustGoThroughLoginScreen(false)
    }

    @Test
    fun userSwitcherSettings() =
        testScope.runTest {
            setUpGlobalSettings(
                isSimpleUserSwitcher = true,
                isAddUsersFromLockscreen = true,
                isUserSwitcherEnabled = true,
            )
            underTest = create(testScope.backgroundScope)
            var value: UserSwitcherSettingsModel? = null
            val job = underTest.userSwitcherSettings.onEach { value = it }.launchIn(this)

            assertUserSwitcherSettings(
                model = value,
                expectedSimpleUserSwitcher = true,
                expectedAddUsersFromLockscreen = true,
                expectedUserSwitcherEnabled = true,
            )

            setUpGlobalSettings(
                isSimpleUserSwitcher = false,
                isAddUsersFromLockscreen = true,
                isUserSwitcherEnabled = true,
            )
            assertUserSwitcherSettings(
                model = value,
                expectedSimpleUserSwitcher = false,
                expectedAddUsersFromLockscreen = true,
                expectedUserSwitcherEnabled = true,
            )
            job.cancel()
        }

    @Test
    fun userSwitcherSettings_isUserSwitcherEnabled_notInitialized() =
        testScope.runTest {
            underTest = create(testScope.backgroundScope)

            var value: UserSwitcherSettingsModel? = null
            val job = underTest.userSwitcherSettings.onEach { value = it }.launchIn(this)

            assertUserSwitcherSettings(
                model = value,
                expectedSimpleUserSwitcher = false,
                expectedAddUsersFromLockscreen = false,
                expectedUserSwitcherEnabled =
                    context.resources.getBoolean(
                        com.android.internal.R.bool.config_showUserSwitcherByDefault
                    ),
            )
            job.cancel()
        }

    @Test
    fun refreshUsers() =
        testScope.runTest {
            val mainUserId = 10
            val mainUser = mock(UserHandle::class.java)
            whenever(manager.mainUser).thenReturn(mainUser)
            whenever(mainUser.identifier).thenReturn(mainUserId)

            underTest = create(testScope.backgroundScope)
            val initialExpectedValue = setUpUsers(count = 3, selectedIndex = 0)
            var userInfos: List<UserInfo>? = null
            var selectedUserInfo: UserInfo? = null
            val job1 = underTest.userInfos.onEach { userInfos = it }.launchIn(this)
            val job2 = underTest.selectedUserInfo.onEach { selectedUserInfo = it }.launchIn(this)

            underTest.refreshUsers()
            assertThat(userInfos).isEqualTo(initialExpectedValue)
            assertThat(selectedUserInfo).isEqualTo(initialExpectedValue[0])
            assertThat(underTest.lastSelectedNonGuestUserId).isEqualTo(selectedUserInfo?.id)

            val secondExpectedValue = setUpUsers(count = 4, selectedIndex = 1)
            underTest.refreshUsers()
            assertThat(userInfos).isEqualTo(secondExpectedValue)
            assertThat(selectedUserInfo).isEqualTo(secondExpectedValue[1])
            assertThat(underTest.lastSelectedNonGuestUserId).isEqualTo(selectedUserInfo?.id)

            val selectedNonGuestUserId = selectedUserInfo?.id
            val thirdExpectedValue =
                setUpUsers(count = 2, isLastGuestUser = true, selectedIndex = 1)
            underTest.refreshUsers()
            assertThat(userInfos).isEqualTo(thirdExpectedValue)
            assertThat(selectedUserInfo).isEqualTo(thirdExpectedValue[1])
            assertThat(selectedUserInfo?.isGuest).isTrue()
            assertThat(underTest.lastSelectedNonGuestUserId).isEqualTo(selectedNonGuestUserId)
            assertThat(underTest.mainUserId).isEqualTo(mainUserId)
            job1.cancel()
            job2.cancel()
        }

    @Test
    fun userUnlockedFlow_tracksBroadcastedChanges() =
        testScope.runTest {
            val userHandle: UserHandle = mock()
            underTest = create(testScope.backgroundScope)
            val latest by collectLastValue(underTest.isUserUnlocked(userHandle))
            whenever(manager.isUserUnlocked(eq(userHandle))).thenReturn(false)
            broadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_USER_UNLOCKED),
            )

            assertThat(latest).isFalse()

            whenever(manager.isUserUnlocked(eq(userHandle))).thenReturn(true)
            broadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_USER_UNLOCKED),
            )

            assertThat(latest).isTrue()
        }

    @Test
    fun userUnlockedFlow_initialValueReported() =
        testScope.runTest {
            val userHandle: UserHandle = mock()
            underTest = create(testScope.backgroundScope)
            whenever(manager.isUserUnlocked(eq(userHandle))).thenReturn(true)
            val latest by collectLastValue(underTest.isUserUnlocked(userHandle))
            assertThat(latest).isTrue()
        }

    @Test
    fun refreshUsers_sortsByCreationTime_guestUserLast() =
        testScope.runTest {
            underTest = create(testScope.backgroundScope)
            val unsortedUsers = setUpUsers(count = 3, selectedIndex = 0, isLastGuestUser = true)
            unsortedUsers[0].creationTime = 999
            unsortedUsers[1].creationTime = 900
            unsortedUsers[2].creationTime = 950
            val expectedUsers =
                listOf(
                    unsortedUsers[1],
                    unsortedUsers[0],
                    unsortedUsers[2], // last because this is the guest
                )
            var userInfos: List<UserInfo>? = null
            val job = underTest.userInfos.onEach { userInfos = it }.launchIn(this)

            underTest.refreshUsers()
            assertThat(userInfos).isEqualTo(expectedUsers)
            job.cancel()
        }

    private fun setUpUsers(
        count: Int,
        isLastGuestUser: Boolean = false,
        selectedIndex: Int = 0,
    ): List<UserInfo> {
        val userInfos =
            (0 until count).map { index ->
                createUserInfo(index, isGuest = isLastGuestUser && index == count - 1)
            }
        whenever(manager.aliveUsers).thenReturn(userInfos)
        tracker.set(userInfos, selectedIndex)
        return userInfos
    }

    @Test
    fun userTrackerCallback_updatesSelectedUserInfo() =
        testScope.runTest {
            underTest = create(testScope.backgroundScope)
            var selectedUserInfo: UserInfo? = null
            val job = underTest.selectedUserInfo.onEach { selectedUserInfo = it }.launchIn(this)

            setUpUsers(count = 2, selectedIndex = 0)
            tracker.onProfileChanged()
            assertThat(selectedUserInfo?.id).isEqualTo(0)
            setUpUsers(count = 2, selectedIndex = 1)
            tracker.onProfileChanged()
            assertThat(selectedUserInfo?.id).isEqualTo(1)
            job.cancel()
        }

    @Test
    fun userTrackerCallback_updatesSelectionStatus() =
        testScope.runTest {
            underTest = create(testScope.backgroundScope)
            var selectedUser: SelectedUserModel? = null
            val job = underTest.selectedUser.onEach { selectedUser = it }.launchIn(this)

            setUpUsers(count = 2, selectedIndex = 1)

            // WHEN the user switch is starting
            tracker.onBeforeUserSwitching(userId = 1)

            // THEN the selection status is IN_PROGRESS
            assertThat(selectedUser!!.selectionStatus)
                .isEqualTo(SelectionStatus.SELECTION_IN_PROGRESS)

            // WHEN the user is changing
            tracker.onUserChanging(userId = 1)

            // THEN the selection status is still IN_PROGRESS
            assertThat(selectedUser!!.selectionStatus)
                .isEqualTo(SelectionStatus.SELECTION_IN_PROGRESS)

            // WHEN the user has finished changing
            tracker.onUserChanged(userId = 1)

            // THEN the selection status is COMPLETE
            assertThat(selectedUser!!.selectionStatus).isEqualTo(SelectionStatus.SELECTION_COMPLETE)

            tracker.onProfileChanged()
            assertThat(selectedUser!!.selectionStatus).isEqualTo(SelectionStatus.SELECTION_COMPLETE)

            setUpUsers(count = 2, selectedIndex = 0)

            tracker.onBeforeUserSwitching(userId = 0)
            tracker.onUserChanging(userId = 0)
            assertThat(selectedUser!!.selectionStatus)
                .isEqualTo(SelectionStatus.SELECTION_IN_PROGRESS)

            // WHEN a profile change occurs while a user is changing
            tracker.onProfileChanged()

            // THEN the selection status remains as IN_PROGRESS
            assertThat(selectedUser!!.selectionStatus)
                .isEqualTo(SelectionStatus.SELECTION_IN_PROGRESS)
            job.cancel()
        }

    @Test
    fun isSecondaryUserLogoutEnabled_secondaryLogoutDisabled_alwaysFalse() =
        testScope.runTest {
            underTest = create(testScope.backgroundScope)
            mockLogoutUser(LogoutUserResult.NON_SYSTEM_CURRENT)
            setSecondaryUserLogoutEnabled(false)
            setUpUsers(count = 2, selectedIndex = 0)
            tracker.onProfileChanged()

            val secondaryUserLogoutEnabled by
                collectLastValue(underTest.isSecondaryUserLogoutEnabled)

            assertThat(secondaryUserLogoutEnabled).isFalse()

            setUpUsers(count = 2, selectedIndex = 1)
            tracker.onProfileChanged()
            assertThat(secondaryUserLogoutEnabled).isFalse()
        }

    @Test
    fun isSecondaryUserLogoutEnabled_secondaryLogoutEnabled_NullLogoutUser_alwaysFalse() =
        testScope.runTest {
            underTest = create(testScope.backgroundScope)
            mockLogoutUser(LogoutUserResult.NONE)
            setSecondaryUserLogoutEnabled(true)
            setUpUsers(count = 2, selectedIndex = 0)
            tracker.onProfileChanged()

            val secondaryUserLogoutEnabled by
                collectLastValue(underTest.isSecondaryUserLogoutEnabled)

            assertThat(secondaryUserLogoutEnabled).isFalse()

            setUpUsers(count = 2, selectedIndex = 1)
            tracker.onProfileChanged()
            assertThat(secondaryUserLogoutEnabled).isFalse()
        }

    @Test
    fun isSecondaryUserLogoutEnabled_secondaryLogoutEnabled_NonSystemLogoutUser_trueWhenNonSystem() =
        testScope.runTest {
            underTest = create(testScope.backgroundScope)
            mockLogoutUser(LogoutUserResult.NON_SYSTEM_CURRENT)
            setSecondaryUserLogoutEnabled(true)
            setUpUsers(count = 2, selectedIndex = 0)
            tracker.onProfileChanged()

            val secondaryUserLogoutEnabled by
                collectLastValue(underTest.isSecondaryUserLogoutEnabled)

            assertThat(secondaryUserLogoutEnabled).isFalse()

            setUpUsers(count = 2, selectedIndex = 1)
            tracker.onProfileChanged()
            assertThat(secondaryUserLogoutEnabled).isTrue()
        }

    @Test
    fun isLogoutToSystemUserEnabled_logoutThroughLoginScreenDisabled_alwaysFalse() =
        testScope.runTest {
            underTest = create(testScope.backgroundScope)
            mockLogoutUser(LogoutUserResult.NON_SYSTEM_CURRENT)
            setUserSwitchingMustGoThroughLoginScreen(false)
            setUpUsers(count = 2, selectedIndex = 0)
            tracker.onProfileChanged()

            val logoutToSystemUserEnabled by collectLastValue(underTest.isLogoutToSystemUserEnabled)

            assertThat(logoutToSystemUserEnabled).isFalse()

            setUpUsers(count = 2, selectedIndex = 1)
            tracker.onProfileChanged()
            assertThat(logoutToSystemUserEnabled).isFalse()
        }

    @Test
    fun isLogoutToSystemUserEnabled_logoutThroughLoginScreenEnabled_NullLogoutUser_alwaysFalse() =
        testScope.runTest {
            underTest = create(testScope.backgroundScope)
            mockLogoutUser(LogoutUserResult.NONE)
            setUserSwitchingMustGoThroughLoginScreen(true)
            setUpUsers(count = 2, selectedIndex = 0)
            tracker.onProfileChanged()

            val logoutToSystemUserEnabled by collectLastValue(underTest.isLogoutToSystemUserEnabled)

            assertThat(logoutToSystemUserEnabled).isFalse()

            setUpUsers(count = 2, selectedIndex = 1)
            tracker.onProfileChanged()
            assertThat(logoutToSystemUserEnabled).isFalse()
        }

    @Test
    fun isLogoutToSystemUserEnabled_logoutThroughLoginScreenEnabled_NonSystemLogoutUser_trueWhenNonSystem() =
        testScope.runTest {
            underTest = create(testScope.backgroundScope)
            mockLogoutUser(LogoutUserResult.NON_SYSTEM_CURRENT)
            setUserSwitchingMustGoThroughLoginScreen(true)
            setUpUsers(count = 2, selectedIndex = 0)
            tracker.onProfileChanged()

            val logoutToSystemUserEnabled by collectLastValue(underTest.isLogoutToSystemUserEnabled)

            assertThat(logoutToSystemUserEnabled).isFalse()

            setUpUsers(count = 2, selectedIndex = 1)
            tracker.onProfileChanged()
            assertThat(logoutToSystemUserEnabled).isTrue()
        }

    private fun createUserInfo(id: Int, isGuest: Boolean): UserInfo {
        val flags = 0
        return UserInfo(
            id,
            "user_$id",
            /* iconPath= */ "",
            flags,
            if (isGuest) UserManager.USER_TYPE_FULL_GUEST else UserInfo.getDefaultUserType(flags),
        )
    }

    private fun setUpGlobalSettings(
        isSimpleUserSwitcher: Boolean = false,
        isAddUsersFromLockscreen: Boolean = false,
        isUserSwitcherEnabled: Boolean = true,
    ) {
        context.orCreateTestableResources.addOverride(
            com.android.internal.R.bool.config_expandLockScreenUserSwitcher,
            true,
        )
        globalSettings.putInt(
            UserRepositoryImpl.SETTING_SIMPLE_USER_SWITCHER,
            if (isSimpleUserSwitcher) 1 else 0,
        )
        globalSettings.putInt(
            Settings.Global.ADD_USERS_WHEN_LOCKED,
            if (isAddUsersFromLockscreen) 1 else 0,
        )
        globalSettings.putInt(
            Settings.Global.USER_SWITCHER_ENABLED,
            if (isUserSwitcherEnabled) 1 else 0,
        )
    }

    private fun assertUserSwitcherSettings(
        model: UserSwitcherSettingsModel?,
        expectedSimpleUserSwitcher: Boolean,
        expectedAddUsersFromLockscreen: Boolean,
        expectedUserSwitcherEnabled: Boolean,
    ) {
        checkNotNull(model)
        assertThat(model.isSimpleUserSwitcher).isEqualTo(expectedSimpleUserSwitcher)
        assertThat(model.isAddUsersFromLockscreen).isEqualTo(expectedAddUsersFromLockscreen)
        assertThat(model.isUserSwitcherEnabled).isEqualTo(expectedUserSwitcherEnabled)
    }

    private fun setSecondaryUserLogoutEnabled(enabled: Boolean) {
        whenever(devicePolicyManager.isLogoutEnabled).thenReturn(enabled)
        broadcastDispatcher.sendIntentToMatchingReceiversOnly(
            kosmos.applicationContext,
            Intent(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
        )
    }

    private fun setUserSwitchingMustGoThroughLoginScreen(enabled: Boolean) {
        context.orCreateTestableResources.addOverride(
            R.bool.config_userSwitchingMustGoThroughLoginScreen,
            enabled,
        )
    }

    private fun mockLogoutUser(result: LogoutUserResult) {
        when (result) {
            LogoutUserResult.NONE -> {
                whenever(devicePolicyManager.logoutUser).thenReturn(null)
            }
            LogoutUserResult.NON_SYSTEM_CURRENT -> {
                whenever(devicePolicyManager.logoutUser).thenAnswer {
                    if (tracker.userHandle != UserHandle.SYSTEM) {
                        tracker.userHandle
                    } else {
                        null
                    }
                }
            }
        }
    }

    private fun create(scope: CoroutineScope): UserRepositoryImpl {
        return UserRepositoryImpl(
            appContext = context,
            manager = manager,
            applicationScope = scope,
            mainDispatcher = testDispatcher,
            backgroundDispatcher = testDispatcher,
            globalSettings = globalSettings,
            tracker = tracker,
            broadcastDispatcher = broadcastDispatcher,
            devicePolicyManager = devicePolicyManager,
            resources = context.resources,
            statusBarService = statusBarService,
        )
    }

    companion object {
        @JvmStatic private val IMMEDIATE = Dispatchers.Main.immediate
    }

    private enum class LogoutUserResult {
        NONE,
        NON_SYSTEM_CURRENT,
    }
}
