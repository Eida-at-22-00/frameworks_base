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
package com.android.wm.shell.windowdecor.tiling

import android.content.Context
import android.content.res.Resources
import android.graphics.Rect
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.desktopmode.DesktopModeEventLogger
import com.android.wm.shell.desktopmode.DesktopRepository
import com.android.wm.shell.desktopmode.DesktopTasksController
import com.android.wm.shell.desktopmode.DesktopTestHelpers.createFreeformTask
import com.android.wm.shell.desktopmode.DesktopUserRepositories
import com.android.wm.shell.desktopmode.ReturnToDragStartAnimator
import com.android.wm.shell.desktopmode.ToggleResizeDesktopTaskTransitionHandler
import com.android.wm.shell.transition.FocusTransitionObserver
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.windowdecor.DesktopModeWindowDecoration
import com.android.wm.shell.windowdecor.common.WindowDecorTaskResourceLoader
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainCoroutineDispatcher
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class DesktopTilingDecorViewModelTest : ShellTestCase() {
    private val contextMock: Context = mock()
    private val resourcesMock: Resources = mock()
    private val mainDispatcher: MainCoroutineDispatcher = mock()
    private val bgScope: CoroutineScope = mock()
    private val displayControllerMock: DisplayController = mock()
    private val rootTdaOrganizerMock: RootTaskDisplayAreaOrganizer = mock()
    private val syncQueueMock: SyncTransactionQueue = mock()
    private val transitionsMock: Transitions = mock()
    private val shellTaskOrganizerMock: ShellTaskOrganizer = mock()
    private val userRepositories: DesktopUserRepositories = mock()
    private val desktopRepository: DesktopRepository = mock()
    private val desktopModeEventLogger: DesktopModeEventLogger = mock()
    private val toggleResizeDesktopTaskTransitionHandlerMock:
        ToggleResizeDesktopTaskTransitionHandler =
        mock()
    private val returnToDragStartAnimatorMock: ReturnToDragStartAnimator = mock()

    private val desktopModeWindowDecorationMock: DesktopModeWindowDecoration = mock()
    private val desktopTilingDecoration: DesktopTilingWindowDecoration = mock()
    private val taskResourceLoader: WindowDecorTaskResourceLoader = mock()
    private val focusTransitionObserver: FocusTransitionObserver = mock()
    private val displayLayout: DisplayLayout = mock()
    private val mainExecutor: ShellExecutor = mock()
    private lateinit var desktopTilingDecorViewModel: DesktopTilingDecorViewModel

    @Before
    fun setUp() {
        desktopTilingDecorViewModel =
            DesktopTilingDecorViewModel(
                contextMock,
                mainDispatcher,
                bgScope,
                displayControllerMock,
                rootTdaOrganizerMock,
                syncQueueMock,
                transitionsMock,
                shellTaskOrganizerMock,
                toggleResizeDesktopTaskTransitionHandlerMock,
                returnToDragStartAnimatorMock,
                userRepositories,
                desktopModeEventLogger,
                taskResourceLoader,
                focusTransitionObserver,
                mainExecutor,
            )
        whenever(contextMock.createContextAsUser(any(), any())).thenReturn(contextMock)
        whenever(displayControllerMock.getDisplayLayout(any())).thenReturn(displayLayout)
        whenever(displayLayout.getStableBounds(any())).thenAnswer { i ->
            (i.arguments.first() as Rect).set(STABLE_BOUNDS)
        }
        whenever(contextMock.createContextAsUser(any(), any())).thenReturn(context)
        whenever(contextMock.resources).thenReturn(resourcesMock)
        whenever(resourcesMock.getDimensionPixelSize(any())).thenReturn(10)
        whenever(userRepositories.current).thenReturn(desktopRepository)
    }

    @Test
    fun testTiling_shouldCreate_newTilingDecoration() {
        val task1 = createFreeformTask()
        val task2 = createFreeformTask()
        task1.displayId = 1
        task2.displayId = 2

        desktopTilingDecorViewModel.snapToHalfScreen(
            task1,
            desktopModeWindowDecorationMock,
            DesktopTasksController.SnapPosition.LEFT,
            BOUNDS,
        )
        assertThat(desktopTilingDecorViewModel.tilingTransitionHandlerByDisplayId.size())
            .isEqualTo(1)
        desktopTilingDecorViewModel.snapToHalfScreen(
            task2,
            desktopModeWindowDecorationMock,
            DesktopTasksController.SnapPosition.LEFT,
            BOUNDS,
        )
        assertThat(desktopTilingDecorViewModel.tilingTransitionHandlerByDisplayId.size())
            .isEqualTo(2)
    }

    @Test
    fun removeTile_shouldCreate_newTilingDecoration() {
        val task1 = createFreeformTask()
        task1.displayId = 1
        desktopTilingDecorViewModel.tilingTransitionHandlerByDisplayId.put(
            1,
            desktopTilingDecoration,
        )
        desktopTilingDecorViewModel.removeTaskIfTiled(task1.displayId, task1.taskId)

        verify(desktopTilingDecoration, times(1)).removeTaskIfTiled(any(), any(), any())
    }

    @Test
    fun moveTaskToFront_shouldRoute_toCorrectTilingDecoration() {

        val task1 = createFreeformTask()
        task1.displayId = 1
        desktopTilingDecorViewModel.tilingTransitionHandlerByDisplayId.put(
            1,
            desktopTilingDecoration,
        )
        desktopTilingDecorViewModel.moveTaskToFrontIfTiled(task1)

        verify(desktopTilingDecoration, times(1))
            .moveTiledPairToFront(any(), isFocusedOnDisplay = eq(true))
    }

    @Test
    fun overviewAnimation_starting_ShouldNotifyAllDecorations() {
        desktopTilingDecorViewModel.tilingTransitionHandlerByDisplayId.put(
            1,
            desktopTilingDecoration,
        )
        desktopTilingDecorViewModel.tilingTransitionHandlerByDisplayId.put(
            2,
            desktopTilingDecoration,
        )
        desktopTilingDecorViewModel.onOverviewAnimationStateChange(true)

        verify(desktopTilingDecoration, times(2)).onOverviewAnimationStateChange(any())
    }

    @Test
    fun onUserChange_allTilingSessionsShouldBeDestroyed() {
        desktopTilingDecorViewModel.tilingTransitionHandlerByDisplayId.put(
            1,
            desktopTilingDecoration,
        )
        desktopTilingDecorViewModel.tilingTransitionHandlerByDisplayId.put(
            2,
            desktopTilingDecoration,
        )

        desktopTilingDecorViewModel.onUserChange()

        verify(desktopTilingDecoration, times(2)).resetTilingSession()
    }

    @Test
    fun displayOrientationChange_tilingForDisplayShouldBeDestroyed() {
        desktopTilingDecorViewModel.tilingTransitionHandlerByDisplayId.put(
            1,
            desktopTilingDecoration,
        )
        desktopTilingDecorViewModel.tilingTransitionHandlerByDisplayId.put(
            2,
            desktopTilingDecoration,
        )

        desktopTilingDecorViewModel.onDisplayChange(1, 1, 2, null, null)

        verify(desktopTilingDecoration, times(1)).resetTilingSession()
        verify(displayControllerMock, times(1))
            .addDisplayChangingController(eq(desktopTilingDecorViewModel))

        desktopTilingDecorViewModel.onDisplayChange(1, 1, 3, null, null)
        // No extra calls after 180 degree change.
        verify(desktopTilingDecoration, times(1)).resetTilingSession()
    }

    @Test
    fun getTiledAppBounds_NoTilingTransitionHandlerObject() {
        // Right bound of the left app here represents default 8 / 2 - 2 ( {Right bound} / 2 -
        // {divider pixel size})
        assertThat(desktopTilingDecorViewModel.getLeftSnapBoundsIfTiled(1))
            .isEqualTo(Rect(6, 7, 2, 9))

        // Left bound of the right app here represents default 8 / 2 + 6 + 2 ( {Left bound} +
        // {width}/ 2 + {divider pixel size})
        assertThat(desktopTilingDecorViewModel.getRightSnapBoundsIfTiled(1))
            .isEqualTo(Rect(12, 7, 8, 9))
    }

    companion object {
        private val BOUNDS = Rect(1, 2, 3, 4)
        private val STABLE_BOUNDS = Rect(6, 7, 8, 9)
    }
}
