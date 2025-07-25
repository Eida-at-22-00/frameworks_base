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

package android.view

import android.content.applicationContext
import com.android.systemui.kosmos.Kosmos
import org.mockito.Mockito.mock

val Kosmos.fakeWindowManager by Kosmos.Fixture { FakeWindowManager(applicationContext) }

val Kosmos.mockWindowManager: WindowManager by Kosmos.Fixture { mock(WindowManager::class.java) }

val Kosmos.mockIWindowManager: IWindowManager by Kosmos.Fixture { mock(IWindowManager::class.java) }

var Kosmos.windowManager: WindowManager by Kosmos.Fixture { mockWindowManager }
