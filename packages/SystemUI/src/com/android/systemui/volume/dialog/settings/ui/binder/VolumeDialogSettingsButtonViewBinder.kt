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

package com.android.systemui.volume.dialog.settings.ui.binder

import android.view.View
import android.widget.ImageButton
import com.android.app.tracing.coroutines.launchInTraced
import com.android.app.tracing.coroutines.launchTraced
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.settings.ui.viewmodel.VolumeDialogSettingsButtonViewModel
import com.android.systemui.volume.dialog.ui.binder.ViewBinder
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.onEach

@VolumeDialogScope
class VolumeDialogSettingsButtonViewBinder
@Inject
constructor(
    private val viewModel: VolumeDialogSettingsButtonViewModel,
    private val dialogViewModel: VolumeDialogViewModel,
) : ViewBinder {

    override fun CoroutineScope.bind(view: View) {
        val button = view.requireViewById<ImageButton>(R.id.volume_dialog_settings)
        launchTraced("VDSBVB#addTouchableBounds") { dialogViewModel.addTouchableBounds(button) }
        viewModel.isVisible
            .onEach { isVisible -> button.visibility = if (isVisible) View.VISIBLE else View.GONE }
            .launchInTraced("VDSBVB#isVisible", this)

        viewModel.icon.onEach { button.setImageDrawable(it) }.launchInTraced("VDSBVB#icon", this)

        button.setOnClickListener { viewModel.onButtonClicked() }
    }
}
