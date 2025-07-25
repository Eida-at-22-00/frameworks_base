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
package com.android.keyguard

import android.app.NotificationManager.zenModeFromInterruptionFilter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.os.Trace
import android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
import android.provider.Settings.Global.ZEN_MODE_OFF
import android.text.format.DateFormat
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.customization.R
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.DisplaySpecific
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags.REGION_SAMPLING
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.log.core.Logger
import com.android.systemui.modes.shared.ModesUi
import com.android.systemui.plugins.clocks.AlarmData
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockEventListener
import com.android.systemui.plugins.clocks.ClockFaceController
import com.android.systemui.plugins.clocks.ClockMessageBuffers
import com.android.systemui.plugins.clocks.ClockTickRate
import com.android.systemui.plugins.clocks.VRectF
import com.android.systemui.plugins.clocks.WeatherData
import com.android.systemui.plugins.clocks.ZenData
import com.android.systemui.plugins.clocks.ZenData.ZenMode
import com.android.systemui.res.R as SysuiR
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.regionsampling.RegionSampler
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import com.android.systemui.util.annotations.DeprecatedSysuiVisibleForTesting
import com.android.systemui.util.concurrency.DelayableExecutor
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Controller for a Clock provided by the registry and used on the keyguard. Functionality is forked
 * from [AnimatableClockController].
 */
