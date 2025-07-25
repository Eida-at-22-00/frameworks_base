package com.android.systemui.bouncer.ui.binder

import android.view.ViewGroup
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor
import com.android.systemui.bouncer.ui.BouncerDialogFactory
import com.android.systemui.bouncer.ui.composable.BouncerContainer
import com.android.systemui.bouncer.ui.viewmodel.BouncerContainerViewModel
import com.android.systemui.bouncer.ui.viewmodel.BouncerOverlayContentViewModel
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.lifecycle.WindowLifecycleState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.lifecycle.viewModel
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import com.android.systemui.util.kotlin.sample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation

/** View binder responsible for binding the compose version of the bouncer. */
object ComposeBouncerViewBinder {
    private var persistentBouncerJob: Job? = null

    fun bind(
        view: ViewGroup,
        scope: CoroutineScope,
        legacyInteractor: PrimaryBouncerInteractor,
        keyguardInteractor: KeyguardInteractor,
        selectedUserInteractor: SelectedUserInteractor,
        viewModelFactory: BouncerOverlayContentViewModel.Factory,
        dialogFactory: BouncerDialogFactory,
        bouncerContainerViewModelFactory: BouncerContainerViewModel.Factory,
    ) {
        persistentBouncerJob?.cancel()
        persistentBouncerJob =
            scope.launch {
                launch {
                    legacyInteractor.isShowing
                        .sample(keyguardInteractor.isKeyguardDismissible, ::Pair)
                        .collect { (isShowing, dismissible) ->
                            if (isShowing && dismissible) {
                                legacyInteractor.notifyUserRequestedBouncerWhenAlreadyAuthenticated(
                                    selectedUserInteractor.getSelectedUserId()
                                )
                            }
                        }
                }

                launch {
                    legacyInteractor.startingDisappearAnimation.collect {
                        it.run()
                        legacyInteractor.hide()
                    }
                }
            }

        view.repeatWhenAttached {
            view.viewModel(
                minWindowLifecycleState = WindowLifecycleState.ATTACHED,
                factory = { bouncerContainerViewModelFactory.create() },
                traceName = "ComposeBouncerViewBinder",
            ) { viewModel ->
                try {
                    view.setViewTreeOnBackPressedDispatcherOwner(
                        object : OnBackPressedDispatcherOwner {
                            override val onBackPressedDispatcher =
                                OnBackPressedDispatcher().apply {
                                    setOnBackInvokedDispatcher(
                                        view.viewRootImpl.onBackInvokedDispatcher
                                    )
                                }

                            override val lifecycle: Lifecycle = this@repeatWhenAttached.lifecycle
                        }
                    )

                    view.addView(
                        ComposeView(view.context).apply {
                            setContent { BouncerContainer(viewModelFactory, dialogFactory) }
                        }
                    )
                    awaitCancellation()
                } finally {
                    view.removeAllViews()
                }
            }
        }
    }
}
