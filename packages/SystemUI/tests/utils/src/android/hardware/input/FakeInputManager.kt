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

package android.hardware.input

import android.hardware.input.InputGestureData.Trigger
import android.hardware.input.InputManager.CUSTOM_INPUT_GESTURE_RESULT_ERROR_ALREADY_EXISTS
import android.hardware.input.InputManager.CUSTOM_INPUT_GESTURE_RESULT_ERROR_DOES_NOT_EXIST
import android.hardware.input.InputManager.CUSTOM_INPUT_GESTURE_RESULT_SUCCESS
import android.hardware.input.InputManager.InputDeviceListener
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyCharacterMap.VIRTUAL_KEYBOARD
import android.view.KeyEvent
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.any
import org.mockito.kotlin.mock

class FakeInputManager {

    private val keyCharacterMap = KeyCharacterMap.load(VIRTUAL_KEYBOARD)

    private val virtualKeyboard =
        InputDevice.Builder()
            .setId(VIRTUAL_KEYBOARD)
            .setKeyboardType(InputDevice.KEYBOARD_TYPE_ALPHABETIC)
            .setSources(InputDevice.SOURCE_KEYBOARD)
            .setEnabled(true)
            .setKeyCharacterMap(keyCharacterMap)
            .build()

    private val devices = mutableMapOf<Int, InputDevice>(VIRTUAL_KEYBOARD to virtualKeyboard)
    private val allKeyCodes = (0..KeyEvent.MAX_KEYCODE)
    private val supportedKeyCodesByDeviceId =
        mutableMapOf(
            // Mark all keys supported by default
            VIRTUAL_KEYBOARD to allKeyCodes.toMutableSet()
        )

    private var inputDeviceListener: InputDeviceListener? = null
    private val customInputGestures: MutableMap<Trigger, InputGestureData> = mutableMapOf()
    var addCustomInputGestureErrorCode = CUSTOM_INPUT_GESTURE_RESULT_ERROR_ALREADY_EXISTS

    val inputManager: InputManager = mock {
        on { getCustomInputGestures(any()) }.then { customInputGestures.values.toList() }

        on { addCustomInputGesture(any()) }
            .then {
                val inputGestureData = it.getArgument<InputGestureData>(0)
                val trigger = inputGestureData.trigger

                if (customInputGestures.containsKey(trigger)) {
                    addCustomInputGestureErrorCode
                } else {
                    customInputGestures[trigger] = inputGestureData
                    CUSTOM_INPUT_GESTURE_RESULT_SUCCESS
                }
            }

        on { removeCustomInputGesture(any()) }
            .then {
                val inputGestureData = it.getArgument<InputGestureData>(0)
                val trigger = inputGestureData.trigger

                if (customInputGestures.containsKey(trigger)) {
                    customInputGestures.remove(trigger)
                    CUSTOM_INPUT_GESTURE_RESULT_SUCCESS
                } else {
                    CUSTOM_INPUT_GESTURE_RESULT_ERROR_DOES_NOT_EXIST
                }
            }

        on { removeAllCustomInputGestures(any()) }.then { customInputGestures.clear() }

        on { getInputGesture(any()) }
            .then {
                val trigger = it.getArgument<Trigger>(0)
                customInputGestures[trigger]
            }

        on { getInputDevice(anyInt()) }
            .thenAnswer { invocation ->
                val deviceId = invocation.arguments[0] as Int
                return@thenAnswer devices[deviceId]
            }
        on { inputDeviceIds }
            .thenAnswer {
                return@thenAnswer devices.keys.toIntArray()
            }

        fun setDeviceEnabled(invocation: InvocationOnMock, enabled: Boolean) {
            val deviceId = invocation.arguments[0] as Int
            val device = devices[deviceId] ?: return
            devices[deviceId] = device.copy(enabled = enabled)
        }

        on { disableInputDevice(anyInt()) }
            .thenAnswer { invocation -> setDeviceEnabled(invocation, enabled = false) }
        on { enableInputDevice(anyInt()) }
            .thenAnswer { invocation -> setDeviceEnabled(invocation, enabled = true) }
        on { deviceHasKeys(any(), any()) }
            .thenAnswer { invocation ->
                val deviceId = invocation.arguments[0] as Int
                val keyCodes = invocation.arguments[1] as IntArray
                val supportedKeyCodes = supportedKeyCodesByDeviceId[deviceId]!!
                return@thenAnswer keyCodes.map { supportedKeyCodes.contains(it) }.toBooleanArray()
            }
    }

    fun resetCustomInputGestures() {
        customInputGestures.clear()
        addCustomInputGestureErrorCode = CUSTOM_INPUT_GESTURE_RESULT_ERROR_ALREADY_EXISTS
    }

    fun addPhysicalKeyboardIfNotPresent(deviceId: Int, enabled: Boolean = true) {
        if (devices.containsKey(deviceId)) {
            return
        }
        addPhysicalKeyboard(deviceId, enabled = enabled)
    }

    fun registerInputDeviceListener(listener: InputDeviceListener) {
        // TODO (b/355422259): handle this by listening to inputManager.registerInputDeviceListener
        inputDeviceListener = listener
    }

    fun addPhysicalKeyboard(
        id: Int,
        vendorId: Int = 0,
        productId: Int = 0,
        isFullKeyboard: Boolean = true,
        enabled: Boolean = true,
    ) {
        check(id > 0) { "Physical keyboard ids have to be > 0" }
        addKeyboard(id, vendorId, productId, isFullKeyboard, enabled)
    }

    fun removeKeysFromKeyboard(deviceId: Int, vararg keyCodes: Int) {
        addPhysicalKeyboardIfNotPresent(deviceId)
        supportedKeyCodesByDeviceId[deviceId]!!.removeAll(keyCodes.asList())
    }

    private fun addKeyboard(
        id: Int,
        vendorId: Int = 0,
        productId: Int = 0,
        isFullKeyboard: Boolean = true,
        enabled: Boolean = true,
    ) {
        val keyboardType =
            if (isFullKeyboard) InputDevice.KEYBOARD_TYPE_ALPHABETIC
            else InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC
        // VendorId and productId are set to 0 if not specified, which is the same as the default
        // values used in InputDevice.Builder
        val builder =
            InputDevice.Builder()
                .setId(id)
                .setVendorId(vendorId)
                .setProductId(productId)
                .setKeyboardType(keyboardType)
                .setSources(InputDevice.SOURCE_KEYBOARD)
                .setEnabled(enabled)
                .setKeyCharacterMap(keyCharacterMap)
        devices[id] = builder.build()
        inputDeviceListener?.onInputDeviceAdded(id)
        supportedKeyCodesByDeviceId[id] = allKeyCodes.toMutableSet()
    }

    fun addDevice(id: Int, sources: Int, isNotFound: Boolean = false) {
        // there's not way of differentiate device connection vs registry in current implementation.
        // If the device isNotFound, it means that we connect an unregistered device.
        if (!isNotFound) {
            devices[id] = InputDevice.Builder().setId(id).setSources(sources).build()
        }
        inputDeviceListener?.onInputDeviceAdded(id)
    }

    fun removeDevice(id: Int) {
        devices.remove(id)
        inputDeviceListener?.onInputDeviceRemoved(id)
    }

    private fun InputDevice.copy(
        id: Int = getId(),
        type: Int = keyboardType,
        sources: Int = getSources(),
        enabled: Boolean = isEnabled,
    ) =
        InputDevice.Builder()
            .setId(id)
            .setKeyboardType(type)
            .setSources(sources)
            .setEnabled(enabled)
            .build()
}
