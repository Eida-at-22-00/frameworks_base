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

package com.android.systemui.screenrecord

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.UserHandle
import android.view.Display
import android.view.MotionEvent.ACTION_MOVE
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Switch
import androidx.annotation.LayoutRes
import com.android.systemui.Prefs
import com.android.systemui.mediaprojection.MediaProjectionCaptureTarget
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger
import com.android.systemui.mediaprojection.appselector.MediaProjectionAppSelectorActivity
import com.android.systemui.mediaprojection.permission.BaseMediaProjectionPermissionViewBinder
import com.android.systemui.mediaprojection.permission.ENTIRE_SCREEN
import com.android.systemui.mediaprojection.permission.SINGLE_APP
import com.android.systemui.mediaprojection.permission.ScreenShareMode
import com.android.systemui.mediaprojection.permission.ScreenShareOption
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.settings.UserContextProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class ScreenRecordPermissionViewBinder(
    private val hostUserHandle: UserHandle,
    private val hostUid: Int,
    mediaProjectionMetricsLogger: MediaProjectionMetricsLogger,
    @ScreenShareMode defaultSelectedMode: Int,
    displayManager: DisplayManager,
    private val controller: RecordingController,
    private val activityStarter: ActivityStarter,
    private val userContextProvider: UserContextProvider,
    private val onStartRecordingClicked: Runnable?,
) :
    BaseMediaProjectionPermissionViewBinder(
        createOptionList(displayManager),
        appName = null,
        hostUid = hostUid,
        mediaProjectionMetricsLogger,
        defaultSelectedMode,
    ) {
    @AssistedInject
    constructor(
        @Assisted hostUserHandle: UserHandle,
        @Assisted hostUid: Int,
        mediaProjectionMetricsLogger: MediaProjectionMetricsLogger,
        displayManager: DisplayManager,
        @Assisted controller: RecordingController,
        activityStarter: ActivityStarter,
        userContextProvider: UserContextProvider,
        @Assisted onStartRecordingClicked: Runnable?,
    ) : this(
        hostUserHandle,
        hostUid,
        mediaProjectionMetricsLogger,
        defaultSelectedMode = SINGLE_APP,
        displayManager,
        controller,
        activityStarter,
        userContextProvider,
        onStartRecordingClicked,
    )

    @AssistedFactory
    interface Factory {
        fun create(
            hostUserHandle: UserHandle,
            hostUid: Int,
            recordingController: RecordingController,
            onStartRecordingClicked: Runnable?,
        ): ScreenRecordPermissionViewBinder
    }

    private val isHEVCAllowed: Boolean = controller.isHEVCAllowed()
    private lateinit var tapsSwitch: Switch
    private lateinit var audioSwitch: Switch
    private lateinit var tapsView: View
    private lateinit var options: Spinner
    private lateinit var lowQualitySpinner: Spinner
    private lateinit var skipTimeSwitch: Switch
    private lateinit var hevcSwitch: Switch

    override fun bind(view: View) {
        super.bind(view)
        initRecordOptionsView()
        setStartButtonOnClickListener { startButtonOnClicked() }
    }

    fun startButtonOnClicked() {
        onStartRecordingClicked?.run()
        if (selectedScreenShareOption.mode == ENTIRE_SCREEN) {
            requestScreenCapture(
                captureTarget = null,
                displayId = selectedScreenShareOption.displayId,
            )
        }
        if (selectedScreenShareOption.mode == SINGLE_APP) {
            val intent =
                Intent(containerView.context, MediaProjectionAppSelectorActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // We can't start activity for result here so we use result receiver to get
            // the selected target to capture
            intent.putExtra(
                MediaProjectionAppSelectorActivity.EXTRA_CAPTURE_REGION_RESULT_RECEIVER,
                CaptureTargetResultReceiver(),
            )

            intent.putExtra(
                MediaProjectionAppSelectorActivity.EXTRA_HOST_APP_USER_HANDLE,
                hostUserHandle,
            )
            intent.putExtra(MediaProjectionAppSelectorActivity.EXTRA_HOST_APP_UID, hostUid)
            intent.putExtra(
                MediaProjectionAppSelectorActivity.EXTRA_SCREEN_SHARE_TYPE,
                MediaProjectionAppSelectorActivity.ScreenShareType.ScreenRecord.name,
            )
            activityStarter.startActivity(intent, /* dismissShade= */ true)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initRecordOptionsView() {
        audioSwitch = containerView.requireViewById(R.id.screenrecord_audio_switch)
        tapsSwitch = containerView.requireViewById(R.id.screenrecord_taps_switch)
        skipTimeSwitch = containerView.requireViewById(R.id.screenrecord_skip_time_switch)
        lowQualitySpinner = containerView.requireViewById(R.id.screenrecord_lowquality_spinner)
        hevcSwitch = containerView.requireViewById(R.id.screenrecord_hevc_switch)

        tapsView = containerView.requireViewById(R.id.show_taps)
        updateTapsViewVisibility()

        // Add these listeners so that the switch only responds to movement
        // within its target region, to meet accessibility requirements
        audioSwitch.setOnTouchListener { _, event -> event.action == ACTION_MOVE }
        tapsSwitch.setOnTouchListener { _, event -> event.action == ACTION_MOVE }

        options = containerView.requireViewById(R.id.screen_recording_options)
        val a: ArrayAdapter<*> =
            ScreenRecordingAdapter(
                containerView.context,
                android.R.layout.simple_spinner_dropdown_item,
                MODES,
            )
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        options.adapter = a
        options.setOnItemClickListenerInt { _: AdapterView<*>?, _: View?, _: Int, _: Long ->
            audioSwitch.isChecked = true
        }

        // disable redundant Touch & Hold accessibility action for Switch Access
        options.accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
                    super.onInitializeAccessibilityNodeInfo(host, info)
                }
            }
        options.isLongClickable = false

        val b: ArrayAdapter<*> =
            ScreenRecordingQualityAdapter(
                containerView.context,
                android.R.layout.simple_spinner_dropdown_item,
                QUALITIES
            )
        b.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        lowQualitySpinner.adapter = b

        // disable redundant Touch & Hold accessibility action for Switch Access
        lowQualitySpinner.accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo
                ) {
                    info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
                    super.onInitializeAccessibilityNodeInfo(host, info)
                }
            }
        lowQualitySpinner.isLongClickable = false

        if (!isHEVCAllowed) {
            val hevcView: View = containerView.requireViewById(R.id.show_hevc)
            hevcView.visibility = View.GONE
        }

        val userContext = userContextProvider.userContext
        tapsSwitch.isChecked = Prefs.getInt(userContext, PREF_TAPS, 0) == 1
        lowQualitySpinner.setSelection(Prefs.getInt(userContext, PREF_LOW, 0))
        audioSwitch.isChecked = Prefs.getInt(userContext, PREF_AUDIO, 0) == 1
        options.setSelection(Prefs.getInt(userContext, PREF_AUDIO_SOURCE, 0))
        skipTimeSwitch.isChecked = Prefs.getInt(userContext, PREF_SKIP, 0) == 1
        hevcSwitch.isChecked = isHEVCAllowed && Prefs.getInt(userContext, PREF_HEVC, 1) == 1
    }

    override fun onItemSelected(adapterView: AdapterView<*>?, view: View, pos: Int, id: Long) {
        super.onItemSelected(adapterView, view, pos, id)
        updateTapsViewVisibility()
    }

    private fun updateTapsViewVisibility() {
        tapsView.visibility = if (selectedScreenShareOption.mode == SINGLE_APP) GONE else VISIBLE
    }

    @LayoutRes override fun getOptionsViewLayoutId(): Int = R.layout.screen_record_options

    /**
     * Starts screen capture after some countdown
     *
     * @param captureTarget target to capture (could be e.g. a task) or null to record the whole
     *   screen
     */
    private fun requestScreenCapture(
        captureTarget: MediaProjectionCaptureTarget?,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ) {
        val userContext = userContextProvider.userContext
        val showTaps = selectedScreenShareOption.mode != SINGLE_APP && tapsSwitch.isChecked
        val audioMode =
            if (audioSwitch.isChecked) options.selectedItem as ScreenRecordingAudioSource
            else ScreenRecordingAudioSource.NONE
        val lowQuality = lowQualitySpinner.selectedItemPosition
        val hevc = isHEVCAllowed && hevcSwitch.isChecked
        val skipTime = skipTimeSwitch.isChecked
        val startIntent =
            PendingIntent.getForegroundService(
                userContext,
                RecordingService.REQUEST_CODE,
                RecordingService.getStartIntent(
                    userContext,
                    Activity.RESULT_OK,
                    audioMode.ordinal,
                    showTaps,
                    displayId,
                    captureTarget,
                    lowQuality,
                    hevc,
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stopIntent =
            PendingIntent.getService(
                userContext,
                RecordingService.REQUEST_CODE,
                RecordingService.getStopIntent(userContext),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        savePreferences()
        controller.startCountdown(if (skipTime) NO_DELAY else DELAY_MS,
                                  INTERVAL_MS, startIntent, stopIntent)
    }

    private inner class CaptureTargetResultReceiver :
        ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
            if (resultCode == Activity.RESULT_OK) {
                val captureTarget =
                    resultData.getParcelable(
                        MediaProjectionAppSelectorActivity.KEY_CAPTURE_TARGET,
                        MediaProjectionCaptureTarget::class.java,
                    )

                // Start recording of the selected target
                requestScreenCapture(captureTarget)
            }
        }
    }

    public fun savePreferences() {
        val userContext = userContextProvider.userContext
        Prefs.putInt(userContext, PREF_TAPS, if (tapsSwitch.isChecked) 1 else 0)
        Prefs.putInt(userContext, PREF_LOW, lowQualitySpinner.selectedItemPosition)
        Prefs.putInt(userContext, PREF_AUDIO, if (audioSwitch.isChecked) 1 else 0)
        Prefs.putInt(userContext, PREF_AUDIO_SOURCE, options.selectedItemPosition)
        Prefs.putInt(userContext, PREF_SKIP, if (skipTimeSwitch.isChecked) 1 else 0)
        Prefs.putInt(userContext, PREF_HEVC, if (hevcSwitch.isChecked) 1 else 0)
    }

    companion object {
        private val MODES =
            listOf(
                ScreenRecordingAudioSource.INTERNAL,
                ScreenRecordingAudioSource.MIC,
                ScreenRecordingAudioSource.MIC_AND_INTERNAL,
            )
        private val QUALITIES = listOf(0, 1, 2)

        private const val DELAY_MS: Long = 3000
        private const val NO_DELAY: Long = 100
        private const val INTERVAL_MS: Long = 1000

        private const val PREF_TAPS = "screenrecord_show_taps"
        private const val PREF_LOW = "screenrecord_use_low_quality_2"
        private const val PREF_HEVC = "screenrecord_use_hevc"
        private const val PREF_AUDIO = "screenrecord_use_audio"
        private const val PREF_AUDIO_SOURCE = "screenrecord_audio_source"
        private const val PREF_SKIP = "screenrecord_skip_timer"

        private val RECORDABLE_DISPLAY_TYPES =
            intArrayOf(
                Display.TYPE_OVERLAY,
                Display.TYPE_EXTERNAL,
                Display.TYPE_INTERNAL,
                Display.TYPE_WIFI,
            )

        private val filterDeviceTypeFlag: Boolean =
            com.android.media.projection.flags.Flags
                .mediaProjectionConnectedDisplayNoVirtualDevice()

        fun createOptionList(displayManager: DisplayManager): List<ScreenShareOption> {
            val connectedDisplays = getConnectedDisplays(displayManager)

            val options =
                mutableListOf(
                    ScreenShareOption(
                        SINGLE_APP,
                        R.string.screenrecord_permission_dialog_option_text_single_app,
                        R.string.screenrecord_permission_dialog_warning_single_app,
                        startButtonText =
                            R.string
                                .media_projection_entry_generic_permission_dialog_continue_single_app,
                    ),
                    ScreenShareOption(
                        ENTIRE_SCREEN,
                        R.string.screenrecord_permission_dialog_option_text_entire_screen,
                        R.string.screenrecord_permission_dialog_warning_entire_screen,
                        startButtonText =
                            R.string.screenrecord_permission_dialog_continue_entire_screen,
                        displayId = Display.DEFAULT_DISPLAY,
                        displayName = Build.MODEL,
                    ),
                )

            if (connectedDisplays.isNotEmpty()) {
                options +=
                    connectedDisplays.map {
                        ScreenShareOption(
                            ENTIRE_SCREEN,
                            R.string
                                .screenrecord_permission_dialog_option_text_entire_screen_for_display,
                            warningText =
                                R.string
                                    .media_projection_entry_app_permission_dialog_warning_entire_screen,
                            startButtonText =
                                R.string
                                    .media_projection_entry_app_permission_dialog_continue_entire_screen,
                            displayId = it.displayId,
                            displayName = it.name,
                        )
                    }
            }
            return options.toList()
        }

        private fun getConnectedDisplays(displayManager: DisplayManager): List<Display> {
            if (!com.android.media.projection.flags.Flags.mediaProjectionConnectedDisplay()) {
                return emptyList()
            }
            return displayManager.displays.filter {
                it.displayId != Display.DEFAULT_DISPLAY &&
                    (!filterDeviceTypeFlag || it.type in RECORDABLE_DISPLAY_TYPES)
            }
        }
    }
}
