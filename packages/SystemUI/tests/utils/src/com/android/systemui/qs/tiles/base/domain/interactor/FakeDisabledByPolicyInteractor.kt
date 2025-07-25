/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.base.domain.interactor

import android.os.UserHandle
import com.android.settingslib.RestrictedLockUtils

class FakeDisabledByPolicyInteractor : DisabledByPolicyInteractor {

    override suspend fun isDisabled(
        user: UserHandle,
        userRestriction: String?,
    ): DisabledByPolicyInteractor.PolicyResult =
        if (userRestriction == DISABLED_RESTRICTION || userRestriction == DISABLED_RESTRICTION_2) {
            DisabledByPolicyInteractor.PolicyResult.TileDisabled(
                RestrictedLockUtils.EnforcedAdmin()
            )
        } else {
            DisabledByPolicyInteractor.PolicyResult.TileEnabled
        }

    override fun handlePolicyResult(
        policyResult: DisabledByPolicyInteractor.PolicyResult
    ): Boolean =
        when (policyResult) {
            is DisabledByPolicyInteractor.PolicyResult.TileEnabled -> false
            is DisabledByPolicyInteractor.PolicyResult.TileDisabled -> true
        }

    companion object {
        const val DISABLED_RESTRICTION = "disabled_restriction"
        const val DISABLED_RESTRICTION_2 = "disabled_restriction_2"
        const val ENABLED_RESTRICTION = "test_restriction"
    }
}
