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

package com.android.systemui.statusbar.pipeline.mobile.data.repository

import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/**
 * Core startable which configures the [CarrierConfigRepository] to listen for updates for the
 * lifetime of the process
 */
class CarrierConfigCoreStartable
@Inject
constructor(
    private val carrierConfigRepository: CarrierConfigRepository,
    @Background private val scope: CoroutineScope,
) : CoreStartable {

    override fun start() {
        scope.launch { carrierConfigRepository.startObservingCarrierConfigUpdates() }
    }
}
