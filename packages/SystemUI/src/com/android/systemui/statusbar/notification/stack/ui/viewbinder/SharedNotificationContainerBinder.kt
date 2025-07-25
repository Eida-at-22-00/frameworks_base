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

package com.android.systemui.statusbar.notification.stack.ui.viewbinder

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.Flags
import com.android.systemui.common.ui.view.onLayoutChanged
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.ui.viewmodel.ViewStateAccessor
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shared.Flags.extendedWallpaperEffects
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.stack.NotificationStackSizeCalculator
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
import com.android.systemui.util.kotlin.DisposableHandles
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Binds the shared notification container to its view-model. */
@SysUISingleton
class SharedNotificationContainerBinder
@Inject
constructor(
    private val controller: NotificationStackScrollLayoutController,
    private val notificationStackSizeCalculator: NotificationStackSizeCalculator,
    private val notificationScrollViewBinder: NotificationScrollViewBinder,
    private val communalSettingsInteractor: CommunalSettingsInteractor,
    @Main private val mainImmediateDispatcher: CoroutineDispatcher,
    val keyguardInteractor: KeyguardInteractor,
) {

    private val calculateMaxNotifications: (Float, Boolean) -> Int = { space, extraShelfSpace ->
        val shelfHeight = controller.getShelfHeight().toFloat()
        notificationStackSizeCalculator.computeMaxKeyguardNotifications(
            controller.view,
            space,
            if (extraShelfSpace) shelfHeight else 0f,
            shelfHeight,
        )
    }

    fun bind(
        view: SharedNotificationContainer,
        viewModel: SharedNotificationContainerViewModel,
    ): DisposableHandle {
        val disposables = DisposableHandles()
        disposables +=
            view.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    launch {
                        viewModel.configurationBasedDimensions.collect {
                            view.updateConstraints(
                                horizontalPosition = it.horizontalPosition,
                                marginStart = it.marginStart,
                                marginTop = it.marginTop,
                                marginEnd = it.marginEnd,
                                marginBottom = it.marginBottom,
                                nsslAlpha = controller.alpha,
                            )

                            controller.setOverExpansion(0f)
                            controller.setOverScrollAmount(0)
                        }
                    }
                }
            }

        val viewState = ViewStateAccessor(alpha = { controller.getAlpha() })

        /*
         * For animation sensitive coroutines, immediately run just like applicationScope does
         * instead of doing a post() to the main thread. This extra delay can cause visible jitter.
         */
        disposables +=
            view.repeatWhenAttached(mainImmediateDispatcher) {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    if (!SceneContainerFlag.isEnabled) {
                        launch {
                            viewModel.shadeCollapseFadeIn.collect { fadeIn ->
                                if (fadeIn) {
                                    android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                                        duration = 250
                                        addUpdateListener { animation ->
                                            controller.setMaxAlphaForKeyguard(
                                                animation.animatedFraction,
                                                "SharedNotificationContainerVB (collapseFadeIn)",
                                            )
                                        }
                                        start()
                                    }
                                }
                            }
                        }
                    }

                    launch {
                        viewModel.getLockscreenDisplayConfig(calculateMaxNotifications).collect {
                            (isOnLockscreen, maxNotifications) ->
                            if (SceneContainerFlag.isEnabled) {
                                controller.setOnLockscreen(isOnLockscreen)
                            }
                            controller.setMaxDisplayedNotifications(maxNotifications)
                        }
                    }

                    if (!SceneContainerFlag.isEnabled) {
                        launch {
                            viewModel.bounds.collect {
                                val animate =
                                    it.isAnimated || controller.isAddOrRemoveAnimationPending
                                controller.updateTopPadding(it.top, animate)
                            }
                        }
                    }

                    if (!SceneContainerFlag.isEnabled) {
                        launch {
                            viewModel.translationY.collect { y -> controller.setTranslationY(y) }
                        }
                    }

                    if (!SceneContainerFlag.isEnabled) {
                        if (extendedWallpaperEffects()) {
                            launch {
                                combine(
                                        viewModel.getNotificationStackAbsoluteBottom(
                                            calculateMaxNotifications = calculateMaxNotifications,
                                            calculateHeight = { maxNotifications ->
                                                notificationStackSizeCalculator.computeHeight(
                                                    maxNotifs = maxNotifications,
                                                    shelfHeight =
                                                        controller.getShelfHeight().toFloat(),
                                                    stack = controller.view,
                                                )
                                            },
                                            controller.getShelfHeight().toFloat(),
                                        ),
                                        viewModel.configurationBasedDimensions.map { it.marginTop },
                                        ::Pair,
                                    )
                                    .collect { (bottom: Float, marginTop: Int) ->
                                        keyguardInteractor.setNotificationStackAbsoluteBottom(
                                            marginTop + bottom
                                        )
                                    }
                            }
                        }
                    }

                    launch { viewModel.translationX.collect { x -> controller.translationX = x } }

                    launch {
                        viewModel.keyguardAlpha(viewState, this).collect {
                            controller.setMaxAlphaForKeyguard(it, "SharedNotificationContainerVB")
                        }
                    }

                    if (!SceneContainerFlag.isEnabled) {
                        launch {
                            // For when the entire view should fade, such as with the brightness
                            // slider
                            viewModel.panelAlpha.collect { controller.setMaxAlphaFromView(it) }
                        }
                    }

                    if (Flags.bouncerUiRevamp()) {
                        launch { viewModel.blurRadius.collect { controller.setBlurRadius(it) } }
                    }

                    if (communalSettingsInteractor.isCommunalFlagEnabled()) {
                        launch {
                            viewModel.glanceableHubAlpha.collect {
                                controller.setMaxAlphaForGlanceableHub(it)
                            }
                        }
                    }
                }
            }

        if (SceneContainerFlag.isEnabled) {
            disposables += notificationScrollViewBinder.bindWhileAttached()
        }

        controller.setOnHeightChangedRunnable { viewModel.notificationStackChanged() }
        disposables += DisposableHandle { controller.setOnHeightChangedRunnable(null) }
        disposables += view.onLayoutChanged { viewModel.notificationStackChanged() }

        return disposables
    }
}
