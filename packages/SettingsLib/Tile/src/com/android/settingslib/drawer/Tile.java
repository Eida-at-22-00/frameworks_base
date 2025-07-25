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

package com.android.settingslib.drawer;

import static com.android.settingslib.drawer.TileUtils.META_DATA_KEY_ORDER;
import static com.android.settingslib.drawer.TileUtils.META_DATA_KEY_PROFILE;
import static com.android.settingslib.drawer.TileUtils.META_DATA_NEW_TASK;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_GROUP_KEY;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_ICON;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_KEYHINT;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_SEARCHABLE;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_SUMMARY;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_SUMMARY_URI;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_SWITCH_URI;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_TITLE;
import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_TITLE_URI;
import static com.android.settingslib.drawer.TileUtils.PROFILE_ALL;
import static com.android.settingslib.drawer.TileUtils.PROFILE_PRIMARY;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;

/** Description of a single dashboard tile that the user can select. */
public abstract class Tile implements Parcelable {

    private static final String TAG = "Tile";

    /** Optional list of user handles which the intent should be launched on. */
    public ArrayList<UserHandle> userHandle = new ArrayList<>();

    public HashMap<UserHandle, PendingIntent> pendingIntentMap = new HashMap<>();

    @VisibleForTesting
    long mLastUpdateTime;
    private final String mComponentPackage;
    private final String mComponentName;
    private final Intent mIntent;

    protected ComponentInfo mComponentInfo;
    private CharSequence mSummaryOverride;
    private Bundle mMetaData;
    private String mCategory;
    private String mGroupKey;

    public Tile(ComponentInfo info, String category, Bundle metaData) {
        mComponentInfo = info;
        mComponentPackage = mComponentInfo.packageName;
        mComponentName = mComponentInfo.name;
        mCategory = category;
        mMetaData = metaData;
        if (mMetaData != null) {
            mGroupKey = metaData.getString(META_DATA_PREFERENCE_GROUP_KEY);
        }
        mIntent = new Intent().setClassName(mComponentPackage, mComponentName);
        if (isNewTask()) {
            mIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
    }

    Tile(Parcel in) {
        mComponentPackage = in.readString();
        mComponentName = in.readString();
        mIntent = new Intent().setClassName(mComponentPackage, mComponentName);
        final int number = in.readInt();
        for (int i = 0; i < number; i++) {
            userHandle.add(UserHandle.CREATOR.createFromParcel(in));
        }
        mCategory = in.readString();
        mMetaData = in.readBundle();
        if (isNewTask()) {
            mIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        mGroupKey = in.readString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(this instanceof ProviderTile);
        dest.writeString(mComponentPackage);
        dest.writeString(mComponentName);
        final int size = userHandle.size();
        dest.writeInt(size);
        for (int i = 0; i < size; i++) {
            userHandle.get(i).writeToParcel(dest, flags);
        }
        dest.writeString(mCategory);
        dest.writeBundle(mMetaData);
        dest.writeString(mGroupKey);
    }

    /** Unique ID of the tile */
    public abstract int getId();

    /** Human-readable description of the tile */
    public abstract String getDescription();

    protected abstract ComponentInfo getComponentInfo(Context context);

    protected abstract CharSequence getComponentLabel(Context context);

    protected abstract int getComponentIcon(ComponentInfo info);

    public String getPackageName() {
        return mComponentPackage;
    }

    public String getComponentName() {
        return mComponentName;
    }

    /** Intent to launch when the preference is selected. */
    public Intent getIntent() {
        return mIntent;
    }

    /** Category in which the tile should be placed. */
    public String getCategory() {
        return mCategory;
    }

    public void setCategory(String newCategoryKey) {
        mCategory = newCategoryKey;
    }

    /** Priority of this tile, used for display ordering. */
    public int getOrder() {
        if (hasOrder()) {
            return mMetaData.getInt(META_DATA_KEY_ORDER);
        } else {
            return 0;
        }
    }

    /** Check whether tile has order. */
    public boolean hasOrder() {
        return mMetaData != null
                && mMetaData.containsKey(META_DATA_KEY_ORDER)
                && mMetaData.get(META_DATA_KEY_ORDER) instanceof Integer;
    }

    /** Check whether tile has a switch. */
    public boolean hasSwitch() {
        return mMetaData != null && mMetaData.containsKey(META_DATA_PREFERENCE_SWITCH_URI);
    }

    /** Check whether tile has a pending intent. */
    public boolean hasPendingIntent() {
        return !pendingIntentMap.isEmpty();
    }

    /** Title of the tile that is shown to the user. */
    public CharSequence getTitle(Context context) {
        CharSequence title = null;
        ensureMetadataNotStale(context);
        final PackageManager packageManager = context.getPackageManager();
        if (mMetaData != null && mMetaData.containsKey(META_DATA_PREFERENCE_TITLE)) {
            if (mMetaData.containsKey(META_DATA_PREFERENCE_TITLE_URI)) {
                // If has as uri to provide dynamic title, skip loading here. UI will later load
                // at tile binding time.
                return null;
            }
            if (mMetaData.get(META_DATA_PREFERENCE_TITLE) instanceof Integer) {
                try {
                    final Resources res =
                            packageManager.getResourcesForApplication(mComponentPackage);
                    title = res.getString(mMetaData.getInt(META_DATA_PREFERENCE_TITLE));
                } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
                    Log.w(TAG, "Couldn't find info", e);
                }
            } else {
                title = mMetaData.getString(META_DATA_PREFERENCE_TITLE);
            }
        }
        // Set the preference title by the component if no meta-data is found
        if (title == null) {
            title = getComponentLabel(context);
        }
        return title;
    }

    /**
     * Overrides the summary. This can happen when injected tile wants to provide dynamic summary.
     */
    public void overrideSummary(CharSequence summaryOverride) {
        mSummaryOverride = summaryOverride;
    }

    /** Optional summary describing what this tile controls. */
    public CharSequence getSummary(Context context) {
        if (mSummaryOverride != null) {
            return mSummaryOverride;
        }
        ensureMetadataNotStale(context);
        CharSequence summary = null;
        final PackageManager packageManager = context.getPackageManager();
        if (mMetaData != null) {
            if (mMetaData.containsKey(META_DATA_PREFERENCE_SUMMARY_URI)) {
                // If has as uri to provide dynamic summary, skip loading here. UI will later load
                // at tile binding time.
                return null;
            }
            if (mMetaData.containsKey(META_DATA_PREFERENCE_SUMMARY)) {
                if (mMetaData.get(META_DATA_PREFERENCE_SUMMARY) instanceof Integer) {
                    try {
                        final Resources res =
                                packageManager.getResourcesForApplication(mComponentPackage);
                        summary = res.getString(mMetaData.getInt(META_DATA_PREFERENCE_SUMMARY));
                    } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
                        Log.d(TAG, "Couldn't find info", e);
                    }
                } else {
                    summary = mMetaData.getString(META_DATA_PREFERENCE_SUMMARY);
                }
            }
        }
        return summary;
    }

