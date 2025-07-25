/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone.fragment;

import static com.android.systemui.statusbar.phone.fragment.StatusBarVisibilityModel.createHiddenModel;

import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.Fragment;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.SparseArray;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.VisibleForTesting;
import androidx.core.animation.Animator;

import com.android.app.animation.Interpolators;
import com.android.app.animation.InterpolatorsAndroidX;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.demomode.DemoMode;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.OperatorNameView;
import com.android.systemui.statusbar.OperatorNameViewController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips;
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays;
import com.android.systemui.statusbar.core.StatusBarRootModernization;
import com.android.systemui.statusbar.data.repository.DarkIconDispatcherStore;
import com.android.systemui.statusbar.data.repository.StatusBarConfigurationController;
import com.android.systemui.statusbar.data.repository.StatusBarConfigurationControllerStore;
import com.android.systemui.statusbar.disableflags.DisableFlagsLogger;
import com.android.systemui.statusbar.events.SystemStatusAnimationCallback;
import com.android.systemui.statusbar.events.SystemStatusAnimationScheduler;
import com.android.systemui.statusbar.headsup.shared.StatusBarNoHunBehavior;
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerStatusBarViewBinder;
import com.android.systemui.statusbar.phone.ClockController;
import com.android.systemui.statusbar.phone.NotificationIconContainer;
import com.android.systemui.statusbar.phone.PhoneStatusBarView;
import com.android.systemui.statusbar.phone.StatusBarHideIconsForBouncerManager;
import com.android.systemui.statusbar.phone.StatusBarLocation;
import com.android.systemui.statusbar.phone.fragment.dagger.HomeStatusBarComponent;
import com.android.systemui.statusbar.phone.fragment.dagger.HomeStatusBarComponent.Startable;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallController;
import com.android.systemui.statusbar.phone.ongoingcall.OngoingCallListener;
import com.android.systemui.statusbar.phone.ongoingcall.StatusBarChipsModernization;
import com.android.systemui.statusbar.phone.ui.DarkIconManager;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarViewBinder;
import com.android.systemui.statusbar.pipeline.shared.ui.binder.StatusBarVisibilityChangeListener;
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModel;
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.HomeStatusBarViewModel.HomeStatusBarViewModelFactory;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.window.StatusBarWindowController;
import com.android.systemui.statusbar.window.StatusBarWindowControllerStore;
import com.android.systemui.statusbar.window.StatusBarWindowStateController;
import com.android.systemui.statusbar.window.StatusBarWindowStateListener;
import com.android.systemui.util.CarrierConfigTracker;
import com.android.systemui.util.CarrierConfigTracker.CarrierConfigChangedListener;
import com.android.systemui.util.CarrierConfigTracker.DefaultDataSubscriptionChangedListener;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.tuner.TunerService;

import kotlin.Unit;

import kotlinx.coroutines.DisposableHandle;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Contains the collapsed status bar and handles hiding/showing based on disable flags
 * and keyguard state. Also manages lifecycle to make sure the views it contains are being
 * updated by the StatusBarIconController and DarkIconManager while it is attached.
 */
