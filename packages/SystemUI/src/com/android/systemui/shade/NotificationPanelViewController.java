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

package com.android.systemui.shade;

import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

import static com.android.app.animation.Interpolators.EMPHASIZED_DECELERATE;
import static com.android.systemui.Flags.msdlFeedback;
import static com.android.systemui.Flags.predictiveBackAnimateShade;
import static com.android.systemui.classifier.Classifier.BOUNCER_UNLOCK;
import static com.android.systemui.classifier.Classifier.GENERIC;
import static com.android.systemui.classifier.Classifier.QUICK_SETTINGS;
import static com.android.systemui.classifier.Classifier.UNLOCK;
import static com.android.systemui.keyguard.shared.model.KeyguardState.AOD;
import static com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN;
import static com.android.systemui.navigationbar.gestural.Utilities.isTrackpadThreeFingerSwipe;
import static com.android.systemui.shade.ShadeExpansionStateManagerKt.STATE_CLOSED;
import static com.android.systemui.shade.ShadeExpansionStateManagerKt.STATE_OPEN;
import static com.android.systemui.shade.ShadeExpansionStateManagerKt.STATE_OPENING;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_NOTIFICATION_PANEL_VISIBLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_QUICK_SETTINGS_EXPANDED;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE;
import static com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED;
import static com.android.systemui.statusbar.notification.stack.StackStateAnimator.ANIMATION_DURATION_FOLD_TO_AOD;
import static com.android.systemui.util.DumpUtilsKt.asIndenting;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import static kotlinx.coroutines.flow.StateFlowKt.MutableStateFlow;

import static java.lang.Float.isNaN;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.hardware.power.Boost;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManagerInternal;
import android.os.Trace;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.MathUtils;
import android.view.Display;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.View.AccessibilityDelegate;
import android.view.ViewConfiguration;
import android.view.ViewPropertyAnimator;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.app.animation.Interpolators;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.policy.SystemBarUtils;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.LatencyTracker;
import com.android.internal.util.yaap.YaapUtils;
import com.android.keyguard.ActiveUnlockConfig;
import com.android.keyguard.KeyguardUnfoldTransition;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.dagger.KeyguardStatusBarViewComponent;
import com.android.server.LocalServices;
import com.android.systemui.DejankUtils;
import com.android.systemui.Dumpable;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.classifier.Classifier;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.common.domain.interactor.SysUIStateDisplaysInteractor;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.dump.DumpsysTableLogger;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.shared.model.ClockSize;
import com.android.systemui.keyguard.shared.model.Edge;
import com.android.systemui.keyguard.shared.model.TransitionState;
import com.android.systemui.keyguard.shared.model.TransitionStep;
import com.android.systemui.keyguard.ui.binder.KeyguardTouchViewBinder;
import com.android.systemui.keyguard.ui.transitions.BlurConfig;
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel;
import com.android.systemui.keyguard.ui.viewmodel.KeyguardTouchHandlingViewModel;
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager;
import com.android.systemui.media.controls.ui.controller.KeyguardMediaController;
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager;
import com.android.systemui.model.StateChange;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.navigationbar.views.NavigationBarView;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.FalsingManager.FalsingTapListener;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.power.shared.model.WakefulnessModel;
import com.android.systemui.qs.flags.QSComposeFragment;
import com.android.systemui.res.R;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.settings.brightness.data.repository.BrightnessMirrorShowingRepository;
import com.android.systemui.settings.brightness.domain.interactor.BrightnessMirrorShowingInteractor;
import com.android.systemui.shade.data.repository.FlingInfo;
import com.android.systemui.shade.data.repository.ShadeDisplaysRepository;
import com.android.systemui.shade.data.repository.ShadeRepository;
import com.android.systemui.shade.domain.interactor.ShadeAnimationInteractor;
import com.android.systemui.shade.shared.flag.ShadeWindowGoesAround;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.KeyguardIndicationController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.PulseExpansionHandler;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.ConversationNotificationManager;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.ViewGroupFadeHelper;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor;
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager;
import com.android.systemui.statusbar.notification.headsup.HeadsUpTouchHelper;
import com.android.systemui.statusbar.notification.headsup.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.HeadsUpAppearanceController;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.KeyguardClockPositionAlgorithm;
import com.android.systemui.statusbar.phone.KeyguardStatusBarViewController;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger;
import com.android.systemui.statusbar.phone.LockscreenGestureLogger.LockscreenUiEvent;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.ShadeTouchableRegionManager;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.TapAgainViewController;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.SplitShadeStateController;
import com.android.systemui.unfold.SysUIUnfoldComponent;
import com.android.systemui.util.Compile;
import com.android.systemui.util.Utils;
import com.android.systemui.util.time.SystemClock;
import com.android.systemui.wallpapers.ui.viewmodel.WallpaperFocalAreaViewModel;
import com.android.wm.shell.animation.FlingAnimationUtils;

import dalvik.annotation.optimization.NeverCompile;

import com.google.android.msdl.data.model.MSDLToken;
import com.google.android.msdl.domain.MSDLPlayer;

import dagger.Lazy;

import kotlin.Unit;

import kotlinx.coroutines.CoroutineDispatcher;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Provider;

