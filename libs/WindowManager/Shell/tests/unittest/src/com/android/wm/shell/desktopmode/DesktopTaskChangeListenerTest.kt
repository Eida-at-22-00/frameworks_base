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

package com.android.wm.shell.desktopmode

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.window.flags.Flags.FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.desktopmode.DesktopTestHelpers.createFreeformTask
import com.android.wm.shell.desktopmode.DesktopTestHelpers.createFullscreenTask
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for {@link DesktopTaskChangeListener}
 *
 * Build/Install/Run: atest WMShellUnitTests:DesktopTaskChangeListenerTest
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class DesktopTaskChangeListenerTest : ShellTestCase() {

    private lateinit var desktopTaskChangeListener: DesktopTaskChangeListener

    private val desktopUserRepositories = mock<DesktopUserRepositories>()
    private val desktopRepository = mock<DesktopRepository>()

    @Before
    fun setUp() {
        desktopTaskChangeListener = DesktopTaskChangeListener(desktopUserRepositories)

        whenever(desktopUserRepositories.current).thenReturn(desktopRepository)
        whenever(desktopUserRepositories.getProfile(anyInt())).thenReturn(desktopRepository)
    }

    @Test
    fun onTaskOpening_fullscreenTask_nonActiveDesktopTask_noop() {
        val task = createFullscreenTask().apply { isVisible = true }
        whenever(desktopUserRepositories.current.isActiveTask(task.taskId)).thenReturn(false)

        desktopTaskChangeListener.onTaskOpening(task)

        verify(desktopUserRepositories.current, never())
            .addTask(task.displayId, task.taskId, task.isVisible)
        verify(desktopUserRepositories.current, never()).removeTask(task.displayId, task.taskId)
    }

    @Test
    fun onTaskOpening_fullscreenTask_taskIsActiveInDesktopRepo_removesTaskFromDesktopRepo() {
        val task = createFullscreenTask().apply { isVisible = true }
        whenever(desktopUserRepositories.current.isActiveTask(task.taskId)).thenReturn(true)

        desktopTaskChangeListener.onTaskOpening(task)

        verify(desktopUserRepositories.current).removeTask(task.displayId, task.taskId)
    }

    @Test
    fun onTaskOpening_freeformTask_activeInDesktopRepository_noop() {
        val task = createFreeformTask().apply { isVisible = true }
        whenever(desktopUserRepositories.current.isActiveTask(task.taskId)).thenReturn(true)

        desktopTaskChangeListener.onTaskOpening(task)

        verify(desktopUserRepositories.current, never())
            .addTask(task.displayId, task.taskId, task.isVisible)
    }

    @Test
    fun onTaskOpening_freeformTask_notActiveInDesktopRepo_addsTaskToRepository() {
        val task = createFreeformTask().apply { isVisible = false }
        whenever(desktopUserRepositories.current.isActiveTask(task.taskId)).thenReturn(false)

        desktopTaskChangeListener.onTaskOpening(task)

        verify(desktopUserRepositories.current).addTask(task.displayId, task.taskId, task.isVisible)
    }

    @Test
    fun onTaskChanging_fullscreenTask_activeInDesktopRepository_removesTaskFromRepo() {
        val task = createFullscreenTask().apply { isVisible = true }
        whenever(desktopUserRepositories.current.isActiveTask(task.taskId)).thenReturn(true)

        desktopTaskChangeListener.onTaskChanging(task)

        verify(desktopUserRepositories.current).removeTask(task.displayId, task.taskId)
    }

    @Test
    fun onTaskChanging_fullscreenTask_nonActiveInDesktopRepo_noop() {
        val task = createFullscreenTask().apply { isVisible = true }
        whenever(desktopUserRepositories.current.isActiveTask(task.taskId)).thenReturn(false)

        desktopTaskChangeListener.onTaskChanging(task)

        verify(desktopUserRepositories.current, never()).removeTask(task.displayId, task.taskId)
    }

    @Test
    fun onTaskChanging_freeformTask_addsTaskToDesktopRepo() {
        val task = createFreeformTask().apply { isVisible = true }

        desktopTaskChangeListener.onTaskChanging(task)

        verify(desktopUserRepositories.current).addTask(task.displayId, task.taskId, task.isVisible)
    }

    @Test
    fun onTaskMovingToFront_fullscreenTask_activeTaskInDesktopRepo_removesTaskFromRepo() {
        val task = createFullscreenTask().apply { isVisible = true }
        whenever(desktopUserRepositories.current.isActiveTask(task.taskId)).thenReturn(true)

        desktopTaskChangeListener.onTaskMovingToFront(task)

        verify(desktopUserRepositories.current).removeTask(task.displayId, task.taskId)
    }

    @Test
    fun onTaskMovingToFront_fullscreenTask_nonActiveTaskInDesktopRepo_noop() {
        val task = createFullscreenTask().apply { isVisible = true }
        whenever(desktopUserRepositories.current.isActiveTask(task.taskId)).thenReturn(false)

        desktopTaskChangeListener.onTaskMovingToFront(task)

        verify(desktopUserRepositories.current, never()).removeTask(task.displayId, task.taskId)
    }

    @Test
    fun onTaskMovingToFront_freeformTask_addsTaskToRepo() {
        val task = createFreeformTask().apply { isVisible = true }

        desktopTaskChangeListener.onTaskMovingToFront(task)

        verify(desktopUserRepositories.current).addTask(task.displayId, task.taskId, task.isVisible)
    }

    @Test
    fun onTaskMovingToBack_activeTaskInRepo_updatesTask() {
        val task = createFreeformTask().apply { isVisible = true }
        whenever(desktopUserRepositories.current.isActiveTask(task.taskId)).thenReturn(true)

        desktopTaskChangeListener.onTaskMovingToBack(task)

        verify(desktopUserRepositories.current)
            .updateTask(task.displayId, task.taskId, /* isVisible= */ false)
    }

    @Test
    fun onTaskMovingToBack_nonActiveTaskInRepo_noop() {
        val task = createFreeformTask().apply { isVisible = true }
        whenever(desktopUserRepositories.current.isActiveTask(task.taskId)).thenReturn(false)

        desktopTaskChangeListener.onTaskMovingToBack(task)

        verify(desktopUserRepositories.current, never())
            .updateTask(task.displayId, task.taskId, /* isVisible= */ false)
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
    fun onTaskClosing_backNavEnabled_nonClosingTask_minimizesTaskInRepo() {
        val task = createFreeformTask().apply { isVisible = true }
        whenever(desktopUserRepositories.current.isActiveTask(task.taskId)).thenReturn(true)
        whenever(desktopUserRepositories.current.isClosingTask(task.taskId)).thenReturn(false)

        desktopTaskChangeListener.onTaskClosing(task)

        verify(desktopUserRepositories.current)
            .updateTask(task.displayId, task.taskId, isVisible = false)
        verify(desktopUserRepositories.current).minimizeTask(task.displayId, task.taskId)
    }

    @Test
    @DisableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
    fun onTaskClosing_backNavDisabled_closingTask_removesTaskInRepo() {
        val task = createFreeformTask().apply { isVisible = true }
        whenever(desktopUserRepositories.current.isActiveTask(task.taskId)).thenReturn(true)
        whenever(desktopUserRepositories.current.isClosingTask(task.taskId)).thenReturn(true)

        desktopTaskChangeListener.onTaskClosing(task)

        verify(desktopUserRepositories.current, never()).minimizeTask(task.displayId, task.taskId)
        verify(desktopUserRepositories.current).removeClosingTask(task.taskId)
        verify(desktopUserRepositories.current).removeTask(task.displayId, task.taskId)
    }

    @Test
    @EnableFlags(FLAG_ENABLE_DESKTOP_WINDOWING_BACK_NAVIGATION)
    fun onTaskClosing_backNavEnabled_closingTask_removesTaskFromRepo() {
        val task = createFreeformTask().apply { isVisible = true }
        whenever(desktopUserRepositories.current.isActiveTask(task.taskId)).thenReturn(true)
        whenever(desktopUserRepositories.current.isClosingTask(task.taskId)).thenReturn(true)

        desktopTaskChangeListener.onTaskClosing(task)

        verify(desktopUserRepositories.current).removeClosingTask(task.taskId)
        verify(desktopUserRepositories.current).removeTask(task.displayId, task.taskId)
    }
}
