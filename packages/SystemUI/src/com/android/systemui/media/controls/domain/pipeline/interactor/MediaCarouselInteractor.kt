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

package com.android.systemui.media.controls.domain.pipeline.interactor

import android.app.PendingIntent
import android.media.MediaDescription
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.service.notification.StatusBarNotification
import com.android.internal.logging.InstanceId
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.media.controls.data.repository.MediaFilterRepository
import com.android.systemui.media.controls.domain.pipeline.MediaDataCombineLatest
import com.android.systemui.media.controls.domain.pipeline.MediaDataFilterImpl
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.media.controls.domain.pipeline.MediaDataProcessor
import com.android.systemui.media.controls.domain.pipeline.MediaDeviceManager
import com.android.systemui.media.controls.domain.pipeline.MediaSessionBasedFilter
import com.android.systemui.media.controls.domain.pipeline.MediaTimeoutListener
import com.android.systemui.media.controls.domain.resume.MediaResumeListener
import com.android.systemui.media.controls.shared.model.MediaCommonModel
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Encapsulates business logic for media pipeline. */
@SysUISingleton
class MediaCarouselInteractor
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    private val mediaDataProcessor: MediaDataProcessor,
    private val mediaTimeoutListener: MediaTimeoutListener,
    private val mediaResumeListener: MediaResumeListener,
    private val mediaSessionBasedFilter: MediaSessionBasedFilter,
    private val mediaDeviceManager: MediaDeviceManager,
    private val mediaDataCombineLatest: MediaDataCombineLatest,
    private val mediaDataFilter: MediaDataFilterImpl,
    private val mediaFilterRepository: MediaFilterRepository,
) : MediaDataManager, CoreStartable {

    /** Are there any media notifications active, including the recommendations? */
    // TODO(b/382680767): rename
    val hasActiveMediaOrRecommendation: StateFlow<Boolean> =
        mediaFilterRepository.selectedUserEntries
            .map { entries -> entries.any { it.value.active } }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /** Are there any media entries we should display, including the recommendations? */
    // TODO(b/382680767): rename
    val hasAnyMediaOrRecommendation: StateFlow<Boolean> =
        mediaFilterRepository.selectedUserEntries
            .map { entries -> entries.isNotEmpty() }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /** The current list for user media instances */
    val currentMedia: StateFlow<List<MediaCommonModel>> = mediaFilterRepository.currentMedia

    override fun start() {
        if (!SceneContainerFlag.isEnabled) {
            return
        }

        // Initialize the internal processing pipeline. The listeners at the front of the pipeline
        // are set as internal listeners so that they receive events. From there, events are
        // propagated through the pipeline. The end of the pipeline is currently mediaDataFilter,
        // so it is responsible for dispatching events to external listeners. To achieve this,
        // external listeners that are registered with [MediaDataManager.addListener] are actually
        // registered as listeners to mediaDataFilter.
        addInternalListener(mediaTimeoutListener)
        addInternalListener(mediaResumeListener)
        addInternalListener(mediaSessionBasedFilter)
        mediaSessionBasedFilter.addListener(mediaDeviceManager)
        mediaSessionBasedFilter.addListener(mediaDataCombineLatest)
        mediaDeviceManager.addListener(mediaDataCombineLatest)
        mediaDataCombineLatest.addListener(mediaDataFilter)

        // Set up links back into the pipeline for listeners that need to send events upstream.
        mediaTimeoutListener.timeoutCallback = { key: String, timedOut: Boolean ->
            mediaDataProcessor.setInactive(key, timedOut)
        }
        mediaTimeoutListener.stateCallback = { key: String, state: PlaybackState ->
            mediaDataProcessor.updateState(key, state)
        }
        mediaTimeoutListener.sessionCallback = { key: String ->
            mediaDataProcessor.onSessionDestroyed(key)
        }
        mediaResumeListener.setManager(this)
        mediaDataFilter.mediaDataProcessor = mediaDataProcessor
    }

    override fun addListener(listener: MediaDataManager.Listener) {
        mediaDataFilter.addListener(listener)
    }

    override fun removeListener(listener: MediaDataManager.Listener) {
        mediaDataFilter.removeListener(listener)
    }

    override fun setInactive(key: String, timedOut: Boolean, forceUpdate: Boolean) = unsupported

    override fun onNotificationAdded(key: String, sbn: StatusBarNotification) {
        mediaDataProcessor.onNotificationAdded(key, sbn)
    }

    override fun destroy() {
        mediaSessionBasedFilter.removeListener(mediaDeviceManager)
        mediaSessionBasedFilter.removeListener(mediaDataCombineLatest)
        mediaDeviceManager.removeListener(mediaDataCombineLatest)
        mediaDataCombineLatest.removeListener(mediaDataFilter)
        mediaDataProcessor.destroy()
    }

    override fun setResumeAction(key: String, action: Runnable?) {
        mediaDataProcessor.setResumeAction(key, action)
    }

    override fun addResumptionControls(
        userId: Int,
        desc: MediaDescription,
        action: Runnable,
        token: MediaSession.Token,
        appName: String,
        appIntent: PendingIntent,
        packageName: String,
    ) {
        mediaDataProcessor.addResumptionControls(
            userId,
            desc,
            action,
            token,
            appName,
            appIntent,
            packageName,
        )
    }

    override fun dismissMediaData(key: String, delay: Long, userInitiated: Boolean): Boolean {
        return mediaDataProcessor.dismissMediaData(key, delay, userInitiated)
    }

    fun removeMediaControl(instanceId: InstanceId, delay: Long) {
        mediaDataProcessor.dismissMediaData(instanceId, delay, userInitiated = false)
    }

    override fun dismissSmartspaceRecommendation(key: String, delay: Long) {}

    override fun onNotificationRemoved(key: String) {
        mediaDataProcessor.onNotificationRemoved(key)
    }

    override fun setMediaResumptionEnabled(isEnabled: Boolean) {
        mediaDataProcessor.setMediaResumptionEnabled(isEnabled)
    }

    override fun onSwipeToDismiss() {
        mediaDataFilter.onSwipeToDismiss()
    }

    override fun hasActiveMediaOrRecommendation() = mediaFilterRepository.hasActiveMedia()

    override fun hasAnyMediaOrRecommendation() = mediaFilterRepository.hasAnyMedia()

    override fun hasActiveMedia() = mediaFilterRepository.hasActiveMedia()

    override fun hasAnyMedia() = mediaFilterRepository.hasAnyMedia()

    override fun isRecommendationActive() = false

    fun reorderMedia() {
        mediaFilterRepository.setOrderedMedia()
    }

    /** Add a listener for internal events. */
    private fun addInternalListener(listener: MediaDataManager.Listener) =
        mediaDataProcessor.addInternalListener(listener)

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        mediaDeviceManager.dump(pw)
    }

    companion object {
        val unsupported: Nothing
            get() =
                error("Code path not supported when ${SceneContainerFlag.DESCRIPTION} is enabled")
    }
}
