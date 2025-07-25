/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.settings.brightness;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.content.Intent.EXTRA_BRIGHTNESS_DIALOG_IS_FULL_WIDTH;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowManagerPolicyConstants.EXTRA_FROM_BRIGHTNESS_KEY;

import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import android.app.Activity;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.compose.ui.platform.ComposeView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel;
import com.android.systemui.compose.ComposeInitializer;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.qs.flags.QsInCompose;
import com.android.systemui.res.R;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.util.List;

import javax.inject.Inject;

/** A dialog that provides controls for adjusting the screen brightness. */
public class BrightnessDialog extends Activity {

    @VisibleForTesting
    static final int DIALOG_TIMEOUT_MILLIS = 3000;

    private BrightnessController mBrightnessController;
    private final BrightnessSliderController.Factory mToggleSliderFactory;
    private final BrightnessController.Factory mBrightnessControllerFactory;
    private final DelayableExecutor mMainExecutor;
    private final AccessibilityManagerWrapper mAccessibilityMgr;
    private Runnable mCancelTimeoutRunnable;
    private final ShadeInteractor mShadeInteractor;
    private final BrightnessSliderViewModel.Factory mBrightnessSliderViewModelFactory;

    private ImageView mAutoBrightnessIcon;

    private final CustomSettingsObserver mCustomSettingsObserver = new CustomSettingsObserver();
    private class CustomSettingsObserver extends ContentObserver {
        CustomSettingsObserver() {
            super(new Handler(Looper.getMainLooper()));
        }

