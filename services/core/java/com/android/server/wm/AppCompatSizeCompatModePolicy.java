/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.content.pm.ActivityInfo.SIZE_CHANGES_SUPPORTED_METADATA;
import static android.content.pm.ActivityInfo.SIZE_CHANGES_SUPPORTED_OVERRIDE;
import static android.content.pm.ActivityInfo.SIZE_CHANGES_UNSUPPORTED_METADATA;
import static android.content.pm.ActivityInfo.SIZE_CHANGES_UNSUPPORTED_OVERRIDE;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;

import static com.android.window.flags.Flags.enableSizeCompatModeImprovementsForConnectedDisplays;
import static com.android.server.wm.AppCompatUtils.isInDesktopMode;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;

import java.io.PrintWriter;
import java.util.function.DoubleSupplier;

/**
 * Encapsulate logic related to the SizeCompatMode.
 */
class AppCompatSizeCompatModePolicy {

    @NonNull
    private final ActivityRecord mActivityRecord;
    @NonNull
    private final AppCompatOverrides mAppCompatOverrides;

    // Whether this activity is in size compatibility mode because its bounds don't fit in parent
    // naturally.
    private boolean mInSizeCompatModeForBounds = false;
    /**
     * The scale to fit at least one side of the activity to its parent. If the activity uses
     * 1920x1080, and the actually size on the screen is 960x540, then the scale is 0.5.
     */
    private float mSizeCompatScale = 1f;

    /**
     * The bounds in global coordinates for activity in size compatibility mode.
     * @see #hasSizeCompatBounds()
     */
    private Rect mSizeCompatBounds;

    /**
     * The precomputed display insets for resolving configuration. It will be non-null if
     * {@link ActivityRecord#shouldCreateAppCompatDisplayInsets} returns {@code true}.
     */
    @Nullable
    private AppCompatDisplayInsets mAppCompatDisplayInsets;

    AppCompatSizeCompatModePolicy(@NonNull ActivityRecord activityRecord,
            @NonNull AppCompatOverrides appCompatOverrides) {
        mActivityRecord = activityRecord;
        mAppCompatOverrides = appCompatOverrides;
    }

    boolean isInSizeCompatModeForBounds() {
        return mInSizeCompatModeForBounds;
    }

    void setInSizeCompatModeForBounds(boolean inSizeCompatModeForBounds) {
        mInSizeCompatModeForBounds = inSizeCompatModeForBounds;
    }

    boolean hasSizeCompatBounds() {
        return mSizeCompatBounds != null;
    }

    /**
     * @return The {@code true} if the current instance has
     * {@link AppCompatSizeCompatModePolicy#mAppCompatDisplayInsets} without
     * considering the inheritance implemented in {@link #getAppCompatDisplayInsets()}
     */
    boolean hasAppCompatDisplayInsetsWithoutInheritance() {
        return mAppCompatDisplayInsets != null;
    }

    @Nullable
    AppCompatDisplayInsets getAppCompatDisplayInsets() {
        final TransparentPolicy transparentPolicy = mActivityRecord.mAppCompatController
                .getTransparentPolicy();
        if (transparentPolicy.isRunning()) {
            return transparentPolicy.getInheritedAppCompatDisplayInsets();
        }
        return mAppCompatDisplayInsets;
    }

    float getCompatScaleIfAvailable(@NonNull DoubleSupplier scaleWhenNotAvailable) {
        return hasSizeCompatBounds() ? mSizeCompatScale
                : (float) scaleWhenNotAvailable.getAsDouble();
    }

    @NonNull
    Rect getAppSizeCompatBoundsIfAvailable(@NonNull Rect boundsWhenNotAvailable) {
        return hasSizeCompatBounds() ? mSizeCompatBounds : boundsWhenNotAvailable;
    }

    @NonNull
    Rect replaceResolvedBoundsIfNeeded(@NonNull Rect resolvedBounds) {
        return hasSizeCompatBounds() ? mSizeCompatBounds : resolvedBounds;
    }

    boolean applyOffsetIfNeeded(@NonNull Rect resolvedBounds,
            @NonNull Configuration resolvedConfig, int offsetX, int offsetY) {
        if (hasSizeCompatBounds()) {
            mSizeCompatBounds.offset(offsetX , offsetY);
            final int dy = mSizeCompatBounds.top - resolvedBounds.top;
            final int dx = mSizeCompatBounds.left - resolvedBounds.left;
            AppCompatUtils.offsetBounds(resolvedConfig, dx, dy);
            return true;
        }
        return false;
    }

