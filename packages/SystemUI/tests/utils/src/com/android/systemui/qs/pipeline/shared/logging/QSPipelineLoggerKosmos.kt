/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.pipeline.shared.logging

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.log.logcatLogBuffer

/** mock */
var Kosmos.qsLogger: QSPipelineLogger by
    Kosmos.Fixture {
        QSPipelineLogger(
            logcatLogBuffer(QSPipelineLogger.TILE_LIST_TAG),
            logcatLogBuffer(QSPipelineLogger.AUTO_ADD_TAG),
            logcatLogBuffer(QSPipelineLogger.RESTORE_TAG),
        )
    }
