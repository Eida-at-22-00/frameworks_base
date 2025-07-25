/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.phone

import android.app.StatusBarManager.WINDOW_STATE_HIDDEN
import android.app.StatusBarManager.WINDOW_STATE_HIDING
import android.app.StatusBarManager.WINDOW_STATE_SHOWING
import android.app.StatusBarManager.WINDOW_STATUS_BAR
import android.graphics.Insets
import android.hardware.display.DisplayManagerGlobal
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.Display
import android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS
import android.view.DisplayInfo
import android.view.InputDevice
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewTreeObserver.OnPreDrawListener
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.systemui.SysuiTestCase
import com.android.systemui.SysuiTestableContext
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.plugins.fakeDarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.scene.ui.view.WindowRootView
import com.android.systemui.shade.ShadeControllerImpl
import com.android.systemui.shade.ShadeLogger
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.shade.StatusBarLongPressGestureDetector
import com.android.systemui.shade.display.StatusBarTouchShadeDisplayPolicy
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor
import com.android.systemui.shade.shared.flag.ShadeWindowGoesAround
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.data.repository.fakeStatusBarContentInsetsProviderStore
import com.android.systemui.statusbar.policy.Clock
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.window.StatusBarWindowStateController
import com.android.systemui.testKosmos
import com.android.systemui.unfold.SysUIUnfoldComponent
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import com.android.systemui.unfold.util.ScopedUnfoldTransitionProgressProvider
import com.android.systemui.user.ui.viewmodel.StatusBarUserChipViewModel
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.view.ViewUtil
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import java.util.Optional
import javax.inject.Provider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq

