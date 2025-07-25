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

package com.android.systemui.accessibility.data.repository

import com.android.systemui.dagger.SysUISingleton
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.MutableStateFlow

@SysUISingleton
class FakeAccessibilityRepository(
    override val isTouchExplorationEnabled: MutableStateFlow<Boolean>,
    override val isEnabled: MutableStateFlow<Boolean>,
) : AccessibilityRepository {
    @Inject constructor() : this(MutableStateFlow(false), MutableStateFlow(false))

    private var recommendedTimeout: Duration = 0.milliseconds

    fun setRecommendedTimeout(duration: Duration) {
        recommendedTimeout = duration
    }

    override fun getRecommendedTimeout(originalTimeout: Duration, uiFlags: Int): Duration {
        return recommendedTimeout
    }
}

@Module
interface FakeAccessibilityRepositoryModule {
    @Binds fun bindFake(fake: FakeAccessibilityRepository): AccessibilityRepository
}
