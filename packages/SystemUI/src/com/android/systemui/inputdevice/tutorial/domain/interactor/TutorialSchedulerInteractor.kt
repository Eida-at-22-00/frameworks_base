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

package com.android.systemui.inputdevice.tutorial.domain.interactor

import android.os.SystemProperties
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.inputdevice.tutorial.InputDeviceTutorialLogger
import com.android.systemui.inputdevice.tutorial.data.repository.DeviceType
import com.android.systemui.inputdevice.tutorial.data.repository.DeviceType.KEYBOARD
import com.android.systemui.inputdevice.tutorial.data.repository.DeviceType.TOUCHPAD
import com.android.systemui.inputdevice.tutorial.data.repository.TutorialSchedulerRepository
import com.android.systemui.inputdevice.tutorial.domain.interactor.TutorialSchedulerInteractor.Companion.LAUNCH_DELAY
import com.android.systemui.keyboard.data.repository.KeyboardRepository
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.touchpad.data.repository.TouchpadRepository
import java.io.PrintWriter
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlin.time.Duration.Companion.hours
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * When the first time a keyboard or touchpad is connected, wait for [LAUNCH_DELAY], and as soon as
 * there's a connected device, show a notification to launch the tutorial.
 */
@SysUISingleton
class TutorialSchedulerInteractor
@Inject
constructor(
    keyboardRepository: KeyboardRepository,
    touchpadRepository: TouchpadRepository,
    private val repo: TutorialSchedulerRepository,
    private val logger: InputDeviceTutorialLogger,
    commandRegistry: CommandRegistry,
    @Background private val backgroundScope: CoroutineScope,
) {
    init {
        commandRegistry.registerCommand(COMMAND) { TutorialCommand() }
    }

    private val isAnyDeviceConnected =
        mapOf(
            KEYBOARD to keyboardRepository.isAnyKeyboardConnected,
            TOUCHPAD to touchpadRepository.isAnyTouchpadConnected,
        )

    private val touchpadScheduleFlow = flow {
        if (!repo.isNotified(TOUCHPAD)) {
            schedule(TOUCHPAD)
            emit(TOUCHPAD)
        }
    }

    private val keyboardScheduleFlow = flow {
        if (!repo.isNotified(KEYBOARD)) {
            schedule(KEYBOARD)
            emit(KEYBOARD)
        }
    }

    private suspend fun schedule(deviceType: DeviceType) {
        if (!repo.wasEverConnected(deviceType)) {
            logger.d("Waiting for $deviceType to connect")
            waitForDeviceConnection(deviceType)
            logger.logDeviceFirstConnection(deviceType)
            repo.setFirstConnectionTime(deviceType, Instant.now())
        }
        val remainingTime = remainingTime(start = repo.getFirstConnectionTime(deviceType)!!)
        logger.d("Tutorial is scheduled in ${remainingTime.inWholeSeconds} seconds")
        delay(remainingTime)
        waitForDeviceConnection(deviceType)
    }

    // This flow is used by the notification updater once an initial notification is launched. It
    // listens to the changes of keyboard and touchpad connection and resolve the tutorial type base
    // on the latest connection state.
    // Dropping the initial state because it represents the existing notification.
    val tutorialTypeUpdates: Flow<TutorialType> =
        keyboardRepository.isAnyKeyboardConnected
            .combine(touchpadRepository.isAnyTouchpadConnected, ::Pair)
            .map { (keyboardConnected, touchpadConnected) ->
                when {
                    keyboardConnected && touchpadConnected -> TutorialType.BOTH
                    keyboardConnected -> TutorialType.KEYBOARD
                    touchpadConnected -> TutorialType.TOUCHPAD
                    else -> TutorialType.NONE
                }
            }
            .drop(1)

    private suspend fun waitForDeviceConnection(deviceType: DeviceType) =
        isAnyDeviceConnected[deviceType]!!.filter { it }.first()

    // Only for testing notifications. This should behave independently from scheduling
    val commandTutorials = MutableStateFlow(TutorialType.NONE)

    // Merging two flows ensures that tutorial is launched consecutively to avoid race condition
    val tutorials: Flow<TutorialType> =
        merge(touchpadScheduleFlow, keyboardScheduleFlow).map {
            val tutorialType = resolveTutorialType(it)

            if (tutorialType == TutorialType.KEYBOARD || tutorialType == TutorialType.BOTH)
                repo.setNotifiedTime(KEYBOARD, Instant.now())
            if (tutorialType == TutorialType.TOUCHPAD || tutorialType == TutorialType.BOTH)
                repo.setNotifiedTime(TOUCHPAD, Instant.now())

            logger.logTutorialLaunched(tutorialType)
            tutorialType
        }

    private suspend fun resolveTutorialType(deviceType: DeviceType): TutorialType {
        // Resolve the type of tutorial depending on which device are connected when the tutorial is
        // launched. E.g. when the keyboard is connected for [LAUNCH_DELAY], both keyboard and
        // touchpad are connected, we launch the tutorial for both.
        if (repo.isNotified(deviceType)) return TutorialType.NONE
        val otherDevice = if (deviceType == KEYBOARD) TOUCHPAD else KEYBOARD
        val isOtherDeviceConnected = isAnyDeviceConnected[otherDevice]!!.first()
        if (!repo.isNotified(otherDevice) && isOtherDeviceConnected) return TutorialType.BOTH
        return if (deviceType == KEYBOARD) TutorialType.KEYBOARD else TutorialType.TOUCHPAD
    }

    private fun remainingTime(start: Instant): kotlin.time.Duration {
        val elapsed = Duration.between(start, Instant.now())
        return LAUNCH_DELAY.minus(elapsed).toKotlinDuration()
    }

    fun updateLaunchInfo(tutorialType: TutorialType) {
        backgroundScope.launch {
            if (tutorialType == TutorialType.KEYBOARD || tutorialType == TutorialType.BOTH)
                repo.setScheduledTutorialLaunchTime(KEYBOARD, Instant.now())
            if (tutorialType == TutorialType.TOUCHPAD || tutorialType == TutorialType.BOTH)
                repo.setScheduledTutorialLaunchTime(TOUCHPAD, Instant.now())
        }
    }

    inner class TutorialCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            if (args.isEmpty()) {
                help(pw)
                return
            }
            when (args[0]) {
                "clear" ->
                    runBlocking {
                        repo.clear()
                        pw.println("Tutorial scheduler reset")
                    }
                "info" ->
                    runBlocking {
                        pw.println(
                            "Keyboard connect time = ${repo.getFirstConnectionTime(KEYBOARD)}"
                        )
                        pw.println("         notified = ${repo.isNotified(KEYBOARD)}")
                        pw.println(
                            "         launch time = ${repo.getScheduledTutorialLaunchTime(KEYBOARD)}"
                        )
                        pw.println(
                            "Touchpad connect time = ${repo.getFirstConnectionTime(TOUCHPAD)}"
                        )
                        pw.println("         notified = ${repo.isNotified(TOUCHPAD)}")
                        pw.println(
                            "         launch time = ${repo.getScheduledTutorialLaunchTime(TOUCHPAD)}"
                        )
                        pw.println("Delay time = ${LAUNCH_DELAY.seconds} sec")
                    }
                "notify" -> {
                    if (args.size != 2) help(pw)
                    when (args[1]) {
                        "keyboard" -> commandTutorials.value = TutorialType.KEYBOARD
                        "touchpad" -> commandTutorials.value = TutorialType.TOUCHPAD
                        "both" -> commandTutorials.value = TutorialType.BOTH
                        else -> help(pw)
                    }
                }
                else -> help(pw)
            }
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar $COMMAND <command>")
            pw.println("Available commands:")
            pw.println("  clear")
            pw.println("  info")
            pw.println("  notify [keyboard|touchpad|both]")
        }
    }

    companion object {
        const val TAG = "TutorialSchedulerInteractor"
        const val COMMAND = "peripheral_tutorial"
        private val DEFAULT_LAUNCH_DELAY_SEC = 72.hours.inWholeSeconds
        private val LAUNCH_DELAY: Duration
            get() =
                Duration.ofSeconds(
                    SystemProperties.getLong(
                        "persist.peripheral_tutorial_delay_sec",
                        DEFAULT_LAUNCH_DELAY_SEC,
                    )
                )
    }

    enum class TutorialType {
        KEYBOARD,
        TOUCHPAD,
        BOTH,
        NONE,
    }
}
