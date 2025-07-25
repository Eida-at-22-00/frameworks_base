/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.listbuilder;

import com.android.systemui.statusbar.notification.collection.PipelineEntry;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter;

import java.util.List;

/**
 * See
 * {@link NotifPipeline#addOnBeforeTransformGroupsListener(OnBeforeTransformGroupsListener)}
 */
public interface OnBeforeTransformGroupsListener {
    /**
     * Called after notifs have been filtered and grouped but before {@link NotifPromoter}s have
     * been called.
     *
     * @param list The current filtered and grouped list of (top-level) entries. Note that this is
     *             a live view into the current notif list and will change as the list moves through
     *             the pipeline.
     */
    void onBeforeTransformGroups(List<PipelineEntry> list);
}
