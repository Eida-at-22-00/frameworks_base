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

package com.android.systemui.user.legacyhelper.data

import android.content.Context
import android.content.pm.UserInfo
import android.graphics.Bitmap
import android.os.UserManager
import com.android.settingslib.RestrictedLockUtils.EnforcedAdmin
import com.android.systemui.res.R
import com.android.systemui.user.data.source.UserRecord
import com.android.systemui.user.shared.model.UserActionModel
import com.android.systemui.utils.UserRestrictionChecker

/**
 * Defines utility functions for helping with legacy data code for users.
 *
 * We need these to avoid code duplication between logic inside the UserSwitcherController and in
 * modern architecture classes such as repositories, interactors, and view-models. If we ever
 * simplify UserSwitcherController (or delete it), the code here could be moved into its call-sites.
 */
object LegacyUserDataHelper {

    @JvmStatic
    fun createRecord(
        context: Context,
        manager: UserManager,
        picture: Bitmap?,
        userInfo: UserInfo,
        isCurrent: Boolean,
        canSwitchUsers: Boolean,
    ): UserRecord {
        val isGuest = userInfo.isGuest
        return UserRecord(
            info = userInfo,
            picture =
                getPicture(
                    manager = manager,
                    context = context,
                    userInfo = userInfo,
                    picture = picture,
                ),
            isGuest = isGuest,
            isCurrent = isCurrent,
            isSwitchToEnabled = canSwitchUsers || (isCurrent && !isGuest),
        )
    }

    @JvmStatic
    fun createRecord(
        context: Context,
        selectedUserId: Int,
        actionType: UserActionModel,
        isRestricted: Boolean,
        isSwitchToEnabled: Boolean,
        userRestrictionChecker: UserRestrictionChecker,
    ): UserRecord {
        return UserRecord(
            isGuest = actionType == UserActionModel.ENTER_GUEST_MODE,
            isAddUser = actionType == UserActionModel.ADD_USER,
            isAddSupervisedUser = actionType == UserActionModel.ADD_SUPERVISED_USER,
            isSignOut = actionType == UserActionModel.SIGN_OUT,
            isRestricted = isRestricted,
            isSwitchToEnabled = isSwitchToEnabled,
            enforcedAdmin =
                getEnforcedAdmin(
                    context = context,
                    selectedUserId = selectedUserId,
                    userRestrictionChecker = userRestrictionChecker,
                ),
            isManageUsers = actionType == UserActionModel.NAVIGATE_TO_USER_MANAGEMENT,
        )
    }

    fun toUserActionModel(record: UserRecord): UserActionModel {
        check(!isUser(record))

        return when {
            record.isAddUser -> UserActionModel.ADD_USER
            record.isAddSupervisedUser -> UserActionModel.ADD_SUPERVISED_USER
            record.isGuest -> UserActionModel.ENTER_GUEST_MODE
            record.isManageUsers -> UserActionModel.NAVIGATE_TO_USER_MANAGEMENT
            record.isSignOut -> UserActionModel.SIGN_OUT
            else -> error("Not a known action: $record")
        }
    }

    fun isUser(record: UserRecord): Boolean {
        return record.info != null
    }

    private fun getEnforcedAdmin(
        context: Context,
        selectedUserId: Int,
        userRestrictionChecker: UserRestrictionChecker,
    ): EnforcedAdmin? {
        val admin =
            userRestrictionChecker.checkIfRestrictionEnforced(
                context,
                UserManager.DISALLOW_ADD_USER,
                selectedUserId,
            ) ?: return null

        return if (
            !userRestrictionChecker.hasBaseUserRestriction(
                context,
                UserManager.DISALLOW_ADD_USER,
                selectedUserId,
            )
        ) {
            admin
        } else {
            null
        }
    }

    private fun getPicture(
        context: Context,
        manager: UserManager,
        userInfo: UserInfo,
        picture: Bitmap?,
    ): Bitmap? {
        if (userInfo.isGuest) {
            return null
        }

        if (picture != null) {
            return picture
        }

        val unscaledOrNull = manager.getUserIcon(userInfo.id) ?: return null

        val avatarSize = context.resources.getDimensionPixelSize(R.dimen.max_avatar_size)
        return Bitmap.createScaledBitmap(unscaledOrNull, avatarSize, avatarSize, /* filter= */ true)
    }
}
