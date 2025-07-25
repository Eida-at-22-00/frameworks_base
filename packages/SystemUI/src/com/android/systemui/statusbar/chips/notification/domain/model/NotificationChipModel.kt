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

package com.android.systemui.statusbar.chips.notification.domain.model

import com.android.internal.logging.InstanceId
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModels

/** Modeling all the data needed to render a status bar notification chip. */
data class NotificationChipModel(
    val key: String,
    /** The user-readable name of the app that posted this notification. */
    val appName: String,
    val statusBarChipIconView: StatusBarIconView?,
    val promotedContent: PromotedNotificationContentModels,
    /** The time when the notification first appeared as promoted. */
    val creationTime: Long,
    /** True if the app managing this notification is currently visible to the user. */
    val isAppVisible: Boolean,
    /**
     * The time when the app managing this notification last appeared as visible, or null if the app
     * hasn't become visible since the notification became promoted.
     */
    val lastAppVisibleTime: Long?,
    /** An optional per-notification ID used for logging. */
    val instanceId: InstanceId?,
)
