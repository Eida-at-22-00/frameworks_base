/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.shared.clocks

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.PluginLifecycleManager
import com.android.systemui.plugins.PluginListener
import com.android.systemui.plugins.PluginManager
import com.android.systemui.plugins.clocks.ClockAxisStyle
import com.android.systemui.plugins.clocks.ClockController
import com.android.systemui.plugins.clocks.ClockId
import com.android.systemui.plugins.clocks.ClockMessageBuffers
import com.android.systemui.plugins.clocks.ClockMetadata
import com.android.systemui.plugins.clocks.ClockPickerConfig
import com.android.systemui.plugins.clocks.ClockProviderPlugin
import com.android.systemui.plugins.clocks.ClockSettings
import com.android.systemui.util.ThreadAssert
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.eq
import java.util.function.BiConsumer
import junit.framework.Assert.assertEquals
import junit.framework.Assert.fail
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidJUnit4::class)
@SmallTest
class ClockRegistryTest : SysuiTestCase() {

    @JvmField @Rule val mockito = MockitoJUnit.rule()
    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: CoroutineDispatcher
    private lateinit var scope: TestScope

    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var mockPluginManager: PluginManager
    @Mock private lateinit var mockClock: ClockController
    @Mock private lateinit var mockDefaultClock: ClockController
    @Mock private lateinit var mockThumbnail: Drawable
    @Mock private lateinit var mockContentResolver: ContentResolver
    @Mock private lateinit var mockThreadAssert: ThreadAssert
    private lateinit var fakeDefaultProvider: FakeClockPlugin
    private lateinit var pluginListener: PluginListener<ClockProviderPlugin>
    private lateinit var registry: ClockRegistry
    private lateinit var pickerConfig: ClockPickerConfig

    companion object {
        private fun failFactory(clockId: ClockId): ClockController {
            fail("Unexpected call to createClock: $clockId")
            return null!!
        }

        private fun failPickerConfig(settings: ClockSettings): ClockPickerConfig {
            fail("Unexpected call to getClockPickerConfig: ${settings.clockId}")
            return null!!
        }
    }

    private class FakeLifecycle(private val tag: String, private val plugin: ClockProviderPlugin?) :
        PluginLifecycleManager<ClockProviderPlugin> {
        var onLoad: (() -> Unit)? = null
        var onUnload: (() -> Unit)? = null

        private var mIsLoaded: Boolean = true

        override fun isLoaded() = mIsLoaded

        override fun getPlugin(): ClockProviderPlugin? = if (isLoaded) plugin else null

        var mComponentName = ComponentName("Package[$tag]", "Class[$tag]")

        override fun toString() = "Manager[$tag]"

        override fun getPackage(): String = mComponentName.getPackageName()

        override fun getComponentName(): ComponentName = mComponentName

        override fun setLogFunc(func: BiConsumer<String, String>) {}

        override fun loadPlugin() {
            if (!mIsLoaded) {
                mIsLoaded = true
                onLoad?.invoke()
            }
        }

        override fun unloadPlugin() {
            if (mIsLoaded) {
                mIsLoaded = false
                onUnload?.invoke()
            }
        }
    }

    private class FakeClockPlugin : ClockProviderPlugin {
        private val metadata = mutableListOf<ClockMetadata>()
        private val createCallbacks = mutableMapOf<ClockId, (ClockId) -> ClockController>()
        private val pickerConfigs = mutableMapOf<ClockId, (ClockSettings) -> ClockPickerConfig>()

        override fun getClocks() = metadata

        override fun createClock(settings: ClockSettings): ClockController {
            val clockId = settings.clockId ?: throw IllegalArgumentException("No clockId specified")
            return createCallbacks[clockId]?.invoke(clockId)
                ?: throw NotImplementedError("No callback for '$clockId'")
        }

        override fun getClockPickerConfig(settings: ClockSettings): ClockPickerConfig {
            return pickerConfigs[settings.clockId]?.invoke(settings)
                ?: throw NotImplementedError("No picker config for '${settings.clockId}'")
        }

        override fun initialize(buffers: ClockMessageBuffers?) {}

