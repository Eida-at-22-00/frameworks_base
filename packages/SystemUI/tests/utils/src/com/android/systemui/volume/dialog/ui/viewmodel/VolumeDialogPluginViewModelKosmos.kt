/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.volume.dialog.ui.viewmodel

import com.android.internal.logging.uiEventLogger
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.backgroundScope
import com.android.systemui.volume.dialog.domain.interactor.csdWarningConfigModel
import com.android.systemui.volume.dialog.domain.interactor.volumeDialogCsdWarningInteractor
import com.android.systemui.volume.dialog.domain.interactor.volumeDialogSafetyWarningInteractor
import com.android.systemui.volume.dialog.domain.interactor.volumeDialogVisibilityInteractor
import com.android.systemui.volume.dialog.shared.volumeDialogLogger
import com.android.systemui.volume.dialog.volumeDialog

val Kosmos.volumeDialogPluginViewModel: VolumeDialogPluginViewModel by
    Kosmos.Fixture {
        VolumeDialogPluginViewModel(
            backgroundScope,
            volumeDialogVisibilityInteractor,
            volumeDialogSafetyWarningInteractor,
            volumeDialogCsdWarningInteractor,
            { volumeDialog },
            volumeDialogLogger,
            csdWarningConfigModel,
            uiEventLogger,
        )
    }
