/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.theme.PlatformTheme
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.compose.infinitegrid.DefaultEditTileGrid
import com.android.systemui.qs.panels.ui.viewmodel.AvailableEditActions
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class EditModeTest : SysuiTestCase() {
    @get:Rule val composeRule = createComposeRule()

    @Composable
    private fun EditTileGridUnderTest(sizedTiles: List<SizedTile<EditTileViewModel>>) {
        var tiles by remember { mutableStateOf(sizedTiles) }
        val (currentTiles, otherTiles) = tiles.partition { it.tile.isCurrent }
        val listState = EditTileListState(currentTiles, columns = 4, largeTilesSpan = 2)

        PlatformTheme {
            DefaultEditTileGrid(
                listState = listState,
                otherTiles = otherTiles,
                columns = 4,
                largeTilesSpan = 4,
                modifier = Modifier.fillMaxSize(),
                onAddTile = { spec, _ -> tiles = tiles.add(spec) },
                onRemoveTile = { tiles = tiles.remove(it) },
                onSetTiles = {},
                onResize = { _, _ -> },
                onStopEditing = {},
                onReset = null,
            )
        }
    }

    @Test
    fun clickAvailableTile_shouldAdd() {
        composeRule.setContent { EditTileGridUnderTest(TestEditTiles) }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("tileF").performClick() // Tap to add
        composeRule.waitForIdle()

        composeRule.assertCurrentTilesGridContainsExactly(
            listOf("tileA", "tileB", "tileC", "tileD_large", "tileE", "tileF")
        )
        composeRule.assertAvailableTilesGridContainsExactly(
            TestEditTiles.map { it.tile.tileSpec.spec }
        )
    }

    @Test
    fun clickRemoveTarget_shouldRemoveSelection() {
        composeRule.setContent { EditTileGridUnderTest(TestEditTiles) }
        composeRule.waitForIdle()

        // Selects first "tileA", i.e. the one in the current grid
        composeRule.onAllNodesWithText("tileA").onFirst().performClick()
        composeRule.onNodeWithText("Remove").performClick() // Removes

        composeRule.waitForIdle()

        composeRule.assertCurrentTilesGridContainsExactly(
            listOf("tileB", "tileC", "tileD_large", "tileE")
        )
        composeRule.assertAvailableTilesGridContainsExactly(
            TestEditTiles.map { it.tile.tileSpec.spec }
        )
    }

    @Test
    fun selectNonRemovableTile_removeTargetShouldHide() {
        val nonRemovableTile = createEditTile("tileA", isRemovable = false)
        composeRule.setContent { EditTileGridUnderTest(listOf(nonRemovableTile)) }
        composeRule.waitForIdle()

        // Selects first "tileA", i.e. the one in the current grid
        composeRule.onAllNodesWithText("tileA").onFirst().performClick()

        // Assert the remove target isn't shown
        composeRule.onNodeWithText("Remove").assertDoesNotExist()
    }

    @Test
    fun placementMode_shouldRepositionTile() {
        composeRule.setContent { EditTileGridUnderTest(TestEditTiles) }
        composeRule.waitForIdle()

        // Double tap first "tileA", i.e. the one in the current grid
        composeRule.onAllNodesWithText("tileA").onFirst().performTouchInput { doubleClick() }

        // Tap on tileE to position tileA in its spot
        composeRule.onAllNodesWithText("tileE").onFirst().performClick()

        // Assert tileA moved to tileE's position
        composeRule.assertCurrentTilesGridContainsExactly(
            listOf("tileB", "tileC", "tileD_large", "tileE", "tileA")
        )
    }

    private fun ComposeContentTestRule.assertCurrentTilesGridContainsExactly(specs: List<String>) =
        assertGridContainsExactly(CURRENT_TILES_GRID_TEST_TAG, specs)

    private fun ComposeContentTestRule.assertAvailableTilesGridContainsExactly(
        specs: List<String>
    ) = assertGridContainsExactly(AVAILABLE_TILES_GRID_TEST_TAG, specs)

    companion object {
        private const val CURRENT_TILES_GRID_TEST_TAG = "CurrentTilesGrid"
        private const val AVAILABLE_TILES_GRID_TEST_TAG = "AvailableTilesGrid"

        private fun List<SizedTile<EditTileViewModel>>.add(
            spec: TileSpec
        ): List<SizedTile<EditTileViewModel>> {
            return map {
                if (it.tile.tileSpec == spec) {
                    createEditTile(it.tile.tileSpec.spec)
                } else {
                    it
                }
            }
        }

        private fun List<SizedTile<EditTileViewModel>>.remove(
            spec: TileSpec
        ): List<SizedTile<EditTileViewModel>> {
            return map {
                if (it.tile.tileSpec == spec) {
                    createEditTile(it.tile.tileSpec.spec, isCurrent = false)
                } else {
                    it
                }
            }
        }

        private fun createEditTile(
            tileSpec: String,
            isCurrent: Boolean = true,
            isRemovable: Boolean = true,
        ): SizedTile<EditTileViewModel> {
            return SizedTileImpl(
                EditTileViewModel(
                    tileSpec = TileSpec.create(tileSpec),
                    icon =
                        Icon.Resource(
                            android.R.drawable.star_on,
                            ContentDescription.Loaded(tileSpec),
                        ),
                    label = AnnotatedString(tileSpec),
                    appName = null,
                    isCurrent = isCurrent,
                    availableEditActions =
                        if (isRemovable) setOf(AvailableEditActions.REMOVE) else emptySet(),
                    category = TileCategory.UNKNOWN,
                ),
                getWidth(tileSpec),
            )
        }

        private fun getWidth(tileSpec: String): Int {
            return if (tileSpec.endsWith("large")) {
                2
            } else {
                1
            }
        }

        private val TestEditTiles =
            listOf(
                createEditTile("tileA"),
                createEditTile("tileB"),
                createEditTile("tileC"),
                createEditTile("tileD_large"),
                createEditTile("tileE"),
                createEditTile("tileF", isCurrent = false),
                createEditTile("tileG_large", isCurrent = false),
            )
    }
}
