/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.input;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.graphics.PointF;
import android.hardware.display.DisplayTopologyGraph;
import android.hardware.display.DisplayViewport;
import android.hardware.input.KeyGestureEvent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.SparseBooleanArray;
import android.view.InputChannel;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.inputmethod.InputMethodSubtypeHandle;
import com.android.internal.policy.IShortcutService;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Input manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class InputManagerInternal {

    // Backup and restore information for custom input gestures.
    public static final int BACKUP_CATEGORY_INPUT_GESTURES = 0;

    // Backup and Restore categories for sending map of data back and forth to backup and restore
    // infrastructure.
    @IntDef({BACKUP_CATEGORY_INPUT_GESTURES})
    public @interface BackupCategory {
    }

    /**
     * Called by the display manager to set information about the displays as needed
     * by the input system.  The input system must copy this information to retain it.
     */
    public abstract void setDisplayViewports(List<DisplayViewport> viewports);

    /**
     * Called by {@link com.android.server.display.DisplayManagerService} to inform InputManager
     * about changes in the displays topology.
     */
    public abstract void setDisplayTopology(DisplayTopologyGraph topology);

    /**
     * Called by the power manager to tell the input manager whether it should start
     * watching for wake events on given displays.
     *
     * @param displayInteractivities Map of display ids to their current interactive state.
     */
    public abstract void setDisplayInteractivities(SparseBooleanArray displayInteractivities);

    /**
     * Toggles Caps Lock state for input device with specific id.
     *
     * @param deviceId The id of input device.
     */
    public abstract void toggleCapsLock(int deviceId);

    /**
     * Set whether the input stack should deliver pulse gesture events when the device is asleep.
     */
    public abstract void setPulseGestureEnabled(boolean enabled);

    /**
     * Atomically transfers an active touch gesture from one window to another, as identified by
     * their input channels.
     *
     * <p>Only the touch gesture that is currently being dispatched to a window associated with
     * {@code fromChannelToken} will be effected. That window will no longer receive
     * the touch gesture (i.e. it will receive {@link android.view.MotionEvent#ACTION_CANCEL}).
     * A window associated with the {@code toChannelToken} will receive the rest of the gesture
     * (i.e. beginning with {@link android.view.MotionEvent#ACTION_DOWN} or
     * {@link android.view.MotionEvent#ACTION_POINTER_DOWN}).
     *
     * <p>Transferring touch gestures will have no impact on focused windows. If the {@code
     * toChannelToken} window is focusable, this will not bring focus to that window.
     *
     * @param fromChannelToken The channel token of a window that has an active touch gesture.
     * @param toChannelToken The channel token of the window that should receive the gesture in
     *   place of the first.
     * @param transferEntireGesture Whether the entire gesture (including subsequent POINTER_DOWN
     *                              events) should be transferred. This should always be set to
     *                              'false' unless you have the permission from the input team to
     *                              set it to true. This behaviour will be removed in future
     *                              versions.
     * @return True if the transfer was successful. False if the specified windows don't exist, or
     *   if the source window is not actively receiving a touch gesture at the time of the request.
     */
    public abstract boolean transferTouchGesture(@NonNull IBinder fromChannelToken,
            @NonNull IBinder toChannelToken, boolean transferEntireGesture);

    /**
     * Gets the current position of the mouse cursor.
     *
     * Returns NaN-s as the coordinates if the cursor is not available.
     */
    public abstract PointF getCursorPosition(int displayId);

    /**
     * Set whether all pointer scaling, including linear scaling based on the
     * user's pointer speed setting, should be enabled or disabled for mice.
     *
     * Note that this only affects pointer movements from mice (that is, pointing devices which send
     * relative motions, including trackballs and pointing sticks), not from other pointer devices
     * such as touchpads and styluses.
     *
     * Scaling is enabled by default on new displays until it is explicitly disabled.
     */
    public abstract void setMouseScalingEnabled(boolean enabled, int displayId);

    /**
     * Sets the eligibility of windows on a given display for pointer capture. If a display is
     * marked ineligible, requests to enable pointer capture for windows on that display will be
     * ignored.
     */
    public abstract void setDisplayEligibilityForPointerCapture(int displayId, boolean isEligible);

    /** Sets the visibility of the cursor. */
    public abstract void setPointerIconVisible(boolean visible, int displayId);

    /** Registers the {@link LidSwitchCallback} to begin receiving notifications. */
    public abstract void registerLidSwitchCallback(@NonNull LidSwitchCallback callbacks);

    /**
     * Unregisters a {@link LidSwitchCallback callback} previously registered with
     * {@link #registerLidSwitchCallback(LidSwitchCallback)}.
     */
    public abstract void unregisterLidSwitchCallback(@NonNull LidSwitchCallback callbacks);

    /**
     * Notify the input manager that an IME connection is becoming active or is no longer active.
     */
    public abstract void notifyInputMethodConnectionActive(boolean connectionIsActive);

    /**
     * Notify user id changes to input.
     *
     * TODO(b/362473586): Cleanup after input shifts to Lifecycle with user change callbacks
     */
    public abstract void setCurrentUser(@UserIdInt int newUserId);

    /** Callback interface for notifications relating to the lid switch. */
    public interface LidSwitchCallback {
        /**
         * This callback is invoked when the lid switch changes state. Will be triggered once on
         * registration of the callback with a {@code whenNanos} of 0 and then on every subsequent
         * change in lid switch state.
         *
         * @param whenNanos the time when the change occurred
         * @param lidOpen true if the lid is open
         */
        void notifyLidSwitchChanged(long whenNanos, boolean lidOpen);
    }

    /** Create an {@link InputChannel} that is registered to InputDispatcher. */
    public abstract InputChannel createInputChannel(String inputChannelName);

    /**
     * Pilfer pointers from the input channel with the given token so that ongoing gestures are
     * canceled for all other channels.
     */
    public abstract void pilferPointers(IBinder token);

    /**
     * Called when the current input method and/or {@link InputMethodSubtype} is updated.
     *
     * @param userId User ID to be notified about.
     * @param subtypeHandle A {@link InputMethodSubtypeHandle} corresponds to {@code subtype}.
     * @param subtype A {@link InputMethodSubtype} object, or {@code null} when the current
     *                {@link InputMethodSubtype} is not suitable for the physical keyboard layout
     *                mapping.
     * @see InputMethodSubtype#isSuitableForPhysicalKeyboardLayoutMapping()
     */
    public abstract void onInputMethodSubtypeChangedForKeyboardLayoutMapping(@UserIdInt int userId,
            @Nullable InputMethodSubtypeHandle subtypeHandle,
            @Nullable InputMethodSubtype subtype);

    /**
     * Increments keyboard backlight level if the device has an associated keyboard backlight
     * {@see Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT}
     */
    public abstract void incrementKeyboardBacklight(int deviceId);

    /**
     * Decrements keyboard backlight level if the device has an associated keyboard backlight
     * {@see Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT}
     */
    public abstract void decrementKeyboardBacklight(int deviceId);

    /**
     * Add a runtime association between the input port and device type. Input ports are expected to
     * be unique.
     * @param inputPort The port of the input device.
     * @param type The type of the device. E.g. "touchNavigation".
     */
    public abstract void setTypeAssociation(@NonNull String inputPort, @NonNull String type);

    /**
     * Removes a runtime association between the input device and type.
     *
     * @param inputPort The port of the input device.
     */
    public abstract void unsetTypeAssociation(@NonNull String inputPort);

    /**
     * Add a mapping from the input port and a keyboard layout, by unique id. Input
     * ports are expected to be unique.
     *
     * @param inputPort   The port of the input device.
     * @param languageTag the language of the input device as an IETF
     *                    <a href="https://tools.ietf.org/html/bcp47">BCP-47</a>
     *                    conformant tag.
     * @param layoutType  the layout type such as "qwerty" or "azerty".
     */
    public abstract void addKeyboardLayoutAssociation(@NonNull String inputPort,
            @NonNull String languageTag, @NonNull String layoutType);

    /**
     * Removes the mapping from input port to the keyboard layout identifier.
     *
     * @param inputPort The port of the input device.
     */
    public abstract void removeKeyboardLayoutAssociation(@NonNull String inputPort);

    /**
     * Set whether stylus button reporting through motion events should be enabled.
     *
     * @param enabled When true, stylus buttons will not be reported through motion events.
     */
    public abstract void setStylusButtonMotionEventsEnabled(boolean enabled);

    /**
     * Notify whether any user activity occurred. This includes any input activity on any
     * display, external peripherals, fingerprint sensor, etc.
     */
    public abstract void notifyUserActivity();

    /**
     * Get the device ID of the {@link InputDevice} that used most recently.
     *
     * @return the last used input device ID, or
     *     {@link android.os.IInputConstants#INVALID_INPUT_DEVICE_ID} if no device has been used
     *     since boot.
     */
    public abstract int getLastUsedInputDeviceId();

    /**
     * Notify key gesture was completed by the user.
     *
     * NOTE: This is a temporary API added to assist in a long-term refactor, and is not meant for
     * general use by system services.
     *
     * @param deviceId the device ID of the keyboard using which the event was completed
     * @param keycodes the keys pressed for the event
     * @param modifierState the modifier state
     * @param event the gesture event that was completed
     *
     */
    public abstract void notifyKeyGestureCompleted(int deviceId, int[] keycodes, int modifierState,
            @KeyGestureEvent.KeyGestureType int event);

    /**
     * Notify that a key gesture was detected by another system component, and it should be handled
     * appropriately by KeyGestureController.
     *
     * NOTE: This is a temporary API added to assist in a long-term refactor, and is not meant for
     * general use by system services.
     *
     * @param deviceId the device ID of the keyboard using which the event was completed
     * @param keycodes the keys pressed for the event
     * @param modifierState the modifier state
     * @param event the gesture event that was completed
     *
     */
    public abstract void handleKeyGestureInKeyGestureController(int deviceId, int[] keycodes,
            int modifierState, @KeyGestureEvent.KeyGestureType int event);

    /**
     * Sets the magnification scale factor for pointer icons.
     *
     * @param displayId   the ID of the display where the new scale factor is applied.
     * @param scaleFactor the new scale factor to be applied for pointer icons.
     */
    public abstract void setAccessibilityPointerIconScaleFactor(int displayId, float scaleFactor);


    /**
     * Register shortcuts for input manager to dispatch.
     * Shortcut code is packed as (metaState << Integer.SIZE) | keyCode
     * @hide
     */
    public abstract void registerShortcutKey(long shortcutCode,
            IShortcutService shortcutKeyReceiver) throws RemoteException;

    /**
     * Set whether the given input device can wake up the kernel from sleep
     * when it generates input events. By default, usually only internal (built-in)
     * input devices can wake the kernel from sleep. For an external input device
     * that supports remote wakeup to be able to wake the kernel, this must be called
     * after each time the device is connected/added.
     *
     * @param deviceId the device ID of the input device.
     * @param enabled When true, device will be configured to wake up kernel.
     *
     * @return true if setting power wakeup was successful.
     */
    public abstract boolean setKernelWakeEnabled(int deviceId, boolean enabled);

    /**
     * Retrieves the input gestures backup payload data.
     *
     * @param userId the user ID of the backup data.
     * @return byte array of UTF-8 encoded backup data.
     */
    public abstract Map<Integer, byte[]> getBackupPayload(int userId) throws IOException;

    /**
     * Applies the given UTF-8 encoded byte array payload to the given user's input data
     * on a best effort basis.
     *
     * @param payload UTF-8 encoded map of byte arrays of restored data
     * @param userId the user ID for which to apply the payload data
     */
    public abstract void applyBackupPayload(Map<Integer, byte[]> payload, int userId)
            throws XmlPullParserException, IOException;

    /**
     * An interface for filtering pointer motion event before cursor position is determined.
     * <p>
     * Different from {@code android.view.InputFilter}, this filter can filter motion events at
     * an early stage of the input pipeline, but only called for pointer's relative motion events.
     * Unless the user really needs to filter events before the cursor position in the display is
     * determined, use {@code android.view.InputFilter} instead.
     */
    public interface AccessibilityPointerMotionFilter {
        /**
         * Called everytime pointer's relative motion event happens.
         * The returned dx and dy will be used to move the cursor in the display.
         * <p>
         * This call happens on the input hot path and it is extremely performance sensitive. It
         * also must not call back into native code.
         *
         * @param dx        delta x of the event in pixels.
         * @param dy        delta y of the event in pixels.
         * @param currentX  the cursor x coordinate on the screen before the motion event.
         * @param currentY  the cursor y coordinate on the screen before the motion event.
         * @param displayId the display ID of the current cursor.
         * @return an array of length 2, delta x and delta y after filtering the motion. The delta
         *         values are in pixels and must be between 0 and original delta.
         */
        @NonNull
        float[] filterPointerMotionEvent(float dx, float dy, float currentX, float currentY,
                int displayId);
    }

    /**
     * Registers an {@code AccessibilityCursorFilter}.
     *
     * @param filter The filter to register. If a filter is already registered, the old filter is
     *               unregistered. {@code null} unregisters the filter that is already registered.
     */
    public abstract void registerAccessibilityPointerMotionFilter(
            @Nullable AccessibilityPointerMotionFilter filter);
}
