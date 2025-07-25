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

package android.hardware.input;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.companion.virtual.IVirtualDevice;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * A virtual stylus which can be used to inject input into the framework that represents a stylus
 * on a remote device.
 *
 * <p>This registers an {@link android.view.InputDevice} that is interpreted like a
 * physically-connected device and dispatches received events to it.</p>
 *
 * @hide
 */
@SystemApi
public class VirtualStylus extends VirtualInputDevice {
    /** @hide */
    public VirtualStylus(VirtualStylusConfig config, IVirtualDevice virtualDevice,
            IBinder token) {
        super(config, virtualDevice, token);
    }

    /**
     * Sends a motion event to the system.
     *
     * @param event the event to send
     */
    public void sendMotionEvent(@NonNull VirtualStylusMotionEvent event) {
        try {
            if (!mVirtualDevice.sendStylusMotionEvent(mToken, event)) {
                Log.w(TAG, "Failed to send motion event from virtual stylus "
                        + mConfig.getInputDeviceName());
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sends a button event to the system.
     *
     * @param event the event to send
     */
    public void sendButtonEvent(@NonNull VirtualStylusButtonEvent event) {
        try {
            if (!mVirtualDevice.sendStylusButtonEvent(mToken, event)) {
                Log.w(TAG, "Failed to send button event from virtual stylus "
                        + mConfig.getInputDeviceName());
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
