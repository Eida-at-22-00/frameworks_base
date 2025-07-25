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

package com.android.systemui.shade.shared.flag

import android.window.DesktopExperienceFlags
import com.android.systemui.Flags
import com.android.systemui.flags.FlagToken
import com.android.systemui.flags.RefactorFlagUtils

/** Helper for reading or using the shade window goes around flag state. */
@Suppress("NOTHING_TO_INLINE")
object ShadeWindowGoesAround {
    /** The aconfig flag name */
    const val FLAG_NAME = Flags.FLAG_SHADE_WINDOW_GOES_AROUND

    /** A token used for dependency declaration */
    val token: FlagToken
        get() = FlagToken(FLAG_NAME, isEnabled)

    /**
     * This is defined as [DesktopExperienceFlags] to make it possible to enable it together with
     * all the other desktop experience flags from the dev settings.
     *
     * Alternatively, using adb:
     * ```bash
     * adb shell aflags enable com.android.window.flags.show_desktop_experience_dev_option && \
     *   adb shell setprop persist.wm.debug.desktop_experience_devopts 1
     * ```
     */
    val FLAG =
        DesktopExperienceFlags.DesktopExperienceFlag(
            Flags::shadeWindowGoesAround,
            /* shouldOverrideByDevOption= */ true,
        )

    /** Is the refactor enabled */
    @JvmStatic
    inline val isEnabled: Boolean
        get() = FLAG.isTrue

    /**
     * Called to ensure code is only run when the flag is enabled. This protects users from the
     * unintended behaviors caused by accidentally running new logic, while also crashing on an eng
     * build to ensure that the refactor author catches issues in testing.
     */
    @JvmStatic
    inline fun isUnexpectedlyInLegacyMode() =
        RefactorFlagUtils.isUnexpectedlyInLegacyMode(isEnabled, FLAG_NAME)

    /**
     * Called to ensure code is only run when the flag is enabled. This will throw an exception if
     * the flag is not enabled to ensure that the refactor author catches issues in testing.
     * Caution!! Using this check incorrectly will cause crashes in nextfood builds!
     */
    @JvmStatic
    @Deprecated("Avoid crashing.", ReplaceWith("if (this.isUnexpectedlyInLegacyMode()) return"))
    inline fun unsafeAssertInNewMode() =
        RefactorFlagUtils.unsafeAssertInNewMode(isEnabled, FLAG_NAME)

    /**
     * Called to ensure code is only run when the flag is disabled. This will throw an exception if
     * the flag is enabled to ensure that the refactor author catches issues in testing.
     */
    @JvmStatic
    inline fun assertInLegacyMode() = RefactorFlagUtils.assertInLegacyMode(isEnabled, FLAG_NAME)
}
