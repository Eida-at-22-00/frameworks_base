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

package com.android.systemui.statusbar.data.repository

import android.view.Display
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.data.model.StatusBarAppearance
import com.android.systemui.statusbar.data.model.StatusBarMode
import com.android.systemui.statusbar.phone.fragment.dagger.HomeStatusBarComponent
import dagger.Binds
import dagger.Module
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

@SysUISingleton
class FakeStatusBarModeRepository @Inject constructor() : StatusBarModeRepositoryStore {

    companion object {
        const val DISPLAY_ID = Display.DEFAULT_DISPLAY
    }

    private val perDisplayRepos = mutableMapOf<Int, FakeStatusBarModePerDisplayRepository>()

    override val defaultDisplay: FakeStatusBarModePerDisplayRepository = forDisplay(DISPLAY_ID)

    override fun forDisplay(displayId: Int): FakeStatusBarModePerDisplayRepository =
        perDisplayRepos.computeIfAbsent(displayId) { FakeStatusBarModePerDisplayRepository() }
}

class FakeStatusBarModePerDisplayRepository : StatusBarModePerDisplayRepository {
    override val isTransientShown = MutableStateFlow(false)
    override val isInFullscreenMode = MutableStateFlow(false)
    override val statusBarAppearance = MutableStateFlow<StatusBarAppearance?>(null)
    override val statusBarMode = MutableStateFlow(StatusBarMode.TRANSPARENT)
    override val ongoingProcessRequiresStatusBarVisible = MutableStateFlow(false)

    override fun showTransient() {
        isTransientShown.value = true
    }

    override fun clearTransient() {
        isTransientShown.value = false
    }

    override fun start() {}

    override fun stop() {}
    override fun setOngoingProcessRequiresStatusBarVisible(requiredVisible: Boolean) {
        ongoingProcessRequiresStatusBarVisible.value = requiredVisible
    }

    override fun onStatusBarViewInitialized(component: HomeStatusBarComponent) {}

    override fun dump(pw: PrintWriter, args: Array<out String>) {}
}

@Module
interface FakeStatusBarModeRepositoryModule {
    @Binds fun bindFake(fake: FakeStatusBarModeRepository): StatusBarModeRepositoryStore
}
