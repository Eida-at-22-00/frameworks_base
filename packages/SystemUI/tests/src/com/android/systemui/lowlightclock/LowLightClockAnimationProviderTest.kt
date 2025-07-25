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

package com.android.systemui.lowlightclock

import android.animation.Animator
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
class LowLightClockAnimationProviderTest : SysuiTestCase() {

    private val underTest by lazy {
        LowLightClockAnimationProvider(
            Y_TRANSLATION_ANIMATION_OFFSET,
            Y_TRANSLATION_ANIMATION_DURATION_MILLIS,
            ALPHA_ANIMATION_IN_START_DELAY_MILLIS,
            ALPHA_ANIMATION_DURATION_MILLIS,
        )
    }

    @Test
    fun animationOutEndsImmediatelyIfViewIsNull() {
        val animator = underTest.provideAnimationOut(null, null)

        val listener = mock<Animator.AnimatorListener>()
        animator.addListener(listener)

        animator.start()
        verify(listener).onAnimationStart(any(), eq(false))
        verify(listener).onAnimationEnd(any(), eq(false))
    }

    @Test
    fun animationInEndsImmediatelyIfViewIsNull() {
        val animator = underTest.provideAnimationIn(null, null)

        val listener = mock<Animator.AnimatorListener>()
        animator.addListener(listener)

        animator.start()
        verify(listener).onAnimationStart(any(), eq(false))
        verify(listener).onAnimationEnd(any(), eq(false))
    }

    private companion object {
        const val Y_TRANSLATION_ANIMATION_OFFSET = 100
        const val Y_TRANSLATION_ANIMATION_DURATION_MILLIS = 100L
        const val ALPHA_ANIMATION_IN_START_DELAY_MILLIS = 200L
        const val ALPHA_ANIMATION_DURATION_MILLIS = 300L
    }
}
