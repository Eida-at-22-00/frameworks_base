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

package com.android.systemui.display.data.repository

import android.util.Log
import android.view.Display
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.qualifiers.Background
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope

/** Provides per display instances of [T]. */
@Deprecated("Use PerDisplayInstanceProvider<T> instead")
interface PerDisplayStore<T> {

    /**
     * The instance for the default/main display of the device. For example, on a phone or a tablet,
     * the default display is the internal/built-in display of the device.
     *
     * Note that the id of the default display is [Display.DEFAULT_DISPLAY].
     */
    val defaultDisplay: T

    /**
     * Returns an instance for a specific display id, or null if [displayId] doesn't match the id of
     * any existing displays.
     */
    fun forDisplay(displayId: Int): T?
}

@Deprecated("Use PerDisplayRepository<T> instead")
abstract class PerDisplayStoreImpl<T>(
    @Background private val backgroundApplicationScope: CoroutineScope,
    private val displayRepository: DisplayRepository,
) : PerDisplayStore<T>, CoreStartable {

    protected val perDisplayInstances = ConcurrentHashMap<Int, T>()

    /**
     * The instance for the default/main display of the device. For example, on a phone or a tablet,
     * the default display is the internal/built-in display of the device.
     *
     * Note that the id of the default display is [Display.DEFAULT_DISPLAY].
     */
    override val defaultDisplay: T
        get() = forDisplay(Display.DEFAULT_DISPLAY)!!

    /**
     * Returns an instance for a specific display id.
     *
     * @throws IllegalArgumentException if [displayId] doesn't match the id of any existing
     *   displays.
     */
    override fun forDisplay(displayId: Int): T? {
        if (displayRepository.getDisplay(displayId)  == null) {
            Log.e(TAG, "<${instanceClass.simpleName}>: Display with id $displayId doesn't exist.")
            return null
        }
        synchronized(perDisplayInstances) {
            val existingInstance = perDisplayInstances[displayId]
            if (existingInstance != null) {
                return existingInstance
            }
            val newInstance = createInstanceForDisplay(displayId)
            if (newInstance == null) {
                Log.e(
                    TAG,
                    "<${instanceClass.simpleName}> returning null because createInstanceForDisplay($displayId) returned null.",
                )
            } else {
                perDisplayInstances[displayId] = newInstance
            }
            return newInstance
        }
    }

    protected abstract fun createInstanceForDisplay(displayId: Int): T?

    override fun start() {
        val instanceType = instanceClass.simpleName
        backgroundApplicationScope.launch("PerDisplayStore#<$instanceType>start") {
            displayRepository.displayRemovalEvent.collect { removedDisplayId ->
                val removedInstance = perDisplayInstances.remove(removedDisplayId)
                removedInstance?.let { onDisplayRemovalAction(it) }
            }
        }
    }

    abstract val instanceClass: Class<T>

    /**
     * Will be called when the display associated with [instance] was removed. It allows to perform
     * any clean up if needed.
     */
    @Deprecated(
        "Use PerDisplayInstanceProviderWithTeardown instead, and let " +
            "PerDisplayInstanceRepositoryImpl decide when to destroy the instance (e.g. on " +
            "display removal or other conditions."
    )
    open suspend fun onDisplayRemovalAction(instance: T) {}

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println(perDisplayInstances)
    }

    private companion object {
        const val TAG = "PerDisplayStore"
    }
}

class SingleDisplayStore<T>(defaultInstance: T) : PerDisplayStore<T> {
    override val defaultDisplay: T = defaultInstance

    override fun forDisplay(displayId: Int): T = defaultDisplay
}
