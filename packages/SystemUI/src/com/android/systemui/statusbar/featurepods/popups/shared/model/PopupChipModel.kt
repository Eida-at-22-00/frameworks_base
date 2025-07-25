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

package com.android.systemui.statusbar.featurepods.popups.shared.model

import com.android.systemui.common.shared.model.Icon

/**
 * Ids used to track different types of popup chips. Will be used to ensure only one chip is
 * displaying its popup at a time.
 */
sealed class PopupChipId(val value: String) {
    data object MediaControl : PopupChipId("MediaControl")
}

/** Defines the behavior of the chip when hovered over. */
sealed interface HoverBehavior {
    /** No specific hover behavior. The default icon will be shown. */
    data object None : HoverBehavior

    /**
     * Shows a button on hover with the given [icon] and executes [onIconPressed] when the icon is
     * pressed.
     */
    data class Button(val icon: Icon, val onIconPressed: () -> Unit) : HoverBehavior
}

/** Model for individual status bar popup chips. */
sealed class PopupChipModel {
    abstract val logName: String
    abstract val chipId: PopupChipId

    data class Hidden(override val chipId: PopupChipId, val shouldAnimate: Boolean = true) :
        PopupChipModel() {
        override val logName = "Hidden(id=$chipId, anim=$shouldAnimate)"
    }

    data class Shown(
        override val chipId: PopupChipId,
        /** Default icon displayed on the chip */
        val icon: Icon,
        val chipText: String,
        val isPopupShown: Boolean = false,
        val showPopup: () -> Unit = {},
        val hidePopup: () -> Unit = {},
        val hoverBehavior: HoverBehavior = HoverBehavior.None,
    ) : PopupChipModel() {
        override val logName = "Shown(id=$chipId, toggled=$isPopupShown)"
    }
}