@SysUISingleton
public final class NotificationPanelViewController implements
        ShadeSurface, Dumpable, BrightnessMirrorShowingInteractor {

    public static final String TAG = NotificationPanelView.class.getSimpleName();
    private static final boolean DEBUG_LOGCAT = Compile.IS_DEBUG && Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean DEBUG_DRAWABLE = false;
    /** The parallax amount of the quick settings translation when dragging down the panel. */
    public static final float QS_PARALLAX_AMOUNT = 0.175f;
    private static final int NO_FIXED_DURATION = -1;
    private static final long SHADE_OPEN_SPRING_OUT_DURATION = 350L;
    private static final long SHADE_OPEN_SPRING_BACK_DURATION = 400L;

    /**
     * The factor of the usual high velocity that is needed in order to reach the maximum overshoot
     * when flinging. A low value will make it that most flings will reach the maximum overshoot.
     */
    private static final float FACTOR_OF_HIGH_VELOCITY_FOR_MAX_OVERSHOOT = 0.5f;
    /**
     * Maximum time before which we will expand the panel even for slow motions when getting a
     * touch passed over from launcher.
     */
    private static final int MAX_TIME_TO_OPEN_WHEN_FLINGING_FROM_LAUNCHER = 300;
    private static final int MAX_DOWN_EVENT_BUFFER_SIZE = 50;
    private static final String COUNTER_PANEL_OPEN = "panel_open";
    public static final String COUNTER_PANEL_OPEN_QS = "panel_open_qs";
    private static final String COUNTER_PANEL_OPEN_PEEK = "panel_open_peek";
    private static final Rect M_DUMMY_DIRTY_RECT = new Rect(0, 0, 1, 1);
    private static final Rect EMPTY_RECT = new Rect();
    //TODO(b/394977231) delete this temporary workaround used only by tests
    private static final boolean DISABLE_LONG_PRESS_EXPAND = Build.HARDWARE.equals("cutf_cvm");
    /**
     * Whether the Shade should animate to reflect Back gesture progress.
     * To minimize latency at runtime, we cache this, else we'd be reading it every time
     * updateQsExpansion() is called... and it's called very often.
     * <p>
     * Whenever we change this flag, SysUI is restarted, so it's never going to be "stale".
     */

    public final boolean mAnimateBack;
    /**
     * The minimum scale to "squish" the Shade and associated elements down to, for Back gesture
     */
    public static final float SHADE_BACK_ANIM_MIN_SCALE = 0.9f;
    private final ShadeTouchableRegionManager mShadeTouchableRegionManager;
    private final Resources mResources;
    private final KeyguardStateController mKeyguardStateController;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final AmbientState mAmbientState;
    private final LockscreenGestureLogger mLockscreenGestureLogger;
    private final SystemClock mSystemClock;
    private final ShadeLogger mShadeLog;
    private final DozeParameters mDozeParameters;
    private final NotificationStackScrollLayout.OnEmptySpaceClickListener
            mOnEmptySpaceClickListener = this::onEmptySpaceClick;
    private final ShadeHeadsUpChangedListener mOnHeadsUpChangedListener =
            new ShadeHeadsUpChangedListener();
    private final ConfigurationListener mConfigurationListener = new ConfigurationListener();
    private final StatusBarStateListener mStatusBarStateListener = new StatusBarStateListener();
    private final NotificationPanelView mView;
    private final VibratorHelper mVibratorHelper;
    private final MSDLPlayer mMSDLPlayer;
    private final MetricsLogger mMetricsLogger;
    private final ConfigurationController mConfigurationController;
    private final Provider<FlingAnimationUtils.Builder> mFlingAnimationUtilsBuilder;
    private final NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    private final AccessibilityManager mAccessibilityManager;
    private final NotificationWakeUpCoordinator mWakeUpCoordinator;
    private final PulseExpansionHandler mPulseExpansionHandler;
    private final KeyguardBypassController mKeyguardBypassController;
    private final KeyguardUpdateMonitor mUpdateMonitor;
    private final DeviceEntryFaceAuthInteractor mDeviceEntryFaceAuthInteractor;
    private final ConversationNotificationManager mConversationNotificationManager;
    private final MediaHierarchyManager mMediaHierarchyManager;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final KeyguardStatusBarViewComponent.Factory mKeyguardStatusBarViewComponentFactory;
    private final FragmentService mFragmentService;
    private final IStatusBarService mStatusBarService;
    private final ScrimController mScrimController;
    private final LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    private final TapAgainViewController mTapAgainViewController;
    private final ShadeHeaderController mShadeHeaderController;
    private final boolean mVibrateOnOpening;
    private final VelocityTracker mVelocityTracker = VelocityTracker.obtain();
    private final FlingAnimationUtils mFlingAnimationUtilsClosing;
    private final FlingAnimationUtils mFlingAnimationUtilsDismissing;
    private final LatencyTracker mLatencyTracker;
    private final DozeLog mDozeLog;
    /** Whether or not the NotificationPanelView can be expanded or collapsed with a drag. */
    private final boolean mNotificationsDragEnabled;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private final ShadeExpansionStateManager mShadeExpansionStateManager;
    private final ShadeRepository mShadeRepository;
    private final ShadeAnimationInteractor mShadeAnimationInteractor;
    private final FalsingTapListener mFalsingTapListener = this::falsingAdditionalTapRequired;
    private final AccessibilityDelegate mAccessibilityDelegate = new ShadeAccessibilityDelegate();
    private final NotificationGutsManager mGutsManager;
    private final AlternateBouncerInteractor mAlternateBouncerInteractor;
    private final QuickSettingsControllerImpl mQsController;
    private final TouchHandler mTouchHandler = new TouchHandler();
    private final BlurConfig mBlurConfig;

    private long mDownTime;
    private long mStatusBarLongPressDowntime = -1L;
    private boolean mTouchSlopExceededBeforeDown;
    private float mOverExpansion;
    private CentralSurfaces mCentralSurfaces;
    private HeadsUpManager mHeadsUpManager;
    private float mExpandedHeight = 0;
    /** The current squish amount for the predictive back animation */
    private float mCurrentBackProgress = 0.0f;
    private boolean mExpanding;
    private boolean mSplitShadeEnabled;
    private KeyguardStatusBarViewController mKeyguardStatusBarViewController;
    private NotificationsQuickSettingsContainer mNotificationContainerParent;
    private final NotificationsQSContainerController mNotificationsQSContainerController;
    private boolean mAnimateNextPositionUpdate;
    private final ScreenOffAnimationController mScreenOffAnimationController;
    private final UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    private TrackingStartedListener mTrackingStartedListener;
    private OpenCloseListener mOpenCloseListener;
    private GestureRecorder mGestureRecorder;

    private boolean mDozing;
    private boolean mDozingOnDown;
    private boolean mBouncerShowing;
    private int mBarState;
    private FlingAnimationUtils mFlingAnimationUtils;
    private int mStatusBarMinHeight;
    private int mStatusBarHeaderHeightKeyguard;
    private float mOverStretchAmount;
    private float mDownX;
    private float mDownY;
    private int mDisplayTopInset = 0; // in pixels
    private int mDisplayRightInset = 0; // in pixels
    private int mDisplayLeftInset = 0; // in pixels

    private final GestureDetector mDoubleTapToSleepGesture;
    private boolean mIsLockscreenDoubleTapEnabled;
    private boolean mIsSbDoubleTapEnabled;
    private int mStatusBarHeaderHeight;

    @VisibleForTesting
    KeyguardClockPositionAlgorithm mClockPositionAlgorithm;
    private final KeyguardClockPositionAlgorithm.Result
            mClockPositionResult =
            new KeyguardClockPositionAlgorithm.Result();
    /**
     * Indicates shade (or just QS) is expanding or collapsing but doesn't fully cover KEYGUARD
     * state when shade can be expanded with swipe down or swipe down from the top to full QS.
     */
    private boolean mIsExpandingOrCollapsing;

    /**
     * Indicates drag starting height when swiping down or up on heads-up notifications.
     * This usually serves as a threshold from when shade expansion should really start. Otherwise
     * this value would be height of shade and it will be immediately expanded to some extent.
     */
    private int mHeadsUpStartHeight;
    private HeadsUpTouchHelper mHeadsUpTouchHelper;
    private boolean mListenForHeadsUp;
    private int mNavigationBarBottomHeight;
    private boolean mExpandingFromHeadsUp;
    private boolean mCollapsedOnDown;
    private boolean mClosingWithAlphaFadeOut;
    private boolean mHeadsUpAnimatingAway;
    private final FalsingManager mFalsingManager;
    private final FalsingCollector mFalsingCollector;
    private final ShadeHeadsUpTrackerImpl mShadeHeadsUpTracker = new ShadeHeadsUpTrackerImpl();
    private final ShadeFoldAnimatorImpl mShadeFoldAnimator = new ShadeFoldAnimatorImpl();

    @VisibleForTesting
    Set<Animator> mTestSetOfAnimatorsUsed;

    private boolean mShowIconsWhenExpanded;
    /** Whether the notifications are displayed full width (no margins on the side). */
    private boolean mIsFullWidth;
    private boolean mBlockingExpansionForCurrentTouch;
    // Following variables maintain state of events when input focus transfer may occur.
    private boolean mExpectingSynthesizedDown;
    private boolean mLastEventSynthesizedDown;

    /** Current dark amount that follows regular interpolation curve of animation. */
    private float mInterpolatedDarkAmount;
    /**
     * Dark amount that animates from 0 to 1 or vice-versa in linear manner, even if the
     * interpolation curve is different.
     */
    private float mLinearDarkAmount;
    private boolean mPulsing;
    private int mStackScrollerMeasuringPass;
    /** Non-null if a heads-up notification's position is being tracked. */
    @Nullable
    private ExpandableNotificationRow mTrackedHeadsUpNotification;
    private final ArrayList<Consumer<ExpandableNotificationRow>>
            mTrackingHeadsUpListeners = new ArrayList<>();
    private HeadsUpAppearanceController mHeadsUpAppearanceController;

    private final BrightnessMirrorShowingRepository mBrightnessMirrorShowingRepository;
    /**
     * This flow would track whether the brightness mirror should be showing, but aware of the
     * alpha transitions of NPV.
     *
     * When the repository flow emits true, this will also emit true (and start the alpha animation
     * of NPV to go to 0f). However, when the repository emits false, this will first animate the
     * alpha to 1f, and then emit false. This guarantees that the mirror is always showing while
     * the alpha of NPV is animating.
     */
    private final MutableStateFlow<Boolean> mIsBrightnessMirrorShowing = MutableStateFlow(false);

    private int mPanelAlpha;
    private Runnable mPanelAlphaEndAction;
    private final AnimatableProperty mPanelAlphaAnimator = AnimatableProperty.from("panelAlpha",
            (view, alpha) -> {
                setAlphaInternal(alpha);
            },
            NotificationPanelView::getCurrentPanelAlpha,
            R.id.panel_alpha_animator_tag, R.id.panel_alpha_animator_start_tag,
            R.id.panel_alpha_animator_end_tag);
    private final AnimationProperties mPanelAlphaOutPropertiesAnimator =
            new AnimationProperties().setDuration(150).setCustomInterpolator(
                    mPanelAlphaAnimator.getProperty(), Interpolators.ALPHA_OUT);
    private final AnimationProperties mPanelAlphaInPropertiesAnimator =
            new AnimationProperties().setDuration(200).setAnimationEndAction((property) -> {
                if (mPanelAlphaEndAction != null) {
                    mPanelAlphaEndAction.run();
                }
                // Once the animation for the alpha has finished (NPV is visible again), dismiss
                // the mirror
                postToView(() -> mIsBrightnessMirrorShowing.setValue(false));
            }).setCustomInterpolator(
                    mPanelAlphaAnimator.getProperty(), Interpolators.ALPHA_IN);

    private final CommandQueue mCommandQueue;
    private final MediaDataManager mMediaDataManager;
    @PanelState
    private int mCurrentPanelState = STATE_CLOSED;
    @Deprecated // Use SysUIStateInteractor instead
    private final SysUiState mSysUiState;
    private final SysUIStateDisplaysInteractor mSysUIStateDisplaysInteractor;
    private final Lazy<ShadeDisplaysRepository> mShadeDisplaysRepository;
    private final NotificationShadeDepthController mDepthController;
    private final NavigationBarController mNavigationBarController;
    private final int mDisplayId;

    private final KeyguardIndicationController mKeyguardIndicationController;
    private int mHeadsUpInset;
    private boolean mHeadsUpPinnedMode;
    private boolean mAllowExpandForSmallExpansion;
    private Runnable mExpandAfterLayoutRunnable;
    private Runnable mHideExpandedRunnable;

    /** The maximum overshoot allowed for the top padding for the full shade transition. */
    private int mMaxOverscrollAmountForPulse;

    /** Whether a collapse that started on the panel should allow the panel to intercept. */
    private boolean mIsPanelCollapseOnQQS;

    /** Are we currently in gesture navigation. */
    private boolean mIsGestureNavigation;
    private int mOldLayoutDirection;

    private float mMinFraction;

    private final KeyguardMediaController mKeyguardMediaController;

    private final Optional<KeyguardUnfoldTransition> mKeyguardUnfoldTransition;

    /** The drag distance required to fully expand the split shade. */
    private int mSplitShadeFullTransitionDistance;
    /** The drag distance required to fully transition scrims. */
    private int mSplitShadeScrimTransitionDistance;

    private final NotificationListContainer mNotificationListContainer;
    private final NPVCDownEventState.Buffer mLastDownEvents;
    private final KeyguardClockInteractor mKeyguardClockInteractor;
    private final WallpaperFocalAreaViewModel mWallpaperFocalAreaViewModel;
    private float mMinExpandHeight;
    private boolean mPanelUpdateWhenAnimatorEnds;
    private boolean mHasVibratedOnOpen = false;
    private int mFixedDuration = NO_FIXED_DURATION;
    /** The overshoot amount when the panel flings open. */
    private float mPanelFlingOvershootAmount;
    /** The amount of pixels that we have overexpanded the last time with a gesture. */
    private float mLastGesturedOverExpansion = -1;
    /** Whether the current animator is the spring back animation. */
    private boolean mIsSpringBackAnimation;
    private float mHintDistance;
    private float mInitialOffsetOnTouch;
    private boolean mCollapsedAndHeadsUpOnDown;
    private float mExpandedFraction = 0;
    private float mExpansionDragDownAmountPx = 0;
    private boolean mPanelClosedOnDown;
    private boolean mHasLayoutedSinceDown;
    private float mUpdateFlingVelocity;
    private boolean mUpdateFlingOnLayout;
    private boolean mTouchSlopExceeded;
    private int mTrackingPointer;
    private int mTouchSlop;
    private float mSlopMultiplier;
    private boolean mTouchAboveFalsingThreshold;
    private boolean mTouchStartedInEmptyArea;
    private boolean mMotionAborted;
    private boolean mUpwardsWhenThresholdReached;
    private boolean mAnimatingOnDown;
    private boolean mHandlingPointerUp;
    private ValueAnimator mHeightAnimator;
    /** Whether an instant expand request is currently pending and we are waiting for layout. */
    private boolean mInstantExpanding;
    private boolean mAnimateAfterExpanding;
    private boolean mIsFlinging;
    private String mViewName;
    private float mInitialExpandY;
    private float mInitialExpandX;
    private boolean mTouchDisabled;
    private boolean mInitialTouchFromKeyguard;
    /** Speed-up factor to be used when {@link #mFlingCollapseRunnable} runs the next time. */
    private float mNextCollapseSpeedUpFactor = 1.0f;
    private boolean mGestureWaitForTouchSlop;
    private boolean mIgnoreXTouchSlop;
    private boolean mExpandLatencyTracking;
    private boolean mUseExternalTouch = false;
    private final DreamingToLockscreenTransitionViewModel mDreamingToLockscreenTransitionViewModel;
    private final SharedNotificationContainerInteractor mSharedNotificationContainerInteractor;
    private final ActiveNotificationsInteractor mActiveNotificationsInteractor;
    private final KeyguardTransitionInteractor mKeyguardTransitionInteractor;
    private final KeyguardInteractor mKeyguardInteractor;
    private final PowerInteractor mPowerInteractor;
    private final CoroutineDispatcher mMainDispatcher;
    private final SplitShadeStateController mSplitShadeStateController;

    private final PowerManagerInternal mLocalPowerManager;

    private final Runnable mFlingCollapseRunnable = () -> fling(0, false /* expand */,
            mNextCollapseSpeedUpFactor, false /* expandBecauseOfFalsing */);
    private final Runnable mHeadsUpExistenceChangedRunnable = () -> {
        setHeadsUpAnimatingAway(false);
        updateExpansionAndVisibility();
    };
    private final Runnable mMaybeHideExpandedRunnable = () -> {
        if (getExpandedFraction() == 0.0f) {
            postToView(mHideExpandedRunnable);
        }
    };

    private final ActivityStarter mActivityStarter;

    @Nullable
    private RenderEffect mBlurRenderEffect = null;

    @Inject
    public NotificationPanelViewController(NotificationPanelView view,
            NotificationWakeUpCoordinator coordinator,
            PulseExpansionHandler pulseExpansionHandler,
            DynamicPrivacyController dynamicPrivacyController,
            KeyguardBypassController bypassController,
            FalsingManager falsingManager,
            FalsingCollector falsingCollector,
            KeyguardStateController keyguardStateController,
            StatusBarStateController statusBarStateController,
            NotificationShadeWindowController notificationShadeWindowController,
            DozeLog dozeLog,
            DozeParameters dozeParameters,
            CommandQueue commandQueue,
            VibratorHelper vibratorHelper,
            LatencyTracker latencyTracker,
            AccessibilityManager accessibilityManager,
            @DisplayId int displayId,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            MetricsLogger metricsLogger,
            ShadeLogger shadeLogger,
            @ShadeDisplayAware ConfigurationController configurationController,
            Provider<FlingAnimationUtils.Builder> flingAnimationUtilsBuilder,
            ShadeTouchableRegionManager shadeTouchableRegionManager,
            ConversationNotificationManager conversationNotificationManager,
            MediaHierarchyManager mediaHierarchyManager,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            NotificationGutsManager gutsManager,
            NotificationsQSContainerController notificationsQSContainerController,
            NotificationStackScrollLayoutController notificationStackScrollLayoutController,
            KeyguardStatusBarViewComponent.Factory keyguardStatusBarViewComponentFactory,
            LockscreenShadeTransitionController lockscreenShadeTransitionController,
            ScrimController scrimController,
            MediaDataManager mediaDataManager,
            NotificationShadeDepthController notificationShadeDepthController,
            AmbientState ambientState,
            KeyguardMediaController keyguardMediaController,
            TapAgainViewController tapAgainViewController,
            NavigationModeController navigationModeController,
            NavigationBarController navigationBarController,
            QuickSettingsControllerImpl quickSettingsController,
            FragmentService fragmentService,
            IStatusBarService statusBarService,
            ShadeHeaderController shadeHeaderController,
            ScreenOffAnimationController screenOffAnimationController,
            LockscreenGestureLogger lockscreenGestureLogger,
            ShadeExpansionStateManager shadeExpansionStateManager,
            ShadeRepository shadeRepository,
            Optional<SysUIUnfoldComponent> unfoldComponent,
            SysUiState sysUiState,
            SysUIStateDisplaysInteractor sysUIStateDisplaysInteractor,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            KeyguardIndicationController keyguardIndicationController,
            NotificationListContainer notificationListContainer,
            UnlockedScreenOffAnimationController unlockedScreenOffAnimationController,
            SystemClock systemClock,
            KeyguardClockInteractor keyguardClockInteractor,
            AlternateBouncerInteractor alternateBouncerInteractor,
            DreamingToLockscreenTransitionViewModel dreamingToLockscreenTransitionViewModel,
            @Main CoroutineDispatcher mainDispatcher,
            KeyguardTransitionInteractor keyguardTransitionInteractor,
            DumpManager dumpManager,
            KeyguardTouchHandlingViewModel keyguardTouchHandlingViewModel,
            WallpaperFocalAreaViewModel wallpaperFocalAreaViewModel,
            KeyguardInteractor keyguardInteractor,
            ActivityStarter activityStarter,
            SharedNotificationContainerInteractor sharedNotificationContainerInteractor,
            ActiveNotificationsInteractor activeNotificationsInteractor,
            ShadeAnimationInteractor shadeAnimationInteractor,
            DeviceEntryFaceAuthInteractor deviceEntryFaceAuthInteractor,
            SplitShadeStateController splitShadeStateController,
            PowerInteractor powerInteractor,
            KeyguardClockPositionAlgorithm keyguardClockPositionAlgorithm,
            MSDLPlayer msdlPlayer,
            BrightnessMirrorShowingRepository brightnessMirrorShowingRepository,
            BlurConfig blurConfig,
            Lazy<ShadeDisplaysRepository> shadeDisplaysRepository) {
        mBlurConfig = blurConfig;
        SceneContainerFlag.assertInLegacyMode();
        keyguardStateController.addCallback(new KeyguardStateController.Callback() {
            @Override
            public void onKeyguardFadingAwayChanged() {
                updateExpandedHeightToMaxHeight();
            }
        });
        mAmbientState = ambientState;
        mView = view;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mLockscreenGestureLogger = lockscreenGestureLogger;
        mShadeExpansionStateManager = shadeExpansionStateManager;
        mShadeRepository = shadeRepository;
        mShadeAnimationInteractor = shadeAnimationInteractor;
        mShadeLog = shadeLogger;
        mGutsManager = gutsManager;
        mDreamingToLockscreenTransitionViewModel = dreamingToLockscreenTransitionViewModel;
        mKeyguardTransitionInteractor = keyguardTransitionInteractor;
        mSharedNotificationContainerInteractor = sharedNotificationContainerInteractor;
        mActiveNotificationsInteractor = activeNotificationsInteractor;
        mKeyguardInteractor = keyguardInteractor;
        mPowerInteractor = powerInteractor;
        mClockPositionAlgorithm = keyguardClockPositionAlgorithm;
        mView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                mViewName = mResources.getResourceName(mView.getId());
            }

            @Override
            public void onViewDetachedFromWindow(View v) {}
        });

        mView.addOnLayoutChangeListener(new ShadeLayoutChangeListener());
        mView.setOnTouchListener(getTouchHandler());
        mView.setOnConfigurationChangedListener(config -> loadDimens());

        mResources = mView.getResources();
        mKeyguardStateController = keyguardStateController;
        mQsController = quickSettingsController;
        mKeyguardIndicationController = keyguardIndicationController;
        mStatusBarStateController = (SysuiStatusBarStateController) statusBarStateController;
        mNotificationShadeWindowController = notificationShadeWindowController;
        FlingAnimationUtils.Builder fauBuilder = flingAnimationUtilsBuilder.get();
        mFlingAnimationUtils = fauBuilder
                .reset()
                .setMaxLengthSeconds(FLING_MAX_LENGTH_SECONDS)
                .setSpeedUpFactor(FLING_SPEED_UP_FACTOR)
                .build();
        mFlingAnimationUtilsClosing = fauBuilder
                .reset()
                .setMaxLengthSeconds(FLING_CLOSING_MAX_LENGTH_SECONDS)
                .setSpeedUpFactor(FLING_CLOSING_SPEED_UP_FACTOR)
                .build();
        mFlingAnimationUtilsDismissing = fauBuilder
                .reset()
                .setMaxLengthSeconds(0.5f)
                .setSpeedUpFactor(0.6f)
                .setX2(0.6f)
                .setY2(0.84f)
                .build();
        mLatencyTracker = latencyTracker;
        mFalsingManager = falsingManager;
        mDozeLog = dozeLog;
        mNotificationsDragEnabled = mResources.getBoolean(
                R.bool.config_enableNotificationShadeDrag);
        mVibratorHelper = vibratorHelper;
        mMSDLPlayer = msdlPlayer;
        mVibrateOnOpening = mResources.getBoolean(R.bool.config_vibrateOnIconAnimation);
        mShadeTouchableRegionManager = shadeTouchableRegionManager;
        mSystemClock = systemClock;
        mKeyguardMediaController = keyguardMediaController;
        mMetricsLogger = metricsLogger;
        mConfigurationController = configurationController;
        mFlingAnimationUtilsBuilder = flingAnimationUtilsBuilder;
        mMediaHierarchyManager = mediaHierarchyManager;
        mNotificationsQSContainerController = notificationsQSContainerController;
        mNotificationListContainer = notificationListContainer;
        mNavigationBarController = navigationBarController;
        mNotificationsQSContainerController.init();
        mNotificationStackScrollLayoutController = notificationStackScrollLayoutController;
        mKeyguardStatusBarViewComponentFactory = keyguardStatusBarViewComponentFactory;
        mDepthController = notificationShadeDepthController;
        mFragmentService = fragmentService;
        mStatusBarService = statusBarService;
        mSplitShadeStateController = splitShadeStateController;
        mSplitShadeEnabled =
                mSplitShadeStateController.shouldUseSplitNotificationShade(mResources);
        mView.setWillNotDraw(!DEBUG_DRAWABLE);
        mShadeHeaderController = shadeHeaderController;
        mAnimateBack = predictiveBackAnimateShade();
        mFalsingCollector = falsingCollector;
        mWakeUpCoordinator = coordinator;
        mMainDispatcher = mainDispatcher;
        mAccessibilityManager = accessibilityManager;
        mView.setAccessibilityPaneTitle(determineAccessibilityPaneTitle());
        setAlpha(255, false /* animate */);
        mCommandQueue = commandQueue;
        mDisplayId = displayId;
        mPulseExpansionHandler = pulseExpansionHandler;
        mDozeParameters = dozeParameters;
        mScrimController = scrimController;
        mMediaDataManager = mediaDataManager;
        mTapAgainViewController = tapAgainViewController;
        mSysUiState = sysUiState;
        mSysUIStateDisplaysInteractor = sysUIStateDisplaysInteractor;
        mShadeDisplaysRepository = shadeDisplaysRepository;
        mKeyguardBypassController = bypassController;
        mUpdateMonitor = keyguardUpdateMonitor;
        mLockscreenShadeTransitionController = lockscreenShadeTransitionController;
        dynamicPrivacyController.addListener(this::onDynamicPrivacyChanged);
        quickSettingsController.setExpansionHeightListener(this::onQsSetExpansionHeightCalled);
        quickSettingsController.setApplyClippingImmediatelyListener(
                this::onQsClippingImmediatelyApplied);
        quickSettingsController.setFlingQsWithoutClickListener(this::onFlingQsWithoutClick);
        quickSettingsController.setExpansionHeightSetToMaxListener(this::onExpansionHeightSetToMax);
        shadeExpansionStateManager.addStateListener(this::onPanelStateChanged);
        mConversationNotificationManager = conversationNotificationManager;
        mScreenOffAnimationController = screenOffAnimationController;
        mUnlockedScreenOffAnimationController = unlockedScreenOffAnimationController;
        mLastDownEvents = new NPVCDownEventState.Buffer(MAX_DOWN_EVENT_BUFFER_SIZE);
        mDeviceEntryFaceAuthInteractor = deviceEntryFaceAuthInteractor;

        int currentMode = navigationModeController.addListener(
                mode -> mIsGestureNavigation = QuickStepContract.isGesturalMode(mode));
        mIsGestureNavigation = QuickStepContract.isGesturalMode(currentMode);

        mView.setBackgroundColor(Color.TRANSPARENT);
        ShadeAttachStateChangeListener
                onAttachStateChangeListener = new ShadeAttachStateChangeListener();
        mView.addOnAttachStateChangeListener(onAttachStateChangeListener);
        if (mView.isAttachedToWindow()) {
            onAttachStateChangeListener.onViewAttachedToWindow(mView);
        }

        mView.setOnApplyWindowInsetsListener((v, insets) -> onApplyShadeWindowInsets(insets));

        if (DEBUG_DRAWABLE) {
            mView.getOverlay().add(new DebugDrawable(this, mView,
                    mNotificationStackScrollLayoutController, mQsController));
        }

        mKeyguardUnfoldTransition = unfoldComponent.map(
                SysUIUnfoldComponent::getKeyguardUnfoldTransition);

        mKeyguardClockInteractor = keyguardClockInteractor;
        mWallpaperFocalAreaViewModel = wallpaperFocalAreaViewModel;
        KeyguardTouchViewBinder.bind(
                mView.requireViewById(R.id.keyguard_long_press),
                keyguardTouchHandlingViewModel,
                (x, y) -> {
                    onEmptySpaceClick(x, y);
                    return Unit.INSTANCE;
                },
                mFalsingManager);
        mActivityStarter = activityStarter;
        mBrightnessMirrorShowingRepository = brightnessMirrorShowingRepository;
        mIsBrightnessMirrorShowing.setValue(
                mBrightnessMirrorShowingRepository.isShowing().getValue()
        );
        mDoubleTapToSleepGesture = new GestureDetector(mView.getContext(),
                new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                mView.getHandler().postDelayed(() -> {
                    YaapUtils.switchScreenOff(mView.getContext());
                }, 100);
                return true;
            }
        });
        onFinishInflate();
        keyguardUnlockAnimationController.addKeyguardUnlockAnimationListener(
                new KeyguardUnlockAnimationController.KeyguardUnlockAnimationListener() {
                    @Override
                    public void onUnlockAnimationFinished() {
                        unlockAnimationFinished();
                    }

                    @Override
                    public void onUnlockAnimationStarted(
                            boolean playingCannedAnimation,
                            boolean isWakeAndUnlockNotFromDream,
                            long startDelay,
                            long unlockAnimationDuration) {
                        unlockAnimationStarted(playingCannedAnimation, isWakeAndUnlockNotFromDream,
                                startDelay);
                    }
                });
        mAlternateBouncerInteractor = alternateBouncerInteractor;
        dumpManager.registerDumpable(this);
        mLocalPowerManager = LocalServices.getService(PowerManagerInternal.class);
    }

    private void unlockAnimationFinished() {
        // Make sure the clock is in the correct position after the unlock animation
        // so that it's not in the wrong place when we show the keyguard again.
        positionClockAndNotifications(true /* forceClockUpdate */);
        mScrimController.onUnlockAnimationFinished();
    }

    private void unlockAnimationStarted(
            boolean playingCannedAnimation,
            boolean isWakeAndUnlockNotFromDream,
            long unlockAnimationStartDelay) {
        // Disable blurs while we're unlocking so that panel expansion does not
        // cause blurring. This will eventually be re-enabled by the panel view on
        // ACTION_UP, since the user's finger might still be down after a swipe to
        // unlock gesture, and we don't want that to cause blurring either.
        mDepthController.setBlursDisabledForUnlock(isTracking());

        if (playingCannedAnimation && !isWakeAndUnlockNotFromDream) {
            // Hide the panel so it's not in the way or the surface behind the
            // keyguard, which will be appearing. If we're wake and unlocking, the
            // lock screen is hidden instantly so should not be flung away.
            if (isTracking() || mIsFlinging) {
                // Instant collapse the notification panel since the notification
                // panel is already in the middle animating
                onTrackingStopped(false);
                instantCollapse();
            } else {
                mView.animate().cancel();
                mView.postDelayed(() -> {
                    instantCollapse();
                }, unlockAnimationStartDelay);
            }
        }
    }

    @VisibleForTesting
    void onFinishInflate() {
        loadDimens();
        mKeyguardStatusBarViewController =
                mKeyguardStatusBarViewComponentFactory.build(
                                mView.findViewById(R.id.keyguard_header),
                                mShadeViewStateProvider)
                        .getKeyguardStatusBarViewController();
        mKeyguardStatusBarViewController.init();
        mNotificationContainerParent = mView.findViewById(R.id.notification_container_parent);
        mNotificationStackScrollLayoutController.setOnHeightChangedListener(
                new NsslHeightChangedListener());
        mNotificationStackScrollLayoutController.setOnEmptySpaceClickListener(
                mOnEmptySpaceClickListener);
        mQsController.init();
        mShadeHeadsUpTracker.addTrackingHeadsUpListener(
                mNotificationStackScrollLayoutController::setTrackingHeadsUp);
        mWakeUpCoordinator.setStackScroller(mNotificationStackScrollLayoutController);
        mWakeUpCoordinator.addListener(new NotificationWakeUpCoordinator.WakeUpListener() {
            @Override
            public void onFullyHiddenChanged(boolean isFullyHidden) {
                mKeyguardStatusBarViewController.updateForHeadsUp();
            }
        });

        mView.setRtlChangeListener(layoutDirection -> {
            if (layoutDirection != mOldLayoutDirection) {
                mOldLayoutDirection = layoutDirection;
            }
        });

        mView.setAccessibilityDelegate(mAccessibilityDelegate);
        if (mSplitShadeEnabled) {
            updateResources();
        }

        mTapAgainViewController.init();
        mShadeHeaderController.init();
        mShadeHeaderController.setShadeCollapseAction(
                () -> collapse(/* delayed= */ false , /* speedUpFactor= */ 1.0f));

        // Dreaming->Lockscreen
        collectFlow(mView, mDreamingToLockscreenTransitionViewModel.getLockscreenAlpha(),
                setDreamLockscreenTransitionAlpha(),
                mMainDispatcher);

        collectFlow(mView, mKeyguardTransitionInteractor.transition(
                Edge.Companion.create(AOD, LOCKSCREEN)),
                (TransitionStep step) -> {
                if (step.getTransitionState() == TransitionState.FINISHED) {
                    updateExpandedHeightToMaxHeight();
                }
            }, mMainDispatcher);

        if (com.android.systemui.Flags.bouncerUiRevamp()) {
            collectFlow(mView, mKeyguardInteractor.primaryBouncerShowing,
                    this::handleBouncerShowingChanged);
        }

        // Ensures that flags are updated when an activity launches
        collectFlow(mView,
                mShadeAnimationInteractor.isLaunchingActivity(),
                isLaunchingActivity -> {
                    if (isLaunchingActivity) {
                        updateSystemUiStateFlags();
                    }
                },
                mMainDispatcher);
        if (QSComposeFragment.isEnabled()) {
            collectFlow(mView,
                    mBrightnessMirrorShowingRepository.isShowing(),
                    this::onBrightnessMirrorShowingChanged
            );
        }
    }

    private void onBrightnessMirrorShowingChanged(boolean isShowing) {
        if (!mIsBrightnessMirrorShowing.getValue()) {
            // Immediately set the value of the mirror if we are not showing the mirror, and then
            // start fading the shade.
            mIsBrightnessMirrorShowing.setValue(isShowing);
        }
        setAlpha(isShowing ? 0 : 255, true);
    }

    @androidx.annotation.NonNull
    @Override
    public StateFlow<Boolean> isShowing() {
        return mIsBrightnessMirrorShowing;
    }

    @Override
    public void setMirrorShowing(boolean showing) {
        mBrightnessMirrorShowingRepository.setMirrorShowing(showing);
    }

    @VisibleForTesting
    void loadDimens() {
        final ViewConfiguration configuration = ViewConfiguration.get(this.mView.getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
        mSlopMultiplier = configuration.getScaledAmbiguousGestureMultiplier();
        mHintDistance = mResources.getDimension(R.dimen.hint_move_distance);
        mPanelFlingOvershootAmount = mResources.getDimension(R.dimen.panel_overshoot_amount);
        mFlingAnimationUtils = mFlingAnimationUtilsBuilder.get()
                .setMaxLengthSeconds(0.4f).build();
        mStatusBarMinHeight = SystemBarUtils.getStatusBarHeight(mView.getContext());
        mStatusBarHeaderHeightKeyguard = Utils.getStatusBarHeaderHeightKeyguard(mView.getContext());
        mClockPositionAlgorithm.loadDimens(mView.getContext(), mResources);
        int statusbarHeight = SystemBarUtils.getStatusBarHeight(mView.getContext());
        mHeadsUpInset = statusbarHeight + mResources.getDimensionPixelSize(
                R.dimen.heads_up_status_bar_padding);
        mMaxOverscrollAmountForPulse = mResources.getDimensionPixelSize(
                R.dimen.pulse_expansion_max_top_overshoot);
        mStatusBarHeaderHeight = mResources.getDimensionPixelSize(R.dimen.status_bar_height);
        mSplitShadeScrimTransitionDistance = mResources.getDimensionPixelSize(
                R.dimen.split_shade_scrim_transition_distance);
        // TODO (b/265193930): remove this and make QsController listen to NotificationPanelViews
        mQsController.loadDimens();
    }

    private void handleBouncerShowingChanged(Boolean isBouncerShowing) {
        if (!com.android.systemui.Flags.bouncerUiRevamp()) return;
        if (isBouncerShowing && isExpanded()) {
            if (mBlurRenderEffect == null) {
                mBlurRenderEffect = RenderEffect.createBlurEffect(
                        mBlurConfig.getMaxBlurRadiusPx(),
                        mBlurConfig.getMaxBlurRadiusPx(),
                        Shader.TileMode.CLAMP);
            }
            debugLog("Applying blur RenderEffect to shade.");
            mView.setRenderEffect(mBlurRenderEffect);
        } else {
            debugLog("Resetting blur RenderEffect on shade.");
            mView.setRenderEffect(null);
        }
    }

    @Override
    public void updateResources() {
        try {
            Trace.beginSection("NSSLC#updateResources");
            final boolean newSplitShadeEnabled =
                    mSplitShadeStateController.shouldUseSplitNotificationShade(mResources);
            final boolean splitShadeChanged = mSplitShadeEnabled != newSplitShadeEnabled;
            mSplitShadeEnabled = newSplitShadeEnabled;
            mQsController.updateResources();
            mNotificationsQSContainerController.updateResources();
            updateKeyguardStatusViewAlignment();
            mKeyguardMediaController.refreshMediaPosition(
                    "NotificationPanelViewController.updateResources");

            if (splitShadeChanged) {
                if (isPanelVisibleBecauseOfHeadsUp()) {
                    // workaround for b/324642496, because HUNs set state to OPENING
                    onPanelStateChanged(STATE_CLOSED);
                }
                onSplitShadeEnabledChanged();
            }

            mSplitShadeFullTransitionDistance =
                    mResources.getDimensionPixelSize(R.dimen.split_shade_full_transition_distance);
        } finally {
            Trace.endSection();
        }
    }

    private void onSplitShadeEnabledChanged() {
        mShadeLog.logSplitShadeChanged(mSplitShadeEnabled);
        // Reset any left over overscroll state. It is a rare corner case but can happen.
        mQsController.setOverScrollAmount(0);
        mScrimController.setNotificationsOverScrollAmount(0);

        // when we switch between split shade and regular shade we want to enforce setting qs to
        // the default state: expanded for split shade and collapsed otherwise
        if (!isKeyguardShowing() && isPanelExpanded()) {
            mQsController.setExpanded(mSplitShadeEnabled);
        }
        if (isKeyguardShowing() && mQsController.getExpanded() && mSplitShadeEnabled) {
            // In single column keyguard - when you swipe from the top - QS is fully expanded and
            // StatusBarState is KEYGUARD. That state doesn't make sense for split shade,
            // where notifications are always visible and we effectively go to fully expanded
            // shade, that is SHADE_LOCKED.
            // Also we might just be switching from regular expanded shade, so we don't want
            // to force state transition if it's already correct.
            mStatusBarStateController.setState(StatusBarState.SHADE_LOCKED, /* force= */false);
        }
        updateClockAppearance();
        mQsController.updateQsState();
    }

    @VisibleForTesting
    void reInflateViews() {
        debugLog("reInflateViews");
        updateResources();
        mStatusBarStateListener.onDozeAmountChanged(mStatusBarStateController.getDozeAmount(),
                mStatusBarStateController.getInterpolatedDozeAmount());
    }

    /** Sets a listener to be notified when the shade starts opening or finishes closing. */
    public void setOpenCloseListener(OpenCloseListener openCloseListener) {
        SceneContainerFlag.assertInLegacyMode();
        mOpenCloseListener = openCloseListener;
    }

    /** Sets a listener to be notified when touch tracking begins. */
    public void setTrackingStartedListener(TrackingStartedListener trackingStartedListener) {
        mTrackingStartedListener = trackingStartedListener;
    }

    private void updateGestureExclusionRect() {
        Rect exclusionRect = calculateGestureExclusionRect();
        mView.setSystemGestureExclusionRects(exclusionRect.isEmpty() ? Collections.emptyList()
                : Collections.singletonList(exclusionRect));
    }

    private Rect calculateGestureExclusionRect() {
        Rect exclusionRect = null;
        Region touchableRegion = mShadeTouchableRegionManager.calculateTouchableRegion();
        if (isFullyCollapsed() && touchableRegion != null) {
            // Note: The manager also calculates the non-pinned touchable region
            exclusionRect = touchableRegion.getBounds();
        }
        return exclusionRect != null ? exclusionRect : EMPTY_RECT;
    }

    private void setIsFullWidth(boolean isFullWidth) {
        mIsFullWidth = isFullWidth;
        mScrimController.setClipsQsScrim(isFullWidth);
        mNotificationStackScrollLayoutController.setIsFullWidth(isFullWidth);
        mQsController.setNotificationPanelFullWidth(isFullWidth);
    }

    /**
     * Positions the clock and notifications dynamically depending on how many notifications are
     * showing.
     */
    void positionClockAndNotifications() {
        positionClockAndNotifications(false /* forceUpdate */);
    }

    /**
     * Positions the clock and notifications dynamically depending on how many notifications are
     * showing.
     *
     * @param forceClockUpdate Should the clock be updated even when not on keyguard
     */
    private void positionClockAndNotifications(boolean forceClockUpdate) {
        int stackScrollerPadding;
        boolean onKeyguard = isKeyguardShowing();

        if (onKeyguard || forceClockUpdate) {
            updateClockAppearance();
        }
        if (!onKeyguard) {
            if (mSplitShadeEnabled) {
                // Quick settings are not on the top of the notifications
                // when in split shade mode (they are on the left side),
                // so we should not add a padding for them
                stackScrollerPadding = 0;
            } else {
                stackScrollerPadding = mQsController.getHeaderHeight();
            }
        } else {
            stackScrollerPadding = mClockPositionResult.stackScrollerPaddingExpanded;
        }

        mNotificationStackScrollLayoutController.setIntrinsicPadding(stackScrollerPadding);

        mStackScrollerMeasuringPass++;
        requestScrollerTopPaddingUpdate();
        mStackScrollerMeasuringPass = 0;
        mAnimateNextPositionUpdate = false;
    }

    private void updateClockAppearance() {
        mKeyguardClockInteractor.setClockSize(computeDesiredClockSize());
        updateKeyguardStatusViewAlignment();

        float darkAmount =
                mScreenOffAnimationController.shouldExpandNotifications()
                        ? 1.0f : mInterpolatedDarkAmount;

        mClockPositionAlgorithm.setup(
                darkAmount, mOverStretchAmount,
                mKeyguardBypassController.getBypassEnabled(),
                mQsController.getHeaderHeight(),
                mSplitShadeEnabled);
        mClockPositionAlgorithm.run(mClockPositionResult);
    }

    KeyguardClockPositionAlgorithm.Result getClockPositionResult() {
        return mClockPositionResult;
    }

    private ClockSize computeDesiredClockSize() {
        if (mSplitShadeEnabled) {
            return computeDesiredClockSizeForSplitShade();
        }
        return computeDesiredClockSizeForSingleShade();
    }

    private ClockSize computeDesiredClockSizeForSingleShade() {
        if (hasVisibleNotifications(true)) {
            return ClockSize.SMALL;
        }
        return ClockSize.LARGE;
    }

    private ClockSize computeDesiredClockSizeForSplitShade() {
        // Media is not visible to the user on AOD.
        boolean isMediaVisibleToUser =
                mMediaDataManager.hasActiveMediaOrRecommendation() && !isOnAod()
                && mMediaHierarchyManager.getShouldShowOnLockScreen();
        if (isMediaVisibleToUser) {
            // When media is visible, it overlaps with the large clock. Use small clock instead.
            return ClockSize.SMALL;
        }
        return ClockSize.LARGE;
    }

    private void updateKeyguardStatusViewAlignment() {
        boolean shouldBeCentered = shouldKeyguardStatusViewBeCentered();
        mKeyguardUnfoldTransition.ifPresent(t -> t.setStatusViewCentered(shouldBeCentered));
    }

    private boolean shouldKeyguardStatusViewBeCentered() {
        if (mSplitShadeEnabled) {
            return shouldKeyguardStatusViewBeCenteredInSplitShade();
        }
        return true;
    }

    private boolean shouldKeyguardStatusViewBeCenteredInSplitShade() {
        if (!hasVisibleNotifications()) {
            // No notifications visible. It is safe to have the clock centered as there will be no
            // overlap.
            return true;
        }
        if (mNotificationListContainer.hasPulsingNotifications()) {
            // Pulsing notification appears on the right. Move clock left to avoid overlap.
            return false;
        }
        // "Visible" notifications are actually not visible on AOD (unless pulsing), so it is safe
        // to center the clock without overlap.
        return isOnAod();
    }

    private boolean isOnAod() {
        return mDozing && mDozeParameters.getAlwaysOn();
    }

    private boolean hasVisibleNotifications() {
        return hasVisibleNotifications(false);
    }

    private boolean hasVisibleNotifications(boolean onKeyguard) {
        final boolean mediaOnKeyguard = !isOnAod()
                && mMediaHierarchyManager.getShouldShowOnLockScreen();
        final boolean isMediaVisibleToUser =
                mMediaDataManager.hasActiveMediaOrRecommendation()
                && (mediaOnKeyguard || !onKeyguard);
        return mActiveNotificationsInteractor.getAreAnyNotificationsPresentValue()
                || mMediaDataManager.hasActiveMediaOrRecommendation() || isMediaVisibleToUser;
    }

    private void boostFrames() {
        if (mView != null && mView.getViewRootImpl() != null) {
            mView.getViewRootImpl().notifyRendererOfExpensiveFrame();
        }
    }

    private void boostFramesDuringRelayout() {
        boostFrames();
        this.mView.requestLayout();
        boostFrames();
    }

    @Override
    public void transitionToExpandedShade(long delay) {
        mNotificationStackScrollLayoutController.goToFullShade(delay);
        boostFramesDuringRelayout();
        mAnimateNextPositionUpdate = true;
    }

    @Override
    public void animateCollapseQs(boolean fullyCollapse) {
        if (mSplitShadeEnabled) {
            collapse(true, false, 1.0f);
        } else {
            mQsController.animateCloseQs(fullyCollapse);
        }
    }

    @Override
    public void resetViews(boolean animate) {
        mGutsManager.closeAndSaveGuts(true /* leavebehind */, true /* force */,
                true /* controls */, -1 /* x */, -1 /* y */, true /* resetMenu */);
        if (animate && !isFullyCollapsed()) {
            animateCollapseQs(true);
        } else {
            closeQsIfPossible();
        }
        mNotificationStackScrollLayoutController.setOverScrollAmount(0f, true /* onTop */, animate,
                !animate /* cancelAnimators */);
        mNotificationStackScrollLayoutController.resetScrollPosition();
    }

    public void collapse(boolean animate, boolean delayed, float speedUpFactor) {
        boolean waiting = false;
        if (animate && !isFullyCollapsed()) {
            collapse(delayed, speedUpFactor);
            waiting = true;
        } else {
            resetViews(false /* animate */);
            setExpandedFraction(0); // just in case
        }
        if (!waiting) {
            // it's possible that nothing animated, so we replicate the termination
            // conditions of panelExpansionChanged here
            // TODO(b/200063118): This can likely go away in a future refactor CL.
            getShadeExpansionStateManager().updateState(STATE_CLOSED);
        }
    }

    public void collapse(boolean delayed, float speedUpFactor) {
        if (!canBeCollapsed()) {
            return;
        }

        if (mQsController.getExpanded()) {
            mQsController.setExpandImmediate(true);
            setShowShelfOnly(true);
        }
        debugLog("collapse: %s", this);
        if (canBeCollapsed()) {
            cancelHeightAnimator();
            notifyExpandingStarted();

            // Set after notifyExpandingStarted, as notifyExpandingStarted resets the closing state.
            setClosing(true);
            mUpdateFlingOnLayout = false;
            if (delayed) {
                mNextCollapseSpeedUpFactor = speedUpFactor;
                this.mView.postDelayed(mFlingCollapseRunnable, 120);
            } else {
                fling(0, false /* expand */, speedUpFactor, false /* expandBecauseOfFalsing */);
            }
        }
    }

    private void setShowShelfOnly(boolean shelfOnly) {
        mNotificationStackScrollLayoutController.setShouldShowShelfOnly(
                shelfOnly && !mSplitShadeEnabled);
    }

    @VisibleForTesting
    void cancelHeightAnimator() {
        if (mHeightAnimator != null) {
            if (mHeightAnimator.isRunning()) {
                mPanelUpdateWhenAnimatorEnds = false;
            }
            mHeightAnimator.cancel();
        }
        endClosing();
    }

    @Override
    public void cancelAnimation() {
        mView.animate().cancel();
    }

    public void expandToQs() {
        if (mQsController.isExpansionEnabled()) {
            mQsController.setExpandImmediate(true);
            setShowShelfOnly(true);
        }
        if (mSplitShadeEnabled && isKeyguardShowing()) {
            // It's a special case as this method is likely to not be initiated by finger movement
            // but rather called from adb shell or accessibility service.
            // We're using LockscreenShadeTransitionController because on lockscreen that's the
            // source of truth for all shade motion. Not using it would make part of state to be
            // outdated and will cause bugs. Ideally we'd use this controller also for non-split
            // case but currently motion in portrait looks worse than when using flingSettings.
            // TODO: make below function transitioning smoothly also in portrait with null target
            mLockscreenShadeTransitionController.goToLockedShade(
                    /* expandedView= */null, /* needsQSAnimation= */true);
        } else if (isFullyCollapsed()) {
            expand(true /* animate */);
        } else {
            mQsController.traceQsJank(true /* startTracing */, false /* wasCancelled */);
            mQsController.flingQs(0, FLING_EXPAND);
        }
    }

    @Override
    public void expandToNotifications() {
        if (mSplitShadeEnabled && (isShadeFullyExpanded() || isExpandingOrCollapsing())) {
            return;
        }
        if (mQsController.getExpanded()) {
            mQsController.flingQs(0, FLING_COLLAPSE);
        } else {
            expand(true /* animate */);
        }
    }

    private void fling(float vel) {
        if (mGestureRecorder != null) {
            mGestureRecorder.tag("fling " + ((vel > 0) ? "open" : "closed"),
                    "notifications,v=" + vel);
        }
        fling(vel, true, 1.0f /* collapseSpeedUpFactor */, false);
    }

    @VisibleForTesting
    void flingToHeight(float vel, boolean expand, float target,
            float collapseSpeedUpFactor, boolean expandBecauseOfFalsing) {
        mQsController.setLastShadeFlingWasExpanding(expand);
        mHeadsUpTouchHelper.notifyFling(!expand);
        mKeyguardStateController.notifyPanelFlingStart(!expand /* flingingToDismiss */);
        setClosingWithAlphaFadeout(!expand && !isKeyguardShowing() && getFadeoutAlpha() == 1.0f);
        mNotificationStackScrollLayoutController.setPanelFlinging(true);
        mShadeRepository.setCurrentFling(new FlingInfo(expand, vel));
        if (target == mExpandedHeight && mOverExpansion == 0.0f) {
            // We're at the target and didn't fling and there's no overshoot
            onFlingEnd(false /* cancelled */);
            return;
        }
        mIsFlinging = true;
        // we want to perform an overshoot animation when flinging open
        final boolean addOverscroll =
                expand
                        && mStatusBarStateController.getState() != KEYGUARD
                        && mOverExpansion == 0.0f
                        && vel >= 0;
        final boolean shouldSpringBack = addOverscroll || (mOverExpansion != 0.0f && expand);
        float overshootAmount = 0.0f;
        if (addOverscroll) {
            // Let's overshoot depending on the amount of velocity
            overshootAmount = MathUtils.lerp(
                    0.2f,
                    1.0f,
                    MathUtils.saturate(vel
                            / (this.mFlingAnimationUtils.getHighVelocityPxPerSecond()
                            * FACTOR_OF_HIGH_VELOCITY_FOR_MAX_OVERSHOOT)));
            overshootAmount += mOverExpansion / mPanelFlingOvershootAmount;
        }
        ValueAnimator animator = createHeightAnimator(target, overshootAmount);
        if (expand) {
            maybeVibrateOnOpening(true /* openingWithTouch */);
            if (expandBecauseOfFalsing && vel < 0) {
                vel = 0;
            }
            this.mFlingAnimationUtils.apply(animator, mExpandedHeight,
                    target + overshootAmount * mPanelFlingOvershootAmount, vel,
                    this.mView.getHeight());
            if (vel == 0) {
                animator.setDuration(SHADE_OPEN_SPRING_OUT_DURATION);
            }
        } else {
            mHasVibratedOnOpen = false;
            if (shouldUseDismissingAnimation()) {
                if (vel == 0) {
                    animator.setInterpolator(Interpolators.PANEL_CLOSE_ACCELERATED);
                    long duration = (long) (200 + mExpandedHeight / this.mView.getHeight() * 100);
                    animator.setDuration(duration);
                } else {
                    mFlingAnimationUtilsDismissing.apply(animator, mExpandedHeight, target, vel,
                            this.mView.getHeight());
                }
            } else {
                mFlingAnimationUtilsClosing.apply(
                        animator, mExpandedHeight, target, vel, this.mView.getHeight());
            }

            // Make it shorter if we run a canned animation
            if (vel == 0) {
                animator.setDuration((long) (animator.getDuration() / collapseSpeedUpFactor));
            }
            if (mFixedDuration != NO_FIXED_DURATION) {
                animator.setDuration(mFixedDuration);
            }

            // Reset Predictive Back animation's transform after Shade is completely hidden.
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    resetBackTransformation();
                }
            });
        }
        if (mLocalPowerManager != null) {
            mLocalPowerManager.setPowerBoost(Boost.DISPLAY_UPDATE_IMMINENT, 200);
        }
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationStart(Animator animation) {
                if (!mStatusBarStateController.isDozing()) {
                    mQsController.beginJankMonitoring(isFullyCollapsed());
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (shouldSpringBack && !mCancelled) {
                    // After the shade is flung open to an overscrolled state, spring back
                    // the shade by reducing section padding to 0.
                    springBack();
                } else {
                    onFlingEnd(mCancelled);
                }
            }
        });
        if (!mScrimController.isScreenOn()) {
            animator.setDuration(1);
        }
        setAnimator(animator);
        animator.start();
    }

    @VisibleForTesting
    void onFlingEnd(boolean cancelled) {
        mIsFlinging = false;
        mExpectingSynthesizedDown = false;
        // No overshoot when the animation ends
        setOverExpansionInternal(0);
        setAnimator(null);
        mKeyguardStateController.notifyPanelFlingEnd();
        if (!cancelled) {
            mQsController.endJankMonitoring();
            notifyExpandingFinished();
        } else {
            mQsController.cancelJankMonitoring();
        }
        updateExpansionAndVisibility();
        mNotificationStackScrollLayoutController.setPanelFlinging(false);
        mShadeLog.d("onFlingEnd called"); // TODO(b/277909752): remove log when bug is fixed
        // expandImmediate should be always reset at the end of animation
        mQsController.setExpandImmediate(false);
        mShadeRepository.setCurrentFling(null);
    }

    private boolean isInContentBounds(float x, float y) {
        float stackScrollerX = mNotificationStackScrollLayoutController.getX();
        return !mNotificationStackScrollLayoutController
                .isBelowLastNotification(x - stackScrollerX, y)
                && stackScrollerX < x
                && x < stackScrollerX + mNotificationStackScrollLayoutController.getWidth();
    }

    private void initDownStates(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mDozingOnDown = mDozing;
            mDownX = event.getX();
            mDownY = event.getY();
            mCollapsedOnDown = isFullyCollapsed();
            mQsController.setCollapsedOnDown(mCollapsedOnDown);
            mIsPanelCollapseOnQQS = mQsController.canPanelCollapseOnQQS(mDownX, mDownY);
            mListenForHeadsUp = mCollapsedOnDown && mHeadsUpManager.hasPinnedHeadsUp();
            mAllowExpandForSmallExpansion = mExpectingSynthesizedDown;
            mTouchSlopExceededBeforeDown = mExpectingSynthesizedDown;
            // When false, down but not synthesized motion event.
            mLastEventSynthesizedDown = mExpectingSynthesizedDown;
            mLastDownEvents.insert(
                    event.getEventTime(),
                    mDownX,
                    mDownY,
                    mQsController.updateAndGetTouchAboveFalsingThreshold(),
                    mDozingOnDown,
                    mCollapsedOnDown,
                    mIsPanelCollapseOnQQS,
                    mListenForHeadsUp,
                    mAllowExpandForSmallExpansion,
                    mTouchSlopExceededBeforeDown,
                    mLastEventSynthesizedDown
            );
        } else {
            // not down event at all.
            mLastEventSynthesizedDown = false;
        }
    }

    boolean flingExpandsQs(float vel) {
        if (Math.abs(vel) < mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
            return mQsController.computeExpansionFraction() > 0.5f;
        } else {
            return vel > 0;
        }
    }

    private boolean shouldExpandWhenNotFlinging() {
        if (getExpandedFraction() > 0.5f) {
            return true;
        }
        if (mAllowExpandForSmallExpansion) {
            // When we get a touch that came over from launcher, the velocity isn't always correct
            // Let's err on expanding if the gesture has been reasonably slow
            long timeSinceDown = mSystemClock.uptimeMillis() - mDownTime;
            return timeSinceDown <= MAX_TIME_TO_OPEN_WHEN_FLINGING_FROM_LAUNCHER;
        }
        return false;
    }

    private float getOpeningHeight() {
        return mNotificationStackScrollLayoutController.getOpeningHeight();
    }

    float getDisplayDensity() {
        if (ShadeWindowGoesAround.isEnabled()) {
            return mView.getContext().getResources().getConfiguration().densityDpi;
        } else {
            return mCentralSurfaces.getDisplayDensity();
        }
    }

    /** Return whether a touch is near the gesture handle at the bottom of screen */
    boolean isInGestureNavHomeHandleArea(float y) {
        return mIsGestureNavigation && y > mView.getHeight() - mNavigationBarBottomHeight;
    }

    @Override
    public void startInputFocusTransfer() {
        if (!mCommandQueue.panelsEnabled()) {
            return;
        }
        if (!isFullyCollapsed()) {
            return;
        }
        mExpectingSynthesizedDown = true;
        onTrackingStarted();
        updatePanelExpanded();
    }

    @Override
    public void cancelInputFocusTransfer() {
        if (!mCommandQueue.panelsEnabled()) {
            return;
        }
        if (mExpectingSynthesizedDown) {
            mExpectingSynthesizedDown = false;
            collapse(false /* delayed */, 1.0f /* speedUpFactor */);
            onTrackingStopped(false);
        }
    }

    /**
     * There are two scenarios behind this function call. First, input focus transfer has
     * successfully happened and this view already received synthetic DOWN event.
     * (mExpectingSynthesizedDown == false). Do nothing.
     * <p>
     * Second, before input focus transfer finished, user may have lifted finger in previous window
     * and this window never received synthetic DOWN event. (mExpectingSynthesizedDown == true). In
     * this case, we use the velocity to trigger fling event.
     */
    @Override
    public void finishInputFocusTransfer(final float velocity) {
        if (!mCommandQueue.panelsEnabled()) {
            return;
        }
        if (mExpectingSynthesizedDown) {
            // Window never will receive touch events that typically trigger haptic on open.
            maybeVibrateOnOpening(false /* openingWithTouch */);
            fling(velocity > 1f ? 1000f * velocity : 0  /* expand */);
            onTrackingStopped(false);
        }
    }

    private boolean flingExpands(float vel, float vectorVel, float x, float y) {
        boolean expands = true;
        if (!this.mFalsingManager.isUnlockingDisabled()) {
            @Classifier.InteractionType int interactionType = y - mInitialExpandY > 0
                    ? QUICK_SETTINGS : (
                    mKeyguardStateController.canDismissLockScreen() ? UNLOCK : BOUNCER_UNLOCK);
            if (!isFalseTouch(x, y, interactionType)) {
                mShadeLog.logFlingExpands(vel, vectorVel, interactionType,
                        this.mFlingAnimationUtils.getMinVelocityPxPerSecond(),
                        mExpandedFraction > 0.5f, mAllowExpandForSmallExpansion);
                if (Math.abs(vectorVel) < this.mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
                    expands = shouldExpandWhenNotFlinging();
                } else {
                    expands = vel > 0;
                }
            }
        }

        // If we are already running a QS expansion, make sure that we keep the panel open.
        if (mQsController.isExpansionAnimating()) {
            expands = true;
        }
        return expands;
    }

    private boolean shouldGestureWaitForTouchSlop() {
        if (mExpectingSynthesizedDown) {
            mExpectingSynthesizedDown = false;
            return false;
        }
        return isFullyCollapsed() || mBarState != StatusBarState.SHADE;
    }

    int getFalsingThreshold() {
        float factor = ShadeViewController.getFalsingThresholdFactor(getWakefulness());
        return (int) (mQsController.getFalsingThreshold() * factor);
    }

    private WakefulnessModel getWakefulness() {
        return mPowerInteractor.getDetailedWakefulness().getValue();
    }

    /**
     * When the back gesture triggers a fully-expanded shade --> QQS shade collapse transition,
     * the expansionFraction goes down from 1.0 --> 0.0 (collapsing), so the current "squish" amount
     * (mCurrentBackProgress) must be un-applied from various UI elements in tandem, such that,
     * as the shade ends up in its half-expanded state (with QQS above), it is back at 100% scale.
     * Without this, the shade would collapse, and stay squished.
     */
    void adjustBackAnimationScale(float expansionFraction) {
        if (expansionFraction > 0.0f) { // collapsing
            float animatedFraction = expansionFraction * mCurrentBackProgress;
            applyBackScaling(animatedFraction);
        } else {
            // collapsed! reset, so that if we re-expand shade, it won't start off "squished"
            mCurrentBackProgress = 0;
        }
    }

    //TODO(b/270981268): allow cancelling back animation mid-flight
    @Override
    public void onBackPressed() {
        closeQsIfPossible();
    }

    @Override
    public void onBackProgressed(float progressFraction) {
        // TODO: non-linearly transform progress fraction into squish amount (ease-in, linear out)
        mCurrentBackProgress = progressFraction;
        applyBackScaling(progressFraction);
        mQsController.setClippingBounds();
    }

    /** Resets back progress. */
    private void resetBackTransformation() {
        mCurrentBackProgress = 0.0f;
        applyBackScaling(0.0f);
    }

    /**
     * Scales multiple elements in tandem to achieve the illusion of the QS+Shade shrinking
     * as a single visual element (used by the Predictive Back Gesture preview animation).
     * fraction = 0 implies "no scaling", and 1 means "scale down to minimum size (90%)".
     */
    private void applyBackScaling(float fraction) {
        if (mNotificationContainerParent == null) {
            return;
        }
        float scale = MathUtils.lerp(1.0f, SHADE_BACK_ANIM_MIN_SCALE, fraction);
        mNotificationContainerParent.applyBackScaling(scale, mSplitShadeEnabled);
        mScrimController.applyBackScaling(scale);
    }

    String determineAccessibilityPaneTitle() {
        if (mQsController != null && mQsController.isCustomizing()) {
            return mResources.getString(R.string.accessibility_desc_quick_settings_edit);
        } else if (mQsController != null && mQsController.getExpansionHeight() != 0.0f
                && mQsController.getFullyExpanded()) {
            // Upon initialisation when we are not layouted yet we don't want to announce that we
            // are fully expanded, hence the != 0.0f check.
            if (mSplitShadeEnabled) {
                // In split shade, QS is expanded but it also shows notifications
                return mResources.getString(R.string.accessibility_desc_qs_notification_shade);
            } else {
                return mResources.getString(R.string.accessibility_desc_quick_settings);
            }
        } else if (mBarState == KEYGUARD) {
            return mResources.getString(R.string.accessibility_desc_lock_screen);
        } else {
            return mResources.getString(R.string.accessibility_desc_notification_shade);
        }
    }

    /** Returns the topPadding of notifications when on keyguard not respecting QS expansion. */
    int getKeyguardNotificationStaticPadding() {
        SceneContainerFlag.assertInLegacyMode();
        if (!isKeyguardShowing()) {
            return 0;
        }

        if (!mKeyguardBypassController.getBypassEnabled()) {
            if (!mSplitShadeEnabled) {
                return (int) mKeyguardInteractor.getNotificationContainerBounds()
                        .getValue().getTop();
            }

            return mClockPositionResult.stackScrollerPadding;
        }
        int collapsedPosition = mHeadsUpInset;
        if (!mNotificationStackScrollLayoutController.isPulseExpanding()) {
            return collapsedPosition;
        } else {
            int expandedPosition =
                    mClockPositionResult.stackScrollerPadding;
            return (int) MathUtils.lerp(collapsedPosition, expandedPosition,
                    mNotificationStackScrollLayoutController.calculateAppearFractionBypass());
        }
    }

    boolean isKeyguardShowing() {
        return mBarState == KEYGUARD;
    }

    void requestScrollerTopPaddingUpdate() {
        if (!SceneContainerFlag.isEnabled()) {
            float padding = mQsController.calculateNotificationsTopPadding(mIsExpandingOrCollapsing,
                    getKeyguardNotificationStaticPadding(), mExpandedFraction);
            mSharedNotificationContainerInteractor.setTopPosition(padding);
        }

        if (isKeyguardShowing()
                && mKeyguardBypassController.getBypassEnabled()) {
            // update the position of the header
            mQsController.updateExpansion();
        }
    }

    @Override
    public void setKeyguardStatusBarAlpha(float alpha) {
        mKeyguardStatusBarViewController.setAlpha(alpha);
    }

    @VisibleForTesting
    boolean canCollapsePanelOnTouch() {
        if (!mQsController.getExpanded() && mBarState == KEYGUARD) {
            return true;
        }

        if (mNotificationStackScrollLayoutController.isScrolledToBottom()) {
            return true;
        }

        return !mSplitShadeEnabled && (mQsController.getExpanded() || mIsPanelCollapseOnQQS);
    }

    int getMaxPanelHeight() {
        int min = mStatusBarMinHeight;
        if (!(mBarState == KEYGUARD)
                && mNotificationStackScrollLayoutController.getNotGoneChildCount() == 0) {
            int minHeight = mQsController.getMinExpansionHeight();
            min = Math.max(min, minHeight);
        }
        int maxHeight;
        if (mQsController.isExpandImmediate() || mQsController.getExpanded()
                || mIsExpandingOrCollapsing && mQsController.getExpandedWhenExpandingStarted()
                || mPulsing || mSplitShadeEnabled) {
            maxHeight = mQsController.calculatePanelHeightExpanded(
                    mClockPositionResult.stackScrollerPadding);
        } else {
            maxHeight = calculatePanelHeightShade();
        }
        maxHeight = Math.max(min, maxHeight);
        if (maxHeight == 0) {
            Log.wtf(TAG, "maxPanelHeight is invalid. mOverExpansion: "
                    + mOverExpansion + ", calculatePanelHeightQsExpanded: "
                    + mQsController.calculatePanelHeightExpanded(
                            mClockPositionResult.stackScrollerPadding)
                    + ", calculatePanelHeightShade: " + calculatePanelHeightShade()
                    + ", mStatusBarMinHeight = " + mStatusBarMinHeight
                    + ", mQsMinExpansionHeight = " + mQsController.getMinExpansionHeight());
        }
        return maxHeight;
    }

    public boolean isExpandingOrCollapsing() {
        float lockscreenExpansionProgress = mQsController.getLockscreenShadeDragProgress();
        return mIsExpandingOrCollapsing
                || (0 < lockscreenExpansionProgress && lockscreenExpansionProgress < 1);
    }

    private void onHeightUpdated(float expandedHeight) {
        if (expandedHeight <= 0) {
            mShadeLog.logExpansionChanged("onHeightUpdated: fully collapsed.",
                    mExpandedFraction, isExpanded(), isTracking(), mExpansionDragDownAmountPx);
        } else if (isFullyExpanded()) {
            mShadeLog.logExpansionChanged("onHeightUpdated: fully expanded.",
                    mExpandedFraction, isExpanded(), isTracking(), mExpansionDragDownAmountPx);
        }
        if (!mQsController.getExpanded() || mQsController.isExpandImmediate()
                || mIsExpandingOrCollapsing && mQsController.getExpandedWhenExpandingStarted()) {
            // Updating the clock position will set the top padding which might
            // trigger a new panel height and re-position the clock.
            // This is a circular dependency and should be avoided, otherwise we'll have
            // a stack overflow.
            if (mStackScrollerMeasuringPass > 2) {
                debugLog("Unstable notification panel height. Aborting.");
            } else {
                positionClockAndNotifications();
            }
        }
        boolean goingBetweenClosedShadeAndExpandedQs =
                mQsController.isGoingBetweenClosedShadeAndExpandedQs();
        // in split shade we react when HUN is visible only if shade height is over HUN start
        // height - which means user is swiping down. Otherwise shade QS will either not show at all
        // with HUN movement or it will blink when touching HUN initially
        boolean qsShouldExpandWithHeadsUp = !mSplitShadeEnabled
                || (!mHeadsUpManager.isTrackingHeadsUp().getValue()
                || expandedHeight > mHeadsUpStartHeight);
        if (goingBetweenClosedShadeAndExpandedQs && qsShouldExpandWithHeadsUp) {
            float qsExpansionFraction;
            if (mSplitShadeEnabled) {
                qsExpansionFraction = 1;
            } else if (isKeyguardShowing()) {
                // On Keyguard, interpolate the QS expansion linearly to the panel expansion
                qsExpansionFraction = expandedHeight / (getMaxPanelHeight());
            } else {
                // In Shade, interpolate linearly such that QS is closed whenever panel height is
                // minimum QS expansion + minStackHeight
                float panelHeightQsCollapsed =
                        mNotificationStackScrollLayoutController.getIntrinsicPadding()
                                + mNotificationStackScrollLayoutController.getLayoutMinHeight();
                float panelHeightQsExpanded = mQsController.calculatePanelHeightExpanded(
                        mClockPositionResult.stackScrollerPadding);
                qsExpansionFraction = (expandedHeight - panelHeightQsCollapsed)
                        / (panelHeightQsExpanded - panelHeightQsCollapsed);
            }
            float targetHeight = mQsController.getMinExpansionHeight() + qsExpansionFraction
                    * (mQsController.getMaxExpansionHeight()
                    - mQsController.getMinExpansionHeight());
            mQsController.setExpansionHeight(targetHeight);
        }
        updateExpandedHeight(expandedHeight);
        updateHeader();
        updatePanelExpanded();
        updateGestureExclusionRect();
        if (DEBUG_DRAWABLE) {
            mView.invalidate();
        }
    }

    private void updatePanelExpanded() {
        boolean isExpanded = !isFullyCollapsed() || mExpectingSynthesizedDown;
        if (isPanelExpanded() != isExpanded) {
            setExpandedOrAwaitingInputTransfer(isExpanded);
            updateSystemUiStateFlags();
            if (!isExpanded) {
                mQsController.closeQsCustomizer();
            }
        }
    }

    private void setExpandedOrAwaitingInputTransfer(boolean expandedOrAwaitingInputTransfer) {
        mShadeRepository.setLegacyExpandedOrAwaitingInputTransfer(expandedOrAwaitingInputTransfer);
    }

    @Override
    public boolean isPanelExpanded() {
        return mShadeRepository.getLegacyExpandedOrAwaitingInputTransfer().getValue();
    }

    private int calculatePanelHeightShade() {
        // Bypass should always occupy the full height
        if (mBarState == KEYGUARD && mKeyguardBypassController.getBypassEnabled()) {
            return mNotificationStackScrollLayoutController.getHeight();
        }

        int emptyBottomMargin = mNotificationStackScrollLayoutController.getEmptyBottomMargin();
        int maxHeight = mNotificationStackScrollLayoutController.getHeight() - emptyBottomMargin;

        if (mBarState == KEYGUARD) {
            int minKeyguardPanelBottom = mNotificationStackScrollLayoutController
                    .getIntrinsicContentHeight();
            return Math.max(maxHeight, minKeyguardPanelBottom);
        } else {
            return maxHeight;
        }
    }

    private float getFadeoutAlpha() {
        float alpha;
        if (mQsController.getMinExpansionHeight() == 0) {
            return 1.0f;
        }
        alpha = getExpandedHeight() / mQsController.getMinExpansionHeight();
        alpha = Math.max(0, Math.min(alpha, 1));
        alpha = (float) Math.pow(alpha, 0.75);
        return alpha;
    }

    /** Hides the header when notifications are colliding with it. */
    private void updateHeader() {
        if (mBarState == KEYGUARD) {
            mKeyguardStatusBarViewController.updateViewState();
        }
        mQsController.updateExpansion();
    }

    private void onExpandingFinished() {
        if (!SceneContainerFlag.isEnabled()) {
            mNotificationStackScrollLayoutController.onExpansionStopped();
        }
        mHeadsUpManager.onExpandingFinished();
        mConversationNotificationManager.onNotificationPanelExpandStateChanged(isFullyCollapsed());
        mIsExpandingOrCollapsing = false;
        mMediaHierarchyManager.setCollapsingShadeFromQS(false);
        mMediaHierarchyManager.setQsExpanded(mQsController.getExpanded());
        if (isFullyCollapsed()) {
            DejankUtils.postAfterTraversal(() -> setListening(false));

            // Workaround b/22639032: Make sure we invalidate something because else RenderThread
            // thinks we are actually drawing a frame put in reality we don't, so RT doesn't go
            // ahead with rendering and we jank.
            mView.postOnAnimation(
                    () -> mView.getParent().invalidateChild(mView, M_DUMMY_DIRTY_RECT));
        } else {
            setListening(true);
        }
        if (mBarState != SHADE) {
            // TODO(b/277909752): remove below logs when bug is fixed
            mShadeLog.d("onExpandingFinished called");
            if (mSplitShadeEnabled && !mQsController.getExpanded()) {
                mShadeLog.d("onExpandingFinished called before QS got expanded");
            }
            // updating qsExpandImmediate is done in onPanelStateChanged for unlocked shade but
            // on keyguard panel state is always OPEN so we need to have that extra update
            mQsController.setExpandImmediate(false);
        }
        setShowShelfOnly(false);
        mQsController.setTwoFingerExpandPossible(false);
        mShadeHeadsUpTracker.updateTrackingHeadsUp(null);
        mExpandingFromHeadsUp = false;
        setPanelScrimMinFraction(0.0f);
        // Reset status bar alpha so alpha can be calculated upon updating view state.
        setKeyguardStatusBarAlpha(-1f);
    }

    private void setListening(boolean listening) {
        mKeyguardStatusBarViewController.setBatteryListening(listening);
        mQsController.setListening(listening);
    }

    public void expand(boolean animate) {
        if (isFullyCollapsed() || isCollapsing()) {
            mInstantExpanding = true;
            mAnimateAfterExpanding = animate;
            mUpdateFlingOnLayout = false;
            abortAnimations();
            if (isTracking()) {
                // The panel is expanded after this call.
                onTrackingStopped(true /* expands */);
            }
            if (mExpanding) {
                notifyExpandingFinished();
            }
            updateExpansionAndVisibility();
            // Wait for window manager to pickup the change, so we know the maximum height of the
            // panel then.
            this.mView.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            if (!mInstantExpanding) {
                                mView.getViewTreeObserver().removeOnGlobalLayoutListener(
                                        this);
                                return;
                            }
                            if (mNotificationShadeWindowController.getWindowRootView()
                                    .isVisibleToUser()) {
                                mView.getViewTreeObserver().removeOnGlobalLayoutListener(
                                        this);
                                if (mAnimateAfterExpanding) {
                                    notifyExpandingStarted();
                                    mQsController.beginJankMonitoring(isFullyCollapsed());
                                    fling(0  /* expand */);
                                } else {
                                    setExpandedFraction(1f);
                                }
                                mInstantExpanding = false;
                            }
                        }
                    });
            // Make sure a layout really happens.
            boostFramesDuringRelayout();
        }

        setListening(true);
    }

    @VisibleForTesting
    void setOverExpansion(float overExpansion) {
        if (overExpansion == mOverExpansion) {
            return;
        }
        mOverExpansion = overExpansion;
        if (mSplitShadeEnabled) {
            mQsController.setOverScrollAmount((int) overExpansion);
            mScrimController.setNotificationsOverScrollAmount((int) overExpansion);
        } else {
            // Translating the quick settings by half the overexpansion to center it in the
            // background frame
            mQsController.updateQsFrameTranslation();
        }
        mNotificationStackScrollLayoutController.setOverExpansion(overExpansion);
    }

    private void falsingAdditionalTapRequired() {
        if (mStatusBarStateController.getState() == StatusBarState.SHADE_LOCKED) {
            mTapAgainViewController.show();
        } else {
            mKeyguardIndicationController.showTransientIndication(
                    R.string.notification_tap_again);
        }

        if (!mStatusBarStateController.isDozing()) {
            performHapticFeedback(HapticFeedbackConstants.REJECT);
        }
    }

    private void onTrackingStarted() {
        endClosing();
        mShadeRepository.setLegacyShadeTracking(true);
        if (mTrackingStartedListener != null) {
            mTrackingStartedListener.onTrackingStarted();
        }
        notifyExpandingStarted();
        updateExpansionAndVisibility();
        mScrimController.onTrackingStarted();
        if (mQsController.getFullyExpanded()) {
            mQsController.setExpandImmediate(true);
            setShowShelfOnly(true);
        }
        mNotificationStackScrollLayoutController.onPanelTrackingStarted();
        cancelPendingCollapse();
    }

    private void onTrackingStopped(boolean expand) {
        mShadeRepository.setLegacyShadeTracking(false);

        updateExpansionAndVisibility();
        if (expand) {
            mNotificationStackScrollLayoutController.setOverScrollAmount(0.0f, true /* onTop */,
                    true /* animate */);
        }
        mNotificationStackScrollLayoutController.onPanelTrackingStopped();

        // If we unlocked from a swipe, the user's finger might still be down after the
        // unlock animation ends. We need to wait until ACTION_UP to enable blurs again.
        mDepthController.setBlursDisabledForUnlock(false);
    }

    private void updateMaxHeadsUpTranslation() {
        mNotificationStackScrollLayoutController.setHeadsUpBoundaries(
                mView.getHeight(), mNavigationBarBottomHeight);
    }

    private boolean shouldUseDismissingAnimation() {
        return mBarState != StatusBarState.SHADE && (mKeyguardStateController.canDismissLockScreen()
                || !isTracking());
    }

    @VisibleForTesting
    int getMaxPanelTransitionDistance() {
        // Traditionally the value is based on the number of notifications. On split-shade, we want
        // the required distance to be a specific and constant value, to make sure the expansion
        // motion has the expected speed. We also only want this on non-lockscreen for now.
        if (mSplitShadeEnabled && mBarState == SHADE) {
            boolean transitionFromHeadsUp = (mHeadsUpManager != null
                    && mHeadsUpManager.isTrackingHeadsUp().getValue()) || mExpandingFromHeadsUp;
            // heads-up starting height is too close to mSplitShadeFullTransitionDistance and
            // when dragging HUN transition is already 90% complete. It makes shade become
            // immediately visible when starting to drag. We want to set distance so that
            // nothing is immediately visible when dragging (important for HUN swipe up motion) -
            // 0.4 expansion fraction is a good starting point.
            if (transitionFromHeadsUp) {
                double maxDistance = Math.max(mSplitShadeFullTransitionDistance,
                        mHeadsUpStartHeight * 2.5);
                return (int) Math.min(getMaxPanelHeight(), maxDistance);
            } else {
                return mSplitShadeFullTransitionDistance;
            }
        } else {
            return getMaxPanelHeight();
        }
    }

    private boolean isLaunchingActivity() {
        return mShadeAnimationInteractor.isLaunchingActivity().getValue();
    }

    @VisibleForTesting
    void setClosing(boolean isClosing) {
        mShadeRepository.setLegacyIsClosing(isClosing);
        mAmbientState.setIsClosing(isClosing);
    }

    private void updateDozingVisibilities(boolean animate) {
        mKeyguardInteractor.setAnimateDozingTransitions(animate);
        if (!mDozing && animate) {
            mKeyguardStatusBarViewController.animateKeyguardStatusBarIn();
        }
    }

    private void onMiddleClicked(float x, float y) {
        switch (mBarState) {
            case KEYGUARD:
                if (!mDozingOnDown) {
                    mShadeLog.v("onMiddleClicked on Keyguard, mDozingOnDown: false");
                    // Try triggering face auth, this "might" run. Check
                    // KeyguardUpdateMonitor#shouldListenForFace to see when face auth won't run.
                    mDeviceEntryFaceAuthInteractor.onNotificationPanelClicked();

                    if (mDeviceEntryFaceAuthInteractor.canFaceAuthRun()) {
                        mUpdateMonitor.requestActiveUnlock(
                                ActiveUnlockConfig.ActiveUnlockRequestOrigin.UNLOCK_INTENT_LEGACY,
                                "lockScreenEmptySpaceTap");
                    } else {
                        mLockscreenGestureLogger.write(MetricsEvent.ACTION_LS_HINT,
                                0 /* lengthDp - N/A */, 0 /* velocityDp - N/A */);
                        mLockscreenGestureLogger
                                .log(LockscreenUiEvent.LOCKSCREEN_LOCK_SHOW_HINT);
                        mKeyguardIndicationController.showActionToUnlock();
                        mKeyguardClockInteractor.handleFidgetTap(x, y);
                    }
                }
                break;
            case StatusBarState.SHADE_LOCKED:
                if (!mQsController.getExpanded()) {
                    mStatusBarStateController.setState(KEYGUARD);
                }
                break;
        }
    }

    public void setLockscreenDoubleTapToSleep(boolean isDoubleTapEnabled) {
        mIsLockscreenDoubleTapEnabled = isDoubleTapEnabled;
    }

    public void setSbDoubleTapToSleep(boolean isDoubleTapEnabled) {
        mIsSbDoubleTapEnabled = isDoubleTapEnabled;
    }

    @Override
    public void setAlpha(int alpha, boolean animate) {
        if (mPanelAlpha != alpha) {
            mPanelAlpha = alpha;
            PropertyAnimator.setProperty(mView, mPanelAlphaAnimator, alpha, alpha == 255
                            ? mPanelAlphaInPropertiesAnimator : mPanelAlphaOutPropertiesAnimator,
                    animate);
        }
    }

    private void setAlphaInternal(float alpha) {
        mKeyguardInteractor.setPanelAlpha(alpha / 255f);
        mView.setPanelAlphaInternal(alpha);
    }

    @Override
    public void setAlphaChangeAnimationEndAction(Runnable r) {
        mPanelAlphaEndAction = r;
    }

    private void setHeadsUpAnimatingAway(boolean headsUpAnimatingAway) {
        mHeadsUpAnimatingAway = headsUpAnimatingAway;
        mNotificationStackScrollLayoutController.setHeadsUpAnimatingAway(headsUpAnimatingAway);
        updateVisibility();
    }

    @Override
    public void setBouncerShowing(boolean bouncerShowing) {
        mBouncerShowing = bouncerShowing;
        updateVisibility();
    }

    private boolean shouldPanelBeVisible() {
        boolean headsUpVisible = mHeadsUpAnimatingAway || mHeadsUpPinnedMode;
        return headsUpVisible || isExpanded() || mBouncerShowing;
    }

    private void setHeadsUpManager(HeadsUpManager headsUpManager) {
        mHeadsUpManager = headsUpManager;
        mHeadsUpManager.addListener(mOnHeadsUpChangedListener);
        mHeadsUpTouchHelper = new HeadsUpTouchHelper(
                headsUpManager,
                mStatusBarService,
                mNotificationStackScrollLayoutController.getHeadsUpCallback(),
                new HeadsUpNotificationViewControllerImpl());
    }

    private void onClosingFinished() {
        if (mOpenCloseListener != null) {
            mOpenCloseListener.onClosingFinished();
        }
        setClosingWithAlphaFadeout(false);
        mMediaHierarchyManager.closeGuts();
    }

    private void setClosingWithAlphaFadeout(boolean closing) {
        mClosingWithAlphaFadeOut = closing;
        mNotificationStackScrollLayoutController.forceNoOverlappingRendering(closing);
    }

    private void updateExpandedHeight(float expandedHeight) {
        if (isTracking()) {
            mNotificationStackScrollLayoutController
                    .setExpandingVelocity(getCurrentExpandVelocity());
        }
        if (mKeyguardBypassController.getBypassEnabled() && isKeyguardShowing()) {
            // The expandedHeight is always the full panel Height when bypassing
            expandedHeight = getMaxPanelHeight();
        }
        if (!SceneContainerFlag.isEnabled()) {
            mNotificationStackScrollLayoutController.setExpandedHeight(expandedHeight);
        }
        updateStatusBarIcons();
    }

    private void updateStatusBarIcons() {
        boolean showIconsWhenExpanded = getExpandedHeight() < getOpeningHeight();
        if (showIconsWhenExpanded && isKeyguardShowing()) {
            showIconsWhenExpanded = false;
        }
        if (showIconsWhenExpanded != mShowIconsWhenExpanded) {
            mShowIconsWhenExpanded = showIconsWhenExpanded;
            mCommandQueue.recomputeDisableFlags(mDisplayId, false);
        }
    }

    /** @deprecated Temporary a11y solution until dual shade launch b/371224114 */
    @Override
    @Deprecated
    public void onStatusBarLongPress(MotionEvent event) {
        Log.i(TAG, "Status Bar was long pressed.");
        if (DISABLE_LONG_PRESS_EXPAND) {
            //TODO(b/394977231) delete this temporary workaround used only by tests
            Log.i(TAG, "Ignoring status Bar long press on virtualized test device.");
            return;
        }
        ShadeExpandsOnStatusBarLongPress.unsafeAssertInNewMode();
        mStatusBarLongPressDowntime = event.getDownTime();
        if (isTracking()) {
            onTrackingStopped(true);
        }
        if (!mQsController.getExpanded()) {
            performHapticFeedback(HapticFeedbackConstants.GESTURE_START);
            if (isExpanded() && mBarState != KEYGUARD) {
                mShadeLog.d("Status Bar was long pressed. Expanding to QS.");
                mQsController.flingQs(0, FLING_EXPAND);
            } else {
                if (mBarState == KEYGUARD) {
                    mShadeLog.d("Lockscreen Status Bar was long pressed. Expanding to Notifications.");
                    mLockscreenShadeTransitionController.goToLockedShade(
                            /* expandedView= */null, /* needsQSAnimation= */true);
                } else {
                    mShadeLog.d("Status Bar was long pressed. Expanding to Notifications.");
                    expandToNotifications();
                }
            }
        }
    }

    @Override
    public int getBarState() {
        return mBarState;
    }

    /** Called when a HUN is dragged up or down to indicate the starting height for shade motion. */
    @VisibleForTesting
    void setHeadsUpDraggingStartingHeight(int startHeight) {
        mHeadsUpStartHeight = startHeight;
        float scrimMinFraction;
        if (mSplitShadeEnabled) {
            boolean highHun = mHeadsUpStartHeight * 2.5
                    > mSplitShadeFullTransitionDistance;
            // if HUN height is higher than 40% of predefined transition distance, it means HUN
            // is too high for regular transition. In that case we need to calculate transition
            // distance - here we take scrim transition distance as equal to shade transition
            // distance. It doesn't result in perfect motion - usually scrim transition distance
            // should be longer - but it's good enough for HUN case.
            float transitionDistance =
                    highHun ? getMaxPanelTransitionDistance() : mSplitShadeFullTransitionDistance;
            scrimMinFraction = mHeadsUpStartHeight / transitionDistance;
        } else {
            int transitionDistance = getMaxPanelHeight();
            scrimMinFraction = transitionDistance > 0f
                    ? (float) mHeadsUpStartHeight / transitionDistance : 0f;
        }
        setPanelScrimMinFraction(scrimMinFraction);
    }

    /**
     * Sets the minimum fraction for the panel expansion offset. This may be non-zero in certain
     * cases, such as if there's a heads-up notification.
     */
    private void setPanelScrimMinFraction(float minFraction) {
        mMinFraction = minFraction;
        mDepthController.setPanelPullDownMinFraction(mMinFraction);
        mScrimController.setPanelScrimMinFraction(mMinFraction);
    }

    private boolean isPanelVisibleBecauseOfHeadsUp() {
        boolean headsUpVisible = (mHeadsUpManager != null && mHeadsUpManager.hasPinnedHeadsUp())
                || mHeadsUpAnimatingAway;
        return headsUpVisible && mBarState == StatusBarState.SHADE;
    }

    private boolean isPanelVisibleBecauseScrimIsAnimatingOff() {
        return mUnlockedScreenOffAnimationController.isAnimationPlaying();
    }

    public boolean shouldHideStatusBarIconsWhenExpanded() {
        if (isLaunchingActivity()) {
            return false;
        }
        if (mHeadsUpAppearanceController != null
                && mHeadsUpAppearanceController.shouldHeadsUpStatusBarBeVisible()) {
            return false;
        }
        return !mShowIconsWhenExpanded;
    }

    @Override
    public void setTouchAndAnimationDisabled(boolean disabled) {
        mTouchDisabled = disabled;
        if (mTouchDisabled) {
            cancelHeightAnimator();
            if (isTracking()) {
                onTrackingStopped(true /* expanded */);
            }
            notifyExpandingFinished();
        }
        // TODO(b/332732878): replace this call when scene container is enabled
        mNotificationStackScrollLayoutController.setAnimationsEnabled(!disabled);
    }

    @Override
    public void setDozing(boolean dozing, boolean animate) {
        if (dozing == mDozing) return;
        mView.setDozing(dozing);
        mDozing = dozing;
        // TODO (b/) make listeners for this
        mNotificationStackScrollLayoutController.setDozing(mDozing, animate);
        mKeyguardInteractor.setAnimateDozingTransitions(animate);
        mKeyguardStatusBarViewController.setDozing(mDozing);
        mQsController.setDozing(mDozing);

        if (mBarState == KEYGUARD || mBarState == StatusBarState.SHADE_LOCKED) {
            updateDozingVisibilities(animate);
        }

        final float dozeAmount = dozing ? 1 : 0;
        mStatusBarStateController.setAndInstrumentDozeAmount(mView, dozeAmount, animate);

        updateKeyguardStatusViewAlignment();
    }

    @Override
    public void setPulsing(boolean pulsing) {
        mPulsing = pulsing;
        final boolean
                animatePulse =
                !mDozeParameters.getDisplayNeedsBlanking() && mDozeParameters.getAlwaysOn();
        if (animatePulse) {
            mAnimateNextPositionUpdate = true;
        }
        // Do not animate the clock when waking up from a pulse.
        // The height callback will take care of pushing the clock to the right position.
        if (!mPulsing && !mDozing) {
            mAnimateNextPositionUpdate = false;
        }
        mNotificationStackScrollLayoutController.setPulsing(pulsing, animatePulse);

        updateKeyguardStatusViewAlignment();
    }

    public void performHapticFeedback(int constant) {
        if (msdlFeedback()) {
            MSDLToken token;
            switch (constant) {
                case HapticFeedbackConstants.GESTURE_START ->
                        token = MSDLToken.SWIPE_THRESHOLD_INDICATOR;
                case HapticFeedbackConstants.REJECT -> token = MSDLToken.FAILURE;
                default -> token = null;
            }
            if (token != null) {
                mMSDLPlayer.playToken(token, null);
            }
        } else {
            mVibratorHelper.performHapticFeedback(mView, constant);
        }
    }

    private class ShadeHeadsUpTrackerImpl implements ShadeHeadsUpTracker {
        @Override
        public void addTrackingHeadsUpListener(Consumer<ExpandableNotificationRow> listener) {
            mTrackingHeadsUpListeners.add(listener);
        }

        @Override
        public void removeTrackingHeadsUpListener(Consumer<ExpandableNotificationRow> listener) {
            mTrackingHeadsUpListeners.remove(listener);
        }

        @Override
        public void setHeadsUpAppearanceController(
                HeadsUpAppearanceController headsUpAppearanceController) {
            mHeadsUpAppearanceController = headsUpAppearanceController;
        }

        @Override
        @Nullable public ExpandableNotificationRow getTrackedHeadsUpNotification() {
            return mTrackedHeadsUpNotification;
        }

        private void updateTrackingHeadsUp(@Nullable ExpandableNotificationRow pickedChild) {
            mTrackedHeadsUpNotification = pickedChild;
            for (int i = 0; i < mTrackingHeadsUpListeners.size(); i++) {
                Consumer<ExpandableNotificationRow> listener = mTrackingHeadsUpListeners.get(i);
                listener.accept(pickedChild);
            }
        }
    }

    @Override
    public ShadeHeadsUpTracker getShadeHeadsUpTracker() {
        return mShadeHeadsUpTracker;
    }

    @Override
    public ShadeFoldAnimatorImpl getShadeFoldAnimator() {
        return mShadeFoldAnimator;
    }

    @Deprecated
    public final class ShadeFoldAnimatorImpl implements ShadeFoldAnimator {
        /** Updates the views to the initial state for the fold to AOD animation. */
        @Override
        public void prepareFoldToAodAnimation() {
            setDozing(true /* dozing */, false /* animate */);

            // Move the content of the AOD all the way to the left
            // so we can animate to the initial position
            final int translationAmount = mView.getResources().getDimensionPixelSize(
                    R.dimen.below_clock_padding_start);
            mView.setTranslationX(-translationAmount);
            mView.setAlpha(0);
        }

        /**
         * Starts fold to AOD animation.
         *
         * @param startAction  invoked when the animation starts.
         * @param endAction    invoked when the animation finishes, also if it was cancelled.
         * @param cancelAction invoked when the animation is cancelled, before endAction.
         */
        @Override
        public void startFoldToAodAnimation(
                Runnable startAction, Runnable endAction, Runnable cancelAction) {

        }

        /**
         * Builds the default NPVC fold animator
         *
         * @deprecated Temporary stop-gap. Do not use outside of keyguard fold transition.
         */
        @Deprecated
        public ViewPropertyAnimator buildViewAnimator(
                Runnable startAction, Runnable endAction, Runnable cancelAction) {
            final ViewPropertyAnimator viewAnimator = mView.animate();
            viewAnimator.cancel();
            return viewAnimator
                    .translationX(0)
                    .alpha(1f)
                    .setDuration(ANIMATION_DURATION_FOLD_TO_AOD)
                    .setInterpolator(EMPHASIZED_DECELERATE)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationStart(Animator animation) {
                            startAction.run();
                        }

                        @Override
                        public void onAnimationCancel(Animator animation) {
                            cancelAction.run();
                        }

                        @Override
                        public void onAnimationEnd(Animator animation) {
                            endAction.run();

                            viewAnimator.setListener(null);
                            viewAnimator.setUpdateListener(null);
                        }
                    });
        }

        /** Cancels fold to AOD transition and resets view state. */
        @Override
        public void cancelFoldToAodAnimation() {
            cancelAnimation();
            resetAlpha();
            resetTranslation();
        }
    }

    @Override
    public void setImportantForAccessibility(int mode) {
        mView.setImportantForAccessibility(mode);
    }

    @Override
    public void blockExpansionForCurrentTouch() {
        mBlockingExpansionForCurrentTouch = isTracking();
    }

    @NeverCompile
    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println(TAG + ":");
        IndentingPrintWriter ipw = asIndenting(pw);
        ipw.increaseIndent();

        ipw.print("mDownTime="); ipw.println(mDownTime);
        ipw.print("mTouchSlopExceededBeforeDown="); ipw.println(mTouchSlopExceededBeforeDown);
        ipw.print("mIsLaunchAnimationRunning="); ipw.println(isLaunchingActivity());
        ipw.print("mOverExpansion="); ipw.println(mOverExpansion);
        ipw.print("mExpandedHeight="); ipw.println(mExpandedHeight);
        ipw.print("isTracking()="); ipw.println(isTracking());
        ipw.print("mExpanding="); ipw.println(mExpanding);
        ipw.print("mSplitShadeEnabled="); ipw.println(mSplitShadeEnabled);
        ipw.print("mAnimateNextPositionUpdate="); ipw.println(mAnimateNextPositionUpdate);
        ipw.print("isPanelExpanded()="); ipw.println(isPanelExpanded());
        ipw.print("mDozing="); ipw.println(mDozing);
        ipw.print("mDozingOnDown="); ipw.println(mDozingOnDown);
        ipw.print("mBouncerShowing="); ipw.println(mBouncerShowing);
        ipw.print("mBarState="); ipw.println(mBarState);
        ipw.print("mStatusBarMinHeight="); ipw.println(mStatusBarMinHeight);
        ipw.print("mStatusBarHeaderHeightKeyguard="); ipw.println(mStatusBarHeaderHeightKeyguard);
        ipw.print("mOverStretchAmount="); ipw.println(mOverStretchAmount);
        ipw.print("mDownX="); ipw.println(mDownX);
        ipw.print("mDownY="); ipw.println(mDownY);
        ipw.print("mDisplayTopInset="); ipw.println(mDisplayTopInset);
        ipw.print("mDisplayRightInset="); ipw.println(mDisplayRightInset);
        ipw.print("mDisplayLeftInset="); ipw.println(mDisplayLeftInset);
        ipw.print("mIsExpandingOrCollapsing="); ipw.println(mIsExpandingOrCollapsing);
        ipw.print("mHeadsUpStartHeight="); ipw.println(mHeadsUpStartHeight);
        ipw.print("mListenForHeadsUp="); ipw.println(mListenForHeadsUp);
        ipw.print("mNavigationBarBottomHeight="); ipw.println(mNavigationBarBottomHeight);
        ipw.print("mExpandingFromHeadsUp="); ipw.println(mExpandingFromHeadsUp);
        ipw.print("mCollapsedOnDown="); ipw.println(mCollapsedOnDown);
        ipw.print("mClosingWithAlphaFadeOut="); ipw.println(mClosingWithAlphaFadeOut);
        ipw.print("mHeadsUpAnimatingAway="); ipw.println(mHeadsUpAnimatingAway);
        ipw.print("mShowIconsWhenExpanded="); ipw.println(mShowIconsWhenExpanded);
        ipw.print("mIsFullWidth="); ipw.println(mIsFullWidth);
        ipw.print("mBlockingExpansionForCurrentTouch=");
        ipw.println(mBlockingExpansionForCurrentTouch);
        ipw.print("mExpectingSynthesizedDown="); ipw.println(mExpectingSynthesizedDown);
        ipw.print("mLastEventSynthesizedDown="); ipw.println(mLastEventSynthesizedDown);
        ipw.print("mInterpolatedDarkAmount="); ipw.println(mInterpolatedDarkAmount);
        ipw.print("mLinearDarkAmount="); ipw.println(mLinearDarkAmount);
        ipw.print("mPulsing="); ipw.println(mPulsing);
        ipw.print("mStackScrollerMeasuringPass="); ipw.println(mStackScrollerMeasuringPass);
        ipw.print("mPanelAlpha="); ipw.println(mPanelAlpha);
        ipw.print("mHeadsUpInset="); ipw.println(mHeadsUpInset);
        ipw.print("mHeadsUpPinnedMode="); ipw.println(mHeadsUpPinnedMode);
        ipw.print("mAllowExpandForSmallExpansion="); ipw.println(mAllowExpandForSmallExpansion);
        ipw.print("mMaxOverscrollAmountForPulse="); ipw.println(mMaxOverscrollAmountForPulse);
        ipw.print("mIsPanelCollapseOnQQS="); ipw.println(mIsPanelCollapseOnQQS);
        ipw.print("mIsGestureNavigation="); ipw.println(mIsGestureNavigation);
        ipw.print("mOldLayoutDirection="); ipw.println(mOldLayoutDirection);
        ipw.print("mMinFraction="); ipw.println(mMinFraction);
        ipw.print("mSplitShadeFullTransitionDistance=");
        ipw.println(mSplitShadeFullTransitionDistance);
        ipw.print("mSplitShadeScrimTransitionDistance=");
        ipw.println(mSplitShadeScrimTransitionDistance);
        ipw.print("mMinExpandHeight="); ipw.println(mMinExpandHeight);
        ipw.print("mPanelUpdateWhenAnimatorEnds="); ipw.println(mPanelUpdateWhenAnimatorEnds);
        ipw.print("mHasVibratedOnOpen="); ipw.println(mHasVibratedOnOpen);
        ipw.print("mFixedDuration="); ipw.println(mFixedDuration);
        ipw.print("mPanelFlingOvershootAmount="); ipw.println(mPanelFlingOvershootAmount);
        ipw.print("mLastGesturedOverExpansion="); ipw.println(mLastGesturedOverExpansion);
        ipw.print("mIsSpringBackAnimation="); ipw.println(mIsSpringBackAnimation);
        ipw.print("mHintDistance="); ipw.println(mHintDistance);
        ipw.print("mInitialOffsetOnTouch="); ipw.println(mInitialOffsetOnTouch);
        ipw.print("mCollapsedAndHeadsUpOnDown="); ipw.println(mCollapsedAndHeadsUpOnDown);
        ipw.print("mExpandedFraction="); ipw.println(mExpandedFraction);
        ipw.print("mExpansionDragDownAmountPx="); ipw.println(mExpansionDragDownAmountPx);
        ipw.print("mPanelClosedOnDown="); ipw.println(mPanelClosedOnDown);
        ipw.print("mHasLayoutedSinceDown="); ipw.println(mHasLayoutedSinceDown);
        ipw.print("mUpdateFlingVelocity="); ipw.println(mUpdateFlingVelocity);
        ipw.print("mUpdateFlingOnLayout="); ipw.println(mUpdateFlingOnLayout);
        ipw.print("isClosing()="); ipw.println(isClosing());
        ipw.print("mTouchSlopExceeded="); ipw.println(mTouchSlopExceeded);
        ipw.print("mTrackingPointer="); ipw.println(mTrackingPointer);
        ipw.print("mTouchSlop="); ipw.println(mTouchSlop);
        ipw.print("mSlopMultiplier="); ipw.println(mSlopMultiplier);
        ipw.print("mTouchAboveFalsingThreshold="); ipw.println(mTouchAboveFalsingThreshold);
        ipw.print("mTouchStartedInEmptyArea="); ipw.println(mTouchStartedInEmptyArea);
        ipw.print("mMotionAborted="); ipw.println(mMotionAborted);
        ipw.print("mUpwardsWhenThresholdReached="); ipw.println(mUpwardsWhenThresholdReached);
        ipw.print("mAnimatingOnDown="); ipw.println(mAnimatingOnDown);
        ipw.print("mHandlingPointerUp="); ipw.println(mHandlingPointerUp);
        ipw.print("mInstantExpanding="); ipw.println(mInstantExpanding);
        ipw.print("mAnimateAfterExpanding="); ipw.println(mAnimateAfterExpanding);
        ipw.print("mIsFlinging="); ipw.println(mIsFlinging);
        ipw.print("mViewName="); ipw.println(mViewName);
        ipw.print("mInitialExpandY="); ipw.println(mInitialExpandY);
        ipw.print("mInitialExpandX="); ipw.println(mInitialExpandX);
        ipw.print("mTouchDisabled="); ipw.println(mTouchDisabled);
        ipw.print("mInitialTouchFromKeyguard="); ipw.println(mInitialTouchFromKeyguard);
        ipw.print("mNextCollapseSpeedUpFactor="); ipw.println(mNextCollapseSpeedUpFactor);
        ipw.print("mGestureWaitForTouchSlop="); ipw.println(mGestureWaitForTouchSlop);
        ipw.print("mIgnoreXTouchSlop="); ipw.println(mIgnoreXTouchSlop);
        ipw.print("mExpandLatencyTracking="); ipw.println(mExpandLatencyTracking);
        ipw.println("gestureExclusionRect:" + calculateGestureExclusionRect());
        Trace.beginSection("Table<DownEvents>");
        new DumpsysTableLogger(
                TAG,
                NPVCDownEventState.TABLE_HEADERS,
                mLastDownEvents.toList()
        ).printTableData(ipw);
        Trace.endSection();
    }

    @Override
    public void initDependencies(
            CentralSurfaces centralSurfaces,
            GestureRecorder recorder,
            Runnable hideExpandedRunnable,
            HeadsUpManager headsUpManager) {
        setHeadsUpManager(headsUpManager);
        // TODO(b/254859580): this can be injected.
        mCentralSurfaces = centralSurfaces;

        mGestureRecorder = recorder;
        mHideExpandedRunnable = hideExpandedRunnable;
    }

    @Override
    public void resetTranslation() {
        mView.setTranslationX(0f);
    }

    @Override
    public void resetAlpha() {
        mView.setAlpha(1f);
    }

    @Override
    public void fadeOut(long startDelayMs, long durationMs, Runnable endAction) {
        mView.animate().cancel();
        mView.animate().alpha(0).setStartDelay(startDelayMs).setDuration(
                durationMs).setInterpolator(Interpolators.ALPHA_OUT).withLayer().withEndAction(
                endAction);
    }

    @Override
    public void resetViewGroupFade() {
        ViewGroupFadeHelper.reset(mView);
    }

    public void addOnGlobalLayoutListener(ViewTreeObserver.OnGlobalLayoutListener listener) {
        mView.getViewTreeObserver().addOnGlobalLayoutListener(listener);
    }

    public void removeOnGlobalLayoutListener(ViewTreeObserver.OnGlobalLayoutListener listener) {
        mView.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
    }

    @Override
    public void onThemeChanged() {
        mConfigurationListener.onThemeChanged();
    }

    @VisibleForTesting
    TouchHandler getTouchHandler() {
        return mTouchHandler;
    }

    @Override
    public void updateSystemUiStateFlags() {
        if (SysUiState.DEBUG) {
            Log.d(TAG, "Updating panel sysui state flags: fullyExpanded="
                    + isFullyExpanded() + " inQs=" + mQsController.getExpanded());
        }
        if (ShadeWindowGoesAround.isEnabled()) {
            setPerDisplaySysUIStateFlags();
        } else {
            setDefaultDisplayFlags();
        }
    }

    private int getShadeDisplayId() {
        if (ShadeWindowGoesAround.isEnabled()) {
            var pendingDisplayId =
                    mShadeDisplaysRepository.get().getPendingDisplayId().getValue();
            // Use the pendingDisplayId from the repository, *not* the Shade's context.
            // This ensures correct UI state updates also if this method is called just *before*
            // the Shade window moves to another display.
            // The pendingDisplayId is guaranteed to be updated before this method is called.
            return pendingDisplayId;
        } else {
            return Display.DEFAULT_DISPLAY;
        }
    }

    private void setPerDisplaySysUIStateFlags() {
        mSysUIStateDisplaysInteractor.setFlagsExclusivelyToDisplay(
                getShadeDisplayId(),
                new StateChange()
                        .setFlag(SYSUI_STATE_NOTIFICATION_PANEL_VISIBLE,
                                isPanelExpanded() && !isCollapsing())
                        .setFlag(SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED,
                                isFullyExpanded() && !mQsController.getExpanded())
                        .setFlag(SYSUI_STATE_QUICK_SETTINGS_EXPANDED,
                                isFullyExpanded() && mQsController.getExpanded())
        );
    }

    @Deprecated
    private void setDefaultDisplayFlags() {
        mSysUiState
                .setFlag(SYSUI_STATE_NOTIFICATION_PANEL_VISIBLE,
                        isPanelExpanded() && !isCollapsing())
                .setFlag(SYSUI_STATE_NOTIFICATION_PANEL_EXPANDED,
                        isFullyExpanded() && !mQsController.getExpanded())
                .setFlag(SYSUI_STATE_QUICK_SETTINGS_EXPANDED,
                        isFullyExpanded() && mQsController.getExpanded()).commitUpdate(
                        mDisplayId);
    }

    private void debugLog(String fmt, Object... args) {
        if (DEBUG_LOGCAT) {
            Log.d(TAG, (mViewName != null ? (mViewName + ": ") : "") + String.format(fmt, args));
        }
    }

    @VisibleForTesting
    void notifyExpandingStarted() {
        if (!mExpanding) {
            DejankUtils.notifyRendererOfExpensiveFrame(mView, "notifyExpandingStarted");
            mExpanding = true;
            mIsExpandingOrCollapsing = true;
            mQsController.onExpandingStarted(mQsController.getFullyExpanded());
        }
    }

    void notifyExpandingFinished() {
        endClosing();
        if (mExpanding) {
            mExpanding = false;
            onExpandingFinished();
        }
    }

    float getTouchSlop(MotionEvent event) {
        // Adjust the touch slop if another gesture may be being performed.
        return event.getClassification() == MotionEvent.CLASSIFICATION_AMBIGUOUS_GESTURE
                ? mTouchSlop * mSlopMultiplier
                : mTouchSlop;
    }

    private void addMovement(MotionEvent event) {
        // Add movement to velocity tracker using raw screen X and Y coordinates instead
        // of window coordinates because the window frame may be moving at the same time.
        float deltaX = event.getRawX() - event.getX();
        float deltaY = event.getRawY() - event.getY();
        event.offsetLocation(deltaX, deltaY);
        mVelocityTracker.addMovement(event);
        event.offsetLocation(-deltaX, -deltaY);
    }

    @Override
    public void startExpandLatencyTracking() {
        if (mLatencyTracker.isEnabled()) {
            mLatencyTracker.onActionStart(LatencyTracker.ACTION_EXPAND_PANEL);
            mExpandLatencyTracking = true;
        }
    }

    private void startOpening(MotionEvent event) {
        updateExpansionAndVisibility();
        //TODO: keyguard opens QS a different way; log that too?

        // Log the position of the swipe that opened the panel
        float width = mCentralSurfaces.getDisplayWidth();
        float height = mCentralSurfaces.getDisplayHeight();
        int rot = mCentralSurfaces.getRotation();

        mLockscreenGestureLogger.writeAtFractionalPosition(MetricsEvent.ACTION_PANEL_VIEW_EXPAND,
                (int) (event.getX() / width * 100), (int) (event.getY() / height * 100), rot);
        mLockscreenGestureLogger
                .log(LockscreenUiEvent.LOCKSCREEN_UNLOCKED_NOTIFICATION_PANEL_EXPAND);
    }

    /**
     * Maybe vibrate as panel is opened.
     *
     * @param openingWithTouch Whether the panel is being opened with touch. If the panel is
     *                         instead being opened programmatically (such as by the open panel
     *                         gesture), we always play haptic.
     */
    private void maybeVibrateOnOpening(boolean openingWithTouch) {
        if (mVibrateOnOpening && mBarState != KEYGUARD && mBarState != SHADE_LOCKED) {
            if (!openingWithTouch || !mHasVibratedOnOpen) {
                performHapticFeedback(HapticFeedbackConstants.GESTURE_START);
                mHasVibratedOnOpen = true;
                mShadeLog.v("Vibrating on opening, mHasVibratedOnOpen=true");
            }
        }
    }

    /**
     * @return whether the swiping direction is upwards and above a 45 degree angle compared to the
     * horizontal direction
     */
    private boolean isDirectionUpwards(float x, float y) {
        float xDiff = x - mInitialExpandX;
        float yDiff = y - mInitialExpandY;
        if (yDiff >= 0) {
            return false;
        }
        return Math.abs(yDiff) >= Math.abs(xDiff);
    }

    /** Called when a MotionEvent is about to trigger Shade expansion. */
    private void startExpandMotion(float newX, float newY, boolean startTracking,
            float expandedHeight) {
        if (!mHandlingPointerUp && !mStatusBarStateController.isDozing()) {
            mQsController.beginJankMonitoring(isFullyCollapsed());
        }
        mInitialOffsetOnTouch = expandedHeight;
        if (!isTracking() || isFullyCollapsed()) {
            mInitialExpandY = newY;
            mInitialExpandX = newX;
        } else {
            mShadeLog.d("not setting mInitialExpandY in startExpandMotion");
        }
        mInitialTouchFromKeyguard = mKeyguardStateController.isShowing();
        if (startTracking) {
            mTouchSlopExceeded = true;
            setExpandedHeight(mInitialOffsetOnTouch);
            onTrackingStarted();
        }
    }

    private void endMotionEvent(MotionEvent event, float x, float y, boolean forceCancel) {
        mShadeLog.logEndMotionEvent("endMotionEvent called", forceCancel, false);
        mTrackingPointer = -1;
        mStatusBarLongPressDowntime = -1L;
        mAmbientState.setSwipingUp(false);
        if ((isTracking() && mTouchSlopExceeded) || Math.abs(x - mInitialExpandX) > mTouchSlop
                || Math.abs(y - mInitialExpandY) > mTouchSlop
                || (!isFullyExpanded() && !isFullyCollapsed())
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL || forceCancel) {
            mVelocityTracker.computeCurrentVelocity(1000);
            float vel = mVelocityTracker.getYVelocity();
            float vectorVel = (float) Math.hypot(
                    mVelocityTracker.getXVelocity(), mVelocityTracker.getYVelocity());

            final boolean onKeyguard = mKeyguardStateController.isShowing();
            final boolean expand;
            if (mKeyguardStateController.isKeyguardFadingAway()
                    || (mInitialTouchFromKeyguard && !onKeyguard)) {
                // Don't expand for any touches that started from the keyguard and ended after the
                // keyguard is gone.
                expand = false;
            } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL || forceCancel) {
                if (onKeyguard) {
                    expand = true;
                    mShadeLog.logEndMotionEvent("endMotionEvent: cancel while on keyguard",
                            forceCancel, expand);
                } else {
                    // If we get a cancel, put the shade back to the state it was in when the
                    // gesture started
                    expand = !mPanelClosedOnDown;
                    mShadeLog.logEndMotionEvent("endMotionEvent: cancel", forceCancel, expand);
                }
            } else {
                expand = flingExpands(vel, vectorVel, x, y);
                mShadeLog.logEndMotionEvent("endMotionEvent: flingExpands", forceCancel, expand);
            }

            mDozeLog.traceFling(
                    expand,
                    mTouchAboveFalsingThreshold,
                    /* screenOnFromTouch=*/ getWakefulness().isAwakeFromTapOrGesture());
            // Log collapse gesture if on lock screen.
            if (!expand && onKeyguard) {
                float displayDensity = getDisplayDensity();
                int heightDp = (int) Math.abs((y - mInitialExpandY) / displayDensity);
                int velocityDp = (int) Math.abs(vel / displayDensity);
                mLockscreenGestureLogger.write(MetricsEvent.ACTION_LS_UNLOCK, heightDp, velocityDp);
                mLockscreenGestureLogger.log(LockscreenUiEvent.LOCKSCREEN_UNLOCK);
            }
            float dy = y - mInitialExpandY;
            @Classifier.InteractionType int interactionType = vel == 0 ? GENERIC
                    : dy > 0 ? QUICK_SETTINGS
                            : (mKeyguardStateController.canDismissLockScreen()
                                    ? UNLOCK : BOUNCER_UNLOCK);

            // don't fling while in keyguard to avoid jump in shade expand animation;
            // touch has been intercepted already so flinging here is redundant
            if (mBarState == KEYGUARD && mExpandedFraction >= 1.0) {
                mShadeLog.d("NPVC endMotionEvent - skipping fling on keyguard");
            } else {
                fling(vel, expand, isFalseTouch(x, y, interactionType));
            }
            onTrackingStopped(expand);
            mUpdateFlingOnLayout = expand && mPanelClosedOnDown && !mHasLayoutedSinceDown;
            if (mUpdateFlingOnLayout) {
                mUpdateFlingVelocity = vel;
            }
        } else if (!mCentralSurfaces.isBouncerShowing()
                && !mAlternateBouncerInteractor.isVisibleState()
                && !mKeyguardStateController.isKeyguardGoingAway()) {
            onEmptySpaceClick(x, y);
            onTrackingStopped(true);
        }
        mVelocityTracker.clear();
    }

    private float getCurrentExpandVelocity() {
        mVelocityTracker.computeCurrentVelocity(1000);
        return mVelocityTracker.getYVelocity();
    }

    private void endClosing() {
        if (isClosing()) {
            setClosing(false);
            onClosingFinished();
        }
    }

    /**
     * @param x the final x-coordinate when the finger was lifted
     * @param y the final y-coordinate when the finger was lifted
     * @return whether this motion should be regarded as a false touch
     */
    private boolean isFalseTouch(float x, float y,
            @Classifier.InteractionType int interactionType) {
        if (mFalsingManager.isClassifierEnabled()) {
            return mFalsingManager.isFalseTouch(interactionType);
        }
        if (!mTouchAboveFalsingThreshold) {
            return true;
        }
        if (mUpwardsWhenThresholdReached) {
            return false;
        }
        return !isDirectionUpwards(x, y);
    }

    private void fling(float vel, boolean expand, boolean expandBecauseOfFalsing) {
        fling(vel, expand, 1.0f /* collapseSpeedUpFactor */, expandBecauseOfFalsing);
    }

    private void fling(float vel, boolean expand, float collapseSpeedUpFactor,
            boolean expandBecauseOfFalsing) {
        float target = expand ? getMaxPanelTransitionDistance() : 0;
        if (!expand) {
            setClosing(true);
        }
        flingToHeight(vel, expand, target, collapseSpeedUpFactor, expandBecauseOfFalsing);
    }

    private void springBack() {
        if (mOverExpansion == 0) {
            onFlingEnd(false /* cancelled */);
            return;
        }
        mIsSpringBackAnimation = true;
        ValueAnimator animator = ValueAnimator.ofFloat(mOverExpansion, 0);
        animator.addUpdateListener(
                animation -> setOverExpansionInternal((float) animation.getAnimatedValue()));
        animator.setDuration(SHADE_OPEN_SPRING_BACK_DURATION);
        animator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                mIsSpringBackAnimation = false;
                onFlingEnd(mCancelled);
            }
        });
        setAnimator(animator);
        animator.start();
    }

    @VisibleForTesting
    void setExpandedHeight(float height) {
        debugLog("setExpandedHeight(%.1f)", height);
        setExpandedHeightInternal(height);
    }

    /** Try to set expanded height to max. */
    void updateExpandedHeightToMaxHeight() {
        float currentMaxPanelHeight = getMaxPanelHeight();

        if (isFullyCollapsed()) {
            return;
        }

        if (currentMaxPanelHeight == mExpandedHeight) {
            return;
        }

        if (isTracking() && !(mBlockingExpansionForCurrentTouch
                || mQsController.isTrackingBlocked())) {
            return;
        }

        if (mHeightAnimator != null && !mIsSpringBackAnimation) {
            mPanelUpdateWhenAnimatorEnds = true;
            return;
        }

        setExpandedHeight(currentMaxPanelHeight);
    }

    private void setExpandedHeightInternal(float h) {
        if (isNaN(h)) {
            Log.wtf(TAG, "ExpandedHeight set to NaN");
        }
        mNotificationShadeWindowController.batchApplyWindowLayoutParams(() -> {
            if (mExpandLatencyTracking && h != 0f) {
                DejankUtils.postAfterTraversal(
                        () -> mLatencyTracker.onActionEnd(LatencyTracker.ACTION_EXPAND_PANEL));
                mExpandLatencyTracking = false;
            }
            float maxPanelHeight = getMaxPanelTransitionDistance();
            mExpandedHeight = Math.min(h, maxPanelHeight);
            // If we are closing the panel and we are almost there due to a slow decelerating
            // interpolator, abort the animation.
            if (mExpandedHeight < 1f && mExpandedHeight != 0f && isClosing()) {
                mExpandedHeight = 0f;
                if (mHeightAnimator != null) {
                    mHeightAnimator.end();
                }
            }
            mExpandedFraction = Math.min(1f,
                    maxPanelHeight == 0 ? 0 : mExpandedHeight / maxPanelHeight);
            if (mExpandedFraction > 0f && mExpectingSynthesizedDown) {
                mExpectingSynthesizedDown = false;
            }
            mShadeRepository.setLegacyShadeExpansion(mExpandedFraction);
            mQsController.setShadeExpansion(mExpandedHeight, mExpandedFraction);
            mExpansionDragDownAmountPx = h;
            if (!SceneContainerFlag.isEnabled()) {
                mAmbientState.setExpansionFraction(mExpandedFraction);
            }
            onHeightUpdated(mExpandedHeight);
            updateExpansionAndVisibility();
        });
    }

    /**
     * Set the current overexpansion
     *
     * @param overExpansion the amount of overexpansion to apply
     */
    private void setOverExpansionInternal(float overExpansion) {
        mLastGesturedOverExpansion = -1;
        setOverExpansion(overExpansion);
    }

    /** Sets the expanded height relative to a number from 0 to 1. */
    @VisibleForTesting
    void setExpandedFraction(float frac) {
        final int maxDist = getMaxPanelTransitionDistance();
        setExpandedHeight(maxDist * frac);
    }

    float getExpandedHeight() {
        return mExpandedHeight;
    }

    float getExpandedFraction() {
        return mExpandedFraction;
    }

    @Override
    public StateFlow<Float> getUdfpsTransitionToFullShadeProgress() {
        return mShadeRepository.getUdfpsTransitionToFullShadeProgress();
    }

    @Override
    public Flow<Float> getLegacyPanelExpansion() {
        return  mShadeRepository.getLegacyShadeExpansion();
    }

    @Override
    public boolean isFullyExpanded() {
        return mExpandedHeight >= getMaxPanelTransitionDistance();
    }

    public boolean isShadeFullyExpanded() {
        if (mBarState == SHADE) {
            return isFullyExpanded();
        } else if (mBarState == SHADE_LOCKED) {
            return true;
        } else {
            // case of swipe from the top of keyguard to expanded QS
            return mQsController.computeExpansionFraction() == 1;
        }
    }

    @Override
    public boolean isFullyCollapsed() {
        return mExpandedFraction <= 0.0f;
    }

    @Override
    public boolean isCollapsing() {
        return isClosing() || isLaunchingActivity();
    }

    public boolean isTracking() {
        return mShadeRepository.getLegacyShadeTracking().getValue();
    }

    @Override
    public boolean canBeCollapsed() {
        return !isFullyCollapsed() && !isTracking() && !isClosing();
    }

    public void instantCollapse() {
        abortAnimations();
        setExpandedFraction(0f);
        if (mExpanding) {
            notifyExpandingFinished();
        }
        if (mInstantExpanding) {
            mInstantExpanding = false;
            updateExpansionAndVisibility();
        }
    }

    private void abortAnimations() {
        cancelHeightAnimator();
        mView.removeCallbacks(mFlingCollapseRunnable);
    }

    private void setAnimator(ValueAnimator animator) {
        // TODO(b/341163515): Should we clean up the old animator?
        registerAnimatorForTest(animator);
        mHeightAnimator = animator;
        if (animator == null && mPanelUpdateWhenAnimatorEnds) {
            mPanelUpdateWhenAnimatorEnds = false;
            updateExpandedHeightToMaxHeight();
        }
    }

    /** Returns whether a shade or QS expansion animation is running */
    private boolean isShadeOrQsHeightAnimationRunning() {
        return mHeightAnimator != null && !mIsSpringBackAnimation;
    }

    /**
     * Create an animator that can also overshoot
     *
     * @param targetHeight    the target height
     * @param overshootAmount the amount of overshoot desired
     */
    private ValueAnimator createHeightAnimator(float targetHeight, float overshootAmount) {
        float startExpansion = mOverExpansion;
        ValueAnimator animator = ValueAnimator.ofFloat(mExpandedHeight, targetHeight);
        registerAnimatorForTest(animator);
        animator.addUpdateListener(
                animation -> {
                    if (overshootAmount > 0.0f
                            // Also remove the overExpansion when collapsing
                            || (targetHeight == 0.0f && startExpansion != 0)) {
                        final float expansion = MathUtils.lerp(
                                startExpansion,
                                mPanelFlingOvershootAmount * overshootAmount,
                                Interpolators.FAST_OUT_SLOW_IN.getInterpolation(
                                        animator.getAnimatedFraction()));
                        setOverExpansionInternal(expansion);
                    }
                    setExpandedHeightInternal((float) animation.getAnimatedValue());
                });
        return animator;
    }

    private void registerAnimatorForTest(Animator animator) {
        if (mTestSetOfAnimatorsUsed != null && animator != null) {
            mTestSetOfAnimatorsUsed.add(animator);
        }
    }

    /** Update the visibility of {@link NotificationPanelView} if necessary. */
    private void updateVisibility() {
        mView.setVisibility(shouldPanelBeVisible() ? VISIBLE : INVISIBLE);
    }


    @Override
    public void updateExpansionAndVisibility() {
        if (!SceneContainerFlag.isEnabled()) {
            mShadeExpansionStateManager.onPanelExpansionChanged(
                    mExpandedFraction, isExpanded(), isTracking());
        }
        updateVisibility();
    }

    @Override
    public boolean isExpanded() {
        return mExpandedFraction > 0f
                || mInstantExpanding
                || isPanelVisibleBecauseOfHeadsUp()
                || isTracking()
                || mHeightAnimator != null
                || isPanelVisibleBecauseScrimIsAnimatingOff()
                && !mIsSpringBackAnimation;
    }

    /** Called when the user performs a click anywhere in the empty area of the panel. */
    private void onEmptySpaceClick(float x, float y) {
        onMiddleClicked(x, y);
    }

    @VisibleForTesting
    boolean isClosing() {
        return mShadeRepository.getLegacyIsClosing().getValue();
    }

    public void collapseWithDuration(int animationDuration) {
        mFixedDuration = animationDuration;
        collapse(false /* delayed */, 1.0f /* speedUpFactor */);
        mFixedDuration = NO_FIXED_DURATION;
    }

    void postToView(Runnable action) {
        mView.post(action);
    }

    /** Sends an external (e.g. Status Bar) intercept touch event to the Shade touch handler. */
    @Override
    public boolean handleExternalInterceptTouch(MotionEvent event) {
        try {
            mUseExternalTouch = true;
            return mTouchHandler.onInterceptTouchEvent(event);
        } finally {
            mUseExternalTouch = false;
        }
    }

    @Override
    public boolean handleExternalTouch(MotionEvent event) {
        try {
            mUseExternalTouch = true;
            return mTouchHandler.onTouchEvent(event);
        } finally {
            mUseExternalTouch = false;
        }
    }

    @Override
    public void updateTouchableRegion() {
        //A layout will ensure that onComputeInternalInsets will be called and after that we can
        // resize the layout. Make sure that the window stays small for one frame until the
        // touchableRegion is set.
        boostFramesDuringRelayout();
        mNotificationShadeWindowController.setForceWindowCollapsed(true);
        postToView(() -> {
            mNotificationShadeWindowController.setForceWindowCollapsed(false);
        });
    }

    @Override
    public boolean isViewEnabled() {
        return mView.isEnabled();
    }

    float getOverStretchAmount() {
        return mOverStretchAmount;
    }

    float getMinFraction() {
        return mMinFraction;
    }

    int getNavigationBarBottomHeight() {
        return mNavigationBarBottomHeight;
    }

    boolean isExpandingFromHeadsUp() {
        return mExpandingFromHeadsUp;
    }

    /**
     * We don't always want to close QS when requested as shade might be in a different state
     * already e.g. when going from collapse to expand very quickly. In that case StatusBar
     * window might send signal to collapse QS but we might be already expanding and in split
     * shade QS are always expanded
     */
    private void closeQsIfPossible() {
        boolean openOrOpening = isShadeFullyExpanded() || isExpandingOrCollapsing();
        if (!(mSplitShadeEnabled && openOrOpening)) {
            mQsController.closeQs();
        }
    }

    @Override
    public void setQsScrimEnabled(boolean qsScrimEnabled) {
        mQsController.setScrimEnabled(qsScrimEnabled);
    }

    private ShadeExpansionStateManager getShadeExpansionStateManager() {
        return mShadeExpansionStateManager;
    }

    void onQsExpansionChanged() {
        updateExpandedHeightToMaxHeight();
        updateSystemUiStateFlags();
        NavigationBarView navigationBarView =
                mNavigationBarController.getNavigationBarView(mDisplayId);
        if (navigationBarView != null) {
            navigationBarView.onStatusBarPanelStateChanged();
        }
    }

    @VisibleForTesting
    void onQsSetExpansionHeightCalled(boolean qsFullyExpanded) {
        requestScrollerTopPaddingUpdate();
        mKeyguardStatusBarViewController.updateViewState();
        int barState = getBarState();
        if (barState == SHADE_LOCKED || barState == KEYGUARD) {
            positionClockAndNotifications();
        }

        if (mAccessibilityManager.isEnabled()) {
            mView.setAccessibilityPaneTitle(determineAccessibilityPaneTitle());
        }

        if (!mFalsingManager.isUnlockingDisabled() && qsFullyExpanded
                && mFalsingCollector.shouldEnforceBouncer()) {
            mActivityStarter.executeRunnableDismissingKeyguard(null, null,
                    false, true, false);
        }
        if (DEBUG_DRAWABLE) {
            mView.invalidate();
        }
    }

    private void onQsClippingImmediatelyApplied(boolean clipStatusView,
            Rect lastQsClipBounds, int top, boolean qsFragmentCreated, boolean qsVisible) {
        if (qsFragmentCreated) {
            mKeyguardInteractor.setQuickSettingsVisible(qsVisible);
        }

        if (mSplitShadeEnabled) {
            mKeyguardStatusBarViewController.setNoTopClipping();
        } else {
            mKeyguardStatusBarViewController.updateTopClipping(top);
        }
    }

    private void onFlingQsWithoutClick(ValueAnimator animator, float qsExpansionHeight,
            float target, float vel) {
        mFlingAnimationUtils.apply(animator, qsExpansionHeight, target, vel);
    }

    private void onExpansionHeightSetToMax(boolean requestPaddingUpdate) {
        if (requestPaddingUpdate) {
            requestScrollerTopPaddingUpdate();
        }
        updateExpandedHeightToMaxHeight();
    }

    private final class NsslHeightChangedListener implements
            ExpandableView.OnHeightChangedListener {
        @Override
        public void onHeightChanged(ExpandableView view, boolean needsAnimation) {
            // Block update if we are in QS and just the top padding changed (i.e. view == null).
            if (view == null && mQsController.getExpanded()) {
                return;
            }
            if (needsAnimation && mInterpolatedDarkAmount == 0) {
                mAnimateNextPositionUpdate = true;
            }
            ExpandableView firstChildNotGone =
                    mNotificationStackScrollLayoutController.getFirstChildNotGone();
            ExpandableNotificationRow
                    firstRow =
                    firstChildNotGone instanceof ExpandableNotificationRow
                            ? (ExpandableNotificationRow) firstChildNotGone : null;
            if (firstRow != null && (view == firstRow || (firstRow.getNotificationParent()
                    == firstRow))) {
                requestScrollerTopPaddingUpdate();
            }
            updateExpandedHeightToMaxHeight();
        }

        @Override
        public void onReset(ExpandableView view) {}
    }

    private void onDynamicPrivacyChanged() {
        // Do not request animation when pulsing or waking up, otherwise the clock will be out
        // of sync with the notification panel.
        if (mLinearDarkAmount != 0) {
            return;
        }
        mAnimateNextPositionUpdate = true;
    }

    private final class ShadeHeadsUpChangedListener implements OnHeadsUpChangedListener {
        @Override
        public void onHeadsUpPinnedModeChanged(final boolean inPinnedMode) {
            if (inPinnedMode) {
                mHeadsUpExistenceChangedRunnable.run();
            } else {
                setHeadsUpAnimatingAway(true);
                mNotificationStackScrollLayoutController.runAfterAnimationFinished(
                        mHeadsUpExistenceChangedRunnable);
            }
            updateGestureExclusionRect();
            mHeadsUpPinnedMode = inPinnedMode;
            updateVisibility();
            mKeyguardStatusBarViewController.updateForHeadsUp();
        }

        @Override
        public void onHeadsUpPinned(NotificationEntry entry) {
            if (!isKeyguardShowing()) {
                mNotificationStackScrollLayoutController.generateHeadsUpAnimation(entry, true);
            }
        }

        @Override
        public void onHeadsUpUnPinned(NotificationEntry entry) {
            // When we're unpinning the notification via active edge they remain heads-upped,
            // we need to make sure that an animation happens in this case, otherwise the
            // notification
            // will stick to the top without any interaction.
            if (isFullyCollapsed() && entry.isRowHeadsUp() && !isKeyguardShowing()) {
                mNotificationStackScrollLayoutController.generateHeadsUpAnimation(entry, false);
                entry.setHeadsUpIsVisible();
            }
        }
    }

    private final class ConfigurationListener implements
            ConfigurationController.ConfigurationListener {
        @Override
        public void onConfigChanged(Configuration newConfig) {
            if (ShadeWindowGoesAround.isEnabled()) {
                updateResources();
            }
        }

        @Override
        public void onThemeChanged() {
            debugLog("onThemeChanged");
            reInflateViews();
        }

        @Override
        public void onDensityOrFontScaleChanged() {
            debugLog("onDensityOrFontScaleChanged");
            reInflateViews();
        }
    }

    private final class StatusBarStateListener implements StateListener {
        @Override
        public void onStateChanged(int statusBarState) {
            onStateChanged(statusBarState, false);
        }

        private void onStateChanged(
                int statusBarState,
                boolean animatingUnlockedShadeToKeyguardBypass
        ) {
            boolean goingToFullShade = mStatusBarStateController.goingToFullShade();
            int oldState = mBarState;
            boolean keyguardShowing = statusBarState == KEYGUARD;

            // TODO: maybe add a listener for barstate
            mBarState = statusBarState;
            mQsController.setBarState(statusBarState);

            boolean fromShadeToKeyguard = statusBarState == KEYGUARD
                    && (oldState == SHADE || oldState == SHADE_LOCKED);
            if (mSplitShadeEnabled && fromShadeToKeyguard) {
                // user can go to keyguard from different shade states and closing animation
                // may not fully run - we always want to make sure we close QS when that happens
                // as we never need QS open in fresh keyguard state
                mQsController.closeQs();
            }

            if (oldState == KEYGUARD && (goingToFullShade
                    || statusBarState == StatusBarState.SHADE_LOCKED)) {

                long startDelay;
                long duration;
                if (mKeyguardStateController.isKeyguardFadingAway()) {
                    startDelay = mKeyguardStateController.getKeyguardFadingAwayDelay();
                    duration = mKeyguardStateController.getShortenedFadingAwayDuration();
                } else {
                    startDelay = 0;
                    duration = StackStateAnimator.ANIMATION_DURATION_STANDARD;
                }
                mKeyguardStatusBarViewController.animateKeyguardStatusBarOut(startDelay, duration);
                mQsController.updateMinHeight();
            } else if (oldState == StatusBarState.SHADE_LOCKED
                    && statusBarState == KEYGUARD) {
                mKeyguardStatusBarViewController.animateKeyguardStatusBarIn();

                mNotificationStackScrollLayoutController.resetScrollPosition();
            } else {
                // this else branch means we are doing one of:
                //  - from KEYGUARD to SHADE (but not fully expanded as when swiping from the top)
                //  - from SHADE to KEYGUARD
                //  - from SHADE_LOCKED to SHADE
                //  - getting notified again about the current SHADE or KEYGUARD state
                final boolean animatingUnlockedShadeToKeyguard = oldState == SHADE
                        && statusBarState == KEYGUARD
                        && mScreenOffAnimationController.isKeyguardShowDelayed()
                        //Bypasses animatingUnlockedShadeToKeyguard for b/337742708
                        && !animatingUnlockedShadeToKeyguardBypass;
                if (!animatingUnlockedShadeToKeyguard) {
                    // Only make the status bar visible if we're not animating the screen off, since
                    // we only want to be showing the clock/notifications during the animation.
                    mShadeLog.logKeyguardStatudBarVisibiliy(keyguardShowing, isOnAod(),
                            animatingUnlockedShadeToKeyguardBypass, oldState, statusBarState);
                    mKeyguardStatusBarViewController.updateViewState(
                            /* alpha= */ 1f,
                            keyguardShowing ? View.VISIBLE : View.INVISIBLE);
                }
                if (keyguardShowing && oldState != mBarState) {
                    mQsController.hideQsImmediately();
                }
            }
            mKeyguardStatusBarViewController.updateForHeadsUp();
            if (keyguardShowing) {
                updateDozingVisibilities(false /* animate */);
            }

            // The update needs to happen after the headerSlide in above, otherwise the translation
            // would reset
            mQsController.updateQsState();
        }

        @Override
        public void onDozeAmountChanged(float linearAmount, float amount) {
            mInterpolatedDarkAmount = amount;
            mLinearDarkAmount = linearAmount;
            positionClockAndNotifications();
        }
    }

    private final ShadeViewStateProvider mShadeViewStateProvider =
            new ShadeViewStateProvider() {
                @Override
                public float getPanelViewExpandedHeight() {
                    return getExpandedHeight();
                }

                @Override
                public boolean shouldHeadsUpBeVisible() {
                    return mHeadsUpAppearanceController != null &&
                            mHeadsUpAppearanceController.shouldHeadsUpStatusBarBeVisible();
                }

                @Override
                public float getLockscreenShadeDragProgress() {
                    return mQsController.getLockscreenShadeDragProgress();
                }
            };

    @Override
    public void showAodUi() {
        setDozing(true /* dozing */, false /* animate */);
        mStatusBarStateController.setUpcomingState(KEYGUARD);
        mStatusBarStateController.setState(KEYGUARD);
        mStatusBarStateListener.onDozeAmountChanged(1f, 1f);
        setExpandedFraction(1f);
    }

    @Override
    public void setOverStretchAmount(float amount) {
        float progress = amount / mView.getHeight();
        float overStretch = Interpolators.getOvershootInterpolation(progress);
        mOverStretchAmount = overStretch * mMaxOverscrollAmountForPulse;
        positionClockAndNotifications(true /* forceUpdate */);
    }

    private final class ShadeAttachStateChangeListener implements View.OnAttachStateChangeListener {
        @Override
        public void onViewAttachedToWindow(View v) {
            mFragmentService.getFragmentHostManager(mView)
                    .addTagListener(QS.TAG, mQsController.getQsFragmentListener());
            if (!SceneContainerFlag.isEnabled()) {
                mStatusBarStateController.addCallback(mStatusBarStateListener);
                // Bypass animatingUnlockedShadeToKeyguard in onStateChanged for b/337742708
                mStatusBarStateListener.onStateChanged(mStatusBarStateController.getState(), true);
            }
            mConfigurationController.addCallback(mConfigurationListener);
            // Theme might have changed between inflating this view and attaching it to the
            // window, so
            // force a call to onThemeChanged
            mConfigurationListener.onThemeChanged();
            mFalsingManager.addTapListener(mFalsingTapListener);
            mKeyguardIndicationController.init();
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            mFragmentService.getFragmentHostManager(mView)
                    .removeTagListener(QS.TAG, mQsController.getQsFragmentListener());
            mStatusBarStateController.removeCallback(mStatusBarStateListener);
            mConfigurationController.removeCallback(mConfigurationListener);
            mFalsingManager.removeTapListener(mFalsingTapListener);
        }
    }

    private final class ShadeLayoutChangeListener implements View.OnLayoutChangeListener {
        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
                int oldTop, int oldRight, int oldBottom) {
            DejankUtils.startDetectingBlockingIpcs("NVP#onLayout");
            updateExpandedHeightToMaxHeight();
            mHasLayoutedSinceDown = true;
            if (mUpdateFlingOnLayout) {
                abortAnimations();
                fling(mUpdateFlingVelocity);
                mUpdateFlingOnLayout = false;
            }
            setIsFullWidth(mNotificationStackScrollLayoutController.getWidth() == mView.getWidth());

            int oldMaxHeight = mQsController.updateHeightsOnShadeLayoutChange();
            positionClockAndNotifications();
            mQsController.handleShadeLayoutChanged(oldMaxHeight);
            updateExpandedHeight(getExpandedHeight());
            updateHeader();

            // If we are running a size change animation, the animation takes care of the height
            // of the container. However, if we are not animating, we always need to make the QS
            // container the desired height so when closing the QS detail, it stays smaller after
            // the size change animation is finished but the detail view is still being animated
            // away (this animation takes longer than the size change animation).
            mQsController.setHeightOverrideToDesiredHeight();

            updateMaxHeadsUpTranslation();
            updateGestureExclusionRect();
            if (mExpandAfterLayoutRunnable != null) {
                mExpandAfterLayoutRunnable.run();
                mExpandAfterLayoutRunnable = null;
            }
            DejankUtils.stopDetectingBlockingIpcs("NVP#onLayout");
        }
    }

    @NonNull
    private WindowInsets onApplyShadeWindowInsets(WindowInsets insets) {
        // the same types of insets that are handled in NotificationShadeWindowView
        int insetTypes = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
        Insets combinedInsets = insets.getInsetsIgnoringVisibility(insetTypes);
        mDisplayTopInset = combinedInsets.top;
        mDisplayRightInset = combinedInsets.right;
        mDisplayLeftInset = combinedInsets.left;
        mQsController.setDisplayInsets(mDisplayLeftInset, mDisplayRightInset);

        mNavigationBarBottomHeight = insets.getStableInsetBottom();
        updateMaxHeadsUpTranslation();
        return insets;
    }

    @Override
    public void cancelPendingCollapse() {
        mView.removeCallbacks(mMaybeHideExpandedRunnable);
    }

    private void onPanelStateChanged(@PanelState int state) {
        mShadeLog.logPanelStateChanged(state);
        mQsController.updateExpansionEnabledAmbient();

        if (state == STATE_OPEN && mCurrentPanelState != state) {
            mQsController.setExpandImmediate(false);
            mView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        }
        if (state == STATE_OPENING) {
            // we need to ignore it on keyguard as this is a false alarm - transition from unlocked
            // to locked will trigger this event and we're not actually in the process of opening
            // the shade, lockscreen is just always expanded
            if (mSplitShadeEnabled && !isKeyguardShowing()) {
                mQsController.setExpandImmediate(true);
            }
            if (mOpenCloseListener != null) {
                mOpenCloseListener.onOpenStarted();
            }
        }
        if (state == STATE_CLOSED) {
            mQsController.setExpandImmediate(false);
            // Close the status bar in the next frame so we can show the end of the animation.
            mView.post(mMaybeHideExpandedRunnable);
        }
        mCurrentPanelState = state;
    }

    private Consumer<Float> setDreamLockscreenTransitionAlpha() {
        return (Float alpha) -> {
            // Also animate the status bar's alpha during transitions between the lockscreen and
            // dreams.
            mKeyguardStatusBarViewController.setAlpha(alpha);
        };
    }

    @VisibleForTesting
    StatusBarStateController getStatusBarStateController() {
        return mStatusBarStateController;
    }

    @VisibleForTesting
    StateListener getStatusBarStateListener() {
        return mStatusBarStateListener;
    }

    /** Handles MotionEvents for the Shade. */
    public final class TouchHandler implements View.OnTouchListener, Gefingerpoken {
        private long mLastTouchDownTime = -1L;

        /**
         * With the shade and lockscreen being separated in the view hierarchy, touch handling now
         * originates with the parent window through {@link #handleExternalTouch}. This allows for
         * parity with the legacy hierarchy while not undertaking a massive refactoring of touch
         * handling.
         *
         * @see NotificationShadeWindowViewController#didNotificationPanelInterceptEvent
         */
        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (!mUseExternalTouch) {
                return false;
            }

            mShadeLog.logMotionEvent(event, "NPVC onInterceptTouchEvent");
            if (mQsController.disallowTouches()) {
                mShadeLog.logMotionEvent(event,
                        "NPVC not intercepting touch, panel touches disallowed");
                return false;
            }
            if (mIsLockscreenDoubleTapEnabled && !mPulsing && !mDozing
                    && mBarState == StatusBarState.KEYGUARD) {
                mDoubleTapToSleepGesture.onTouchEvent(event);
            }
            initDownStates(event);
            // Do not let touches go to shade or QS if the bouncer is visible,
            // but still let user swipe down to expand the panel, dismissing the bouncer.
            if (mCentralSurfaces.isBouncerShowing()) {
                mShadeLog.v("NotificationPanelViewController MotionEvent intercepted: "
                        + "bouncer is showing");
                return true;
            }
            if (mCommandQueue.panelsEnabled()
                    && !mNotificationStackScrollLayoutController.isLongPressInProgress()
                    && mHeadsUpTouchHelper.onInterceptTouchEvent(event)) {
                mMetricsLogger.count(COUNTER_PANEL_OPEN, 1);
                mMetricsLogger.count(COUNTER_PANEL_OPEN_PEEK, 1);
                mShadeLog.v("NotificationPanelViewController MotionEvent intercepted: "
                        + "HeadsUpTouchHelper");
                return true;
            }
            if (!mQsController.shouldQuickSettingsIntercept(mDownX, mDownY, 0)
                    && mPulseExpansionHandler.onInterceptTouchEvent(event)) {
                mShadeLog.v("NotificationPanelViewController MotionEvent intercepted: "
                        + "PulseExpansionHandler");
                return true;
            }

            if (!isFullyCollapsed() && mQsController.onIntercept(event)) {
                debugLog("onQsIntercept true");
                mShadeLog.v("NotificationPanelViewController MotionEvent intercepted: "
                        + "QsIntercept");
                return true;
            }

            if (mInstantExpanding || !mNotificationsDragEnabled || mTouchDisabled) {
                mShadeLog.logNotInterceptingTouchInstantExpanding(mInstantExpanding,
                        !mNotificationsDragEnabled, mTouchDisabled);
                return false;
            }
            if (mMotionAborted && event.getActionMasked() != MotionEvent.ACTION_DOWN) {
                mShadeLog.logMotionEventStatusBarState(event, mStatusBarStateController.getState(),
                        "NPVC MotionEvent not intercepted: non-down action, motion was aborted");
                return false;
            }

            /* If the user drags anywhere inside the panel we intercept it if the movement is
             upwards. This allows closing the shade from anywhere inside the panel.
             We only do this if the current content is scrolled to the bottom, i.e.
             canCollapsePanelOnTouch() is true and therefore there is no conflicting scrolling
             gesture possible. */
            int pointerIndex = event.findPointerIndex(mTrackingPointer);
            if (pointerIndex < 0) {
                pointerIndex = 0;
                mTrackingPointer = event.getPointerId(pointerIndex);
            }
            final float x = event.getX(pointerIndex);
            final float y = event.getY(pointerIndex);
            boolean canCollapsePanel = canCollapsePanelOnTouch();
            final boolean isTrackpadThreeFingerSwipe = isTrackpadThreeFingerSwipe(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mAnimatingOnDown = mHeightAnimator != null && !mIsSpringBackAnimation;
                    mMinExpandHeight = 0.0f;
                    mDownTime = mSystemClock.uptimeMillis();
                    if (mAnimatingOnDown && isClosing()) {
                        cancelHeightAnimator();
                        mTouchSlopExceeded = true;
                        mShadeLog.v("NotificationPanelViewController MotionEvent intercepted:"
                                + " mAnimatingOnDown: true, isClosing(): true");
                        return true;
                    }

                    if (!isTracking() || isFullyCollapsed()) {
                        mInitialExpandY = y;
                        mInitialExpandX = x;
                    } else {
                        mShadeLog.d("not setting mInitialExpandY in onInterceptTouch");
                    }
                    mTouchStartedInEmptyArea = !isInContentBounds(x, y);
                    mTouchSlopExceeded = mTouchSlopExceededBeforeDown;
                    mMotionAborted = false;
                    mPanelClosedOnDown = isFullyCollapsed();
                    mShadeLog.logPanelClosedOnDown("intercept down touch", mPanelClosedOnDown,
                            mExpandedFraction);
                    mCollapsedAndHeadsUpOnDown = false;
                    mHasLayoutedSinceDown = false;
                    mUpdateFlingOnLayout = false;
                    mTouchAboveFalsingThreshold = false;
                    addMovement(event);
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    if (isTrackpadThreeFingerSwipe) {
                        break;
                    }
                    final int upPointer = event.getPointerId(event.getActionIndex());
                    if (mTrackingPointer == upPointer) {
                        // gesture is ongoing, find a new pointer to track
                        final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                        mTrackingPointer = event.getPointerId(newIndex);
                        mInitialExpandX = event.getX(newIndex);
                        mInitialExpandY = event.getY(newIndex);
                    }
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    mShadeLog.logMotionEventStatusBarState(event,
                            mStatusBarStateController.getState(),
                            "onInterceptTouchEvent: pointer down action");
                    if (!isTrackpadThreeFingerSwipe
                            && mStatusBarStateController.getState() == StatusBarState.KEYGUARD) {
                        mMotionAborted = true;
                        mVelocityTracker.clear();
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    final float h = y - mInitialExpandY;
                    addMovement(event);
                    final boolean openShadeWithoutHun =
                            mPanelClosedOnDown && !mCollapsedAndHeadsUpOnDown;
                    if (canCollapsePanel || mTouchStartedInEmptyArea || mAnimatingOnDown
                            || openShadeWithoutHun) {
                        float hAbs = Math.abs(h);
                        float touchSlop = getTouchSlop(event);
                        if ((h < -touchSlop
                                || ((openShadeWithoutHun || mAnimatingOnDown) && hAbs > touchSlop))
                                && hAbs > Math.abs(x - mInitialExpandX)) {
                            cancelHeightAnimator();
                            startExpandMotion(x, y, true /* startTracking */, mExpandedHeight);
                            mShadeLog.v("NotificationPanelViewController MotionEvent"
                                    + " intercepted: startExpandMotion");
                            return true;
                        }
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    mVelocityTracker.clear();
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            return onTouchEvent(event);
        }

        /**
         * With the shade and lockscreen being separated in the view hierarchy, touch handling now
         * originates with the parent window through {@link #handleExternalTouch}. This allows for
         * parity with the legacy hierarchy while not undertaking a massive refactoring of touch
         * handling.
         *
         * @see NotificationShadeWindowViewController#didNotificationPanelInterceptEvent
         */
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!mUseExternalTouch) {
                return false;
            }

            if (mAlternateBouncerInteractor.isVisibleState()) {
                // never send touches to shade if the alternate bouncer is showing
                return false;
            }

            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (event.getDownTime() == mLastTouchDownTime) {
                    // An issue can occur when swiping down after unlock, where multiple down
                    // events are received in this handler with identical downTimes. Until the
                    // source of the issue can be located, detect this case and ignore.
                    // see b/193350347
                    mShadeLog.logMotionEvent(event,
                            "onTouch: duplicate down event detected... ignoring");
                    return true;
                }
                mLastTouchDownTime = event.getDownTime();
            }

            if (mQsController.isFullyExpandedAndTouchesDisallowed()) {
                mShadeLog.logMotionEvent(event,
                        "onTouch: ignore touch, panel touches disallowed and qs fully expanded");
                return false;
            }

            // Do not allow panel expansion if bouncer is scrimmed,
            // otherwise user would be able to pull down QS or expand the shade.
            if (mCentralSurfaces.isBouncerShowingScrimmed()) {
                mShadeLog.logMotionEvent(event,
                        "onTouch: ignore touch, bouncer scrimmed or showing over dream");
                return false;
            }

            // Make sure the next touch won't the blocked after the current ends.
            if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                mBlockingExpansionForCurrentTouch = false;
            }
            // When touch focus transfer happens, ACTION_DOWN->ACTION_UP may happen immediately
            // without any ACTION_MOVE event.
            // In such case, simply expand the panel instead of being stuck at the bottom bar.
            if (mLastEventSynthesizedDown && event.getAction() == MotionEvent.ACTION_UP) {
                expand(true /* animate */);
            }

            if (!isFullyExpanded() && mIsSbDoubleTapEnabled
                    && event.getY() < mStatusBarHeaderHeight) {
                mDoubleTapToSleepGesture.onTouchEvent(event);
            }

            initDownStates(event);

            // If pulse is expanding already, let's give it the touch. There are situations
            // where the panel starts expanding even though we're also pulsing
            boolean pulseShouldGetTouch = (!mIsExpandingOrCollapsing
                    && !mQsController.shouldQuickSettingsIntercept(mDownX, mDownY, 0))
                    || mPulseExpansionHandler.isExpanding();
            if (pulseShouldGetTouch && mPulseExpansionHandler.onTouchEvent(event)) {
                // We're expanding all the other ones shouldn't get this anymore
                mShadeLog.logMotionEvent(event, "onTouch: PulseExpansionHandler handled event");
                return true;
            }
            if (mPulsing) {
                mShadeLog.logMotionEvent(event, "onTouch: eat touch, device pulsing");
                return true;
            }
            if (mListenForHeadsUp && !mHeadsUpTouchHelper.isTrackingHeadsUp()
                    && !mNotificationStackScrollLayoutController.isLongPressInProgress()
                    && mHeadsUpTouchHelper.onInterceptTouchEvent(event)) {
                mMetricsLogger.count(COUNTER_PANEL_OPEN_PEEK, 1);
            }
            boolean handled = mHeadsUpTouchHelper.onTouchEvent(event);

            // This touch session has already resulted in shade expansion. Ignore everything else.
            if (ShadeExpandsOnStatusBarLongPress.isEnabled()
                    && event.getActionMasked() != MotionEvent.ACTION_DOWN
                    && event.getDownTime() == mStatusBarLongPressDowntime) {
                mShadeLog.d("Touch has same down time as Status Bar long press. Ignoring.");
                return false;
            }
            if (!mHeadsUpTouchHelper.isTrackingHeadsUp() && mQsController.handleTouch(
                    event, isFullyCollapsed(), isShadeOrQsHeightAnimationRunning())) {
                if (event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                    mShadeLog.logMotionEvent(event, "onTouch: handleQsTouch handled event");
                }
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && isFullyCollapsed()) {
                mMetricsLogger.count(COUNTER_PANEL_OPEN, 1);
                handled = true;
            }

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && isFullyExpanded()
                    && mKeyguardStateController.isShowing()) {
                mStatusBarKeyguardViewManager.updateKeyguardPosition(event.getX());
            }

            handled |= handleTouch(event);
            return !mDozing || handled;
        }

        private boolean handleTouch(MotionEvent event) {
            if (mInstantExpanding) {
                mShadeLog.logMotionEvent(event,
                        "handleTouch: touch ignored due to instant expanding");
                return false;
            }
            if (mTouchDisabled && event.getActionMasked() != MotionEvent.ACTION_CANCEL) {
                mShadeLog.logMotionEvent(event, "handleTouch: non-cancel action, touch disabled");
                return false;
            }
            if (mMotionAborted && event.getActionMasked() != MotionEvent.ACTION_DOWN) {
                mShadeLog.logMotionEventStatusBarState(event, mStatusBarStateController.getState(),
                        "handleTouch: non-down action, motion was aborted");
                return false;
            }

            // If dragging should not expand the notifications shade, then return false.
            if (!mNotificationsDragEnabled) {
                if (isTracking()) {
                    // Turn off tracking if it's on or the shade can get stuck in the down position.
                    onTrackingStopped(true /* expand */);
                }
                mShadeLog.logMotionEvent(event, "handleTouch: drag not enabled");
                return false;
            }

            /*
             * We capture touch events here and update the expand height here in case according to
             * the users fingers. This also handles multi-touch.
             *
             * Flinging is also enabled in order to open or close the shade.
             */
            int pointerIndex = event.findPointerIndex(mTrackingPointer);
            if (pointerIndex < 0) {
                pointerIndex = 0;
                mTrackingPointer = event.getPointerId(pointerIndex);
            }
            final float x = event.getX(pointerIndex);
            final float y = event.getY(pointerIndex);

            boolean isDown = event.getActionMasked() == MotionEvent.ACTION_DOWN;
            if (isDown
                    || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                mGestureWaitForTouchSlop = shouldGestureWaitForTouchSlop();
                mIgnoreXTouchSlop = true;
            }

            final boolean isTrackpadThreeFingerSwipe = isTrackpadThreeFingerSwipe(event);
            if (com.android.systemui.Flags.disableShadeTrackpadTwoFingerSwipe()
                    && !isTrackpadThreeFingerSwipe && isTwoFingerSwipeTrackpadEvent(event)
                    && !isPanelExpanded()) {
                if (isDown) {
                    mShadeLog.d("ignoring down event for two finger trackpad swipe");
                }
                return false;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (QuickStepContract.ALLOW_BACK_GESTURE_IN_SHADE && mAnimateBack) {
                        // Cache the gesture insets now, so we can quickly query them during
                        // ACTION_MOVE and decide whether to intercept events for back gesture anim.
                        mQsController.updateGestureInsetsCache();
                    }
                    mShadeLog.logMotionEvent(event, "onTouch: down action");
                    startExpandMotion(x, y, false /* startTracking */, mExpandedHeight);
                    mMinExpandHeight = 0.0f;
                    mPanelClosedOnDown = isFullyCollapsed();
                    mShadeLog.logPanelClosedOnDown("handle down touch", mPanelClosedOnDown,
                            mExpandedFraction);
                    mHasLayoutedSinceDown = false;
                    mUpdateFlingOnLayout = false;
                    mMotionAborted = false;
                    mDownTime = mSystemClock.uptimeMillis();
                    mStatusBarLongPressDowntime = -1L;
                    mTouchAboveFalsingThreshold = false;
                    mCollapsedAndHeadsUpOnDown =
                            isFullyCollapsed() && mHeadsUpManager.hasPinnedHeadsUp();
                    addMovement(event);
                    boolean regularHeightAnimationRunning = isShadeOrQsHeightAnimationRunning();
                    if (!mGestureWaitForTouchSlop || regularHeightAnimationRunning) {
                        mTouchSlopExceeded = regularHeightAnimationRunning
                                || mTouchSlopExceededBeforeDown;
                        cancelHeightAnimator();
                        onTrackingStarted();
                    }
                    if (isFullyCollapsed() && !mHeadsUpManager.hasPinnedHeadsUp()
                            && !mCentralSurfaces.isBouncerShowing() && !mIsSbDoubleTapEnabled) {
                        startOpening(event);
                    }
                    break;

                case MotionEvent.ACTION_POINTER_UP:
                    if (isTrackpadThreeFingerSwipe) {
                        break;
                    }
                    final int upPointer = event.getPointerId(event.getActionIndex());
                    if (mTrackingPointer == upPointer) {
                        // gesture is ongoing, find a new pointer to track
                        final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                        final float newY = event.getY(newIndex);
                        final float newX = event.getX(newIndex);
                        mTrackingPointer = event.getPointerId(newIndex);
                        mHandlingPointerUp = true;
                        startExpandMotion(newX, newY, true /* startTracking */, mExpandedHeight);
                        mHandlingPointerUp = false;
                    }
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    mShadeLog.logMotionEventStatusBarState(event,
                            mStatusBarStateController.getState(),
                            "handleTouch: pointer down action");
                    if (!isTrackpadThreeFingerSwipe
                            && mStatusBarStateController.getState() == StatusBarState.KEYGUARD) {
                        mMotionAborted = true;
                        endMotionEvent(event, x, y, true /* forceCancel */);
                        return false;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    // If the shade is half-collapsed, a horizontal swipe inwards from L/R edge
                    // must be routed to the back gesture (which shows a preview animation).
                    if (QuickStepContract.ALLOW_BACK_GESTURE_IN_SHADE && mAnimateBack
                            && mQsController.shouldBackBypassQuickSettings(x)) {
                        return false;
                    }
                    if (isFullyCollapsed()) {
                        // If panel is fully collapsed, reset haptic effect before adding movement.
                        mHasVibratedOnOpen = false;
                        mShadeLog.logHasVibrated(mHasVibratedOnOpen, mExpandedFraction);
                    }
                    addMovement(event);
                    if (!isFullyCollapsed()) {
                        maybeVibrateOnOpening(true /* openingWithTouch */);
                    }
                    float h = y - mInitialExpandY;

                    // If the panel was collapsed when touching, we only need to check for the
                    // y-component of the gesture, as we have no conflicting horizontal gesture.
                    if (Math.abs(h) > getTouchSlop(event)
                            && (Math.abs(h) > Math.abs(x - mInitialExpandX)
                            || mIgnoreXTouchSlop)) {
                        mTouchSlopExceeded = true;
                        if (mGestureWaitForTouchSlop
                                && !isTracking()
                                && !mCollapsedAndHeadsUpOnDown
                                && !isTwoFingerSwipeTrackpadEvent(event)
                        ) {
                            if (mInitialOffsetOnTouch != 0f) {
                                startExpandMotion(x, y, false /* startTracking */, mExpandedHeight);
                                h = 0;
                            }
                            cancelHeightAnimator();
                            onTrackingStarted();
                        }
                    }
                    float newHeight = Math.max(0, h + mInitialOffsetOnTouch);
                    newHeight = Math.max(newHeight, mMinExpandHeight);
                    if (-h >= getFalsingThreshold()) {
                        mTouchAboveFalsingThreshold = true;
                        mUpwardsWhenThresholdReached = isDirectionUpwards(x, y);
                    }
                    if ((!mGestureWaitForTouchSlop || isTracking())
                            && !(mBlockingExpansionForCurrentTouch
                            || mQsController.isTrackingBlocked())) {
                        // Count h==0 as part of swipe-up,
                        // otherwise {@link NotificationStackScrollLayout}
                        // wrongly enables stack height updates at the start of lockscreen swipe-up
                        mAmbientState.setSwipingUp(h <= 0);
                        setExpandedHeightInternal(newHeight);
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mShadeLog.logMotionEvent(event, "onTouch: up/cancel action");
                    addMovement(event);
                    endMotionEvent(event, x, y, false /* forceCancel */);
                    // mHeightAnimator is null, there is no remaining frame, ends instrumenting.
                    if (mHeightAnimator == null) {
                        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                            mQsController.endJankMonitoring();
                        } else {
                            mQsController.cancelJankMonitoring();
                        }
                    }
                    break;
            }
            return !mGestureWaitForTouchSlop || isTracking();
        }
    }

    private static boolean isTwoFingerSwipeTrackpadEvent(MotionEvent event) {
        //SOURCE_MOUSE because SOURCE_TOUCHPAD is reserved for "captured" touchpads
        return event.getSource() == InputDevice.SOURCE_MOUSE
                && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
                && event.getClassification() == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE;
    }

    private final class HeadsUpNotificationViewControllerImpl implements
            HeadsUpTouchHelper.HeadsUpNotificationViewController {
        @Override
        public void setHeadsUpDraggingStartingHeight(int startHeight) {
            NotificationPanelViewController.this.setHeadsUpDraggingStartingHeight(startHeight);
        }

        @Override
        public void setTrackedHeadsUp(ExpandableNotificationRow pickedChild) {
            if (pickedChild != null) {
                mShadeHeadsUpTracker.updateTrackingHeadsUp(pickedChild);
                mExpandingFromHeadsUp = true;
            }
            // otherwise we update the state when the expansion is finished
        }

        @Override
        public void startExpand(float x, float y, boolean startTracking, float expandedHeight) {
            startExpandMotion(x, y, startTracking, expandedHeight);
        }
    }

    private final class ShadeAccessibilityDelegate extends AccessibilityDelegate {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host,
                AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP);
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (action
                    == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.getId()
                    || action
                    == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()) {
                mStatusBarKeyguardViewManager.showPrimaryBouncer(true,
                        "NotificationPanelViewController#performAccessibilityAction");
                return true;
            }
            return super.performAccessibilityAction(host, action, args);
        }
    }
}
