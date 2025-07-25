package com.android.systemui.scene.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.android.systemui.qs.ui.adapter.QSSceneAdapter
import com.android.systemui.scene.shared.model.SceneContainerConfig
import com.android.systemui.scene.shared.model.SceneDataSourceDelegator
import com.android.systemui.scene.ui.composable.Overlay
import com.android.systemui.scene.ui.composable.Scene
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import com.android.systemui.shade.TouchLogger
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableStateFlow

/** A root view of the main SysUI window that supports scenes. */
class SceneWindowRootView(context: Context, attrs: AttributeSet?) : WindowRootView(context, attrs) {

    private var motionEventHandler: SceneContainerViewModel.MotionEventHandler? = null
    // TODO(b/298525212): remove once Compose exposes window inset bounds.
    private val windowInsets: MutableStateFlow<WindowInsets?> = MutableStateFlow(null)

    fun init(
        viewModelFactory: SceneContainerViewModel.Factory,
        containerConfig: SceneContainerConfig,
        sharedNotificationContainer: SharedNotificationContainer,
        scenes: Set<Scene>,
        overlays: Set<Overlay>,
        layoutInsetController: LayoutInsetsController,
        sceneDataSourceDelegator: SceneDataSourceDelegator,
        qsSceneAdapter: Provider<QSSceneAdapter>,
        sceneJankMonitorFactory: SceneJankMonitor.Factory,
        windowRootViewKeyEventHandler: WindowRootViewKeyEventHandler,
    ) {
        setLayoutInsetsController(layoutInsetController)
        SceneWindowRootViewBinder.bind(
            view = this@SceneWindowRootView,
            viewModelFactory = viewModelFactory,
            motionEventHandlerReceiver = { motionEventHandler ->
                this.motionEventHandler = motionEventHandler
            },
            windowInsets = windowInsets,
            containerConfig = containerConfig,
            sharedNotificationContainer = sharedNotificationContainer,
            scenes = scenes,
            overlays = overlays,
            onVisibilityChangedInternal = { isVisible ->
                super.setVisibility(if (isVisible) View.VISIBLE else View.INVISIBLE)
            },
            dataSourceDelegator = sceneDataSourceDelegator,
            qsSceneAdapter = qsSceneAdapter,
            sceneJankMonitorFactory = sceneJankMonitorFactory,
        )
        setWindowRootViewKeyEventHandler(windowRootViewKeyEventHandler)
    }

    override fun setVisibility(visibility: Int) {
        // Do nothing. We don't want external callers to invoke this. Instead, we drive our own
        // visibility from our view-binder.
    }

    // TODO(b/298525212): remove once Compose exposes window inset bounds.
    override fun onApplyWindowInsets(windowInsets: WindowInsets): WindowInsets {
        this.windowInsets.value = windowInsets
        return windowInsets
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        motionEventHandler?.onMotionEvent(ev)
        return super.dispatchTouchEvent(ev).also {
            TouchLogger.logDispatchTouch(TAG, ev, it)
            motionEventHandler?.onMotionEventComplete()
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        event?.let { motionEventHandler?.onEmptySpaceMotionEvent(it) }
        return super.onTouchEvent(event)
    }

    companion object {
        private const val TAG = "SceneWindowRootView"
    }
}
