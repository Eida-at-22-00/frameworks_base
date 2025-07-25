package com.android.systemui.qs.tiles;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.systemui.animation.Expandable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.SysUIToast;

import java.util.concurrent.Executors;
import java.util.List;

import javax.inject.Inject;

public class DataSwitchTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "dataswitch";

    private boolean mCanSwitch = true;
    private boolean mRegistered = false;
    private int mSimCount = 0;
    BroadcastReceiver mSimReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "mSimReceiver:onReceive");
            refreshState();
        }
    };
    private final MyCallStateListener mPhoneStateListener;
    private final SubscriptionManager mSubscriptionManager;
    private final TelephonyManager mTelephonyManager;

    class MyCallStateListener extends PhoneStateListener {
        MyCallStateListener() { }

        public void onCallStateChanged(int state, String arg1) {
            mCanSwitch = mTelephonyManager.getCallState() == TelephonyManager.CALL_STATE_IDLE;
            refreshState();
        }
    }

    @Inject
    public DataSwitchTile(
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
        mSubscriptionManager = SubscriptionManager.from(host.getContext());
        mTelephonyManager = TelephonyManager.from(host.getContext());
        mPhoneStateListener = new MyCallStateListener();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (listening) {
            if (!mRegistered) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
                mContext.registerReceiver(mSimReceiver, filter);
                mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
                mRegistered = true;
            }
            refreshState();
        } else if (mRegistered) {
            mContext.unregisterReceiver(mSimReceiver);
            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
            mRegistered = false;
        }
    }

    private void updateSimCount() {
        String simState = SystemProperties.get("gsm.sim.state");
        Log.d(TAG, "DataSwitchTile:updateSimCount:simState=" + simState);
        mSimCount = 0;
        try {
            String[] sims = TextUtils.split(simState, ",");
            for (String sim : sims) {
                if (!sim.isEmpty()
                        && !sim.equalsIgnoreCase(IccCardConstants.INTENT_VALUE_ICC_ABSENT)
                        && !sim.equalsIgnoreCase(IccCardConstants.INTENT_VALUE_ICC_NOT_READY)) {
                    mSimCount++;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error to parse sim state");
        }
        Log.d(TAG, "DataSwitchTile:updateSimCount:mSimCount=" + mSimCount);
    }

    @Override
    public void handleClick(@Nullable Expandable expandable) {
        if (!mCanSwitch) {
            Log.d(TAG, "Call state=" + mTelephonyManager.getCallState());
        } else if (mSimCount == 0) {
            Log.d(TAG, "handleClick:no sim card");
            SysUIToast.makeText(mContext,
                    mContext.getString(R.string.qs_data_switch_toast_0),
                    Toast.LENGTH_LONG).show();
        } else if (mSimCount == 1) {
            Log.d(TAG, "handleClick:only one sim card");
            SysUIToast.makeText(mContext,
                    mContext.getString(R.string.qs_data_switch_toast_1),
                    Toast.LENGTH_LONG).show();
        } else {
            Executors.newSingleThreadExecutor().execute(() -> {
                toggleMobileDataEnabled();
                refreshState();
            });
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.qs_data_switch_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        boolean activeSIMZero;
        if (arg == null) {
            final SubscriptionInfo info = mSubscriptionManager.getDefaultDataSubscriptionInfo();
            final int defaultPhoneId = info == null ? 0 : info.getSimSlotIndex();
            Log.d(TAG, "default data phone id=" + defaultPhoneId);
            activeSIMZero = defaultPhoneId == 0;
        } else {
            activeSIMZero = (Boolean) arg;
        }
        updateSimCount();
        switch (mSimCount) {
            case 1:
                state.icon = ResourceIcon.get(activeSIMZero
                        ? R.drawable.ic_qs_data_switch_1
                        : R.drawable.ic_qs_data_switch_2);
                state.secondaryLabel = mContext.getString(
                        activeSIMZero ? R.string.qs_data_switch_text_1
                                      : R.string.qs_data_switch_text_2);
                state.value = false;
                break;
            case 2:
                state.icon = ResourceIcon.get(activeSIMZero
                        ? R.drawable.ic_qs_data_switch_1
                        : R.drawable.ic_qs_data_switch_2);
                state.secondaryLabel = mContext.getString(
                        activeSIMZero ? R.string.qs_data_switch_text_1
                                      : R.string.qs_data_switch_text_2);
                state.value = true;
                break;
            default:
                state.icon = ResourceIcon.get(R.drawable.ic_qs_data_switch_1);
                state.value = false;
                break;
        }
        if (mSimCount < 2) {
            state.state = 0;
        } else if (!mCanSwitch) {
            state.state = 0;
            Log.d(TAG, "call state isn't idle, set to unavailable.");
        } else {
            state.state = state.value ? 2 : 1;
        }

        state.contentDescription =
                mContext.getString(activeSIMZero
                        ? R.string.qs_data_switch_changed_1
                        : R.string.qs_data_switch_changed_2);
        state.label = mContext.getString(R.string.qs_data_switch_label);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.YASP;
    }

    /**
     * Set whether to enable data for {@code subId}, also whether to disable data for other
     * subscription
     */
    private void toggleMobileDataEnabled() {
        // subIDs aren't necessarily 1 & 2 only !
        final SubscriptionInfo currentSubInfo = mSubscriptionManager.getDefaultDataSubscriptionInfo();
        if (currentSubInfo == null) return;
        final int currentSubID = currentSubInfo.getSubscriptionId();
        final int currentIndex = currentSubInfo.getSimSlotIndex();

        List<SubscriptionInfo> subInfoList =
                mSubscriptionManager.getActiveSubscriptionInfoList(true);
        SubscriptionInfo newSubInfo = null;
        if (subInfoList != null) {
            Log.d(TAG, "subInfos:");
            for (SubscriptionInfo subInfo : subInfoList) {
                final int id = subInfo.getSubscriptionId();
                final int index = subInfo.getSimSlotIndex();
                Log.d(TAG, "id: " + id + " index: " + index);
                if (id != currentSubID && index != currentIndex) {
                    newSubInfo = subInfo;
                    break;
                }
            }
        }

        if (newSubInfo == null) {
            Log.d(TAG, "Could not find newSubInfo");
            return;
        }

        final int newSubID = newSubInfo.getSubscriptionId();
        mTelephonyManager.createForSubscriptionId(newSubID).setDataEnabled(true);
        mSubscriptionManager.setDefaultDataSubId(newSubID);
        Log.d(TAG, "Enabled subID: " + newSubID);

        if (currentSubInfo.isOpportunistic()) {
            // Never disable mobile data for opportunistic subscriptions
            Log.d(TAG, "Refusing to disable opportunistic subID: " + currentSubID);
            return;
        }

        mTelephonyManager.createForSubscriptionId(currentSubID).setDataEnabled(false);
        Log.d(TAG, "Disabled subID: " + currentSubID);
    }
}