        fun addClock(
            id: ClockId,
            create: (ClockId) -> ClockController = ::failFactory,
            getPickerConfig: (ClockSettings) -> ClockPickerConfig = ::failPickerConfig,
        ): FakeClockPlugin {
            return addClock(ClockMetadata(id), create, getPickerConfig)
        }

        fun addClock(
            metadata: ClockMetadata,
            create: (ClockId) -> ClockController = ::failFactory,
            getPickerConfig: (ClockSettings) -> ClockPickerConfig = ::failPickerConfig,
        ): FakeClockPlugin {
            this.metadata.add(metadata)
            createCallbacks[metadata.clockId] = create
            pickerConfigs[metadata.clockId] = getPickerConfig
            return this
        }
    }

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        dispatcher = StandardTestDispatcher(scheduler)
        scope = TestScope(dispatcher)
        pickerConfig = ClockPickerConfig("CLOCK_ID", "NAME", "DESC", mockThumbnail)

        fakeDefaultProvider =
            FakeClockPlugin().addClock(DEFAULT_CLOCK_ID, { mockDefaultClock }, { pickerConfig })
        whenever(mockContext.contentResolver).thenReturn(mockContentResolver)

        val captor = argumentCaptor<PluginListener<ClockProviderPlugin>>()
        registry =
            object :
                ClockRegistry(
                    mockContext,
                    mockPluginManager,
                    scope = scope.backgroundScope,
                    mainDispatcher = dispatcher,
                    bgDispatcher = dispatcher,
                    isEnabled = true,
                    handleAllUsers = true,
                    defaultClockProvider = fakeDefaultProvider,
                    keepAllLoaded = false,
                    subTag = "Test",
                    assert = mockThreadAssert,
                ) {
                override fun querySettings() {}

                override fun applySettings(value: ClockSettings?) {
                    settings = value
                }
            }
        registry.registerListeners()

