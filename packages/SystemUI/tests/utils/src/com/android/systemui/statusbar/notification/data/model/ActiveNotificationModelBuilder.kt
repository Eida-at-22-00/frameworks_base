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

package com.android.systemui.statusbar.notification.data.model

import android.app.PendingIntent
import android.graphics.drawable.Icon
import com.android.internal.logging.InstanceId
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModels
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.notification.shared.CallType
import com.android.systemui.statusbar.notification.stack.BUCKET_UNKNOWN

/** Simple ActiveNotificationModel builder for use in tests. */
fun activeNotificationModel(
    key: String,
    groupKey: String? = null,
    whenTime: Long = 0L,
    isForegroundService: Boolean = false,
    isOngoingEvent: Boolean = false,
    isAmbient: Boolean = false,
    isRowDismissed: Boolean = false,
    isSilent: Boolean = false,
    isLastMessageFromReply: Boolean = false,
    isSuppressedFromStatusBar: Boolean = false,
    isPulsing: Boolean = false,
    aodIcon: Icon? = null,
    shelfIcon: Icon? = null,
    statusBarIcon: Icon? = null,
    statusBarChipIcon: StatusBarIconView? = null,
    uid: Int = 0,
    instanceId: InstanceId? = null,
    isGroupSummary: Boolean = false,
    packageName: String = "pkg",
    appName: String = "appName",
    contentIntent: PendingIntent? = null,
    bucket: Int = BUCKET_UNKNOWN,
    callType: CallType = CallType.None,
    promotedContent: PromotedNotificationContentModels? = null,
) =
    ActiveNotificationModel(
        key = key,
        groupKey = groupKey,
        whenTime = whenTime,
        isForegroundService = isForegroundService,
        isOngoingEvent = isOngoingEvent,
        isAmbient = isAmbient,
        isRowDismissed = isRowDismissed,
        isSilent = isSilent,
        isLastMessageFromReply = isLastMessageFromReply,
        isSuppressedFromStatusBar = isSuppressedFromStatusBar,
        isPulsing = isPulsing,
        aodIcon = aodIcon,
        shelfIcon = shelfIcon,
        statusBarIcon = statusBarIcon,
        statusBarChipIconView = statusBarChipIcon,
        uid = uid,
        packageName = packageName,
        appName = appName,
        contentIntent = contentIntent,
        instanceId = instanceId,
        isGroupSummary = isGroupSummary,
        bucket = bucket,
        callType = callType,
        promotedContent = promotedContent,
    )
