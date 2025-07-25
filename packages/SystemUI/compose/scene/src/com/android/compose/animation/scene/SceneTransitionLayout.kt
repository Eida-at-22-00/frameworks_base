/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.compose.animation.scene

import androidx.annotation.FloatRange
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.android.compose.gesture.NestedScrollableBound

/**
 * [SceneTransitionLayout] is a container that automatically animates its content whenever its state
 * changes.
 *
 * Note: You should use [androidx.compose.animation.AnimatedContent] instead of
 * [SceneTransitionLayout] if it fits your need. Use [SceneTransitionLayout] over AnimatedContent if
 * you need support for swipe gestures, shared elements or transitions defined declaratively outside
 * UI code.
 *
 * @param state the state of this layout.
 * @param swipeSourceDetector the edge detector used to detect which edge a swipe is started from,
 *   if any.
 * @param transitionInterceptionThreshold used during a scene transition. For the scene to be
 *   intercepted, the progress value must be above the threshold, and below (1 - threshold).
 * @param builder the configuration of the different scenes and overlays of this layout.
 */
@Composable
fun SceneTransitionLayout(
    state: SceneTransitionLayoutState,
    modifier: Modifier = Modifier,
    swipeSourceDetector: SwipeSourceDetector = DefaultEdgeDetector,
    swipeDetector: SwipeDetector = DefaultSwipeDetector,
    @FloatRange(from = 0.0, to = 0.5) transitionInterceptionThreshold: Float = 0.05f,
    // TODO(b/240432457) Remove this once test utils can access the internal STLForTesting().
    implicitTestTags: Boolean = false,
    builder: SceneTransitionLayoutScope<ContentScope>.() -> Unit,
) {
    SceneTransitionLayoutForTesting(
        state,
        modifier,
        swipeSourceDetector,
        swipeDetector,
        transitionInterceptionThreshold,
        implicitTestTags = implicitTestTags,
        onLayoutImpl = null,
        builder = builder,
    )
}

interface SceneTransitionLayoutScope<out CS : ContentScope> {
    /**
     * Add a scene to this layout, identified by [key].
     *
     * You can configure [userActions] so that swiping on this layout or navigating back will
     * transition to a different scene.
     *
     * By default, [verticalOverscrollEffect][ContentScope.verticalOverscrollEffect] and
     * [horizontalOverscrollEffect][ContentScope.horizontalOverscrollEffect] of this scene will be
     * created using [LocalOverscrollFactory]. You can specify a non-null [effectFactory] to set up
     * a custom factory that will be used by this scene and by any calls to
     * rememberOverscrollEffect() inside the scene.
     *
     * Important: scene order along the z-axis follows call order. Calling scene(A) followed by
     * scene(B) will mean that scene B renders after/above scene A.
     */
    fun scene(
        key: SceneKey,
        userActions: Map<UserAction, UserActionResult> = emptyMap(),
        effectFactory: OverscrollFactory? = null,
        alwaysCompose: Boolean = false,
        content: @Composable CS.() -> Unit,
    )

    /**
     * Add an overlay to this layout, identified by [key].
     *
     * Overlays are displayed above scenes and can be toggled using
     * [MutableSceneTransitionLayoutState.showOverlay] and
     * [MutableSceneTransitionLayoutState.hideOverlay].
     *
     * Overlays will have a maximum size that is the size of the layout without overlays, i.e. an
     * overlay can be fillMaxSize() to match the layout size but it won't make the layout bigger.
     *
     * By default overlays are centered in their layout but they can be aligned differently using
     * [alignment].
     *
     * If [isModal] is true (the default), then a protective layer will be added behind the overlay
     * to prevent swipes from reaching other scenes or overlays behind this one. Clicking this
     * protective layer will close the overlay.
     *
     * By default, [verticalOverscrollEffect][ContentScope.verticalOverscrollEffect] and
     * [horizontalOverscrollEffect][ContentScope.horizontalOverscrollEffect] of this overlay will be
     * created using [LocalOverscrollFactory]. You can specify a non-null [effectFactory] to set up
     * a custom factory that will be used by this content and by any calls to
     * rememberOverscrollEffect() inside the content.
     *
     * Important: overlays must be defined after all scenes. Overlay order along the z-axis follows
     * call order. Calling overlay(A) followed by overlay(B) will mean that overlay B renders
     * after/above overlay A.
     */
    fun overlay(
        key: OverlayKey,
        userActions: Map<UserAction, UserActionResult> =
            mapOf(Back to UserActionResult.HideOverlay(key)),
        alignment: Alignment = Alignment.Center,
        isModal: Boolean = true,
        effectFactory: OverscrollFactory? = null,
        content: @Composable CS.() -> Unit,
    )
}

