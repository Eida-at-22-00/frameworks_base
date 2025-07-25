/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.provider.settings.validators;

import static android.provider.settings.validators.SettingsValidators.ANY_INTEGER_VALIDATOR;
import static android.provider.settings.validators.SettingsValidators.ANY_STRING_VALIDATOR;
import static android.provider.settings.validators.SettingsValidators.BOOLEAN_VALIDATOR;
import static android.provider.settings.validators.SettingsValidators.COMMA_SEPARATED_PACKAGE_LIST_VALIDATOR_EMPTY;
import static android.provider.settings.validators.SettingsValidators.COMPONENT_NAME_VALIDATOR;
import static android.provider.settings.validators.SettingsValidators.CUSTOM_VIBRATION_PATTERN_VALIDATOR;
import static android.provider.settings.validators.SettingsValidators.FONT_SCALE_VALIDATOR;
import static android.provider.settings.validators.SettingsValidators.LENIENT_IP_ADDRESS_VALIDATOR;
import static android.provider.settings.validators.SettingsValidators.NON_NEGATIVE_FLOAT_VALIDATOR;
import static android.provider.settings.validators.SettingsValidators.NON_NEGATIVE_INTEGER_VALIDATOR;
import static android.provider.settings.validators.SettingsValidators.URI_VALIDATOR;
import static android.provider.settings.validators.SettingsValidators.VIBRATION_INTENSITY_VALIDATOR;
import static android.view.PointerIcon.DEFAULT_POINTER_SCALE;
import static android.view.PointerIcon.LARGE_POINTER_SCALE;
import static android.view.PointerIcon.POINTER_ICON_VECTOR_STYLE_FILL_BEGIN;
import static android.view.PointerIcon.POINTER_ICON_VECTOR_STYLE_FILL_END;
import static android.view.PointerIcon.POINTER_ICON_VECTOR_STYLE_STROKE_BEGIN;
import static android.view.PointerIcon.POINTER_ICON_VECTOR_STYLE_STROKE_END;

import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.hardware.display.ColorDisplayManager;
import android.os.BatteryManager;
import android.provider.Settings.System;
import android.util.ArrayMap;

import java.util.Map;

/**
 * Validators for System settings
 */
public class SystemSettingsValidators {
    @UnsupportedAppUsage
    public static final Map<String, Validator> VALIDATORS = new ArrayMap<>();

