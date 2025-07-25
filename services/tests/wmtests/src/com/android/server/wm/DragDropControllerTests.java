/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static android.Manifest.permission.START_TASKS_FROM_RECENTS;
import static android.content.ClipDescription.MIMETYPE_APPLICATION_ACTIVITY;
import static android.content.ClipDescription.MIMETYPE_APPLICATION_SHORTCUT;
import static android.content.ClipDescription.MIMETYPE_APPLICATION_TASK;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.view.DragEvent.ACTION_DRAG_ENDED;
import static android.view.DragEvent.ACTION_DRAG_STARTED;
import static android.view.DragEvent.ACTION_DROP;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.wm.DragDropController.MSG_UNHANDLED_DROP_LISTENER_TIMEOUT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Intent;
import android.content.pm.ShortcutServiceInternal;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.view.DragEvent;
import android.view.InputChannel;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.window.IGlobalDragListener;

import androidx.test.filters.SmallTest;

import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.window.flags.Flags;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Tests for the {@link DragDropController} class.
 *
 * Build/Install/Run:
 * atest WmTests:DragDropControllerTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class DragDropControllerTests extends WindowTestsBase {
    private static final int TIMEOUT_MS = 3000;
    private static final int TEST_UID = 12345;
    private static final int TEST_PROFILE_UID = 12345 * UserHandle.PER_USER_RANGE;
    private static final int TEST_PID = 67890;
    private static final String TEST_PACKAGE = "com.test.package";

    private TestDragDropController mTarget;
    private WindowProcessController mProcess;
    private WindowState mWindow;
    private IBinder mToken;

    static class TestDragDropController extends DragDropController {
        private Runnable mCloseCallback;
        private boolean mDeferDragStateClosed;
        private DragState mPendingCloseDragState;
        boolean mIsAccessibilityDrag;

        TestDragDropController(WindowManagerService service, Looper looper) {
            super(service, looper);
        }

        void setOnClosedCallbackLocked(Runnable runnable) {
            if (mIsAccessibilityDrag) {
                // Accessibility does not use animation
                assertTrue(!dragDropActiveLocked());
            } else {
                assertTrue(dragDropActiveLocked());
                mCloseCallback = runnable;
            }
        }

        /**
         * Caller of this is expected to also call
         * {@link TestDragDropController#continueDragStateClose} to properly close and clean up
         * DragState.
         */
        void deferDragStateClose() {
            mDeferDragStateClosed = true;
        }

        void continueDragStateClose() {
            mDeferDragStateClosed = false;
            if (mPendingCloseDragState != null) {
                onDragStateClosedLocked(mPendingCloseDragState);
            }
        }

        @Override
        void onDragStateClosedLocked(DragState dragState) {
            if (mDeferDragStateClosed) {
                mPendingCloseDragState = dragState;
                return;
            }
            super.onDragStateClosedLocked(dragState);
            if (mCloseCallback != null) {
                mCloseCallback.run();
                mCloseCallback = null;
            }
        }
    }

    private WindowState createDropTargetWindow(String name) {
        return createDropTargetWindow(name, null /* targetDisplay */);
    }

    /**
     * Creates a window state which can be used as a drop target.
     */
    private WindowState createDropTargetWindow(String name,
            @Nullable DisplayContent targetDisplay) {
        final WindowState window;
        if (targetDisplay == null) {
            final Task task = new TaskBuilder(mSupervisor).build();
            final ActivityRecord activity = new ActivityBuilder(mAtm).setTask(task).setUseProcess(
                    mProcess).build();
            window = newWindowBuilder(name, TYPE_BASE_APPLICATION).setWindowToken(
                    activity).setClientWindow(new TestIWindow()).build();
        } else {
            window = newWindowBuilder(name, TYPE_BASE_APPLICATION).setDisplay(
                    targetDisplay).setClientWindow(new TestIWindow()).build();
        }

        // Use a new TestIWindow so we don't collect events for other windows
        InputChannel channel = new InputChannel();
        window.openInputChannel(channel);
        window.mHasSurface = true;
        mWm.mWindowMap.put(window.mClient.asBinder(), window);
        return window;
    }

    @BeforeClass
    public static void setUpOnce() {
        final UserManagerInternal userManager = mock(UserManagerInternal.class);
        LocalServices.addService(UserManagerInternal.class, userManager);
    }

    @AfterClass
    public static void tearDownOnce() {
        LocalServices.removeServiceForTest(UserManagerInternal.class);
    }

    @Before
    public void setUp() throws Exception {
        mTarget = new TestDragDropController(mWm, mWm.mH.getLooper());
        mProcess = mSystemServicesTestRule.addProcess(TEST_PACKAGE, "testProc", TEST_PID, TEST_UID);
        mWindow = createDropTargetWindow("Drag test window");
        doReturn(mWindow).when(mDisplayContent).getTouchableWinAtPointLocked(0, 0);
        when(mWm.mInputManager.startDragAndDrop(any(IBinder.class), any(IBinder.class))).thenReturn(
                true);

        mWm.mWindowMap.put(mWindow.mClient.asBinder(), mWindow);
    }

    @After
    public void tearDown() throws Exception {
        final CountDownLatch latch;
        if (!mTarget.dragDropActiveLocked()) {
            return;
        }
        if (mToken != null) {
            mTarget.cancelDragAndDrop(mToken, false);
        }
        latch = new CountDownLatch(1);
        mTarget.setOnClosedCallbackLocked(latch::countDown);
        if (mTarget.mIsAccessibilityDrag) {
            mTarget.mIsAccessibilityDrag = false;
            return;
        }
        assertTrue(awaitInWmLock(() -> latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)));
    }

    @Test
    public void testDragFlow() {
        doDragAndDrop(0, ClipData.newPlainText("label", "Test"), 0, 0);
    }

    @Test
    public void testA11yDragFlow() {
        mTarget.mIsAccessibilityDrag = true;
        doA11yDragAndDrop(0, ClipData.newPlainText("label", "Test"), 0, 0);
    }

    @Test
    public void testPerformDrag_NullDataWithGrantUri() {
        doDragAndDrop(View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ, null, 0, 0);
    }

    @Test
    public void testPrivateInterceptGlobalDragDropFlagChecksPermission() {
        DisplayPolicy policy = mDisplayContent.getDisplayPolicy();
        WindowManager.LayoutParams attrs = new WindowManager.LayoutParams();
        attrs.privateFlags |= PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP;
        policy.validateAddingWindowLw(attrs, Binder.getCallingPid(), Binder.getCallingUid());

        verify(mWm.mAtmService).enforceTaskPermission(any());
    }

    @Test
    public void testPrivateInterceptGlobalDragDropFlagBehaviour() {
        mWindow.mAttrs.privateFlags |= PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP;
        mWindow.setViewVisibility(View.GONE);

        // Necessary for now since DragState.sendDragStartedLocked() will recycle drag events
        // immediately after dispatching, which is a problem when using mockito arguments captor
        // because it returns and modifies the same drag event
        TestIWindow iwindow = (TestIWindow) mWindow.mClient;
        final ArrayList<DragEvent> dragEvents = new ArrayList<>();
        iwindow.setDragEventJournal(dragEvents);

        startDrag(View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ,
                ClipData.newPlainText("label", "text"), (unused) -> {
                    // Verify the start-drag event is sent for invisible windows
                    final DragEvent dragEvent = dragEvents.get(0);
                    assertTrue(dragEvent.getAction() == ACTION_DRAG_STARTED);

                    // Verify after consuming that the drag surface is relinquished
                    try {
                        mTarget.deferDragStateClose();
                        mTarget.reportDropWindow(mWindow.mInputChannelToken, 0, 0);
                        // Verify the drop event includes the drag surface
                        mTarget.handleMotionEvent(false, mWindow.getDisplayId(), 0, 0);
                        final DragEvent dropEvent = dragEvents.get(dragEvents.size() - 1);
                        assertTrue(dropEvent.getDragSurface() != null);

                        mTarget.reportDropResult(iwindow, true);
                    } finally {
                        assertTrue(mTarget.dragSurfaceRelinquishedToDropTarget());
                        mTarget.continueDragStateClose();
                    }
                });
    }

    @Test
    public void testPrivateInterceptGlobalDragDropIgnoresNonLocalWindows() {
        WindowState nonLocalWindow = createDropTargetWindow("App drag test window");
        WindowState globalInterceptWindow = createDropTargetWindow("Global drag test window");
        globalInterceptWindow.mAttrs.privateFlags |= PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP;

        // Necessary for now since DragState.sendDragStartedLocked() will recycle drag events
        // immediately after dispatching, which is a problem when using mockito arguments captor
        // because it returns and modifies the same drag event
        TestIWindow localIWindow = (TestIWindow) mWindow.mClient;
        final ArrayList<DragEvent> localWindowDragEvents = new ArrayList<>();
        localIWindow.setDragEventJournal(localWindowDragEvents);
        TestIWindow nonLocalIWindow = (TestIWindow) nonLocalWindow.mClient;
        final ArrayList<DragEvent> nonLocalWindowDragEvents = new ArrayList<>();
        nonLocalIWindow.setDragEventJournal(nonLocalWindowDragEvents);
        TestIWindow globalInterceptIWindow = (TestIWindow) globalInterceptWindow.mClient;
        final ArrayList<DragEvent> globalInterceptWindowDragEvents = new ArrayList<>();
        globalInterceptIWindow.setDragEventJournal(globalInterceptWindowDragEvents);

        startDrag(View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ,
                createClipDataForActivity(null, mock(UserHandle.class)), (unused) -> {
                    // Verify the start-drag event is sent for the local and global intercept window
                    // but not the other window
                    assertTrue(nonLocalWindowDragEvents.isEmpty());
                    assertTrue(localWindowDragEvents.get(0).getAction() == ACTION_DRAG_STARTED);
                    assertTrue(globalInterceptWindowDragEvents.get(0).getAction()
                            == ACTION_DRAG_STARTED);

                    // Verify that only the global intercept window receives the clip data with the
                    // resolved activity info for the drag
                    assertNull(localWindowDragEvents.get(0).getClipData());
                    assertTrue(globalInterceptWindowDragEvents.get(
                            0).getClipData().willParcelWithActivityInfo());

                    mTarget.reportDropWindow(globalInterceptWindow.mInputChannelToken, 0, 0);
                    mTarget.handleMotionEvent(false, globalInterceptWindow.getDisplayId(), 0, 0);
                    mToken = globalInterceptWindow.mClient.asBinder();

                    // Verify the drop event is only sent for the global intercept window
                    assertTrue(nonLocalWindowDragEvents.isEmpty());
                    assertNotEquals(ACTION_DROP, localWindowDragEvents.getLast().getAction());
                    assertEquals(ACTION_DROP,
                            globalInterceptWindowDragEvents.getLast().getAction());

                    // Verify that item extras were not sent with the drop event
                    assertNull(localWindowDragEvents.getLast().getClipData());
                    assertFalse(globalInterceptWindowDragEvents.getLast().getClipData()
                            .willParcelWithActivityInfo());
                });
    }

    @Test
    public void testPrivateInterceptGlobalDragDropGetsDragFlags() {
        mWindow.mAttrs.privateFlags |= PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP;
        mWindow.setViewVisibility(View.GONE);

        // Necessary for now since DragState.sendDragStartedLocked() will recycle drag events
        // immediately after dispatching, which is a problem when using mockito arguments captor
        // because it returns and modifies the same drag event
        TestIWindow iwindow = (TestIWindow) mWindow.mClient;
        final ArrayList<DragEvent> dragEvents = new ArrayList<>();
        iwindow.setDragEventJournal(dragEvents);

        startDrag(View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_START_INTENT_SENDER_ON_UNHANDLED_DRAG,
                ClipData.newPlainText("label", "text"), (unused) -> {
                    // Verify the start-drag event has the drag flags
                    final DragEvent dragEvent = dragEvents.get(0);
                    assertTrue(dragEvent.getAction() == ACTION_DRAG_STARTED);
                    assertTrue(dragEvent.getDragFlags() == (View.DRAG_FLAG_GLOBAL
                            | View.DRAG_FLAG_START_INTENT_SENDER_ON_UNHANDLED_DRAG));

                    try {
                        mTarget.deferDragStateClose();
                        mTarget.reportDropWindow(mWindow.mInputChannelToken, 0, 0);
                        // Verify the drop event does not have the drag flags
                        mTarget.handleMotionEvent(false, mWindow.getDisplayId(), 0, 0);
                        final DragEvent dropEvent = dragEvents.get(dragEvents.size() - 1);
                        assertTrue(dropEvent.getDragFlags() == (View.DRAG_FLAG_GLOBAL
                                | View.DRAG_FLAG_START_INTENT_SENDER_ON_UNHANDLED_DRAG));

                        mTarget.reportDropResult(iwindow, true);
                    } finally {
                        mTarget.continueDragStateClose();
                    }
                });
    }

    @Test
    public void testDragEventCoordinatesOverlappingWindows() {
        int dragStartX = mWindow.getBounds().centerX();
        int dragStartY = mWindow.getBounds().centerY();
        int startOffsetPx = 10;
        int dropCoordsPx = 15;
        WindowState window2 = createDropTargetWindow("App drag test window");
        Rect bounds = new Rect(dragStartX + startOffsetPx, dragStartY + startOffsetPx,
                mWindow.getBounds().right, mWindow.getBounds().bottom);
        window2.setBounds(bounds);
        window2.getFrame().set(bounds);

        // Necessary for now since DragState.sendDragStartedLocked() will recycle drag events
        // immediately after dispatching, which is a problem when using mockito arguments captor
        // because it returns and modifies the same drag event.
        TestIWindow iwindow = (TestIWindow) mWindow.mClient;
        final ArrayList<DragEvent> dragEvents = new ArrayList<>();
        iwindow.setDragEventJournal(dragEvents);
        TestIWindow iwindow2 = (TestIWindow) window2.mClient;
        final ArrayList<DragEvent> dragEvents2 = new ArrayList<>();
        iwindow2.setDragEventJournal(dragEvents2);

        startDrag(dragStartX, dragStartY, View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ,
                ClipData.newPlainText("label", "text"), (unused) -> {
                    // Verify the start-drag event is sent as-is for the drag origin window.
                    final DragEvent dragEvent = dragEvents.get(0);
                    assertEquals(ACTION_DRAG_STARTED, dragEvent.getAction());
                    assertEquals(dragStartX, dragEvent.getX(), 0.0 /* delta */);
                    assertEquals(dragStartY, dragEvent.getY(), 0.0 /* delta */);
                    // Verify the start-drag event is sent relative to the window top-left.
                    final DragEvent dragEvent2 = dragEvents2.get(0);
                    assertEquals(ACTION_DRAG_STARTED, dragEvent2.getAction());
                    assertEquals(-startOffsetPx, dragEvent2.getX(),  0.0 /* delta */);
                    assertEquals(-startOffsetPx, dragEvent2.getY(), 0.0 /* delta */);

                    try {
                        mTarget.deferDragStateClose();
                        // x, y is window-local coordinate.
                        mTarget.reportDropWindow(window2.mInputChannelToken, dropCoordsPx,
                                dropCoordsPx);
                        mTarget.handleMotionEvent(false, window2.getDisplayId(), dropCoordsPx,
                                dropCoordsPx);
                        mToken = window2.mClient.asBinder();
                        // Verify only window2 received the DROP event and coords are sent as-is.
                        assertEquals(1, dragEvents.size());
                        assertEquals(2, dragEvents2.size());
                        final DragEvent dropEvent = dragEvents2.getLast();
                        assertEquals(ACTION_DROP, dropEvent.getAction());
                        assertEquals(dropCoordsPx, dropEvent.getX(),  0.0 /* delta */);
                        assertEquals(dropCoordsPx, dropEvent.getY(),  0.0 /* delta */);
                        assertEquals(window2.getDisplayId(), dropEvent.getDisplayId());

                        mTarget.reportDropResult(iwindow2, true);
                        // Verify both windows received ACTION_DRAG_ENDED event.
                        assertEquals(ACTION_DRAG_ENDED, dragEvents.getLast().getAction());
                        assertEquals(window2.getDisplayId(), dragEvents.getLast().getDisplayId());
                        assertEquals(ACTION_DRAG_ENDED, dragEvents2.getLast().getAction());
                        assertEquals(window2.getDisplayId(), dragEvents2.getLast().getDisplayId());
                    } finally {
                        mTarget.continueDragStateClose();
                    }
                });
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CONNECTED_DISPLAYS_DND)
    public void testDragEventConnectedDisplaysCoordinates() {
        final DisplayContent testDisplay = createMockSimulatedDisplay();
        int dragStartX = mWindow.getBounds().centerX();
        int dragStartY = mWindow.getBounds().centerY();
        int dropCoordsPx = 15;
        WindowState window2 = createDropTargetWindow("App drag test window", testDisplay);

        // Necessary for now since DragState.sendDragStartedLocked() will recycle drag events
        // immediately after dispatching, which is a problem when using mockito arguments captor
        // because it returns and modifies the same drag event.
        TestIWindow iwindow = (TestIWindow) mWindow.mClient;
        final ArrayList<DragEvent> dragEvents = new ArrayList<>();
        iwindow.setDragEventJournal(dragEvents);
        TestIWindow iwindow2 = (TestIWindow) window2.mClient;
        final ArrayList<DragEvent> dragEvents2 = new ArrayList<>();
        iwindow2.setDragEventJournal(dragEvents2);

        startDrag(dragStartX, dragStartY, View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ,
                ClipData.newPlainText("label", "text"), (unused) -> {
                    // Verify the start-drag event is sent as-is for the drag origin window.
                    final DragEvent dragEvent = dragEvents.get(0);
                    assertEquals(ACTION_DRAG_STARTED, dragEvent.getAction());
                    assertEquals(dragStartX, dragEvent.getX(), 0.0 /* delta */);
                    assertEquals(dragStartY, dragEvent.getY(), 0.0 /* delta */);
                    // Verify the start-drag event from different display is sent out of display
                    // bounds.
                    final DragEvent dragEvent2 = dragEvents2.get(0);
                    assertEquals(ACTION_DRAG_STARTED, dragEvent2.getAction());
                    assertEquals(-window2.getBounds().left - 1, dragEvent2.getX(), 0.0 /* delta */);
                    assertEquals(-window2.getBounds().top - 1, dragEvent2.getY(), 0.0 /* delta */);

                    try {
                        mTarget.deferDragStateClose();
                        mTarget.handleMotionEvent(true, testDisplay.getDisplayId(), dropCoordsPx,
                                dropCoordsPx);
                        // x, y is window-local coordinate.
                        mTarget.reportDropWindow(window2.mInputChannelToken, dropCoordsPx,
                                dropCoordsPx);
                        mTarget.handleMotionEvent(false, testDisplay.getDisplayId(), dropCoordsPx,
                                dropCoordsPx);
                        mToken = window2.mClient.asBinder();
                        // Verify only window2 received the DROP event and coords are sent as-is
                        assertEquals(1, dragEvents.size());
                        assertEquals(2, dragEvents2.size());
                        final DragEvent dropEvent = dragEvents2.getLast();
                        assertEquals(ACTION_DROP, dropEvent.getAction());
                        assertEquals(dropCoordsPx, dropEvent.getX(),  0.0 /* delta */);
                        assertEquals(dropCoordsPx, dropEvent.getY(),  0.0 /* delta */);
                        assertEquals(testDisplay.getDisplayId(), dropEvent.getDisplayId());

                        mTarget.reportDropResult(iwindow2, true);
                        // Verify both windows received ACTION_DRAG_ENDED event.
                        assertEquals(ACTION_DRAG_ENDED, dragEvents.getLast().getAction());
                        assertEquals(testDisplay.getDisplayId(),
                                dragEvents.getLast().getDisplayId());
                        assertEquals(ACTION_DRAG_ENDED, dragEvents2.getLast().getAction());
                        assertEquals(testDisplay.getDisplayId(),
                                dragEvents2.getLast().getDisplayId());
                    } finally {
                        mTarget.continueDragStateClose();
                    }
                });
    }

    @Test
    public void testDragMove() {
        startDrag(0, 0, View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ,
                ClipData.newPlainText("label", "text"), (surface) -> {
                    int dragMoveX = mWindow.getBounds().centerX();
                    int dragMoveY = mWindow.getBounds().centerY();
                    final SurfaceControl.Transaction transaction =
                            mSystemServicesTestRule.mTransaction;
                    clearInvocations(transaction);

                    mTarget.handleMotionEvent(true, mWindow.getDisplayId(), dragMoveX, dragMoveY);
                    verify(transaction).setPosition(surface, dragMoveX, dragMoveY);

                    // Clean-up.
                    mTarget.reportDropWindow(mWindow.mInputChannelToken, 0, 0);
                    mTarget.handleMotionEvent(false /* keepHandling */, mWindow.getDisplayId(), 0,
                            0);
                    mToken = mWindow.mClient.asBinder();
                });
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CONNECTED_DISPLAYS_DND)
    public void testConnectedDisplaysDragMoveToOtherDisplay() {
        final float testDensityMultiplier = 1.5f;
        final DisplayContent testDisplay = createMockSimulatedDisplay();
        testDisplay.mBaseDisplayDensity =
                (int) (mDisplayContent.mBaseDisplayDensity * testDensityMultiplier);
        WindowState testWindow = createDropTargetWindow("App drag test window", testDisplay);

        // Test starts from mWindow which is on default display.
        startDrag(0, 0, View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ,
                ClipData.newPlainText("label", "text"), (surface) -> {
                    final SurfaceControl.Transaction transaction =
                            mSystemServicesTestRule.mTransaction;
                    clearInvocations(transaction);
                    mTarget.handleMotionEvent(true, testWindow.getDisplayId(), 0, 0);

                    verify(transaction).reparent(surface, testDisplay.getSurfaceControl());
                    verify(transaction).setScale(surface, testDensityMultiplier,
                            testDensityMultiplier);

                    // Clean-up.
                    mTarget.reportDropWindow(mWindow.mInputChannelToken, 0, 0);
                    mTarget.handleMotionEvent(false /* keepHandling */, mWindow.getDisplayId(), 0,
                            0);
                    mToken = mWindow.mClient.asBinder();
                });
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_CONNECTED_DISPLAYS_DND)
    public void testDragCancelledOnTopologyChange() {
        // Necessary for now since DragState.sendDragStartedLocked() will recycle drag events
        // immediately after dispatching, which is a problem when using mockito arguments captor
        // because it returns and modifies the same drag event.
        TestIWindow iwindow = (TestIWindow) mWindow.mClient;
        final ArrayList<DragEvent> dragEvents = new ArrayList<>();
        iwindow.setDragEventJournal(dragEvents);

        startDrag(0, 0, View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ,
                ClipData.newPlainText("label", "text"), (surface) -> {
                    // Simulate display topology change to trigger drag-and-drop cancellation.
                    mTarget.handleDisplayTopologyChange(null /* displayTopology */);
                    assertEquals(2, dragEvents.size());
                    assertEquals(ACTION_DRAG_ENDED, dragEvents.getLast().getAction());
                });
    }

    @Test
    public void testValidateAppActivityArguments() {
        final Session session = getTestSession();
        try {
            session.validateAndResolveDragMimeTypeExtras(
                    createClipDataForActivity(mock(PendingIntent.class), null), TEST_UID, TEST_PID,
                    TEST_PACKAGE);
            fail("Expected failure without user");
        } catch (IllegalArgumentException e) {
            // Expected failure
        }
        try {
            session.validateAndResolveDragMimeTypeExtras(
                    createClipDataForActivity(null, mock(UserHandle.class)), TEST_UID, TEST_PID,
                    TEST_PACKAGE);
            fail("Expected failure without pending intent");
        } catch (IllegalArgumentException e) {
            // Expected failure
        }
    }

    private ClipData createClipDataForActivity(PendingIntent pi, UserHandle user) {
        final Intent data = new Intent();
        if (pi != null) {
            data.putExtra(ClipDescription.EXTRA_PENDING_INTENT, (Parcelable) pi);
        }
        if (user != null) {
            data.putExtra(Intent.EXTRA_USER, user);
        }
        final ClipData clipData = new ClipData(
                new ClipDescription("drag", new String[]{MIMETYPE_APPLICATION_ACTIVITY}),
                new ClipData.Item(data));
        return clipData;
    }

    @Test
    public void testValidateAppShortcutArguments() {
        doReturn(PERMISSION_GRANTED).when(mWm.mContext).checkCallingOrSelfPermission(
                eq(START_TASKS_FROM_RECENTS));
        final Session session = createTestSession(mAtm);
        try {
            session.validateAndResolveDragMimeTypeExtras(
                    createClipDataForShortcut(null, "test_shortcut_id", mock(UserHandle.class)),
                    TEST_UID, TEST_PID, TEST_PACKAGE);
            fail("Expected failure without package name");
        } catch (IllegalArgumentException e) {
            // Expected failure
        }
        try {
            session.validateAndResolveDragMimeTypeExtras(
                    createClipDataForShortcut("test_package", null, mock(UserHandle.class)),
                    TEST_UID, TEST_PID, TEST_PACKAGE);
            fail("Expected failure without shortcut id");
        } catch (IllegalArgumentException e) {
            // Expected failure
        }
        try {
            session.validateAndResolveDragMimeTypeExtras(
                    createClipDataForShortcut("test_package", "test_shortcut_id", null), TEST_UID,
                    TEST_PID, TEST_PACKAGE);
            fail("Expected failure without package name");
        } catch (IllegalArgumentException e) {
            // Expected failure
        }
    }

    @Test
    public void testValidateProfileAppShortcutArguments_notCallingUid() {
        doReturn(PERMISSION_GRANTED).when(mWm.mContext).checkCallingOrSelfPermission(
                eq(START_TASKS_FROM_RECENTS));
        final Session session = createTestSession(mAtm);
        final ShortcutServiceInternal shortcutService = mock(ShortcutServiceInternal.class);
        final Intent[] shortcutIntents = new Intent[1];
        shortcutIntents[0] = new Intent();
        doReturn(shortcutIntents).when(shortcutService).createShortcutIntents(anyInt(), any(),
                any(), any(), anyInt(), anyInt(), anyInt());
        LocalServices.removeServiceForTest(ShortcutServiceInternal.class);
        LocalServices.addService(ShortcutServiceInternal.class, shortcutService);

        ArgumentCaptor<Integer> callingUser = ArgumentCaptor.forClass(Integer.class);
        session.validateAndResolveDragMimeTypeExtras(
                createClipDataForShortcut("test_package", "test_shortcut_id",
                        mock(UserHandle.class)), TEST_PROFILE_UID, TEST_PID, TEST_PACKAGE);
        verify(shortcutService).createShortcutIntents(callingUser.capture(), any(), any(), any(),
                anyInt(), anyInt(), anyInt());
        assertTrue(callingUser.getValue() == UserHandle.getUserId(TEST_PROFILE_UID));
    }

    private ClipData createClipDataForShortcut(String packageName, String shortcutId,
            UserHandle user) {
        final Intent data = new Intent();
        if (packageName != null) {
            data.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName);
        }
        if (shortcutId != null) {
            data.putExtra(Intent.EXTRA_SHORTCUT_ID, shortcutId);
        }
        if (user != null) {
            data.putExtra(Intent.EXTRA_USER, user);
        }
        final ClipData clipData = new ClipData(
                new ClipDescription("drag", new String[]{MIMETYPE_APPLICATION_SHORTCUT}),
                new ClipData.Item(data));
        return clipData;
    }

    @Test
    public void testValidateAppTaskArguments() {
        doReturn(PERMISSION_GRANTED).when(mWm.mContext).checkCallingOrSelfPermission(
                eq(START_TASKS_FROM_RECENTS));
        final Session session = createTestSession(mAtm);
        try {
            final ClipData clipData = new ClipData(
                    new ClipDescription("drag", new String[]{MIMETYPE_APPLICATION_TASK}),
                    new ClipData.Item(new Intent()));

            session.validateAndResolveDragMimeTypeExtras(clipData, TEST_UID, TEST_PID,
                    TEST_PACKAGE);
            fail("Expected failure without task id");
        } catch (IllegalArgumentException e) {
            // Expected failure
        }
    }

    @Test
    public void testValidateFlags() {
        final Session session = getTestSession();
        try {
            session.validateDragFlags(View.DRAG_FLAG_REQUEST_SURFACE_FOR_RETURN_ANIMATION,
                    0 /* callingUid */);
            fail("Expected failure without permission");
        } catch (SecurityException e) {
            // Expected failure
        }
    }

    @Test
    public void testValidateFlagsWithPermission() {
        doReturn(PERMISSION_GRANTED).when(mWm.mContext).checkCallingOrSelfPermission(
                eq(START_TASKS_FROM_RECENTS));
        final Session session = createTestSession(mAtm);
        try {
            session.validateDragFlags(View.DRAG_FLAG_REQUEST_SURFACE_FOR_RETURN_ANIMATION,
                    0 /* callingUid */);
            // Expected pass
        } catch (SecurityException e) {
            fail("Expected no failure with permission");
        }
    }

    @Test
    public void testRequestSurfaceForReturnAnimationFlag_dropSuccessful() {
        WindowState otherWindow = createDropTargetWindow("App drag test window");
        TestIWindow otherIWindow = (TestIWindow) otherWindow.mClient;

        // Necessary for now since DragState.sendDragStartedLocked() will recycle drag events
        // immediately after dispatching, which is a problem when using mockito arguments captor
        // because it returns and modifies the same drag event
        TestIWindow iwindow = (TestIWindow) mWindow.mClient;
        final ArrayList<DragEvent> dragEvents = new ArrayList<>();
        iwindow.setDragEventJournal(dragEvents);

        startDrag(View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ
                        | View.DRAG_FLAG_REQUEST_SURFACE_FOR_RETURN_ANIMATION,
                ClipData.newPlainText("label", "text"), (unused) -> {
                    assertTrue(dragEvents.get(0).getAction() == ACTION_DRAG_STARTED);

                    // Verify after consuming that the drag surface is relinquished
                    mTarget.reportDropWindow(otherWindow.mInputChannelToken, 0, 0);
                    mTarget.handleMotionEvent(false, otherWindow.getDisplayId(), 0, 0);
                    mToken = otherWindow.mClient.asBinder();
                    mTarget.reportDropResult(otherIWindow, true);

                    // Verify the DRAG_ENDED event does NOT include the drag surface
                    final DragEvent dropEvent = dragEvents.get(dragEvents.size() - 1);
                    assertTrue(
                            dragEvents.get(dragEvents.size() - 1).getAction() == ACTION_DRAG_ENDED);
                    assertTrue(dropEvent.getDragSurface() == null);
                });
    }

    @Test
    public void testRequestSurfaceForReturnAnimationFlag_dropUnsuccessful() {
        WindowState otherWindow = createDropTargetWindow("App drag test window");
        TestIWindow otherIWindow = (TestIWindow) otherWindow.mClient;

        // Necessary for now since DragState.sendDragStartedLocked() will recycle drag events
        // immediately after dispatching, which is a problem when using mockito arguments captor
        // because it returns and modifies the same drag event
        TestIWindow iwindow = (TestIWindow) mWindow.mClient;
        final ArrayList<DragEvent> dragEvents = new ArrayList<>();
        iwindow.setDragEventJournal(dragEvents);

        startDrag(View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ
                        | View.DRAG_FLAG_REQUEST_SURFACE_FOR_RETURN_ANIMATION,
                ClipData.newPlainText("label", "text"), (unused) -> {
                    assertTrue(dragEvents.get(0).getAction() == ACTION_DRAG_STARTED);

                    // Verify after consuming that the drag surface is relinquished
                    mTarget.reportDropWindow(otherWindow.mInputChannelToken, 0, 0);
                    mTarget.handleMotionEvent(false, otherWindow.getDisplayId(), 0, 0);
                    mToken = otherWindow.mClient.asBinder();
                    mTarget.reportDropResult(otherIWindow, false);

                    // Verify the DRAG_ENDED event includes the drag surface
                    final DragEvent dropEvent = dragEvents.get(dragEvents.size() - 1);
                    assertTrue(
                            dragEvents.get(dragEvents.size() - 1).getAction() == ACTION_DRAG_ENDED);
                    assertTrue(dropEvent.getDragSurface() != null);
                });
    }

    @Test
    public void testUnhandledDragNotCalledForNormalDrags() throws RemoteException {
        assumeTrue(com.android.window.flags.Flags.delegateUnhandledDrags());

        final IGlobalDragListener listener = mock(IGlobalDragListener.class);
        doReturn(mock(Binder.class)).when(listener).asBinder();
        mTarget.setGlobalDragListener(listener);
        doDragAndDrop(0, ClipData.newPlainText("label", "Test"), 0, 0);
        verify(listener, times(0)).onUnhandledDrop(any(), any());
    }

    @Test
    public void testUnhandledDragReceivesUnhandledDropOverWindow() {
        assumeTrue(com.android.window.flags.Flags.delegateUnhandledDrags());

        final IGlobalDragListener listener = mock(IGlobalDragListener.class);
        doReturn(mock(Binder.class)).when(listener).asBinder();
        mTarget.setGlobalDragListener(listener);
        final int invalidXY = 100_000;
        startDrag(View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_START_INTENT_SENDER_ON_UNHANDLED_DRAG,
                ClipData.newPlainText("label", "Test"), (unused) -> {
                    // Trigger an unhandled drop and verify the global drag listener was called
                    mTarget.reportDropWindow(mWindow.mInputChannelToken, invalidXY, invalidXY);
                    mTarget.handleMotionEvent(false /* keepHandling */, mWindow.getDisplayId(),
                            invalidXY, invalidXY);
                    mTarget.reportDropResult(mWindow.mClient, false);
                    mTarget.onUnhandledDropCallback(true);
                    mToken = null;
                    try {
                        verify(listener, times(1)).onUnhandledDrop(any(), any());
                    } catch (RemoteException e) {
                        fail("Failed to verify unhandled drop: " + e);
                    }
                });
    }

    @Test
    public void testUnhandledDragReceivesUnhandledDropOverNoValidWindow() {
        assumeTrue(com.android.window.flags.Flags.delegateUnhandledDrags());

        final IGlobalDragListener listener = mock(IGlobalDragListener.class);
        doReturn(mock(Binder.class)).when(listener).asBinder();
        mTarget.setGlobalDragListener(listener);
        final int invalidXY = 100_000;
        startDrag(View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_START_INTENT_SENDER_ON_UNHANDLED_DRAG,
                ClipData.newPlainText("label", "Test"), (unused) -> {
                    // Trigger an unhandled drop and verify the global drag listener was called
                    mTarget.reportDropWindow(mock(IBinder.class), invalidXY, invalidXY);
                    mTarget.handleMotionEvent(false /* keepHandling */, mWindow.getDisplayId(),
                            invalidXY, invalidXY);
                    mTarget.onUnhandledDropCallback(true);
                    mToken = null;
                    try {
                        verify(listener, times(1)).onUnhandledDrop(any(), any());
                    } catch (RemoteException e) {
                        fail("Failed to verify unhandled drop: " + e);
                    }
                });
    }

    @Test
    public void testUnhandledDragDoesNotReceiveUnhandledDropWithoutDragFlag() {
        assumeTrue(com.android.window.flags.Flags.delegateUnhandledDrags());

        final IGlobalDragListener listener = mock(IGlobalDragListener.class);
        doReturn(mock(Binder.class)).when(listener).asBinder();
        mTarget.setGlobalDragListener(listener);
        final int invalidXY = 100_000;
        startDrag(View.DRAG_FLAG_GLOBAL, ClipData.newPlainText("label", "Test"), (unused) -> {
            // Trigger an unhandled drop and verify the global drag listener was not called
            mTarget.reportDropWindow(mock(IBinder.class), invalidXY, invalidXY);
            mTarget.handleMotionEvent(false /* keepHandling */, mDisplayContent.getDisplayId(),
                    invalidXY, invalidXY);
            mToken = null;
            try {
                verify(listener, never()).onUnhandledDrop(any(), any());
            } catch (RemoteException e) {
                fail("Failed to verify unhandled drop: " + e);
            }
        });
    }

    @Test
    public void testUnhandledDragCallbackTimeout() {
        assumeTrue(com.android.window.flags.Flags.delegateUnhandledDrags());

        final IGlobalDragListener listener = mock(IGlobalDragListener.class);
        doReturn(mock(Binder.class)).when(listener).asBinder();
        mTarget.setGlobalDragListener(listener);
        final int invalidXY = 100_000;
        startDrag(View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_START_INTENT_SENDER_ON_UNHANDLED_DRAG,
                ClipData.newPlainText("label", "Test"), (unused) -> {
                    // Trigger an unhandled drop and verify the global drag listener was called
                    mTarget.reportDropWindow(mock(IBinder.class), invalidXY, invalidXY);
                    mTarget.handleMotionEvent(false /* keepHandling */,
                            mDisplayContent.getDisplayId(), invalidXY, invalidXY);

                    // Verify that the unhandled drop listener callback timeout has been scheduled
                    final Handler handler = mTarget.getHandler();
                    assertTrue(handler.hasMessages(MSG_UNHANDLED_DROP_LISTENER_TIMEOUT));

                    // Force trigger the timeout and verify that it actually cleans up the drag &
                    // timeout
                    handler.handleMessage(
                            Message.obtain(handler, MSG_UNHANDLED_DROP_LISTENER_TIMEOUT));
                    assertFalse(handler.hasMessages(MSG_UNHANDLED_DROP_LISTENER_TIMEOUT));
                    assertFalse(mTarget.dragDropActiveLocked());
                    mToken = null;
                });
    }

    private void doDragAndDrop(int flags, ClipData data, float dropX, float dropY) {
        startDrag(flags, data, (unused) -> {
            mTarget.reportDropWindow(mWindow.mInputChannelToken, dropX, dropY);
            mTarget.handleMotionEvent(false /* keepHandling */, mWindow.getDisplayId(), dropX,
                    dropY);
            mToken = mWindow.mClient.asBinder();
        });
    }

    /**
     * Starts a drag with the given parameters, calls Runnable `r` after drag is started.
     */
    private void startDrag(int flag, ClipData data, Consumer<SurfaceControl> c) {
        startDrag(0, 0, flag, data, c);
    }

    /**
     * Starts a drag with the given parameters, calls Runnable `r` after drag is started.
     */
    private void startDrag(float startInWindowX, float startInWindowY, int flag, ClipData data,
            Consumer<SurfaceControl> c) {
        final SurfaceSession appSession = new SurfaceSession();
        try {
            final SurfaceControl surface = new SurfaceControl.Builder(appSession).setName(
                    "drag surface").setBufferSize(100, 100).setFormat(
                    PixelFormat.TRANSLUCENT).build();
            assertTrue(mWm.mInputManager.startDragAndDrop(new Binder(), new Binder()));
            mToken = mTarget.performDrag(TEST_PID, 0, mWindow.mClient, flag, surface, 0, 0, 0,
                    startInWindowX, startInWindowY, 0, 0, data);
            assertNotNull(mToken);

            c.accept(surface);
        } finally {
            appSession.kill();
        }
    }

    private void doA11yDragAndDrop(int flags, ClipData data, float dropX, float dropY) {
        spyOn(mTarget);
        AccessibilityManager accessibilityManager = Mockito.mock(AccessibilityManager.class);
        when(accessibilityManager.isEnabled()).thenReturn(true);
        doReturn(accessibilityManager).when(mTarget).getAccessibilityManager();
        startA11yDrag(flags, data, () -> {
            boolean dropped = mTarget.dropForAccessibility(mWindow.mClient, dropX, dropY);
            mToken = mWindow.mClient.asBinder();
        });
    }

    private void startA11yDrag(int flags, ClipData data, Runnable r) {
        mToken = mTarget.performDrag(0, 0, mWindow.mClient,
                flags | View.DRAG_FLAG_ACCESSIBILITY_ACTION, null, 0, 0, 0, 0, 0, 0, 0, data);
        assertNotNull(mToken);
        r.run();
    }
}
