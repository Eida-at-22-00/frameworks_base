/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.view.InsetsSource.ID_IME;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.policy.WindowManagerPolicy.TRANSIT_EXIT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Configuration;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.IBinder;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.window.WindowContext;

import androidx.test.filters.SmallTest;

import com.android.window.flags.Flags;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.BiFunction;

/**
 * Tests for the {@link WindowToken} class.
 *
 * Build/Install/Run:
 *  atest WmTests:WindowTokenTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowTokenTests extends WindowTestsBase {

    @Test
    public void testAddWindow() {
        final TestWindowToken token = createTestWindowToken(0, mDisplayContent);

        assertEquals(0, token.getWindowsCount());

        final WindowState window1 = newWindowBuilder("window1", TYPE_APPLICATION).setWindowToken(
                token).build();
        final WindowState window11 = newWindowBuilder("window11", FIRST_SUB_WINDOW).setParent(
                window1).setWindowToken(token).build();
        final WindowState window12 = newWindowBuilder("window12", FIRST_SUB_WINDOW).setParent(
                window1).setWindowToken(token).build();
        final WindowState window2 = newWindowBuilder("window2", TYPE_APPLICATION).setWindowToken(
                token).build();
        final WindowState window3 = newWindowBuilder("window3", TYPE_APPLICATION).setWindowToken(
                token).build();

        token.addWindow(window1);
        // NOTE: Child windows will not be added to the token as window containers can only
        // contain/reference their direct children.
        token.addWindow(window11);
        token.addWindow(window12);
        token.addWindow(window2);
        token.addWindow(window3);

        // Should not contain the child windows that were added above.
        assertEquals(3, token.getWindowsCount());
        assertTrue(token.hasWindow(window1));
        assertFalse(token.hasWindow(window11));
        assertFalse(token.hasWindow(window12));
        assertTrue(token.hasWindow(window2));
        assertTrue(token.hasWindow(window3));

        // The child windows should have the same window token as their parents.
        assertEquals(window1.mToken, window11.mToken);
        assertEquals(window1.mToken, window12.mToken);
    }

    @Test
    public void testAddWindow_assignsLayers() {
        final TestWindowToken token1 = createTestWindowToken(0, mDisplayContent);
        final TestWindowToken token2 = createTestWindowToken(0, mDisplayContent);
        final WindowState window1 = newWindowBuilder("window1", TYPE_STATUS_BAR).setWindowToken(
                token1).build();
        final WindowState window2 = newWindowBuilder("window2", TYPE_STATUS_BAR).setWindowToken(
                token2).build();

        token1.addWindow(window1);
        token2.addWindow(window2);

        assertEquals(token1.getLastLayer(), 0);
        assertEquals(token2.getLastLayer(), 1);
    }

    @Test
    public void testChildRemoval() {
        final DisplayContent dc = mDisplayContent;
        final TestWindowToken token = createTestWindowToken(0, dc);

        assertEquals(token, dc.getWindowToken(token.token));

        final WindowState window1 = newWindowBuilder("window1", TYPE_APPLICATION).setWindowToken(
                token).build();
        final WindowState window2 = newWindowBuilder("window2", TYPE_APPLICATION).setWindowToken(
                token).build();

        window2.removeImmediately();
        // The token should still be mapped in the display content since it still has a child.
        assertEquals(token, dc.getWindowToken(token.token));

        window1.removeImmediately();
        // The token should have been removed from the display content since it no longer has a
        // child.
        assertEquals(null, dc.getWindowToken(token.token));
    }

    /**
     * Test that a window token isn't orphaned by the system when it is requested to be removed.
     * Tokens should only be removed from the system when all their windows are gone.
     */
    @Test
    public void testTokenRemovalProcess() {
        final TestWindowToken token = createTestWindowToken(
                TYPE_TOAST, mDisplayContent, true /* persistOnEmpty */);

        // Verify that the token is on the display
        assertNotNull(mDisplayContent.getWindowToken(token.token));

        final WindowState window1 = newWindowBuilder("window1", TYPE_TOAST).setWindowToken(
                token).build();
        final WindowState window2 = newWindowBuilder("window2", TYPE_TOAST).setWindowToken(
                token).build();

        mDisplayContent.removeWindowToken(token.token, true /* animateExit */);
        // Verify that the token is no longer mapped on the display
        assertNull(mDisplayContent.getWindowToken(token.token));
        // Verify that the token is still attached to its parent
        assertNotNull(token.getParent());
        // Verify that the token windows are still around.
        assertEquals(2, token.getWindowsCount());

        window1.removeImmediately();
        // Verify that the token is still attached to its parent
        assertNotNull(token.getParent());
        // Verify that the other token window is still around.
        assertEquals(1, token.getWindowsCount());

        final TransitionController transitionController = token.mTransitionController;
        spyOn(transitionController);
        doReturn(true).when(transitionController).isPlayingTarget(token);
        window2.removeImmediately();
        assertTrue(token.mIsExiting);
        assertNotNull("Defer removal for playing transition", token.getParent());

        doReturn(false).when(transitionController).isPlayingTarget(token);
        token.handleCompleteDeferredRemoval();
        assertFalse(token.mIsExiting);
        // Verify that the token is no-longer attached to its parent
        assertNull(token.getParent());
        // Verify that the token windows are no longer attached to it.
        assertEquals(0, token.getWindowsCount());
    }

    @Test
    public void testFinishFixedRotationTransform() {
        final WindowToken[] tokens = new WindowToken[3];
        for (int i = 0; i < tokens.length; i++) {
            tokens[i] = createTestWindowToken(TYPE_APPLICATION_OVERLAY, mDisplayContent);
        }

        final Configuration config = new Configuration(mDisplayContent.getConfiguration());
        final int originalRotation = config.windowConfiguration.getRotation();
        final int targetRotation = (originalRotation + 1) % 4;

        config.windowConfiguration.setRotation(targetRotation);
        tokens[0].applyFixedRotationTransform(mDisplayInfo, mDisplayContent.mDisplayFrames, config);
        tokens[1].linkFixedRotationTransform(tokens[0]);

        // The window tokens should apply the rotation by the transformation.
        assertEquals(targetRotation, tokens[0].getWindowConfiguration().getRotation());
        assertEquals(targetRotation, tokens[1].getWindowConfiguration().getRotation());

        tokens[2].applyFixedRotationTransform(mDisplayInfo, mDisplayContent.mDisplayFrames, config);
        // The tokens[1] was linked to tokens[0], this should make tokens[1] link to tokens[2].
        tokens[1].linkFixedRotationTransform(tokens[2]);

        // Assume the display doesn't rotate, the transformation will be canceled.
        tokens[0].finishFixedRotationTransform();

        // The tokens[0] should restore to the original rotation.
        assertEquals(originalRotation, tokens[0].getWindowConfiguration().getRotation());
        // The tokens[1] is linked to tokens[2], it should keep the target rotation.
        assertNotEquals(originalRotation, tokens[1].getWindowConfiguration().getRotation());

        tokens[2].finishFixedRotationTransform();
        // The rotation of tokens[1] should be restored because its linked state is finished.
        assertEquals(originalRotation, tokens[1].getWindowConfiguration().getRotation());
    }

    /**
     * Test that {@link android.view.SurfaceControl} should not be created for the
     * {@link WindowToken} which was created for {@link WindowContext} initially, the
     * surface should be create after addWindow for this token.
     */
    @Test
    public void testSurfaceCreatedForWindowToken() {
        final WindowToken fromClientToken = new WindowToken.Builder(mDisplayContent.mWmService,
                mock(IBinder.class), TYPE_APPLICATION_OVERLAY)
                .setDisplayContent(mDisplayContent)
                .setFromClientToken(true)
                .build();

        assertNull(fromClientToken.mSurfaceControl);

        newWindowBuilder("window", TYPE_APPLICATION_OVERLAY).setWindowToken(
                fromClientToken).build();
        assertNotNull(fromClientToken.mSurfaceControl);

        final WindowToken nonClientToken = new WindowToken.Builder(mDisplayContent.mWmService,
                mock(IBinder.class), TYPE_APPLICATION_OVERLAY)
                .setDisplayContent(mDisplayContent)
                .setFromClientToken(false)
                .build();
        assertNotNull(nonClientToken.mSurfaceControl);
    }

    @Test
    public void testWindowAttachedWithOptions() {
        BiFunction<Integer, Bundle, RootDisplayArea> selectFunc =
                ((DisplayAreaPolicyBuilder.Result) mDisplayContent.mDisplayAreaPolicy)
                        .mSelectRootForWindowFunc;
        spyOn(selectFunc);

        final WindowToken token1 = new WindowToken.Builder(mDisplayContent.mWmService,
                mock(IBinder.class), TYPE_STATUS_BAR)
                .setDisplayContent(mDisplayContent)
                .setPersistOnEmpty(true)
                .setOwnerCanManageAppTokens(true)
                .build();

        verify(selectFunc).apply(token1.windowType, null);

        final Bundle options = new Bundle();
        final WindowToken token2 = new WindowToken.Builder(mDisplayContent.mWmService,
                mock(IBinder.class), TYPE_STATUS_BAR)
                .setDisplayContent(mDisplayContent)
                .setPersistOnEmpty(true)
                .setOwnerCanManageAppTokens(true)
                .setOptions(options)
                .build();

        verify(selectFunc).apply(token2.windowType, options);
    }

    /**
     * Test that {@link WindowToken#setInsetsFrozen(boolean)} will set the frozen insets
     * states for its children windows and by default it shouldn't let IME window setting
     * the frozen insets state even the window of the window token is the IME layering target.
     */
    @SetupWindows(addWindows = W_INPUT_METHOD)
    @Test
    public void testSetInsetsFrozen_notAffectImeWindowState() {
        // Pre-condition: make the IME window be controlled by IME insets provider.
        mDisplayContent.getInsetsStateController()
                .getOrCreateSourceProvider(ID_IME, WindowInsets.Type.ime())
                .setWindowContainer(mDisplayContent.mInputMethodWindow, null, null);

        // Simulate an app window to be the IME layering target, assume the app window has no
        // frozen insets state by default.
        final WindowState app = newWindowBuilder("app", TYPE_APPLICATION).build();
        mDisplayContent.setImeLayeringTarget(app);
        assertNull(app.getFrozenInsetsState());
        assertTrue(app.isImeLayeringTarget());

        // Verify invoking setInsetsFrozen shouldn't let IME window setting the frozen insets state.
        app.mToken.setInsetsFrozen(true);
        assertNotNull(app.getFrozenInsetsState());
        assertNull(mDisplayContent.mInputMethodWindow.getFrozenInsetsState());
    }

    @Test
    public void testRemoveWindowToken_noAnimateExitWhenSet() {
        final TestWindowToken token = createTestWindowToken(0, mDisplayContent);
        final WindowState win = newWindowBuilder("win", TYPE_APPLICATION).setWindowToken(
                token).build();
        makeWindowVisible(win);
        assertTrue(win.isOnScreen());
        spyOn(win);
        spyOn(win.mWinAnimator);
        spyOn(win.mToken);

        // Invoking removeWindowToken with setting no window exit animation and not remove window
        // immediately. verify the window will hide without applying exit animation.
        mWm.removeWindowToken(win.mToken.token, false /* removeWindows */, false /* animateExit */,
                mDisplayContent.mDisplayId);
        verify(win).onSetAppExiting(eq(false) /* animateExit */);
        verify(win).hide(false /* doAnimation */, false /* requestAnim */);
        assertFalse(win.isOnScreen());
        verify(win.mWinAnimator, never()).applyAnimationLocked(TRANSIT_EXIT, false);
        assertTrue(win.mToken.hasChild());

        // Even though the window is being removed afterwards, it won't apply exit animation.
        win.removeIfPossible();
        verify(win.mWinAnimator, never()).applyAnimationLocked(TRANSIT_EXIT, false);
    }

    @Test
    public void testSeamlesslyRotate() {
        final SurfaceControl.Transaction t = mTransaction;
        final TestWindowToken token = createTestWindowToken(0, mDisplayContent);
        token.mLastSurfacePosition.x = 10;
        token.mLastSurfacePosition.y = 20;
        final SeamlessRotator rotator = new SeamlessRotator(ROTATION_0, ROTATION_90,
                mDisplayContent.getDisplayInfo(), false /* applyFixedTransformationHint */);
        clearInvocations(t);
        rotator.unrotate(t, token);

        // Verify surface is un-rotated.
        final Matrix matrix = new Matrix();
        // Un-rotate 90 deg.
        matrix.setRotate(270);
        // Translate it back to origin.
        matrix.postTranslate(0, mDisplayInfo.logicalWidth);
        verify(t).setMatrix(eq(token.mSurfaceControl), eq(matrix), any(float[].class));

        final float[] curSurfacePos = {token.mLastSurfacePosition.x, token.mLastSurfacePosition.y};
        matrix.mapPoints(curSurfacePos);
        verify(t).setPosition(eq(token.mSurfaceControl),
                eq(curSurfacePos[0]), eq(curSurfacePos[1]));
    }

    @Test
    @EnableFlags(Flags.FLAG_REPARENT_WINDOW_TOKEN_API)
    public void onDisplayChanged_differentDisplay_reparented() {
        final TestWindowToken token = createTestWindowToken(0, mDisplayContent);
        final DisplayContent dc = mock(DisplayContent.class);
        when(dc.getWindowToken(any())).thenReturn(null); // dc doesn't have this window token.

        token.onDisplayChanged(dc);

        verify(dc).reParentWindowToken(eq(token));
    }

    @Test
    @EnableFlags(Flags.FLAG_REPARENT_WINDOW_TOKEN_API)
    public void onDisplayChanged_samedisplay_notReparented() {

        final TestWindowToken token = createTestWindowToken(0, mDisplayContent);
        final DisplayContent dc = mock(DisplayContent.class);
        when(dc.getWindowToken(any())).thenReturn(mock(WindowToken.class));

        token.onDisplayChanged(dc);

        verify(dc, never()).reParentWindowToken(eq(token));
    }
}
