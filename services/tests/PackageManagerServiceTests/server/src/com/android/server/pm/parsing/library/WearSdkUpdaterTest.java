/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.pm.parsing.library;

import android.os.Build;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.internal.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.pkg.AndroidPackage;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Test for {@link WearSdkUpdater}
 */
@Presubmit
@SmallTest
@RunWith(JUnit4.class)
public class WearSdkUpdaterTest extends PackageSharedLibraryUpdaterTest {

    @Test
    public void otherUsesLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.BAKLAVA)
                .addUsesLibrary("other")
                .addUsesOptionalLibrary("optional")
                .addUsesLibrary("wear-sdk")
                .hideAsParsed());
        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.BAKLAVA)
                .addUsesLibrary("other")
                .addUsesOptionalLibrary("optional")
                .hideAsParsed())
                .hideAsFinal();
        checkBackwardsCompatibility(before, after, false);
    }

    @Test
    public void in_usesLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesLibrary("wear-sdk")
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .hideAsParsed())
                .hideAsFinal();

        checkBackwardsCompatibility(before, after, false);
    }

    @Test
    public void in_usesOptionalLibraries() {
        ParsedPackage before = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .addUsesOptionalLibrary("wear-sdk")
                .hideAsParsed());

        AndroidPackage after = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME)
                .setTargetSdkVersion(Build.VERSION_CODES.CUR_DEVELOPMENT)
                .hideAsParsed())
                .hideAsFinal();

        checkBackwardsCompatibility(before, after, false);
    }

    private void checkBackwardsCompatibility(ParsedPackage before, AndroidPackage after,
            boolean isSystemApp) {
        checkBackwardsCompatibility(before, after, isSystemApp, WearSdkUpdater::new);
    }
}
