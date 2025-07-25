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
package com.android.internal.widget.remotecompose.core;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

public interface SerializableToString {
    /**
     * Returns a stable string representation of an operation
     *
     * @param indent the indentation for that operation
     * @param serializer the serializer object
     */
    void serializeToString(int indent, @NonNull StringSerializer serializer);
}
