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

package com.android.server.appfunctions;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.UserHandle;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.infra.AndroidFuture;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface for validating that the caller has the correct privilege to call an AppFunctionManager
 * API.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public interface CallerValidator {
    // TODO(b/357551503): Should we verify NOT instant app?
    // TODO(b/357551503): Verify that user have been unlocked.

    /**
     * This method is used to validate that the calling package reported in the request is the same
     * as the binder calling identity.
     *
     * @param claimedCallingPackage The package name of the caller.
     * @return The package name of the caller.
     * @throws SecurityException if the package name and uid don't match.
     */
    String validateCallingPackage(@NonNull String claimedCallingPackage);

    /**
     * Validates that the caller can invoke an AppFunctionManager API in the provided target user
     * space.
     *
     * @param targetUserHandle The user which the caller is requesting to execute as.
     * @param claimedCallingPackage The package name of the caller.
     * @return The user handle that the call should run as. Will always be a concrete user.
     * @throws IllegalArgumentException if the target user is a special user.
     * @throws SecurityException if caller trying to interact across users without {@link
     *     Manifest.permission#INTERACT_ACROSS_USERS_FULL}
     */
    UserHandle verifyTargetUserHandle(
            @NonNull UserHandle targetUserHandle, @NonNull String claimedCallingPackage);

    /**
     * Validates that the caller can execute the specified app function.
     *
     * <p>The caller can execute if the app function's package name is the same as the caller's
     * package or the caller has the {@link Manifest.permission#EXECUTE_APP_FUNCTIONS} granted.
     *
     * @param callingUid The calling uid.
     * @param callingPid The calling pid.
     * @param targetUser The user which the caller is requesting to execute as.
     * @param callerPackageName The calling package (as previously validated).
     * @param targetPackageName The package that owns the app function to execute.
     * @param functionId The id of the app function to execute.
     * @return Whether the caller can execute the specified app function.
     */
    @CanExecuteAppFunctionResult
    AndroidFuture<Integer> verifyCallerCanExecuteAppFunction(
            int callingUid,
            int callingPid,
            @NonNull UserHandle targetUser,
            @NonNull String callerPackageName,
            @NonNull String targetPackageName,
            @NonNull String functionId);

    @IntDef(
            prefix = {"CAN_EXECUTE_APP_FUNCTIONS_"},
            value = {
                    CAN_EXECUTE_APP_FUNCTIONS_DENIED,
                    CAN_EXECUTE_APP_FUNCTIONS_ALLOWED_SAME_PACKAGE,
                    CAN_EXECUTE_APP_FUNCTIONS_ALLOWED_HAS_PERMISSION,
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface CanExecuteAppFunctionResult {}

    /** Callers are not allowed to execute app functions. */
    int CAN_EXECUTE_APP_FUNCTIONS_DENIED = 0;

    /**
     * Callers can execute app functions because they are calling app functions from the same
     * package.
     */
    int CAN_EXECUTE_APP_FUNCTIONS_ALLOWED_SAME_PACKAGE = 1;

    /**
     * Callers can execute app functions because they have the necessary permission.
     * This case also applies when a caller with the permission invokes their own app functions.
     */
    int CAN_EXECUTE_APP_FUNCTIONS_ALLOWED_HAS_PERMISSION = 2;

    /**
     * Checks if the app function policy is allowed.
     *
     * @param callingUser The current calling user.
     * @param targetUser The user which the caller is requesting to execute as.
     * @return Whether the app function policy is allowed.
     */
    boolean verifyEnterprisePolicyIsAllowed(
            @NonNull UserHandle callingUser, @NonNull UserHandle targetUser);
}
