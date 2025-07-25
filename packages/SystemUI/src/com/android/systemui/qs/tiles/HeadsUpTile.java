/*
 * Copyright (C) 2015 The CyanogenMod Project
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

package com.android.systemui.qs.tiles;

import android.content.ComponentName;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.quicksettings.Tile;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.animation.Expandable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;


/** Quick settings tile: Heads up **/
public class HeadsUpTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "heads_up";

    private final CustomObserver mObserver = new CustomObserver();

    @Inject
    public HeadsUpTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            BroadcastDispatcher broadcastDispatcher,
            KeyguardStateController keyguardStateController
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick(@Nullable Expandable expandable) {
        setEnabled(!mState.value);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return (new Intent(Settings.ACTION_NOTIFICATION_SETTINGS));
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_heads_up_label);
    }

    private void setEnabled(boolean enabled) {
        Global.putInt(mContext.getContentResolver(),
                Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                enabled ? 1 : 0);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final int value = arg instanceof Integer 
                ? (Integer)arg
                : Global.getInt(mContext.getContentResolver(),
                                Global.HEADS_UP_NOTIFICATIONS_ENABLED, 1);
        final boolean headsUp = value != 0;
        state.value = headsUp;
        state.label = mContext.getString(R.string.quick_settings_heads_up_label);
        if (headsUp) {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_heads_up_on);
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_heads_up_on);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_heads_up_off);
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_heads_up_off);
            state.state = Tile.STATE_INACTIVE;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.YASP;
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (listening) mObserver.observe();
        else mObserver.stop();
    }

    private class CustomObserver extends ContentObserver {
        CustomObserver() {
            super(mHandler);
        }

        void observe() {
            mContext.getContentResolver().registerContentObserver(
                    Global.getUriFor(Global.HEADS_UP_NOTIFICATIONS_ENABLED),
                    false, this, UserHandle.USER_ALL);
        }

        void stop() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            final int value = Global.getInt(
                    mContext.getContentResolver(),
                    Global.HEADS_UP_NOTIFICATIONS_ENABLED, 1);
            handleRefreshState(value);
        }
    }
}