    public void setMetaData(Bundle metaData) {
        mMetaData = metaData;
    }

    /** The metaData from the activity that defines this tile. */
    public Bundle getMetaData() {
        return mMetaData;
    }

    /** Optional key to use for this tile. */
    public String getKey(Context context) {
        ensureMetadataNotStale(context);
        if (!hasKey()) {
            return null;
        }
        if (mMetaData.get(META_DATA_PREFERENCE_KEYHINT) instanceof Integer) {
            return context.getResources().getString(mMetaData.getInt(META_DATA_PREFERENCE_KEYHINT));
        } else {
            return mMetaData.getString(META_DATA_PREFERENCE_KEYHINT);
        }
    }

    /** Check whether title has key. */
    public boolean hasKey() {
        return mMetaData != null && mMetaData.containsKey(META_DATA_PREFERENCE_KEYHINT);
    }

    /**
     * Optional icon to show for this tile.
     *
     * @attr ref android.R.styleable#PreferenceHeader_icon
     */
    public Icon getIcon(Context context) {
        if (context == null || mMetaData == null) {
            return null;
        }
        ensureMetadataNotStale(context);
        final ComponentInfo componentInfo = getComponentInfo(context);
        if (componentInfo == null) {
            Log.w(TAG, "Cannot find ComponentInfo for " + getDescription());
            return null;
        }

        int iconResId = mMetaData.getInt(META_DATA_PREFERENCE_ICON);
        // Set the icon. Skip the transparent color for backward compatibility since Android S.
        if (iconResId != 0 && iconResId != android.R.color.transparent) {
            final Icon icon = Icon.createWithResource(componentInfo.packageName, iconResId);
            if (isIconTintable(context)) {
                final TypedArray a =
                        context.obtainStyledAttributes(
                                new int[] {android.R.attr.colorControlNormal});
                final int tintColor = a.getColor(0, 0);
                a.recycle();
                icon.setTint(tintColor);
            }
            return icon;
        } else {
            return null;
        }
    }