@SmallTest
@RunWith(AndroidJUnit4::class)
class PhoneStatusBarViewControllerTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val statusBarContentInsetsProviderStore = kosmos.fakeStatusBarContentInsetsProviderStore
    private val statusBarContentInsetsProvider = statusBarContentInsetsProviderStore.defaultDisplay
    private val statusBarContentInsetsProviderForSecondaryDisplay =
        statusBarContentInsetsProviderStore.forDisplay(SECONDARY_DISPLAY_ID)

    private val fakeDarkIconDispatcher = kosmos.fakeDarkIconDispatcher
    @Mock private lateinit var shadeViewController: ShadeViewController
    @Mock private lateinit var panelExpansionInteractor: PanelExpansionInteractor
    @Mock private lateinit var featureFlags: FeatureFlags
    @Mock private lateinit var moveFromCenterAnimation: StatusBarMoveFromCenterAnimationController
    @Mock private lateinit var sysuiUnfoldComponent: SysUIUnfoldComponent
    @Mock private lateinit var progressProvider: ScopedUnfoldTransitionProgressProvider
    @Mock private lateinit var configurationController: ConfigurationController
    @Mock private lateinit var mStatusOverlayHoverListenerFactory: StatusOverlayHoverListenerFactory
    @Mock private lateinit var userChipViewModel: StatusBarUserChipViewModel
    @Mock private lateinit var centralSurfacesImpl: CentralSurfacesImpl
    @Mock private lateinit var commandQueue: CommandQueue
    @Mock private lateinit var shadeControllerImpl: ShadeControllerImpl
    @Mock private lateinit var windowRootView: Provider<WindowRootView>
    @Mock private lateinit var shadeLogger: ShadeLogger
    @Mock private lateinit var viewUtil: ViewUtil
    @Mock private lateinit var mStatusBarLongPressGestureDetector: StatusBarLongPressGestureDetector
    @Mock private lateinit var statusBarTouchShadeDisplayPolicy: StatusBarTouchShadeDisplayPolicy
    private lateinit var statusBarWindowStateController: StatusBarWindowStateController

    private lateinit var view: PhoneStatusBarView
    private lateinit var controller: PhoneStatusBarViewController

    private lateinit var viewForSecondaryDisplay: PhoneStatusBarView

    private val clockView: Clock
        get() = view.requireViewById(R.id.clock)

    private val batteryView: BatteryMeterView
        get() = view.requireViewById(R.id.battery)

    private val unfoldConfig = UnfoldConfig()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        statusBarWindowStateController = StatusBarWindowStateController(DISPLAY_ID, commandQueue)

        `when`(statusBarContentInsetsProvider.getStatusBarContentInsetsForCurrentRotation())
            .thenReturn(Insets.NONE)

        `when`(sysuiUnfoldComponent.getStatusBarMoveFromCenterAnimationController())
            .thenReturn(moveFromCenterAnimation)
        // create the view and controller on main thread as it requires main looper
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = FrameLayout(mContext) // add parent to keep layout params
            view =
                LayoutInflater.from(mContext).inflate(R.layout.status_bar, parent, false)
                    as PhoneStatusBarView
            controller = createAndInitController(view)
        }

        `when`(
                statusBarContentInsetsProviderForSecondaryDisplay
                    .getStatusBarContentInsetsForCurrentRotation()
            )
            .thenReturn(Insets.NONE)

        val contextForSecondaryDisplay =
            SysuiTestableContext(
                mContext.createDisplayContext(
                    Display(
                        DisplayManagerGlobal.getInstance(),
                        SECONDARY_DISPLAY_ID,
                        DisplayInfo(),
                        DEFAULT_DISPLAY_ADJUSTMENTS,
                    )
                )
            )

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = FrameLayout(contextForSecondaryDisplay) // add parent to keep layout params
            viewForSecondaryDisplay =
                LayoutInflater.from(contextForSecondaryDisplay)
                    .inflate(R.layout.status_bar, parent, false) as PhoneStatusBarView
            createAndInitController(viewForSecondaryDisplay)
        }
    }

    @Test
    fun onViewAttachedAndDrawn_startListeningConfigurationControllerCallback() {
        val view = createViewMock()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller = createAndInitController(view)
        }

        verify(configurationController).addCallback(any())
    }

    @Test
    fun onViewAttachedAndDrawn_darkReceiversRegistered() {
        val view = createViewMock()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller = createAndInitController(view)
        }

        assertThat(fakeDarkIconDispatcher.receivers.size).isEqualTo(2)
        assertThat(fakeDarkIconDispatcher.receivers).contains(clockView)
        assertThat(fakeDarkIconDispatcher.receivers).contains(batteryView)
    }

    @Test
    fun onViewAttachedAndDrawn_moveFromCenterAnimationEnabled_moveFromCenterAnimationInitialized() {
        whenever(featureFlags.isEnabled(Flags.ENABLE_UNFOLD_STATUS_BAR_ANIMATIONS)).thenReturn(true)
        val view = createViewMock()
        val argumentCaptor = ArgumentCaptor.forClass(OnPreDrawListener::class.java)
        unfoldConfig.isEnabled = true
        // create the controller on main thread as it requires main looper
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller = createAndInitController(view)
        }

        verify(view.viewTreeObserver).addOnPreDrawListener(argumentCaptor.capture())
        argumentCaptor.value.onPreDraw()

        verify(moveFromCenterAnimation).onViewsReady(any())
    }

    @Test
    fun onViewAttachedAndDrawn_statusBarAnimationDisabled_animationNotInitialized() {
        whenever(featureFlags.isEnabled(Flags.ENABLE_UNFOLD_STATUS_BAR_ANIMATIONS))
            .thenReturn(false)
        val view = createViewMock()
        unfoldConfig.isEnabled = true
        // create the controller on main thread as it requires main looper
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller = createAndInitController(view)
        }

        verify(moveFromCenterAnimation, never()).onViewsReady(any())
    }

    @Test
    fun onViewDetached_darkReceiversUnregistered() {
        val view = createViewMock()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller = createAndInitController(view)
        }

        assertThat(fakeDarkIconDispatcher.receivers).isNotEmpty()

        controller.onViewDetached()

        assertThat(fakeDarkIconDispatcher.receivers).isEmpty()
    }

    @Test
    fun handleTouchEventFromStatusBar_panelsNotEnabled_returnsFalseAndNoViewEvent() {
        `when`(centralSurfacesImpl.commandQueuePanelsEnabled).thenReturn(false)
        val returnVal =
            view.onTouchEvent(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 2f, 0))
        assertThat(returnVal).isFalse()
        verify(shadeViewController, never()).handleExternalTouch(any())
    }

    @Test
    fun handleTouchEventFromStatusBar_viewNotEnabled_returnsTrueAndNoViewEvent() {
        `when`(centralSurfacesImpl.commandQueuePanelsEnabled).thenReturn(true)
        `when`(shadeViewController.isViewEnabled).thenReturn(false)
        val returnVal =
            view.onTouchEvent(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 2f, 0))
        assertThat(returnVal).isTrue()
        verify(shadeViewController, never()).handleExternalTouch(any())
    }

    @Test
    fun handleTouchEventFromStatusBar_viewNotEnabledButIsMoveEvent_viewReceivesEvent() {
        `when`(centralSurfacesImpl.commandQueuePanelsEnabled).thenReturn(true)
        `when`(shadeViewController.isViewEnabled).thenReturn(false)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 0f, 2f, 0)

        view.onTouchEvent(event)

        verify(shadeViewController).handleExternalTouch(event)
    }

    @Test
    fun handleTouchEventFromStatusBar_panelAndViewEnabled_viewReceivesEvent() {
        `when`(centralSurfacesImpl.commandQueuePanelsEnabled).thenReturn(true)
        `when`(shadeViewController.isViewEnabled).thenReturn(true)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 2f, 0)

        view.onTouchEvent(event)

        verify(shadeViewController).handleExternalTouch(event)
    }

    @Test
    fun handleTouchEventFromStatusBar_topEdgeTouch_viewNeverReceivesEvent() {
        `when`(centralSurfacesImpl.commandQueuePanelsEnabled).thenReturn(true)
        `when`(panelExpansionInteractor.isFullyCollapsed).thenReturn(true)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)

        view.onTouchEvent(event)

        verify(shadeViewController, never()).handleExternalTouch(any())
    }

    @Test
    @DisableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    fun handleTouchEventFromStatusBar_touchOnPrimaryDisplay_statusBarConnectedDisplaysDisabled_shadeReceivesEvent() {
        `when`(centralSurfacesImpl.commandQueuePanelsEnabled).thenReturn(true)
        `when`(shadeViewController.isViewEnabled).thenReturn(true)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 2f, 0)

        view.onTouchEvent(event)

        verify(shadeViewController).handleExternalTouch(event)
    }

    @Test
    @EnableFlags(StatusBarConnectedDisplays.FLAG_NAME, ShadeWindowGoesAround.FLAG_NAME)
    fun handleTouchEventFromStatusBar_touchOnPrimaryDisplay_statusBarConnectedDisplaysEnabled_shadeWindowGoesAroundEnabled_shadeReceivesEvent() {
        `when`(centralSurfacesImpl.commandQueuePanelsEnabled).thenReturn(true)
        `when`(shadeViewController.isViewEnabled).thenReturn(true)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 2f, 0)

        view.onTouchEvent(event)

        verify(shadeViewController).handleExternalTouch(event)
    }

    @Test
    @EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    @DisableFlags(ShadeWindowGoesAround.FLAG_NAME)
    fun handleTouchEventFromStatusBar_touchOnPrimaryDisplay_statusBarConnectedDisplaysEnabled_shadeWindowGoesAroundDisabled_shadeReceivesEvent() {
        `when`(centralSurfacesImpl.commandQueuePanelsEnabled).thenReturn(true)
        `when`(shadeViewController.isViewEnabled).thenReturn(true)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 2f, 0)

        view.onTouchEvent(event)

        verify(shadeViewController).handleExternalTouch(event)
    }

    @Test
    @DisableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    fun handleTouchEventFromStatusBar_touchOnSecondaryDisplay_statusBarConnectedDisplaysDisabled_shadeReceivesEvent() {
        `when`(centralSurfacesImpl.commandQueuePanelsEnabled).thenReturn(true)
        `when`(shadeViewController.isViewEnabled).thenReturn(true)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 2f, 0)

        viewForSecondaryDisplay.onTouchEvent(event)

        verify(shadeViewController).handleExternalTouch(event)
    }

    @Test
    @EnableFlags(StatusBarConnectedDisplays.FLAG_NAME, ShadeWindowGoesAround.FLAG_NAME)
    fun handleTouchEventFromStatusBar_touchOnSecondaryDisplay_statusBarConnectedDisplaysEnabled_shadeWindowGoesAroundEnabled_shadeReceivesEvent() {
        `when`(centralSurfacesImpl.commandQueuePanelsEnabled).thenReturn(true)
        `when`(shadeViewController.isViewEnabled).thenReturn(true)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 2f, 0)

        viewForSecondaryDisplay.onTouchEvent(event)

        verify(shadeViewController).handleExternalTouch(event)
    }

    @Test
    @EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
    @DisableFlags(ShadeWindowGoesAround.FLAG_NAME)
    fun handleTouchEventFromStatusBar_touchOnSecondaryDisplay_statusBarConnectedDisplaysEnabled_shadeWindowGoesAroundDisabled_shadeDoesNotReceiveEvent() {
        `when`(centralSurfacesImpl.commandQueuePanelsEnabled).thenReturn(true)
        `when`(shadeViewController.isViewEnabled).thenReturn(true)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 2f, 0)

        viewForSecondaryDisplay.onTouchEvent(event)

        verify(shadeViewController, never()).handleExternalTouch(event)
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_STATUS_BAR_SWIPE_OVER_CHIP)
    fun handleInterceptTouchEventFromStatusBar_shadeReturnsFalse_flagOff_viewReturnsFalse() {
        `when`(shadeViewController.handleExternalInterceptTouch(any())).thenReturn(false)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 2f, 0)

        val returnVal = view.onInterceptTouchEvent(event)

        assertThat(returnVal).isFalse()
    }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_STATUS_BAR_SWIPE_OVER_CHIP)
    fun handleInterceptTouchEventFromStatusBar_shadeReturnsFalse_flagOn_viewReturnsFalse() {
        `when`(shadeViewController.handleExternalInterceptTouch(any())).thenReturn(false)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 2f, 0)

        val returnVal = view.onInterceptTouchEvent(event)

        assertThat(returnVal).isFalse()
    }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_STATUS_BAR_SWIPE_OVER_CHIP)
    fun handleInterceptTouchEventFromStatusBar_shadeReturnsTrue_flagOff_viewReturnsFalse() {
        `when`(shadeViewController.handleExternalInterceptTouch(any())).thenReturn(true)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 2f, 0)

        val returnVal = view.onInterceptTouchEvent(event)

        assertThat(returnVal).isFalse()
    }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_STATUS_BAR_SWIPE_OVER_CHIP)
    fun handleInterceptTouchEventFromStatusBar_shadeReturnsTrue_flagOn_viewReturnsTrue() {
        `when`(shadeViewController.handleExternalInterceptTouch(any())).thenReturn(true)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 2f, 0)

        val returnVal = view.onInterceptTouchEvent(event)

        assertThat(returnVal).isTrue()
    }

    @Test
    fun onTouch_windowHidden_centralSurfacesNotNotified() {
        val callback = getCommandQueueCallback()
        callback.setWindowState(DISPLAY_ID, WINDOW_STATUS_BAR, WINDOW_STATE_HIDDEN)

        controller.onTouch(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0))

        verify(centralSurfacesImpl, never()).setInteracting(any(), any())
    }

    @Test
    fun onTouch_windowHiding_centralSurfacesNotNotified() {
        val callback = getCommandQueueCallback()
        callback.setWindowState(DISPLAY_ID, WINDOW_STATUS_BAR, WINDOW_STATE_HIDING)

        controller.onTouch(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0))

        verify(centralSurfacesImpl, never()).setInteracting(any(), any())
    }

    @Test
    fun onTouch_windowShowing_centralSurfacesNotified() {
        val callback = getCommandQueueCallback()
        callback.setWindowState(DISPLAY_ID, WINDOW_STATUS_BAR, WINDOW_STATE_SHOWING)

        controller.onTouch(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0))

        verify(centralSurfacesImpl).setInteracting(any(), any())
    }

    @Test
    @EnableFlags(ShadeWindowGoesAround.FLAG_NAME)
    fun onInterceptTouchEvent_actionDown_propagatesToDisplayPolicy() {
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)

        view.onInterceptTouchEvent(event)

        verify(statusBarTouchShadeDisplayPolicy).onStatusBarTouched(eq(event), any())
    }

    @Test
    @EnableFlags(ShadeWindowGoesAround.FLAG_NAME)
    fun onInterceptTouchEvent_actionUp_notPropagatesToDisplayPolicy() {
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_UP, 0f, 0f, 0)

        view.onInterceptTouchEvent(event)

        verify(statusBarTouchShadeDisplayPolicy, never()).onStatusBarTouched(any(), any())
    }

    @Test
    @DisableFlags(ShadeWindowGoesAround.FLAG_NAME)
    fun onInterceptTouchEvent_shadeWindowGoesAroundDisabled_notPropagatesToDisplayPolicy() {
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)

        view.onInterceptTouchEvent(event)

        verify(statusBarTouchShadeDisplayPolicy, never()).onStatusBarTouched(eq(event), any())
    }

    @Test
    @EnableFlags(ShadeWindowGoesAround.FLAG_NAME)
    fun onTouch_withMouseOnEndSideIcons_flagOn_propagatedToShadeDisplayPolicy() {
        val view = createViewMock()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller = createAndInitController(view)
        }
        val event = getActionUpEventFromSource(InputDevice.SOURCE_MOUSE)

        val statusContainer = view.requireViewById<View>(R.id.system_icons)
        statusContainer.dispatchTouchEvent(event)

        verify(statusBarTouchShadeDisplayPolicy).onStatusBarTouched(eq(event), any())
    }

    @Test
    @EnableFlags(ShadeWindowGoesAround.FLAG_NAME)
    fun onTouch_withMouseOnStartSideIcons_flagOn_propagatedToShadeDisplayPolicy() {
        val view = createViewMock()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller = createAndInitController(view)
        }
        val event = getActionUpEventFromSource(InputDevice.SOURCE_MOUSE)

        val statusContainer = view.requireViewById<View>(R.id.status_bar_start_side_content)
        statusContainer.dispatchTouchEvent(event)

        verify(statusBarTouchShadeDisplayPolicy).onStatusBarTouched(eq(event), any())
    }

    @Test
    @DisableFlags(ShadeWindowGoesAround.FLAG_NAME)
    fun onTouch_withMouseOnSystemIcons_flagOff_notPropagatedToShadeDisplayPolicy() {
        val view = createViewMock()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller = createAndInitController(view)
        }
        val event = getActionUpEventFromSource(InputDevice.SOURCE_MOUSE)

        val statusContainer = view.requireViewById<View>(R.id.system_icons)
        statusContainer.dispatchTouchEvent(event)

        verify(statusBarTouchShadeDisplayPolicy, never()).onStatusBarTouched(eq(event), any())
    }

    @Test
    fun shadeIsExpandedOnStatusIconMouseClick() {
        val view = createViewMock()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller = createAndInitController(view)
        }
        val statusContainer = view.requireViewById<View>(R.id.system_icons)
        statusContainer.dispatchTouchEvent(getActionUpEventFromSource(InputDevice.SOURCE_MOUSE))
        verify(shadeControllerImpl).animateExpandShade()
    }

    @Test
    fun statusIconContainerIsNotHandlingTouchScreenTouches() {
        val view = createViewMock()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller = createAndInitController(view)
        }
        val statusContainer = view.requireViewById<View>(R.id.system_icons)
        val handled =
            statusContainer.dispatchTouchEvent(
                getActionUpEventFromSource(InputDevice.SOURCE_TOUCHSCREEN)
            )
        assertThat(handled).isFalse()
    }

    private fun getActionUpEventFromSource(source: Int): MotionEvent {
        val ev = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 0f, 0f, 0)
        ev.source = source
        return ev
    }

    @Test
    fun shadeIsNotExpandedOnStatusBarGeneralClick() {
        val view = createViewMock()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller = createAndInitController(view)
        }
        view.performClick()
        verify(shadeControllerImpl, never()).animateExpandShade()
    }

    private fun getCommandQueueCallback(): CommandQueue.Callbacks {
        val captor = argumentCaptor<CommandQueue.Callbacks>()
        verify(commandQueue).addCallback(captor.capture())
        return captor.value!!
    }

    private fun createViewMock(): PhoneStatusBarView {
        val view = spy(view)
        val viewTreeObserver = mock(ViewTreeObserver::class.java)
        `when`(view.viewTreeObserver).thenReturn(viewTreeObserver)
        `when`(view.isAttachedToWindow).thenReturn(true)
        return view
    }

    private fun createAndInitController(view: PhoneStatusBarView): PhoneStatusBarViewController {
        return PhoneStatusBarViewController.Factory(
                Optional.of(sysuiUnfoldComponent),
                Optional.of(progressProvider),
                featureFlags,
                userChipViewModel,
                centralSurfacesImpl,
                statusBarWindowStateController,
                shadeControllerImpl,
                shadeViewController,
                panelExpansionInteractor,
                { mStatusBarLongPressGestureDetector },
                windowRootView,
                shadeLogger,
                viewUtil,
                configurationController,
                mStatusOverlayHoverListenerFactory,
                fakeDarkIconDispatcher,
                statusBarContentInsetsProviderStore,
                Lazy { statusBarTouchShadeDisplayPolicy },
            )
            .create(view)
            .also { it.init() }
    }

    private class UnfoldConfig : UnfoldTransitionConfig {
        override var isEnabled: Boolean = false
        override var isHingeAngleEnabled: Boolean = false
        override val isHapticsEnabled: Boolean = false
        override val halfFoldedTimeoutMillis: Int = 0
    }

    private companion object {
        const val DISPLAY_ID = 0
        const val SECONDARY_DISPLAY_ID = 2
    }
}
