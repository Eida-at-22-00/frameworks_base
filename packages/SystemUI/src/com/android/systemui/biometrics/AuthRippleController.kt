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

package com.android.systemui.biometrics

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Point
import android.hardware.biometrics.BiometricFingerprintConstants
import android.hardware.biometrics.BiometricSourceType
import android.util.DisplayMetrics
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.animation.Interpolators
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.keyguard.logging.KeyguardLogger
import com.android.settingslib.Utils
import com.android.systemui.CoreStartable
import com.android.systemui.biometrics.data.repository.FacePropertyRepository
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.deviceentry.domain.interactor.AuthRippleInteractor
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import com.android.systemui.shared.Flags.ambientAod
import com.android.systemui.statusbar.CircleReveal
import com.android.systemui.statusbar.LiftReveal
import com.android.systemui.statusbar.LightRevealEffect
import com.android.systemui.statusbar.LightRevealScrim
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.commandline.Command
import com.android.systemui.statusbar.commandline.CommandRegistry
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.ViewController
import java.io.PrintWriter
import javax.inject.Inject
import javax.inject.Provider

/**
 * Controls two ripple effects:
 * 1. Unlocked ripple: shows when authentication is successful
 * 2. UDFPS dwell ripple: shows when the user has their finger down on the UDFPS area and reacts to
 *    errors and successes
 *
 * The ripple uses the accent color of the current theme.
 */
