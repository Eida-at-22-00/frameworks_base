/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.EXPANSION_HIDDEN;
import static com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.EXPANSION_VISIBLE;

import static kotlinx.coroutines.flow.StateFlowKt.MutableStateFlow;
import static kotlinx.coroutines.test.TestCoroutineDispatchersKt.StandardTestDispatcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.window.WindowOnBackInvokedDispatcher;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardMessageArea;
import com.android.keyguard.KeyguardMessageAreaController;
import com.android.keyguard.KeyguardSecurityModel;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.bouncer.domain.interactor.BouncerInteractor;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerCallbackInteractor;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerCallbackInteractor.PrimaryBouncerExpansionCallback;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor;
import com.android.systemui.bouncer.ui.BouncerView;
import com.android.systemui.bouncer.ui.BouncerViewDelegate;
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.flags.DisableSceneContainer;
import com.android.systemui.flags.EnableSceneContainer;
import com.android.systemui.keyguard.DismissCallbackRegistry;
import com.android.systemui.keyguard.domain.interactor.KeyguardDismissActionInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardDismissTransitionInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.shared.model.KeyguardState;
import com.android.systemui.keyguard.shared.model.TransitionState;
import com.android.systemui.keyguard.shared.model.TransitionStep;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.navigationbar.TaskbarDelegate;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.ActivityStarter.OnDismissAction;
import com.android.systemui.scene.domain.interactor.SceneInteractor;
import com.android.systemui.scene.shared.model.Overlays;
import com.android.systemui.shade.NotificationShadeWindowView;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.ShadeExpansionChangeEvent;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.shade.domain.interactor.ShadeLockscreenInteractor;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.domain.interactor.StatusBarKeyguardViewManagerInteractor;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.unfold.SysUIUnfoldComponent;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.util.time.FakeSystemClock;

import com.google.common.truth.Truth;

