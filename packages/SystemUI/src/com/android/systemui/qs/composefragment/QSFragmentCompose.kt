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

package com.android.systemui.qs.composefragment

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.Trace
import android.util.IndentingPrintWriter
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.compose.animation.scene.ContentKey
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.ElementMatcher
import com.android.compose.animation.scene.MutableSceneTransitionLayoutState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.rememberMutableSceneTransitionLayoutState
import com.android.compose.animation.scene.transitions
import com.android.compose.modifiers.height
import com.android.compose.modifiers.padding
import com.android.compose.modifiers.thenIf
import com.android.compose.theme.PlatformTheme
import com.android.mechanics.GestureContext
import com.android.systemui.Dumpable
import com.android.systemui.Flags
import com.android.systemui.Flags.notificationShadeBlur
import com.android.systemui.brightness.ui.compose.BrightnessSliderContainer
import com.android.systemui.brightness.ui.compose.ContainerColors
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyboard.shortcut.ui.composable.InteractionsConfig
import com.android.systemui.keyboard.shortcut.ui.composable.ProvideShortcutHelperIndication
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.lifecycle.setSnapshotBinding
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.plugins.qs.QS
import com.android.systemui.plugins.qs.QSContainerController
import com.android.systemui.qs.composefragment.SceneKeys.QuickQuickSettings
import com.android.systemui.qs.composefragment.SceneKeys.QuickSettings
import com.android.systemui.qs.composefragment.SceneKeys.debugName
import com.android.systemui.qs.composefragment.SceneKeys.toIdleSceneKey
import com.android.systemui.qs.composefragment.ui.GridAnchor
import com.android.systemui.qs.composefragment.ui.NotificationScrimClipParams
import com.android.systemui.qs.composefragment.ui.quickQuickSettingsToQuickSettings
import com.android.systemui.qs.composefragment.ui.toEditMode
import com.android.systemui.qs.composefragment.viewmodel.QSFragmentComposeViewModel
import com.android.systemui.qs.flags.QSComposeFragment
import com.android.systemui.qs.footer.ui.compose.FooterActions
import com.android.systemui.qs.panels.ui.compose.EditMode
import com.android.systemui.qs.panels.ui.compose.QuickQuickSettings
import com.android.systemui.qs.panels.ui.compose.TileGrid
import com.android.systemui.qs.shared.ui.ElementKeys
import com.android.systemui.qs.ui.composable.QuickSettingsShade
import com.android.systemui.qs.ui.composable.QuickSettingsShade.systemGestureExclusionInShade
import com.android.systemui.qs.ui.composable.QuickSettingsTheme
import com.android.systemui.res.R
import com.android.systemui.util.LifecycleFragment
import com.android.systemui.util.animation.UniqueObjectHostView
import com.android.systemui.util.asIndenting
import com.android.systemui.util.printSection
import com.android.systemui.util.println
import java.io.PrintWriter
import java.util.function.Consumer
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@SuppressLint("ValidFragment")
class QSFragmentCompose
@Inject
constructor(
    private val qsFragmentComposeViewModelFactory: QSFragmentComposeViewModel.Factory,
    private val dumpManager: DumpManager,
) : LifecycleFragment(), QS, Dumpable {

    private val scrollListener = MutableStateFlow<QS.ScrollListener?>(null)
    private val collapsedMediaVisibilityChangedListener =
        MutableStateFlow<(Consumer<Boolean>)?>(null)
    private val heightListener = MutableStateFlow<QS.HeightListener?>(null)
    private val qsContainerController = MutableStateFlow<QSContainerController?>(null)

    private lateinit var viewModel: QSFragmentComposeViewModel

    private val qqsVisible = MutableStateFlow(false)
    private val qqsPositionOnRoot = Rect()
    private val composeViewPositionOnScreen = Rect()
    private val scrollState = ScrollState(0)
    private val locationTemp = IntArray(2)

    // Inside object for namespacing
    private val notificationScrimClippingParams =
        object {
            var isEnabled by mutableStateOf(false)
            var params by mutableStateOf(NotificationScrimClipParams())

            fun dump(pw: IndentingPrintWriter) {
                pw.printSection("NotificationScrimClippingParams") {
                    pw.println("isEnabled", isEnabled)
                    pw.println("params", params)
                }
            }
        }

    override fun onStart() {
        super.onStart()
        registerDumpable()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        QSComposeFragment.isUnexpectedlyInLegacyMode()
        viewModel = qsFragmentComposeViewModelFactory.create(lifecycleScope)

        setListenerCollections()
        lifecycleScope.launch { viewModel.activate() }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = inflater.context
        val composeView =
            ComposeView(context).apply {
                id = R.id.quick_settings_container
                repeatWhenAttached {
                    repeatOnLifecycle(Lifecycle.State.CREATED) {
                        setViewTreeOnBackPressedDispatcherOwner(
                            object : OnBackPressedDispatcherOwner {
                                override val onBackPressedDispatcher =
                                    OnBackPressedDispatcher().apply {
                                        setOnBackInvokedDispatcher(
                                            it.viewRootImpl.onBackInvokedDispatcher
                                        )
                                    }

                                override val lifecycle: Lifecycle =
                                    this@repeatWhenAttached.lifecycle
                            }
                        )
                        setContent { this@QSFragmentCompose.Content() }
                    }
                }
            }

        val frame =
            FrameLayoutTouchPassthrough(
                context,
                { notificationScrimClippingParams.isEnabled },
                snapshotFlow { notificationScrimClippingParams.params },
                // Only allow scrolling when we are fully expanded. That way, we don't intercept
                // swipes in lockscreen (when somehow QS is receiving touches).
                { (scrollState.canScrollForward && viewModel.isQsFullyExpanded) || isCustomizing },
                viewModel::emitMotionEventForFalsingSwipeNested,
            )
        frame.addView(
            composeView,
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        return frame
    }

    @Composable
    private fun Content() {
        PlatformTheme(isDarkTheme = if (notificationShadeBlur()) isSystemInDarkTheme() else true) {
            ProvideShortcutHelperIndication(interactionsConfig = interactionsConfig()) {
                // TODO(b/389985793): Make sure that there is no coroutine work or recompositions
                // happening when alwaysCompose is true but isQsVisibleAndAnyShadeExpanded is false.
                if (alwaysCompose || viewModel.isQsVisibleAndAnyShadeExpanded) {
                    Box(
                        modifier =
                            Modifier.thenIf(alwaysCompose) {
                                    Modifier.layout { measurable, constraints ->
                                        measurable.measure(constraints).run {
                                            layout(width, height) {
                                                if (viewModel.isQsVisibleAndAnyShadeExpanded) {
                                                    place(0, 0)
                                                }
                                            }
                                        }
                                    }
                                }
                                .graphicsLayer { alpha = viewModel.viewAlpha }
                                .thenIf(!Flags.notificationShadeBlur()) {
                                    Modifier.offset {
                                        IntOffset(
                                            x = 0,
                                            y = viewModel.viewTranslationY.fastRoundToInt(),
                                        )
                                    }
                                }
                                // Disable touches in the whole composable while the mirror is
                                // showing. While the mirror is showing, an ancestor of the
                                // ComposeView is made alpha 0, but touches are still being captured
                                // by the composables.
                                .gesturesDisabled(viewModel.showingMirror)
                    ) {
                        CollapsableQuickSettingsSTL()
                    }
                }
            }
        }
    }

    /**
     * STL that contains both QQS (tiles) and QS (brightness, tiles, footer actions), but no Edit
     * mode. It tracks [QSFragmentComposeViewModel.expansionState] to drive the transition between
     * [SceneKeys.QuickQuickSettings] and [SceneKeys.QuickSettings].
     */
    @Composable
    private fun CollapsableQuickSettingsSTL() {
        val nextCookie = remember {
            object {
                var value = 0
            }
        }
        val transitionToCookie = remember { mutableMapOf<TransitionState.Transition, Int>() }
        val sceneState =
            rememberMutableSceneTransitionLayoutState(
                initialScene = remember { viewModel.expansionState.toIdleSceneKey() },
                transitions =
                    transitions {
                        from(QuickQuickSettings, QuickSettings) {
                            quickQuickSettingsToQuickSettings(viewModel::animateTilesExpansion::get)
                        }
                        to(SceneKeys.EditMode) {
                            spec = tween(durationMillis = EDIT_MODE_TIME_MILLIS)
                            toEditMode()
                        }
                    },
                onTransitionStart = { transition ->
                    val cookie = nextCookie.value++
                    transitionToCookie[transition] = cookie
                    Trace.beginAsyncSection(
                        "CollapsableQuickSettingsSTL ${transition.debugName}",
                        cookie,
                    )
                },
                onTransitionEnd = { transition ->
                    Trace.endAsyncSection(
                        "CollapsableQuickSettingsSTL ${transition.debugName}",
                        transitionToCookie.remove(transition) ?: -1,
                    )
                },
            )

        LaunchedEffect(Unit) {
            synchronizeQsState(
                sceneState,
                viewModel.containerViewModel.editModeViewModel.isEditing,
                snapshotFlow { viewModel.expansionState }.map { it.progress },
            )
        }

        SceneTransitionLayout(state = sceneState, modifier = Modifier.fillMaxSize()) {
            scene(QuickSettings, alwaysCompose = alwaysCompose) {
                LaunchedEffect(Unit) { viewModel.onQSOpen() }
                Element(QuickSettings.rootElementKey, Modifier) { QuickSettingsElement() }
            }

            scene(QuickQuickSettings, alwaysCompose = alwaysCompose) {
                LaunchedEffect(Unit) { viewModel.onQQSOpen() }
                // Cannot pass the element modifier in because the top element has a `testTag`
                // and this would overwrite it.
                Element(QuickQuickSettings.rootElementKey, Modifier) { QuickQuickSettingsElement() }
            }

            scene(SceneKeys.EditMode) {
                Element(SceneKeys.EditMode.rootElementKey, Modifier) { EditModeElement() }
            }
        }
    }

    override fun setPanelView(notificationPanelView: QS.HeightListener?) {
        heightListener.value = notificationPanelView
    }

    override fun hideImmediately() {
        //        view?.animate()?.cancel()
        //        view?.y = -qsMinExpansionHeight.toFloat()
    }

    override fun getQsMinExpansionHeight(): Int {
        return if (viewModel.isInSplitShade) {
            getQsMinExpansionHeightForSplitShade()
        } else {
            viewModel.qqsHeight
        }
    }

    /**
     * Returns the min expansion height for split shade.
     *
     * On split shade, QS is always expanded and goes from the top of the screen to the bottom of
     * the QS container.
     */
    private fun getQsMinExpansionHeightForSplitShade(): Int {
        view?.getLocationOnScreen(locationTemp)
        val top = locationTemp.get(1)
        // We want to get the original top position, so we subtract any translation currently set.
        val originalTop = (top - (view?.translationY ?: 0f)).toInt()
        // On split shade the QS view doesn't start at the top of the screen, so we need to add the
        // top margin.
        return originalTop + (view?.height ?: 0)
    }

    override fun getDesiredHeight(): Int {
        /*
         * Looking at the code, it seems that
         * * If customizing, then the height is that of the view post-layout, which is set by
         *   QSContainerImpl.calculateContainerHeight, which is the height the customizer takes
         * * If not customizing, it's the measured height. So we may want to surface that.
         */
        return view?.height ?: 0
    }

    override fun setHeightOverride(desiredHeight: Int) {
        viewModel.heightOverride = desiredHeight
    }

    override fun setHeaderClickable(qsExpansionEnabled: Boolean) {
        // Empty method
    }

    override fun isCustomizing(): Boolean {
        return viewModel.isEditing
    }

    override fun closeCustomizer() {
        viewModel.containerViewModel.editModeViewModel.stopEditing()
    }

    override fun setOverscrolling(overscrolling: Boolean) {
        viewModel.isStackScrollerOverscrolling = overscrolling
    }

    override fun setExpanded(qsExpanded: Boolean) {
        viewModel.isQsExpanded = qsExpanded
    }

    override fun setListening(listening: Boolean) {
        // Not needed, views start listening and collection when composed
    }

    override fun setQsVisible(qsVisible: Boolean) {
        viewModel.isQsVisible = qsVisible
    }

    override fun isShowingDetail(): Boolean {
        return isCustomizing
    }

    override fun closeDetail() {
        closeCustomizer()
    }

    override fun animateHeaderSlidingOut() {
        // TODO(b/353254353)
    }

    override fun setQsExpansion(
        qsExpansionFraction: Float,
        panelExpansionFraction: Float,
        headerTranslation: Float,
        squishinessFraction: Float,
    ) {
        viewModel.setQsExpansionValue(qsExpansionFraction)
        viewModel.panelExpansionFraction = panelExpansionFraction
        viewModel.squishinessFraction = squishinessFraction
        viewModel.proposedTranslation = headerTranslation
    }

    override fun setHeaderListening(listening: Boolean) {
        // Not needed, header will start listening as soon as it's composed
    }

    override fun notifyCustomizeChanged() {
        // Not needed, only called from inside customizer
    }

    override fun setContainerController(controller: QSContainerController?) {
        qsContainerController.value = controller
    }

    override fun setCollapseExpandAction(action: Runnable?) {
        viewModel.collapseExpandAccessibilityAction = action
    }

    override fun getHeightDiff(): Int {
        return viewModel.heightDiff
    }

    override fun getHeader(): View? {
        QSComposeFragment.isUnexpectedlyInLegacyMode()
        return null
    }

    override fun setShouldUpdateSquishinessOnMedia(shouldUpdate: Boolean) {
        viewModel.shouldUpdateSquishinessOnMedia = shouldUpdate
    }

    override fun setInSplitShade(isInSplitShade: Boolean) {
        viewModel.isInSplitShade = isInSplitShade
    }

    override fun setTransitionToFullShadeProgress(
        isTransitioningToFullShade: Boolean,
        qsTransitionFraction: Float,
        qsSquishinessFraction: Float,
    ) {
        viewModel.isTransitioningToFullShade = isTransitioningToFullShade
        viewModel.lockscreenToShadeProgress = qsTransitionFraction
        if (isTransitioningToFullShade) {
            viewModel.squishinessFraction = qsSquishinessFraction
        }
    }

    override fun setFancyClipping(
        leftInset: Int,
        top: Int,
        rightInset: Int,
        bottom: Int,
        cornerRadius: Int,
        visible: Boolean,
        fullWidth: Boolean,
    ) {
        notificationScrimClippingParams.isEnabled = visible
        notificationScrimClippingParams.params =
            NotificationScrimClipParams(
                top,
                bottom,
                if (fullWidth) 0 else leftInset,
                if (fullWidth) 0 else rightInset,
                cornerRadius,
            )
    }

    override fun isFullyCollapsed(): Boolean {
        return viewModel.isQsFullyCollapsed
    }

    override fun setCollapsedMediaVisibilityChangedListener(listener: Consumer<Boolean>?) {
        collapsedMediaVisibilityChangedListener.value = listener
    }

    override fun setScrollListener(scrollListener: QS.ScrollListener?) {
        this.scrollListener.value = scrollListener
    }

    override fun setOverScrollAmount(overScrollAmount: Int) {
        viewModel.overScrollAmount = overScrollAmount
    }

    override fun setIsNotificationPanelFullWidth(isFullWidth: Boolean) {
        viewModel.isSmallScreen = isFullWidth
    }

    override fun getHeaderTop(): Int {
        return qqsPositionOnRoot.top
    }

    override fun getHeaderBottom(): Int {
        return qqsPositionOnRoot.bottom
    }

    override fun getHeaderLeft(): Int {
        return qqsPositionOnRoot.left
    }

    override fun getHeaderBoundsOnScreen(outBounds: Rect) {
        outBounds.set(qqsPositionOnRoot)
        view?.getBoundsOnScreen(composeViewPositionOnScreen)
            ?: run { composeViewPositionOnScreen.setEmpty() }
        outBounds.offset(composeViewPositionOnScreen.left, composeViewPositionOnScreen.top)
    }

    override fun isHeaderShown(): Boolean {
        return qqsVisible.value
    }

    private fun setListenerCollections() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                this@QSFragmentCompose.view?.setSnapshotBinding {
                    scrollListener.value?.onQsPanelScrollChanged(scrollState.value)
                    collapsedMediaVisibilityChangedListener.value?.accept(viewModel.qqsMediaVisible)
                }
                launch {
                    setListenerJob(
                        heightListener,
                        viewModel.containerViewModel.editModeViewModel.isEditing,
                    ) {
                        onQsHeightChanged()
                    }
                }
                launch {
                    setListenerJob(
                        qsContainerController,
                        viewModel.containerViewModel.editModeViewModel.isEditing,
                    ) {
                        setCustomizerShowing(it, EDIT_MODE_TIME_MILLIS.toLong())
                    }
                }
            }
        }
    }

    @Composable
    private fun ContentScope.QuickQuickSettingsElement(modifier: Modifier = Modifier) {
        val qqsPadding = viewModel.qqsHeaderHeight
        val bottomPadding = viewModel.qqsBottomPadding
        DisposableEffect(Unit) {
            qqsVisible.value = true

            onDispose { qqsVisible.value = false }
        }
        val squishiness by
            viewModel.quickQuickSettingsViewModel.squishinessViewModel.squishiness
                .collectAsStateWithLifecycle()

        Column(modifier = modifier.sysuiResTag(ResIdTags.quickQsPanel)) {
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .onPlaced { coordinates ->
                            val (leftFromRoot, topFromRoot) = coordinates.positionInRoot().round()
                            qqsPositionOnRoot.set(
                                leftFromRoot,
                                topFromRoot,
                                leftFromRoot + coordinates.size.width,
                                topFromRoot + coordinates.size.height,
                            )
                            if (squishiness == 1f) {
                                viewModel.qqsHeight = coordinates.size.height
                            }
                        }
                        // Use an approach layout to determien the height without squishiness, as
                        // that's the value that NPVC and QuickSettingsController care about
                        // (measured height).
                        .approachLayout(isMeasurementApproachInProgress = { squishiness < 1f }) {
                            measurable,
                            constraints ->
                            viewModel.qqsHeight = lookaheadSize.height
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        }
                        .padding(top = { qqsPadding }, bottom = { bottomPadding })
            ) {
                val Tiles =
                    @Composable {
                        QuickQuickSettings(
                            viewModel = viewModel.quickQuickSettingsViewModel,
                            listening = {
                                /*
                                 *  When always compose is false, this will always be true, and we'll be
                                 *  listening whenever this is composed.
                                 *  When always compose is true, we listen if we are visible and not
                                 *  fully expanded
                                 */
                                !alwaysCompose ||
                                    (viewModel.isQsVisibleAndAnyShadeExpanded &&
                                        viewModel.expansionState.progress < 1f &&
                                        !viewModel.isEditing)
                            },
                        )
                    }
                val Media =
                    @Composable {
                        if (viewModel.qqsMediaVisible) {
                            MediaObject(
                                // In order to have stable constraints passed to the AndroidView
                                // during expansion (available height changing due to squishiness),
                                // We always allow the media here to be as tall as it wants.
                                // (b/383085298)
                                modifier = Modifier.requiredHeightIn(max = Dp.Infinity),
                                mediaHost = viewModel.qqsMediaHost,
                            )
                        }
                    }

                if (viewModel.isQsEnabled) {
                    Box(
                        modifier =
                            Modifier.collapseExpandSemanticAction(
                                    stringResource(
                                        id = R.string.accessibility_quick_settings_expand
                                    )
                                )
                                .padding(horizontal = qsHorizontalMargin())
                    ) {
                        QuickQuickSettingsLayout(
                            tiles = Tiles,
                            media = Media,
                            mediaInRow = viewModel.qqsMediaInRow,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
        }
    }

    @Composable
    private fun ContentScope.QuickSettingsElement(modifier: Modifier = Modifier) {
        val qqsPadding = viewModel.qqsHeaderHeight
        val qsExtraPadding = dimensionResource(R.dimen.qs_panel_padding_top)
        Column(
            modifier =
                modifier.collapseExpandSemanticAction(
                    stringResource(id = R.string.accessibility_quick_settings_collapse)
                )
        ) {
            if (viewModel.isQsEnabled) {
                Element(ElementKeys.QuickSettingsContent, modifier = Modifier.weight(1f)) {
                    DisposableEffect(Unit) {
                        lifecycleScope.launch { scrollState.scrollTo(0) }
                        onDispose { lifecycleScope.launch { scrollState.scrollTo(0) } }
                    }

                    Column(
                        modifier =
                            Modifier.fillMaxSize()
                                .onPlaced { coordinates ->
                                    val positionOnScreen = coordinates.positionOnScreen()
                                    val left = positionOnScreen.x
                                    val right = left + coordinates.size.width
                                    val top = positionOnScreen.y
                                    val bottom = top + coordinates.size.height
                                    viewModel.applyNewQsScrollerBounds(
                                        left = left,
                                        top = top,
                                        right = right,
                                        bottom = bottom,
                                    )
                                }
                                .offset {
                                    IntOffset(
                                        x = 0,
                                        y = viewModel.qsScrollTranslationY.fastRoundToInt(),
                                    )
                                }
                                .onSizeChanged { viewModel.qsScrollHeight = it.height }
                                .verticalScroll(scrollState)
                                .sysuiResTag(ResIdTags.qsScroll)
                    ) {
                        val containerViewModel = viewModel.containerViewModel
                        Spacer(
                            modifier = Modifier.height { qqsPadding + qsExtraPadding.roundToPx() }
                        )
                        val BrightnessSlider =
                            @Composable {
                                Box(
                                    Modifier.systemGestureExclusionInShade(
                                        enabled = {
                                            /*
                                             * While we are transitioning into QS (either from QQS
                                             * or from gone), the global position of the brightness
                                             * slider will change in every frame. This causes
                                             * the modifier to send a new gesture exclusion
                                             * rectangle on every frame. Instead, only apply the
                                             * modifier when this is settled.
                                             */
                                            layoutState.transitionState is TransitionState.Idle &&
                                                viewModel.isNotTransitioning
                                        }
                                    )
                                ) {
                                    AlwaysDarkMode {
                                        BrightnessSliderContainer(
                                            viewModel =
                                                containerViewModel.brightnessSliderViewModel,
                                            containerColors =
                                                ContainerColors(
                                                    Color.Transparent,
                                                    ContainerColors.defaultContainerColor,
                                                ),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        val TileGrid =
                            @Composable {
                                Box {
                                    GridAnchor()
                                    TileGrid(
                                        viewModel = containerViewModel.tileGridViewModel,
                                        modifier = Modifier.fillMaxWidth(),
                                        listening = {
                                            /*
                                             *  When always compose is false, this will always be true,
                                             *  and we'll be listening whenever this is composed.
                                             *  When always compose is true, we look a the second
                                             *  condition and we'll listen if QS is visible AND we are
                                             *  not fully collapsed.
                                             */
                                            !alwaysCompose ||
                                                (viewModel.isQsVisibleAndAnyShadeExpanded &&
                                                    viewModel.expansionState.progress > 0f &&
                                                    !viewModel.isEditing)
                                        },
                                    )
                                }
                            }
                        val Media =
                            @Composable {
                                if (viewModel.qsMediaVisible) {
                                    MediaObject(
                                        mediaHost = viewModel.qsMediaHost,
                                        update = { translationY = viewModel.qsMediaTranslationY },
                                    )
                                }
                            }
                        Box(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .sysuiResTag(ResIdTags.quickSettingsPanel)
                                    .padding(
                                        top = QuickSettingsShade.Dimensions.Padding,
                                        start = qsHorizontalMargin(),
                                        end = qsHorizontalMargin(),
                                    )
                        ) {
                            QuickSettingsLayout(
                                brightness = BrightnessSlider,
                                tiles = TileGrid,
                                media = Media,
                                mediaInRow = viewModel.qsMediaInRow,
                            )
                        }
                    }
                }
                QuickSettingsTheme {
                    Element(
                        ElementKeys.FooterActions,
                        Modifier.sysuiResTag(ResIdTags.qsFooterActions),
                    ) {
                        FooterActions(
                            viewModel = viewModel.footerActionsViewModel,
                            qsVisibilityLifecycleOwner = this@QSFragmentCompose,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun EditModeElement(modifier: Modifier = Modifier) {
        // No need for top padding, the Scaffold inside takes care of the correct insets
        EditMode(
            viewModel = viewModel.containerViewModel.editModeViewModel,
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = { QuickSettingsShade.Dimensions.Padding.roundToPx() }),
        )
    }

    private fun Modifier.collapseExpandSemanticAction(label: String): Modifier {
        return viewModel.collapseExpandAccessibilityAction?.let {
            semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction(label) {
                            it.run()
                            true
                        }
                    )
            }
        } ?: this
    }

    private fun registerDumpable() {
        val instanceId = instanceProvider.getNextId()
        // Add an instanceId because the system may have more than 1 of these when re-inflating and
        // DumpManager doesn't like repeated identifiers. Also, put it first because DumpHandler
        // matches by end.
        val stringId = "$instanceId-QSFragmentCompose"
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.CREATED) {
                try {
                    dumpManager.registerNormalDumpable(stringId, this@QSFragmentCompose)
                    awaitCancellation()
                } finally {
                    dumpManager.unregisterDumpable(stringId)
                }
            }
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.asIndenting().run {
            notificationScrimClippingParams.dump(this)
            printSection("QQS positioning") {
                println("qqsHeight", "${headerHeight}px")
                println("qqsTop", "${headerTop}px")
                println("qqsBottom", "${headerBottom}px")
                println("qqsLeft", "${headerLeft}px")
                println("qqsPositionOnRoot", qqsPositionOnRoot)
                val rect = Rect()
                getHeaderBoundsOnScreen(rect)
                println("qqsPositionOnScreen", rect)
            }
            println("QQS visible", qqsVisible.value)
            println("Always composed", alwaysCompose)
            if (::viewModel.isInitialized) {
                printSection("View Model") { viewModel.dump(this@run, args) }
            }
        }
    }
}

private suspend inline fun <Listener : Any, Data> setListenerJob(
    listenerFlow: MutableStateFlow<Listener?>,
    dataFlow: Flow<Data>,
    crossinline onCollect: suspend Listener.(Data) -> Unit,
) {
    coroutineScope {
        try {
            listenerFlow.collectLatest { listenerOrNull ->
                listenerOrNull?.let { currentListener ->
                    launch {
                        // Called when editing mode changes
                        dataFlow.collect { currentListener.onCollect(it) }
                    }
                }
            }
            awaitCancellation()
        } finally {
            listenerFlow.value = null
        }
    }
}

private val instanceProvider =
    object {
        private var currentId = 0

        fun getNextId(): Int {
            return currentId++
        }
    }

object SceneKeys {
    val QuickQuickSettings = SceneKey("QuickQuickSettingsScene")
    val QuickSettings = SceneKey("QuickSettingsScene")
    val EditMode = SceneKey("EditModeScene")

    val TransitionState.Transition.debugName: String
        get() = "[from=${fromContent.debugName}, to=${toContent.debugName}]"

    fun QSFragmentComposeViewModel.QSExpansionState.toIdleSceneKey(): SceneKey {
        return when {
            progress < 0.5f -> QuickQuickSettings
            else -> QuickSettings
        }
    }

    val QqsTileElementMatcher =
        object : ElementMatcher {
            override fun matches(key: ElementKey, content: ContentKey): Boolean {
                return content == SceneKeys.QuickQuickSettings &&
                    ElementKeys.TileElementMatcher.matches(key, content)
            }
        }
}

private suspend fun synchronizeQsState(
    state: MutableSceneTransitionLayoutState,
    editMode: Flow<Boolean>,
    expansion: Flow<Float>,
) {
    coroutineScope {
        val animationScope = this

        var currentTransition: ExpansionTransition? = null

        fun snapTo(scene: SceneKey) {
            state.snapTo(scene)
            currentTransition = null
        }

        editMode.combine(expansion, ::Pair).collectLatest { (editMode, progress) ->
            if (editMode && state.currentScene != SceneKeys.EditMode) {
                state.setTargetScene(SceneKeys.EditMode, animationScope)?.second?.join()
            } else if (!editMode && state.currentScene == SceneKeys.EditMode) {
                state.setTargetScene(SceneKeys.QuickSettings, animationScope)?.second?.join()
            }
            if (!editMode) {
                when (progress) {
                    0f -> snapTo(QuickQuickSettings)
                    1f -> snapTo(QuickSettings)
                    else -> {
                        val transition = currentTransition
                        if (transition != null) {
                            transition.progress = progress
                            return@collectLatest
                        }

                        val newTransition =
                            ExpansionTransition(progress).also { currentTransition = it }
                        state.startTransitionImmediately(
                            animationScope = animationScope,
                            transition = newTransition,
                        )
                    }
                }
            }
        }
    }
}

private class ExpansionTransition(currentProgress: Float) :
    TransitionState.Transition.ChangeScene(
        fromScene = QuickQuickSettings,
        toScene = QuickSettings,
    ) {
    override val currentScene: SceneKey
        get() {
            // This should return the logical scene. If the QS STLState is only driven by
            // synchronizeQSState() then it probably does not matter which one we return, this is
            // only used to compute the current user actions of a STL.
            return QuickQuickSettings
        }

    override var progress: Float by mutableFloatStateOf(currentProgress)

    override val progressVelocity: Float
        get() = 0f

    override val isInitiatedByUserInput: Boolean
        get() = true

    override val isUserInputOngoing: Boolean
        get() = true

    override val gestureContext: GestureContext? = null

    private val finishCompletable = CompletableDeferred<Unit>()

    override suspend fun run() {
        // This transition runs until it is interrupted by another one.
        finishCompletable.await()
    }

    override fun freezeAndAnimateToCurrentState() {
        finishCompletable.complete(Unit)
    }
}

private const val EDIT_MODE_TIME_MILLIS = 500

/**
 * Performs different touch handling based on the state of the ComposeView:
 * * Ignore touches below the value returned by [clippingTopProvider], when clipping is enabled, as
 *   per [clippingEnabledProvider].
 * * Intercept touches that would overscroll QS forward and instead allow them to be used to close
 *   the shade.
 */
private class FrameLayoutTouchPassthrough(
    context: Context,
    private val clippingEnabledProvider: () -> Boolean,
    private val clippingParams: Flow<NotificationScrimClipParams>,
    private val canScrollForwardQs: () -> Boolean,
    private val emitMotionEventForFalsing: () -> Unit,
) : FrameLayout(context) {

    init {
        repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                clippingParams.collect { currentClipParams = it }
            }
        }
    }

    private val currentClippingPath = Path()
    private var lastWidth = -1
        set(value) {
            if (field != value) {
                field = value
                updateClippingPath()
            }
        }

    private var currentClipParams = NotificationScrimClipParams()
        set(value) {
            if (field != value) {
                field = value
                updateClippingPath()
            }
        }

    private fun updateClippingPath() {
        currentClippingPath.rewind()
        if (clippingEnabledProvider()) {
            val right = width + currentClipParams.rightInset
            val left = -currentClipParams.leftInset
            val top = currentClipParams.top
            val bottom = currentClipParams.bottom
            currentClippingPath.addRoundRect(
                left.toFloat(),
                top.toFloat(),
                right.toFloat(),
                bottom.toFloat(),
                currentClipParams.radius.toFloat(),
                currentClipParams.radius.toFloat(),
                Path.Direction.CW,
            )
        }
        invalidate()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        lastWidth = right - left
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!currentClippingPath.isEmpty) {
            canvas.clipOutPath(currentClippingPath)
        }
        super.dispatchDraw(canvas)
    }

    override fun isTransformedTouchPointInView(
        x: Float,
        y: Float,
        child: View?,
        outLocalPoint: PointF?,
    ): Boolean {
        return if (clippingEnabledProvider() && y + translationY > currentClipParams.top) {
            false
        } else {
            super.isTransformedTouchPointInView(x, y, child, outLocalPoint)
        }
    }

    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    var downY = 0f
    var preventingIntercept = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                preventingIntercept = false
                if (canScrollVertically(1)) {
                    // If we can scroll down, make sure we're not intercepted by the parent
                    preventingIntercept = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else if (!canScrollVertically(-1)) {
                    // Don't pass on the touch to the view, because scrolling will unconditionally
                    // disallow interception even if we can't scroll.
                    // if a user can't scroll at all, we should never listen to the touch.
                    return false
                }
            }
            MotionEvent.ACTION_UP -> {
                if (preventingIntercept) {
                    emitMotionEventForFalsing()
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // If there's a touch on this view and we can scroll down, we don't want to be intercepted
        val action = ev.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                preventingIntercept = false
                // If we can scroll down, make sure none of our parents intercepts us.
                if (canScrollForwardQs()) {
                    preventingIntercept = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                downY = ev.y
            }

            MotionEvent.ACTION_MOVE -> {
                val y = ev.y.toInt()
                val yDiff: Float = y - downY
                if (yDiff < -touchSlop && !canScrollForwardQs()) {
                    // Intercept touches that are overscrolling.
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }
}

private fun Modifier.gesturesDisabled(disabled: Boolean) =
    if (disabled) {
        pointerInput(Unit) {
            awaitPointerEventScope {
                // we should wait for all new pointer events
                while (true) {
                    awaitPointerEvent(pass = PointerEventPass.Initial)
                        .changes
                        .forEach(PointerInputChange::consume)
                }
            }
        }
    } else {
        this
    }

@Composable
private fun MediaObject(
    mediaHost: MediaHost,
    modifier: Modifier = Modifier,
    update: UniqueObjectHostView.() -> Unit = {},
) {
    Box {
        AndroidView(
            modifier = modifier,
            factory = {
                mediaHost.hostView.apply {
                    layoutParams =
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                        )
                }
            },
            update = { view -> view.update() },
            onReset = {},
        )
    }
}

@Composable
@VisibleForTesting
fun QuickQuickSettingsLayout(
    tiles: @Composable () -> Unit,
    media: @Composable () -> Unit,
    mediaInRow: Boolean,
) {
    if (mediaInRow) {
        Row(
            horizontalArrangement = spacedBy(dimensionResource(R.dimen.qs_tile_margin_vertical)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) { tiles() }
            Box(modifier = Modifier.weight(1f)) { media() }
        }
    } else {
        Column(verticalArrangement = spacedBy(dimensionResource(R.dimen.qs_tile_margin_vertical))) {
            tiles()
            media()
        }
    }
}

@Composable
@VisibleForTesting
fun QuickSettingsLayout(
    brightness: @Composable () -> Unit,
    tiles: @Composable () -> Unit,
    media: @Composable () -> Unit,
    mediaInRow: Boolean,
) {
    if (mediaInRow) {
        Column(
            verticalArrangement = spacedBy(QuickSettingsShade.Dimensions.Padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            brightness()
            Row(
                horizontalArrangement = spacedBy(QuickSettingsShade.Dimensions.Padding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) { tiles() }
                Box(modifier = Modifier.weight(1f)) { media() }
            }
        }
    } else {
        Column(
            verticalArrangement = spacedBy(QuickSettingsShade.Dimensions.Padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            brightness()
            tiles()
            media()
        }
    }
}

private object ResIdTags {
    const val quickSettingsPanel = "quick_settings_panel"
    const val quickQsPanel = "quick_qs_panel"
    const val qsScroll = "expanded_qs_scroll_view"
    const val qsFooterActions = "qs_footer_actions"
}

@Composable private fun qsHorizontalMargin() = dimensionResource(id = R.dimen.qs_horizontal_margin)

@Composable
private fun interactionsConfig() =
    InteractionsConfig(
        hoverOverlayColor = MaterialTheme.colorScheme.onSurface,
        hoverOverlayAlpha = 0.11f,
        pressedOverlayColor = MaterialTheme.colorScheme.onSurface,
        pressedOverlayAlpha = 0.15f,
        // we are OK using this as our content is clipped and all corner radius are larger than this
        surfaceCornerRadius = 28.dp,
    )

private inline val alwaysCompose
    get() = Flags.alwaysComposeQsUiFragment()

/**
 * Forces the configuration and themes to be dark theme. This is needed in order to have
 * [colorResource] retrieve the dark mode colors.
 *
 * This should be removed when [notificationShadeBlur] is removed
 */
@Composable
private fun AlwaysDarkMode(content: @Composable () -> Unit) {
    if (notificationShadeBlur()) {
        content()
    } else {
        val currentConfig = LocalConfiguration.current
        val darkConfig =
            Configuration(currentConfig).apply {
                uiMode =
                    (uiMode and (Configuration.UI_MODE_NIGHT_MASK.inv())) or
                        Configuration.UI_MODE_NIGHT_YES
            }
        val newContext = LocalContext.current.createConfigurationContext(darkConfig)
        CompositionLocalProvider(
            LocalConfiguration provides darkConfig,
            LocalContext provides newContext,
        ) {
            content()
        }
    }
}
