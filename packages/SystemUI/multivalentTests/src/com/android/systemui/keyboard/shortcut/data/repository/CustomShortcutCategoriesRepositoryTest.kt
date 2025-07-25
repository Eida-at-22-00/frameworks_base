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

package com.android.systemui.keyboard.shortcut.data.repository

import android.content.Context
import android.content.Context.INPUT_SERVICE
import android.hardware.input.AppLaunchData
import android.hardware.input.AppLaunchData.RoleData
import android.hardware.input.InputGestureData
import android.hardware.input.InputGestureData.createKeyTrigger
import android.hardware.input.InputManager.CUSTOM_INPUT_GESTURE_RESULT_ERROR_DOES_NOT_EXIST
import android.hardware.input.InputManager.CUSTOM_INPUT_GESTURE_RESULT_SUCCESS
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_HOME
import android.hardware.input.KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION
import android.hardware.input.fakeInputManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.KeyEvent.KEYCODE_A
import android.view.KeyEvent.KEYCODE_B
import android.view.KeyEvent.KEYCODE_SLASH
import android.view.KeyEvent.META_ALT_ON
import android.view.KeyEvent.META_CAPS_LOCK_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_META_ON
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.hardware.input.Flags.FLAG_ENABLE_CUSTOMIZABLE_INPUT_GESTURES
import com.android.hardware.input.Flags.FLAG_USE_KEY_GESTURE_EVENT_HANDLER
import com.android.systemui.Flags.FLAG_APP_SHORTCUT_REMOVAL_FIX
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyboard.shared.model.ShortcutCustomizationRequestResult
import com.android.systemui.keyboard.shortcut.customShortcutCategoriesRepository
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.ALL_SUPPORTED_MODIFIERS
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.allAppsInputGestureData
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.allAppsShortcutAddRequest
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.allAppsShortcutCategory
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.allAppsShortcutDeleteRequest
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.allCustomizableInputGesturesWithSimpleShortcutCombinations
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.customizableInputGestureWithUnknownKeyGestureType
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.expectedShortcutCategoriesWithSimpleShortcutCombination
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.goHomeInputGestureData
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.launchCalendarShortcutAddRequest
import com.android.systemui.keyboard.shortcut.data.source.TestShortcuts.standardKeyCombination
import com.android.systemui.keyboard.shortcut.shared.model.KeyCombination
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCategoryType
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCustomizationRequestInfo
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutCustomizationRequestInfo.SingleShortcutCustomization
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutKey
import com.android.systemui.keyboard.shortcut.shared.model.shortcutCommand
import com.android.systemui.keyboard.shortcut.shortcutHelperInputDeviceRepository
import com.android.systemui.keyboard.shortcut.shortcutHelperTestHelper
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.res.R
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.userTracker
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class CustomShortcutCategoriesRepositoryTest : SysuiTestCase() {

    private val mockUserContext: Context = mock()
    private val kosmos =
        testKosmos().useUnconfinedTestDispatcher().also {
            it.userTracker = FakeUserTracker(onCreateCurrentUserContext = { mockUserContext })
        }

    private val inputManager = kosmos.fakeInputManager.inputManager
    private val testScope = kosmos.testScope
    private val helper = kosmos.shortcutHelperTestHelper
    private val repo = kosmos.customShortcutCategoriesRepository

    @Before
    fun setup() {
        whenever(mockUserContext.getSystemService(INPUT_SERVICE)).thenReturn(inputManager)
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CUSTOMIZABLE_INPUT_GESTURES, FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    fun categories_correctlyConvertsAPIModelsToShortcutHelperModels() {
        testScope.runTest {
            whenever(inputManager.getCustomInputGestures(/* filter= */ anyOrNull()))
                .thenReturn(allCustomizableInputGesturesWithSimpleShortcutCombinations)

            helper.toggle(deviceId = 123)
            val categories by collectLastValue(repo.categories)

            assertThat(categories)
                .containsExactlyElementsIn(expectedShortcutCategoriesWithSimpleShortcutCombination)
        }
    }

    @Test
    @DisableFlags(FLAG_ENABLE_CUSTOMIZABLE_INPUT_GESTURES, FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    fun categories_emitsEmptyListWhenFlagIsDisabled() {
        testScope.runTest {
            whenever(inputManager.getCustomInputGestures(/* filter= */ anyOrNull()))
                .thenReturn(allCustomizableInputGesturesWithSimpleShortcutCombinations)

            helper.toggle(deviceId = 123)
            val categories by collectLastValue(repo.categories)

            assertThat(categories).isEmpty()
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CUSTOMIZABLE_INPUT_GESTURES, FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    fun categories_ignoresUnknownKeyGestureTypes() {
        testScope.runTest {
            whenever(inputManager.getCustomInputGestures(/* filter= */ anyOrNull()))
                .thenReturn(customizableInputGestureWithUnknownKeyGestureType)

            helper.toggle(deviceId = 123)
            val categories by collectLastValue(repo.categories)

            assertThat(categories).isEmpty()
        }
    }

    @Test
    fun pressedKeys_isEmptyByDefault() {
        testScope.runTest {
            val pressedKeys by collectLastValue(repo.pressedKeys)
            assertThat(pressedKeys).isEmpty()

            helper.toggle(deviceId = 123)
            assertThat(pressedKeys).isEmpty()
        }
    }

    @Test
    fun pressedKeys_recognizesAllSupportedModifiers() {
        testScope.runTest {
            helper.toggle(deviceId = 123)
            val pressedKeys by collectLastValue(repo.pressedKeys)
            repo.updateUserKeyCombination(
                KeyCombination(modifiers = ALL_SUPPORTED_MODIFIERS, keyCode = null)
            )

            assertThat(pressedKeys)
                .containsExactly(
                    ShortcutKey.Icon.ResIdIcon(R.drawable.ic_ksh_key_meta),
                    ShortcutKey.Text("Ctrl"),
                    ShortcutKey.Text("Fn"),
                    ShortcutKey.Text("Shift"),
                    ShortcutKey.Text("Alt"),
                    ShortcutKey.Text("Sym"),
                )
        }
    }

    @Test
    fun pressedKeys_ignoresUnsupportedModifiers() {
        testScope.runTest {
            helper.toggle(deviceId = 123)
            val pressedKeys by collectLastValue(repo.pressedKeys)
            repo.updateUserKeyCombination(
                KeyCombination(modifiers = META_CAPS_LOCK_ON, keyCode = null)
            )

            assertThat(pressedKeys).isEmpty()
        }
    }

    @Test
    fun pressedKeys_assertCorrectConversion() {
        testScope.runTest {
            helper.toggle(deviceId = 123)
            val pressedKeys by collectLastValue(repo.pressedKeys)
            repo.updateUserKeyCombination(
                KeyCombination(modifiers = META_META_ON, keyCode = KEYCODE_SLASH)
            )

            assertThat(pressedKeys)
                .containsExactly(
                    ShortcutKey.Icon.ResIdIcon(R.drawable.ic_ksh_key_meta),
                    ShortcutKey.Text("/"),
                )
        }
    }

    @Test
    fun shortcutBeingCustomized_updatedOnCustomizationRequested() {
        testScope.runTest {
            repo.onCustomizationRequested(allAppsShortcutAddRequest)

            val shortcutBeingCustomized = repo.getShortcutBeingCustomized()

            assertThat(shortcutBeingCustomized).isEqualTo(allAppsShortcutAddRequest)
        }
    }

    @Test
    fun buildInputGestureDataForShortcutBeingCustomized_noShortcutBeingCustomized_returnsNull() {
        testScope.runTest {
            helper.toggle(deviceId = 123)
            repo.updateUserKeyCombination(standardKeyCombination)

            val inputGestureData = repo.buildInputGestureDataForShortcutBeingCustomized()

            assertThat(inputGestureData).isNull()
        }
    }

    @Test
    fun buildInputGestureDataForShortcutBeingCustomized_noKeyCombinationSelected_returnsNull() {
        testScope.runTest {
            helper.toggle(deviceId = 123)
            repo.onCustomizationRequested(allAppsShortcutAddRequest)

            val inputGestureData = repo.buildInputGestureDataForShortcutBeingCustomized()

            assertThat(inputGestureData).isNull()
        }
    }

    @Test
    fun buildInputGestureDataForShortcutBeingCustomized_successfullyBuildInputGestureData() {
        testScope.runTest {
            helper.toggle(deviceId = 123)
            repo.onCustomizationRequested(allAppsShortcutAddRequest)
            repo.updateUserKeyCombination(standardKeyCombination)
            val inputGestureData = repo.buildInputGestureDataForShortcutBeingCustomized()

            // using toString as we're testing for only structural equality not referential.
            // inputGestureData is a java class and isEqual Tests for referential equality
            // as well which would cause this assert to fail
            assertThat(inputGestureData.toString()).isEqualTo(allAppsInputGestureData.toString())
        }
    }

    @Test
    fun buildInputGestureDataForAppLaunchShortcut_keyGestureTypeIsTypeLaunchApp() =
        testScope.runTest {
            setApiAppLaunchBookmarks(listOf(simpleInputGestureDataForAppLaunchShortcut()))
            helper.toggle(deviceId = 123)
            repo.onCustomizationRequested(launchCalendarShortcutAddRequest)
            repo.updateUserKeyCombination(standardKeyCombination)

            val inputGestureData = repo.buildInputGestureDataForShortcutBeingCustomized()

            assertThat(inputGestureData?.action?.keyGestureType())
                .isEqualTo(KEY_GESTURE_TYPE_LAUNCH_APPLICATION)
        }

    @Test
    fun buildInputGestureDataForAppLaunchShortcut_appLaunchDataIsAdded() =
        testScope.runTest {
            setApiAppLaunchBookmarks(listOf(simpleInputGestureDataForAppLaunchShortcut()))
            helper.toggle(deviceId = 123)
            repo.onCustomizationRequested(launchCalendarShortcutAddRequest)
            repo.updateUserKeyCombination(standardKeyCombination)

            val inputGestureData = repo.buildInputGestureDataForShortcutBeingCustomized()
            assertThat(inputGestureData?.action?.appLaunchData()).isNotNull()
        }

    @Test
    @EnableFlags(FLAG_ENABLE_CUSTOMIZABLE_INPUT_GESTURES, FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    fun deleteShortcut_successfullyRetrievesGestureDataAndDeletesShortcut() {
        testScope.runTest {
            whenever(inputManager.getCustomInputGestures(anyOrNull()))
                .thenReturn(listOf(allAppsInputGestureData, goHomeInputGestureData))
            whenever(inputManager.removeCustomInputGesture(allAppsInputGestureData))
                .thenReturn(CUSTOM_INPUT_GESTURE_RESULT_SUCCESS)
            helper.toggle(deviceId = 123)

            val result = customizeShortcut(allAppsShortcutDeleteRequest)
            assertThat(result).isEqualTo(ShortcutCustomizationRequestResult.SUCCESS)
        }
    }

    @Test
    @EnableFlags(
        FLAG_ENABLE_CUSTOMIZABLE_INPUT_GESTURES,
        FLAG_USE_KEY_GESTURE_EVENT_HANDLER,
        FLAG_APP_SHORTCUT_REMOVAL_FIX,
    )
    fun removeAppCategoryShortcut_successfullyRetrievesGestureDataAndDeletesTheCorrectShortcut() {
        testScope.runTest {
            // We are collecting this because the flow is a cold flow but we need its value as a
            // stateflow when deleting a custom shortcut.
            // TODO remove when refactoring test - use Fakes Instead. b/405358441
            collectLastValue(kosmos.shortcutHelperInputDeviceRepository.activeInputDevice)
            var customInputGestures = listOf(ctrlAltAShortcut, ctrlAltBShortcut)
            whenever(inputManager.getCustomInputGestures(anyOrNull())).then {
                return@then customInputGestures
            }
            whenever(inputManager.removeCustomInputGesture(any())).then {
                val inputGestureToRemove = it.getArgument<InputGestureData>(0)
                val containsGesture = customInputGestures.contains(inputGestureToRemove)
                customInputGestures = customInputGestures - inputGestureToRemove
                return@then if (containsGesture) CUSTOM_INPUT_GESTURE_RESULT_SUCCESS
                else CUSTOM_INPUT_GESTURE_RESULT_ERROR_DOES_NOT_EXIST
            }
            helper.toggle(deviceId = 123)

            customizeShortcut(customizationRequest = ctrlAltBShortcutDeleteRequest)
            assertThat(customInputGestures).containsExactly(ctrlAltAShortcut)
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CUSTOMIZABLE_INPUT_GESTURES, FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    fun categories_isUpdatedAfterCustomShortcutIsDeleted() {
        testScope.runTest {
            // TODO(b/380445594) refactor tests and move these stubbing to ShortcutHelperTestHelper
            var customInputGestures = listOf(allAppsInputGestureData)
            whenever(inputManager.getCustomInputGestures(anyOrNull())).then {
                return@then customInputGestures
            }
            whenever(inputManager.removeCustomInputGesture(any())).then {
                val inputGestureToRemove = it.getArgument<InputGestureData>(0)
                val containsGesture = customInputGestures.contains(inputGestureToRemove)
                customInputGestures = customInputGestures - inputGestureToRemove
                return@then if (containsGesture) CUSTOM_INPUT_GESTURE_RESULT_SUCCESS
                else CUSTOM_INPUT_GESTURE_RESULT_ERROR_DOES_NOT_EXIST
            }
            val categories by collectLastValue(repo.categories)
            helper.toggle(deviceId = 123)

            customizeShortcut(customizationRequest = allAppsShortcutDeleteRequest)
            assertThat(categories).isEmpty()
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CUSTOMIZABLE_INPUT_GESTURES, FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    fun categories_isUpdatedAfterCustomShortcutIsAdded() {
        testScope.runTest {
            // TODO(b/380445594) refactor tests and move these stubbings to ShortcutHelperTestHelper
            var customInputGestures = listOf<InputGestureData>()
            whenever(inputManager.getCustomInputGestures(anyOrNull())).then {
                return@then customInputGestures
            }
            whenever(inputManager.addCustomInputGesture(any())).then {
                val inputGestureToAdd = it.getArgument<InputGestureData>(0)
                customInputGestures = customInputGestures + inputGestureToAdd
                return@then CUSTOM_INPUT_GESTURE_RESULT_SUCCESS
            }
            val categories by collectLastValue(repo.categories)
            helper.toggle(deviceId = 123)

            customizeShortcut(allAppsShortcutAddRequest, standardKeyCombination)
            assertThat(categories).containsExactly(allAppsShortcutCategory)
        }
    }

    @Test
    @EnableFlags(FLAG_ENABLE_CUSTOMIZABLE_INPUT_GESTURES, FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    fun categories_isUpdatedAfterCustomShortcutsAreReset() {
        testScope.runTest {
            // TODO(b/380445594) refactor tests and move these stubbings to ShortcutHelperTestHelper
            var customInputGestures = listOf(allAppsInputGestureData)
            whenever(inputManager.getCustomInputGestures(anyOrNull())).then {
                return@then customInputGestures
            }
            whenever(
                    inputManager.removeAllCustomInputGestures(
                        /* filter = */ InputGestureData.Filter.KEY
                    )
                )
                .then {
                    customInputGestures = emptyList()
                    return@then CUSTOM_INPUT_GESTURE_RESULT_SUCCESS
                }

            val categories by collectLastValue(repo.categories)
            helper.toggle(deviceId = 123)
            repo.onCustomizationRequested(ShortcutCustomizationRequestInfo.Reset)

            assertThat(categories).containsExactly(allAppsShortcutCategory)
            repo.resetAllCustomShortcuts()
            assertThat(categories).isEmpty()
        }
    }

    @Test
    fun selectedKeyCombinationIsAvailable_whenTriggerIsNotRegisteredInInputManager() =
        testScope.runTest {
            helper.toggle(deviceId = 123)
            repo.onCustomizationRequested(allAppsShortcutAddRequest)
            repo.updateUserKeyCombination(standardKeyCombination)

            assertThat(repo.isSelectedKeyCombinationAvailable()).isTrue()
        }

    @Test
    fun selectedKeyCombinationIsNotAvailable_whenTriggerIsRegisteredInInputManager() =
        testScope.runTest {
            inputManager.addCustomInputGesture(buildInputGestureWithStandardKeyCombination())

            helper.toggle(deviceId = 123)
            repo.onCustomizationRequested(allAppsShortcutAddRequest)
            repo.updateUserKeyCombination(standardKeyCombination)

            assertThat(repo.isSelectedKeyCombinationAvailable()).isFalse()
        }

    private fun setApiAppLaunchBookmarks(appLaunchBookmarks: List<InputGestureData>) {
        whenever(inputManager.appLaunchBookmarks).thenReturn(appLaunchBookmarks)
    }

    private suspend fun customizeShortcut(
        customizationRequest: ShortcutCustomizationRequestInfo,
        keyCombination: KeyCombination? = null,
    ): ShortcutCustomizationRequestResult {
        repo.onCustomizationRequested(customizationRequest)
        repo.updateUserKeyCombination(keyCombination)

        return when (customizationRequest) {
            is SingleShortcutCustomization.Add -> {
                repo.confirmAndSetShortcutCurrentlyBeingCustomized()
            }

            is SingleShortcutCustomization.Delete -> {
                repo.deleteShortcutCurrentlyBeingCustomized()
            }

            else -> {
                ShortcutCustomizationRequestResult.ERROR_OTHER
            }
        }
    }

    private fun buildInputGestureWithStandardKeyCombination() =
        InputGestureData.Builder()
            .setKeyGestureType(KEY_GESTURE_TYPE_HOME)
            .setTrigger(
                createKeyTrigger(
                    /* keycode= */ standardKeyCombination.keyCode!!,
                    /* modifierState= */ standardKeyCombination.modifiers and
                        ALL_SUPPORTED_MODIFIERS,
                )
            )
            .build()

    private fun simpleInputGestureDataForAppLaunchShortcut(
        keyCode: Int = KEYCODE_A,
        modifiers: Int = META_CTRL_ON or META_ALT_ON,
        appLaunchData: AppLaunchData = RoleData("Test role"),
    ): InputGestureData {
        return InputGestureData.Builder()
            .setTrigger(createKeyTrigger(keyCode, modifiers))
            .setAppLaunchData(appLaunchData)
            .build()
    }

    private val ctrlAltAShortcut = simpleInputGestureDataForAppLaunchShortcut()
    private val ctrlAltBShortcut = simpleInputGestureDataForAppLaunchShortcut(keyCode = KEYCODE_B)
    private val ctrlAltBShortcutDeleteRequest =
        SingleShortcutCustomization.Delete(
            categoryType = ShortcutCategoryType.AppCategories,
            subCategoryLabel = context.getString(R.string.keyboard_shortcut_group_applications),
            customShortcutCommand =
                shortcutCommand {
                    key("Ctrl")
                    key("Alt")
                    key("B")
                },
        )
}