    void alignToTopIfNeeded(@NonNull Rect parentBounds) {
        if (hasSizeCompatBounds()) {
            mSizeCompatBounds.top = parentBounds.top;
        }
    }

    void applySizeCompatScaleIfNeeded(@NonNull Rect resolvedBounds,
            @NonNull Configuration resolvedConfig) {
        if (mSizeCompatScale != 1f) {
            final int screenPosX = resolvedBounds.left;
            final int screenPosY = resolvedBounds.top;
            final int dx = (int) (screenPosX / mSizeCompatScale + 0.5f) - screenPosX;
            final int dy = (int) (screenPosY / mSizeCompatScale + 0.5f) - screenPosY;
            AppCompatUtils.offsetBounds(resolvedConfig, dx, dy);
        }
    }

    void updateSizeCompatScale(@NonNull Rect resolvedAppBounds, @NonNull Rect containerAppBounds,
            @NonNull Configuration newParentConfig) {
        mSizeCompatScale = mActivityRecord.mAppCompatController.getTransparentPolicy()
                .findOpaqueNotFinishingActivityBelow()
                .map(ar -> Math.min(1.0f, ar.getCompatScale()))
                .orElseGet(() -> calculateSizeCompatScale(
                        resolvedAppBounds, containerAppBounds, newParentConfig));
    }

    void clearSizeCompatModeAttributes() {
        mInSizeCompatModeForBounds = false;
        final float lastSizeCompatScale = mSizeCompatScale;
        mSizeCompatScale = 1f;
        if (mSizeCompatScale != lastSizeCompatScale) {
            mActivityRecord.forAllWindows(WindowState::updateGlobalScale,
                    false /* traverseTopToBottom */);
        }
        mSizeCompatBounds = null;
        mAppCompatDisplayInsets = null;
        mActivityRecord.mAppCompatController.getTransparentPolicy()
                .clearInheritedAppCompatDisplayInsets();
    }

    private Configuration clearOverrideConfiguration() {
        // Clear config override in #updateAppCompatDisplayInsets().
        final int activityType = mActivityRecord.getActivityType();
        final Configuration overrideConfig = mActivityRecord.getRequestedOverrideConfiguration();
        overrideConfig.unset();
        // Keep the activity type which was set when attaching to a task to prevent leaving it
        // undefined.
        overrideConfig.windowConfiguration.setActivityType(activityType);
        return overrideConfig;
    }

    void clearSizeCompatMode() {
        clearSizeCompatModeAttributes();
        final Configuration overrideConfig = clearOverrideConfiguration();
        mActivityRecord.onRequestedOverrideConfigurationChanged(overrideConfig);
    }

    void clearSizeCompatModeIfNeededOnResolveOverrideConfiguration() {
        if (mAppCompatDisplayInsets == null || !mActivityRecord.isUniversalResizeable()) {
            return;
        }
        clearSizeCompatModeAttributes();
        clearOverrideConfiguration();
    }

