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

package com.android.systemui.qs.tiles.impl.notes.ui.mapper

import android.content.res.Resources
import android.widget.Button
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig
import com.android.systemui.qs.tiles.base.shared.model.QSTileState
import com.android.systemui.qs.tiles.base.ui.model.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.notes.domain.model.NotesTileModel
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import javax.inject.Inject

class NotesTileMapper
@Inject
constructor(
    @ShadeDisplayAware private val resources: Resources,
    private val theme: Resources.Theme,
) : QSTileDataToStateMapper<NotesTileModel> {
    override fun map(config: QSTileConfig, data: NotesTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            val iconRes = R.drawable.ic_qs_notes
            icon = Icon.Loaded(resources.getDrawable(iconRes, theme), null, iconRes)
            contentDescription = label
            activationState = QSTileState.ActivationState.INACTIVE
            sideViewIcon = QSTileState.SideViewIcon.Chevron
            supportedActions =
                setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
            expandedAccessibilityClass = Button::class
        }
}