@SysUISingleton
class AuthRippleController
@Inject
constructor(
    @Main private val sysuiContext: Context,
    private val authController: AuthController,
    @Main private val configurationController: ConfigurationController,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val keyguardStateController: KeyguardStateController,
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    private val commandRegistry: CommandRegistry,
    private val notificationShadeWindowController: NotificationShadeWindowController,
    private val udfpsControllerProvider: Provider<UdfpsController>,
    private val statusBarStateController: StatusBarStateController,
    private val displayMetrics: DisplayMetrics,
    private val logger: KeyguardLogger,
    private val biometricUnlockController: BiometricUnlockController,
    private val lightRevealScrim: LightRevealScrim,
    private val authRippleInteractor: AuthRippleInteractor,
    private val facePropertyRepository: FacePropertyRepository,
    rippleView: AuthRippleView?,
) :
    ViewController<AuthRippleView>(rippleView),
    CoreStartable,
    KeyguardStateController.Callback,
    WakefulnessLifecycle.Observer {

    @VisibleForTesting internal var startLightRevealScrimOnKeyguardFadingAway = false
    var lightRevealScrimAnimator: ValueAnimator? = null
    var fingerprintSensorLocation: Point? = null
    private var faceSensorLocation: Point? = null
    private var circleReveal: LightRevealEffect? = null

    private var udfpsController: UdfpsController? = null
    private var udfpsRadius: Float = -1f

    private var animationDuration: Long

    init {
        animationDuration = sysuiContext.resources.getFloat(
                R.dimen.auth_ripple_animation_duration).toLong()
    }

    override fun start() {
        init()
        mView.setAnimationDuration(animationDuration)
    }

    init {
        rippleView?.repeatWhenAttached {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.CREATED) {
                authRippleInteractor.showUnlockRipple.collect { biometricUnlockSource ->
                    if (biometricUnlockSource == BiometricUnlockSource.FINGERPRINT_SENSOR) {
                        showUnlockRippleInternal(BiometricSourceType.FINGERPRINT)
                    } else {
                        showUnlockRippleInternal(BiometricSourceType.FACE)
                    }
                }
            }
        }
    }

    @VisibleForTesting
    public override fun onViewAttached() {
        authController.addCallback(authControllerCallback)
        updateRippleColor()
        updateUdfpsDependentParams()
        udfpsController?.addCallback(udfpsControllerCallback)
        configurationController.addCallback(configurationChangedListener)
        keyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback)
        keyguardStateController.addCallback(this)
        wakefulnessLifecycle.addObserver(this)
        commandRegistry.registerCommand("auth-ripple") { AuthRippleCommand() }
    }

    @VisibleForTesting
    public override fun onViewDetached() {
        udfpsController?.removeCallback(udfpsControllerCallback)
        authController.removeCallback(authControllerCallback)
        keyguardUpdateMonitor.removeCallback(keyguardUpdateMonitorCallback)
        configurationController.removeCallback(configurationChangedListener)
        keyguardStateController.removeCallback(this)
        wakefulnessLifecycle.removeObserver(this)
        commandRegistry.unregisterCommand("auth-ripple")

        notificationShadeWindowController.setForcePluginOpen(false, this)
    }

    private fun showUnlockRippleInternal(biometricSourceType: BiometricSourceType) {
        val keyguardNotShowing = !keyguardStateController.isShowing
        val unlockNotAllowed =
            !keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(biometricSourceType)
        if (keyguardNotShowing || unlockNotAllowed) {
            logger.notShowingUnlockRipple(keyguardNotShowing, unlockNotAllowed)
            return
        }

        updateSensorLocation()
        if (biometricSourceType == BiometricSourceType.FINGERPRINT) {
            fingerprintSensorLocation?.let {
                mView.setFingerprintSensorLocation(it, udfpsRadius)
                circleReveal =
                    CircleReveal(
                        it.x,
                        it.y,
                        0,
                        Math.max(
                            Math.max(it.x, displayMetrics.widthPixels - it.x),
                            Math.max(it.y, displayMetrics.heightPixels - it.y),
                        ),
                    )
                logger.showingUnlockRippleAt(it.x, it.y, "FP sensor radius: $udfpsRadius")
                showUnlockedRipple()
            }
        } else if (biometricSourceType == BiometricSourceType.FACE) {
            faceSensorLocation?.let {
                mView.setSensorLocation(it)
                circleReveal =
                    CircleReveal(
                        it.x,
                        it.y,
                        0,
                        Math.max(
                            Math.max(it.x, displayMetrics.widthPixels - it.x),
                            Math.max(it.y, displayMetrics.heightPixels - it.y),
                        ),
                    )
                logger.showingUnlockRippleAt(it.x, it.y, "Face unlock ripple")
                showUnlockedRipple()
            }
        }
    }

    private fun showUnlockedRipple() {
        notificationShadeWindowController.setForcePluginOpen(true, this)

        // This code path is not used if the KeyguardTransitionRepository is managing the light
        // reveal scrim.
        if (!ambientAod()) {
            if (statusBarStateController.isDozing || biometricUnlockController.isWakeAndUnlock) {
                circleReveal?.let {
                    lightRevealScrim.revealAmount = 0f
                    lightRevealScrim.revealEffect = it
                    startLightRevealScrimOnKeyguardFadingAway = true
                }
            }
        }

        mView.startUnlockedRipple(
            /* end runnable */
            Runnable { notificationShadeWindowController.setForcePluginOpen(false, this) }
        )
    }

    override fun onKeyguardFadingAwayChanged() {
        if (ambientAod()) {
            return
        }

        if (keyguardStateController.isKeyguardFadingAway) {
            if (startLightRevealScrimOnKeyguardFadingAway) {
                lightRevealScrimAnimator?.cancel()
                lightRevealScrimAnimator =
                    ValueAnimator.ofFloat(.1f, 1f).apply {
                        interpolator = Interpolators.LINEAR_OUT_SLOW_IN
                        duration = animationDuration
                        startDelay = keyguardStateController.keyguardFadingAwayDelay
                        addUpdateListener { animator ->
                            if (lightRevealScrim.revealEffect != circleReveal) {
                                // if something else took over the reveal, let's cancel ourselves
                                cancel()
                                return@addUpdateListener
                            }
                            lightRevealScrim.revealAmount = animator.animatedValue as Float
                        }
                        addListener(
                            object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    // Reset light reveal scrim to the default, so the
                                    // CentralSurfaces
                                    // can handle any subsequent light reveal changes
                                    // (ie: from dozing changes)
                                    if (lightRevealScrim.revealEffect == circleReveal) {
                                        lightRevealScrim.revealEffect = LiftReveal
                                    }

                                    lightRevealScrimAnimator = null
                                }
                            }
                        )
                        start()
                    }
                startLightRevealScrimOnKeyguardFadingAway = false
            }
        }
    }

    /**
     * Whether we're animating the light reveal scrim from a call to [onKeyguardFadingAwayChanged].
     */
    fun isAnimatingLightRevealScrim(): Boolean {
        return lightRevealScrimAnimator?.isRunning ?: false
    }

    override fun onStartedGoingToSleep() {
        // reset the light reveal start in case we were pending an unlock
        startLightRevealScrimOnKeyguardFadingAway = false
    }

    fun updateSensorLocation() {
        fingerprintSensorLocation = authController.fingerprintSensorLocation
        faceSensorLocation = facePropertyRepository.sensorLocation.value
    }

    private fun updateRippleColor() {
        mView.setLockScreenColor(
            Utils.getColorAttrDefaultColor(sysuiContext, R.attr.wallpaperTextColorAccent)
        )
    }

    private fun showDwellRipple() {
        updateSensorLocation()
        fingerprintSensorLocation?.let {
            mView.setFingerprintSensorLocation(it, udfpsRadius)
            mView.startDwellRipple(statusBarStateController.isDozing)
        }
    }

    private val keyguardUpdateMonitorCallback =
        object : KeyguardUpdateMonitorCallback() {
            override fun onBiometricAuthenticated(
                userId: Int,
                biometricSourceType: BiometricSourceType,
                isStrongBiometric: Boolean,
            ) {
                if (biometricSourceType == BiometricSourceType.FINGERPRINT) {
                    mView.fadeDwellRipple()
                }
            }

            override fun onBiometricAuthFailed(biometricSourceType: BiometricSourceType) {
                if (biometricSourceType == BiometricSourceType.FINGERPRINT) {
                    mView.retractDwellRipple()
                }
            }

            override fun onBiometricAcquired(
                biometricSourceType: BiometricSourceType,
                acquireInfo: Int,
            ) {
                if (
                    biometricSourceType == BiometricSourceType.FINGERPRINT &&
                        BiometricFingerprintConstants.shouldDisableUdfpsDisplayMode(acquireInfo) &&
                        acquireInfo != BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_GOOD
                ) {
                    // received an 'acquiredBad' message, so immediately retract
                    mView.retractDwellRipple()
                }
            }

            override fun onKeyguardBouncerStateChanged(bouncerIsOrWillBeShowing: Boolean) {
                if (bouncerIsOrWillBeShowing) {
                    mView.fadeDwellRipple()
                }
            }
        }

    private val configurationChangedListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onUiModeChanged() {
                updateRippleColor()
            }

            override fun onThemeChanged() {
                updateRippleColor()
            }
        }

    private val udfpsControllerCallback =
        object : UdfpsController.Callback {
            override fun onFingerDown() {
                // only show dwell ripple for device entry
                if (keyguardUpdateMonitor.isFingerprintDetectionRunning) {
                    showDwellRipple()
                }
            }

            override fun onFingerUp() {
                mView.retractDwellRipple()
            }
        }

    private val authControllerCallback =
        object : AuthController.Callback {
            override fun onAllAuthenticatorsRegistered(modality: Int) {
                updateUdfpsDependentParams()
            }

            override fun onUdfpsLocationChanged(udfpsOverlayParams: UdfpsOverlayParams) {
                updateUdfpsDependentParams()
            }
        }

    private fun updateUdfpsDependentParams() {
        authController.udfpsProps?.let {
            if (it.size > 0) {
                udfpsController = udfpsControllerProvider.get()
                udfpsRadius = authController.udfpsRadius

                if (mView.isAttachedToWindow) {
                    udfpsController?.addCallback(udfpsControllerCallback)
                }
            }
        }
    }

    inner class AuthRippleCommand : Command {
        override fun execute(pw: PrintWriter, args: List<String>) {
            if (args.isEmpty()) {
                invalidCommand(pw)
            } else {
                when (args[0]) {
                    "dwell" -> {
                        showDwellRipple()
                        pw.println(
                            "lock screen dwell ripple: " +
                                "\n\tsensorLocation=$fingerprintSensorLocation" +
                                "\n\tudfpsRadius=$udfpsRadius"
                        )
                    }
                    "fingerprint" -> {
                        pw.println("fingerprint ripple sensorLocation=$fingerprintSensorLocation")
                        showUnlockRippleInternal(BiometricSourceType.FINGERPRINT)
                    }
                    "face" -> {
                        // note: only shows when about to proceed to the home screen
                        pw.println("face ripple sensorLocation=$faceSensorLocation")
                        showUnlockRippleInternal(BiometricSourceType.FACE)
                    }
                    "custom" -> {
                        if (
                            args.size != 3 ||
                                args[1].toFloatOrNull() == null ||
                                args[2].toFloatOrNull() == null
                        ) {
                            invalidCommand(pw)
                            return
                        }
                        pw.println("custom ripple sensorLocation=" + args[1] + ", " + args[2])
                        mView.setSensorLocation(Point(args[1].toInt(), args[2].toInt()))
                        showUnlockedRipple()
                    }
                    else -> invalidCommand(pw)
                }
            }
        }

        override fun help(pw: PrintWriter) {
            pw.println("Usage: adb shell cmd statusbar auth-ripple <command>")
            pw.println("Available commands:")
            pw.println("  dwell")
            pw.println("  fingerprint")
            pw.println("  face")
            pw.println("  custom <x-location: int> <y-location: int>")
        }

        private fun invalidCommand(pw: PrintWriter) {
            pw.println("invalid command")
            help(pw)
        }
    }

    companion object {
        const val TAG = "AuthRippleController"
    }
}
