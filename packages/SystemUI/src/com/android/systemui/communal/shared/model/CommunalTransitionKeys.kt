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

package com.android.systemui.communal.shared.model

import com.android.compose.animation.scene.TransitionKey

/**
 * Defines all known named transitions for [CommunalScenes].
 *
 * These transitions can be referenced by key when changing scenes programmatically.
 */
object CommunalTransitionKeys {
    /** Fades the glanceable hub without any translation */
    @Deprecated("No longer supported as all hub transitions will be fades.")
    val SimpleFade = TransitionKey("SimpleFade")
    /** Transition from the glanceable hub before entering edit mode */
    val ToEditMode = TransitionKey("ToEditMode")
    /** Transition to the glanceable hub after exiting edit mode */
    val FromEditMode = TransitionKey("FromEditMode")
    /** Swipes the glanceable hub in/out of view */
    val Swipe = TransitionKey("Swipe")
    /** Swipes out of glanceable hub in landscape orientation */
    val SwipeInLandscape = TransitionKey("SwipeInLandscape")
}