        void observe() {
            getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.QS_SHOW_AUTO_BRIGHTNESS_BUTTON),
                    false, this, UserHandle.USER_ALL);
        }

        void stop() {
            getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (mAutoBrightnessIcon == null) return;
            boolean show = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.QS_SHOW_AUTO_BRIGHTNESS_BUTTON, 1) == 1;
            mAutoBrightnessIcon.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    @Inject
    public BrightnessDialog(
            BrightnessSliderController.Factory brightnessSliderfactory,
            BrightnessController.Factory brightnessControllerFactory,
            @Main DelayableExecutor mainExecutor,
            AccessibilityManagerWrapper accessibilityMgr,
            ShadeInteractor shadeInteractor,
            BrightnessSliderViewModel.Factory brightnessSliderViewModelFactory
    ) {
        mToggleSliderFactory = brightnessSliderfactory;
        mBrightnessControllerFactory = brightnessControllerFactory;
        mMainExecutor = mainExecutor;
        mAccessibilityMgr = accessibilityMgr;
        mShadeInteractor = shadeInteractor;
        mBrightnessSliderViewModelFactory = brightnessSliderViewModelFactory;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setWindowAttributes();
        View view;
        if (!QsInCompose.isEnabled()) {
            setContentView(R.layout.brightness_mirror_container);
            view = findViewById(R.id.brightness_mirror_container);
            setDialogContent((FrameLayout) view);
        } else {
            ComposeView composeView = new ComposeView(this);
            ComposeDialogComposableProvider.INSTANCE.setComposableBrightness(
                    composeView,
                    new ComposableProvider(mBrightnessSliderViewModelFactory)
            );
            composeView.setId(R.id.brightness_dialog_slider);
            setContentView(composeView);
            ((ViewGroup) composeView.getParent()).setClipChildren(false);
            view = composeView;
        }
        setBrightnessDialogViewAttributes(view);

        if (mShadeInteractor.isQsExpanded().getValue()) {
            finish();
        }

        if (view != null) {
            collectFlow(view, mShadeInteractor.isQsExpanded(), this::onShadeStateChange);
        }
    }

    private void onShadeStateChange(boolean isQsExpanded) {
        if (isQsExpanded) {
            requestFinish();
        }
    }

    private void setWindowAttributes() {
        final Window window = getWindow();

        window.setGravity(Gravity.TOP | Gravity.START);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.requestFeature(Window.FEATURE_NO_TITLE);

        // Calling this creates the decor View, so setLayout takes proper effect
        // (see Dialog#onWindowAttributesChanged)
        window.getDecorView();
        window.setLayout(WRAP_CONTENT, WRAP_CONTENT);
        getTheme().applyStyle(R.style.Theme_SystemUI_QuickSettings, false);
        if (QsInCompose.isEnabled()) {
            window.getDecorView().addOnAttachStateChangeListener(
                    new View.OnAttachStateChangeListener() {
                        @Override
                        public void onViewAttachedToWindow(@NonNull View v) {
                            ComposeInitializer.INSTANCE.onAttachedToWindow(v);
                        }

                        @Override
                        public void onViewDetachedFromWindow(@NonNull View v) {
                            ComposeInitializer.INSTANCE.onDetachedFromWindow(v);
                        }
                    });
        }
    }

    void setBrightnessDialogViewAttributes(View container) {
        Configuration configuration = getResources().getConfiguration();
        // The brightness mirror container is INVISIBLE by default.
        container.setVisibility(View.VISIBLE);
        ViewGroup.MarginLayoutParams lp =
                (ViewGroup.MarginLayoutParams) container.getLayoutParams();
        // Remove the margin. Have the container take all the space. Instead, insert padding.
        // This allows for the background to be visible around the slider.
        int margin = 0;
        lp.topMargin = margin;
        lp.bottomMargin = margin;
        lp.leftMargin = margin;
        lp.rightMargin = margin;
        int padding = getResources().getDimensionPixelSize(
                R.dimen.rounded_slider_background_padding
        );
        container.setPadding(padding, padding, padding, padding);
        // If in multi-window or freeform, increase the top margin so the brightness dialog
        // doesn't get cut off.
        final int windowingMode = configuration.windowConfiguration.getWindowingMode();
        if (windowingMode == WINDOWING_MODE_MULTI_WINDOW
                || windowingMode == WINDOWING_MODE_FREEFORM) {
            lp.topMargin += 50;
        }

        int orientation = configuration.orientation;
        int windowWidth = getWindowAvailableWidth();

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            boolean shouldBeFullWidth = getIntent()
                    .getBooleanExtra(EXTRA_BRIGHTNESS_DIALOG_IS_FULL_WIDTH, false);
            lp.width = (shouldBeFullWidth ? windowWidth : windowWidth / 2) - margin * 2;
        } else if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            lp.width = windowWidth - margin * 2;
        }

        container.setLayoutParams(lp);
        Rect bounds = new Rect();
        container.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    // Exclude this view (and its horizontal margins) from triggering gestures.
                    // This prevents back gesture from being triggered by dragging close to the
                    // edge of the slider (0% or 100%).
                    bounds.set(-margin, 0, right - left + margin, bottom - top);
                    v.setSystemGestureExclusionRects(List.of(bounds));
                });
    }

    private void setDialogContent(FrameLayout frame) {
        BrightnessSliderController controller = mToggleSliderFactory.create(this, frame);
        controller.init();
        frame.addView(controller.getRootView(), MATCH_PARENT, WRAP_CONTENT);

        mAutoBrightnessIcon = controller.getIconView();
        boolean show = Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.QS_SHOW_AUTO_BRIGHTNESS_BUTTON, 1) == 1;
        mAutoBrightnessIcon.setVisibility(show ? View.VISIBLE : View.GONE);
        mBrightnessController = mBrightnessControllerFactory.create(mAutoBrightnessIcon, controller);
    }

    private int getWindowAvailableWidth() {
        final WindowMetrics metrics = getWindowManager().getCurrentWindowMetrics();
        // Gets all excluding insets
        final WindowInsets windowInsets = metrics.getWindowInsets();
        Insets insets = windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()
                | WindowInsets.Type.displayCutout());
        int insetsWidth = insets.right + insets.left;
        return metrics.getBounds().width() - insetsWidth;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!QsInCompose.isEnabled()) {
            mBrightnessController.registerCallbacks();
        }
        MetricsLogger.visible(this, MetricsEvent.BRIGHTNESS_DIALOG);
        mCustomSettingsObserver.observe();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (triggeredByBrightnessKey()) {
            scheduleTimeout();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onStop() {
        super.onStop();
        MetricsLogger.hidden(this, MetricsEvent.BRIGHTNESS_DIALOG);
        if (!QsInCompose.isEnabled()) {
            mBrightnessController.unregisterCallbacks();
        }
        mCustomSettingsObserver.stop();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (mCancelTimeoutRunnable != null) {
                mCancelTimeoutRunnable.run();
            }
            requestFinish();
        }
        return super.onKeyDown(keyCode, event);
    }

    protected void requestFinish() {
        finish();
    }

    private boolean triggeredByBrightnessKey() {
        return getIntent().getBooleanExtra(EXTRA_FROM_BRIGHTNESS_KEY, false);
    }

    private void scheduleTimeout() {
        if (mCancelTimeoutRunnable != null) {
            mCancelTimeoutRunnable.run();
        }
        final int timeout = mAccessibilityMgr.getRecommendedTimeoutMillis(DIALOG_TIMEOUT_MILLIS,
                AccessibilityManager.FLAG_CONTENT_CONTROLS);
        mCancelTimeoutRunnable = mMainExecutor.executeDelayed(this::requestFinish, timeout);
    }
}