/**
 * A DSL marker to prevent people from nesting calls to Modifier.element() inside a MovableElement,
 * which is not supported.
 */
@DslMarker annotation class ElementDsl

/** A scope that can be used to query the target state of an element or scene. */
interface ElementStateScope {
    /**
     * Return the *target* size of [this] element in the given [content], i.e. the size of the
     * element when idle, or `null` if the element is not composed and measured in that content
     * (yet).
     */
    fun ElementKey.targetSize(content: ContentKey): IntSize?

    /**
     * Return the *approaching* size of [this] element in the given [content], i.e. thethe size the
     * element when is transitioning, or `null` if the element is not composed and measured in that
     * content (yet).
     */
    fun ElementKey.approachSize(content: ContentKey): IntSize?

    /**
     * Return the *target* offset of [this] element in the given [content], i.e. the size of the
     * element when idle, or `null` if the element is not composed and placed in that content (yet).
     */
    fun ElementKey.targetOffset(content: ContentKey): Offset?

    /**
     * Return the *target* size of [this] content, i.e. the size of the content when idle, or `null`
     * if the content was not composed (yet).
     */
    fun ContentKey.targetSize(): IntSize?
}

@Stable
@ElementDsl
interface BaseContentScope : ElementStateScope {
    /** The key of this content. */
    val contentKey: ContentKey

    /** The state of the [SceneTransitionLayout] in which this content is contained. */
    val layoutState: SceneTransitionLayoutState

    /** The [LookaheadScope] used by the [SceneTransitionLayout]. */
    val lookaheadScope: LookaheadScope

    /**
     * Tag an element identified by [key].
     *
     * Tagging an element will allow you to reference that element when defining transitions, so
     * that the element can be transformed and animated when the content transitions in or out.
     *
     * Additionally, this [key] will be used to detect elements that are shared between contents to
     * automatically interpolate their size and offset. If you need to animate shared element values
     * (i.e. values associated to this element that change depending on which content it is composed
     * in), use [ElementWithValues] instead.
     *
     * Note that shared elements tagged using this function will be duplicated in each content they
     * are part of, so any **internal** state (e.g. state created using `remember {
     * mutableStateOf(...) }`) will be lost. If you need to preserve internal state, you should use
     * [MovableElement] instead.
     *
     * @see Element
     * @see ElementWithValues
     * @see MovableElement
     */
    // TODO(b/389985793): Does replacing this by Element have a noticeable impact on performance and
    // should we deprecate it?
    @Stable fun Modifier.element(key: ElementKey): Modifier

    /**
     * Create an element identified by [key].
     *
     * Similar to [element], this creates an element that will be automatically shared when present
     * in multiple contents and that can be transformed during transitions, the same way that
     * [element] does.
     *
     * The only difference with [element] is that [Element] introduces its own recomposition scope
     * and layout node, which can be helpful to avoid expensive recompositions when a transition is
     * started or finished (see b/389985793#comment103 for details).
     *
     * @see element
     * @see ElementWithValues
     * @see MovableElement
     */
    @Composable
    fun Element(key: ElementKey, modifier: Modifier, content: @Composable BoxScope.() -> Unit)

    /**
     * Create an element identified by [key].
     *
     * The only difference with [Element] is that the provided [ElementScope] allows you to
     * [animate element values][ElementScope.animateElementValueAsState].
     *
     * @see element
     * @see Element
     * @see MovableElement
     */
    @Composable
    fun ElementWithValues(
        key: ElementKey,
        modifier: Modifier,

        // TODO(b/317026105): As discussed in http://shortn/_gJVdltF8Si, remove the @Composable
        // scope here to make sure that callers specify the content in ElementScope.content {} or
        // ElementScope.movableContent {}.
        content: @Composable ElementScope<ElementContentScope>.() -> Unit,
    )

