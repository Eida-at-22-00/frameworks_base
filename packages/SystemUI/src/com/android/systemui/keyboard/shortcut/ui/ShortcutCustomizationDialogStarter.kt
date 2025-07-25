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

package com.android.systemui.keyboard.shortcut.ui

import android.app.Dialog
import android.content.res.Resources
import android.view.WindowManager.LayoutParams.PRIVATE_FLAG_ALLOW_ACTION_KEY_EVENTS
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCustomizationRequestInfo
import com.android.systemui.keyboard.shortcut.ui.composable.ShortcutCustomizationDialog
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCustomizationUiState
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCustomizationUiState.AddShortcutDialog
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCustomizationUiState.DeleteShortcutDialog
import com.android.systemui.keyboard.shortcut.ui.model.ShortcutCustomizationUiState.ResetShortcutDialog
import com.android.systemui.keyboard.shortcut.ui.viewmodel.ShortcutCustomizationViewModel
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class ShortcutCustomizationDialogStarter
@AssistedInject
constructor(
    viewModelFactory: ShortcutCustomizationViewModel.Factory,
    private val dialogFactory: SystemUIDialogFactory,
    @Main private val resources: Resources,
) : ExclusiveActivatable() {

    private var dialog: Dialog? = null
    private val viewModel = viewModelFactory.create()

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch {
                viewModel.shortcutCustomizationUiState.collect { uiState ->
                    when (uiState) {
                        is AddShortcutDialog,
                        is DeleteShortcutDialog,
                        is ResetShortcutDialog -> {
                            if (dialog == null) {
                                dialog = createDialog().also { it.show() }
                            }
                        }
                        is ShortcutCustomizationUiState.Inactive -> {
                            dialog?.dismiss()
                            dialog = null
                        }
                    }
                }
            }
            launch { viewModel.activate() }
        }
        awaitCancellation()
    }

    fun onShortcutCustomizationRequested(requestInfo: ShortcutCustomizationRequestInfo) {
        viewModel.onShortcutCustomizationRequested(requestInfo)
    }

    private fun createDialog(): Dialog {
        return dialogFactory.create(dialogDelegate = ShortcutCustomizationDialogDelegate()) { dialog
            ->
            val uiState by viewModel.shortcutCustomizationUiState.collectAsStateWithLifecycle()
            val coroutineScope = rememberCoroutineScope()
            ShortcutCustomizationDialog(
                uiState = uiState,
                modifier = Modifier.width(364.dp).wrapContentHeight().padding(vertical = 24.dp),
                onShortcutKeyCombinationSelected = {
                    viewModel.onShortcutKeyCombinationSelected(it)
                },
                onCancel = { dialog.dismiss() },
                onConfirmSetShortcut = { coroutineScope.launch { viewModel.onSetShortcut() } },
                onConfirmDeleteShortcut = {
                    coroutineScope.launch { viewModel.deleteShortcutCurrentlyBeingCustomized() }
                },
                onConfirmResetShortcut = {
                    coroutineScope.launch { viewModel.resetAllCustomShortcuts() }
                },
                onClearSelectedKeyCombination = { viewModel.clearSelectedKeyCombination() },
            )
            setDialogProperties(dialog, uiState)
        }
    }

    private fun setDialogProperties(dialog: SystemUIDialog, uiState: ShortcutCustomizationUiState) {
        dialog.setOnDismissListener { viewModel.onDialogDismissed() }
        dialog.setTitle("${getDialogTitle(uiState)}. ${getDialogDescription(uiState)}")
        // By default, apps cannot intercept action key. The system always handles it. This
        // flag is needed to enable customisation dialog window to intercept action key
        dialog.window?.addPrivateFlags(PRIVATE_FLAG_ALLOW_ACTION_KEY_EVENTS)
    }

    private fun getDialogTitle(uiState: ShortcutCustomizationUiState): String {
        return when (uiState) {
            is AddShortcutDialog -> uiState.shortcutLabel
            is DeleteShortcutDialog ->
                resources.getString(R.string.shortcut_customize_mode_remove_shortcut_dialog_title)
            else ->
                resources.getString(R.string.shortcut_customize_mode_reset_shortcut_dialog_title)
        }
    }

    private fun getDialogDescription(uiState: ShortcutCustomizationUiState): String {
        return resources.getString(
            when (uiState) {
                is AddShortcutDialog -> R.string.shortcut_customize_mode_add_shortcut_description
                is DeleteShortcutDialog ->
                    R.string.shortcut_customize_mode_remove_shortcut_description
                else -> R.string.shortcut_customize_mode_reset_shortcut_description
            }
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(): ShortcutCustomizationDialogStarter
    }
}
