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

package com.android.systemui.biometrics.ui.viewmodel

import android.content.applicationContext
import android.view.accessibility.accessibilityManager
import com.android.app.activityTaskManager
import com.android.launcher3.icons.IconProvider
import com.android.systemui.biometrics.domain.interactor.biometricStatusInteractor
import com.android.systemui.biometrics.domain.interactor.displayStateInteractor
import com.android.systemui.biometrics.domain.interactor.promptSelectorInteractor
import com.android.systemui.biometrics.domain.interactor.udfpsOverlayInteractor
import com.android.systemui.biometrics.udfpsUtils
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.util.mockito.mock

val Kosmos.promptViewModel by Fixture {
    PromptViewModel(
        displayStateInteractor = displayStateInteractor,
        promptSelectorInteractor = promptSelectorInteractor,
        context = applicationContext,
        udfpsOverlayInteractor = udfpsOverlayInteractor,
        biometricStatusInteractor = biometricStatusInteractor,
        udfpsUtils = udfpsUtils,
        iconProvider = iconProvider,
        activityTaskManager = activityTaskManager,
        accessibilityManager = accessibilityManager,
    )
}

val Kosmos.iconProvider by Fixture { mock<IconProvider>() }