    /**
     * Create a *movable* element identified by [key].
     *
     * Similar to [ElementWithValues], this creates an element that will be automatically shared
     * when present in multiple contents and that can be transformed during transitions, and you can
     * also use the provided [ElementScope] to
     * [animate element values][ElementScope.animateElementValueAsState].
     *
     * The important difference with [element], [Element] and [ElementWithValues] is that this
     * element [content][ElementScope.content] will be "moved" and composed only once during
     * transitions, as opposed to [element], [Element] and [ElementWithValues] that duplicates
     * shared elements, so that any internal state is preserved during and after the transition.
     *
     * @see element
     * @see Element
     * @see ElementWithValues
     */
    @Composable
    fun MovableElement(
        key: MovableElementKey,
        modifier: Modifier,

        // TODO(b/317026105): As discussed in http://shortn/_gJVdltF8Si, remove the @Composable
        // scope here to make sure that callers specify the content in ElementScope.content {} or
        // ElementScope.movableContent {}.
        content: @Composable ElementScope<MovableElementContentScope>.() -> Unit,
    )

    /**
     * Don't resize during transitions. This can for instance be used to make sure that scrollable
     * lists keep a constant size during transitions even if its elements are growing/shrinking.
     */
    fun Modifier.noResizeDuringTransitions(): Modifier

    /**
     * Temporarily disable this content swipe actions when any scrollable below this modifier has
     * consumed any amount of scroll delta, until the scroll gesture is finished.
     *
     * This can for instance be used to ensure that a scrollable list is overscrolled once it
     * reached its bounds instead of directly starting a scene transition from the same scroll
     * gesture.
     */
    fun Modifier.disableSwipesWhenScrolling(
        bounds: NestedScrollableBound = NestedScrollableBound.Any
    ): Modifier
}

@Stable
@ElementDsl
interface ContentScope : BaseContentScope {
    /**
     * The overscroll effect applied to the content in the vertical direction. This can be used to
     * customize how the content behaves when the scene is over scrolled.
     *
     * You should use this effect exactly once with the `Modifier.overscroll()` modifier:
     * ```kotlin
     * @Composable
     * fun ContentScope.MyScene() {
     *     Box(
     *         modifier = Modifier
     *             // Apply the effect
     *             .overscroll(verticalOverscrollEffect)
     *     ) {
     *         // ... your content ...
     *     }
     * }
     * ```
     *
     * @see horizontalOverscrollEffect
     */
    val verticalOverscrollEffect: OverscrollEffect

    /**
     * The overscroll effect applied to the content in the horizontal direction. This can be used to
     * customize how the content behaves when the scene is over scrolled.
     *
     * @see verticalOverscrollEffect
     */
    val horizontalOverscrollEffect: OverscrollEffect

    /**
     * Animate some value at the content level.
     *
     * @param value the value of this shared value in the current content.
     * @param key the key of this shared value.
     * @param type the [SharedValueType] of this animated value.
     * @param canOverflow whether this value can overflow past the values it is interpolated
     *   between, for instance because the transition is animated using a bouncy spring.
     * @see animateContentIntAsState
     * @see animateContentFloatAsState
     * @see animateContentDpAsState
     * @see animateContentColorAsState
     */
    @Composable
    fun <T> animateContentValueAsState(
        value: T,
        key: ValueKey,
        type: SharedValueType<T, *>,
        canOverflow: Boolean,
    ): AnimatedState<T>

    /**
     * A [NestedSceneTransitionLayout] will share its elements with its ancestor STLs therefore
     * enabling sharedElement transitions between them.
     */
    // TODO(b/380070506): Add more parameters when default params are supported in Kotlin 2.0.21
    @Composable
    fun NestedSceneTransitionLayout(
        state: SceneTransitionLayoutState,
        modifier: Modifier,
        builder: SceneTransitionLayoutScope<ContentScope>.() -> Unit,
    )
}

internal interface InternalContentScope : ContentScope {

