/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Resources;
import android.content.ContentResolver;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.systemui.res.R;
import com.android.systemui.settings.brightness.BrightnessSliderController;
import com.android.systemui.settings.brightness.MirrorController;
import com.android.systemui.settings.brightness.ToggleSlider;
import com.android.systemui.shade.NotificationShadeWindowView;
import com.android.systemui.shade.ShadeViewController;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Controls showing and hiding of the brightness mirror.
 */
public class BrightnessMirrorController implements MirrorController {

    private final NotificationShadeWindowView mStatusBarWindow;
    private final Consumer<Boolean> mVisibilityCallback;
    private final ShadeViewController mNotificationPanel;
    private final NotificationShadeDepthController mDepthController;
    private final ArraySet<BrightnessMirrorListener> mBrightnessMirrorListeners = new ArraySet<>();
    private final BrightnessSliderController.Factory mToggleSliderFactory;
    private BrightnessSliderController mToggleSliderController;
    private final int[] mInt2Cache = new int[2];

    private FrameLayout mBrightnessMirror;
    private int mBrightnessMirrorBackgroundPadding;
    private int mLastBrightnessSliderWidth = -1;
    private Context mContext;

    public BrightnessMirrorController(Context context, NotificationShadeWindowView statusBarWindow,
            ShadeViewController shadeViewController,
            NotificationShadeDepthController notificationShadeDepthController,
            BrightnessSliderController.Factory factory,
            @NonNull Consumer<Boolean> visibilityCallback) {
        mContext = context;
        mStatusBarWindow = statusBarWindow;
        mToggleSliderFactory = factory;
        mBrightnessMirror = statusBarWindow.findViewById(R.id.brightness_mirror_container);
        mToggleSliderController = setMirrorLayout();
        mNotificationPanel = shadeViewController;
        mDepthController = notificationShadeDepthController;
        mNotificationPanel.setAlphaChangeAnimationEndAction(() -> {
            mBrightnessMirror.setVisibility(View.INVISIBLE);
        });
        mVisibilityCallback = visibilityCallback;
        updateIcon();
        updateResources();
    }

    @Override
    public void showMirror() {
        updateIcon();
        mBrightnessMirror.setVisibility(View.VISIBLE);
        mVisibilityCallback.accept(true);
        mNotificationPanel.setAlpha(0, true /* animate */);
        mDepthController.setBrightnessMirrorVisible(true);
    }

    @Override
    public void hideMirror() {
        mVisibilityCallback.accept(false);
        mNotificationPanel.setAlpha(255, true /* animate */);
        mDepthController.setBrightnessMirrorVisible(false);
    }

    @Override
    public void setLocationAndSize(View original) {
        original.getLocationInWindow(mInt2Cache);

        // Original is slightly larger than the mirror, so make sure to use the center for the
        // positioning.
        int originalX = mInt2Cache[0] - mBrightnessMirrorBackgroundPadding;
        int originalY = mInt2Cache[1] - mBrightnessMirrorBackgroundPadding;
        mBrightnessMirror.setTranslationX(0);
        mBrightnessMirror.setTranslationY(0);
        mBrightnessMirror.getLocationInWindow(mInt2Cache);
        int mirrorX = mInt2Cache[0];
        int mirrorY = mInt2Cache[1];
        mBrightnessMirror.setTranslationX(originalX - mirrorX);
        mBrightnessMirror.setTranslationY(originalY - mirrorY);

        // Set the brightness mirror container to be the width of the mirror + 2 times the padding
        int newWidth = original.getMeasuredWidth() + 2 * mBrightnessMirrorBackgroundPadding;
        if (newWidth != mLastBrightnessSliderWidth) {
            ViewGroup.LayoutParams lp = mBrightnessMirror.getLayoutParams();
            lp.width = newWidth;
            mBrightnessMirror.setLayoutParams(lp);
        }

        updateIcon();
    }

    @Override
    public ToggleSlider getToggleSlider() {
        return mToggleSliderController;
    }

    public void updateResources() {
        Resources r = mBrightnessMirror.getResources();
        mBrightnessMirrorBackgroundPadding = r
                .getDimensionPixelSize(R.dimen.rounded_slider_background_padding);
        mBrightnessMirror.setPadding(
                mBrightnessMirrorBackgroundPadding,
                mBrightnessMirrorBackgroundPadding,
                mBrightnessMirrorBackgroundPadding,
                mBrightnessMirrorBackgroundPadding
        );
    }

    public void onOverlayChanged() {
        reinflate();
    }

    public void onDensityOrFontScaleChanged() {
        reinflate();
    }

    private BrightnessSliderController setMirrorLayout() {
        Context context = mBrightnessMirror.getContext();
        BrightnessSliderController controller = mToggleSliderFactory.create(context,
                mBrightnessMirror);
        controller.init();

        mBrightnessMirror.addView(controller.getRootView(), ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);

        return controller;
    }

    private void reinflate() {
        int index = mStatusBarWindow.indexOfChild(mBrightnessMirror);
        mStatusBarWindow.removeView(mBrightnessMirror);
        mBrightnessMirror = (FrameLayout) LayoutInflater.from(mStatusBarWindow.getContext())
                .inflate(R.layout.brightness_mirror_container, mStatusBarWindow, false);
        mToggleSliderController = setMirrorLayout();
        mStatusBarWindow.addView(mBrightnessMirror, index);
        updateResources();

        for (int i = 0; i < mBrightnessMirrorListeners.size(); i++) {
            mBrightnessMirrorListeners.valueAt(i).onBrightnessMirrorReinflated(mBrightnessMirror);
        }

        updateIcon();
    }

    @Override
    public void addCallback(@NonNull BrightnessMirrorListener listener) {
        Objects.requireNonNull(listener);
        mBrightnessMirrorListeners.add(listener);
    }

    @Override
    public void removeCallback(@NonNull BrightnessMirrorListener listener) {
        mBrightnessMirrorListeners.remove(listener);
    }

    public void onUiModeChanged() {
        reinflate();
    }

    private void updateIcon() {
        // maybe enable the brightness icon
        if (mBrightnessMirror == null) return;
        ImageView icon = mBrightnessMirror.findViewById(R.id.brightness_icon);
        if (icon == null) return;
        boolean show = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.QS_SHOW_AUTO_BRIGHTNESS_BUTTON, 1) == 1;
        if (!show) {
            icon.setVisibility(View.GONE);
            return;
        }
        icon.setVisibility(View.VISIBLE);
        boolean automatic = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                UserHandle.USER_CURRENT) != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
        icon.setImageResource(automatic ?
                com.android.systemui.res.R.drawable.ic_qs_brightness_auto_on_new :
                com.android.systemui.res.R.drawable.ic_qs_brightness_auto_off_new);
    }
}
