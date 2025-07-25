/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.shared.clocks

import android.view.LayoutInflater
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.app.animation.Interpolators
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.FontVariationUtils
import com.android.systemui.animation.TextAnimator
import com.android.systemui.customization.R
import com.android.systemui.util.mockito.any
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.eq

@RunWith(AndroidJUnit4::class)
@SmallTest
class AnimatableClockViewTest : SysuiTestCase() {

    @JvmField @Rule val mockito = MockitoJUnit.rule()

    @Mock private lateinit var mockTextAnimator: TextAnimator
    private lateinit var clockView: AnimatableClockView

    @Before
    fun setUp() {
        val layoutInflater = LayoutInflater.from(context)
        whenever(mockTextAnimator.fontVariationUtils).thenReturn(FontVariationUtils())
        clockView =
            layoutInflater.inflate(R.layout.clock_default_small, null) as AnimatableClockView
        clockView.textAnimatorFactory = { _, _ -> mockTextAnimator }
    }

    @Test
    fun validateColorAnimationRunsBeforeMeasure() {
        clockView.setColors(100, 200)
        clockView.animateAppearOnLockscreen()
        clockView.measure(50, 50)

        verify(mockTextAnimator).fontVariationUtils
        verify(mockTextAnimator).glyphFilter = any()
        verify(mockTextAnimator)
            .setTextStyle(
                eq(TextAnimator.Style(fVar = "'wght' 300", color = 200)),
                eq(
                    TextAnimator.Animation(
                        animate = false,
                        duration = 833L,
                        interpolator = Interpolators.EMPHASIZED_DECELERATE,
                        onAnimationEnd = null,
                    )
                ),
            )
        verifyNoMoreInteractions(mockTextAnimator)
    }

    @Test
    fun validateColorAnimationRunsAfterMeasure() {
        clockView.setColors(100, 200)
        clockView.measure(50, 50)
        clockView.animateAppearOnLockscreen()

        verify(mockTextAnimator, times(2)).fontVariationUtils
        verify(mockTextAnimator, times(2)).glyphFilter = any()
        verify(mockTextAnimator)
            .setTextStyle(
                eq(TextAnimator.Style(fVar = "'wght' 100", color = 200)),
                eq(TextAnimator.Animation(animate = false, duration = 0)),
            )

        verify(mockTextAnimator)
            .setTextStyle(
                eq(TextAnimator.Style(fVar = "'wght' 300", color = 200)),
                eq(
                    TextAnimator.Animation(
                        animate = true,
                        duration = 833L,
                        interpolator = Interpolators.EMPHASIZED_DECELERATE,
                    )
                ),
            )
        verifyNoMoreInteractions(mockTextAnimator)
    }
}
