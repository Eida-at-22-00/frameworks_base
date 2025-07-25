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

package com.android.systemui.qs.tiles.impl.modes.domain.interactor

import android.content.Context
import android.os.UserHandle
import android.text.TextUtils
import com.android.app.tracing.coroutines.flow.flowName
import com.android.settingslib.notification.modes.ZenMode
import com.android.settingslib.notification.modes.ZenModeDescriptions
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.modes.shared.ModesUi
import com.android.systemui.qs.tiles.base.domain.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.base.domain.model.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.modes.domain.model.ModesDndTileModel
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class ModesDndTileDataInteractor
@Inject
constructor(
    @ShadeDisplayAware val context: Context,
    val zenModeInteractor: ZenModeInteractor,
    @Background val bgDispatcher: CoroutineDispatcher,
) : QSTileDataInteractor<ModesDndTileModel> {

    private val zenModeDescriptions = ZenModeDescriptions(context)

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>,
    ): Flow<ModesDndTileModel> = tileData()

    /**
     * An adapted version of the base class' [tileData] method for use in an old-style tile.
     *
     * TODO(b/299909989): Remove after the transition.
     */
    fun tileData() =
        zenModeInteractor.dndMode
            .filterNotNull()
            .map { dndMode -> buildTileData(dndMode) }
            .flowName("tileData")
            .flowOn(bgDispatcher)
            .distinctUntilChanged()

    fun getCurrentTileModel() = buildTileData(zenModeInteractor.getDndMode())

    private fun buildTileData(dndMode: ZenMode): ModesDndTileModel {
        return ModesDndTileModel(
            isActivated = dndMode.isActive,
            extraStatus = TextUtils.nullIfEmpty(zenModeDescriptions.getTriggerDescription(dndMode)),
        )
    }

    override fun availability(user: UserHandle): Flow<Boolean> =
        flowOf(ModesUi.isEnabled && android.app.Flags.modesUiDndTile())
}
