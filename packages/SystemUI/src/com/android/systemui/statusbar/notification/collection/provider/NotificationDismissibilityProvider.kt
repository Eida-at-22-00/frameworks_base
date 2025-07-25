/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.provider

import com.android.systemui.statusbar.notification.collection.NotificationEntry

/** Keeps track of the dismissibility of Notifications currently handed over to the view layer. */
interface NotificationDismissibilityProvider {
    /** @return true if the given entry's key can currently be dismissed by the user */
    fun isDismissable(entryKey: String): Boolean
}
