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

package com.android.settingslib

import android.content.Context
import androidx.preference.Preference
import com.android.settingslib.metadata.PreferenceMetadata
import com.android.settingslib.preference.PreferenceBinding

/** Preference binding for [PrimarySwitchPreference]. */
interface PrimarySwitchPreferenceBinding : PreferenceBinding {

    override fun createWidget(context: Context): Preference = PrimarySwitchPreference(context)

    override fun bind(preference: Preference, metadata: PreferenceMetadata) {
        super.bind(preference, metadata)
        // Could bind on PreferenceScreen
        (preference as? PrimarySwitchPreference)?.apply {
            isChecked = preferenceDataStore!!.getBoolean(key, false)
            isSwitchEnabled = isEnabled
        }
    }
}
