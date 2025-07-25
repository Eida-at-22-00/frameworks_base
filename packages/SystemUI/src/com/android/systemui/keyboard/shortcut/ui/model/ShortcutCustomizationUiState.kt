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

package com.android.systemui.keyboard.shortcut.ui.model

import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey

sealed interface ShortcutCustomizationUiState {
    data class AddShortcutDialog(
        val shortcutLabel: String,
        val errorMessage: String = "",
        val defaultCustomShortcutModifierKey: ShortcutKey.Icon.ResIdIcon,
        val pressedKeys: List<ShortcutKey> = emptyList(),
        val pressedKeysDescription: String = "",
    ) : ShortcutCustomizationUiState

    data object DeleteShortcutDialog : ShortcutCustomizationUiState

    data object ResetShortcutDialog : ShortcutCustomizationUiState

    data object Inactive : ShortcutCustomizationUiState
}