@SuppressLint("ValidFragment")
public class CollapsedStatusBarFragment extends Fragment implements CommandQueue.Callbacks,
        StatusBarStateController.StateListener,
        SystemStatusAnimationCallback, Dumpable, TunerService.Tunable {

    public static final String TAG = "CollapsedStatusBarFragment";
    private static final String EXTRA_PANEL_STATE = "panel_state";
    public static final String STATUS_BAR_ICON_MANAGER_TAG = "status_bar_icon_manager";
    public static final int FADE_IN_DURATION = 320;
    public static final int FADE_OUT_DURATION = 160;
    public static final int FADE_IN_DELAY = 50;
    private static final int SOURCE_SYSTEM_EVENT_ANIMATOR = 1;
    private static final int SOURCE_OTHER = 2;
    private HomeStatusBarComponent mHomeStatusBarComponent;
    private PhoneStatusBarView mStatusBar;
    private final StatusBarStateController mStatusBarStateController;
    private final KeyguardStateController mKeyguardStateController;
    private final PanelExpansionInteractor mPanelExpansionInteractor;
    private MultiSourceMinAlphaController mEndSideAlphaController;
    private LinearLayout mEndSideContent;
    private View mClockView;
    private View mPrimaryOngoingActivityChip;
    private View mSecondaryOngoingActivityChip;
    private View mNotificationIconAreaInner;
    // Visibilities come in from external system callers via disable flags, but we also sometimes
    // modify the visibilities internally. We need to store both so that we don't accidentally
    // propagate our internally modified flags for too long.
    private StatusBarVisibilityModel mLastSystemVisibility =
            StatusBarVisibilityModel.createDefaultModel();
    private StatusBarVisibilityModel mLastModifiedVisibility =
            StatusBarVisibilityModel.createDefaultModel();
    private DarkIconManager mDarkIconManager;
    private HomeStatusBarViewModel mHomeStatusBarViewModel;

    private final HomeStatusBarComponent.Factory mHomeStatusBarComponentFactory;
    private final CommandQueue mCommandQueue;
    private final CollapsedStatusBarFragmentLogger mCollapsedStatusBarFragmentLogger;
    private final OperatorNameViewController.Factory mOperatorNameViewControllerFactory;
    private final OngoingCallController mOngoingCallController;
    private final SystemStatusAnimationScheduler mAnimationScheduler;
    private final ShadeExpansionStateManager mShadeExpansionStateManager;
    private final StatusBarIconController mStatusBarIconController;
    private final CarrierConfigTracker mCarrierConfigTracker;
    private final HomeStatusBarViewBinder mHomeStatusBarViewBinder;
    private final HomeStatusBarViewModelFactory mHomeStatusBarViewModelFactory;
    private final StatusBarHideIconsForBouncerManager mStatusBarHideIconsForBouncerManager;
    private final DarkIconManager.Factory mDarkIconManagerFactory;
    private final SecureSettings mSecureSettings;
    private final Executor mMainExecutor;
    private final DumpManager mDumpManager;
    private final StatusBarWindowStateController mStatusBarWindowStateController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final NotificationIconContainerStatusBarViewBinder mNicViewBinder;
    private final DemoModeController mDemoModeController;
    private final StatusBarWindowControllerStore mStatusBarWindowControllerStore;
    private final StatusBarConfigurationControllerStore mStatusBarConfigurationControllerStore;
    private final DarkIconDispatcherStore mDarkIconDispatcherStore;

    private ClockController mClockController;
    private boolean mIsClockBlacklisted;

    private List<String> mBlockedIcons = new ArrayList<>();
    private Map<Startable, Startable.State> mStartableStates = new ArrayMap<>();

    private final OngoingCallListener mOngoingCallListener = new OngoingCallListener() {
        @Override
        public void onOngoingCallStateChanged(boolean animate) {
            updateStatusBarVisibilities(animate);
        }
    };
    private OperatorNameViewController mOperatorNameViewController;
    private StatusBarSystemEventDefaultAnimator mSystemEventAnimator;

    private final CarrierConfigChangedListener mCarrierConfigCallback =
            new CarrierConfigChangedListener() {
                @Override
                public void onCarrierConfigChanged() {
                    if (mOperatorNameViewController == null) {
                        initOperatorName();
                    } else {
                        // Already initialized, KeyguardUpdateMonitorCallback will handle the update
                    }
                }
            };

    private final DefaultDataSubscriptionChangedListener mDefaultDataListener =
            new DefaultDataSubscriptionChangedListener() {
                @Override
                public void onDefaultSubscriptionChanged(int subId) {
                    if (mOperatorNameViewController == null) {
                        initOperatorName();
                    }
                }
            };

    /**
     * Whether we've launched the secure camera over the lockscreen, but haven't yet received a
     * status bar window state change afterward.
     *
     * We wait for this state change (which will tell us whether to show/hide the status bar icons)
     * so that there is no flickering/jump cutting during the camera launch.
     */
    private boolean mWaitingForWindowStateChangeAfterCameraLaunch = false;

    /**
     * True when a transition from lockscreen to dream has started, but haven't yet received a
     * status bar window state change afterward.
     *
     * Similar to [mWaitingForWindowStateChangeAfterCameraLaunch].
     */
    private boolean mTransitionFromLockscreenToDreamStarted = false;

    /**
     * True if the current scene allows the home status bar (aka this status bar) to be shown, and
     * false if the current scene should never show the home status bar. Only used if the scene
     * container is enabled.
     */
    private boolean mHomeStatusBarAllowedByScene = true;

    /**
     * True if there's a primary active ongoing activity that should be showing a chip and false
     * otherwise.
     */
    private boolean mHasPrimaryOngoingActivity;

    /**
     * True if there's a secondary active ongoing activity that should be showing a chip and false
     * otherwise.
     */
    private boolean mHasSecondaryOngoingActivity;

    /**
     * Listener that updates {@link #mWaitingForWindowStateChangeAfterCameraLaunch} when it receives
     * a new status bar window state.
     */
    private final StatusBarWindowStateListener mStatusBarWindowStateListener = state -> {
        mWaitingForWindowStateChangeAfterCameraLaunch = false;
        mTransitionFromLockscreenToDreamStarted = false;
    };
    private DisposableHandle mNicBindingDisposable;

    private boolean mAnimationsEnabled = true;

    @Inject
    public CollapsedStatusBarFragment(
            HomeStatusBarComponent.Factory homeStatusBarComponentFactory,
            OngoingCallController ongoingCallController,
            SystemStatusAnimationScheduler animationScheduler,
            ShadeExpansionStateManager shadeExpansionStateManager,
            StatusBarIconController statusBarIconController,
            DarkIconManager.Factory darkIconManagerFactory,
            HomeStatusBarViewModelFactory homeStatusBarViewModelFactory,
            HomeStatusBarViewBinder homeStatusBarViewBinder,
            StatusBarHideIconsForBouncerManager statusBarHideIconsForBouncerManager,
            KeyguardStateController keyguardStateController,
            PanelExpansionInteractor panelExpansionInteractor,
            StatusBarStateController statusBarStateController,
            NotificationIconContainerStatusBarViewBinder nicViewBinder,
            CommandQueue commandQueue,
            CarrierConfigTracker carrierConfigTracker,
            CollapsedStatusBarFragmentLogger collapsedStatusBarFragmentLogger,
            OperatorNameViewController.Factory operatorNameViewControllerFactory,
            SecureSettings secureSettings,
            @Main Executor mainExecutor,
            DumpManager dumpManager,
            StatusBarWindowStateController statusBarWindowStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            DemoModeController demoModeController,
            StatusBarWindowControllerStore statusBarWindowControllerStore,
            StatusBarConfigurationControllerStore statusBarConfigurationControllerStore,
            DarkIconDispatcherStore darkIconDispatcherStore) {
        mHomeStatusBarComponentFactory = homeStatusBarComponentFactory;
        mOngoingCallController = ongoingCallController;
        mAnimationScheduler = animationScheduler;
        mShadeExpansionStateManager = shadeExpansionStateManager;
        mStatusBarIconController = statusBarIconController;
        mHomeStatusBarViewModelFactory = homeStatusBarViewModelFactory;
        mHomeStatusBarViewBinder = homeStatusBarViewBinder;
        mStatusBarHideIconsForBouncerManager = statusBarHideIconsForBouncerManager;
        mDarkIconManagerFactory = darkIconManagerFactory;
        mKeyguardStateController = keyguardStateController;
        mPanelExpansionInteractor = panelExpansionInteractor;
        mStatusBarStateController = statusBarStateController;
        mNicViewBinder = nicViewBinder;
        mCommandQueue = commandQueue;
        mCarrierConfigTracker = carrierConfigTracker;
        mCollapsedStatusBarFragmentLogger = collapsedStatusBarFragmentLogger;
        mOperatorNameViewControllerFactory = operatorNameViewControllerFactory;
        mSecureSettings = secureSettings;
        mMainExecutor = mainExecutor;
        mDumpManager = dumpManager;
        mStatusBarWindowStateController = statusBarWindowStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mDemoModeController = demoModeController;
        mStatusBarWindowControllerStore = statusBarWindowControllerStore;
        mStatusBarConfigurationControllerStore = statusBarConfigurationControllerStore;
        mDarkIconDispatcherStore = darkIconDispatcherStore;
    }

    private final DemoMode mDemoModeCallback = new DemoMode() {
        @Override
        public List<String> demoCommands() {
            return List.of(DemoMode.COMMAND_NOTIFICATIONS);
        }

        @Override
        public void dispatchDemoCommand(String command, Bundle args) {
            if (mNotificationIconAreaInner == null) return;
            String visible = args.getString("visible");
            if ("false".equals(visible)) {
                mNotificationIconAreaInner.setVisibility(View.INVISIBLE);
            } else {
                mNotificationIconAreaInner.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void onDemoModeFinished() {
            if (mNotificationIconAreaInner == null) return;
            mNotificationIconAreaInner.setVisibility(View.VISIBLE);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mStatusBarWindowStateController.addListener(mStatusBarWindowStateListener);
        mDemoModeController.addCallback(mDemoModeCallback);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mStatusBarWindowStateController.removeListener(mStatusBarWindowStateListener);
        mDemoModeController.removeCallback(mDemoModeCallback);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            Bundle savedInstanceState) {
        return inflater.inflate(R.layout.status_bar, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mDumpManager.registerDumpable(getDumpableName(), this);
        int displayId = view.getContext().getDisplayId();
        StatusBarConfigurationController configurationController =
                mStatusBarConfigurationControllerStore.forDisplay(displayId);
        if (configurationController == null) {
            return;
        }
        StatusBarWindowController statusBarWindowController =
                mStatusBarWindowControllerStore.forDisplay(displayId);
        if (statusBarWindowController == null) {
            return;
        }
        DarkIconDispatcher darkIconDispatcher = mDarkIconDispatcherStore.forDisplay(displayId);
        if (darkIconDispatcher == null) {
            return;
        }
        mHomeStatusBarComponent =
                mHomeStatusBarComponentFactory.create(
                        (PhoneStatusBarView) getView(),
                        configurationController,
                        statusBarWindowController,
                        darkIconDispatcher);
        mHomeStatusBarComponent.init();
        mStartableStates.clear();
        for (Startable startable : mHomeStatusBarComponent.getStartables()) {
            mStartableStates.put(startable, Startable.State.STARTING);
            startable.start();
            mStartableStates.put(startable, Startable.State.STARTED);
        }

        mStatusBar = (PhoneStatusBarView) view;
        if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_PANEL_STATE)) {
            mStatusBar.restoreHierarchyState(
                    savedInstanceState.getSparseParcelableArray(EXTRA_PANEL_STATE));
        }
        mDarkIconManager =
                mDarkIconManagerFactory.create(
                        view.findViewById(R.id.statusIcons),
                        StatusBarLocation.HOME,
                        mHomeStatusBarComponent.getDarkIconDispatcher());
        mDarkIconManager.setShouldLog(true);
        updateBlockedIcons();
        mStatusBarIconController.addIconGroup(mDarkIconManager);
        mEndSideContent = mStatusBar.findViewById(R.id.status_bar_end_side_content);
        mEndSideAlphaController = new MultiSourceMinAlphaController(mEndSideContent);
        mClockController = mStatusBar.getClockController();
        mClockView = mStatusBar.findViewById(R.id.clock);
        mPrimaryOngoingActivityChip = mStatusBar.findViewById(R.id.ongoing_activity_chip_primary);
        mSecondaryOngoingActivityChip =
                mStatusBar.findViewById(R.id.ongoing_activity_chip_secondary);
        if (!StatusBarRootModernization.isEnabled()) {
            showEndSideContent(false);
            showClock(false);
        }
        initOperatorName();
        initNotificationIconArea();
        mSystemEventAnimator = getSystemEventAnimator();
        mCarrierConfigTracker.addCallback(mCarrierConfigCallback);
        mCarrierConfigTracker.addDefaultDataSubscriptionChangedListener(mDefaultDataListener);

        mHomeStatusBarViewModel = mHomeStatusBarViewModelFactory.create(displayId);
        mHomeStatusBarViewBinder.bind(
                view.getContext().getDisplayId(),
                mStatusBar,
                mHomeStatusBarViewModel,
                /* systemEventChipAnimateIn */ null,
                /* systemEventChipAnimateOut */ null,
                mStatusBarVisibilityChangeListener);

        Dependency.get(TunerService.class).addTunable(this, StatusBarIconController.ICON_HIDE_LIST);
    }

    private String getDumpableName() {
        if (getContext().getDisplayId() == Display.DEFAULT_DISPLAY) {
            return getClass().getSimpleName();
        } else {
            return getClass().getSimpleName() + getContext().getDisplayId();
        }
    }

    @Override
    public void onCameraLaunchGestureDetected(int source) {
        mWaitingForWindowStateChangeAfterCameraLaunch = true;
    }

    @VisibleForTesting
    void updateBlockedIcons() {
        mBlockedIcons.clear();

        // Reload the blocklist from res
        List<String> blockList = Arrays.asList(getResources().getStringArray(
                R.array.config_collapsed_statusbar_icon_blocklist));
        String vibrateIconSlot = getString(com.android.internal.R.string.status_bar_volume);
        boolean showVibrateIcon =
                mSecureSettings.getIntForUser(
                        Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON,
                        1,
                        UserHandle.USER_CURRENT) == 0;

        // Filter out vibrate icon from the blocklist if the setting is on
        for (int i = 0; i < blockList.size(); i++) {
            if (blockList.get(i).equals(vibrateIconSlot)) {
                if (showVibrateIcon) {
                    mBlockedIcons.add(blockList.get(i));
                }
            } else {
                mBlockedIcons.add(blockList.get(i));
            }
        }

        mMainExecutor.execute(() -> mDarkIconManager.setBlockList(mBlockedIcons));
    }

    @VisibleForTesting
    List<String> getBlockedIcons() {
        return mBlockedIcons;
    }


    @VisibleForTesting
    void enableAnimationsForTesting() {
        mAnimationsEnabled = true;
    }

    @VisibleForTesting
    void disableAnimationsForTesting() {
        mAnimationsEnabled = false;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        SparseArray<Parcelable> states = new SparseArray<>();
        mStatusBar.saveHierarchyState(states);
        outState.putSparseParcelableArray(EXTRA_PANEL_STATE, states);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mHomeStatusBarComponent == null) {
            return;
        }
        mCommandQueue.addCallback(this);
        mStatusBarStateController.addCallback(this);
        initOngoingCallChip();
        mAnimationScheduler.addCallback(this);

        mSecureSettings.registerContentObserverForUserSync(
                Settings.Secure.STATUS_BAR_SHOW_VIBRATE_ICON,
                false,
                mVolumeSettingObserver,
                UserHandle.USER_ALL);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mHomeStatusBarComponent == null) {
            return;
        }
        mCommandQueue.removeCallback(this);
        mStatusBarStateController.removeCallback(this);
        if (!StatusBarRootModernization.isEnabled()) {
            mOngoingCallController.removeCallback(mOngoingCallListener);
        }
        mAnimationScheduler.removeCallback(this);
        mSecureSettings.unregisterContentObserverSync(mVolumeSettingObserver);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mHomeStatusBarComponent == null) {
            return;
        }
        mStatusBarIconController.removeIconGroup(mDarkIconManager);
        mCarrierConfigTracker.removeCallback(mCarrierConfigCallback);
        mCarrierConfigTracker.removeDataSubscriptionChangedListener(mDefaultDataListener);

        for (Startable startable : mHomeStatusBarComponent.getStartables()) {
            mStartableStates.put(startable, Startable.State.STOPPING);
            startable.stop();
            mStartableStates.put(startable, Startable.State.STOPPED);
        }
        mDumpManager.unregisterDumpable(getDumpableName());
        if (mNicBindingDisposable != null) {
            mNicBindingDisposable.dispose();
            mNicBindingDisposable = null;
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        boolean wasClockBlacklisted = mIsClockBlacklisted;
        mIsClockBlacklisted = StatusBarIconController.getIconHideList(
                getContext(), newValue).contains("clock");
        if (wasClockBlacklisted && !mIsClockBlacklisted) {
            showClock(false);
        }
    }

    /** Initializes views related to the notification icon area. */
    public void initNotificationIconArea() {
        Trace.beginSection("CollapsedStatusBarFragment#initNotifIconArea");
        ViewGroup notificationIconArea = mStatusBar.requireViewById(R.id.notification_icon_area);
        LayoutInflater.from(getContext())
                .inflate(R.layout.notification_icon_area, notificationIconArea, true);
        NotificationIconContainer notificationIcons =
                notificationIconArea.requireViewById(R.id.notificationIcons);
        mNotificationIconAreaInner = notificationIcons;
        int displayId = mHomeStatusBarComponent.getDisplayId();
        mNicBindingDisposable = mNicViewBinder.bindWhileAttached(notificationIcons, displayId);

        if (!StatusBarRootModernization.isEnabled()) {
            updateNotificationIconAreaAndOngoingActivityChip(/* animate= */ false);
        }
        Trace.endSection();
    }

    /**
     * Returns the dagger component for this fragment.
     *
     * TODO(b/205609837): Eventually, the dagger component should encapsulate all status bar
     *   fragment functionality and we won't need to expose it here anymore.
     */
    @Nullable
    public HomeStatusBarComponent getHomeStatusBarComponent() {
        return mHomeStatusBarComponent;
    }

    private StatusBarVisibilityChangeListener mStatusBarVisibilityChangeListener =
            new StatusBarVisibilityChangeListener() {
                @Override
                public void onStatusBarVisibilityMaybeChanged() {
                    if (StatusBarRootModernization.isEnabled()) {
                        return;
                    }
                    updateStatusBarVisibilities(/* animate= */ true);
                }

                @Override
                public void onTransitionFromLockscreenToDreamStarted() {
                    if (StatusBarRootModernization.isEnabled()) {
                        return;
                    }
                    mTransitionFromLockscreenToDreamStarted = true;
                }

                @Override
                public void onOngoingActivityStatusChanged(
                        boolean hasPrimaryOngoingActivity,
                        boolean hasSecondaryOngoingActivity,
                        boolean shouldAnimate) {
                    if (StatusBarRootModernization.isEnabled()) {
                        return;
                    }
                    mHasPrimaryOngoingActivity = hasPrimaryOngoingActivity;
                    mHasSecondaryOngoingActivity = hasSecondaryOngoingActivity;
                    updateStatusBarVisibilities(shouldAnimate);
                }

                @Override
                public void onIsHomeStatusBarAllowedBySceneChanged(
                        boolean isHomeStatusBarAllowedByScene) {
                    if (StatusBarRootModernization.isEnabled()) {
                        return;
                    }
                    mHomeStatusBarAllowedByScene = isHomeStatusBarAllowedByScene;
                    updateStatusBarVisibilities(/* animate= */ true);
                }
    };

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (StatusBarRootModernization.isEnabled()) {
            return;
        }
        if (displayId != getContext().getDisplayId()) {
            return;
        }
        mCollapsedStatusBarFragmentLogger
                .logDisableFlagChange(new DisableFlagsLogger.DisableState(state1, state2));
        mLastSystemVisibility =
                StatusBarVisibilityModel.createModelFromFlags(state1, state2);
        updateStatusBarVisibilities(animate);
    }

    private void updateStatusBarVisibilities(boolean animate) {
        StatusBarRootModernization.assertInLegacyMode();

        StatusBarVisibilityModel previousModel = mLastModifiedVisibility;
        StatusBarVisibilityModel newModel = calculateInternalModel(mLastSystemVisibility);
        mCollapsedStatusBarFragmentLogger.logVisibilityModel(newModel);
        mLastModifiedVisibility = newModel;

        if (newModel.getShowSystemInfo() != previousModel.getShowSystemInfo()) {
            if (newModel.getShowSystemInfo()) {
                showEndSideContent(animate);
                showOperatorName(animate);
            } else {
                hideEndSideContent(animate);
                hideOperatorName(animate);
            }
        }

        // The ongoing activity chip and notification icon visibilities are intertwined, so update
        // both if either change.
        boolean notifsChanged =
                newModel.getShowNotificationIcons() != previousModel.getShowNotificationIcons();
        boolean ongoingActivityChanged =
                newModel.isOngoingActivityStatusDifferentFrom(previousModel);
        if (notifsChanged || ongoingActivityChanged) {
            updateNotificationIconAreaAndOngoingActivityChip(animate);
        }

        // The clock may have already been hidden, but we might want to shift its
        // visibility to GONE from INVISIBLE or vice versa
        if (newModel.getShowClock() != previousModel.getShowClock()
                || mClockController.getClock().getVisibility() != clockHiddenMode()) {
            if (newModel.getShowClock() && !mIsClockBlacklisted) {
                showClock(animate);
            } else {
                hideClock(animate);
            }
        }
    }

    private StatusBarVisibilityModel calculateInternalModel(
            StatusBarVisibilityModel externalModel) {
        StatusBarRootModernization.assertInLegacyMode();

        // TODO(b/328393714) use HeadsUpNotificationInteractor.showHeadsUpStatusBar instead.
        boolean headsUpVisible = mHomeStatusBarComponent
                .getHeadsUpAppearanceController()
                .shouldHeadsUpStatusBarBeVisible();
        if (StatusBarNoHunBehavior.isEnabled()) {
            // With this flag enabled, we have no custom HUN behavior, so just always consider it
            // to be not visible.
            headsUpVisible = false;
        }

        if (SceneContainerFlag.isEnabled()) {
            // With the scene container, only use the value calculated by the view model to
            // determine if the status bar needs hiding.
            if (!mHomeStatusBarAllowedByScene) {
                return createHiddenModel();
            }
        } else {
            // Without the scene container, use our old, mildly-hacky logic to determine if the
            // status bar needs hiding.
            if (!mKeyguardStateController.isLaunchTransitionFadingAway()
                    && !mKeyguardStateController.isKeyguardFadingAway()
                    && shouldHideStatusBar()
                    && !(mStatusBarStateController.getState() == StatusBarState.KEYGUARD
                    && headsUpVisible)) {
                return createHiddenModel();
            }
        }

        boolean showClock = externalModel.getShowClock() && !headsUpVisible;

        boolean showPrimaryOngoingActivityChip = mHasPrimaryOngoingActivity;
        boolean showSecondaryOngoingActivityChip =
                StatusBarNotifChips.isEnabled() && mHasSecondaryOngoingActivity;

        return new StatusBarVisibilityModel(
                showClock,
                externalModel.getShowNotificationIcons(),
                showPrimaryOngoingActivityChip && !headsUpVisible,
                showSecondaryOngoingActivityChip && !headsUpVisible,
                externalModel.getShowSystemInfo());
    }

    /**
     * Updates the visibility of the notification icon area and ongoing activity chip based on
     * mLastModifiedVisibility.
     */
    private void updateNotificationIconAreaAndOngoingActivityChip(boolean animate) {
        StatusBarRootModernization.assertInLegacyMode();

        StatusBarVisibilityModel visibilityModel = mLastModifiedVisibility;
        boolean disableNotifications = !visibilityModel.getShowNotificationIcons();
        boolean hasOngoingActivity = visibilityModel.getShowPrimaryOngoingActivityChip();

        // Hide notifications if the disable flag is set or we have an ongoing activity.
        if (disableNotifications || hasOngoingActivity) {
            hideNotificationIconArea(animate && !hasOngoingActivity);
        } else {
            showNotificationIconArea(animate);
        }

        // Show the ongoing activity chip only if there is an ongoing activity *and* notification
        // icons are allowed. (The ongoing activity chip occupies the same area as the notification,
        // icons so if the icons are disabled then the activity chip should be, too.)
        boolean showPrimaryOngoingActivityChip =
                visibilityModel.getShowPrimaryOngoingActivityChip() && !disableNotifications;
        if (showPrimaryOngoingActivityChip) {
            showPrimaryOngoingActivityChip(animate);
        } else {
            hidePrimaryOngoingActivityChip(animate);
        }

        boolean showSecondaryOngoingActivityChip =
                // Secondary chips are only supported when RONs are enabled.
                StatusBarNotifChips.isEnabled()
                        && visibilityModel.getShowSecondaryOngoingActivityChip()
                        && !disableNotifications;
        if (showSecondaryOngoingActivityChip) {
            showSecondaryOngoingActivityChip(animate);
        } else {
            hideSecondaryOngoingActivityChip(animate);
        }
    }

    private boolean shouldHideStatusBar() {
        StatusBarRootModernization.assertInLegacyMode();

        boolean isDefaultDisplay = getContext().getDisplayId() == Display.DEFAULT_DISPLAY;
        boolean shouldHideForCurrentDisplay =
                !StatusBarConnectedDisplays.isEnabled() || isDefaultDisplay;
        if (!mShadeExpansionStateManager.isClosed()
                && mPanelExpansionInteractor.shouldHideStatusBarIconsWhenExpanded()
                //TODO(b/373310629): for now the shade only shows on the main display.
                // Remove this once there is a display aware API for the shade.
                && shouldHideForCurrentDisplay) {
            return true;
        }

        // When launching the camera over the lockscreen, the icons become visible momentarily
        // before animating out, since we're not yet aware that the launching camera activity is
        // fullscreen. Even once the activity finishes launching, it takes a short time before WM
        // decides that the top app wants to hide the icons and tells us to hide them. To ensure
        // that this high-visibility animation is smooth, keep the icons hidden during a camera
        // launch until we receive a window state change which indicates that the activity is done
        // launching and WM has decided to show/hide the icons. For extra safety (to ensure the
        // icons don't remain hidden somehow) we double check that the camera is still showing, the
        // status bar window isn't hidden, and we're still occluded as well, though these checks
        // are typically unnecessary.
        //
        // TODO(b/273314977): Can this be deleted now that we have the
        //   [isTransitioningFromLockscreenToOccluded] check below?
        final boolean hideIconsForSecureCamera =
                (mWaitingForWindowStateChangeAfterCameraLaunch ||
                        !mStatusBarWindowStateController.windowIsShowing()) &&
                        mKeyguardUpdateMonitor.isSecureCameraLaunchedOverKeyguard() &&
                        mKeyguardStateController.isOccluded();

        if (hideIconsForSecureCamera) {
            return true;
        }

        // Similar to [hideIconsForSecureCamera]: When dream is launched over lockscreen, the icons
        // are momentarily visible because the dream animation has finished, but SysUI has not been
        // informed that the dream is full-screen. For extra safety, we double-check that we're
        // still dreaming.
        final boolean hideIconsForDream =
                mTransitionFromLockscreenToDreamStarted
                        && mKeyguardUpdateMonitor.isDreaming()
                        && mKeyguardStateController.isOccluded();
        if (hideIconsForDream) {
            return true;
        }

        // While the status bar is transitioning from lockscreen to an occluded, we don't yet know
        // if the occluding activity is fullscreen or not. If it *is* fullscreen, we don't want to
        // briefly show the status bar just to immediately hide it again. So, we wait for the
        // transition to occluding to finish before allowing us to potentially show the status bar
        // again. (This status bar is always hidden on keyguard, so it's safe to continue hiding it
        // during this transition.) See b/273314977.
        if (mHomeStatusBarViewModel.isTransitioningFromLockscreenToOccluded().getValue()) {
            return true;
        }

        return mStatusBarHideIconsForBouncerManager.getShouldHideStatusBarIconsForBouncer();
    }

    private void hideEndSideContent(boolean animate) {
        StatusBarRootModernization.assertInLegacyMode();
        if (!animate || !mAnimationsEnabled) {
            mEndSideAlphaController.setAlpha(/*alpha*/ 0f, SOURCE_OTHER);
        } else {
            mEndSideAlphaController.animateToAlpha(/*alpha*/ 0f, SOURCE_OTHER, FADE_OUT_DURATION,
                    InterpolatorsAndroidX.ALPHA_OUT, /*startDelay*/ 0);
        }
    }

    private void showEndSideContent(boolean animate) {
        StatusBarRootModernization.assertInLegacyMode();
        if (!animate || !mAnimationsEnabled) {
            mEndSideAlphaController.setAlpha(1f, SOURCE_OTHER);
            return;
        }
        if (mKeyguardStateController.isKeyguardFadingAway()) {
            mEndSideAlphaController.animateToAlpha(/*alpha*/ 1f, SOURCE_OTHER,
                    mKeyguardStateController.getKeyguardFadingAwayDuration(),
                    InterpolatorsAndroidX.LINEAR_OUT_SLOW_IN,
                    mKeyguardStateController.getKeyguardFadingAwayDelay());
        } else {
            mEndSideAlphaController.animateToAlpha(/*alpha*/ 1f, SOURCE_OTHER, FADE_IN_DURATION,
                    InterpolatorsAndroidX.ALPHA_IN, FADE_IN_DELAY);
        }
    }

    private void hideClock(boolean animate) {
        StatusBarRootModernization.assertInLegacyMode();
        animateHiddenState(mClockController.getClock(), clockHiddenMode(), animate);
    }

    private void showClock(boolean animate) {
        StatusBarRootModernization.assertInLegacyMode();
        animateShow(mClockController.getClock(), animate);
    }

    /** Hides the primary ongoing activity chip. */
    private void hidePrimaryOngoingActivityChip(boolean animate) {
        StatusBarRootModernization.assertInLegacyMode();
        animateHiddenState(mPrimaryOngoingActivityChip, View.GONE, animate);
    }

    /**
     * Displays the primary ongoing activity chip.
     */
    private void showPrimaryOngoingActivityChip(boolean animate) {
        StatusBarRootModernization.assertInLegacyMode();
        animateShow(mPrimaryOngoingActivityChip, animate);
    }

    private void hideSecondaryOngoingActivityChip(boolean animate) {
        StatusBarRootModernization.assertInLegacyMode();
        animateHiddenState(mSecondaryOngoingActivityChip, View.GONE, animate);
    }

    private void showSecondaryOngoingActivityChip(boolean animate) {
        StatusBarNotifChips.unsafeAssertInNewMode();
        StatusBarRootModernization.assertInLegacyMode();
        animateShow(mSecondaryOngoingActivityChip, animate);
    }

    /**
     * If panel is expanded/expanding it usually means QS shade is opening, so
     * don't set the clock GONE otherwise it'll mess up the animation.
     */
    private int clockHiddenMode() {
        StatusBarRootModernization.assertInLegacyMode();
        if (!mShadeExpansionStateManager.isClosed() && !mKeyguardStateController.isShowing()
                && !mStatusBarStateController.isDozing() && mClockController.getClock().shouldBeVisible()) {
            return View.INVISIBLE;
        }
        return View.GONE;
    }

    public void hideNotificationIconArea(boolean animate) {
        StatusBarRootModernization.assertInLegacyMode();
        animateHide(mNotificationIconAreaInner, animate);
    }

    public void showNotificationIconArea(boolean animate) {
        StatusBarRootModernization.assertInLegacyMode();
        animateShow(mNotificationIconAreaInner, animate);
    }

    public void hideOperatorName(boolean animate) {
        StatusBarRootModernization.assertInLegacyMode();
        if (mOperatorNameViewController != null) {
            animateHide(mOperatorNameViewController.getView(), animate);
        }
    }

    public void showOperatorName(boolean animate) {
        StatusBarRootModernization.assertInLegacyMode();
        if (mOperatorNameViewController != null) {
            animateShow(mOperatorNameViewController.getView(), animate);
        }
    }

    /**
     * Animate a view to INVISIBLE or GONE
     */
    private void animateHiddenState(final View v, int state, boolean animate) {
        StatusBarRootModernization.assertInLegacyMode();
        v.animate().cancel();
        if (!animate || !mAnimationsEnabled) {
            v.setAlpha(0f);
            v.setVisibility(state);
            return;
        }

        v.animate()
                .alpha(0f)
                .setDuration(FADE_OUT_DURATION)
                .setStartDelay(0)
                .setInterpolator(Interpolators.ALPHA_OUT)
                .withEndAction(() -> v.setVisibility(state));
    }

    /**
     * Hides a view.
     */
    private void animateHide(final View v, boolean animate) {
        StatusBarRootModernization.assertInLegacyMode();
        animateHiddenState(v, View.INVISIBLE, animate);
    }

    /**
     * Shows a view, and synchronizes the animation with Keyguard exit animations, if applicable.
     */
    private void animateShow(View v, boolean animate) {
        StatusBarRootModernization.assertInLegacyMode();
        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        if (!animate || !mAnimationsEnabled) {
            v.setAlpha(1f);
            return;
        }
        v.animate()
                .alpha(1f)
                .setDuration(FADE_IN_DURATION)
                .setInterpolator(Interpolators.ALPHA_IN)
                .setStartDelay(FADE_IN_DELAY)

                // We need to clean up any pending end action from animateHide if we call
                // both hide and show in the same frame before the animation actually gets started.
                // cancel() doesn't really remove the end action.
                .withEndAction(null);

        // Synchronize the motion with the Keyguard fading if necessary.
        if (mKeyguardStateController.isKeyguardFadingAway()) {
            v.animate()
                    .setDuration(mKeyguardStateController.getKeyguardFadingAwayDuration())
                    .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                    .setStartDelay(mKeyguardStateController.getKeyguardFadingAwayDelay())
                    .start();
        }
    }

    private void initOperatorName() {
        int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        if (mCarrierConfigTracker.getShowOperatorNameInStatusBarConfig(subId)) {
            View view = mStatusBar.findViewById(R.id.operator_name);
            mOperatorNameViewController =
                    mOperatorNameViewControllerFactory.create(
                            (OperatorNameView) view,
                            mHomeStatusBarComponent.getDarkIconDispatcher());
            mOperatorNameViewController.init();
            // This view should not be visible on lock-screen
            if (mKeyguardStateController.isShowing()) {
                if (!StatusBarRootModernization.isEnabled()) {
                    hideOperatorName(false);
                }
            }
        }
    }

    private void initOngoingCallChip() {
        if (!StatusBarRootModernization.isEnabled()) {
            mOngoingCallController.addCallback(mOngoingCallListener);
        }
        // TODO(b/364653005): Do we also need to set the secondary activity chip?
        if (!StatusBarChipsModernization.isEnabled()) {
            mOngoingCallController.setChipView(mPrimaryOngoingActivityChip);
        }
    }

    @Override
    public void onStateChanged(int newState) { }

    @Override
    public void onDozingChanged(boolean isDozing) {
        if (StatusBarRootModernization.isEnabled()) {
            return;
        }
        updateStatusBarVisibilities(/* animate= */ false);
    }

    @Nullable
    @Override
    public Animator onSystemEventAnimationBegin() {
        return mSystemEventAnimator.onSystemEventAnimationBegin();
    }

    @Nullable
    @Override
    public Animator onSystemEventAnimationFinish(boolean hasPersistentDot) {
        return mSystemEventAnimator.onSystemEventAnimationFinish(hasPersistentDot);
    }

    private StatusBarSystemEventDefaultAnimator getSystemEventAnimator() {
        return new StatusBarSystemEventDefaultAnimator(getResources(), (alpha) -> {
            mEndSideAlphaController.setAlpha(alpha, SOURCE_SYSTEM_EVENT_ANIMATOR);
            return Unit.INSTANCE;
        }, (translationX) -> {
            mEndSideContent.setTranslationX(translationX);
            return Unit.INSTANCE;
        }, /*isAnimationRunning*/ false);
    }

    private final ContentObserver mVolumeSettingObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
            updateBlockedIcons();
        }
    };

    @Override
    public void dump(PrintWriter printWriter, String[] args) {
        IndentingPrintWriter pw = new IndentingPrintWriter(printWriter, /* singleIndent= */"  ");
        pw.println("mHasPrimaryOngoingActivity=" + mHasPrimaryOngoingActivity);
        pw.println("mHasSecondaryOngoingActivity=" + mHasSecondaryOngoingActivity);
        pw.println("mAnimationsEnabled=" + mAnimationsEnabled);
        HomeStatusBarComponent component = mHomeStatusBarComponent;
        if (component == null) {
            pw.println("StatusBarFragmentComponent is null");
        } else {
            Set<Startable> startables = component.getStartables();
            pw.println("Startables: " + startables.size());
            pw.increaseIndent();
            for (Startable startable : startables) {
                Startable.State startableState = mStartableStates.getOrDefault(startable,
                        Startable.State.NONE);
                pw.println(startable + ", state: " + startableState);
            }
            pw.decreaseIndent();
        }
    }
}