    @Composable
    fun NestedSceneTransitionLayoutForTesting(
        state: SceneTransitionLayoutState,
        modifier: Modifier,
        onLayoutImpl: ((SceneTransitionLayoutImpl) -> Unit)?,
        builder: SceneTransitionLayoutScope<InternalContentScope>.() -> Unit,
    )
}

/**
 * The type of a shared value animated using [ElementScope.animateElementValueAsState] or
 * [ContentScope.animateContentValueAsState].
 */
@Stable
interface SharedValueType<T, Delta> {
    /** The unspecified value for this type. */
    val unspecifiedValue: T

    /**
     * The zero value of this type. It should be equal to what [diff(x, x)] returns for any value of
     * x.
     */
    val zeroDeltaValue: Delta

    /**
     * Return the linear interpolation of [a] and [b] at the given [progress], i.e. `a + (b - a) *
     * progress`.
     */
    fun lerp(a: T, b: T, progress: Float): T

    /** Return `a - b`. */
    fun diff(a: T, b: T): Delta

    /** Return `a + b * bWeight`. */
    fun addWeighted(a: T, b: Delta, bWeight: Float): T
}

@Stable
@ElementDsl
interface ElementScope<ContentScope> {
    /**
     * Animate some value associated to this element.
     *
     * @param value the value of this shared value in the current content.
     * @param key the key of this shared value.
     * @param type the [SharedValueType] of this animated value.
     * @param canOverflow whether this value can overflow past the values it is interpolated
     *   between, for instance because the transition is animated using a bouncy spring.
     * @see animateElementIntAsState
     * @see animateElementFloatAsState
     * @see animateElementDpAsState
     * @see animateElementColorAsState
     */
    @Composable
    fun <T> animateElementValueAsState(
        value: T,
        key: ValueKey,
        type: SharedValueType<T, *>,
        canOverflow: Boolean,
    ): AnimatedState<T>

    /**
     * The content of this element.
     *
     * Important: This must be called exactly once, after all calls to [animateElementValueAsState].
     */
    @Composable fun content(content: @Composable ContentScope.() -> Unit)
}

/**
 * The exact same scope as [androidx.compose.foundation.layout.BoxScope].
 *
 * We can't reuse BoxScope directly because of the @LayoutScopeMarker annotation on it, which would
 * prevent us from calling Modifier.element() and other methods of [ContentScope] inside any Box {}
 * in the [content][ElementScope.content] of a [ContentScope.ElementWithValues] or a
 * [ContentScope.MovableElement].
 */
@Stable
@ElementDsl
interface ElementBoxScope {
    /** @see [androidx.compose.foundation.layout.BoxScope.align]. */
    @Stable fun Modifier.align(alignment: Alignment): Modifier

    /** @see [androidx.compose.foundation.layout.BoxScope.matchParentSize]. */
    @Stable fun Modifier.matchParentSize(): Modifier
}

/** The scope for "normal" (not movable) elements. */
@Stable @ElementDsl interface ElementContentScope : ContentScope, ElementBoxScope

/**
 * The scope for the content of movable elements.
 *
 * Note that it extends [BaseContentScope] and not [ContentScope] because movable elements should
 * not call [ContentScope.animateContentValueAsState], given that their content is not composed in
 * all scenes.
 */
@Stable @ElementDsl interface MovableElementContentScope : BaseContentScope, ElementBoxScope

/** An action performed by the user. */
sealed class UserAction {
    infix fun to(scene: SceneKey): Pair<UserAction, UserActionResult> {
        return this to UserActionResult(toScene = scene)
    }

    infix fun to(overlay: OverlayKey): Pair<UserAction, UserActionResult> {
        return this to UserActionResult.ShowOverlay(overlay)
    }

    /** Resolve this into a [Resolved] user action given [layoutDirection]. */
    internal abstract fun resolve(layoutDirection: LayoutDirection): Resolved

    /** A resolved [UserAction] that does not depend on the layout direction. */
    internal sealed class Resolved
}

/** The user navigated back, either using a gesture or by triggering a KEYCODE_BACK event. */
data object Back : UserAction() {
    override fun resolve(layoutDirection: LayoutDirection): Resolved = Resolved

    internal object Resolved : UserAction.Resolved()
}