    void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
        if (mSizeCompatScale != 1f || hasSizeCompatBounds()) {
            pw.println(prefix + "mSizeCompatScale=" + mSizeCompatScale + " mSizeCompatBounds="
                    + mSizeCompatBounds);
        }
    }

    /**
     * Resolves consistent screen configuration for orientation and rotation changes without
     * inheriting the parent bounds.
     */
    void resolveSizeCompatModeConfiguration(@NonNull Configuration newParentConfiguration,
            @NonNull AppCompatDisplayInsets appCompatDisplayInsets, @NonNull Rect tmpBounds) {
        final Configuration resolvedConfig = mActivityRecord.getResolvedOverrideConfiguration();
        final Rect resolvedBounds = resolvedConfig.windowConfiguration.getBounds();

        // When an activity needs to be letterboxed because of fixed orientation, use fixed
        // orientation bounds (stored in resolved bounds) instead of parent bounds since the
        // activity will be displayed within them even if it is in size compat mode. They should be
        // saved here before resolved bounds are overridden below.
        final AppCompatAspectRatioPolicy aspectRatioPolicy = mActivityRecord.mAppCompatController
                .getAspectRatioPolicy();
        final boolean useResolvedBounds = aspectRatioPolicy.isAspectRatioApplied();
        final Rect containerBounds = useResolvedBounds
                ? new Rect(resolvedBounds)
                : newParentConfiguration.windowConfiguration.getBounds();
        final Rect containerAppBounds = useResolvedBounds
                ? new Rect(resolvedConfig.windowConfiguration.getAppBounds())
                : mActivityRecord.mResolveConfigHint.mParentAppBoundsOverride;

        final int requestedOrientation = mActivityRecord.getRequestedConfigurationOrientation();
        final boolean orientationRequested = requestedOrientation != ORIENTATION_UNDEFINED;
        final int parentOrientation = mActivityRecord.mResolveConfigHint.mUseOverrideInsetsForConfig
                ? mActivityRecord.mResolveConfigHint.mTmpOverrideConfigOrientation
                : newParentConfiguration.orientation;
        final int orientation = orientationRequested
                ? requestedOrientation
                // We should use the original orientation of the activity when possible to avoid
                // forcing the activity in the opposite orientation.
                : appCompatDisplayInsets.mOriginalRequestedOrientation != ORIENTATION_UNDEFINED
                        ? appCompatDisplayInsets.mOriginalRequestedOrientation
                        : parentOrientation;
        int rotation = newParentConfiguration.windowConfiguration.getRotation();
        final boolean isFixedToUserRotation = mActivityRecord.mDisplayContent == null
                || mActivityRecord.mDisplayContent.getDisplayRotation().isFixedToUserRotation();
        final boolean tasksAreFloating =
                newParentConfiguration.windowConfiguration.tasksAreFloating();
        // Ignore parent rotation for floating tasks as window rotation is independent of its parent
        // and thus will remain, and so should be reconfigured, in its original rotation.
        if (!isFixedToUserRotation && !tasksAreFloating) {
            // Use parent rotation because the original display can be rotated.
            resolvedConfig.windowConfiguration.setRotation(rotation);
        } else {
            final int overrideRotation = resolvedConfig.windowConfiguration.getRotation();
            if (overrideRotation != ROTATION_UNDEFINED) {
                rotation = overrideRotation;
            }
        }

        // Use compat insets to lock width and height. We should not use the parent width and height
        // because apps in compat mode should have a constant width and height. The compat insets
        // are locked when the app is first launched and are never changed after that, so we can
        // rely on them to contain the original and unchanging width and height of the app.
        final Rect containingAppBounds = new Rect();
        final Rect containingBounds = tmpBounds;
        appCompatDisplayInsets.getContainerBounds(containingAppBounds, containingBounds, rotation,
                orientation, orientationRequested, isFixedToUserRotation);
        resolvedBounds.set(containingBounds);
        // The size of floating task is fixed (only swap), so the aspect ratio is already correct.
        if (!appCompatDisplayInsets.mIsFloating) {
            aspectRatioPolicy.applyAspectRatioForLetterbox(resolvedBounds, containingAppBounds,
                            containingBounds);
        }

        // Use resolvedBounds to compute other override configurations such as appBounds. The bounds
        // are calculated in compat container space. The actual position on screen will be applied
        // later, so the calculation is simpler that doesn't need to involve offset from parent.
        mActivityRecord.mResolveConfigHint.mTmpCompatInsets = appCompatDisplayInsets;
        mActivityRecord.computeConfigByResolveHint(resolvedConfig, newParentConfiguration);
        // Use current screen layout as source because the size of app is independent to parent.
        resolvedConfig.screenLayout = ActivityRecord.computeScreenLayout(
                mActivityRecord.getConfiguration().screenLayout, resolvedConfig.screenWidthDp,
                resolvedConfig.screenHeightDp);

        // Use parent orientation if it cannot be decided by bounds, so the activity can fit inside
        // the parent bounds appropriately.
        if (resolvedConfig.screenWidthDp == resolvedConfig.screenHeightDp) {
            resolvedConfig.orientation = parentOrientation;
        }

        // Below figure is an example that puts an activity which was launched in a larger container
        // into a smaller container.
        //   The outermost rectangle is the real display bounds.
        //   "@" is the container app bounds (parent bounds or fixed orientation bounds)
        //   "#" is the {@code resolvedBounds} that applies to application.
        //   "*" is the {@code mSizeCompatBounds} that used to show on screen if scaled.
        // ------------------------------
        // |                            |
        // |    @@@@*********@@@@###    |
        // |    @   *       *   @  #    |
        // |    @   *       *   @  #    |
        // |    @   *       *   @  #    |
        // |    @@@@*********@@@@  #    |
        // ---------#--------------#-----
        //          #              #
        //          ################
        // The application is still layouted in "#" since it was launched, and it will be visually
        // scaled and positioned to "*".

        final Rect resolvedAppBounds = resolvedConfig.windowConfiguration.getAppBounds();
        // Calculates the scale the size compatibility bounds into the region which is available
        // to application.
        final float lastSizeCompatScale = mSizeCompatScale;
        updateSizeCompatScale(resolvedAppBounds, containerAppBounds, newParentConfiguration);

        // Container top inset when floating is not included in height of bounds.
        final int containerTopInset = tasksAreFloating ? 0
                : containerAppBounds.top - containerBounds.top;
        final boolean topNotAligned =
                containerTopInset != resolvedAppBounds.top - resolvedBounds.top;
        if (mSizeCompatScale != 1f || topNotAligned) {
            if (mSizeCompatBounds == null) {
                mSizeCompatBounds = new Rect();
            }
            mSizeCompatBounds.set(resolvedAppBounds);
            mSizeCompatBounds.offsetTo(0, 0);
            mSizeCompatBounds.scale(mSizeCompatScale);
            // The insets are included in height, e.g. the area of real cutout shouldn't be scaled.
            mSizeCompatBounds.bottom += containerTopInset;
        } else {
            mSizeCompatBounds = null;
        }
        if (mSizeCompatScale != lastSizeCompatScale) {
            mActivityRecord.forAllWindows(WindowState::updateGlobalScale,
                    false /* traverseTopToBottom */);
        }

        // The position will be later adjusted in updateResolvedBoundsPosition.
        // Above coordinates are in "@" space, now place "*" and "#" to screen space.
        final boolean fillContainer = resolvedBounds.equals(containingBounds);
        final int screenPosX = fillContainer ? containerBounds.left : containerAppBounds.left;
        final int screenPosY = fillContainer ? containerBounds.top : containerAppBounds.top;

        if (screenPosX != 0 || screenPosY != 0) {
            if (hasSizeCompatBounds()) {
                mSizeCompatBounds.offset(screenPosX, screenPosY);
            }
            // Add the global coordinates and remove the local coordinates.
            final int dx = screenPosX - resolvedBounds.left;
            final int dy = screenPosY - resolvedBounds.top;
            AppCompatUtils.offsetBounds(resolvedConfig, dx, dy);
        }

        mInSizeCompatModeForBounds = isInSizeCompatModeForBounds(resolvedAppBounds,
                containerAppBounds);
    }

    // TODO(b/36505427): Consider moving this method and similar ones to ConfigurationContainer.
    void updateAppCompatDisplayInsets() {
        if (getAppCompatDisplayInsets() != null
                || !mActivityRecord.shouldCreateAppCompatDisplayInsets()) {
            // The override configuration is set only once in size compatibility mode.
            return;
        }

        Configuration overrideConfig = mActivityRecord.getRequestedOverrideConfiguration();
        final Configuration fullConfig = mActivityRecord.getConfiguration();

        // Ensure the screen related fields are set. It is used to prevent activity relaunch
        // when moving between displays. For screenWidthDp and screenWidthDp, because they
        // are relative to bounds and density, they will be calculated in
        // {@link Task#computeConfigResourceOverrides} and the result will also be
        // relatively fixed.
        overrideConfig.colorMode = fullConfig.colorMode;
        overrideConfig.densityDpi = fullConfig.densityDpi;
        if (enableSizeCompatModeImprovementsForConnectedDisplays()) {
            overrideConfig.touchscreen = fullConfig.touchscreen;
            overrideConfig.navigation = fullConfig.navigation;
            overrideConfig.keyboard = fullConfig.keyboard;
            overrideConfig.keyboardHidden = fullConfig.keyboardHidden;
            overrideConfig.hardKeyboardHidden = fullConfig.hardKeyboardHidden;
            overrideConfig.navigationHidden = fullConfig.navigationHidden;
        }
        // The smallest screen width is the short side of screen bounds. Because the bounds
        // and density won't be changed, smallestScreenWidthDp is also fixed.
        overrideConfig.smallestScreenWidthDp = fullConfig.smallestScreenWidthDp;
        if (ActivityInfo.isFixedOrientation(mActivityRecord.getOverrideOrientation())) {
            // lock rotation too. When in size-compat, onConfigurationChanged will watch for and
            // apply runtime rotation changes.
            overrideConfig.windowConfiguration.setRotation(
                    fullConfig.windowConfiguration.getRotation());
        }

        final Rect letterboxedContainerBounds = mActivityRecord.mAppCompatController
                .getAspectRatioPolicy().getLetterboxedContainerBounds();

        // The role of AppCompatDisplayInsets is like the override bounds.
        mAppCompatDisplayInsets =
                new AppCompatDisplayInsets(mActivityRecord.mDisplayContent, mActivityRecord,
                        letterboxedContainerBounds, mActivityRecord.mResolveConfigHint
                            .mUseOverrideInsetsForConfig);
    }

    /**
     * @return {@code true} if this activity is in size compatibility mode that uses the different
     *         density than its parent or its bounds don't fit in parent naturally.
     */
    boolean inSizeCompatMode() {
        if (isInSizeCompatModeForBounds()) {
            return true;
        }
        if (getAppCompatDisplayInsets() == null || !shouldCreateAppCompatDisplayInsets()
                // The orientation is different from parent when transforming.
                || mActivityRecord.isFixedRotationTransforming()) {
            return false;
        }
        final Rect appBounds = mActivityRecord.getConfiguration().windowConfiguration
                .getAppBounds();
        if (appBounds == null) {
            // The app bounds hasn't been computed yet.
            return false;
        }
        final WindowContainer<?> parent = mActivityRecord.getParent();
        if (parent == null) {
            // The parent of detached Activity can be null.
            return false;
        }
        final Configuration parentConfig = parent.getConfiguration();
        // Although colorMode, screenLayout, smallestScreenWidthDp are also fixed, generally these
        // fields should be changed with density and bounds, so here only compares the most
        // significant field.
        return parentConfig.densityDpi != mActivityRecord.getConfiguration().densityDpi;
    }

    /**
     * Indicates the activity will keep the bounds and screen configuration when it was first
     * launched, no matter how its parent changes.
     *
     * <p>If {@true}, then {@link AppCompatDisplayInsets} will be created in {@link
     * ActivityRecord#resolveOverrideConfiguration} to "freeze" activity bounds and insets.
     *
     * @return {@code true} if this activity is declared as non-resizable and fixed orientation or
     *         aspect ratio.
     */
    boolean shouldCreateAppCompatDisplayInsets() {
        if (mActivityRecord.mAppCompatController.getAspectRatioOverrides()
                .hasFullscreenOverride()) {
            // If the user has forced the applications aspect ratio to be fullscreen, don't use size
            // compatibility mode in any situation. The user has been warned and therefore accepts
            // the risk of the application misbehaving.
            return false;
        }
        switch (supportsSizeChanges()) {
            case SIZE_CHANGES_SUPPORTED_METADATA:
            case SIZE_CHANGES_SUPPORTED_OVERRIDE:
                return false;
            case SIZE_CHANGES_UNSUPPORTED_OVERRIDE:
                return true;
            default:
                // Fall through
        }
        // Use root activity's info for tasks in multi-window mode, or fullscreen tasks in freeform
        // task display areas, to ensure visual consistency across activity launches and exits in
        // the same task.
        final TaskDisplayArea tda = mActivityRecord.getTaskDisplayArea();
        if (mActivityRecord.inMultiWindowMode() || (tda != null && tda.inFreeformWindowingMode())) {
            final Task task = mActivityRecord.getTask();
            final ActivityRecord root = task != null ? task.getRootActivity() : null;
            if (root != null && root != mActivityRecord
                    && !root.shouldCreateAppCompatDisplayInsets()) {
                // If the root activity doesn't use size compatibility mode, the activities above
                // are forced to be the same for consistent visual appearance.
                return false;
            }
        }
        final AppCompatAspectRatioPolicy aspectRatioPolicy = mActivityRecord.mAppCompatController
                .getAspectRatioPolicy();
        return !mActivityRecord.isResizeable() && (mActivityRecord.info.isFixedOrientation()
                || aspectRatioPolicy.hasFixedAspectRatio())
                // The configuration of non-standard type should be enforced by system.
                // {@link WindowConfiguration#ACTIVITY_TYPE_STANDARD} is set when this activity is
                // added to a task, but this function is called when resolving the launch params, at
                // which point, the activity type is still undefined if it will be standard.
                // For other non-standard types, the type is set in the constructor, so this should
                // not be a problem.
                && mActivityRecord.isActivityTypeStandardOrUndefined();
    }

    /**
     * Returns whether the activity supports size changes.
     */
    @ActivityInfo.SizeChangesSupportMode
    int supportsSizeChanges() {
        final AppCompatResizeOverrides resizeOverrides = mAppCompatOverrides.getResizeOverrides();
        if (resizeOverrides.shouldOverrideForceNonResizeApp()) {
            return SIZE_CHANGES_UNSUPPORTED_OVERRIDE;
        }

        if (mActivityRecord.info.supportsSizeChanges) {
            return SIZE_CHANGES_SUPPORTED_METADATA;
        }

        if (resizeOverrides.shouldOverrideForceResizeApp()) {
            return SIZE_CHANGES_SUPPORTED_OVERRIDE;
        }

        return SIZE_CHANGES_UNSUPPORTED_METADATA;
    }


    private boolean isInSizeCompatModeForBounds(final @NonNull Rect appBounds,
            final @NonNull Rect containerBounds) {
        if (mActivityRecord.mAppCompatController.getTransparentPolicy().isRunning()) {
            // To avoid wrong app behaviour, we decided to disable SCM when a translucent activity
            // is letterboxed.
            return false;
        }
        final int appWidth = appBounds.width();
        final int appHeight = appBounds.height();
        final int containerAppWidth = containerBounds.width();
        final int containerAppHeight = containerBounds.height();

        if (containerAppWidth == appWidth && containerAppHeight == appHeight) {
            // Matched the container bounds.
            return false;
        }
        if (containerAppWidth > appWidth && containerAppHeight > appHeight) {
            // Both sides are smaller than the container.
            return true;
        }
        if (containerAppWidth < appWidth || containerAppHeight < appHeight) {
            // One side is larger than the container.
            return true;
        }

        // The rest of the condition is that only one side is smaller than the container, but it
        // still needs to exclude the cases where the size is limited by the fixed aspect ratio.
        final float maxAspectRatio = mActivityRecord.getMaxAspectRatio();
        if (maxAspectRatio > 0) {
            final float aspectRatio = (0.5f + Math.max(appWidth, appHeight))
                    / Math.min(appWidth, appHeight);
            if (aspectRatio >= maxAspectRatio) {
                // The current size has reached the max aspect ratio.
                return false;
            }
        }
        final float minAspectRatio = mActivityRecord.getMinAspectRatio();
        if (minAspectRatio > 0) {
            // The activity should have at least the min aspect ratio, so this checks if the
            // container still has available space to provide larger aspect ratio.
            final float containerAspectRatio =
                    (0.5f + Math.max(containerAppWidth, containerAppHeight))
                            / Math.min(containerAppWidth, containerAppHeight);
            if (containerAspectRatio <= minAspectRatio) {
                // The long side has reached the parent.
                return false;
            }
        }
        return true;
    }

    private float calculateSizeCompatScale(@NonNull Rect resolvedAppBounds,
            @NonNull Rect containerAppBounds, @NonNull Configuration newParentConfig) {
        final int contentW = resolvedAppBounds.width();
        final int contentH = resolvedAppBounds.height();
        final int viewportW = containerAppBounds.width();
        final int viewportH = containerAppBounds.height();
        // Allow an application to be up-scaled if its window is smaller than its
        // original container or if it's a freeform window in desktop mode.
        boolean shouldAllowUpscaling = !(contentW <= viewportW && contentH <= viewportH)
                || isInDesktopMode(mActivityRecord.mAtmService.mContext,
                newParentConfig.windowConfiguration.getWindowingMode());
        return shouldAllowUpscaling ? Math.min(
                (float) viewportW / contentW, (float) viewportH / contentH) : 1f;
    }
}