import kotlin.Unit;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class StatusBarKeyguardViewManagerTest extends SysuiTestCase {

    private static final ShadeExpansionChangeEvent EXPANSION_EVENT =
            expansionEvent(/* fraction= */ 0.5f, /* expanded= */ false, /* tracking= */ true);

    @Mock private ViewMediatorCallback mViewMediatorCallback;
    @Mock private LockPatternUtils mLockPatternUtils;
    @Mock private CentralSurfaces mCentralSurfaces;
    @Mock private ViewGroup mContainer;
    @Mock private ShadeLockscreenInteractor mShadeLockscreenInteractor;
    @Mock private BiometricUnlockController mBiometricUnlockController;
    @Mock private SysuiStatusBarStateController mStatusBarStateController;
    @Mock private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock private View mNotificationContainer;
    @Mock private KeyguardMessageAreaController.Factory mKeyguardMessageAreaFactory;
    @Mock private KeyguardMessageAreaController mKeyguardMessageAreaController;
    @Mock private KeyguardMessageArea mKeyguardMessageArea;
    @Mock private ShadeController mShadeController;
    @Mock private SysUIUnfoldComponent mSysUiUnfoldComponent;
    @Mock private DreamOverlayStateController mDreamOverlayStateController;
    @Mock private LatencyTracker mLatencyTracker;
    @Mock private KeyguardSecurityModel mKeyguardSecurityModel;
    @Mock private PrimaryBouncerCallbackInteractor mPrimaryBouncerCallbackInteractor;
    @Mock private PrimaryBouncerInteractor mPrimaryBouncerInteractor;
    @Mock private AlternateBouncerInteractor mAlternateBouncerInteractor;
    @Mock private ActivityStarter mActivityStarter;
    @Mock private BouncerView mBouncerView;
    @Mock private BouncerViewDelegate mBouncerViewDelegate;
    @Mock private OnBackAnimationCallback mBouncerViewDelegateBackCallback;
    @Mock private NotificationShadeWindowController mNotificationShadeWindowController;
    @Mock private NotificationShadeWindowView mNotificationShadeWindowView;
    @Mock private WindowInsetsController mWindowInsetsController;
    @Mock private TaskbarDelegate mTaskbarDelegate;
    @Mock private SelectedUserInteractor mSelectedUserInteractor;
    @Mock private DeviceEntryInteractor mDeviceEntryInteractor;
    @Mock private SceneInteractor mSceneInteractor;
    @Mock private DismissCallbackRegistry mDismissCallbackRegistry;
    @Mock private BouncerInteractor mBouncerInteractor;
    @Mock private CommunalSceneInteractor mCommunalSceneInteractor;

    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private PrimaryBouncerCallbackInteractor.PrimaryBouncerExpansionCallback
            mBouncerExpansionCallback;
    private FakeKeyguardStateController mKeyguardStateController =
            spy(new FakeKeyguardStateController());
    private final FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());
    private final static String TEST_REASON = "reason";

    @Mock
    private ViewRootImpl mViewRootImpl;
    @Mock
    private WindowOnBackInvokedDispatcher mOnBackInvokedDispatcher;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private KeyguardTransitionInteractor mKeyguardTransitionInteractor;
    @Captor
    private ArgumentCaptor<OnBackInvokedCallback> mBackCallbackCaptor;
    @Mock
    private KeyguardDismissActionInteractor mKeyguardDismissActionInteractor;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    @DisableFlags(com.android.systemui.Flags.FLAG_KEYGUARD_WM_STATE_REFACTOR)
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContainer.findViewById(anyInt())).thenReturn(mKeyguardMessageArea);
        when(mKeyguardMessageAreaFactory.create(any()))
                .thenReturn(mKeyguardMessageAreaController);
        when(mBouncerView.getDelegate()).thenReturn(mBouncerViewDelegate);
        when(mBouncerViewDelegate.getBackCallback()).thenReturn(mBouncerViewDelegateBackCallback);

        when(mNotificationShadeWindowController.getWindowRootView())
                .thenReturn(mNotificationShadeWindowView);
        when(mNotificationShadeWindowView.getWindowInsetsController())
                .thenReturn(mWindowInsetsController);
        when(mCommunalSceneInteractor.isIdleOnCommunal()).thenReturn(MutableStateFlow(false));

        mStatusBarKeyguardViewManager =
                new StatusBarKeyguardViewManager(
                        getContext(),
                        mViewMediatorCallback,
                        mLockPatternUtils,
                        mStatusBarStateController,
                        mock(ConfigurationController.class),
                        mKeyguardUpdateMonitor,
                        mDreamOverlayStateController,
                        mock(NavigationModeController.class),
                        mock(DockManager.class),
                        mNotificationShadeWindowController,
                        mKeyguardStateController,
                        Optional.of(mSysUiUnfoldComponent),
                        () -> mShadeController,
                        mLatencyTracker,
                        mKeyguardSecurityModel,
                        mPrimaryBouncerCallbackInteractor,
                        mPrimaryBouncerInteractor,
                        mBouncerView,
                        mAlternateBouncerInteractor,
                        mActivityStarter,
                        mKeyguardTransitionInteractor,
                        mock(KeyguardDismissTransitionInteractor.class),
                        StandardTestDispatcher(null, null),
                        () -> mKeyguardDismissActionInteractor,
                        mSelectedUserInteractor,
                        mock(JavaAdapter.class),
                        () -> mSceneInteractor,
                        mock(StatusBarKeyguardViewManagerInteractor.class),
                        mExecutor,
                        () -> mDeviceEntryInteractor,
                        mDismissCallbackRegistry,
                        () -> mBouncerInteractor,
                        mCommunalSceneInteractor) {
                    @Override
                    public ViewRootImpl getViewRootImpl() {
                        return mViewRootImpl;
                    }
                };
        when(mViewRootImpl.getOnBackInvokedDispatcher())
                .thenReturn(mOnBackInvokedDispatcher);
        mStatusBarKeyguardViewManager.registerCentralSurfaces(
                mCentralSurfaces,
                mShadeLockscreenInteractor,
                new ShadeExpansionStateManager(),
                mBiometricUnlockController,
                mNotificationContainer);
        mStatusBarKeyguardViewManager.show(null);
        ArgumentCaptor<PrimaryBouncerExpansionCallback> callbackArgumentCaptor =
                ArgumentCaptor.forClass(PrimaryBouncerExpansionCallback.class);
        verify(mPrimaryBouncerCallbackInteractor).addBouncerExpansionCallback(
                callbackArgumentCaptor.capture());
        mBouncerExpansionCallback = callbackArgumentCaptor.getValue();
    }

    @Test
    @DisableSceneContainer
    public void dismissWithAction_AfterKeyguardGoneSetToFalse() {
        OnDismissAction action = () -> false;
        Runnable cancelAction = () -> {
        };
        mStatusBarKeyguardViewManager.dismissWithAction(
                action, cancelAction, false /* afterKeyguardGone */);
        verify(mPrimaryBouncerInteractor).setDismissAction(eq(action), eq(cancelAction));
        verify(mPrimaryBouncerInteractor).show(eq(true),
                eq("StatusBarKeyguardViewManager#dismissWithAction"));
    }

    @Test
    public void showPrimaryBouncer_onlyWhenShowing() {
        mStatusBarKeyguardViewManager.hide(0 /* startTime */, 0 /* fadeoutDuration */);
        mStatusBarKeyguardViewManager.showPrimaryBouncer(true /* scrimmed */, TEST_REASON);
        verify(mPrimaryBouncerInteractor, never()).show(anyBoolean(), eq(TEST_REASON));
        verify(mDeviceEntryInteractor, never()).attemptDeviceEntry();
        verify(mSceneInteractor, never()).changeScene(any(), any());
    }

    @Test
    public void showPrimaryBouncer_notWhenBouncerAlreadyShowing() {
        mStatusBarKeyguardViewManager.hide(0 /* startTime */, 0 /* fadeoutDuration */);
        when(mKeyguardSecurityModel.getSecurityMode(anyInt())).thenReturn(
                KeyguardSecurityModel.SecurityMode.Password);
        mStatusBarKeyguardViewManager.showPrimaryBouncer(true /* scrimmed */, TEST_REASON);
        verify(mPrimaryBouncerInteractor, never()).show(anyBoolean(), eq(TEST_REASON));
        verify(mDeviceEntryInteractor, never()).attemptDeviceEntry();
        verify(mSceneInteractor, never()).changeScene(any(), any());
    }

    @Test
    @DisableSceneContainer
    public void showBouncer_showsTheBouncer() {
        mStatusBarKeyguardViewManager.showPrimaryBouncer(true /* scrimmed */, TEST_REASON);
        verify(mPrimaryBouncerInteractor).show(eq(true), eq(TEST_REASON));
    }

    @Test
    public void onPanelExpansionChanged_neverShowsDuringHintAnimation() {
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncerInteractor, never()).setPanelExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_propagatesToBouncerOnlyIfShowing() {
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncerInteractor, never()).setPanelExpansion(eq(0.5f));

        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(/* fraction= */ 0.6f, /* expanded= */ false, /* tracking= */ true));
        verify(mPrimaryBouncerInteractor).setPanelExpansion(eq(0.6f));
    }

    @Test
    public void onPanelExpansionChanged_duplicateEventsAreIgnored() {
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncerInteractor).setPanelExpansion(eq(0.5f));

        reset(mPrimaryBouncerInteractor);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncerInteractor, never()).setPanelExpansion(eq(0.5f));
    }

    @Test
    public void onPanelExpansionChanged_hideBouncer_afterKeyguardHidden() {
        mStatusBarKeyguardViewManager.hide(0, 0);
        when(mPrimaryBouncerInteractor.isInTransit()).thenReturn(true);

        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncerInteractor).setPanelExpansion(eq(EXPANSION_HIDDEN));
    }

    @Test
    @DisableSceneContainer
    public void onPanelExpansionChanged_showsBouncerWhenSwiping() {
        mKeyguardStateController.setCanDismissLockScreen(false);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncerInteractor).show(eq(false),
                eq("StatusBarKeyguardViewManager#onPanelExpansionChanged"));

        // But not when it's already visible
        reset(mPrimaryBouncerInteractor);
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncerInteractor, never()).show(eq(false), eq(TEST_REASON));

        // Or animating away
        reset(mPrimaryBouncerInteractor);
        when(mPrimaryBouncerInteractor.isAnimatingAway()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(EXPANSION_EVENT);
        verify(mPrimaryBouncerInteractor, never()).show(eq(false), eq(TEST_REASON));
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenWakeAndUnlock() {
        when(mBiometricUnlockController.getMode())
                .thenReturn(BiometricUnlockController.MODE_WAKE_AND_UNLOCK);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(
                        /* fraction= */ EXPANSION_VISIBLE,
                        /* expanded= */ true,
                        /* tracking= */ false));
        verify(mPrimaryBouncerInteractor, never()).setPanelExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenDismissBouncer() {
        // Since KeyguardBouncer.EXPANSION_VISIBLE = 0 panel expansion, if the unlock is dismissing
        // the bouncer, there may be an onPanelExpansionChanged(0) call to collapse the panel
        // which would mistakenly cause the bouncer to show briefly before its visibility
        // is set to hide. Therefore, we don't want to propagate panelExpansionChanged to the
        // bouncer if the bouncer is dismissing as a result of a biometric unlock.
        when(mBiometricUnlockController.getMode())
                .thenReturn(BiometricUnlockController.MODE_DISMISS_BOUNCER);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(
                        /* fraction= */ EXPANSION_VISIBLE,
                        /* expanded= */ true,
                        /* tracking= */ false));
        verify(mPrimaryBouncerInteractor, never()).setPanelExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenOccluded() {
        when(mKeyguardStateController.isOccluded()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(
                        /* fraction= */ EXPANSION_VISIBLE,
                        /* expanded= */ true,
                        /* tracking= */ false));
        verify(mPrimaryBouncerInteractor, never()).setPanelExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenGoingAway() {
        when(mKeyguardStateController.isKeyguardGoingAway()).thenReturn(true);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(
                        /* fraction= */ EXPANSION_VISIBLE,
                        /* expanded= */ true,
                        /* tracking= */ false));
        verify(mPrimaryBouncerInteractor, never()).setPanelExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenShowBouncer() {
        // Since KeyguardBouncer.EXPANSION_VISIBLE = 0 panel expansion, if the unlock is dismissing
        // the bouncer, there may be an onPanelExpansionChanged(0) call to collapse the panel
        // which would mistakenly cause the bouncer to show briefly before its visibility
        // is set to hide. Therefore, we don't want to propagate panelExpansionChanged to the
        // bouncer if the bouncer is dismissing as a result of a biometric unlock.
        when(mBiometricUnlockController.getMode())
                .thenReturn(BiometricUnlockController.MODE_SHOW_BOUNCER);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(
                        /* fraction= */ EXPANSION_VISIBLE,
                        /* expanded= */ true,
                        /* tracking= */ false));
        verify(mPrimaryBouncerInteractor, never()).setPanelExpansion(anyFloat());
    }

    @Test
    public void onPanelExpansionChanged_neverTranslatesBouncerWhenShadeLocked() {
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE_LOCKED);
        mStatusBarKeyguardViewManager.onPanelExpansionChanged(
                expansionEvent(
                        /* fraction= */ EXPANSION_VISIBLE,
                        /* expanded= */ true,
                        /* tracking= */ false));
        verify(mPrimaryBouncerInteractor, never()).setPanelExpansion(anyFloat());
    }

    @Test
    public void setOccluded_onKeyguardOccludedChangedCalled() {
        clearInvocations(mKeyguardStateController);
        clearInvocations(mKeyguardUpdateMonitor);

        mStatusBarKeyguardViewManager.setOccluded(false /* occluded */, false /* animated */);
        verify(mKeyguardStateController).notifyKeyguardState(true, false);

        clearInvocations(mKeyguardUpdateMonitor);
        clearInvocations(mKeyguardStateController);

        mStatusBarKeyguardViewManager.setOccluded(true /* occluded */, false /* animated */);
        verify(mKeyguardStateController).notifyKeyguardState(true, true);

        clearInvocations(mKeyguardUpdateMonitor);
        clearInvocations(mKeyguardStateController);

        mStatusBarKeyguardViewManager.setOccluded(false /* occluded */, false /* animated */);
        verify(mKeyguardStateController).notifyKeyguardState(true, false);
    }

    @Test
    public void setOccluded_isInLaunchTransition_onKeyguardOccludedChangedCalled() {
        mStatusBarKeyguardViewManager.show(null);

        mStatusBarKeyguardViewManager.setOccluded(true /* occluded */, false /* animated */);
        verify(mKeyguardStateController).notifyKeyguardState(true, true);
    }

    @Test
    public void setOccluded_isLaunchingActivityOverLockscreen_onKeyguardOccludedChangedCalled() {
        when(mCentralSurfaces.isLaunchingActivityOverLockscreen()).thenReturn(true);
        mStatusBarKeyguardViewManager.show(null);

        mStatusBarKeyguardViewManager.setOccluded(true /* occluded */, false /* animated */);
        verify(mKeyguardStateController).notifyKeyguardState(true, true);
    }

    @Test
    @DisableSceneContainer
    public void testHiding_cancelsGoneRunnable() {
        OnDismissAction action = mock(OnDismissAction.class);
        Runnable cancelAction = mock(Runnable.class);
        mStatusBarKeyguardViewManager.dismissWithAction(
                action, cancelAction, true /* afterKeyguardGone */);

        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(false);
        mStatusBarKeyguardViewManager.hideBouncer(true);
        mStatusBarKeyguardViewManager.hide(0, 30);
        verify(action, never()).onDismiss();
        verify(cancelAction).run();
    }

    @Test
    @DisableSceneContainer
    public void testHidingBouncer_cancelsGoneRunnable() {
        OnDismissAction action = mock(OnDismissAction.class);
        Runnable cancelAction = mock(Runnable.class);
        mStatusBarKeyguardViewManager.dismissWithAction(
                action, cancelAction, true /* afterKeyguardGone */);

        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(false);
        mStatusBarKeyguardViewManager.hideBouncer(true);

        verify(action, never()).onDismiss();
        verify(cancelAction).run();
    }

    @Test
    @DisableSceneContainer
    public void testHiding_doesntCancelWhenShowing() {
        OnDismissAction action = mock(OnDismissAction.class);
        Runnable cancelAction = mock(Runnable.class);
        mStatusBarKeyguardViewManager.dismissWithAction(
                action, cancelAction, true /* afterKeyguardGone */);

        mStatusBarKeyguardViewManager.hide(0, 30);
        verify(action).onDismiss();
        verify(cancelAction, never()).run();
    }

    @Test
    public void testShowing_whenAlternateAuthShowing() {
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(false);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);
        assertTrue(
                "Is showing not accurate when alternative bouncer is visible",
                mStatusBarKeyguardViewManager.isBouncerShowing());
    }

    @Test
    public void testWillBeShowing_whenAlternateAuthShowing() {
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(false);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);
        assertTrue(
                "Is or will be showing not accurate when alternate bouncer is visible",
                mStatusBarKeyguardViewManager.primaryBouncerIsOrWillBeShowing());
    }

    @Test
    public void testHideAlternateBouncer_onShowPrimaryBouncer() {
        reset(mAlternateBouncerInteractor);

        // GIVEN alt bouncer is showing
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(false);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);

        // WHEN showBouncer is called
        mStatusBarKeyguardViewManager.showPrimaryBouncer(true, TEST_REASON);

        // THEN alt bouncer should be hidden
        verify(mAlternateBouncerInteractor).hide();
    }

    @Test
    public void testBouncerIsOrWillBeShowing_whenBouncerIsInTransit() {
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(false);
        when(mPrimaryBouncerInteractor.isInTransit()).thenReturn(true);

        assertTrue(
                "Is or will be showing should be true when bouncer is in transit",
                mStatusBarKeyguardViewManager.primaryBouncerIsOrWillBeShowing());
    }

    @Test
    @DisableSceneContainer
    public void testShowAltAuth_unlockingWithBiometricNotAllowed() {
        // GIVEN cannot use alternate bouncer
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(false);
        when(mAlternateBouncerInteractor.canShowAlternateBouncerForFingerprint()).thenReturn(false);

        // WHEN showGenericBouncer is called
        final boolean scrimmed = true;
        mStatusBarKeyguardViewManager.showBouncer(scrimmed, TEST_REASON);

        // THEN regular bouncer is shown
        verify(mPrimaryBouncerInteractor).show(eq(scrimmed), eq(TEST_REASON));
    }

    @Test
    public void testUpdateResources_delegatesToBouncer() {
        mStatusBarKeyguardViewManager.updateResources();

        verify(mPrimaryBouncerInteractor).updateResources();
    }

    @Test
    public void updateKeyguardPosition_delegatesToBouncer() {
        mStatusBarKeyguardViewManager.updateKeyguardPosition(1.0f);

        verify(mPrimaryBouncerInteractor).setKeyguardPosition(1.0f);
    }

    @Test
    public void testIsBouncerInTransit() {
        when(mPrimaryBouncerInteractor.isInTransit()).thenReturn(true);
        Truth.assertThat(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit()).isTrue();
        when(mPrimaryBouncerInteractor.isInTransit()).thenReturn(false);
        Truth.assertThat(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit()).isFalse();
    }

    private static ShadeExpansionChangeEvent expansionEvent(
            float fraction, boolean expanded, boolean tracking) {
        return new ShadeExpansionChangeEvent(fraction, expanded, tracking);
    }

    @Test
    public void testPredictiveBackCallback_registration() {
        /* verify that a predictive back callback is registered when the bouncer becomes visible */
        mBouncerExpansionCallback.onVisibilityChanged(true);
        verify(mOnBackInvokedDispatcher).registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_DEFAULT),
                mBackCallbackCaptor.capture());

        /* verify that the same callback is unregistered when the bouncer becomes invisible */
        mBouncerExpansionCallback.onVisibilityChanged(false);
        verify(mOnBackInvokedDispatcher).unregisterOnBackInvokedCallback(
                eq(mBackCallbackCaptor.getValue()));
    }

    @Test
    public void testPredictiveBackCallback_invocationHidesBouncer() {
        mBouncerExpansionCallback.onVisibilityChanged(true);
        /* capture the predictive back callback during registration */
        verify(mOnBackInvokedDispatcher).registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_DEFAULT),
                mBackCallbackCaptor.capture());

        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(true);
        when(mCentralSurfaces.shouldKeyguardHideImmediately()).thenReturn(true);
        /* invoke the back callback directly */
        mBackCallbackCaptor.getValue().onBackInvoked();

        /* verify that the bouncer will be hidden as a result of the invocation */
        verify(mCentralSurfaces).setBouncerShowing(eq(false));
    }

    @Test
    public void testPredictiveBackCallback_noBackAnimationForFullScreenBouncer() {
        when(mKeyguardSecurityModel.getSecurityMode(anyInt()))
                .thenReturn(KeyguardSecurityModel.SecurityMode.SimPin);
        mBouncerExpansionCallback.onVisibilityChanged(true);
        /* capture the predictive back callback during registration */
        verify(mOnBackInvokedDispatcher).registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_DEFAULT),
                mBackCallbackCaptor.capture());
        assertTrue(mBackCallbackCaptor.getValue() instanceof OnBackAnimationCallback);

        OnBackAnimationCallback backCallback =
                (OnBackAnimationCallback) mBackCallbackCaptor.getValue();

        BackEvent event = new BackEvent(0, 0, 0, BackEvent.EDGE_LEFT);
        backCallback.onBackStarted(event);
        verify(mBouncerViewDelegateBackCallback, never()).onBackStarted(any());
    }

    @Test
    public void testPredictiveBackCallback_forwardsBackDispatches() {
        mBouncerExpansionCallback.onVisibilityChanged(true);
        /* capture the predictive back callback during registration */
        verify(mOnBackInvokedDispatcher).registerOnBackInvokedCallback(
                eq(OnBackInvokedDispatcher.PRIORITY_DEFAULT),
                mBackCallbackCaptor.capture());
        assertTrue(mBackCallbackCaptor.getValue() instanceof OnBackAnimationCallback);

        OnBackAnimationCallback backCallback =
                (OnBackAnimationCallback) mBackCallbackCaptor.getValue();

        BackEvent event = new BackEvent(0, 0, 0, BackEvent.EDGE_LEFT);
        backCallback.onBackStarted(event);
        verify(mBouncerViewDelegateBackCallback).onBackStarted(eq(event));

        backCallback.onBackProgressed(event);
        verify(mBouncerViewDelegateBackCallback).onBackProgressed(eq(event));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(backCallback::onBackInvoked);
        verify(mBouncerViewDelegateBackCallback, timeout(1000)).onBackInvoked();

        backCallback.onBackCancelled();
        verify(mBouncerViewDelegateBackCallback).onBackCancelled();
    }

    @Test
    public void testReportBouncerOnDreamWhenVisible() {
        mBouncerExpansionCallback.onVisibilityChanged(true);
        assertFalse(mStatusBarKeyguardViewManager.isBouncerShowingOverDream());
        Mockito.clearInvocations(mCentralSurfaces);
        when(mDreamOverlayStateController.isOverlayActive()).thenReturn(true);
        mBouncerExpansionCallback.onVisibilityChanged(true);
        assertTrue(mStatusBarKeyguardViewManager.isBouncerShowingOverDream());
    }

    @Test
    public void testReportBouncerOnDreamWhenNotVisible() {
        mBouncerExpansionCallback.onVisibilityChanged(false);
        assertFalse(mStatusBarKeyguardViewManager.isBouncerShowingOverDream());
        Mockito.clearInvocations(mCentralSurfaces);
        when(mDreamOverlayStateController.isOverlayActive()).thenReturn(true);
        mBouncerExpansionCallback.onVisibilityChanged(false);
        assertFalse(mStatusBarKeyguardViewManager.isBouncerShowingOverDream());
    }

    @Test
    public void testHideTaskbar() {
        when(mTaskbarDelegate.isInitialized()).thenReturn(true);
        mStatusBarKeyguardViewManager.setTaskbarDelegate(mTaskbarDelegate);
        mStatusBarKeyguardViewManager.updateNavigationBarVisibility(false);
        verify(mWindowInsetsController).hide(WindowInsets.Type.navigationBars());
    }

    @Test
    public void hideAlternateBouncer_beforeCentralSurfacesRegistered() {
        mStatusBarKeyguardViewManager =
                new StatusBarKeyguardViewManager(
                        getContext(),
                        mViewMediatorCallback,
                        mLockPatternUtils,
                        mStatusBarStateController,
                        mock(ConfigurationController.class),
                        mKeyguardUpdateMonitor,
                        mDreamOverlayStateController,
                        mock(NavigationModeController.class),
                        mock(DockManager.class),
                        mock(NotificationShadeWindowController.class),
                        mKeyguardStateController,
                        Optional.of(mSysUiUnfoldComponent),
                        () -> mShadeController,
                        mLatencyTracker,
                        mKeyguardSecurityModel,
                        mPrimaryBouncerCallbackInteractor,
                        mPrimaryBouncerInteractor,
                        mBouncerView,
                        mAlternateBouncerInteractor,
                        mActivityStarter,
                        mock(KeyguardTransitionInteractor.class),
                        mock(KeyguardDismissTransitionInteractor.class),
                        StandardTestDispatcher(null, null),
                        () -> mock(KeyguardDismissActionInteractor.class),
                        mSelectedUserInteractor,
                        mock(JavaAdapter.class),
                        () -> mSceneInteractor,
                        mock(StatusBarKeyguardViewManagerInteractor.class),
                        mExecutor,
                        () -> mDeviceEntryInteractor,
                        mDismissCallbackRegistry,
                        () -> mBouncerInteractor,
                        mCommunalSceneInteractor) {
                    @Override
                    public ViewRootImpl getViewRootImpl() {
                        return mViewRootImpl;
                    }
                };

        // the following call before registering centralSurfaces should NOT throw a NPE:
        mStatusBarKeyguardViewManager.hideAlternateBouncer(true);
    }

    @Test
    @DisableSceneContainer
    public void testResetDoesNotHideBouncerWhenNotShowing() {
        reset(mDismissCallbackRegistry);
        reset(mPrimaryBouncerInteractor);

        // GIVEN the keyguard is showing
        reset(mAlternateBouncerInteractor);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(false);

        // WHEN SBKV is reset with hideBouncerWhenShowing=true
        mStatusBarKeyguardViewManager.reset(true);

        // THEN no calls to hide should be made
        verify(mAlternateBouncerInteractor, never()).hide();
        verify(mDismissCallbackRegistry, never()).notifyDismissCancelled();
        verify(mPrimaryBouncerInteractor, never()).setDismissAction(eq(null), eq(null));
    }

    @Test
    @DisableSceneContainer
    public void testResetHideBouncerWhenShowing_alternateBouncerHides() {
        reset(mDismissCallbackRegistry);
        reset(mPrimaryBouncerInteractor);

        // GIVEN the keyguard is showing
        reset(mAlternateBouncerInteractor);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(true);

        // WHEN SBKV is reset with hideBouncerWhenShowing=true
        mStatusBarKeyguardViewManager.reset(true);

        // THEN alternate bouncer is hidden and dismiss actions reset
        verify(mAlternateBouncerInteractor).hide();
        verify(mDismissCallbackRegistry).notifyDismissCancelled();
        verify(mPrimaryBouncerInteractor).setDismissAction(eq(null), eq(null));
    }

    @Test
    public void onBackPressedResetsLeaveOnKeyguardHide() {
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(true);
        mStatusBarKeyguardViewManager.onBackPressed();
        verify(mStatusBarStateController).setLeaveOpenOnKeyguardHide(false);
    }

    @Test
    public void testResetHideBouncerWhenShowingIsFalse_alternateBouncerHides() {
        // GIVEN the keyguard is showing
        reset(mAlternateBouncerInteractor);
        when(mKeyguardStateController.isShowing()).thenReturn(true);

        // WHEN SBKV is reset with hideBouncerWhenShowing=false
        mStatusBarKeyguardViewManager.reset(false);

        // THEN alternate bouncer is NOT hidden
        verify(mAlternateBouncerInteractor, never()).hide();
    }

    @Test
    public void testResetBouncerAnimatingAway() {
        reset(mPrimaryBouncerInteractor);
        when(mPrimaryBouncerInteractor.isAnimatingAway()).thenReturn(true);

        mStatusBarKeyguardViewManager.reset(true);

        verify(mPrimaryBouncerInteractor, never()).hide();
    }

    @Test
    public void alternateBouncerToShowPrimaryBouncer_updatesScrimControllerOnce() {
        // GIVEN the alternate bouncer has shown and calls to hide()  will result in successfully
        // hiding it
        when(mAlternateBouncerInteractor.hide()).thenReturn(true);
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(false);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(false);

        // WHEN request to show primary bouncer
        mStatusBarKeyguardViewManager.showPrimaryBouncer(true, TEST_REASON);

        // THEN the scrim isn't updated from StatusBarKeyguardViewManager
        verify(mCentralSurfaces, never()).updateScrimController();
    }

    @Test
    @DisableSceneContainer
    @DisableFlags(Flags.FLAG_SIM_PIN_RACE_CONDITION_ON_RESTART)
    public void testShowBouncerOrKeyguard_needsFullScreen() {
        when(mKeyguardSecurityModel.getSecurityMode(anyInt())).thenReturn(
                KeyguardSecurityModel.SecurityMode.SimPin);
        mStatusBarKeyguardViewManager.showBouncerOrKeyguard(false, false, TEST_REASON);
        verify(mCentralSurfaces).hideKeyguard();
        verify(mPrimaryBouncerInteractor).show(true, TEST_REASON);
    }

    @Test
    @DisableSceneContainer
    @EnableFlags(Flags.FLAG_SIM_PIN_RACE_CONDITION_ON_RESTART)
    public void testShowBouncerOrKeyguard_showsKeyguardIfShowBouncerReturnsFalse() {
        when(mKeyguardSecurityModel.getSecurityMode(anyInt())).thenReturn(
                KeyguardSecurityModel.SecurityMode.SimPin);
        // Returning false means unable to show the bouncer
        when(mPrimaryBouncerInteractor.show(true, TEST_REASON)).thenReturn(false);
        when(mKeyguardTransitionInteractor.getTransitionState().getValue().getTo())
                .thenReturn(KeyguardState.LOCKSCREEN);
        mStatusBarKeyguardViewManager.onStartedWakingUp();

        reset(mCentralSurfaces);
        // Advance past reattempts
        mStatusBarKeyguardViewManager.setAttemptsToShowBouncer(10);

        mStatusBarKeyguardViewManager.showBouncerOrKeyguard(false, false, TEST_REASON);
        verify(mPrimaryBouncerInteractor).show(true, TEST_REASON);
        verify(mCentralSurfaces).showKeyguard();
    }

    @Test
    @DisableSceneContainer
    @EnableFlags(Flags.FLAG_SIM_PIN_RACE_CONDITION_ON_RESTART)
    public void testShowBouncerOrKeyguard_showsKeyguardIfSleeping() {
        when(mKeyguardTransitionInteractor.getTransitionState().getValue().getTo())
                .thenReturn(KeyguardState.LOCKSCREEN);
        mStatusBarKeyguardViewManager.onStartedGoingToSleep();

        reset(mCentralSurfaces);
        reset(mPrimaryBouncerInteractor);
        mStatusBarKeyguardViewManager.showBouncerOrKeyguard(
                /* hideBouncerWhenShowing= */true, false, TEST_REASON);
        verify(mCentralSurfaces).showKeyguard();
        verify(mPrimaryBouncerInteractor).hide();
    }


    @Test
    @DisableSceneContainer
    public void testShowBouncerOrKeyguard_needsFullScreen_bouncerAlreadyShowing() {
        boolean isFalsingReset = false;
        when(mKeyguardSecurityModel.getSecurityMode(anyInt())).thenReturn(
                KeyguardSecurityModel.SecurityMode.SimPin);
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(true);
        mStatusBarKeyguardViewManager.showBouncerOrKeyguard(false, isFalsingReset, TEST_REASON);
        verify(mCentralSurfaces, never()).hideKeyguard();
        verify(mPrimaryBouncerInteractor).show(true, TEST_REASON);
    }

    @Test
    @DisableSceneContainer
    public void testShowBouncerOrKeyguard_needsFullScreen_bouncerAlreadyShowing_onFalsing() {
        boolean isFalsingReset = true;
        when(mKeyguardSecurityModel.getSecurityMode(anyInt())).thenReturn(
                KeyguardSecurityModel.SecurityMode.SimPin);
        when(mPrimaryBouncerInteractor.isFullyShowing()).thenReturn(true);
        mStatusBarKeyguardViewManager.showBouncerOrKeyguard(false, isFalsingReset, TEST_REASON);
        verify(mCentralSurfaces, never()).hideKeyguard();

        // Do not refresh the full screen bouncer if the call is from falsing
        verify(mPrimaryBouncerInteractor, never()).show(true, TEST_REASON);
    }

    @Test
    @EnableSceneContainer
    public void showBouncer_attemptDeviceEntry() {
        mStatusBarKeyguardViewManager.showBouncer(false, TEST_REASON);
        verify(mDeviceEntryInteractor).attemptDeviceEntry();
    }

    @Test
    @EnableSceneContainer
    public void showPrimaryBouncer() {
        mStatusBarKeyguardViewManager.showPrimaryBouncer(false, TEST_REASON);
        verify(mSceneInteractor).showOverlay(eq(Overlays.Bouncer), anyString());
    }

    @Test
    public void altBouncerNotVisible_keyguardAuthenticatedBiometricsHandled() {
        clearInvocations(mAlternateBouncerInteractor);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(false);
        mStatusBarKeyguardViewManager.consumeKeyguardAuthenticatedBiometricsHandled(Unit.INSTANCE);
        verify(mAlternateBouncerInteractor, never()).hide();
    }

    @Test
    public void altBouncerVisible_keyguardAuthenticatedBiometricsHandled() {
        clearInvocations(mAlternateBouncerInteractor);
        when(mAlternateBouncerInteractor.isVisibleState()).thenReturn(true);
        mStatusBarKeyguardViewManager.consumeKeyguardAuthenticatedBiometricsHandled(Unit.INSTANCE);
        verify(mAlternateBouncerInteractor).hide();
    }

    @Test
    public void fromAlternateBouncerTransitionStep() {
        clearInvocations(mAlternateBouncerInteractor);
        mStatusBarKeyguardViewManager.consumeFromAlternateBouncerTransitionSteps(
                new TransitionStep(
                        /* from */ KeyguardState.ALTERNATE_BOUNCER,
                        /* to */ KeyguardState.GONE,
                        /* value */ 1f,
                        TransitionState.FINISHED,
                        "StatusBarKeyguardViewManagerTest"
                )
        );
        verify(mAlternateBouncerInteractor).hide();
    }

    @Test
    public void hideAlternateBouncer_clearsDismissActionByDefault() {
        clearInvocations(mKeyguardDismissActionInteractor);

        mStatusBarKeyguardViewManager.hideAlternateBouncer(/* updateScrim= */ true);

        verify(mKeyguardDismissActionInteractor).clearDismissAction();
    }

    @Test
    public void hideAlternateBouncer_clearsDismissActionExplicitly() {
        clearInvocations(mKeyguardDismissActionInteractor);

        mStatusBarKeyguardViewManager.hideAlternateBouncer(
                /* updateScrim= */ true, /* clearDismissAction= */ true);

        verify(mKeyguardDismissActionInteractor).clearDismissAction();
    }

    @Test
    public void hideAlternateBouncer_doNotClearDismissActionExplicitly() {
        clearInvocations(mKeyguardDismissActionInteractor);

        mStatusBarKeyguardViewManager.hideAlternateBouncer(
                /* updateScrim= */ true, /* clearDismissAction= */ false);

        verify(mKeyguardDismissActionInteractor, never()).clearDismissAction();
    }
}