    static {
        VALIDATORS.put(
                System.STAY_ON_WHILE_PLUGGED_IN,
                value -> {
                    try {
                        int val = Integer.parseInt(value);
                        return (val == 0)
                                || (val == BatteryManager.BATTERY_PLUGGED_AC)
                                || (val == BatteryManager.BATTERY_PLUGGED_USB)
                                || (val == BatteryManager.BATTERY_PLUGGED_WIRELESS)
                                || (val == BatteryManager.BATTERY_PLUGGED_DOCK)
                                || (val
                                        == (BatteryManager.BATTERY_PLUGGED_AC
                                                | BatteryManager.BATTERY_PLUGGED_USB))
                                || (val
                                        == (BatteryManager.BATTERY_PLUGGED_AC
                                                | BatteryManager.BATTERY_PLUGGED_WIRELESS))
                                || (val
                                        == (BatteryManager.BATTERY_PLUGGED_USB
                                                | BatteryManager.BATTERY_PLUGGED_WIRELESS))
                                || (val
                                        == (BatteryManager.BATTERY_PLUGGED_AC
                                                | BatteryManager.BATTERY_PLUGGED_USB
                                                | BatteryManager.BATTERY_PLUGGED_WIRELESS))
                                || (val
                                        == (BatteryManager.BATTERY_PLUGGED_AC
                                                | BatteryManager.BATTERY_PLUGGED_DOCK))
                                || (val
                                        == (BatteryManager.BATTERY_PLUGGED_USB
                                                | BatteryManager.BATTERY_PLUGGED_DOCK));
                    } catch (NumberFormatException e) {
                        return false;
                    }
                });
        VALIDATORS.put(System.END_BUTTON_BEHAVIOR, new InclusiveIntegerRangeValidator(0, 3));
        VALIDATORS.put(System.WIFI_USE_STATIC_IP, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.BLUETOOTH_DISCOVERABILITY, new InclusiveIntegerRangeValidator(0, 2));
        VALIDATORS.put(System.BLUETOOTH_DISCOVERABILITY_TIMEOUT, NON_NEGATIVE_INTEGER_VALIDATOR);
        VALIDATORS.put(
                System.NEXT_ALARM_FORMATTED,
                new Validator() {
                    private static final int MAX_LENGTH = 1000;

                    @Override
                    public boolean validate(String value) {
                        // TODO: No idea what the correct format is.
                        return value == null || value.length() < MAX_LENGTH;
                    }
                });
        VALIDATORS.put(System.DEFAULT_DEVICE_FONT_SCALE, FONT_SCALE_VALIDATOR);
        VALIDATORS.put(System.FONT_SCALE, FONT_SCALE_VALIDATOR);
        VALIDATORS.put(System.DIM_SCREEN, BOOLEAN_VALIDATOR);
        VALIDATORS.put(
                System.DISPLAY_COLOR_MODE,
                new Validator() {
                    @Override
                    public boolean validate(@Nullable String value) {
                        // Assume the actual validation that this device can properly handle this
                        // kind of
                        // color mode further down in ColorDisplayManager / ColorDisplayService.
                        try {
                            final int setting = Integer.parseInt(value);
                            final boolean isInFrameworkRange =
                                    setting >= ColorDisplayManager.COLOR_MODE_NATURAL
                                            && setting <= ColorDisplayManager.COLOR_MODE_AUTOMATIC;
                            final boolean isInVendorRange =
                                    setting >= ColorDisplayManager.VENDOR_COLOR_MODE_RANGE_MIN
                                            && setting
                                                    <= ColorDisplayManager
                                                            .VENDOR_COLOR_MODE_RANGE_MAX;
                            return isInFrameworkRange || isInVendorRange;
                        } catch (NumberFormatException | NullPointerException e) {
                            return false;
                        }
                    }
                });
        VALIDATORS.put(System.DISPLAY_COLOR_MODE_VENDOR_HINT, ANY_STRING_VALIDATOR);
        VALIDATORS.put(System.SCREEN_OFF_TIMEOUT, NON_NEGATIVE_INTEGER_VALIDATOR);
        VALIDATORS.put(System.SCREEN_BRIGHTNESS_MODE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(
                System.SCREEN_BRIGHTNESS_FOR_ALS,
                new InclusiveIntegerRangeValidator(
                        System.SCREEN_BRIGHTNESS_AUTOMATIC_BRIGHT,
                        System.SCREEN_BRIGHTNESS_AUTOMATIC_DIM));
        VALIDATORS.put(System.ADAPTIVE_SLEEP, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.MODE_RINGER_STREAMS_AFFECTED, NON_NEGATIVE_INTEGER_VALIDATOR);
        VALIDATORS.put(System.MUTE_STREAMS_AFFECTED, NON_NEGATIVE_INTEGER_VALIDATOR);
        VALIDATORS.put(System.VIBRATE_ON, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.APPLY_RAMPING_RINGER, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.ALARM_VIBRATION_INTENSITY, VIBRATION_INTENSITY_VALIDATOR);
        VALIDATORS.put(System.MEDIA_VIBRATION_INTENSITY, VIBRATION_INTENSITY_VALIDATOR);
        VALIDATORS.put(System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_VALIDATOR);
        VALIDATORS.put(System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_VALIDATOR);
        VALIDATORS.put(System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_VALIDATOR);
        VALIDATORS.put(System.HARDWARE_HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_VALIDATOR);
        VALIDATORS.put(System.KEYBOARD_VIBRATION_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.HAPTIC_FEEDBACK_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.RINGTONE, URI_VALIDATOR);
        VALIDATORS.put(System.NOTIFICATION_SOUND, URI_VALIDATOR);
        VALIDATORS.put(System.FOLD_LOCK_BEHAVIOR, ANY_STRING_VALIDATOR);
        VALIDATORS.put(System.ALARM_ALERT, URI_VALIDATOR);
        VALIDATORS.put(System.TEXT_AUTO_REPLACE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.TEXT_AUTO_CAPS, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.TEXT_AUTO_PUNCTUATE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.TEXT_SHOW_PASSWORD, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.AUTO_TIME, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.AUTO_TIME_ZONE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.SHOW_GTALK_SERVICE_STATUS, BOOLEAN_VALIDATOR);
        VALIDATORS.put(
                System.WALLPAPER_ACTIVITY,
                new Validator() {
                    private static final int MAX_LENGTH = 1000;

                    @Override
                    public boolean validate(String value) {
                        if (value != null && value.length() > MAX_LENGTH) {
                            return false;
                        }
                        return ComponentName.unflattenFromString(value) != null;
                    }
                });
        VALIDATORS.put(
                System.TIME_12_24, new DiscreteValueValidator(new String[] {"12", "24", null}));
        VALIDATORS.put(System.SETUP_WIZARD_HAS_RUN, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.ACCELEROMETER_ROTATION, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.USER_ROTATION, new InclusiveIntegerRangeValidator(0, 3));
        VALIDATORS.put(System.DTMF_TONE_WHEN_DIALING, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.SOUND_EFFECTS_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.HAPTIC_FEEDBACK_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.POWER_SOUNDS_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.DOCK_SOUNDS_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.SHOW_WEB_SUGGESTIONS, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.WIFI_USE_STATIC_IP, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.ADVANCED_SETTINGS, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.SCREEN_AUTO_BRIGHTNESS_ADJ, new InclusiveFloatRangeValidator(-1, 1));
        VALIDATORS.put(System.VIBRATE_INPUT_DEVICES, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.MASTER_MONO, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.MASTER_BALANCE, new InclusiveFloatRangeValidator(-1.f, 1.f));
        VALIDATORS.put(System.NOTIFICATIONS_USE_RING_VOLUME, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.VIBRATE_IN_SILENT, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.MEDIA_BUTTON_RECEIVER, COMPONENT_NAME_VALIDATOR);
        VALIDATORS.put(System.HIDE_ROTATION_LOCK_TOGGLE_FOR_ACCESSIBILITY, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.VIBRATE_WHEN_RINGING, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.DTMF_TONE_TYPE_WHEN_DIALING, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.HEARING_AID, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.TTY_MODE, new InclusiveIntegerRangeValidator(0, 3));
        VALIDATORS.put(System.NOTIFICATION_LIGHT_PULSE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.POINTER_LOCATION, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.SHOW_TOUCHES, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.SHOW_KEY_PRESSES, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.TOUCHPAD_VISUALIZER, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.SHOW_ROTARY_INPUT, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.WINDOW_ORIENTATION_LISTENER_LOG, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.LOCKSCREEN_SOUNDS_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.LOCKSCREEN_DISABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.SIP_RECEIVE_CALLS, BOOLEAN_VALIDATOR);
        VALIDATORS.put(
                System.SIP_CALL_OPTIONS,
                new DiscreteValueValidator(new String[] {"SIP_ALWAYS", "SIP_ADDRESS_ONLY"}));
        VALIDATORS.put(System.SIP_ALWAYS, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.SIP_ADDRESS_ONLY, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.SIP_ASK_ME_EACH_TIME, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.POINTER_SPEED, new InclusiveFloatRangeValidator(-7, 7));
        VALIDATORS.put(System.TOUCHPAD_THREE_FINGER_TAP_CUSTOMIZATION,
                NON_NEGATIVE_INTEGER_VALIDATOR);
        VALIDATORS.put(System.POINTER_FILL_STYLE,
                new InclusiveIntegerRangeValidator(POINTER_ICON_VECTOR_STYLE_FILL_BEGIN,
                        POINTER_ICON_VECTOR_STYLE_FILL_END));
        VALIDATORS.put(System.POINTER_STROKE_STYLE,
                new InclusiveIntegerRangeValidator(POINTER_ICON_VECTOR_STYLE_STROKE_BEGIN,
                        POINTER_ICON_VECTOR_STYLE_STROKE_END));
        VALIDATORS.put(System.POINTER_SCALE,
                new InclusiveFloatRangeValidator(DEFAULT_POINTER_SCALE, LARGE_POINTER_SCALE));
        VALIDATORS.put(System.MOUSE_REVERSE_VERTICAL_SCROLLING, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.MOUSE_SWAP_PRIMARY_BUTTON, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.MOUSE_SCROLLING_ACCELERATION, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.MOUSE_POINTER_ACCELERATION_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.MOUSE_SCROLLING_SPEED, new InclusiveIntegerRangeValidator(-7, 7));
        VALIDATORS.put(System.TOUCHPAD_POINTER_SPEED, new InclusiveIntegerRangeValidator(-7, 7));
        VALIDATORS.put(System.TOUCHPAD_NATURAL_SCROLLING, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.TOUCHPAD_TAP_TO_CLICK, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.TOUCHPAD_TAP_DRAGGING, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.TOUCHPAD_RIGHT_CLICK_ZONE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.TOUCHPAD_SYSTEM_GESTURES, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.TOUCHPAD_ACCELERATION_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.LOCK_TO_APP_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(
                System.EGG_MODE,
                new Validator() {
                    @Override
                    public boolean validate(@Nullable String value) {
                        try {
                            return Long.parseLong(value) >= 0;
                        } catch (NumberFormatException e) {
                            return false;
                        }
                    }
                });
        VALIDATORS.put(System.WIFI_STATIC_IP, LENIENT_IP_ADDRESS_VALIDATOR);
        VALIDATORS.put(System.WIFI_STATIC_GATEWAY, LENIENT_IP_ADDRESS_VALIDATOR);
        VALIDATORS.put(System.WIFI_STATIC_NETMASK, LENIENT_IP_ADDRESS_VALIDATOR);
        VALIDATORS.put(System.WIFI_STATIC_DNS1, LENIENT_IP_ADDRESS_VALIDATOR);
        VALIDATORS.put(System.WIFI_STATIC_DNS2, LENIENT_IP_ADDRESS_VALIDATOR);
        VALIDATORS.put(System.FORCE_FULLSCREEN_CUTOUT_APPS, ANY_STRING_VALIDATOR);
        VALIDATORS.put(System.SHOW_BATTERY_PERCENT, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.SHOW_BATTERY_PERCENT_CHARGING, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.NOTIFICATION_LIGHT_PULSE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.WEAR_ACCESSIBILITY_GESTURE_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.WEAR_ACCESSIBILITY_GESTURE_ENABLED_DURING_OOBE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.WEAR_TTS_PREWARM_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.CLOCKWORK_BLUETOOTH_SETTINGS_PREF, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.UNREAD_NOTIFICATION_DOT_INDICATOR, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.AUTO_LAUNCH_MEDIA_CONTROLS, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.LOCALE_PREFERENCES, ANY_STRING_VALIDATOR);
        VALIDATORS.put(System.CAMERA_FLASH_NOTIFICATION, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.SCREEN_FLASH_NOTIFICATION, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.SCREEN_FLASH_NOTIFICATION_COLOR, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(System.PEAK_REFRESH_RATE, NON_NEGATIVE_FLOAT_VALIDATOR);
        VALIDATORS.put(System.MIN_REFRESH_RATE, NON_NEGATIVE_FLOAT_VALIDATOR);
        VALIDATORS.put(System.NOTIFICATION_COOLDOWN_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.NOTIFICATION_COOLDOWN_ALL, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.NOTIFICATION_COOLDOWN_VIBRATE_UNLOCKED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.PREFERRED_REGION, ANY_STRING_VALIDATOR);
        VALIDATORS.put(System.CV_ENABLED,
                new InclusiveIntegerRangeValidator(0, 1));
        VALIDATORS.put(System.OMNI_ADVANCED_REBOOT, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.QS_FOOTER_TEXT_SHOW, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.QS_FOOTER_TEXT_STRING, ANY_STRING_VALIDATOR);
        VALIDATORS.put(System.LOCKSCREEN_BATTERY_INFO, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.NETWORK_TRAFFIC_STATE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.NETWORK_TRAFFIC_TYPE, new InclusiveIntegerRangeValidator(0, 4));
        VALIDATORS.put(System.NETWORK_TRAFFIC_AUTOHIDE_THRESHOLD, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(System.NETWORK_TRAFFIC_ARROW, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.NETWORK_TRAFFIC_FONT_SIZE, NON_NEGATIVE_INTEGER_VALIDATOR);
        VALIDATORS.put(System.NETWORK_TRAFFIC_TEXT_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.NETWORK_TRAFFIC_VIEW_LOCATION, new InclusiveIntegerRangeValidator(0, 2));
        VALIDATORS.put(System.GAMING_MODE_HEADS_UP, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.GAMING_MODE_ZEN, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.GAMING_MODE_RINGER, new InclusiveIntegerRangeValidator(0, 2));
        VALIDATORS.put(System.GAMING_MODE_COLOR_MODE, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(System.GAMING_MODE_NAVBAR, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.GAMING_MODE_HW_BUTTONS, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.GAMING_MODE_NIGHT_LIGHT, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.GAMING_MODE_BATTERY_SCHEDULE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.GAMING_MODE_POWER, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.GAMING_MODE_BLUETOOTH, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.GAMING_MODE_EXTRA_DIM, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.GAMING_MODE_BRIGHTNESS_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.GAMING_MODE_BRIGHTNESS, new InclusiveIntegerRangeValidator(0, 100));
        VALIDATORS.put(System.GAMING_MODE_MEDIA_ENABLED, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.GAMING_MODE_MEDIA, new InclusiveIntegerRangeValidator(0, 100));
        VALIDATORS.put(System.GAMING_MODE_SCREEN_OFF, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.GAMING_MODE_BATTERY_SAVER_DISABLES, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.GAMING_MODE_THREE_FINGER, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.GAMING_MODE_TOUCH_SENSITIVITY, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.GAMING_MODE_HIGH_TOUCH_RATE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.GAMING_MODE_APPS, COMMA_SEPARATED_PACKAGE_LIST_VALIDATOR_EMPTY);
        VALIDATORS.put(System.NOTIFICATION_HEADERS, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.RINGTONE_VIBRATION_PATTERN, new InclusiveIntegerRangeValidator(0, 5));
        VALIDATORS.put(System.CUSTOM_RINGTONE_VIBRATION_PATTERN, CUSTOM_VIBRATION_PATTERN_VALIDATOR);
        VALIDATORS.put(System.VIBRATE_ON_CONNECT, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.VIBRATE_ON_CALLWAITING, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.VIBRATE_ON_DISCONNECT, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.TORCH_POWER_BUTTON_GESTURE, new InclusiveIntegerRangeValidator(0, 2));
        VALIDATORS.put(System.DOUBLE_TAP_SLEEP_LOCKSCREEN, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.DOUBLE_TAP_SLEEP_GESTURE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.VOLUME_BUTTON_MUSIC_CONTROL, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.VOLUME_BUTTON_MUSIC_CONTROL_DELAY, new InclusiveIntegerRangeValidator(300, 2000));
        VALIDATORS.put(System.QS_SHOW_BATTERY_ESTIMATE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.ENABLE_FLOATING_ROTATION_BUTTON, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.NAVIGATION_BAR_INVERSE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.NAVBAR_LAYOUT_MODE, new InclusiveIntegerRangeValidator(0, 3));
        VALIDATORS.put(System.VOLUME_KEY_CURSOR_CONTROL, new InclusiveIntegerRangeValidator(0, 2));
        VALIDATORS.put(System.STATUS_BAR_BATTERY_STYLE, new InclusiveIntegerRangeValidator(0, 4));
        VALIDATORS.put(System.SHOW_BATTERY_PERCENT_INSIDE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.VOLUME_BUTTON_QUICK_MUTE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.VOLUME_BUTTON_QUICK_MUTE_DELAY, new InclusiveIntegerRangeValidator(300, 1500));
        VALIDATORS.put(System.BACK_GESTURE_HEIGHT, new InclusiveIntegerRangeValidator(0, 5));
        VALIDATORS.put(System.STATUSBAR_CLOCK_POSITION, new InclusiveIntegerRangeValidator(0, 2));
        VALIDATORS.put(System.NOTIFICATION_VIBRATION_PATTERN, new InclusiveIntegerRangeValidator(0, 5));
        VALIDATORS.put(System.CUSTOM_NOTIFICATION_VIBRATION_PATTERN, CUSTOM_VIBRATION_PATTERN_VALIDATOR);
        VALIDATORS.put(System.FLASHLIGHT_ON_CALL, new InclusiveIntegerRangeValidator(0, 4));
        VALIDATORS.put(System.FLASHLIGHT_ON_CALL_IGNORE_DND, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.FLASHLIGHT_ON_CALL_RATE, new InclusiveIntegerRangeValidator(1, 5));
        VALIDATORS.put(System.VOLUME_PANEL_ON_LEFT, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.VOLUME_PANEL_ON_LEFT_LAND, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.MAX_CALL_VOLUME, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(System.MAX_MUSIC_VOLUME, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(System.MAX_ALARM_VOLUME, ANY_INTEGER_VALIDATOR);
        VALIDATORS.put(System.LOCKSCREEN_WEATHER_PROVIDER, new InclusiveIntegerRangeValidator(0, 2));
        VALIDATORS.put(System.LOCKSCREEN_WEATHER_LOCATION, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.LOCKSCREEN_WEATHER_TEXT, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.LOCKSCREEN_WEATHER_CLICK_UPDATES, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.QS_WIFI_AUTO_ON, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.QS_BT_AUTO_ON, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.BRIGHTNESS_SLIDER_HAPTICS, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.VOLUME_PANEL_HAPTICS, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.THREE_FINGER_GESTURE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.NAVBAR_LONG_PRESS_GESTURE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.RAMPING_RINGER_DURATION, new InclusiveIntegerRangeValidator(2, 20));
        VALIDATORS.put(System.RAMPING_RINGER_START_VOLUME, new InclusiveIntegerRangeValidator(0, 90));
        VALIDATORS.put(System.RAMPING_RINGER_NO_SILENCE, BOOLEAN_VALIDATOR);
        VALIDATORS.put(System.NOTIFICATION_SOUND_VIB_SCREEN_ON, BOOLEAN_VALIDATOR);
    }
}
