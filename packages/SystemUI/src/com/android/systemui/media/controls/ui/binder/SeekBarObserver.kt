/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media.controls.ui.binder

import android.animation.Animator
import android.animation.ObjectAnimator
import android.text.format.DateUtils
import androidx.annotation.UiThread
import androidx.lifecycle.Observer
import com.android.app.animation.Interpolators
import com.android.app.tracing.TraceStateLogger
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.media.controls.ui.drawable.SquigglyProgress
import com.android.systemui.media.controls.ui.view.MediaViewHolder
import com.android.systemui.media.controls.ui.viewmodel.SeekBarViewModel
import com.android.systemui.res.R

private const val TAG = "SeekBarObserver"

/**
 * Observer for changes from SeekBarViewModel.
 *
 * <p>Updates the seek bar views in response to changes to the model.
 */
open class SeekBarObserver(
    private val holder: MediaViewHolder,
    private var alwaysOnTime: Boolean
) : Observer<SeekBarViewModel.Progress> {

    companion object {
        @JvmStatic val RESET_ANIMATION_DURATION_MS: Int = 750
        @JvmStatic val RESET_ANIMATION_THRESHOLD_MS: Int = 250
    }

    // Trace state loggers for playing and listening states of progress bar.
    private val playingStateLogger = TraceStateLogger("$TAG#playing")
    private val listeningStateLogger = TraceStateLogger("$TAG#listening")

    val seekBarEnabledMaxHeight =
        holder.seekBar.context.resources.getDimensionPixelSize(
            R.dimen.qs_media_enabled_seekbar_height
        )
    val seekBarDisabledHeight =
        holder.seekBar.context.resources.getDimensionPixelSize(
            R.dimen.qs_media_disabled_seekbar_height
        )
    val seekBarEnabledVerticalPadding =
        holder.seekBar.context.resources.getDimensionPixelSize(
            R.dimen.qs_media_session_enabled_seekbar_vertical_padding
        )
    val seekBarDisabledVerticalPadding =
        holder.seekBar.context.resources.getDimensionPixelSize(
            R.dimen.qs_media_session_disabled_seekbar_vertical_padding
        )
    var seekBarResetAnimator: Animator? = null
    var animationEnabled: Boolean = true

    init {
        val seekBarProgressWavelength =
            holder.seekBar.context.resources
                .getDimensionPixelSize(R.dimen.qs_media_seekbar_progress_wavelength)
                .toFloat()
        val seekBarProgressAmplitude =
            holder.seekBar.context.resources
                .getDimensionPixelSize(R.dimen.qs_media_seekbar_progress_amplitude)
                .toFloat()
        val seekBarProgressPhase =
            holder.seekBar.context.resources
                .getDimensionPixelSize(R.dimen.qs_media_seekbar_progress_phase)
                .toFloat()
        val seekBarProgressStrokeWidth =
            holder.seekBar.context.resources
                .getDimensionPixelSize(R.dimen.qs_media_seekbar_progress_stroke_width)
                .toFloat()
        val progressDrawable = holder.seekBar.progressDrawable as? SquigglyProgress
        progressDrawable?.let {
            it.waveLength = seekBarProgressWavelength
            it.lineAmplitude = seekBarProgressAmplitude
            it.phaseSpeed = seekBarProgressPhase
            it.strokeWidth = seekBarProgressStrokeWidth
        }
    }

    /** Updates seek bar views when the data model changes. */
    @UiThread
    override fun onChanged(data: SeekBarViewModel.Progress) {
        val progressDrawable = holder.seekBar.progressDrawable as? SquigglyProgress
        if (!data.enabled) {
            if (holder.seekBar.maxHeight != seekBarDisabledHeight) {
                holder.seekBar.maxHeight = seekBarDisabledHeight
                setVerticalPadding(seekBarDisabledVerticalPadding)
            }
            holder.seekBar.isEnabled = false
            progressDrawable?.animate = false
            holder.seekBar.thumb.alpha = 0
            holder.seekBar.progress = 0
            holder.seekBar.contentDescription = ""
            holder.scrubbingElapsedTimeView.text = ""
            holder.scrubbingTotalTimeView.text = ""
            return
        }

        playingStateLogger.log("${data.playing}")
        listeningStateLogger.log("${data.listening}")

        holder.seekBar.thumb.alpha = if (data.seekAvailable) 255 else 0
        holder.seekBar.isEnabled = data.seekAvailable
        progressDrawable?.animate =
            data.playing && !data.scrubbing && animationEnabled && data.listening
        progressDrawable?.minWaveEndpoint = if (data.seekAvailable) 0f else 0.2f
        progressDrawable?.matchedWaveEndpoint = if (data.seekAvailable) 1f else 0.6f

        if (holder.seekBar.maxHeight != seekBarEnabledMaxHeight) {
            holder.seekBar.maxHeight = seekBarEnabledMaxHeight
            setVerticalPadding(seekBarEnabledVerticalPadding)
        }

        holder.seekBar.setMax(data.duration)
        if (data.scrubbing || alwaysOnTime) {
            holder.scrubbingTotalTimeView.text = formatTimeLabel(data.duration)
        }

        data.elapsedTime?.let {
            if (!data.scrubbing && !(seekBarResetAnimator?.isRunning ?: false)) {
                if (
                    it <= RESET_ANIMATION_THRESHOLD_MS &&
                        holder.seekBar.progress > RESET_ANIMATION_THRESHOLD_MS
                ) {
                    // This animation resets for every additional update to zero.
                    val animator = buildResetAnimator(it)
                    animator.start()
                    seekBarResetAnimator = animator
                } else {
                    holder.seekBar.progress = it
                }
            }

            if (data.scrubbing || alwaysOnTime) {
                holder.scrubbingElapsedTimeView.text = formatTimeLabel(it)
            }
        }
    }

    /** Returns a time string suitable for display, e.g. "12:34" */
    private fun formatTimeLabel(milliseconds: Int): CharSequence {
        return DateUtils.formatElapsedTime(milliseconds / DateUtils.SECOND_IN_MILLIS)
    }

    @UiThread
    fun updateContentDescription(
        elapsedTimeDescription: CharSequence,
        durationDescription: CharSequence,
    ) {
        holder.seekBar.contentDescription =
            holder.seekBar.context.getString(
                R.string.controls_media_seekbar_description,
                elapsedTimeDescription,
                durationDescription,
            )
    }

    @VisibleForTesting
    open fun buildResetAnimator(targetTime: Int): Animator {
        val animator =
            ObjectAnimator.ofInt(
                holder.seekBar,
                "progress",
                holder.seekBar.progress,
                targetTime + RESET_ANIMATION_DURATION_MS,
            )
        animator.setAutoCancel(true)
        animator.duration = RESET_ANIMATION_DURATION_MS.toLong()
        animator.interpolator = Interpolators.EMPHASIZED
        return animator
    }

    @UiThread
    fun setVerticalPadding(padding: Int) {
        val leftPadding = holder.seekBar.paddingLeft
        val rightPadding = holder.seekBar.paddingRight
        val bottomPadding = holder.seekBar.paddingBottom
        holder.seekBar.setPadding(leftPadding, padding, rightPadding, bottomPadding)
    }

    fun setAlwaysOnTime(enabled: Boolean) {
        alwaysOnTime = enabled
    }
}
