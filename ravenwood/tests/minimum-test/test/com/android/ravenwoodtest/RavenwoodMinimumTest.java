/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.ravenwoodtest;

import android.platform.test.annotations.DisabledOnRavenwood;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RavenwoodMinimumTest {
    @Test
    public void testSimple() {
        Assert.assertTrue(android.os.Process.isApplicationUid(android.os.Process.myUid()));
    }

    @Test
    public void testAssumeNot() {
        Assume.assumeFalse(android.os.Process.isApplicationUid(android.os.Process.myUid()));
    }

    @Test
    @DisabledOnRavenwood
    public void testIgnored() {
        throw new RuntimeException("Shouldn't be executed under ravenwood");
    }

    @Test
    public void testIgnored$noRavenwood() {
        throw new RuntimeException("Shouldn't be executed under ravenwood");
    }
}
