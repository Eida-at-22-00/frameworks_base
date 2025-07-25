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

package com.android.systemui.statusbar.pipeline.mobile.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfig
import com.android.systemui.statusbar.pipeline.mobile.data.repository.CarrierConfigRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Business logic for [CarrierConfigRepository] */
@SysUISingleton
class CarrierConfigInteractor
@Inject
constructor(
    repo: CarrierConfigRepository,
    iconsInteractor: MobileIconsInteractor,
    @Application scope: CoroutineScope,
) {
    val defaultDataSubscriptionCarrierConfig: StateFlow<SystemUiCarrierConfig?> =
        iconsInteractor.defaultDataSubId
            .map { repo.getOrCreateConfigForSubId(it) }
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)
}
