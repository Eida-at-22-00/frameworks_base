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

package com.android.server.power.stats;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.Process;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.os.BatteryStatsHistoryIterator;
import com.android.internal.os.MonotonicClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

@RunWith(AndroidJUnit4.class)
@SmallTest
@SuppressWarnings("GuardedBy")
public class BatteryStatsHistoryIteratorTest {
    private static final int APP_UID = Process.FIRST_APPLICATION_UID + 42;

    private final MockClock mMockClock = new MockClock();
    private MockBatteryStatsImpl mBatteryStats;
    private final Random mRandom = new Random();

    @Before
    public void setup() {
        final File historyDir = createTemporaryDirectory(getClass().getSimpleName());
        mMockClock.currentTime = 3000;
        mBatteryStats = new MockBatteryStatsImpl(mMockClock, historyDir);
        mBatteryStats.setRecordAllHistoryLocked(true);
        mBatteryStats.forceRecordAllHistory();
        mBatteryStats.setNoAutoReset(true);
    }

    /**
     * Creates a unique new temporary directory under "java.io.tmpdir".
     */
    private File createTemporaryDirectory(String prefix) {
        while (true) {
            String candidateName = prefix + mRandom.nextInt();
            File result = new File(System.getProperty("java.io.tmpdir"), candidateName);
            if (result.mkdir()) {
                return result;
            }
        }
    }

    @Test
    public void unconstrainedIteration() {
        prepareHistory();

        final BatteryStatsHistoryIterator iterator =
                mBatteryStats.iterateBatteryStatsHistory(0, MonotonicClock.UNDEFINED);

        BatteryStats.HistoryItem item;

        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.cmd).isEqualTo(BatteryStats.HistoryItem.CMD_RESET);

