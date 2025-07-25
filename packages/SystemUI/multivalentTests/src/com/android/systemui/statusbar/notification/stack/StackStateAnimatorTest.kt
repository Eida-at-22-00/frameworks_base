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

package com.android.systemui.statusbar.notification.stack

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.AnimatorTestRule
import com.android.systemui.res.R
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.notification.headsup.HeadsUpAnimator
import com.android.systemui.statusbar.notification.headsup.NotificationsHunSharedAnimationValues
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.AnimationEvent
import com.android.systemui.statusbar.notification.stack.StackStateAnimator.ANIMATION_DURATION_HEADS_UP_APPEAR
import com.android.systemui.statusbar.notification.stack.StackStateAnimator.ANIMATION_DURATION_HEADS_UP_DISAPPEAR
import com.android.systemui.statusbar.ui.fakeSystemBarUtilsProxy
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.description
import org.mockito.Mockito.eq
import org.mockito.Mockito.verify

private const val VIEW_HEIGHT = 100
private const val FULL_SHADE_APPEAR_TRANSLATION = 300
private const val HEADS_UP_ABOVE_SCREEN = 80

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class StackStateAnimatorTest : SysuiTestCase() {

    @get:Rule val setFlagsRule = SetFlagsRule()
    @get:Rule val animatorTestRule = AnimatorTestRule(this)

    private val kosmos = testKosmos()

    private lateinit var stackStateAnimator: StackStateAnimator
    private lateinit var headsUpAnimator: HeadsUpAnimator
    private val stackScroller: NotificationStackScrollLayout = mock()
    private val view: ExpandableView = mock()
    private lateinit var viewState: ExpandableViewState
    private val runnableCaptor: ArgumentCaptor<Runnable> = argumentCaptor()

    @Before
    fun setUp() {
        viewState = ExpandableViewState().apply { height = VIEW_HEIGHT }
        overrideResource(
            R.dimen.go_to_full_shade_appearing_translation,
            FULL_SHADE_APPEAR_TRANSLATION,
        )
        overrideResource(R.dimen.heads_up_appear_y_above_screen, HEADS_UP_ABOVE_SCREEN)

        whenever(stackScroller.context).thenReturn(context)
        whenever(view.viewState).thenReturn(viewState)

        if (NotificationsHunSharedAnimationValues.isEnabled) {
            headsUpAnimator = HeadsUpAnimator(context, kosmos.fakeSystemBarUtilsProxy)
        }
        stackStateAnimator =
            StackStateAnimator(
                mContext,
                stackScroller,
                if (::headsUpAnimator.isInitialized) headsUpAnimator else null,
            )
    }

    @Test
    @DisableFlags(NotificationsHunSharedAnimationValues.FLAG_NAME)
    fun startAnimationForEvents_headsUpFromTop_startsHeadsUpAppearAnim_flagOff() {
        val topMargin = 50f
        val expectedStartY = -topMargin - stackStateAnimator.mHeadsUpAppearStartAboveScreen
        val event = AnimationEvent(view, AnimationEvent.ANIMATION_TYPE_HEADS_UP_APPEAR)
        stackStateAnimator.setStackTopMargin(topMargin.toInt())

        stackStateAnimator.startAnimationForEvents(arrayListOf(event), 0)

        verify(view).setFinalActualHeight(VIEW_HEIGHT)
        verify(view, description("should animate from the top")).translationY = expectedStartY
        verify(view)
            .performAddAnimation(
                /* delay= */ 0L,
                /* duration= */ ANIMATION_DURATION_HEADS_UP_APPEAR.toLong(),
                /* isHeadsUpAppear= */ true,
                /* isHeadsUpCycling= */ false,
                /* onEndRunnable= */ null,
            )
    }

    @Test
    @EnableFlags(NotificationsHunSharedAnimationValues.FLAG_NAME)
    fun startAnimationForEvents_headsUpFromTop_startsHeadsUpAppearAnim_flagOn() {
        val topMargin = 50f
        val expectedStartY = -topMargin - HEADS_UP_ABOVE_SCREEN
        val event = AnimationEvent(view, AnimationEvent.ANIMATION_TYPE_HEADS_UP_APPEAR)
        headsUpAnimator.stackTopMargin = topMargin.toInt()

        stackStateAnimator.startAnimationForEvents(arrayListOf(event), 0)

        verify(view).setFinalActualHeight(VIEW_HEIGHT)
        verify(view, description("should animate from the top")).translationY = expectedStartY
        verify(view)
            .performAddAnimation(
                /* delay= */ 0L,
                /* duration= */ ANIMATION_DURATION_HEADS_UP_APPEAR.toLong(),
                /* isHeadsUpAppear= */ true,
                /* isHeadsUpCycling= */ false,
                /* onEndRunnable= */ null,
            )
    }

    @Test
    @EnableFlags(NotificationsHunSharedAnimationValues.FLAG_NAME, StatusBarNotifChips.FLAG_NAME)
    fun startAnimationForEvents_headsUpFromTop_andHasStatusBarChipFalse() {
        val statusBarHeight = 156
        val topMargin = 50f
        val expectedStartY = -topMargin - HEADS_UP_ABOVE_SCREEN

        headsUpAnimator.stackTopMargin = topMargin.toInt()
        kosmos.fakeSystemBarUtilsProxy.fakeStatusBarHeight = statusBarHeight
        headsUpAnimator.updateResources(context)

        val event = AnimationEvent(view, AnimationEvent.ANIMATION_TYPE_HEADS_UP_APPEAR)
        event.headsUpHasStatusBarChip = false

        stackStateAnimator.startAnimationForEvents(arrayListOf(event), 0)

        verify(view).setFinalActualHeight(VIEW_HEIGHT)
        verify(view, description("should animate from the top")).translationY = expectedStartY
        verify(view)
            .performAddAnimation(
                /* delay= */ 0L,
                /* duration= */ ANIMATION_DURATION_HEADS_UP_APPEAR.toLong(),
                /* isHeadsUpAppear= */ true,
                /* isHeadsUpCycling= */ false,
                /* onEndRunnable= */ null,
            )
    }

    @Test
    @EnableFlags(NotificationsHunSharedAnimationValues.FLAG_NAME, StatusBarNotifChips.FLAG_NAME)
    fun startAnimationForEvents_headsUpFromTop_andHasStatusBarChipTrue() {
        val statusBarHeight = 156
        val topMargin = 50f
        val expectedStartY = statusBarHeight - topMargin

        headsUpAnimator!!.stackTopMargin = topMargin.toInt()
        kosmos.fakeSystemBarUtilsProxy.fakeStatusBarHeight = statusBarHeight
        headsUpAnimator!!.updateResources(context)

        val event = AnimationEvent(view, AnimationEvent.ANIMATION_TYPE_HEADS_UP_APPEAR)
        event.headsUpHasStatusBarChip = true

        stackStateAnimator.startAnimationForEvents(arrayListOf(event), 0)

        verify(view).setFinalActualHeight(VIEW_HEIGHT)
        verify(view, description("should animate below status bar")).translationY = expectedStartY
        verify(view)
            .performAddAnimation(
                /* delay= */ 0L,
                /* duration= */ ANIMATION_DURATION_HEADS_UP_APPEAR.toLong(),
                /* isHeadsUpAppear= */ true,
                /* isHeadsUpCycling= */ false,
                /* onEndRunnable= */ null,
            )
    }

    @Test
    @DisableFlags(NotificationsHunSharedAnimationValues.FLAG_NAME)
    fun startAnimationForEvents_headsUpFromBottom_startsHeadsUpAppearAnim_flagOff() {
        val screenHeight = 2000f
        val expectedStartY = screenHeight + stackStateAnimator.mHeadsUpAppearStartAboveScreen
        val event =
            AnimationEvent(view, AnimationEvent.ANIMATION_TYPE_HEADS_UP_APPEAR).apply {
                headsUpFromBottom = true
            }
        stackStateAnimator.setHeadsUpAppearHeightBottom(screenHeight.toInt())

        stackStateAnimator.startAnimationForEvents(arrayListOf(event), 0)

        verify(view).setFinalActualHeight(VIEW_HEIGHT)
        verify(view, description("should animate from the bottom")).translationY = expectedStartY
        verify(view)
            .performAddAnimation(
                /* delay= */ 0L,
                /* duration= */ ANIMATION_DURATION_HEADS_UP_APPEAR.toLong(),
                /* isHeadsUpAppear= */ true,
                /* isHeadsUpCycling= */ false,
                /* onEndRunnable= */ null,
            )
    }

    @Test
    @EnableFlags(NotificationsHunSharedAnimationValues.FLAG_NAME)
    fun startAnimationForEvents_headsUpFromBottom_startsHeadsUpAppearAnim_flagOn() {
        val screenHeight = 2000f
        val expectedStartY = screenHeight + HEADS_UP_ABOVE_SCREEN
        val event =
            AnimationEvent(view, AnimationEvent.ANIMATION_TYPE_HEADS_UP_APPEAR).apply {
                headsUpFromBottom = true
            }
        headsUpAnimator.headsUpAppearHeightBottom = screenHeight.toInt()

        stackStateAnimator.startAnimationForEvents(arrayListOf(event), 0)

        verify(view).setFinalActualHeight(VIEW_HEIGHT)
        verify(view, description("should animate from the bottom")).translationY = expectedStartY
        verify(view)
            .performAddAnimation(
                /* delay= */ 0L,
                /* duration= */ ANIMATION_DURATION_HEADS_UP_APPEAR.toLong(),
                /* isHeadsUpAppear= */ true,
                /* isHeadsUpCycling= */ false,
                /* onEndRunnable= */ null,
            )
    }

    @Test
    @DisableFlags(NotificationsHunSharedAnimationValues.FLAG_NAME, Flags.FLAG_PHYSICAL_NOTIFICATION_MOVEMENT)
    fun startAnimationForEvents_startsHeadsUpDisappearAnim_flagOff() {
        val disappearDuration = ANIMATION_DURATION_HEADS_UP_DISAPPEAR.toLong()
        val event = AnimationEvent(view, AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR)
        clearInvocations(view)
        stackStateAnimator.startAnimationForEvents(arrayListOf(event), 0)

        verify(view)
            .performRemoveAnimation(
                /* duration= */ eq(disappearDuration),
                /* delay= */ eq(0L),
                /* translationDirection= */ eq(0f),
                /* isHeadsUpAnimation= */ eq(true),
                /* isHeadsUpCycling= */ eq(false),
                /* onStartedRunnable= */ any(),
                /* onFinishedRunnable= */ runnableCaptor.capture(),
                /* animationListener= */ any(),
                /* clipSide= */ eq(ExpandableView.ClipSide.BOTTOM),
            )

        animatorTestRule.advanceTimeBy(disappearDuration) // move to the end of SSA animations
        runnableCaptor.value.run() // execute the end runnable

        verify(view, description("should be translated to the heads up appear start"))
            .translationY = -stackStateAnimator.mHeadsUpAppearStartAboveScreen
        verify(view, description("should be called at the end of the disappear animation"))
            .removeFromTransientContainer()
    }

    @Test
    @EnableFlags(NotificationsHunSharedAnimationValues.FLAG_NAME)
    @DisableFlags(Flags.FLAG_PHYSICAL_NOTIFICATION_MOVEMENT)
    fun startAnimationForEvents_startsHeadsUpDisappearAnim_flagOn() {
        val disappearDuration = ANIMATION_DURATION_HEADS_UP_DISAPPEAR.toLong()
        val event = AnimationEvent(view, AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR)
        clearInvocations(view)
        stackStateAnimator.startAnimationForEvents(arrayListOf(event), 0)

        verify(view)
            .performRemoveAnimation(
                /* duration= */ eq(disappearDuration),
                /* delay= */ eq(0L),
                /* translationDirection= */ eq(0f),
                /* isHeadsUpAnimation= */ eq(true),
                /* isHeadsUpCycling= */ eq(false),
                /* onStartedRunnable= */ any(),
                /* onFinishedRunnable= */ runnableCaptor.capture(),
                /* animationListener= */ any(),
                /* clipSide= */ eq(ExpandableView.ClipSide.BOTTOM),
            )

        animatorTestRule.advanceTimeBy(disappearDuration) // move to the end of SSA animations
        runnableCaptor.value.run() // execute the end runnable

        verify(view, description("should be translated to the heads up appear start"))
            .translationY = -stackStateAnimator.mHeadsUpAppearStartAboveScreen
        verify(view, description("should be called at the end of the disappear animation"))
            .removeFromTransientContainer()
    }

    @EnableFlags(Flags.FLAG_PHYSICAL_NOTIFICATION_MOVEMENT)
    @Test
    fun startAnimationForEvents_startsHeadsUpDisappearAnim_physical() {
        val disappearDuration = ANIMATION_DURATION_HEADS_UP_DISAPPEAR.toLong()
        val event = AnimationEvent(view, AnimationEvent.ANIMATION_TYPE_HEADS_UP_DISAPPEAR)
        clearInvocations(view)
        stackStateAnimator.startAnimationForEvents(arrayListOf(event), 0)

        verify(view)
            .performRemoveAnimation(
                /* duration= */ eq(disappearDuration),
                /* delay= */ eq(0L),
                /* translationDirection= */ eq(0f),
                /* isHeadsUpAnimation= */ eq(true),
                /* isHeadsUpCycling= */ eq(false),
                /* onStartedRunnable= */ any(),
                /* onFinishedRunnable= */ runnableCaptor.capture(),
                /* animationListener= */ any(),
                /* clipSide= */ eq(ExpandableView.ClipSide.BOTTOM),
            )

        runnableCaptor.value.run() // execute the end runnable
        verify(view, description("should be called at the end of the disappear animation"))
            .removeFromTransientContainer()
    }

    @Test
    fun initView_updatesResources() {
        // Given: the resource values are initialized in the SSA
        assertThat(stackStateAnimator.mGoToFullShadeAppearingTranslation)
            .isEqualTo(FULL_SHADE_APPEAR_TRANSLATION)
        assertThat(stackStateAnimator.mHeadsUpAppearStartAboveScreen)
            .isEqualTo(HEADS_UP_ABOVE_SCREEN)

        // When: initView is called after the resources have changed
        overrideResource(R.dimen.go_to_full_shade_appearing_translation, 200)
        overrideResource(R.dimen.heads_up_appear_y_above_screen, 100)
        stackStateAnimator.initView(mContext)

        // Then: the resource values are updated
        assertThat(stackStateAnimator.mGoToFullShadeAppearingTranslation).isEqualTo(200)
        assertThat(stackStateAnimator.mHeadsUpAppearStartAboveScreen).isEqualTo(100)
    }
}
