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

package com.android.systemui.statusbar.notification.row

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.widget.Button

/**
 * Custom Button for Animated Action Button, which includes the custom background and foreground.
 */
@SuppressLint("AppCompatCustomView")
class AnimatedActionButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : Button(context, attrs, defStyleAttr) {
    init {
        background = AnimatedActionBackgroundDrawable(context)
    }
}
