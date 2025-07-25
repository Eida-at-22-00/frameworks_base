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

package com.android.systemui.statusbar.notification.stack

import android.content.applicationContext
import com.android.systemui.dump.dumpManager
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.shade.transition.largeScreenShadeInterpolator
import com.android.systemui.statusbar.notification.headsup.mockAvalancheController
import com.android.systemui.statusbar.notification.stack.data.repository.headsUpNotificationRepository
import com.android.systemui.statusbar.phone.statusBarKeyguardViewManager

val Kosmos.ambientState by Fixture {
    AmbientState(
        /*context=*/ applicationContext,
        /*dumpManager=*/ dumpManager,
        /*sectionProvider=*/ stackScrollAlgorithmSectionProvider,
        /*bypassController=*/ stackScrollAlgorithmBypassController,
        /*statusBarKeyguardViewManager=*/ statusBarKeyguardViewManager,
        /*largeScreenShadeInterpolator=*/ largeScreenShadeInterpolator,
        /*headsUpRepository=*/ headsUpNotificationRepository,
        /*avalancheController=*/ mockAvalancheController,
    )
}
