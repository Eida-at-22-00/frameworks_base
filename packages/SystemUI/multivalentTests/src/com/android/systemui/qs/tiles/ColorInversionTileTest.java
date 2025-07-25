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

package com.android.systemui.qs.tiles;

import static android.platform.test.flag.junit.FlagsParameterization.allCombinationsOf;

import static com.android.systemui.Flags.FLAG_QS_CUSTOM_TILE_CLICK_GUARANTEED_BUG_FIX;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.os.Handler;
import android.platform.test.flag.junit.FlagsParameterization;
import android.provider.Settings;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.flags.QsInCompose;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.shared.QSSettingsPackageRepository;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.List;

@RunWith(ParameterizedAndroidJunit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class ColorInversionTileTest extends SysuiTestCase {
    private static final String SETTINGS_PACKAGE_NAME = "com.android.settings";
    private static final Integer COLOR_INVERSION_DISABLED = 0;
    private static final Integer COLOR_INVERSION_ENABLED = 1;

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getParams() {
        return allCombinationsOf(FLAG_QS_CUSTOM_TILE_CLICK_GUARANTEED_BUG_FIX);
    }

    @Mock
    private QSHost mHost;
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private QSLogger mQSLogger;
    @Mock
    private QsEventLogger mUiEventLogger;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private QSSettingsPackageRepository mQSSettingsPackageRepository;

    private TestableLooper mTestableLooper;
    private SecureSettings mSecureSettings;
    private ColorInversionTile mTile;

    public ColorInversionTileTest(FlagsParameterization flags) {
        super();
        mSetFlagsRule.setFlagsParameterization(flags);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mSecureSettings = new FakeSettings();
        mTestableLooper = TestableLooper.get(this);

        when(mHost.getContext()).thenReturn(mContext);
        when(mQSSettingsPackageRepository.getSettingsPackageName())
                .thenReturn(SETTINGS_PACKAGE_NAME);

        mTile = new ColorInversionTile(
                mHost,
                mUiEventLogger,
                mTestableLooper.getLooper(),
                new Handler(mTestableLooper.getLooper()),
                new FalsingManagerFake(),
                mMetricsLogger,
                mStatusBarStateController,
                mActivityStarter,
                mQSLogger,
                mUserTracker,
                mSecureSettings,
                mQSSettingsPackageRepository
        );

        mTile.initialize();
        mTestableLooper.processAllMessages();
    }

    @After
    public void tearDown() {
        mTile.destroy();
        mTestableLooper.processAllMessages();
    }

    @Test
    public void longClick_expectedAction() {
        final ArgumentCaptor<Intent> IntentCaptor = ArgumentCaptor.forClass(Intent.class);

        mTile.longClick(/* view= */ null);
        mTestableLooper.processAllMessages();

        verify(mActivityStarter).postStartActivityDismissingKeyguard(IntentCaptor.capture(),
                anyInt(), any());
        assertThat(IntentCaptor.getValue().getAction()).isEqualTo(
                Settings.ACTION_COLOR_INVERSION_SETTINGS);
        assertThat(IntentCaptor.getValue().getPackage()).isEqualTo(SETTINGS_PACKAGE_NAME);
    }

    @Test
    public void testIcon_whenColorInversionDisabled_isOffState() {
        QSTile.BooleanState state = new QSTile.BooleanState();

        mTile.handleUpdateState(state, COLOR_INVERSION_DISABLED);

        assertThat(state.icon)
                .isEqualTo(createExpectedIcon(R.drawable.qs_invert_colors_icon_off));
    }

    @Test
    public void testIcon_whenColorInversionEnabled_isOnState() {
        QSTile.BooleanState state = new QSTile.BooleanState();

        mTile.handleUpdateState(state, COLOR_INVERSION_ENABLED);

        assertThat(state.icon)
                .isEqualTo(createExpectedIcon(R.drawable.qs_invert_colors_icon_on));
    }

    private QSTile.Icon createExpectedIcon(int resId) {
        if (QsInCompose.isEnabled()) {
            return new QSTileImpl.DrawableIconWithRes(mContext.getDrawable(resId), resId);
        } else {
            return QSTileImpl.ResourceIcon.get(resId);
        }
    }
}
