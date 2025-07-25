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

package com.android.compose.ui.graphics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.modifier.ModifierLocalModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastForEach

/**
 * Define this as a container into which other composables can be drawn using [drawInContainer].
 *
 * The elements redirected to this container will be drawn above the content of this composable.
 */
fun Modifier.container(state: ContainerState): Modifier {
    return this then ContainerElement(state)
}

/**
 * Draw this composable into the container associated to [state].
 *
 * @param state the state of the container into which we should draw this composable.
 * @param enabled whether the redirection of the drawing to the container is enabled.
 * @param zIndex the z-index of the composable in the container.
 * @param clipPath the clip path applied when drawing this composable into the container.
 */
fun Modifier.drawInContainer(
    state: ContainerState,
    enabled: () -> Boolean = { true },
    zIndex: Float = 0f,
    clipPath: (LayoutDirection, Density) -> Path? = { _, _ -> null },
): Modifier {
    return this.then(
        DrawInContainerElement(
            state = state,
            enabled = enabled,
            zIndex = zIndex,
            clipPath = clipPath,
        )
    )
}

class ContainerState {
    private var renderers = mutableStateListOf<LayerRenderer>()
    internal var lastOffsetInWindow by mutableStateOf(Offset.Zero)

    internal fun onLayerRendererAttached(renderer: LayerRenderer) {
        renderers.add(renderer)
        renderers.sortBy { it.zIndex }
    }

    internal fun onLayerRendererDetached(renderer: LayerRenderer) {
        renderers.remove(renderer)
    }

    internal fun drawInOverlay(drawScope: DrawScope) {
        renderers.fastForEach { it.drawInOverlay(drawScope) }
    }
}

internal interface LayerRenderer {
    val zIndex: Float

    fun drawInOverlay(drawScope: DrawScope)
}

private data class ContainerElement(private val state: ContainerState) :
    ModifierNodeElement<ContainerNode>() {
    override fun create(): ContainerNode {
        return ContainerNode(state)
    }

    override fun update(node: ContainerNode) {
        node.state = state
    }
}

/** A node implementing [container] that can be delegated to. */
class ContainerNode(var state: ContainerState) :
    Modifier.Node(), LayoutAwareModifierNode, DrawModifierNode {
    override fun onPlaced(coordinates: LayoutCoordinates) {
        state.lastOffsetInWindow = coordinates.positionInWindow()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        state.drawInOverlay(this)
    }
}

private data class DrawInContainerElement(
    var state: ContainerState,
    var enabled: () -> Boolean,
    val zIndex: Float,
    val clipPath: (LayoutDirection, Density) -> Path?,
) : ModifierNodeElement<DrawInContainerNode>() {
    override fun create(): DrawInContainerNode {
        return DrawInContainerNode(state, enabled, zIndex, clipPath)
    }

    override fun update(node: DrawInContainerNode) {
        node.state = state
        node.enabled = enabled
        node.zIndex = zIndex
        node.clipPath = clipPath
    }
}

/**
 * The implementation of [drawInContainer].
 *
 * Note: this was forked from AndroidX RenderInTransitionOverlayNodeElement.kt
 * (http://shortn/_3dfSFPbm8f).
 */
internal class DrawInContainerNode(
    var state: ContainerState,
    var enabled: () -> Boolean = { true },
    zIndex: Float = 0f,
    var clipPath: (LayoutDirection, Density) -> Path? = { _, _ -> null },
) : Modifier.Node(), LayoutAwareModifierNode, DrawModifierNode, ModifierLocalModifierNode {
    private var lastOffsetInWindow by mutableStateOf(Offset.Zero)
    var zIndex by mutableFloatStateOf(zIndex)

    private inner class LayerWithRenderer(val layer: GraphicsLayer) : LayerRenderer {
        override val zIndex: Float
            get() = this@DrawInContainerNode.zIndex

        override fun drawInOverlay(drawScope: DrawScope) {
            if (enabled()) {
                with(drawScope) {
                    val (x, y) = lastOffsetInWindow - state.lastOffsetInWindow
                    val clipPath = clipPath(layoutDirection, requireDensity())
                    if (clipPath != null) {
                        clipPath(clipPath) { translate(x, y) { drawLayer(layer) } }
                    } else {
                        translate(x, y) { drawLayer(layer) }
                    }
                }
            }
        }
    }

    // Render in-place logic. Depending on the result of `renderInOverlay()`, the content will
    // either render in-place or in the overlay, but never in both places.
    override fun ContentDrawScope.draw() {
        val layer = requireNotNull(layer) { "Error: layer never initialized" }
        layer.record { this@draw.drawContent() }
        if (!enabled()) {
            drawLayer(layer)
        }
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        lastOffsetInWindow = coordinates.positionInWindow()
    }

    val layer: GraphicsLayer?
        get() = layerWithRenderer?.layer

    private var layerWithRenderer: LayerWithRenderer? = null

    override fun onAttach() {
        LayerWithRenderer(requireGraphicsContext().createGraphicsLayer()).let {
            state.onLayerRendererAttached(it)
            layerWithRenderer = it
        }
    }

    override fun onDetach() {
        layerWithRenderer?.let {
            state.onLayerRendererDetached(it)
            requireGraphicsContext().releaseGraphicsLayer(it.layer)
        }
    }
}
