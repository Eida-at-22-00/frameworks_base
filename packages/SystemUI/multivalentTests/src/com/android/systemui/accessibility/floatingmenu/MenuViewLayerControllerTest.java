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

package com.android.systemui.accessibility.floatingmenu;

import static android.view.WindowInsets.Type.displayCutout;
import static android.view.WindowInsets.Type.systemBars;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.settingslib.bluetooth.HearingAidDeviceManager;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link MenuViewLayerController}. */
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class MenuViewLayerControllerTest extends SysuiTestCase {
    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private WindowManager mWindowManager;

    @Mock
    private AccessibilityManager mAccessibilityManager;
    @Mock
    private HearingAidDeviceManager mHearingAidDeviceManager;

    @Mock
    private SecureSettings mSecureSettings;

    @Mock
    private WindowMetrics mWindowMetrics;

    private MenuViewLayerController mMenuViewLayerController;

    @Before
    public void setUp() throws Exception {
        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        doAnswer(invocation -> wm.getMaximumWindowMetrics()).when(
                mWindowManager).getMaximumWindowMetrics();
        mContext.addMockSystemService(Context.WINDOW_SERVICE, mWindowManager);
        when(mWindowManager.getCurrentWindowMetrics()).thenReturn(mWindowMetrics);
        when(mWindowMetrics.getBounds()).thenReturn(new Rect(0, 0, 1080, 2340));
        when(mWindowMetrics.getWindowInsets()).thenReturn(stubDisplayInsets());
        mMenuViewLayerController = new MenuViewLayerController(mContext, mWindowManager,
                mAccessibilityManager, mSecureSettings, mock(NavigationModeController.class),
                mHearingAidDeviceManager);
    }

    @Test
    public void show_shouldAddViewToWindow() {
        mMenuViewLayerController.show();

        verify(mWindowManager).addView(any(View.class), any(ViewGroup.LayoutParams.class));
    }

    @Test
    public void hide_menuIsShowing_removeViewFromWindow() {
        mMenuViewLayerController.show();

        mMenuViewLayerController.hide();

        verify(mWindowManager).removeView(any(View.class));
    }

    private WindowInsets stubDisplayInsets() {
        final int stubStatusBarHeight = 118;
        final int stubNavigationBarHeight = 125;
        return new WindowInsets.Builder()
                .setVisible(systemBars() | displayCutout(), true)
                .setInsets(systemBars() | displayCutout(),
                        Insets.of(0, stubStatusBarHeight, 0, stubNavigationBarHeight))
                .build();
    }
}
