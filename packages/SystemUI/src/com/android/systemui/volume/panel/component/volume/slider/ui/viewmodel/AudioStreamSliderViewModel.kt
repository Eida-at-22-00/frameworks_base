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

package com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.annotation.DrawableRes
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.volume.domain.interactor.AudioVolumeInteractor
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.settingslib.volume.shared.model.AudioStreamModel
import com.android.settingslib.volume.shared.model.RingerMode
import com.android.systemui.Flags
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.UiBackground
import com.android.systemui.haptics.slider.SliderHapticFeedbackFilter
import com.android.systemui.haptics.slider.compose.ui.SliderHapticsViewModel
import com.android.systemui.modes.shared.ModesUiIcons
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import com.android.systemui.util.kotlin.combine
import com.android.systemui.volume.domain.interactor.AudioSharingInteractor
import com.android.systemui.volume.panel.shared.VolumePanelLogger
import com.android.systemui.volume.panel.ui.VolumePanelUiEvent
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Models a particular slider state. */
class AudioStreamSliderViewModel
@AssistedInject
constructor(
    @Assisted private val audioStreamWrapper: FactoryAudioStreamWrapper,
    @Assisted private val coroutineScope: CoroutineScope,
    @UiBackground private val uiBackgroundContext: CoroutineContext,
    private val context: Context,
    private val audioVolumeInteractor: AudioVolumeInteractor,
    private val zenModeInteractor: ZenModeInteractor,
    audioSharingInteractor: AudioSharingInteractor,
    private val uiEventLogger: UiEventLogger,
    private val volumePanelLogger: VolumePanelLogger,
    private val hapticsViewModelFactory: SliderHapticsViewModel.Factory,
) : SliderViewModel {

    private val volumeChanges = MutableStateFlow<Int?>(null)
    private val audioStream = audioStreamWrapper.audioStream
    private val labelsByStream =
        mapOf(
            AudioStream(AudioManager.STREAM_MUSIC) to R.string.stream_music,
            AudioStream(AudioManager.STREAM_VOICE_CALL) to R.string.stream_voice_call,
            AudioStream(AudioManager.STREAM_RING) to R.string.stream_ring,
            AudioStream(AudioManager.STREAM_NOTIFICATION) to R.string.stream_notification,
            AudioStream(AudioManager.STREAM_ALARM) to R.string.stream_alarm,
        )
    private val uiEventByStream =
        mapOf(
            AudioStream(AudioManager.STREAM_MUSIC) to
                VolumePanelUiEvent.VOLUME_PANEL_MUSIC_SLIDER_TOUCHED,
            AudioStream(AudioManager.STREAM_VOICE_CALL) to
                VolumePanelUiEvent.VOLUME_PANEL_VOICE_CALL_SLIDER_TOUCHED,
            AudioStream(AudioManager.STREAM_RING) to
                VolumePanelUiEvent.VOLUME_PANEL_RING_SLIDER_TOUCHED,
            AudioStream(AudioManager.STREAM_NOTIFICATION) to
                VolumePanelUiEvent.VOLUME_PANEL_NOTIFICATION_SLIDER_TOUCHED,
            AudioStream(AudioManager.STREAM_ALARM) to
                VolumePanelUiEvent.VOLUME_PANEL_ALARM_SLIDER_TOUCHED,
        )

    override val slider: StateFlow<SliderState> =
        combine(
                audioVolumeInteractor.getAudioStream(audioStream),
                audioVolumeInteractor.canChangeVolume(audioStream),
                audioVolumeInteractor.ringerMode,
                streamDisabledMessage(),
                audioSharingInteractor.isInAudioSharing,
                audioSharingInteractor.primaryDevice,
            ) { model, isEnabled, ringerMode, streamDisabledMessage, isInAudioSharing, primaryDevice
                ->
                volumePanelLogger.onVolumeUpdateReceived(audioStream, model.volume)
                model.toState(
                    isEnabled,
                    ringerMode,
                    streamDisabledMessage,
                    isInAudioSharing,
                    primaryDevice,
                )
            }
            .stateIn(coroutineScope, SharingStarted.Eagerly, SliderState.Empty)

    init {
        volumeChanges
            .filterNotNull()
            .onEach {
                volumePanelLogger.onSetVolumeRequested(audioStream, it)
                audioVolumeInteractor.setVolume(audioStream, it)
            }
            .launchIn(coroutineScope)
    }

    override fun onValueChanged(state: SliderState, newValue: Float) {
        val audioViewModel = state as? State
        audioViewModel ?: return
        volumeChanges.tryEmit(newValue.roundToInt())
    }

    override fun onValueChangeFinished() {
        uiEventByStream[audioStream]?.let { uiEventLogger.log(it) }
    }

    override fun toggleMuted(state: SliderState) {
        val audioViewModel = state as? State
        audioViewModel ?: return
        coroutineScope.launch {
            audioVolumeInteractor.setMuted(audioStream, !audioViewModel.audioStreamModel.isMuted)
        }
    }

    override fun getSliderHapticsViewModelFactory(): SliderHapticsViewModel.Factory? =
        if (Flags.hapticsForComposeSliders() && slider.value != SliderState.Empty) {
            hapticsViewModelFactory
        } else {
            null
        }

    private suspend fun AudioStreamModel.toState(
        isEnabled: Boolean,
        ringerMode: RingerMode,
        disabledMessage: String?,
        inAudioSharing: Boolean,
        primaryDevice: CachedBluetoothDevice?,
    ): State =
        withContext(uiBackgroundContext) {
            val label = getLabel(inAudioSharing, primaryDevice)
            State(
                value = volume.toFloat(),
                valueRange = volumeRange.first.toFloat()..volumeRange.last.toFloat(),
                hapticFilter = createHapticFilter(ringerMode),
                icon = getIcon(ringerMode, inAudioSharing),
                label = label,
                disabledMessage = disabledMessage,
                isEnabled = isEnabled,
                step = volumeRange.step.toFloat(),
                a11yContentDescription =
                    if (isEnabled) {
                        label
                    } else {
                        disabledMessage?.let {
                            context.getString(
                                R.string.volume_slider_disabled_message_template,
                                label,
                                disabledMessage,
                            )
                        } ?: label
                    },
                a11yClickDescription =
                    if (isAffectedByMute) {
                        context.getString(
                            if (isMuted) {
                                R.string.volume_panel_hint_unmute
                            } else {
                                R.string.volume_panel_hint_mute
                            },
                            label,
                        )
                    } else {
                        null
                    },
                a11yStateDescription =
                    if (isMuted) {
                        context.getString(
                            if (isAffectedByRingerMode) {
                                if (ringerMode.value == AudioManager.RINGER_MODE_VIBRATE) {
                                    R.string.volume_panel_hint_vibrate
                                } else {
                                    R.string.volume_panel_hint_muted
                                }
                            } else {
                                R.string.volume_panel_hint_muted
                            }
                        )
                    } else {
                        null
                    },
                audioStreamModel = this@toState,
                isMutable = isAffectedByMute,
            )
        }

    private fun AudioStreamModel.createHapticFilter(
        ringerMode: RingerMode
    ): SliderHapticFeedbackFilter =
        when (audioStream.value) {
            AudioManager.STREAM_RING -> SliderHapticFeedbackFilter(vibrateOnLowerBookend = false)
            AudioManager.STREAM_NOTIFICATION ->
                SliderHapticFeedbackFilter(
                    vibrateOnLowerBookend = ringerMode.value != AudioManager.RINGER_MODE_VIBRATE
                )
            else -> SliderHapticFeedbackFilter()
        }

    // TODO: b/372213356 - Figure out the correct messages for VOICE_CALL and RING.
    //  In fact, VOICE_CALL should not be affected by interruption filtering at all.
    private fun streamDisabledMessage(): Flow<String> {
        return if (ModesUiIcons.isEnabled) {
            if (audioStream.value == AudioManager.STREAM_NOTIFICATION) {
                flowOf(context.getString(R.string.stream_notification_unavailable))
            } else {
                if (zenModeInteractor.canBeBlockedByZenMode(audioStream)) {
                    zenModeInteractor
                        .activeModesBlockingStream(audioStream)
                        .map { blockingZenModes ->
                            blockingZenModes.mainMode?.name?.let {
                                context.getString(R.string.stream_unavailable_by_modes, it)
                            } ?: context.getString(R.string.stream_unavailable_by_unknown)
                        }
                        .distinctUntilChanged()
                } else {
                    flowOf(context.getString(R.string.stream_unavailable_by_unknown))
                }
            }
        } else {
            flowOf(
                if (audioStream.value == AudioManager.STREAM_NOTIFICATION) {
                    context.getString(R.string.stream_notification_unavailable)
                } else {
                    context.getString(R.string.stream_alarm_unavailable)
                }
            )
        }
    }

    private fun AudioStreamModel.getLabel(
        inAudioSharing: Boolean,
        primaryDevice: CachedBluetoothDevice?,
    ): String =
        if (
            Flags.showAudioSharingSliderInVolumePanel() &&
                audioStream.value == AudioManager.STREAM_MUSIC &&
                inAudioSharing
        ) {
            primaryDevice?.name ?: context.getString(R.string.stream_music)
        } else {
            labelsByStream[audioStream]?.let(context::getString)
                ?: error("No label for the stream: $audioStream")
        }

    private fun AudioStreamModel.getIcon(
        ringerMode: RingerMode,
        inAudioSharing: Boolean,
    ): Icon.Loaded {
        val iconResource: Int =
            if (isMuted) {
                if (isAffectedByRingerMode) {
                    if (ringerMode.value == AudioManager.RINGER_MODE_VIBRATE) {
                        R.drawable.ic_volume_ringer_vibrate
                    } else {
                        R.drawable.ic_volume_off
                    }
                } else {
                    if (
                        Flags.showAudioSharingSliderInVolumePanel() &&
                            audioStream.value == AudioManager.STREAM_MUSIC &&
                            inAudioSharing
                    ) {
                        R.drawable.ic_volume_media_bt_mute
                    } else {
                        R.drawable.ic_volume_off
                    }
                }
            } else {
                getIconByStream(audioStream, inAudioSharing)
            }
        return Icon.Loaded(
            drawable = context.getDrawable(iconResource)!!,
            contentDescription = null,
            res = iconResource,
        )
    }

    @DrawableRes
    private fun getIconByStream(audioStream: AudioStream, inAudioSharing: Boolean): Int =
        when (audioStream.value) {
            AudioManager.STREAM_MUSIC ->
                if (Flags.showAudioSharingSliderInVolumePanel() && inAudioSharing) {
                    R.drawable.ic_volume_media_bt
                } else R.drawable.ic_music_note
            AudioManager.STREAM_VOICE_CALL -> R.drawable.ic_call
            AudioManager.STREAM_RING -> R.drawable.ic_ring_volume
            AudioManager.STREAM_NOTIFICATION -> R.drawable.ic_volume_ringer
            AudioManager.STREAM_ALARM -> R.drawable.ic_volume_alarm
            else -> {
                Log.wtf(TAG, "No icon for the stream: $audioStream")
                R.drawable.ic_music_note
            }
        }

    private val AudioStreamModel.volumeRange: IntRange
        get() = minVolume..maxVolume

    private data class State(
        override val value: Float,
        override val valueRange: ClosedFloatingPointRange<Float>,
        override val step: Float,
        override val hapticFilter: SliderHapticFeedbackFilter,
        override val icon: Icon.Loaded?,
        override val label: String,
        override val disabledMessage: String?,
        override val isEnabled: Boolean,
        override val a11yClickDescription: String?,
        override val a11yStateDescription: String?,
        override val a11yContentDescription: String,
        override val isMutable: Boolean,
        val audioStreamModel: AudioStreamModel,
    ) : SliderState

    @AssistedFactory
    interface Factory {

        fun create(
            audioStream: FactoryAudioStreamWrapper,
            coroutineScope: CoroutineScope,
        ): AudioStreamSliderViewModel
    }

    /**
     * AudioStream is a value class that compiles into a primitive. This fail AssistedFactory build
     * when using [AudioStream] directly because it expects another type.
     */
    class FactoryAudioStreamWrapper(val audioStream: AudioStream)

    private companion object {
        const val TAG = "AudioStreamSliderViewModel"
    }
}
