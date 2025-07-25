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

package com.android.systemui.statusbar.notification.collection;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Represents a set of grouped notifications. The final notification list is usually a mix of
 * GroupEntries and NotificationEntries.
 */
public class GroupEntry extends ListEntry {
    @Nullable private NotificationEntry mSummary;
    private final List<NotificationEntry> mChildren = new ArrayList<>();

    private final List<NotificationEntry> mUnmodifiableChildren =
            Collections.unmodifiableList(mChildren);
    private int mUntruncatedChildCount;

    GroupEntry(String key, long creationTime) {
        super(key, creationTime);
    }

    @Override
    public NotificationEntry getRepresentativeEntry() {
        return mSummary;
    }

    @Nullable
    public NotificationEntry getSummary() {
        return mSummary;
    }

    @NonNull
    public List<NotificationEntry> getChildren() {
        return mUnmodifiableChildren;
    }

    void setSummary(@Nullable NotificationEntry summary) {
        mSummary = summary;
    }

    void clearChildren() {
        mChildren.clear();
    }

    void addChild(NotificationEntry child) {
        mChildren.add(child);
    }

    void sortChildren(Comparator<? super NotificationEntry> c) {
        mChildren.sort(c);
    }

    List<NotificationEntry> getRawChildren() {
        return mChildren;
    }

    // TODO(b/394483200) Change ROOT_ENTRY to PipelineEntry
    public static final GroupEntry ROOT_ENTRY = new GroupEntry("<root>", 0);

}