open class ClockEventController
@Inject
constructor(
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val batteryController: BatteryController,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    // TODO b/362719719 - We should use the configuration controller associated with the display.
    private val configurationController: ConfigurationController,
    @DisplaySpecific private val resources: Resources,
    @DisplaySpecific val context: Context,
    @Main private val mainExecutor: DelayableExecutor,
    @Background private val bgExecutor: Executor,
    private val clockBuffers: ClockMessageBuffers,
    private val featureFlags: FeatureFlagsClassic,
    private val zenModeController: ZenModeController,
    private val zenModeInteractor: ZenModeInteractor,
    private val userTracker: UserTracker,
) {
    var loggers =
        listOf(
                clockBuffers.infraMessageBuffer,
                clockBuffers.smallClockMessageBuffer,
                clockBuffers.largeClockMessageBuffer,
            )
            .map { Logger(it, TAG) }

    var clock: ClockController? = null
        get() = field
        set(value) {
            disconnectClock(field)
            field = value
            connectClock(value)
        }

    private fun is24HourFormat(userId: Int? = null): Boolean {
        return DateFormat.is24HourFormat(context, userId ?: userTracker.userId)
    }

    private fun disconnectClock(clock: ClockController?) {
        if (clock == null) {
            return
        }
        smallClockOnAttachStateChangeListener?.let {
            clock.smallClock.view.removeOnAttachStateChangeListener(it)
            smallClockFrame?.viewTreeObserver?.removeOnGlobalLayoutListener(onGlobalLayoutListener)
        }
        largeClockOnAttachStateChangeListener?.let {
            clock.largeClock.view.removeOnAttachStateChangeListener(it)
        }
    }

    private fun connectClock(clock: ClockController?) {
        if (clock == null) {
            return
        }
        val clockStr = clock.toString()
        loggers.forEach { it.d({ "New Clock: $str1" }) { str1 = clockStr } }

        clock.initialize(isDarkTheme(), dozeAmount.value, 0f, clockListener)

        if (!regionSamplingEnabled) {
            updateColors()
        } else {
            smallRegionSampler =
                createRegionSampler(
                        clock.smallClock.view,
                        mainExecutor,
                        bgExecutor,
                        regionSamplingEnabled,
                        isLockscreen = true,
                        ::updateColors,
                    )
                    .apply { startRegionSampler() }

            largeRegionSampler =
                createRegionSampler(
                        clock.largeClock.view,
                        mainExecutor,
                        bgExecutor,
                        regionSamplingEnabled,
                        isLockscreen = true,
                        ::updateColors,
                    )
                    .apply { startRegionSampler() }

            updateColors()
        }
        updateFontSizes()
        updateTimeListeners()

        weatherData?.let {
            if (WeatherData.DEBUG) {
                Log.i(TAG, "Pushing cached weather data to new clock: $it")
            }
            clock.events.onWeatherDataChanged(it)
        }
        zenData?.let { clock.events.onZenDataChanged(it) }
        alarmData?.let { clock.events.onAlarmDataChanged(it) }

        smallClockOnAttachStateChangeListener =
            object : OnAttachStateChangeListener {
                var pastVisibility: Int? = null

                override fun onViewAttachedToWindow(view: View) {
                    clock.events.onTimeFormatChanged(is24HourFormat())
                    // Match the asing for view.parent's layout classes.
                    smallClockFrame =
                        (view.parent as ViewGroup)?.also { frame ->
                            pastVisibility = frame.visibility
                            onGlobalLayoutListener = OnGlobalLayoutListener {
                                val currentVisibility = frame.visibility
                                if (pastVisibility != currentVisibility) {
                                    pastVisibility = currentVisibility
                                    // when small clock is visible,
                                    // recalculate bounds and sample
                                    if (currentVisibility == View.VISIBLE) {
                                        smallRegionSampler?.stopRegionSampler()
                                        smallRegionSampler?.startRegionSampler()
                                    }
                                }
                            }
                            frame.viewTreeObserver.addOnGlobalLayoutListener(onGlobalLayoutListener)
                        }
                }

                override fun onViewDetachedFromWindow(p0: View) {
                    smallClockFrame
                        ?.viewTreeObserver
                        ?.removeOnGlobalLayoutListener(onGlobalLayoutListener)
                }
            }
        clock.smallClock.view.addOnAttachStateChangeListener(smallClockOnAttachStateChangeListener)

        largeClockOnAttachStateChangeListener =
            object : OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(p0: View) {
                    clock.events.onTimeFormatChanged(is24HourFormat())
                }

                override fun onViewDetachedFromWindow(p0: View) {}
            }
        clock.largeClock.view.addOnAttachStateChangeListener(largeClockOnAttachStateChangeListener)
    }

    @VisibleForTesting
    var smallClockOnAttachStateChangeListener: OnAttachStateChangeListener? = null
    @VisibleForTesting
    var largeClockOnAttachStateChangeListener: OnAttachStateChangeListener? = null
    private var smallClockFrame: ViewGroup? = null
    private var onGlobalLayoutListener: OnGlobalLayoutListener? = null

    private var isCharging = false
    private var isKeyguardVisible = false
    private var isRegistered = false
    private var disposableHandle: DisposableHandle? = null
    private val regionSamplingEnabled = featureFlags.isEnabled(REGION_SAMPLING)
    private var largeClockOnSecondaryDisplay = false

    val dozeAmount = MutableStateFlow(0f)
    val onClockBoundsChanged = MutableStateFlow<VRectF>(VRectF.ZERO)

    private fun isDarkTheme(): Boolean {
        val isLightTheme = TypedValue()
        context.theme.resolveAttribute(android.R.attr.isLightTheme, isLightTheme, true)
        return isLightTheme.data == 0
    }

    private fun updateColors() {
        val isDarkTheme = isDarkTheme()
        if (regionSamplingEnabled) {
            clock?.smallClock?.run {
                val isDark = smallRegionSampler?.currentRegionDarkness()?.isDark ?: isDarkTheme
                events.onThemeChanged(theme.copy(isDarkTheme = isDark))
            }
            clock?.largeClock?.run {
                val isDark = largeRegionSampler?.currentRegionDarkness()?.isDark ?: isDarkTheme
                events.onThemeChanged(theme.copy(isDarkTheme = isDark))
            }
            return
        }

        clock?.run {
            Log.i(TAG, "isThemeDark: $isDarkTheme")
            smallClock.events.onThemeChanged(smallClock.theme.copy(isDarkTheme = isDarkTheme))
            largeClock.events.onThemeChanged(largeClock.theme.copy(isDarkTheme = isDarkTheme))
        }
    }

    protected open fun createRegionSampler(
        sampledView: View,
        mainExecutor: Executor?,
        bgExecutor: Executor?,
        regionSamplingEnabled: Boolean,
        isLockscreen: Boolean,
        updateColors: () -> Unit,
    ): RegionSampler {
        return RegionSampler(
            sampledView,
            mainExecutor,
            bgExecutor,
            regionSamplingEnabled,
            isLockscreen,
        ) {
            updateColors()
        }
    }

    var smallRegionSampler: RegionSampler? = null
        private set

    var largeRegionSampler: RegionSampler? = null
        private set

    var smallTimeListener: TimeListener? = null
    var largeTimeListener: TimeListener? = null
    val shouldTimeListenerRun: Boolean
        get() = isKeyguardVisible && dozeAmount.value < DOZE_TICKRATE_THRESHOLD

    private var weatherData: WeatherData? = null
    private var zenData: ZenData? = null
    private var alarmData: AlarmData? = null

    private val clockListener =
        object : ClockEventListener {
            override fun onBoundsChanged(bounds: VRectF) {
                onClockBoundsChanged.value = bounds
            }
        }

    private val configListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onThemeChanged() {
                updateColors()
            }

            override fun onDensityOrFontScaleChanged() {
                updateFontSizes()
            }
        }

    private val batteryCallback =
        object : BatteryStateChangeCallback {
            override fun onBatteryLevelChanged(level: Int, pluggedIn: Boolean, charging: Boolean) {
                if (isKeyguardVisible && !isCharging && charging) {
                    clock?.run {
                        smallClock.animations.charge()
                        largeClock.animations.charge()
                    }
                }
                isCharging = charging
            }
        }

    private val localeBroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                clock?.run { events.onLocaleChanged(Locale.getDefault()) }
            }
        }

    private val keyguardUpdateMonitorCallback =
        object : KeyguardUpdateMonitorCallback() {
            override fun onKeyguardVisibilityChanged(visible: Boolean) {
                isKeyguardVisible = visible

                if (visible) {
                    refreshTime()
                }

                smallTimeListener?.update(shouldTimeListenerRun)
                largeTimeListener?.update(shouldTimeListenerRun)
            }

            override fun onTimeFormatChanged(timeFormat: String?) {
                clock?.run { events.onTimeFormatChanged(is24HourFormat()) }
            }

            override fun onTimeZoneChanged(timeZone: TimeZone) {
                clock?.run { events.onTimeZoneChanged(timeZone) }
            }

            override fun onUserSwitchComplete(userId: Int) {
                clock?.run { events.onTimeFormatChanged(is24HourFormat(userId)) }
                zenModeCallback.onNextAlarmChanged()
            }

            override fun onWeatherDataChanged(data: WeatherData) {
                weatherData = data
                clock?.run { events.onWeatherDataChanged(data) }
            }

            override fun onTimeChanged() {
                refreshTime()
            }

            private fun refreshTime() {
                clock?.smallClock?.events?.onTimeTick()
                clock?.largeClock?.events?.onTimeTick()
            }
        }

    @DeprecatedSysuiVisibleForTesting
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun listenForDnd(scope: CoroutineScope): Job {
        ModesUi.unsafeAssertInNewMode()
        return scope.launch {
            zenModeInteractor.dndMode.collect {
                val zenMode =
                    if (it != null && it.isActive)
                        zenModeFromInterruptionFilter(
                            it.interruptionFilter,
                            ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                        )
                    else ZEN_MODE_OFF

                handleZenMode(zenMode)
            }
        }
    }

    private val zenModeCallback =
        object : ZenModeController.Callback {
            override fun onZenChanged(zen: Int) {
                if (!ModesUi.isEnabled) {
                    handleZenMode(zen)
                }
            }

            override fun onNextAlarmChanged() {
                val nextAlarmMillis = zenModeController.getNextAlarm()
                alarmData =
                    AlarmData(
                            if (nextAlarmMillis > 0) nextAlarmMillis else null,
                            SysuiR.string::status_bar_alarm.name,
                        )
                        .also { data ->
                            mainExecutor.execute { clock?.run { events.onAlarmDataChanged(data) } }
                        }
            }
        }

    private fun handleZenMode(zen: Int) {
        val mode = ZenMode.fromInt(zen)
        if (mode == null) {
            Log.e(TAG, "Failed to get zen mode from int: $zen")
            return
        }

        zenData =
            ZenData(
                    mode,
                    if (mode == ZenMode.OFF) SysuiR.string::dnd_is_off.name
                    else SysuiR.string::dnd_is_on.name,
                )
                .also { data ->
                    mainExecutor.execute { clock?.run { events.onZenDataChanged(data) } }
                }
    }

    fun registerListeners(parent: View) {
        if (isRegistered) {
            return
        }
        isRegistered = true
        broadcastDispatcher.registerReceiver(
            localeBroadcastReceiver,
            IntentFilter(Intent.ACTION_LOCALE_CHANGED),
        )
        configurationController.addCallback(configListener)
        batteryController.addCallback(batteryCallback)
        keyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback)
        zenModeController.addCallback(zenModeCallback)
        if (SceneContainerFlag.isEnabled) {
            handleDoze(
                when (AOD) {
                    keyguardTransitionInteractor.getCurrentState() -> 1f
                    keyguardTransitionInteractor.getStartedState() -> 1f
                    else -> 0f
                }
            )
        }
        disposableHandle =
            parent.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    if (ModesUi.isEnabled) {
                        listenForDnd(this)
                    }
                    listenForDozeAmountTransition(this)
                    listenForAnyStateToAodTransition(this)
                    listenForAnyStateToLockscreenTransition(this)
                    listenForAnyStateToDozingTransition(this)
                }
            }
        smallTimeListener?.update(shouldTimeListenerRun)
        largeTimeListener?.update(shouldTimeListenerRun)

        bgExecutor.execute {
            // Query ZenMode data
            if (!ModesUi.isEnabled) {
                zenModeCallback.onZenChanged(zenModeController.zen)
            }
            zenModeCallback.onNextAlarmChanged()
        }
    }

    fun unregisterListeners() {
        if (!isRegistered) {
            return
        }
        isRegistered = false

        disposableHandle?.dispose()
        broadcastDispatcher.unregisterReceiver(localeBroadcastReceiver)
        configurationController.removeCallback(configListener)
        batteryController.removeCallback(batteryCallback)
        keyguardUpdateMonitor.removeCallback(keyguardUpdateMonitorCallback)
        zenModeController.removeCallback(zenModeCallback)
        smallRegionSampler?.stopRegionSampler()
        largeRegionSampler?.stopRegionSampler()
        smallTimeListener?.stop()
        largeTimeListener?.stop()
        clock?.run {
            smallClock.view.removeOnAttachStateChangeListener(smallClockOnAttachStateChangeListener)
            largeClock.view.removeOnAttachStateChangeListener(largeClockOnAttachStateChangeListener)
        }
        smallClockFrame?.viewTreeObserver?.removeOnGlobalLayoutListener(onGlobalLayoutListener)
    }

    fun setFallbackWeatherData(data: WeatherData) {
        if (weatherData != null) return
        weatherData = data
        clock?.run { events.onWeatherDataChanged(data) }
    }

    /**
     * Sets this clock as showing in a secondary display.
     *
     * Not that this is not necessarily needed, as we could get the displayId from [Context]
     * directly and infere [largeClockOnSecondaryDisplay] from the id being different than the
     * default display one. However, if we do so, current screenshot tests would not work, as they
     * pass an activity context always from the default display.
     */
    fun setLargeClockOnSecondaryDisplay(onSecondaryDisplay: Boolean) {
        largeClockOnSecondaryDisplay = onSecondaryDisplay
        updateFontSizes()
    }

    private fun updateTimeListeners() {
        smallTimeListener?.stop()
        largeTimeListener?.stop()

        smallTimeListener = null
        largeTimeListener = null

        clock?.let {
            smallTimeListener =
                TimeListener(it.smallClock, mainExecutor).apply { update(shouldTimeListenerRun) }
            largeTimeListener =
                TimeListener(it.largeClock, mainExecutor).apply { update(shouldTimeListenerRun) }
        }
    }

    fun updateFontSizes() {
        clock?.run {
            smallClock.events.onFontSettingChanged(getSmallClockSizePx())
            largeClock.events.onFontSettingChanged(getLargeClockSizePx())
        }
    }

    private fun getSmallClockSizePx(): Float {
        return resources.getDimensionPixelSize(R.dimen.small_clock_text_size).toFloat()
    }

    private fun getLargeClockSizePx(): Float {
        return if (largeClockOnSecondaryDisplay) {
            resources.getDimensionPixelSize(R.dimen.presentation_clock_text_size).toFloat()
        } else {
            resources.getDimensionPixelSize(R.dimen.large_clock_text_size).toFloat()
        }
    }

    fun handleFidgetTap(x: Float, y: Float) {
        clock?.run {
            smallClock.animations.onFidgetTap(x, y)
            largeClock.animations.onFidgetTap(x, y)
        }
    }

    private fun handleDoze(doze: Float) {
        clock?.run {
            Trace.beginSection("$TAG#smallClock.animations.doze")
            smallClock.animations.doze(doze)
            Trace.endSection()
            Trace.beginSection("$TAG#largeClock.animations.doze")
            largeClock.animations.doze(doze)
            Trace.endSection()
        }
        smallTimeListener?.update(doze < DOZE_TICKRATE_THRESHOLD)
        largeTimeListener?.update(doze < DOZE_TICKRATE_THRESHOLD)
        dozeAmount.value = doze
    }

    @DeprecatedSysuiVisibleForTesting
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun listenForDozeAmountTransition(scope: CoroutineScope): Job {
        return scope.launch {
            merge(
                    keyguardTransitionInteractor.transition(Edge.create(AOD, LOCKSCREEN)).map {
                        it.copy(value = 1f - it.value)
                    },
                    keyguardTransitionInteractor.transition(Edge.create(LOCKSCREEN, AOD)),
                )
                .filter { it.transitionState != TransitionState.FINISHED }
                .collect { handleDoze(it.value) }
        }
    }

    /**
     * When keyguard is displayed again after being gone, the clock must be reset to full dozing.
     */
    @DeprecatedSysuiVisibleForTesting
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun listenForAnyStateToAodTransition(scope: CoroutineScope): Job {
        return scope.launch {
            keyguardTransitionInteractor
                .transition(Edge.create(to = AOD))
                .filter { it.transitionState == TransitionState.STARTED }
                .filter { it.from != LOCKSCREEN }
                .collect { handleDoze(1f) }
        }
    }

    @DeprecatedSysuiVisibleForTesting
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun listenForAnyStateToLockscreenTransition(scope: CoroutineScope): Job {
        return scope.launch {
            keyguardTransitionInteractor
                .transition(Edge.create(to = LOCKSCREEN))
                .filter { it.transitionState == TransitionState.STARTED }
                .filter { it.from != AOD }
                .collect { handleDoze(0f) }
        }
    }

    /**
     * When keyguard is displayed due to pulsing notifications when AOD is off, we should make sure
     * clock is in dozing state instead of LS state
     */
    @DeprecatedSysuiVisibleForTesting
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun listenForAnyStateToDozingTransition(scope: CoroutineScope): Job {
        return scope.launch {
            keyguardTransitionInteractor
                .transition(Edge.create(to = DOZING))
                .filter { it.transitionState == TransitionState.FINISHED }
                .collect { handleDoze(1f) }
        }
    }

    class TimeListener(val clockFace: ClockFaceController, val executor: DelayableExecutor) {
        val predrawListener =
            ViewTreeObserver.OnPreDrawListener {
                clockFace.events.onTimeTick()
                true
            }

        val secondsRunnable =
            object : Runnable {
                override fun run() {
                    if (!isRunning) {
                        return
                    }

                    executor.executeDelayed(this, 990)
                    clockFace.events.onTimeTick()
                }
            }

        var isRunning: Boolean = false
            private set

        fun start() {
            if (isRunning) {
                return
            }

            isRunning = true
            when (clockFace.config.tickRate) {
                ClockTickRate.PER_MINUTE -> {
                    // Handled by KeyguardUpdateMonitorCallback#onTimeChanged.
                }
                ClockTickRate.PER_SECOND -> executor.execute(secondsRunnable)
                ClockTickRate.PER_FRAME -> {
                    clockFace.view.viewTreeObserver.addOnPreDrawListener(predrawListener)
                    clockFace.view.invalidate()
                }
            }
        }

        fun stop() {
            if (!isRunning) {
                return
            }

            isRunning = false
            clockFace.view.viewTreeObserver.removeOnPreDrawListener(predrawListener)
        }

        fun update(shouldRun: Boolean) = if (shouldRun) start() else stop()
    }

    companion object {
        private const val TAG = "ClockEventController"
        private const val DOZE_TICKRATE_THRESHOLD = 0.99f
    }
}
