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

package com.android.server.alarm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.alarm.UserWakeupStore.BUFFER_TIME_MS;
import static com.android.server.alarm.UserWakeupStore.USER_START_TIME_DEVIATION_LIMIT_MS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.res.AssetManager;
import android.content.res.XmlResourceParser;
import android.os.Environment;
import android.os.FileUtils;
import android.os.SystemClock;
import android.util.Xml;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.BackgroundThread;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.testing.ExtendedMockitoRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;

@RunWith(AndroidJUnit4.class)
public class UserWakeupStoreTest {
    private static final int USER_ID_1 = 10;
    private static final int USER_ID_2 = 11;
    private static final int USER_ID_3 = 12;
    private static final long TEST_TIMESTAMP = 150_000;
    private static final File TEST_SYSTEM_DIR = new File(InstrumentationRegistry
            .getInstrumentation().getContext().getDataDir(), "alarmsTestDir");
    private static final File ROOT_DIR = new File(TEST_SYSTEM_DIR, UserWakeupStore.ROOT_DIR_NAME);
    private static final String USERS_FILE_NAME = "usersWithAlarmClocks.xml";
    private ExecutorService mMockExecutorService = null;
    UserWakeupStore mUserWakeupStore;

    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule = new ExtendedMockitoRule.Builder(this)
            .mockStatic(Environment.class)
            .mockStatic(BackgroundThread.class)
            .build();