        assertThat(item = iterator.next()).isNotNull();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_UPDATE, BatteryStats.HistoryItem.EVENT_NONE,
                null, 0, -1, 3_600_000, 90, 1_000_000);

        assertThat(item = iterator.next()).isNotNull();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_UPDATE, BatteryStats.HistoryItem.EVENT_NONE,
                null, 0, 3700, 2_400_000, 80, 2_000_000);

        assertThat(item = iterator.next()).isNotNull();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_UPDATE,
                BatteryStats.HistoryItem.EVENT_ALARM | BatteryStats.HistoryItem.EVENT_FLAG_START,
                "foo", APP_UID, 3700, 2_400_000, 80, 3_000_000);

        assertThat(item = iterator.next()).isNotNull();
        assertHistoryItem(item,
                BatteryStats.HistoryItem.CMD_UPDATE,
                BatteryStats.HistoryItem.EVENT_ALARM | BatteryStats.HistoryItem.EVENT_FLAG_FINISH,
                "foo", APP_UID, 3700, 2_400_000, 80, 3_001_000);

        assertThat(iterator.hasNext()).isFalse();
        assertThat(iterator.next()).isNull();
    }

    @Test
    public void constrainedIteration() {
        prepareHistory();

        // Initial time is 1000_000
        assertIncludedEvents(mBatteryStats.iterateBatteryStatsHistory(0, MonotonicClock.UNDEFINED),
                1000_000L, 1000_000L, 2000_000L, 3000_000L, 3001_000L);
        assertIncludedEvents(
                mBatteryStats.iterateBatteryStatsHistory(2000_000, MonotonicClock.UNDEFINED),
                2000_000L, 3000_000L, 3001_000L);
        assertIncludedEvents(mBatteryStats.iterateBatteryStatsHistory(0, 3000_000L),
                1000_000L, 1000_000L, 2000_000L);
        assertIncludedEvents(mBatteryStats.iterateBatteryStatsHistory(1003_000L, 2004_000L),
                2000_000L);
    }

    private void prepareHistory() {
        mMockClock.realtime = 1000;
        mMockClock.uptime = 1000;
        mMockClock.currentTime = 3000;

        mBatteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING,
                100, /* plugType */ 0, 90, 72, -1, 3_600_000, 4_000_000, 0, 1_000_000,
                1_000_000, 1_000_000);
        mBatteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING,
                100, /* plugType */ 0, 80, 72, 3700, 2_400_000, 4_000_000, 0, 2_000_000,
                2_000_000, 2_000_000);
        mBatteryStats.noteAlarmStartLocked("foo", null, APP_UID, 3_000_000, 2_000_000);
        mBatteryStats.noteAlarmFinishLocked("foo", null, APP_UID, 3_001_000, 2_001_000);
    }

    private void assertIncludedEvents(BatteryStatsHistoryIterator iterator,
            Long... expectedTimestamps) {
        ArrayList<Long> actualTimestamps = new ArrayList<>();
        while (iterator.hasNext()) {
            BatteryStats.HistoryItem item = iterator.next();
            actualTimestamps.add(item.time);
        }
        assertThat(actualTimestamps).isEqualTo(Arrays.asList(expectedTimestamps));
    }

    // Test history that spans multiple buffers and uses more than 32k different strings.
    @Test
    public void tagsLongHistory() {
        mMockClock.currentTime = 1_000_000;
        mMockClock.realtime = 1_000_000;
        mMockClock.uptime = 1_000_000;

        mBatteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING,
                100, /* plugType */ 0, 90, 72, 3700, 3_600_000, 4_000_000, 0, mMockClock.realtime,
                mMockClock.uptime, mMockClock.currentTime);

        // More than 32k strings
        final int eventCount = 0x7FFF + 100;
        for (int i = 0; i < eventCount; i++) {
            // Names repeat in order to verify de-duping of identical history tags.
            String name = "a" + (i % 10);
            mMockClock.currentTime += 1_000_000;
            mMockClock.realtime += 1_000_000;
            mMockClock.uptime += 1_000_000;
            mBatteryStats.noteAlarmStartLocked(name, null, APP_UID,
                    mMockClock.realtime, mMockClock.uptime);
            mMockClock.currentTime += 500_000;
            mMockClock.realtime += 500_000;
            mMockClock.uptime += 500_000;
            mBatteryStats.noteAlarmFinishLocked(name, null, APP_UID,
                    mMockClock.realtime, mMockClock.uptime);
        }

        final BatteryStatsHistoryIterator iterator =
                mBatteryStats.iterateBatteryStatsHistory(0, MonotonicClock.UNDEFINED);

        BatteryStats.HistoryItem item;
        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.cmd).isEqualTo((int) BatteryStats.HistoryItem.CMD_RESET);
        assertThat(item.eventCode).isEqualTo(BatteryStats.HistoryItem.EVENT_NONE);
        assertThat(item.eventTag).isNull();

        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.cmd).isEqualTo((int) BatteryStats.HistoryItem.CMD_UPDATE);
        assertThat(item.eventCode).isEqualTo(BatteryStats.HistoryItem.EVENT_NONE);
        assertThat(item.eventTag).isNull();
        assertThat(item.time).isEqualTo(1_000_000);

        for (int i = 0; i < eventCount; i++) {
            String name = "a" + (i % 10);
            do {
                assertThat(item = iterator.next()).isNotNull();
                // Skip a blank event inserted at the start of every buffer
            } while (item.cmd != BatteryStats.HistoryItem.CMD_UPDATE
                    || item.eventCode == BatteryStats.HistoryItem.EVENT_NONE);

            assertThat(item.eventCode).isEqualTo(BatteryStats.HistoryItem.EVENT_ALARM
                    | BatteryStats.HistoryItem.EVENT_FLAG_START);
            assertThat(item.eventTag.string).isEqualTo(name);

            do {
                assertThat(item = iterator.next()).isNotNull();
            } while (item.cmd != BatteryStats.HistoryItem.CMD_UPDATE
                    || item.eventCode == BatteryStats.HistoryItem.EVENT_NONE);

            assertThat(item.eventCode).isEqualTo(BatteryStats.HistoryItem.EVENT_ALARM
                    | BatteryStats.HistoryItem.EVENT_FLAG_FINISH);
            assertThat(item.eventTag.string).isEqualTo(name);
        }

        assertThat(iterator.hasNext()).isFalse();
        assertThat(iterator.next()).isNull();
    }

    @Test
    public void cpuSuspendHistoryEvents() {
        mBatteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING,
                100, /* plugType */ 0, 90, 72, 3700, 3_600_000, 4_000_000, 0,
                1_000_000, 1_000_000, 1_000_000);

        // Device was suspended for 3_000 seconds, note the difference in elapsed time and uptime
        mBatteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING,
                100, /* plugType */ 0, 80, 72, 3700, 2_400_000, 4_000_000, 0,
                5_000_000, 2_000_000, 5_000_000);

        // Battery level is unchanged, so we don't write battery level details in history
        mBatteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING,
                100, /* plugType */ 0, 80, 72, 3700, 2_400_000, 4_000_000, 0,
                5_500_000, 2_500_000, 5_000_000);

        // Not a battery state change event, so details are not written
        mBatteryStats.noteAlarmStartLocked("wakeup", null, APP_UID, 6_000_000, 3_000_000);

        // Battery level drops, so we write the accumulated battery level details
        mBatteryStats.setBatteryStateLocked(BatteryManager.BATTERY_STATUS_DISCHARGING,
                100, /* plugType */ 0, 70, 72, 3700, 2_000_000, 4_000_000, 0,
                7_000_000, 4_000_000, 6_000_000);

        final BatteryStatsHistoryIterator iterator =
                mBatteryStats.iterateBatteryStatsHistory(0, MonotonicClock.UNDEFINED);

        BatteryStats.HistoryItem item;
        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.cmd).isEqualTo((int) BatteryStats.HistoryItem.CMD_RESET);

        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.batteryLevel).isEqualTo(90);
        assertThat(item.states & BatteryStats.HistoryItem.STATE_CPU_RUNNING_FLAG).isNotEqualTo(0);

        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.batteryLevel).isEqualTo(90);
        assertThat(item.states & BatteryStats.HistoryItem.STATE_CPU_RUNNING_FLAG).isEqualTo(0);

        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.batteryLevel).isEqualTo(80);
        assertThat(item.states & BatteryStats.HistoryItem.STATE_CPU_RUNNING_FLAG).isNotEqualTo(0);

        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.batteryLevel).isEqualTo(80);
        assertThat(item.eventCode).isEqualTo(BatteryStats.HistoryItem.EVENT_ALARM_START);
        assertThat(item.states & BatteryStats.HistoryItem.STATE_CPU_RUNNING_FLAG).isNotEqualTo(0);

        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.batteryLevel).isEqualTo(70);
        assertThat(item.states & BatteryStats.HistoryItem.STATE_CPU_RUNNING_FLAG).isNotEqualTo(0);

        assertThat(iterator.next()).isNull();
    }

    @Test
    public void batteryLevelAccuracy() {
        // Check that battery values are recorded correctly throughout their valid ranges:
        // - Battery level range: [0, 100]
        // - Voltage and temperature range: [-2**15, 2**15-1] (note: exceeds physical requirements)

        int i16Min = Short.MIN_VALUE;
        int i16Max = Short.MAX_VALUE;
        int[][] testValues = {
                // { level, temperature, voltage }

                // Min/max values
                {   0,      0,      0 },
                {   0, i16Min, i16Min },
                { 100, i16Max, i16Max },

                // Level changes
                {  0, 0, 0 },
                { 12, 0, 0 },
                { 90, 0, 0 },
                { 34, 0, 0 },
                { 78, 0, 0 },
                { 56, 0, 0 },

                // Temperature changes
                { 0,           0, 0 },
                { 0, i16Max,      0 }, // Large change to max
                { 0, i16Max - 12, 0 }, // Small change near max
                { 0, i16Max - 56, 0 }, // Small change near max
                { 0, i16Max - 34, 0 }, // Small change near max
                { 0,           0, 0 }, // Large change to 0
                { 0,          12, 0 }, // Small change near 0
                { 0,         -34, 0 }, // Small change near 0
                { 0,          56, 0 }, // Small change near 0
                { 0,         -78, 0 }, // Small change near 0
                { 0, i16Min,      0 }, // Large change to min
                { 0, i16Min + 12, 0 }, // Small change near min
                { 0, i16Min + 56, 0 }, // Small change near min
                { 0, i16Min + 34, 0 }, // Small change near min

                // Voltage changes
                { 0, 0,            0 },
                { 0, 0, i16Max       }, // Large change to max
                { 0, 0, i16Max - 120 }, // Small change near max
                { 0, 0, i16Max - 560 }, // Small change near max
                { 0, 0, i16Max - 340 }, // Small change near max
                { 0, 0,            0 }, // Large change to 0
                { 0, 0,          120 }, // Small change near 0
                { 0, 0,         -340 }, // Small change near 0
                { 0, 0,          560 }, // Small change near 0
                { 0, 0,         -780 }, // Small change near 0
                { 0, 0, i16Min       }, // Large change to min
                { 0, 0, i16Min + 120 }, // Small change near min
                { 0, 0, i16Min + 560 }, // Small change near min
                { 0, 0, i16Min + 340 }, // Small change near min
        };

        for (int[] val : testValues) {
            mBatteryStats.setBatteryStateLocked(
                    BatteryManager.BATTERY_STATUS_DISCHARGING,
                    100, 0, val[0], val[1], val[2], 1000_000, 1000_000, 0, 0, 0, 0);
        }

        final BatteryStatsHistoryIterator iterator =
                mBatteryStats.iterateBatteryStatsHistory(0, MonotonicClock.UNDEFINED);

        BatteryStats.HistoryItem item;
        assertThat(item = iterator.next()).isNotNull();
        assertThat(item.cmd).isEqualTo((int) BatteryStats.HistoryItem.CMD_RESET);

        for (int i = 0; i < testValues.length; i++) {
            int[] val = testValues[i];
            String msg = String.format("Incorrect battery value returned at index %d:", i);
            assertThat(item = iterator.next()).isNotNull();
            assertWithMessage(msg).that(item.batteryLevel).isEqualTo(val[0]);
            assertWithMessage(msg).that(item.batteryTemperature).isEqualTo(val[1]);
            assertWithMessage(msg).that(item.batteryVoltage).isEqualTo(val[2]);
        }

        assertThat(item = iterator.next()).isNull();
    }

    private void assertHistoryItem(BatteryStats.HistoryItem item, int command, int eventCode,
            String tag, int uid, int voltageMv, int batteryChargeUah, int batteryLevel,
            long elapsedTimeMs) {
        assertThat(item.cmd).isEqualTo(command);
        assertThat(item.eventCode).isEqualTo(eventCode);
        if (tag == null) {
            assertThat(item.eventTag).isNull();
        } else {
            assertThat(item.eventTag.string).isEqualTo(tag);
            assertThat(item.eventTag.uid).isEqualTo(uid);
        }
        assertThat((int) item.batteryVoltage).isEqualTo(voltageMv);
        assertThat(item.batteryChargeUah).isEqualTo(batteryChargeUah);
        assertThat(item.batteryLevel).isEqualTo(batteryLevel);

        assertThat(item.time).isEqualTo(elapsedTimeMs);
    }
}
