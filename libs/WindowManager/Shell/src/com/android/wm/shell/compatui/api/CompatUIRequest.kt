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

package com.android.wm.shell.compatui.api

/**
 * Abstraction for all the possible Compat UI Component requests.
 */
interface CompatUIRequest {
    /**
     * Unique request identifier
     */
    val requestId: Int

    @Suppress("UNCHECKED_CAST")
    fun <T : CompatUIRequest> asType(): T? = this as? T

    fun <T : CompatUIRequest> asType(clazz: Class<T>): T? {
        return if (clazz.isInstance(this)) clazz.cast(this) else null
    }
}