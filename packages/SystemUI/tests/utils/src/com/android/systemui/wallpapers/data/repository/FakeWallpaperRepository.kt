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

package com.android.systemui.wallpapers.data.repository

import android.app.WallpaperInfo
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fake implementation of the wallpaper repository. */
class FakeWallpaperRepository : WallpaperRepository {
    private val _wallpaperInfo: MutableStateFlow<WallpaperInfo?> = MutableStateFlow(null)
    override val wallpaperInfo: StateFlow<WallpaperInfo?> = _wallpaperInfo.asStateFlow()
    private val _lockscreenWallpaperInfo: MutableStateFlow<WallpaperInfo?> = MutableStateFlow(null)
    override val lockscreenWallpaperInfo: StateFlow<WallpaperInfo?> =
        _lockscreenWallpaperInfo.asStateFlow()
    private val _wallpaperSupportsAmbientMode = MutableStateFlow(false)
    override val wallpaperSupportsAmbientMode: Flow<Boolean> =
        _wallpaperSupportsAmbientMode.asStateFlow()
    override var rootView: View? = null
    private val _shouldSendFocalArea = MutableStateFlow(false)
    override val shouldSendFocalArea: StateFlow<Boolean> = _shouldSendFocalArea.asStateFlow()

    override fun sendLockScreenLayoutChangeCommand(wallpaperFocalAreaBounds: RectF) {}

    override fun sendTapCommand(tapPosition: PointF) {}

    fun setWallpaperInfo(wallpaperInfo: WallpaperInfo?) {
        _wallpaperInfo.value = wallpaperInfo
    }
}