    @Before
    public void setUp() {
        TEST_SYSTEM_DIR.mkdirs();
        doReturn(TEST_SYSTEM_DIR).when(Environment::getDataSystemDirectory);
        mMockExecutorService = Mockito.mock(ExecutorService.class);
        Mockito.doAnswer((invocation) -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(mMockExecutorService).execute(Mockito.any(Runnable.class));
        doReturn(mMockExecutorService).when(BackgroundThread::getExecutor);
        mUserWakeupStore = new UserWakeupStore();
        spyOn(mUserWakeupStore);
        mUserWakeupStore.init();
    }

    @After
    public void tearDown() {
        // Clean up test dir to remove persisted user files.
        FileUtils.deleteContentsAndDir(TEST_SYSTEM_DIR);
    }

    @Test
    public void testAddWakeups() {
        mUserWakeupStore.addUserWakeup(USER_ID_1, TEST_TIMESTAMP - 19_000);
        mUserWakeupStore.addUserWakeup(USER_ID_2, TEST_TIMESTAMP - 7_000);
        mUserWakeupStore.addUserWakeup(USER_ID_3, TEST_TIMESTAMP - 13_000);
        assertEquals(3, mUserWakeupStore.getUserIdsToWakeup(TEST_TIMESTAMP).length);
        ArrayList<Integer> userIds = new ArrayList<>();
        userIds.add(USER_ID_1);
        userIds.add(USER_ID_2);
        userIds.add(USER_ID_3);
        final int[] usersToWakeup = mUserWakeupStore.getUserIdsToWakeup(TEST_TIMESTAMP);
        ArrayList<Integer> userWakeups = new ArrayList<>();
        for (int i = 0; i < usersToWakeup.length; i++) {
            userWakeups.add(usersToWakeup[i]);
        }
        Collections.sort(userIds);
        Collections.sort(userWakeups);
        assertEquals(userIds, userWakeups);

        final File file = new File(ROOT_DIR, USERS_FILE_NAME);
        assertTrue(file.exists());
    }

    @Test
    public void testAddMultipleWakeupsForUser_ensureOnlyLastWakeupRemains() {
        final long finalAlarmTime = TEST_TIMESTAMP - 13_000;
        mUserWakeupStore.addUserWakeup(USER_ID_1, TEST_TIMESTAMP - 29_000);
        mUserWakeupStore.addUserWakeup(USER_ID_1, TEST_TIMESTAMP - 7_000);
        mUserWakeupStore.addUserWakeup(USER_ID_1, finalAlarmTime);
        assertEquals(1, mUserWakeupStore.getUserIdsToWakeup(TEST_TIMESTAMP).length);
        final long alarmTime = mUserWakeupStore.getWakeupTimeForUser(USER_ID_1)
                + BUFFER_TIME_MS;
        assertTrue(finalAlarmTime + USER_START_TIME_DEVIATION_LIMIT_MS >= alarmTime);
        assertTrue(finalAlarmTime - USER_START_TIME_DEVIATION_LIMIT_MS <= alarmTime);
    }

    @Test
    public void testRemoveWakeupForUser_negativeWakeupTimeIsReturnedForUser() {
        mUserWakeupStore.addUserWakeup(USER_ID_1, TEST_TIMESTAMP - 19_000);
        mUserWakeupStore.addUserWakeup(USER_ID_2, TEST_TIMESTAMP - 7_000);
        mUserWakeupStore.addUserWakeup(USER_ID_3, TEST_TIMESTAMP - 13_000);
        assertEquals(3, mUserWakeupStore.getUserIdsToWakeup(TEST_TIMESTAMP).length);
        mUserWakeupStore.removeUserWakeup(USER_ID_3);
        assertEquals(-1, mUserWakeupStore.getWakeupTimeForUser(USER_ID_3));
        assertTrue(mUserWakeupStore.getWakeupTimeForUser(USER_ID_2) > 0);
    }

    @Test
    public void testOnUserStarting_userIsRemovedFromTheStore() {
        mUserWakeupStore.addUserWakeup(USER_ID_1, TEST_TIMESTAMP - 19_000);
        mUserWakeupStore.addUserWakeup(USER_ID_2, TEST_TIMESTAMP - 7_000);
        mUserWakeupStore.addUserWakeup(USER_ID_3, TEST_TIMESTAMP - 13_000);
        assertEquals(3, mUserWakeupStore.getUserIdsToWakeup(TEST_TIMESTAMP).length);
        mUserWakeupStore.onUserStarting(USER_ID_3);
        // getWakeupTimeForUser returns negative wakeup time if there is no entry for user.
        assertEquals(-1, mUserWakeupStore.getWakeupTimeForUser(USER_ID_3));
        assertEquals(2, mUserWakeupStore.getUserIdsToWakeup(TEST_TIMESTAMP).length);
    }

    @Test
    public void testGetNextUserWakeup() {
        mUserWakeupStore.addUserWakeup(USER_ID_1, TEST_TIMESTAMP - 19_000);
        mUserWakeupStore.addUserWakeup(USER_ID_2, TEST_TIMESTAMP - 3_000);
        mUserWakeupStore.addUserWakeup(USER_ID_3, TEST_TIMESTAMP - 13_000);
        assertEquals(mUserWakeupStore.getNextWakeupTime(),
                mUserWakeupStore.getWakeupTimeForUser(USER_ID_1));
        mUserWakeupStore.removeUserWakeup(USER_ID_1);
        assertEquals(mUserWakeupStore.getNextWakeupTime(),
                mUserWakeupStore.getWakeupTimeForUser(USER_ID_3));
    }

    @Test
    public void testWriteAndReadUsersFromFile() {
        mUserWakeupStore.addUserWakeup(USER_ID_1, TEST_TIMESTAMP - 19_000);
        mUserWakeupStore.addUserWakeup(USER_ID_2, TEST_TIMESTAMP - 7_000);
        mUserWakeupStore.addUserWakeup(USER_ID_3, TEST_TIMESTAMP - 13_000);
        assertEquals(3, mUserWakeupStore.getUserIdsToWakeup(TEST_TIMESTAMP).length);
        mUserWakeupStore.init();
        final long realtime = SystemClock.elapsedRealtime();
        assertEquals(0, mUserWakeupStore.getUserIdsToWakeup(TEST_TIMESTAMP).length);
        assertTrue(mUserWakeupStore.getWakeupTimeForUser(USER_ID_2) > realtime);
        assertTrue(mUserWakeupStore.getWakeupTimeForUser(USER_ID_1)
                < mUserWakeupStore.getWakeupTimeForUser(USER_ID_3));
        assertTrue(mUserWakeupStore.getWakeupTimeForUser(USER_ID_3)
                < mUserWakeupStore.getWakeupTimeForUser(USER_ID_2));
        assertTrue(mUserWakeupStore.getWakeupTimeForUser(USER_ID_1) - realtime
                < BUFFER_TIME_MS + USER_START_TIME_DEVIATION_LIMIT_MS);
        assertTrue(mUserWakeupStore.getWakeupTimeForUser(USER_ID_3) - realtime
                < 2 * BUFFER_TIME_MS + USER_START_TIME_DEVIATION_LIMIT_MS);
        assertTrue(mUserWakeupStore.getWakeupTimeForUser(USER_ID_2) - realtime
                < 3 * BUFFER_TIME_MS + USER_START_TIME_DEVIATION_LIMIT_MS);
    }

    @Test
    public void testWriteWakeups_xmlIsOrdered() {
        mUserWakeupStore.addUserWakeup(USER_ID_1, TEST_TIMESTAMP - 19_000);
        mUserWakeupStore.addUserWakeup(USER_ID_2, TEST_TIMESTAMP - 7_000);
        assertFileContentsMatchExpectedXml("res/xml/expectedUserWakeupList_1.xml");
    }

    @Test
    public void testWriteWakeups_containsOneEntryPerUser() {
        mUserWakeupStore.addUserWakeup(USER_ID_1, TEST_TIMESTAMP - 19_000);
        mUserWakeupStore.addUserWakeup(USER_ID_1, TEST_TIMESTAMP - 7_000);
        assertFileContentsMatchExpectedXml("res/xml/expectedUserWakeupList_2.xml");
    }

    private static void assertFileContentsMatchExpectedXml(String expectedContentsFile) {
        final File actual = new File(ROOT_DIR, USERS_FILE_NAME);
        AssetManager assetManager =
                InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        try (FileInputStream actualFis = new FileInputStream(actual)) {
            final TypedXmlPullParser actualParser = Xml.resolvePullParser(actualFis);
            final XmlResourceParser expectedParser = assetManager.openXmlResourceParser(
                    expectedContentsFile);
            for (XmlUtils.nextElement(expectedParser), XmlUtils.nextElement(actualParser);
                    actualParser.getEventType() != XmlResourceParser.END_DOCUMENT
                            && expectedParser.getEventType() != XmlResourceParser.END_DOCUMENT;
                    XmlUtils.nextElement(actualParser), XmlUtils.nextElement(expectedParser)) {
                assertEquals("Event types differ ", expectedParser.getEventType(),
                        actualParser.getEventType());
                for (int i = 0; i < expectedParser.getAttributeCount(); i++) {
                    assertEquals("Attribute names differ at index " + i,
                            expectedParser.getAttributeName(i), actualParser.getAttributeName(i));
                    assertEquals("Attribute values differ at index " + i,
                            expectedParser.getAttributeValue(i), actualParser.getAttributeValue(i));
                }
            }
            // Ensure they are both at the end of document
            assertEquals("One of the parsers has not reached the EOF",
                    expectedParser.getEventType(), actualParser.getEventType());
        } catch (IOException | XmlPullParserException e) {
            fail(e.getLocalizedMessage());
        }
    }
}
