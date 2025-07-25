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

package com.android.systemui.statusbar.notification.stack.shared.model

import androidx.compose.ui.geometry.Rect

/** Models the bounds of the notification stack. */
data class ShadeScrimBounds(
    /** The position of the left of the stack in its window coordinate system, in pixels. */
    val left: Float = 0f,
    /** The position of the top of the stack in its window coordinate system, in pixels. */
    val top: Float = 0f,
    /** The position of the right of the stack in its window coordinate system, in pixels. */
    val right: Float = 0f,
    /** The position of the bottom of the stack in its window coordinate system, in pixels. */
    val bottom: Float = 0f,
) {
    constructor(
        bounds: Rect
    ) : this(left = bounds.left, top = bounds.top, right = bounds.right, bottom = bounds.bottom)

    /** The current height of the notification container. */
    val height: Float = bottom - top

    fun minus(leftOffset: Int = 0, topOffset: Int = 0) =
        if (leftOffset == 0 && topOffset == 0) {
            this
        } else {
            ShadeScrimBounds(
                left = this.left - leftOffset,
                top = this.top - topOffset,
                right = this.right - leftOffset,
                bottom = this.bottom - topOffset,
            )
        }
}