/** The user swiped on the container. */
data class Swipe
private constructor(
    val direction: SwipeDirection,
    val pointerCount: Int = 1,
    val pointerType: PointerType? = null,
    val fromSource: SwipeSource? = null,
) : UserAction() {
    companion object {
        val Left = Swipe(SwipeDirection.Left)
        val Up = Swipe(SwipeDirection.Up)
        val Right = Swipe(SwipeDirection.Right)
        val Down = Swipe(SwipeDirection.Down)
        val Start = Swipe(SwipeDirection.Start)
        val End = Swipe(SwipeDirection.End)

        fun Left(
            pointerCount: Int = 1,
            pointerType: PointerType? = null,
            fromSource: SwipeSource? = null,
        ) = Swipe(SwipeDirection.Left, pointerCount, pointerType, fromSource)

        fun Up(
            pointerCount: Int = 1,
            pointerType: PointerType? = null,
            fromSource: SwipeSource? = null,
        ) = Swipe(SwipeDirection.Up, pointerCount, pointerType, fromSource)

        fun Right(
            pointerCount: Int = 1,
            pointerType: PointerType? = null,
            fromSource: SwipeSource? = null,
        ) = Swipe(SwipeDirection.Right, pointerCount, pointerType, fromSource)

        fun Down(
            pointerCount: Int = 1,
            pointerType: PointerType? = null,
            fromSource: SwipeSource? = null,
        ) = Swipe(SwipeDirection.Down, pointerCount, pointerType, fromSource)

        fun Start(
            pointerCount: Int = 1,
            pointerType: PointerType? = null,
            fromSource: SwipeSource? = null,
        ) = Swipe(SwipeDirection.Start, pointerCount, pointerType, fromSource)

        fun End(
            pointerCount: Int = 1,
            pointerType: PointerType? = null,
            fromSource: SwipeSource? = null,
        ) = Swipe(SwipeDirection.End, pointerCount, pointerType, fromSource)
    }

    override fun resolve(layoutDirection: LayoutDirection): UserAction.Resolved {
        return Resolved(
            direction = direction.resolve(layoutDirection),
            pointerCount = pointerCount,
            pointerType = pointerType,
            fromSource = fromSource?.resolve(layoutDirection),
        )
    }

    /** A resolved [Swipe] that does not depend on the layout direction. */
    internal data class Resolved(
        val direction: SwipeDirection.Resolved,
        val pointerCount: Int,
        val fromSource: SwipeSource.Resolved?,
        val pointerType: PointerType?,
    ) : UserAction.Resolved()
}

enum class SwipeDirection(internal val resolve: (LayoutDirection) -> Resolved) {
    Up(resolve = { Resolved.Up }),
    Down(resolve = { Resolved.Down }),
    Left(resolve = { Resolved.Left }),
    Right(resolve = { Resolved.Right }),
    Start(resolve = { if (it == LayoutDirection.Ltr) Resolved.Left else Resolved.Right }),
    End(resolve = { if (it == LayoutDirection.Ltr) Resolved.Right else Resolved.Left });

    /** A resolved [SwipeDirection] that does not depend on the layout direction. */
    internal enum class Resolved(val orientation: Orientation) {
        Up(Orientation.Vertical),
        Down(Orientation.Vertical),
        Left(Orientation.Horizontal),
        Right(Orientation.Horizontal),
    }
}

/**
 * The source of a Swipe.
 *
 * Important: This can be anything that can be returned by any [SwipeSourceDetector], but this must
 * implement [equals] and [hashCode]. Note that those can be trivially implemented using data
 * classes.
 */
interface SwipeSource {
    // Require equals() and hashCode() to be implemented.
    override fun equals(other: Any?): Boolean

    override fun hashCode(): Int

    /** Resolve this into a [Resolved] swipe source given [layoutDirection]. */
    fun resolve(layoutDirection: LayoutDirection): Resolved

    /** A resolved [SwipeSource] that does not depend on the layout direction. */
    interface Resolved {
        override fun equals(other: Any?): Boolean

        override fun hashCode(): Int
    }
}

