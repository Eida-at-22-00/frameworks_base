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

package com.android.systemui.statusbar.policy.ui.dialog

import android.annotation.UiThread
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.android.compose.PlatformButton
import com.android.compose.PlatformOutlinedButton
import com.android.compose.theme.PlatformTheme
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.jank.InteractionJankMonitor
import com.android.settingslib.notification.modes.EnableDndDialogFactory
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dialog.ui.composable.AlertDialogContent
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.tiles.dialog.QSEnableDndDialogMetricsLogger
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor
import com.android.systemui.statusbar.phone.ComponentSystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogFactory
import com.android.systemui.statusbar.phone.create
import com.android.systemui.statusbar.policy.ui.dialog.composable.ModeTileGrid
import com.android.systemui.statusbar.policy.ui.dialog.viewmodel.ModesDialogViewModel
import com.android.systemui.util.Assert
import javax.inject.Inject
import javax.inject.Provider
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SysUISingleton
class ModesDialogDelegate
@Inject
constructor(
    val context: Context,
    private val sysuiDialogFactory: SystemUIDialogFactory,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val activityStarter: ActivityStarter,
    // Using a provider to avoid a circular dependency.
    private val viewModel: Provider<ModesDialogViewModel>,
    private val dialogEventLogger: ModesDialogEventLogger,
    @Application private val applicationCoroutineScope: CoroutineScope,
    @Main private val mainCoroutineContext: CoroutineContext,
    @Background private val bgContext: CoroutineContext,
    private val shadeDisplayContextRepository: ShadeDialogContextInteractor,
) : SystemUIDialog.Delegate {
    // NOTE: This should only be accessed/written from the main thread.
    @VisibleForTesting var currentDialog: ComponentSystemUIDialog? = null
    private val dndDurationDialogLogger by lazy { QSEnableDndDialogMetricsLogger(context) }

    override fun createDialog(): SystemUIDialog {
        Assert.isMainThread()
        if (currentDialog != null) {
            Log.w(TAG, "Dialog is already open, dismissing it and creating a new one.")
            currentDialog?.dismiss()
        }

        currentDialog =
            sysuiDialogFactory.create(context = shadeDisplayContextRepository.context) {
                ModesDialogContent(it)
            }
        currentDialog
            ?.lifecycle
            ?.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStop(owner: LifecycleOwner) {
                        Assert.isMainThread()
                        currentDialog = null
                    }
                }
            )

        return currentDialog!!
    }

    @Composable
    private fun ModesDialogContent(dialog: SystemUIDialog) {
        // TODO(b/369376884): The composable does correctly update when the theme changes
        //  while the dialog is open, but the background (which we don't control here)
        //  doesn't, which causes us to show things like white text on a white background.
        //  as a workaround, we remember the original theme and keep it on recomposition.
        val isCurrentlyInDarkTheme = isSystemInDarkTheme()
        val cachedDarkTheme = remember { isCurrentlyInDarkTheme }
        PlatformTheme(isDarkTheme = cachedDarkTheme) {
            AlertDialogContent(
                modifier =
                    Modifier.semantics {
                        testTagsAsResourceId = true
                        paneTitle =
                            dialog.context.getString(R.string.accessibility_desc_quick_settings)
                    },
                title = {
                    Text(
                        modifier = Modifier.testTag("modes_title"),
                        text = stringResource(R.string.zen_modes_dialog_title),
                    )
                },
                content = { ModeTileGrid(viewModel.get()) },
                neutralButton = {
                    PlatformOutlinedButton(onClick = { openSettings(dialog) }) {
                        Text(stringResource(R.string.zen_modes_dialog_settings))
                    }
                },
                positiveButton = {
                    PlatformButton(onClick = { dialog.dismiss() }) {
                        Text(stringResource(R.string.zen_modes_dialog_done))
                    }
                },
            )
        }
    }

    @VisibleForTesting
    fun openSettings(dialog: SystemUIDialog) {
        dialogEventLogger.logDialogSettings()
        val animationController =
            dialogTransitionAnimator.createActivityTransitionController(dialog)
        if (animationController == null) {
            // The controller will take care of dismissing for us after
            // the animation, but let's make sure we dismiss the dialog
            // if we don't animate it.
            dialog.dismiss()
        }
        activityStarter.startActivity(
            ZEN_MODE_SETTINGS_INTENT,
            /* dismissShade= */ true,
            animationController,
        )
    }

    suspend fun showDialog(expandable: Expandable? = null): SystemUIDialog {
        // Dialogs shown by the DialogTransitionAnimator must be created and shown on the main
        // thread, so we post it to the UI handler.
        withContext(mainCoroutineContext) {
            // Create the dialog if necessary
            if (currentDialog == null) {
                createDialog()
            }

            expandable
                ?.dialogTransitionController(
                    DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN, INTERACTION_JANK_TAG)
                )
                ?.let { controller -> dialogTransitionAnimator.show(currentDialog!!, controller) }
                ?: currentDialog!!.show()
        }

        return currentDialog!!
    }

    /**
     * Launches the [intent] by animating from the dialog. If the dialog is not showing, just
     * launches it normally without animating.
     */
    fun launchFromDialog(intent: Intent) {
        // TODO: b/394571336 - Remove this method and inline "actual" if b/394571336 fixed.
        // Workaround for Compose bug, see b/394241061 and b/394571336 -- Need to post on the main
        // thread so that dialog dismissal doesn't crash after a long press inside it (the *double*
        // jump, out and back in, is because mainCoroutineContext is .immediate).
        applicationCoroutineScope.launch {
            withContext(bgContext) {
                withContext(mainCoroutineContext) { actualLaunchFromDialog(intent) }
            }
        }
    }

    private fun actualLaunchFromDialog(intent: Intent) {
        Assert.isMainThread()
        if (currentDialog == null) {
            Log.w(
                TAG,
                "Cannot launch from dialog, the dialog is not present. " +
                    "Will launch activity without animating.",
            )
        }

        val animationController =
            currentDialog?.let { dialogTransitionAnimator.createActivityTransitionController(it) }
        if (animationController == null) {
            currentDialog?.dismiss()
        }
        activityStarter.startActivity(intent, /* dismissShade= */ true, animationController)
    }

    /**
     * Special dialog to ask the user for the duration of DND. Not to be confused with the modes
     * dialog itself.
     */
    @UiThread
    fun makeDndDurationDialog(): Dialog {
        val dialog =
            EnableDndDialogFactory(
                    context,
                    R.style.Theme_SystemUI_Dialog,
                    /* cancelIsNeutral= */ true,
                    dndDurationDialogLogger,
                )
                .createDialog()
        SystemUIDialog.applyFlags(dialog)
        SystemUIDialog.setShowForAllUsers(dialog, true)
        SystemUIDialog.registerDismissListener(dialog)
        SystemUIDialog.setDialogSize(dialog)
        return dialog
    }

    companion object {
        private const val TAG = "ModesDialogDelegate"
        private val ZEN_MODE_SETTINGS_INTENT = Intent(Settings.ACTION_ZEN_MODE_SETTINGS)
        private const val INTERACTION_JANK_TAG = "configure_priority_modes"
    }
}
