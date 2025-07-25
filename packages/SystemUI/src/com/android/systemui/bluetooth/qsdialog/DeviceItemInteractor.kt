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

package com.android.systemui.bluetooth.qsdialog

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import com.android.settingslib.bluetooth.BluetoothCallback
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.volume.domain.interactor.AudioModeInteractor
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Holds business logic for the Bluetooth Dialog after clicking on the Bluetooth QS tile. */
@SysUISingleton
class DeviceItemInteractor
@Inject
constructor(
    private val bluetoothTileDialogRepository: BluetoothTileDialogRepository,
    private val audioSharingInteractor: AudioSharingInteractor,
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter(),
    private val localBluetoothManager: LocalBluetoothManager?,
    private val systemClock: SystemClock,
    private val logger: BluetoothTileDialogLogger,
    private val deviceItemFactoryList: List<@JvmSuppressWildcards DeviceItemFactory>,
    private val deviceItemDisplayPriority: List<@JvmSuppressWildcards DeviceItemType>,
    @Application private val coroutineScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val audioModeInteractor: AudioModeInteractor,
) {

    private val mutableDeviceItemUpdate: MutableSharedFlow<List<DeviceItem>> =
        MutableSharedFlow(extraBufferCapacity = 1)
    val deviceItemUpdate
        get() = mutableDeviceItemUpdate.asSharedFlow()

    private val mutableShowSeeAllUpdate: MutableStateFlow<Boolean> = MutableStateFlow(false)
    internal val showSeeAllUpdate
        get() = mutableShowSeeAllUpdate.asStateFlow()

    val deviceItemUpdateRequest: SharedFlow<Unit> =
        conflatedCallbackFlow {
                val listener =
                    object : BluetoothCallback {
                        override fun onActiveDeviceChanged(
                            activeDevice: CachedBluetoothDevice?,
                            bluetoothProfile: Int,
                        ) {
                            super.onActiveDeviceChanged(activeDevice, bluetoothProfile)
                            logger.logActiveDeviceChanged(activeDevice?.address, bluetoothProfile)
                            trySendWithFailureLogging(Unit, TAG, "onActiveDeviceChanged")
                        }

                        override fun onProfileConnectionStateChanged(
                            cachedDevice: CachedBluetoothDevice,
                            state: Int,
                            bluetoothProfile: Int,
                        ) {
                            super.onProfileConnectionStateChanged(
                                cachedDevice,
                                state,
                                bluetoothProfile,
                            )
                            logger.logProfileConnectionStateChanged(
                                cachedDevice.address,
                                state.toString(),
                                bluetoothProfile,
                            )
                            trySendWithFailureLogging(Unit, TAG, "onProfileConnectionStateChanged")
                        }

                        override fun onAclConnectionStateChanged(
                            cachedDevice: CachedBluetoothDevice,
                            state: Int,
                        ) {
                            super.onAclConnectionStateChanged(cachedDevice, state)
                            trySendWithFailureLogging(Unit, TAG, "onAclConnectionStateChanged")
                        }
                    }
                localBluetoothManager?.eventManager?.registerCallback(listener)
                awaitClose { localBluetoothManager?.eventManager?.unregisterCallback(listener) }
            }
            .flowOn(backgroundDispatcher)
            .shareIn(coroutineScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0))

    internal suspend fun updateDeviceItems(context: Context, trigger: DeviceFetchTrigger) {
        withContext(backgroundDispatcher) {
            if (!isActive) {
                return@withContext
            }
            val start = systemClock.elapsedRealtime()
            val audioSharingAvailable = audioSharingInteractor.audioSharingAvailable()
            val isOngoingCall = audioModeInteractor.isOngoingCall.first()
            val deviceItems =
                bluetoothTileDialogRepository.cachedDevices
                    .mapNotNull { cachedDevice ->
                        deviceItemFactoryList
                            .firstOrNull {
                                it.isFilterMatched(
                                    context,
                                    cachedDevice,
                                    isOngoingCall,
                                    audioSharingAvailable,
                                )
                            }
                            ?.create(context, cachedDevice)
                    }
                    .sort(deviceItemDisplayPriority, bluetoothAdapter?.mostRecentlyConnectedDevices)
            // Only emit when the job is not cancelled
            if (isActive) {
                mutableDeviceItemUpdate.tryEmit(deviceItems.take(MAX_DEVICE_ITEM_ENTRY))
                mutableShowSeeAllUpdate.tryEmit(deviceItems.size > MAX_DEVICE_ITEM_ENTRY)
                logger.logDeviceFetch(
                    JobStatus.FINISHED,
                    trigger,
                    systemClock.elapsedRealtime() - start,
                )
            } else {
                logger.logDeviceFetch(
                    JobStatus.CANCELLED,
                    trigger,
                    systemClock.elapsedRealtime() - start,
                )
            }
        }
    }

    private fun List<DeviceItem>.sort(
        displayPriority: List<DeviceItemType>,
        mostRecentlyConnectedDevices: List<BluetoothDevice>?,
    ): List<DeviceItem> {
        return this.sortedWith(
            compareBy<DeviceItem> { displayPriority.indexOf(it.type) }
                .thenBy {
                    mostRecentlyConnectedDevices?.indexOf(it.cachedBluetoothDevice.device) ?: 0
                }
        )
    }

    companion object {
        private const val TAG = "DeviceItemInteractor"
        private const val MAX_DEVICE_ITEM_ENTRY = 3
    }
}