        verify(mockPluginManager)
            .addPluginListener(captor.capture(), eq(ClockProviderPlugin::class.java), eq(true))
        pluginListener = captor.value
    }

    @Test
    fun pluginRegistration_CorrectState() {
        val plugin1 = FakeClockPlugin().addClock("clock_1").addClock("clock_2")
        val lifecycle1 = FakeLifecycle("1", plugin1)

        val plugin2 =
            FakeClockPlugin()
                .addClock(ClockMetadata("clock_3", isDeprecated = false))
                .addClock(ClockMetadata("clock_4", isDeprecated = true))
        val lifecycle2 = FakeLifecycle("2", plugin2)

        pluginListener.onPluginLoaded(plugin1, mockContext, lifecycle1)
        pluginListener.onPluginLoaded(plugin2, mockContext, lifecycle2)
        assertEquals(
            setOf(
                ClockMetadata(DEFAULT_CLOCK_ID),
                ClockMetadata("clock_1"),
                ClockMetadata("clock_2"),
                ClockMetadata("clock_3"),
            ),
            registry.getClocks().toSet(),
        )

        assertEquals(
            setOf(
                ClockMetadata(DEFAULT_CLOCK_ID),
                ClockMetadata("clock_1"),
                ClockMetadata("clock_2"),
                ClockMetadata("clock_3"),
                ClockMetadata("clock_4", isDeprecated = true),
            ),
            registry.getClocks(includeDeprecated = true).toSet(),
        )
    }

    @Test
    fun noPlugins_createDefaultClock() {
        val clock = registry.createCurrentClock()
        assertEquals(mockDefaultClock, clock)
    }

    @Test
    fun clockIdConflict_ErrorWithoutCrash_unloadDuplicate() {
        val plugin1 =
            FakeClockPlugin()
                .addClock("clock_1", { mockClock }, { pickerConfig })
                .addClock("clock_2", { mockClock }, { pickerConfig })
        val lifecycle1 = spy(FakeLifecycle("1", plugin1))

        val plugin2 = FakeClockPlugin().addClock("clock_1").addClock("clock_2")
        val lifecycle2 = spy(FakeLifecycle("2", plugin2))

        pluginListener.onPluginLoaded(plugin1, mockContext, lifecycle1)
        pluginListener.onPluginLoaded(plugin2, mockContext, lifecycle2)
        val list = registry.getClocks()
        assertEquals(
            setOf(
                ClockMetadata(DEFAULT_CLOCK_ID),
                ClockMetadata("clock_1"),
                ClockMetadata("clock_2"),
            ),
            list.toSet(),
        )

        assertEquals(mockClock, registry.createExampleClock("clock_1"))
        assertEquals(mockClock, registry.createExampleClock("clock_2"))
        assertEquals(pickerConfig, registry.getClockPickerConfig("clock_1"))
        assertEquals(pickerConfig, registry.getClockPickerConfig("clock_2"))
        verify(lifecycle1, never()).unloadPlugin()
        verify(lifecycle2, times(2)).unloadPlugin()
    }

    @Test
    fun createCurrentClock_pluginConnected() {
        val plugin1 = FakeClockPlugin().addClock("clock_1").addClock("clock_2")
        val lifecycle1 = spy(FakeLifecycle("1", plugin1))

        val plugin2 = FakeClockPlugin().addClock("clock_3", { mockClock }).addClock("clock_4")
        val lifecycle2 = spy(FakeLifecycle("2", plugin2))

        registry.applySettings(ClockSettings("clock_3", null))
        pluginListener.onPluginLoaded(plugin1, mockContext, lifecycle1)
        pluginListener.onPluginLoaded(plugin2, mockContext, lifecycle2)

        val clock = registry.createCurrentClock()
        assertEquals(mockClock, clock)
    }

    @Test
    fun activeClockId_changeAfterPluginConnected() {
        val plugin1 = FakeClockPlugin().addClock("clock_1").addClock("clock_2")
        val lifecycle1 = spy(FakeLifecycle("1", plugin1))

        val plugin2 = FakeClockPlugin().addClock("clock_3", { mockClock }).addClock("clock_4")
        val lifecycle2 = spy(FakeLifecycle("2", plugin2))

        registry.applySettings(ClockSettings("clock_3", null))

        pluginListener.onPluginLoaded(plugin1, mockContext, lifecycle1)
        assertEquals(DEFAULT_CLOCK_ID, registry.activeClockId)

        pluginListener.onPluginLoaded(plugin2, mockContext, lifecycle2)
        assertEquals("clock_3", registry.activeClockId)
    }

    @Test
    fun createDefaultClock_pluginDisconnected() {
        val plugin1 = FakeClockPlugin().addClock("clock_1").addClock("clock_2")
        val lifecycle1 = spy(FakeLifecycle("1", plugin1))

        val plugin2 = FakeClockPlugin().addClock("clock_3").addClock("clock_4")
        val lifecycle2 = spy(FakeLifecycle("2", plugin2))

        registry.applySettings(ClockSettings("clock_3", null))
        pluginListener.onPluginLoaded(plugin1, mockContext, lifecycle1)
        pluginListener.onPluginLoaded(plugin2, mockContext, lifecycle2)
        pluginListener.onPluginUnloaded(plugin2, lifecycle2)

        val clock = registry.createCurrentClock()
        assertEquals(mockDefaultClock, clock)
    }

    @Test
    fun pluginRemoved_clockAndListChanged() {
        val plugin1 = FakeClockPlugin().addClock("clock_1").addClock("clock_2")
        val lifecycle1 = spy(FakeLifecycle("1", plugin1))

        val plugin2 = FakeClockPlugin().addClock("clock_3", { mockClock }).addClock("clock_4")
        val lifecycle2 = spy(FakeLifecycle("2", plugin2))

        var changeCallCount = 0
        var listChangeCallCount = 0
        registry.registerClockChangeListener(
            object : ClockRegistry.ClockChangeListener {
                override fun onCurrentClockChanged() {
                    changeCallCount++
                }

                override fun onAvailableClocksChanged() {
                    listChangeCallCount++
                }
            }
        )

        registry.applySettings(ClockSettings("clock_3", null))
        scheduler.runCurrent()
        assertEquals(1, changeCallCount)
        assertEquals(0, listChangeCallCount)

        pluginListener.onPluginLoaded(plugin1, mockContext, lifecycle1)
        scheduler.runCurrent()
        assertEquals(1, changeCallCount)
        assertEquals(1, listChangeCallCount)

        pluginListener.onPluginLoaded(plugin2, mockContext, lifecycle2)
        scheduler.runCurrent()
        assertEquals(2, changeCallCount)
        assertEquals(2, listChangeCallCount)

        pluginListener.onPluginUnloaded(plugin1, lifecycle1)
        scheduler.runCurrent()
        assertEquals(2, changeCallCount)
        assertEquals(2, listChangeCallCount)

        pluginListener.onPluginUnloaded(plugin2, lifecycle2)
        scheduler.runCurrent()
        assertEquals(3, changeCallCount)
        assertEquals(2, listChangeCallCount)

        pluginListener.onPluginDetached(lifecycle1)
        scheduler.runCurrent()
        assertEquals(3, changeCallCount)
        assertEquals(3, listChangeCallCount)

        pluginListener.onPluginDetached(lifecycle2)
        scheduler.runCurrent()
        assertEquals(3, changeCallCount)
        assertEquals(4, listChangeCallCount)
    }

    @Test
    fun unknownPluginAttached_clockAndListUnchanged_loadRequested() {
        val lifecycle =
            FakeLifecycle("", null).apply {
                mComponentName = ComponentName("some.other.package", "SomeClass")
            }

        var changeCallCount = 0
        var listChangeCallCount = 0
        registry.registerClockChangeListener(
            object : ClockRegistry.ClockChangeListener {
                override fun onCurrentClockChanged() {
                    changeCallCount++
                }

                override fun onAvailableClocksChanged() {
                    listChangeCallCount++
                }
            }
        )

        assertEquals(true, pluginListener.onPluginAttached(lifecycle))
        scheduler.runCurrent()
        assertEquals(0, changeCallCount)
        assertEquals(0, listChangeCallCount)
    }

    @Test
    fun knownPluginAttached_clockAndListChanged_loadedCurrent() {
        val metroLifecycle =
            FakeLifecycle("Metro", null).apply {
                mComponentName = ComponentName("com.android.systemui.clocks.metro", "Metro")
            }
        val bignumLifecycle =
            FakeLifecycle("BigNum", null).apply {
                mComponentName = ComponentName("com.android.systemui.clocks.bignum", "BigNum")
            }
        val calligraphyLifecycle =
            FakeLifecycle("Calligraphy", null).apply {
                mComponentName =
                    ComponentName("com.android.systemui.clocks.calligraphy", "Calligraphy")
            }

        var changeCallCount = 0
        var listChangeCallCount = 0
        registry.registerClockChangeListener(
            object : ClockRegistry.ClockChangeListener {
                override fun onCurrentClockChanged() {
                    changeCallCount++
                }

                override fun onAvailableClocksChanged() {
                    listChangeCallCount++
                }
            }
        )

        registry.applySettings(ClockSettings("DIGITAL_CLOCK_CALLIGRAPHY", null))
        scheduler.runCurrent()
        assertEquals(1, changeCallCount)
        assertEquals(0, listChangeCallCount)

        assertEquals(false, pluginListener.onPluginAttached(metroLifecycle))
        scheduler.runCurrent()
        assertEquals(1, changeCallCount)
        assertEquals(1, listChangeCallCount)

        assertEquals(false, pluginListener.onPluginAttached(bignumLifecycle))
        scheduler.runCurrent()
        assertEquals(1, changeCallCount)
        assertEquals(2, listChangeCallCount)

        // This returns true, but doesn't trigger onCurrentClockChanged yet
        assertEquals(true, pluginListener.onPluginAttached(calligraphyLifecycle))
        scheduler.runCurrent()
        assertEquals(1, changeCallCount)
        assertEquals(3, listChangeCallCount)
    }

    @Test
    fun pluginAddRemove_concurrentModification() {
        val plugin1 = FakeClockPlugin().addClock("clock_1")
        val lifecycle1 = FakeLifecycle("1", plugin1)
        val plugin2 = FakeClockPlugin().addClock("clock_2")
        val lifecycle2 = FakeLifecycle("2", plugin2)
        val plugin3 = FakeClockPlugin().addClock("clock_3")
        val lifecycle3 = FakeLifecycle("3", plugin3)
        val plugin4 = FakeClockPlugin().addClock("clock_4")
        val lifecycle4 = FakeLifecycle("4", plugin4)

        // Set the current clock to the final clock to load
        registry.applySettings(ClockSettings("clock_4", null))
        scheduler.runCurrent()

        // When ClockRegistry attempts to unload a plugin, we at that point decide to load and
        // unload other plugins. This causes ClockRegistry to modify the list of available clock
        // plugins while it is being iterated over. In production this happens as a result of a
        // thread race, instead of synchronously like it does here.
        lifecycle2.onUnload = {
            pluginListener.onPluginDetached(lifecycle1)
            pluginListener.onPluginLoaded(plugin4, mockContext, lifecycle4)
        }

        // Load initial plugins
        pluginListener.onPluginLoaded(plugin1, mockContext, lifecycle1)
        pluginListener.onPluginLoaded(plugin2, mockContext, lifecycle2)
        pluginListener.onPluginLoaded(plugin3, mockContext, lifecycle3)

        // Repeatedly verify the loaded providers to get final state
        registry.verifyLoadedProviders()
        scheduler.runCurrent()
        registry.verifyLoadedProviders()
        scheduler.runCurrent()

        // Verify all plugins were correctly loaded into the registry
        assertEquals(
            setOf(
                ClockMetadata("DEFAULT"),
                ClockMetadata("clock_2"),
                ClockMetadata("clock_3"),
                ClockMetadata("clock_4"),
            ),
            registry.getClocks().toSet(),
        )
    }

    @Test
    fun jsonDeserialization() {
        val expected = ClockSettings("ID").apply { metadata.put("appliedTimestamp", 50) }
        val json = JSONObject("""{"clockId":"ID", "metadata": { "appliedTimestamp":50 } }""")
        val actual = ClockSettings.fromJson(json)
        assertEquals(expected, actual)
    }

    @Test
    fun jsonDeserialization_noTimestamp() {
        val expected = ClockSettings("ID")
        val actual = ClockSettings.fromJson(JSONObject("""{"clockId":"ID"}"""))
        assertEquals(expected, actual)
    }

    @Test
    fun jsonDeserialization_nullTimestamp() {
        val expected = ClockSettings("ID")
        val actual = ClockSettings.fromJson(JSONObject("""{"clockId":"ID", "metadata":null}"""))
        assertEquals(expected, actual)
    }

    @Test
    fun jsonDeserialization_noId_deserializedEmpty() {
        val expected = ClockSettings().apply { metadata.put("appliedTimestamp", 50) }
        val actual = ClockSettings.fromJson(JSONObject("""{"metadata":{"appliedTimestamp":50}}"""))
        assertEquals(expected, actual)
    }

    @Test
    fun jsonDeserialization_fontAxes() {
        val expected = ClockSettings(axes = ClockAxisStyle("KEY", 10f))
        val json = JSONObject("""{"axes":[{"key":"KEY","value":10}]}""")
        val actual = ClockSettings.fromJson(json)
        assertEquals(expected, actual)
    }

    @Test
    fun jsonSerialization() {
        val expected =
            JSONObject().apply {
                put("clockId", "ID")
                put("metadata", JSONObject().apply { put("appliedTimestamp", 50) })
                put("axes", JSONArray())
            }
        val settings = ClockSettings("ID", null).apply { metadata.put("appliedTimestamp", 50) }
        val actual = ClockSettings.toJson(settings)
        assertEquals(expected.toString(), actual.toString())
    }

    @Test
    fun jsonSerialization_noTimestamp() {
        val expected =
            JSONObject().apply {
                put("clockId", "ID")
                put("metadata", JSONObject())
                put("axes", JSONArray())
            }
        val actual = ClockSettings.toJson(ClockSettings("ID", null))
        assertEquals(expected.toString(), actual.toString())
    }

    @Test
    fun jsonSerialization_axisSettings() {
        val settings = ClockSettings(axes = ClockAxisStyle("KEY", 10f))
        val actual = ClockSettings.toJson(settings)
        val expected = JSONObject("""{"metadata":{},"axes":[{"key":"KEY","value":10}]}""")
        assertEquals(expected.toString(), actual.toString())
    }
}