    /**
     * Whether the icon can be tinted. This is true when icon needs to be monochrome (single-color)
     */
    public boolean isIconTintable(Context context) {
        ensureMetadataNotStale(context);
        if (mMetaData != null
                && mMetaData.containsKey(TileUtils.META_DATA_PREFERENCE_ICON_TINTABLE)) {
            return mMetaData.getBoolean(TileUtils.META_DATA_PREFERENCE_ICON_TINTABLE);
        }
        return false;
    }

    /** Returns the icon color scheme. */
    @Nullable
    public String getIconColorScheme(@NonNull Context context) {
        ensureMetadataNotStale(context);
        return mMetaData != null
                ? mMetaData.getString(TileUtils.META_DATA_PREFERENCE_ICON_COLOR_SCHEME, null)
                : null;
    }

    /** Whether the {@link Activity} should be launched in a separate task. */
    public boolean isNewTask() {
        if (mMetaData != null && mMetaData.containsKey(META_DATA_NEW_TASK)) {
            return mMetaData.getBoolean(META_DATA_NEW_TASK);
        }
        return false;
    }

    /** Ensures metadata is not stale for this tile. */
    private void ensureMetadataNotStale(Context context) {
        final PackageManager pm = context.getApplicationContext().getPackageManager();

        try {
            final long lastUpdateTime =
                    pm.getPackageInfo(mComponentPackage, PackageManager.GET_META_DATA)
                            .lastUpdateTime;
            if (lastUpdateTime == mLastUpdateTime) {
                // All good. Do nothing
                return;
            }
            // App has been updated since we load metadata last time. Reload metadata.
            mComponentInfo = null;
            getComponentInfo(context);
            mLastUpdateTime = lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "Can't find package, probably uninstalled.");
        }
    }

    public static final Creator<Tile> CREATOR =
            new Creator<Tile>() {
                public Tile createFromParcel(Parcel source) {
                    final boolean isProviderTile = source.readBoolean();
                    // reset the Parcel pointer before delegating to the real constructor.
                    source.setDataPosition(0);
                    return isProviderTile ? new ProviderTile(source) : new ActivityTile(source);
                }

                public Tile[] newArray(int size) {
                    return new Tile[size];
                }
            };

    /** Check whether tile only has primary profile. */
    public boolean isPrimaryProfileOnly() {
        return isPrimaryProfileOnly(mMetaData);
    }

    static boolean isPrimaryProfileOnly(Bundle metaData) {
        String profile = metaData != null ? metaData.getString(META_DATA_KEY_PROFILE) : PROFILE_ALL;
        profile = (profile != null ? profile : PROFILE_ALL);
        return TextUtils.equals(profile, PROFILE_PRIMARY);
    }

    /** Returns whether the tile belongs to another group / category. */
    public boolean hasGroupKey() {
        return !TextUtils.isEmpty(mGroupKey);
    }

    /** Set the group / PreferenceCategory key this tile belongs to. */
    public void setGroupKey(String groupKey) {
        mGroupKey = groupKey;
    }

    /** Returns the group / category key this tile belongs to. */
    public String getGroupKey() {
        return mGroupKey;
    }

    /** Returns if this is searchable. */
    public boolean isSearchable() {
        return mMetaData == null || mMetaData.getBoolean(META_DATA_PREFERENCE_SEARCHABLE, true);
    }

    /** The type of the tile. */
    public enum Type {
        /** A preference that can be tapped on to open a new page. */
        ACTION,

        /** A preference that can be tapped on to open an external app. */
        EXTERNAL_ACTION,

        /** A preference that shows an on / off switch that can be toggled by the user. */
        SWITCH,

        /**
         * A preference with both an on / off switch, and a tappable area that can perform an
         * action.
         */
        SWITCH_WITH_ACTION,

        /**
         * A preference category with a title that can be used to group multiple preferences
         * together.
         */
        GROUP
    }

    /**
     * Returns the type of the tile.
     *
     * @see Type
     */
    public Type getType() {
        boolean hasExternalAction = hasPendingIntent();
        boolean hasAction = hasExternalAction || this instanceof ActivityTile;
        boolean hasSwitch = hasSwitch();

        if (hasSwitch && hasAction) {
            return Type.SWITCH_WITH_ACTION;
        } else if (hasSwitch) {
            return Type.SWITCH;
        } else if (hasExternalAction) {
            return Type.EXTERNAL_ACTION;
        } else if (hasAction) {
            return Type.ACTION;
        } else {
            return Type.GROUP;
        }
    }

    public static final Comparator<Tile> TILE_COMPARATOR =
            (lhs, rhs) -> rhs.getOrder() - lhs.getOrder();
}
