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

package com.android.settingslib.users;

import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

/**
 * Defines a callback when a new user data is filled out.
 */
public interface NewUserData {

    /**
     * Consumes data relevant to new user that needs to be created.
     * @param userName New user name.
     * @param userImage New user icon.
     * @param iconPath New user icon path.
     * @param isNewUserAdmin A boolean that indicated whether new user has admin status.
     */
    void onSuccess(@Nullable String userName, @Nullable Drawable userImage,
            @Nullable String iconPath, @Nullable Boolean isNewUserAdmin);

}
