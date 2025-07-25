package com.android.systemui.qs.tiles

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import android.platform.test.flag.junit.FlagsParameterization.allCombinationsOf
import android.service.quicksettings.Tile
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.settingslib.Utils
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.systemui.SysuiTestCase
import com.android.systemui.bluetooth.qsdialog.BluetoothDetailsContentViewModel
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.qs.TileDetailsViewModel
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.flags.QSComposeFragment
import com.android.systemui.qs.flags.QsDetailedView
import com.android.systemui.qs.flags.QsInCompose.isEnabled
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tileimpl.QSTileImpl.DrawableIconWithRes
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.BluetoothController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@RunWith(ParameterizedAndroidJunit4::class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
class BluetoothTileTest(flags: FlagsParameterization) : SysuiTestCase() {

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Mock private lateinit var qsLogger: QSLogger
    @Mock private lateinit var qsHost: QSHost
    @Mock private lateinit var metricsLogger: MetricsLogger
    private val falsingManager = FalsingManagerFake()
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var bluetoothController: BluetoothController
    @Mock private lateinit var uiEventLogger: QsEventLogger
    @Mock private lateinit var featureFlags: FeatureFlagsClassic
    @Mock private lateinit var bluetoothDetailsContentViewModel: BluetoothDetailsContentViewModel
    @Mock private lateinit var clickJob: Job
    private lateinit var testableLooper: TestableLooper
    private lateinit var tile: FakeBluetoothTile

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)

        whenever(qsHost.context).thenReturn(mContext)
        whenever(bluetoothController.canConfigBluetooth()).thenReturn(true)

        tile =
            FakeBluetoothTile(
                qsHost,
                uiEventLogger,
                testableLooper.looper,
                Handler(testableLooper.looper),
                falsingManager,
                metricsLogger,
                statusBarStateController,
                activityStarter,
                qsLogger,
                bluetoothController,
                featureFlags,
            ) {
                bluetoothDetailsContentViewModel
            }

        tile.initialize()
        testableLooper.processAllMessages()
    }

    @After
    fun tearDown() {
        tile.destroy()
        testableLooper.processAllMessages()
    }

    @Test
    fun testRestrictionChecked() {
        tile.refreshState()
        testableLooper.processAllMessages()

        assertThat(tile.restrictionChecked).isEqualTo(UserManager.DISALLOW_BLUETOOTH)
    }

    @Test
    fun testIcon_whenDisabled_isOffState() {
        val state = QSTile.BooleanState()
        disableBluetooth()

        tile.handleUpdateState(state, /* arg= */ null)

        assertThat(state.icon).isEqualTo(createExpectedIcon(R.drawable.qs_bluetooth_icon_off))
    }

    @Test
    fun testIcon_whenDisconnected_isOffState() {
        val state = QSTile.BooleanState()
        enableBluetooth()
        setBluetoothDisconnected()

        tile.handleUpdateState(state, /* arg= */ null)

        assertThat(state.icon).isEqualTo(createExpectedIcon(R.drawable.qs_bluetooth_icon_off))
    }

    @Test
    fun testIcon_whenConnected_isOnState() {
        val state = QSTile.BooleanState()
        enableBluetooth()
        setBluetoothConnected()

        tile.handleUpdateState(state, /* arg= */ null)

        assertThat(state.icon).isEqualTo(createExpectedIcon(R.drawable.qs_bluetooth_icon_on))
    }

    @Test
    fun testIcon_whenConnecting_isSearchState() {
        val state = QSTile.BooleanState()
        enableBluetooth()
        setBluetoothConnecting()

        tile.handleUpdateState(state, /* arg= */ null)

        assertThat(state.icon).isEqualTo(createExpectedIcon(R.drawable.qs_bluetooth_icon_search))
    }

    @Test
    fun testSecondaryLabel_whenBatteryMetadataAvailable_isMetadataBatteryLevelState() {
        val cachedDevice = mock<CachedBluetoothDevice>()
        val state = QSTile.BooleanState()
        listenToDeviceMetadata(state, cachedDevice, 50)

        tile.handleUpdateState(state, /* arg= */ null)

        assertThat(state.secondaryLabel)
            .isEqualTo(
                mContext.getString(
                    R.string.quick_settings_bluetooth_secondary_label_battery_level,
                    Utils.formatPercentage(50),
                )
            )
        verify(bluetoothController).addOnMetadataChangedListener(eq(cachedDevice), any(), any())
    }

    @Test
    fun testSecondaryLabel_whenBatteryMetadataUnavailable_isBluetoothBatteryLevelState() {
        val state = QSTile.BooleanState()
        val cachedDevice = mock<CachedBluetoothDevice>()
        listenToDeviceMetadata(state, cachedDevice, 50)
        val cachedDevice2 = mock<CachedBluetoothDevice>()
        val btDevice = mock<BluetoothDevice>()
        whenever(cachedDevice2.device).thenReturn(btDevice)
        whenever(btDevice.getMetadata(BluetoothDevice.METADATA_MAIN_BATTERY)).thenReturn(null)
        whenever(cachedDevice2.minBatteryLevelWithMemberDevices).thenReturn(25)
        addConnectedDevice(cachedDevice2)

        tile.handleUpdateState(state, /* arg= */ null)

        assertThat(state.secondaryLabel)
            .isEqualTo(
                mContext.getString(
                    R.string.quick_settings_bluetooth_secondary_label_battery_level,
                    Utils.formatPercentage(25),
                )
            )
        verify(bluetoothController, times(1))
            .removeOnMetadataChangedListener(eq(cachedDevice), any())
    }

    @Test
    @DisableFlags(QsDetailedView.FLAG_NAME)
    fun handleClick_hasSatelliteFeatureButNoQsTileDialogAndClickIsProcessing_doNothing() {
        `when`(featureFlags.isEnabled(com.android.systemui.flags.Flags.BLUETOOTH_QS_TILE_DIALOG))
            .thenReturn(false)
        `when`(clickJob.isCompleted).thenReturn(false)
        tile.mClickJob = clickJob

        tile.handleClick(null)

        verify(bluetoothController, times(0)).setBluetoothEnabled(any())
    }

    @Test
    @EnableFlags(QsDetailedView.FLAG_NAME)
    fun handleClick_hasSatelliteFeatureAndQsDetailedViewIsEnabledAndClickIsProcessing_doNothing() {
        `when`(featureFlags.isEnabled(com.android.systemui.flags.Flags.BLUETOOTH_QS_TILE_DIALOG))
            .thenReturn(false)
        `when`(clickJob.isCompleted).thenReturn(false)
        tile.mClickJob = clickJob
        var currentModel: TileDetailsViewModel? = null

        tile.getDetailsViewModel { model: TileDetailsViewModel? -> currentModel = model }

        // Click is not allowed.
        assertThat(currentModel).isEqualTo(null)
    }

    @Test
    fun testMetadataListener_whenDisconnected_isUnregistered() {
        val state = QSTile.BooleanState()
        val cachedDevice = mock<CachedBluetoothDevice>()
        listenToDeviceMetadata(state, cachedDevice, 50)
        disableBluetooth()

        tile.handleUpdateState(state, null)

        verify(bluetoothController, times(1))
            .removeOnMetadataChangedListener(eq(cachedDevice), any())
    }

    @Test
    fun testMetadataListener_whenTileNotListening_isUnregistered() {
        val state = QSTile.BooleanState()
        val cachedDevice = mock<CachedBluetoothDevice>()
        listenToDeviceMetadata(state, cachedDevice, 50)

        tile.handleSetListening(false)

        verify(bluetoothController, times(1))
            .removeOnMetadataChangedListener(eq(cachedDevice), any())
    }

    @Test
    @EnableFlags(QSComposeFragment.FLAG_NAME)
    fun disableBluetooth_transientTurningOff() {
        enableBluetooth()
        tile.refreshState()
        testableLooper.processAllMessages()

        tile.handleSecondaryClick(null)
        testableLooper.processAllMessages()

        val state = tile.state

        assertThat(state.state).isEqualTo(Tile.STATE_INACTIVE)
        assertThat(state.isTransient).isTrue()
        assertThat(state.icon).isEqualTo(createExpectedIcon(R.drawable.qs_bluetooth_icon_off))
    }

    @Test
    @EnableFlags(QSComposeFragment.FLAG_NAME)
    fun turningOffState() {
        setBluetoothTurningOff()

        tile.refreshState()
        testableLooper.processAllMessages()

        val state = tile.state

        assertThat(state.state).isEqualTo(Tile.STATE_INACTIVE)
        assertThat(state.isTransient).isTrue()
        assertThat(state.icon).isEqualTo(createExpectedIcon(R.drawable.qs_bluetooth_icon_off))
    }

    private class FakeBluetoothTile(
        qsHost: QSHost,
        uiEventLogger: QsEventLogger,
        backgroundLooper: Looper,
        mainHandler: Handler,
        falsingManager: FalsingManager,
        metricsLogger: MetricsLogger,
        statusBarStateController: StatusBarStateController,
        activityStarter: ActivityStarter,
        qsLogger: QSLogger,
        bluetoothController: BluetoothController,
        featureFlags: FeatureFlagsClassic,
        lazyBluetoothDetailsContentViewModel: Lazy<BluetoothDetailsContentViewModel>,
    ) :
        BluetoothTile(
            qsHost,
            uiEventLogger,
            backgroundLooper,
            mainHandler,
            falsingManager,
            metricsLogger,
            statusBarStateController,
            activityStarter,
            qsLogger,
            bluetoothController,
            featureFlags,
            lazyBluetoothDetailsContentViewModel,
        ) {
        var restrictionChecked: String? = null

        override fun checkIfRestrictionEnforcedByAdminOnly(
            state: QSTile.State?,
            userRestriction: String?,
        ) {
            restrictionChecked = userRestriction
        }
    }

    fun enableBluetooth() {
        whenever(bluetoothController.isBluetoothEnabled).thenReturn(true)
    }

    fun disableBluetooth() {
        whenever(bluetoothController.isBluetoothEnabled).thenReturn(false)
    }

    fun setBluetoothDisconnected() {
        whenever(bluetoothController.isBluetoothConnecting).thenReturn(false)
        whenever(bluetoothController.isBluetoothConnected).thenReturn(false)
    }

    fun setBluetoothConnected() {
        whenever(bluetoothController.isBluetoothConnecting).thenReturn(false)
        whenever(bluetoothController.isBluetoothConnected).thenReturn(true)
    }

    fun setBluetoothConnecting() {
        whenever(bluetoothController.isBluetoothConnected).thenReturn(false)
        whenever(bluetoothController.isBluetoothConnecting).thenReturn(true)
    }

    fun setBluetoothTurningOff() {
        whenever(bluetoothController.isBluetoothConnected).thenReturn(false)
        whenever(bluetoothController.isBluetoothConnecting).thenReturn(false)
        whenever(bluetoothController.isBluetoothEnabled).thenReturn(false)
        whenever(bluetoothController.bluetoothState).thenReturn(BluetoothAdapter.STATE_TURNING_OFF)
    }

    fun addConnectedDevice(device: CachedBluetoothDevice) {
        whenever(bluetoothController.connectedDevices).thenReturn(listOf(device))
    }

    fun listenToDeviceMetadata(
        state: QSTile.BooleanState,
        cachedDevice: CachedBluetoothDevice,
        batteryLevel: Int,
    ) {
        val btDevice = mock<BluetoothDevice>()
        whenever(cachedDevice.device).thenReturn(btDevice)
        whenever(btDevice.getMetadata(BluetoothDevice.METADATA_MAIN_BATTERY))
            .thenReturn(batteryLevel.toString().toByteArray())
        enableBluetooth()
        setBluetoothConnected()
        addConnectedDevice(cachedDevice)
        tile.handleUpdateState(state, /* arg= */ null)
    }

    private fun createExpectedIcon(resId: Int): QSTile.Icon {
        return if (isEnabled) {
            DrawableIconWithRes(mContext.getDrawable(resId), resId)
        } else {
            QSTileImpl.ResourceIcon.get(resId)
        }
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return allCombinationsOf(QSComposeFragment.FLAG_NAME)
        }
    }
}
