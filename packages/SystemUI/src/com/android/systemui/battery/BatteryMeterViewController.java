/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.battery;

import static android.provider.Settings.Global.BATTERY_ESTIMATES_LAST_UPDATE_TIME;
import static android.provider.Settings.System.SHOW_BATTERY_PERCENT;
import static android.provider.Settings.System.SHOW_BATTERY_PERCENT_CHARGING;
import static android.provider.Settings.System.QS_SHOW_BATTERY_ESTIMATE;
import static android.provider.Settings.System.STATUS_BAR_BATTERY_STYLE;
import static android.provider.Settings.System.SHOW_BATTERY_PERCENT_INSIDE;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.view.View;

import androidx.annotation.NonNull;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.StatusBarLocation;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.ViewController;

import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Controller for {@link BatteryMeterView}.
 * @deprecated once [NewStatusBarIcons] is rolled out, this class is no longer needed
 */
public class BatteryMeterViewController extends ViewController<BatteryMeterView> {
    private final ConfigurationController mConfigurationController;
    private final TunerService mTunerService;
    private final Handler mMainHandler;
    private final ContentResolver mContentResolver;
    private final FeatureFlags mFeatureFlags;
    private final BatteryController mBatteryController;

    private final String mSlotBattery;
    private final SettingObserver mSettingObserver;
    private final UserTracker mUserTracker;
    private final StatusBarLocation mLocation;

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onDensityOrFontScaleChanged() {
                    mView.scaleBatteryMeterViews();
                }
            };

    private final TunerService.Tunable mTunable = new TunerService.Tunable() {
        @Override
        public void onTuningChanged(String key, String newValue) {
            if (StatusBarIconController.ICON_HIDE_LIST.equals(key)) {
                ArraySet<String> icons = StatusBarIconController.getIconHideList(
                        getContext(), newValue);
                mView.setVisibility(icons.contains(mSlotBattery) ? View.GONE : View.VISIBLE);
            }
        }
    };

    private final BatteryController.BatteryStateChangeCallback mBatteryStateChangeCallback =
            new BatteryController.BatteryStateChangeCallback() {
                @Override
                public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
                    mView.onBatteryLevelChanged(level, pluggedIn);
                }

                @Override
                public void onPowerSaveChanged(boolean isPowerSave) {
                    mView.onPowerSaveChanged(isPowerSave);
                }

                @Override
                public void onBatteryUnknownStateChanged(boolean isUnknown) {
                    mView.onBatteryUnknownStateChanged(isUnknown);
                }

                @Override
                public void onIsBatteryDefenderChanged(boolean isBatteryDefender) {
                    mView.onIsBatteryDefenderChanged(isBatteryDefender);
                }

                @Override
                public void onIsIncompatibleChargingChanged(boolean isIncompatibleCharging) {
                    if (mFeatureFlags.isEnabled(Flags.INCOMPATIBLE_CHARGING_BATTERY_ICON)) {
                        mView.onIsIncompatibleChargingChanged(isIncompatibleCharging);
                    }
                }

                @Override
                public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
                    pw.print(super.toString());
                    pw.println(" location=" + mLocation);
                    mView.dump(pw, args);
                }
            };

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    mContentResolver.unregisterContentObserver(mSettingObserver);
                    registerShowBatteryPercentObserver(newUser);
                    registerGlobalBatteryUpdateObserver();
                    registerUserSettingsObservers();
                    mView.updateBatteryStyle();
                }
            };

    // Some places may need to show the battery conditionally, and not obey the tuner
    private boolean mIgnoreTunerUpdates;
    private boolean mIsSubscribedForTunerUpdates;

    @Inject
    public BatteryMeterViewController(
            BatteryMeterView view,
            StatusBarLocation location,
            UserTracker userTracker,
            ConfigurationController configurationController,
            TunerService tunerService,
            @Main Handler mainHandler,
            ContentResolver contentResolver,
            FeatureFlags featureFlags,
            BatteryController batteryController) {
        super(view);
        mLocation = location;
        mUserTracker = userTracker;
        mConfigurationController = configurationController;
        mTunerService = tunerService;
        mMainHandler = mainHandler;
        mContentResolver = contentResolver;
        mFeatureFlags = featureFlags;
        mBatteryController = batteryController;

        mView.setBatteryEstimateFetcher(mBatteryController::getEstimatedTimeRemainingString);

        mSlotBattery = getResources().getString(com.android.internal.R.string.status_bar_battery);
        mSettingObserver = new SettingObserver(mMainHandler);
    }

    @Override
    protected void onViewAttached() {
        mConfigurationController.addCallback(mConfigurationListener);
        subscribeForTunerUpdates();
        mBatteryController.addCallback(mBatteryStateChangeCallback);

        registerShowBatteryPercentObserver(mUserTracker.getUserId());
        registerGlobalBatteryUpdateObserver();
        registerUserSettingsObservers();
        mUserTracker.addCallback(mUserChangedCallback, new HandlerExecutor(mMainHandler));

        mView.updateBatteryStyle();
    }

    @Override
    protected void onViewDetached() {
        mConfigurationController.removeCallback(mConfigurationListener);
        unsubscribeFromTunerUpdates();
        mBatteryController.removeCallback(mBatteryStateChangeCallback);

        mUserTracker.removeCallback(mUserChangedCallback);
        mContentResolver.unregisterContentObserver(mSettingObserver);
    }

    /**
     * Turn off {@link BatteryMeterView}'s subscribing to the tuner for updates, and thus avoid it
     * controlling its own visibility.
     */
    public void ignoreTunerUpdates() {
        mIgnoreTunerUpdates = true;
        unsubscribeFromTunerUpdates();
    }

    private void subscribeForTunerUpdates() {
        if (mIsSubscribedForTunerUpdates || mIgnoreTunerUpdates) {
            return;
        }

        mTunerService.addTunable(mTunable, StatusBarIconController.ICON_HIDE_LIST);
        mIsSubscribedForTunerUpdates = true;
    }

    private void unsubscribeFromTunerUpdates() {
        if (!mIsSubscribedForTunerUpdates) {
            return;
        }

        mTunerService.removeTunable(mTunable);
        mIsSubscribedForTunerUpdates = false;
    }

    private void registerShowBatteryPercentObserver(int user) {
        mContentResolver.registerContentObserver(
                Settings.System.getUriFor(SHOW_BATTERY_PERCENT),
                false,
                mSettingObserver,
                user);
        mContentResolver.registerContentObserver(
                Settings.System.getUriFor(SHOW_BATTERY_PERCENT_CHARGING),
                false,
                mSettingObserver,
                user);
    }

    private void registerGlobalBatteryUpdateObserver() {
        mContentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.BATTERY_ESTIMATES_LAST_UPDATE_TIME),
                false,
                mSettingObserver);
    }

    private void registerUserSettingsObservers() {
        mContentResolver.registerContentObserver(
                Settings.System.getUriFor(QS_SHOW_BATTERY_ESTIMATE),
                false,
                mSettingObserver);
        mContentResolver.registerContentObserver(
                Settings.System.getUriFor(STATUS_BAR_BATTERY_STYLE),
                false,
                mSettingObserver);
        mContentResolver.registerContentObserver(
                Settings.System.getUriFor(SHOW_BATTERY_PERCENT_INSIDE),
                false,
                mSettingObserver);
    }

    private final class SettingObserver extends ContentObserver {
        public SettingObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            switch (uri.getLastPathSegment()) {
                case BATTERY_ESTIMATES_LAST_UPDATE_TIME:
                    // update the text for sure if the estimate in the cache was updated
                    mView.updatePercentText();
                    break;
                case SHOW_BATTERY_PERCENT:
                case SHOW_BATTERY_PERCENT_CHARGING:
                case SHOW_BATTERY_PERCENT_INSIDE:
                    mView.updateShowPercent();
                    break;
                case QS_SHOW_BATTERY_ESTIMATE:
                    mView.updatePercentView();
                    break;
                case STATUS_BAR_BATTERY_STYLE:
                    mView.updateBatteryStyle();
                    break;
            }
        }
    }

    /** */
    @SysUISingleton
    public static class Factory {
        private final UserTracker mUserTracker;
        private final ConfigurationController mConfigurationController;
        private final TunerService mTunerService;
        private final @Main Handler mMainHandler;
        private final ContentResolver mContentResolver;
        private final FeatureFlags mFeatureFlags;
        private final BatteryController mBatteryController;

        @Inject
        public Factory(
                UserTracker userTracker,
                ConfigurationController configurationController,
                TunerService tunerService,
                @Main Handler mainHandler,
                ContentResolver contentResolver,
                FeatureFlags featureFlags,
                BatteryController batteryController
        ) {
            mUserTracker = userTracker;
            mConfigurationController = configurationController;
            mTunerService = tunerService;
            mMainHandler = mainHandler;
            mContentResolver = contentResolver;
            mFeatureFlags = featureFlags;
            mBatteryController = batteryController;
        }

        /** */
        public BatteryMeterViewController create(View view, StatusBarLocation location) {
            return new BatteryMeterViewController(
                    (BatteryMeterView) view,
                    location,
                    mUserTracker,
                    mConfigurationController,
                    mTunerService,
                    mMainHandler,
                    mContentResolver,
                    mFeatureFlags,
                    mBatteryController
            );
        }
    }
}