interface SwipeSourceDetector {
    /**
     * Return the [SwipeSource] associated to [position] inside a layout of size [layoutSize], given
     * [density] and [orientation].
     */
    fun source(
        layoutSize: IntSize,
        position: IntOffset,
        density: Density,
        orientation: Orientation,
    ): SwipeSource.Resolved?
}

/** The result of performing a [UserAction]. */
sealed class UserActionResult(
    /** The key of the transition that should be used. */
    open val transitionKey: TransitionKey? = null,

    /**
     * If `true`, the swipe will be committed and we will settle to [toScene] if only if the user
     * swiped at least the swipe distance, i.e. the transition progress was already equal to or
     * bigger than 100% when the user released their finger. `
     */
    open val requiresFullDistanceSwipe: Boolean,
) {
    internal abstract fun toContent(currentScene: SceneKey): ContentKey

    data class ChangeScene
    internal constructor(
        /** The scene we should be transitioning to during the [UserAction]. */
        val toScene: SceneKey,
        override val transitionKey: TransitionKey? = null,
        override val requiresFullDistanceSwipe: Boolean = false,
    ) : UserActionResult(transitionKey, requiresFullDistanceSwipe) {
        override fun toContent(currentScene: SceneKey): ContentKey = toScene
    }

    /** A [UserActionResult] that shows [overlay]. */
    data class ShowOverlay(
        val overlay: OverlayKey,
        override val transitionKey: TransitionKey? = null,
        override val requiresFullDistanceSwipe: Boolean = false,

        /** Specify which overlays (if any) should be hidden when this user action is started. */
        val hideCurrentOverlays: HideCurrentOverlays = HideCurrentOverlays.None,
    ) : UserActionResult(transitionKey, requiresFullDistanceSwipe) {
        override fun toContent(currentScene: SceneKey): ContentKey = overlay

        sealed class HideCurrentOverlays {
            /** Hide none of the current overlays. */
            object None : HideCurrentOverlays()

            /** Hide all current overlays. */
            object All : HideCurrentOverlays()

            /** Hide [overlays], for those in that set that are currently shown. */
            class Some(val overlays: Set<OverlayKey>) : HideCurrentOverlays() {
                constructor(vararg overlays: OverlayKey) : this(overlays.toSet())
            }
        }
    }

    /** A [UserActionResult] that hides [overlay]. */
    data class HideOverlay(
        val overlay: OverlayKey,
        override val transitionKey: TransitionKey? = null,
        override val requiresFullDistanceSwipe: Boolean = false,
    ) : UserActionResult(transitionKey, requiresFullDistanceSwipe) {
        override fun toContent(currentScene: SceneKey): ContentKey = currentScene
    }

    /**
     * A [UserActionResult] that replaces the current overlay by [overlay].
     *
     * Note: This result can only be used for user actions of overlays and an exception will be
     * thrown if it is used for a scene.
     */
    data class ReplaceByOverlay(
        val overlay: OverlayKey,
        override val transitionKey: TransitionKey? = null,
        override val requiresFullDistanceSwipe: Boolean = false,
    ) : UserActionResult(transitionKey, requiresFullDistanceSwipe) {
        override fun toContent(currentScene: SceneKey): ContentKey = overlay
    }

    companion object {
        /** A [UserActionResult] that changes the current scene to [toScene]. */
        operator fun invoke(
            /** The scene we should be transitioning to during the [UserAction]. */
            toScene: SceneKey,

            /** The key of the transition that should be used. */
            transitionKey: TransitionKey? = null,

            /**
             * If `true`, the swipe will be committed if only if the user swiped at least the swipe
             * distance, i.e. the transition progress was already equal to or bigger than 100% when
             * the user released their finger.
             */
            requiresFullDistanceSwipe: Boolean = false,
        ): UserActionResult = ChangeScene(toScene, transitionKey, requiresFullDistanceSwipe)
    }
}

fun interface UserActionDistance {
    /**
     * Return the **absolute** distance of the user action when going from [fromContent] to
     * [toContent] in the given [orientation].
     *
     * Note: This function will be called for each drag event until it returns a value > 0f. This
     * for instance allows you to return 0f or a negative value until the first layout pass of a
     * scene, so that you can use the size and position of elements in the scene we are
     * transitioning to when computing this absolute distance.
     */
    fun UserActionDistanceScope.absoluteDistance(
        fromContent: ContentKey,
        toContent: ContentKey,
        orientation: Orientation,
    ): Float
}

