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

package com.android.systemui.keyguard.domain.interactor

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.os.UserManager
import android.provider.DeviceConfig
import android.util.Log
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags
import com.android.internal.statusbar.IStatusBarService
import com.android.systemui.CoreStartable
import com.android.systemui.authentication.domain.interactor.AuthenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.deviceconfig.domain.interactor.DeviceConfigInteractor
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.keyguard.KeyguardWmStateRefactor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.navigation.domain.interactor.NavigationInteractor
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessModel
import com.android.systemui.process.ProcessWrapper
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val TAG = StatusBarDisableFlagsInteractor::class.simpleName

/**
 * Logic around StatusBarService#disableForUser, which is used to disable the home and recents
 * button in certain device states.
 *
 * TODO(b/362313975): Remove post-Flexiglass, this duplicates StatusBarStartable logic.
 */
@SysUISingleton
class StatusBarDisableFlagsInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    @ShadeDisplayAware private val context: Context,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val deviceEntryFaceAuthInteractor: DeviceEntryFaceAuthInteractor,
    private val statusBarService: IStatusBarService,
    private val processWrapper: ProcessWrapper,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    selectedUserInteractor: SelectedUserInteractor,
    deviceConfigInteractor: DeviceConfigInteractor,
    navigationInteractor: NavigationInteractor,
    authenticationInteractor: AuthenticationInteractor,
    powerInteractor: PowerInteractor,
) : CoreStartable {

    private val disableToken: IBinder = Binder()

    private val disableFlagsForUserId =
        if (!KeyguardWmStateRefactor.isEnabled || SceneContainerFlag.isEnabled) {
            flowOf(Pair(0, StatusBarManager.DISABLE_NONE))
        } else {
            combine(
                    selectedUserInteractor.selectedUser,
                    keyguardTransitionInteractor.startedKeyguardTransitionStep.map { it.to },
                    deviceConfigInteractor.property(
                        namespace = DeviceConfig.NAMESPACE_SYSTEMUI,
                        name = SystemUiDeviceConfigFlags.NAV_BAR_HANDLE_SHOW_OVER_LOCKSCREEN,
                        default = true,
                    ),
                    navigationInteractor.isGesturalMode,
                    authenticationInteractor.authenticationMethod,
                    powerInteractor.detailedWakefulness,
                ) { values ->
                    val selectedUserId = values[0] as Int
                    val startedState = values[1] as KeyguardState
                    val isShowHomeOverLockscreen = values[2] as Boolean
                    val isGesturalMode = values[3] as Boolean
                    val authenticationMethod = values[4] as AuthenticationMethodModel
                    val wakefulnessModel = values[5] as WakefulnessModel
                    val isOccluded = startedState == KeyguardState.OCCLUDED

                    val hideHomeAndRecentsForBouncer =
                        startedState == KeyguardState.PRIMARY_BOUNCER ||
                            startedState == KeyguardState.ALTERNATE_BOUNCER
                    val isKeyguardShowing = startedState != KeyguardState.GONE
                    val isPowerGestureIntercepted =
                        with(wakefulnessModel) {
                            isAwake() &&
                                powerButtonLaunchGestureTriggered &&
                                lastSleepReason == WakeSleepReason.POWER_BUTTON
                        }

                    var flags = StatusBarManager.DISABLE_NONE

                    if (hideHomeAndRecentsForBouncer || (isKeyguardShowing && !isOccluded)) {
                        // Hide the home button/nav handle if we're on keyguard and either:
                        // - Going to AOD, in which case the handle should animate away.
                        // - Nav handle is configured not to show on lockscreen.
                        // - There is no nav handle.
                        if (
                            startedState == KeyguardState.AOD ||
                                !isShowHomeOverLockscreen ||
                                !isGesturalMode
                        ) {
                            flags = flags or StatusBarManager.DISABLE_HOME
                        }
                        flags = flags or StatusBarManager.DISABLE_RECENT
                    }

                    if (
                        isPowerGestureIntercepted &&
                            isOccluded &&
                            authenticationMethod.isSecure &&
                            deviceEntryFaceAuthInteractor.isFaceAuthEnabledAndEnrolled()
                    ) {
                        flags = flags or StatusBarManager.DISABLE_RECENT
                    }

                    selectedUserId to flags
                }
                .distinctUntilChanged()
        }

    @SuppressLint("WrongConstant", "NonInjectedService")
    override fun start() {
        if (!KeyguardWmStateRefactor.isEnabled || SceneContainerFlag.isEnabled) {
            return
        }

        //  TODO(b/341604160): Remove this blocking logic once StatusBarManagerService supports
        //  visible background users properly.
        if (
            UserManager.isVisibleBackgroundUsersEnabled() &&
                !processWrapper.isSystemUser() &&
                !processWrapper.isForegroundUserOrProfile()
        ) {
            // Currently, only one SysUI process can register with IStatusBarService to listen
            // for the CommandQueue events.
            // In the Multi Display configuration with concurrent multi users (primarily used
            // in Automotive), a visible background user (Automotive Multi Display passengers)
            // could also access this code path. Given this limitation and we only allow the
            // current user's SysUI process to register with IStatusBarService, we need to prevent
            // calls into IStatusBarService from visible background users.
            Log.d(TAG, "Status bar manager is disabled for visible background users")
            return
        }

        scope.launch {
            disableFlagsForUserId.collect { (selectedUserId, flags) ->
                if (context.getSystemService(Context.STATUS_BAR_SERVICE) == null) {
                    return@collect
                }

                withContext(backgroundDispatcher) {
                    try {
                        statusBarService.disableForUser(
                            flags,
                            disableToken,
                            context.packageName,
                            selectedUserId,
                        )
                    } catch (e: RemoteException) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }
}
