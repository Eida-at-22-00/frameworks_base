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

package com.android.systemui.wallpapers.data.repository

import android.content.applicationContext
import com.android.app.wallpaperManager
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.common.ui.domain.interactor.configurationInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.user.data.repository.userRepository
import com.android.systemui.util.settings.fakeSettings

val Kosmos.wallpaperRepository by Fixture {
    WallpaperRepositoryImpl(
        context = applicationContext,
        scope = testScope.backgroundScope,
        bgDispatcher = testDispatcher,
        broadcastDispatcher = broadcastDispatcher,
        userRepository = userRepository,
        wallpaperManager = wallpaperManager,
        secureSettings = fakeSettings,
        configurationInteractor = configurationInteractor,
    )
}