interface UserActionDistanceScope : Density, ElementStateScope

/** The user action has a fixed [absoluteDistance]. */
class FixedDistance(private val distance: Dp) : UserActionDistance {
    override fun UserActionDistanceScope.absoluteDistance(
        fromContent: ContentKey,
        toContent: ContentKey,
        orientation: Orientation,
    ): Float = distance.toPx()
}

/**
 * An internal version of [SceneTransitionLayout] to be used for tests, that provides access to the
 * internal [SceneTransitionLayoutImpl] and implicitly tags all scenes and elements.
 */
@Composable
internal fun SceneTransitionLayoutForTesting(
    state: SceneTransitionLayoutState,
    modifier: Modifier = Modifier,
    swipeSourceDetector: SwipeSourceDetector = DefaultEdgeDetector,
    swipeDetector: SwipeDetector = DefaultSwipeDetector,
    transitionInterceptionThreshold: Float = 0f,
    onLayoutImpl: ((SceneTransitionLayoutImpl) -> Unit)? = null,
    sharedElementMap: MutableMap<ElementKey, Element> = remember { mutableMapOf() },
    ancestors: List<Ancestor> = remember { emptyList() },
    lookaheadScope: LookaheadScope? = null,
    implicitTestTags: Boolean = true,
    builder: SceneTransitionLayoutScope<InternalContentScope>.() -> Unit,
) {
    val density = LocalDensity.current
    val directionChangeSlop = LocalViewConfiguration.current.touchSlop
    val layoutDirection = LocalLayoutDirection.current
    val defaultEffectFactory = checkNotNull(LocalOverscrollFactory.current)
    val animationScope = rememberCoroutineScope()
    val decayAnimationSpec = rememberSplineBasedDecay<Float>()
    val layoutImpl = remember {
        SceneTransitionLayoutImpl(
                state = state as MutableSceneTransitionLayoutStateImpl,
                density = density,
                layoutDirection = layoutDirection,
                swipeSourceDetector = swipeSourceDetector,
                swipeDetector = swipeDetector,
                transitionInterceptionThreshold = transitionInterceptionThreshold,
                builder = builder,
                animationScope = animationScope,
                elements = sharedElementMap,
                ancestors = ancestors,
                lookaheadScope = lookaheadScope,
                directionChangeSlop = directionChangeSlop,
                defaultEffectFactory = defaultEffectFactory,
                decayAnimationSpec = decayAnimationSpec,
                implicitTestTags = implicitTestTags,
            )
            .also { onLayoutImpl?.invoke(it) }
    }

    // TODO(b/317014852): Move this into the SideEffect {} again once STLImpl.scenes is not a
    // SnapshotStateMap anymore.
    layoutImpl.updateContents(builder, layoutDirection, defaultEffectFactory)

    SideEffect {
        if (state != layoutImpl.state) {
            error(
                "This SceneTransitionLayout was bound to a different SceneTransitionLayoutState" +
                    " that was used when creating it, which is not supported"
            )
        }
        if (layoutImpl.elements != sharedElementMap) {
            error(
                "This SceneTransitionLayout was bound to a different elements map that was used " +
                    "when creating it, which is not supported"
            )
        }
        if (layoutImpl.ancestors != ancestors) {
            error(
                "This SceneTransitionLayout was bound to a different ancestors that was " +
                    "used when creating it, which is not supported"
            )
        }
        if (lookaheadScope != null && layoutImpl.lookaheadScope != lookaheadScope) {
            error(
                "This SceneTransitionLayout was bound to a different lookaheadScope that was " +
                    "used when creating it, which is not supported"
            )
        }

        layoutImpl.density = density
        layoutImpl.layoutDirection = layoutDirection
        layoutImpl.swipeSourceDetector = swipeSourceDetector
        layoutImpl.swipeDetector = swipeDetector
        layoutImpl.transitionInterceptionThreshold = transitionInterceptionThreshold
        layoutImpl.decayAnimationSpec = decayAnimationSpec
    }

    layoutImpl.Content(modifier)
}
