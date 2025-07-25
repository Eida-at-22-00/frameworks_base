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

package com.android.systemui.deviceentry.domain.interactor

import com.android.internal.policy.IKeyguardDismissCallback
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.data.repository.DeviceEntryRepository
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.scene.data.model.asIterable
import com.android.systemui.scene.domain.SceneFrameworkTableLog
import com.android.systemui.scene.domain.interactor.SceneBackInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.utils.coroutines.flow.mapLatestConflated
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Hosts application business logic related to device entry.
 *
 * Device entry occurs when the user successfully dismisses (or bypasses) the lockscreen, regardless
 * of the authentication method used.
 */
@SysUISingleton
class DeviceEntryInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val repository: DeviceEntryRepository,
    private val authenticationInteractor: AuthenticationInteractor,
    private val sceneInteractor: SceneInteractor,
    private val deviceUnlockedInteractor: DeviceUnlockedInteractor,
    private val alternateBouncerInteractor: AlternateBouncerInteractor,
    private val dismissCallbackRegistry: DismissCallbackRegistry,
    sceneBackInteractor: SceneBackInteractor,
    @SceneFrameworkTableLog private val tableLogBuffer: TableLogBuffer,
) {
    /**
     * Whether the device is unlocked.
     *
     * A device that is not yet unlocked requires unlocking by completing an authentication
     * challenge according to the current authentication method, unless in cases when the current
     * authentication method is not "secure" (for example, None and Swipe); in such cases, the value
     * of this flow will always be `true`, even if the lockscreen is showing and still needs to be
     * dismissed by the user to proceed.
     */
    val isUnlocked: StateFlow<Boolean> =
        deviceUnlockedInteractor.deviceUnlockStatus
            .map { it.isUnlocked }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = deviceUnlockedInteractor.deviceUnlockStatus.value.isUnlocked,
            )

    /**
     * Emits `true` when the current scene switches to [Scenes.Gone] for the first time after having
     * been on [Scenes.Lockscreen].
     *
     * Different from [isDeviceEntered] such that the current scene must actually go through
     * [Scenes.Gone] to produce a `true`. [isDeviceEntered] also takes into account the navigation
     * back stack and will produce a `true` value even when the current scene is still not
     * [Scenes.Gone] but the bottommost entry of the navigation back stack switched from
     * [Scenes.Lockscreen] to [Scenes.Gone] while the user is staring at another scene.
     */
    val isDeviceEnteredDirectly: StateFlow<Boolean> =
        sceneInteractor.currentScene
            .filter { currentScene ->
                currentScene == Scenes.Gone || currentScene == Scenes.Lockscreen
            }
            .mapLatestConflated { scene ->
                if (scene == Scenes.Gone) {
                    // Make sure device unlock status is definitely unlocked before we
                    // consider the device "entered".
                    deviceUnlockedInteractor.deviceUnlockStatus.first { it.isUnlocked }
                    true
                } else {
                    false
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    /**
     * Whether the device has been entered (i.e. the lockscreen has been dismissed, by any method).
     * This can be `false` when the device is unlocked, e.g. when the user still needs to swipe away
     * the non-secure lockscreen, even though they've already authenticated.
     *
     * Note: This does not imply that the lockscreen is visible or not.
     *
     * Different from [isDeviceEnteredDirectly] such that the current scene doesn't actually have to
     * go through [Scenes.Gone] to produce a `true`. [isDeviceEnteredDirectly] doesn't take the
     * navigation back stack into account and will only produce a `true` value even when the current
     * scene is actually [Scenes.Gone].
     */
    val isDeviceEntered: StateFlow<Boolean> =
        combine(
                // This flow emits true when the currentScene switches to Gone for the first time
                // after having been on Lockscreen.
                isDeviceEnteredDirectly,
                // This flow emits true only if the bottom of the navigation back stack has been
                // switched from Lockscreen to Gone. In other words, only if the device was unlocked
                // while visiting at least one scene "above" the Lockscreen scene.
                sceneBackInteractor.backStack
                    // The bottom of the back stack, which is Lockscreen, Gone, or null if empty.
                    .map { it.asIterable().lastOrNull() }
                    // Filter out cases where the stack changes but the bottom remains unchanged.
                    .distinctUntilChanged()
                    // Detect changes of the bottom of the stack, start with null, so the first
                    // update emits a value and the logic doesn't need to wait for a second value
                    // before emitting something.
                    .pairwise(initialValue = null)
                    // Replacing a bottom of the stack that was Lockscreen with Gone constitutes a
                    // "device entered" event.
                    .map { (from, to) -> from == Scenes.Lockscreen && to == Scenes.Gone },
            ) { enteredDirectly, enteredOnBackStack ->
                enteredOnBackStack || enteredDirectly
            }
            .logDiffsForTable(
                tableLogBuffer = tableLogBuffer,
                columnName = "isDeviceEntered",
                initialValue = false,
            )
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    val isLockscreenEnabled: Flow<Boolean> by lazy {
        repository.isLockscreenEnabled.onStart { refreshLockscreenEnabled() }
    }

    /**
     * Whether it's currently possible to swipe up to enter the device without requiring
     * authentication or when the device is already authenticated using a passive authentication
     * mechanism like face or trust manager. This returns `false` whenever the lockscreen has been
     * dismissed.
     *
     * A value of `null` is meaningless and is used as placeholder while the actual value is still
     * being loaded in the background.
     *
     * Note: `true` doesn't mean the lockscreen is visible. It may be occluded or covered by other
     * UI.
     */
    val canSwipeToEnter: StateFlow<Boolean?> by lazy {
        combine(
                authenticationInteractor.authenticationMethod.map {
                    it == AuthenticationMethodModel.None
                },
                isLockscreenEnabled,
                deviceUnlockedInteractor.deviceUnlockStatus,
                isDeviceEntered,
            ) { isNoneAuthMethod, isLockscreenEnabled, deviceUnlockStatus, isDeviceEntered ->
                val isSwipeAuthMethod = isNoneAuthMethod && isLockscreenEnabled
                (isSwipeAuthMethod ||
                    (deviceUnlockStatus.isUnlocked &&
                        deviceUnlockStatus.deviceUnlockSource?.dismissesLockscreen == false)) &&
                    !isDeviceEntered
            }
            .logDiffsForTable(
                tableLogBuffer = tableLogBuffer,
                columnName = "canSwipeToEnter",
                initialValue = false,
            )
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                // Starts as null to prevent downstream collectors from falsely assuming that the
                // user can or cannot swipe to enter the device while the real value is being loaded
                // from upstream data sources.
                initialValue = null,
            )
    }

    /**
     * Whether lockscreen bypass is enabled. When enabled, the lockscreen will be automatically
     * dismissed once the authentication challenge is completed. For example, completing a biometric
     * authentication challenge via face unlock or fingerprint sensor can automatically bypass the
     * lockscreen.
     */
    val isBypassEnabled: StateFlow<Boolean> = repository.isBypassEnabled

    /**
     * Attempt to enter the device and dismiss the lockscreen. If authentication is required to
     * unlock the device it will transition to bouncer.
     *
     * @param callback An optional callback to invoke when the attempt succeeds, fails, or is
     *   canceled
     */
    @JvmOverloads
    fun attemptDeviceEntry(callback: IKeyguardDismissCallback? = null) {
        callback?.let { dismissCallbackRegistry.addCallback(it) }

        // TODO (b/307768356),
        //       1. Check if the device is already authenticated by trust agent/passive biometrics
        //       2. Show SPFS/UDFPS bouncer if it is available AlternateBouncerInteractor.show
        //       3. For face auth only setups trigger face auth, delay transitioning to bouncer for
        //          a small amount of time.
        //       4. Transition to bouncer scene
        applicationScope.launch {
            if (isAuthenticationRequired()) {
                if (alternateBouncerInteractor.canShowAlternateBouncer.value) {
                    alternateBouncerInteractor.forceShow()
                } else {
                    sceneInteractor.showOverlay(
                        overlay = Overlays.Bouncer,
                        loggingReason = "request to unlock device while authentication required",
                    )
                }
            } else {
                sceneInteractor.changeScene(
                    toScene = Scenes.Gone,
                    loggingReason = "request to unlock device while authentication isn't required",
                )
            }
        }
    }

    /**
     * Returns `true` if the device currently requires authentication before entry is granted;
     * `false` if the device can be entered without authenticating first.
     */
    suspend fun isAuthenticationRequired(): Boolean {
        return !deviceUnlockedInteractor.deviceUnlockStatus.value.isUnlocked &&
            authenticationInteractor.getAuthenticationMethod().isSecure
    }

    /**
     * Whether the lockscreen is enabled for the current user. This is `true` whenever the user has
     * chosen any secure authentication method and even if they set the lockscreen to be dismissed
     * when the user swipes on it.
     */
    suspend fun isLockscreenEnabled(): Boolean {
        return repository.isLockscreenEnabled()
    }

    /**
     * Forces a refresh of the value of [isLockscreenEnabled] such that the flow emits the latest
     * value.
     *
     * Without calling this method, the flow will have a stale value unless the collector is removed
     * and re-added.
     */
    suspend fun refreshLockscreenEnabled() {
        isLockscreenEnabled()
    }

    /** Locks the device instantly. */
    fun lockNow(debuggingReason: String) {
        deviceUnlockedInteractor.lockNow(debuggingReason)
    }
}
