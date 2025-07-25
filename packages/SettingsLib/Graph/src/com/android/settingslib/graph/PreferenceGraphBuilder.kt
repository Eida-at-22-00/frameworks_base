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

@file:Suppress("DEPRECATION")

package com.android.settingslib.graph

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import com.android.settingslib.graph.PreferenceGetterFlags.includeMetadata
import com.android.settingslib.graph.PreferenceGetterFlags.includeValue
import com.android.settingslib.graph.PreferenceGetterFlags.includeValueDescriptor
import com.android.settingslib.graph.proto.PreferenceGraphProto
import com.android.settingslib.graph.proto.PreferenceGroupProto
import com.android.settingslib.graph.proto.PreferenceProto
import com.android.settingslib.graph.proto.PreferenceProto.ActionTarget
import com.android.settingslib.graph.proto.PreferenceScreenProto
import com.android.settingslib.graph.proto.TextProto
import com.android.settingslib.metadata.EXTRA_BINDING_SCREEN_ARGS
import com.android.settingslib.metadata.IntRangeValuePreference
import com.android.settingslib.metadata.PersistentPreference
import com.android.settingslib.metadata.PreferenceAvailabilityProvider
import com.android.settingslib.metadata.PreferenceHierarchy
import com.android.settingslib.metadata.PreferenceMetadata
import com.android.settingslib.metadata.PreferenceRestrictionProvider
import com.android.settingslib.metadata.PreferenceScreenBindingKeyProvider
import com.android.settingslib.metadata.PreferenceScreenCoordinate
import com.android.settingslib.metadata.PreferenceScreenMetadata
import com.android.settingslib.metadata.PreferenceScreenMetadataFactory
import com.android.settingslib.metadata.PreferenceScreenMetadataParameterizedFactory
import com.android.settingslib.metadata.PreferenceScreenRegistry
import com.android.settingslib.metadata.PreferenceSummaryProvider
import com.android.settingslib.metadata.PreferenceTitleProvider
import com.android.settingslib.metadata.ReadWritePermit
import com.android.settingslib.metadata.SensitivityLevel.Companion.HIGH_SENSITIVITY
import com.android.settingslib.metadata.SensitivityLevel.Companion.UNKNOWN_SENSITIVITY
import com.android.settingslib.metadata.getPreferenceIcon
import com.android.settingslib.preference.PreferenceScreenFactory
import com.android.settingslib.preference.PreferenceScreenProvider
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "PreferenceGraphBuilder"

/** Builder of preference graph. */
class PreferenceGraphBuilder
private constructor(
    private val context: Context,
    private val callingPid: Int,
    private val callingUid: Int,
    private val request: GetPreferenceGraphRequest,
) {
    private val preferenceScreenFactory by lazy {
        PreferenceScreenFactory(context.ofLocale(request.locale))
    }
    private val builder by lazy { PreferenceGraphProto.newBuilder() }
    private val visitedScreens = request.visitedScreens.toMutableSet()
    private val screens = mutableMapOf<String, PreferenceScreenProto.Builder>()

    private suspend fun init() {
        for (screen in request.screens) {
            PreferenceScreenRegistry.create(context, screen)?.let { addPreferenceScreen(it) }
        }
    }

    fun build(): PreferenceGraphProto {
        for ((key, screenBuilder) in screens) builder.putScreens(key, screenBuilder.build())
        return builder.build()
    }

    /**
     * Adds an activity to the graph.
     *
     * Reflection is used to create the instance. To avoid security vulnerability, the code ensures
     * given [activityClassName] must be declared as an <activity> entry in AndroidManifest.xml.
     */
    suspend fun add(activityClassName: String) {
        try {
            val intent = Intent()
            intent.setClassName(context, activityClassName)
            if (
                context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ==
                    null
            ) {
                Log.e(TAG, "$activityClassName is not activity")
                return
            }
            val activityClass = context.classLoader.loadClass(activityClassName)
            if (addPreferenceScreenKeyProvider(activityClass)) return
            if (PreferenceScreenProvider::class.java.isAssignableFrom(activityClass)) {
                addPreferenceScreenProvider(activityClass)
            } else {
                Log.w(TAG, "$activityClass does not implement PreferenceScreenProvider")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fail to add $activityClassName", e)
        }
    }

    private suspend fun addPreferenceScreenKeyProvider(activityClass: Class<*>): Boolean {
        if (!PreferenceScreenBindingKeyProvider::class.java.isAssignableFrom(activityClass)) {
            return false
        }
        val key = getPreferenceScreenKey { activityClass.newInstance() } ?: return false
        if (addPreferenceScreenFromRegistry(key)) {
            builder.addRoots(key)
            return true
        }
        return false
    }

    private suspend fun getPreferenceScreenKey(newInstance: () -> Any): String? =
        withContext(Dispatchers.Main) {
            try {
                val instance = newInstance()
                if (instance is PreferenceScreenBindingKeyProvider) {
                    return@withContext instance.getPreferenceScreenBindingKey(context)
                } else {
                    Log.w(TAG, "$instance is not PreferenceScreenKeyProvider")
                }
            } catch (e: Exception) {
                Log.e(TAG, "getPreferenceScreenKey failed", e)
            }
            null
        }

    private suspend fun addPreferenceScreenFromRegistry(key: String): Boolean {
        val factory =
            PreferenceScreenRegistry.preferenceScreenMetadataFactories[key] ?: return false
        return addPreferenceScreen(factory)
    }

    suspend fun addPreferenceScreenProvider(activityClass: Class<*>) {
        Log.d(TAG, "add $activityClass")
        createPreferenceScreen { activityClass.newInstance() }
            ?.let {
                addPreferenceScreen(Intent(context, activityClass), it)
                builder.addRoots(it.key)
            }
    }

    /**
     * Creates [PreferenceScreen].
     *
     * Androidx Activity/Fragment instance must be created in main thread, otherwise an exception is
     * raised.
     */
    private suspend fun createPreferenceScreen(newInstance: () -> Any): PreferenceScreen? =
        withContext(Dispatchers.Main) {
            try {
                val instance = newInstance()
                Log.d(TAG, "createPreferenceScreen $instance")
                if (instance is PreferenceScreenProvider) {
                    return@withContext instance.createPreferenceScreen(preferenceScreenFactory)
                } else {
                    Log.w(TAG, "$instance is not PreferenceScreenProvider")
                }
            } catch (e: Exception) {
                Log.e(TAG, "createPreferenceScreen failed", e)
            }
            return@withContext null
        }

    private suspend fun addPreferenceScreen(intent: Intent, preferenceScreen: PreferenceScreen?) {
        val key = preferenceScreen?.key
        if (key.isNullOrEmpty()) {
            Log.e(TAG, "\"$preferenceScreen\" has no key")
            return
        }
        val args = preferenceScreen.peekExtras()?.getBundle(EXTRA_BINDING_SCREEN_ARGS)
        @Suppress("CheckReturnValue")
        addPreferenceScreen(key, args) {
            this.intent = intent.toProto()
            root = preferenceScreen.toProto()
        }
    }

    suspend fun addPreferenceScreen(factory: PreferenceScreenMetadataFactory): Boolean {
        if (factory is PreferenceScreenMetadataParameterizedFactory) {
            factory.parameters(context).collect { addPreferenceScreen(factory.create(context, it)) }
            return true
        }
        return addPreferenceScreen(factory.create(context))
    }

    private suspend fun addPreferenceScreen(metadata: PreferenceScreenMetadata): Boolean =
        addPreferenceScreen(metadata.key, metadata.arguments) {
            completeHierarchy = metadata.hasCompleteHierarchy()
            root = metadata.getPreferenceHierarchy(context).toProto(metadata, true)
        }

    private suspend fun addPreferenceScreen(
        key: String,
        args: Bundle?,
        init: suspend PreferenceScreenProto.Builder.() -> Unit,
    ): Boolean {
        if (!visitedScreens.add(PreferenceScreenCoordinate(key, args))) {
            Log.w(TAG, "$key $args visited")
            return false
        }
        if (args == null) { // normal screen
            screens[key] = PreferenceScreenProto.newBuilder().also { init(it) }
        } else if (args.isEmpty) { // parameterized screen with backward compatibility
            val builder = screens.getOrPut(key) { PreferenceScreenProto.newBuilder() }
            init(builder)
        } else { // parameterized screen with non-empty arguments
            val builder = screens.getOrPut(key) { PreferenceScreenProto.newBuilder() }
            val parameterizedScreen = parameterizedPreferenceScreenProto {
                setArgs(args.toProto())
                setScreen(PreferenceScreenProto.newBuilder().also { init(it) })
            }
            builder.addParameterizedScreens(parameterizedScreen)
        }
        return true
    }

    private suspend fun PreferenceGroup.toProto(): PreferenceGroupProto = preferenceGroupProto {
        preference = (this@toProto as Preference).toProto()
        for (index in 0 until preferenceCount) {
            val child = getPreference(index)
            addPreferences(
                preferenceOrGroupProto {
                    if (child is PreferenceGroup) {
                        group = child.toProto()
                    } else {
                        preference = child.toProto()
                    }
                }
            )
        }
    }

    private suspend fun Preference.toProto(): PreferenceProto = preferenceProto {
        this@toProto.key?.let { key = it }
        this@toProto.title?.let { title = textProto { string = it.toString() } }
        this@toProto.summary?.let { summary = textProto { string = it.toString() } }
        val preferenceExtras = peekExtras()
        preferenceExtras?.let { extras = it.toProto() }
        enabled = isEnabled
        available = isVisible
        persistent = isPersistent
        if (request.flags.includeValue() && isPersistent && this@toProto is TwoStatePreference) {
            value = preferenceValueProto { booleanValue = this@toProto.isChecked }
        }
        this@toProto.fragment.toActionTarget(preferenceExtras)?.let {
            actionTarget = it
            return@preferenceProto
        }
        this@toProto.intent?.let { actionTarget = it.toActionTarget() }
    }

    private suspend fun PreferenceHierarchy.toProto(
        screenMetadata: PreferenceScreenMetadata,
        isRoot: Boolean,
    ): PreferenceGroupProto = preferenceGroupProto {
        preference = toProto(screenMetadata, this@toProto.metadata, isRoot)
        forEachAsync {
            addPreferences(
                preferenceOrGroupProto {
                    if (it is PreferenceHierarchy) {
                        group = it.toProto(screenMetadata, false)
                    } else {
                        preference = toProto(screenMetadata, it.metadata, false)
                    }
                }
            )
        }
    }

    private suspend fun toProto(
        screenMetadata: PreferenceScreenMetadata,
        metadata: PreferenceMetadata,
        isRoot: Boolean,
    ) =
        metadata
            .toProto(context, callingPid, callingUid, screenMetadata, isRoot, request.flags)
            .also {
                if (metadata is PreferenceScreenMetadata) {
                    @Suppress("CheckReturnValue") addPreferenceScreen(metadata)
                }
                metadata.intent(context)?.resolveActivity(context.packageManager)?.let {
                    if (it.packageName == context.packageName) {
                        add(it.className)
                    }
                }
            }

    private suspend fun String?.toActionTarget(extras: Bundle?): ActionTarget? {
        if (this.isNullOrEmpty()) return null
        try {
            val fragmentClass = context.classLoader.loadClass(this)
            if (Fragment::class.java.isAssignableFrom(fragmentClass)) {
                @Suppress("UNCHECKED_CAST")
                return (fragmentClass as Class<out Fragment>).toActionTarget(extras)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot loadClass $this", e)
        }
        return null
    }

    private suspend fun Class<out Fragment>.toActionTarget(extras: Bundle?): ActionTarget? {
        if (
            !PreferenceScreenProvider::class.java.isAssignableFrom(this) &&
                !PreferenceScreenBindingKeyProvider::class.java.isAssignableFrom(this)
        ) {
            return null
        }
        val fragment =
            withContext(Dispatchers.Main) {
                return@withContext try {
                    newInstance().apply { arguments = extras }
                } catch (e: Exception) {
                    Log.e(TAG, "Fail to instantiate fragment ${this@toActionTarget}", e)
                    null
                }
            }
        if (fragment is PreferenceScreenBindingKeyProvider) {
            val screenKey = fragment.getPreferenceScreenBindingKey(context)
            if (screenKey != null && addPreferenceScreenFromRegistry(screenKey)) {
                return actionTargetProto { key = screenKey }
            }
        }
        if (fragment is PreferenceScreenProvider) {
            try {
                val screen = fragment.createPreferenceScreen(preferenceScreenFactory)
                val screenKey = screen?.key
                if (!screenKey.isNullOrEmpty()) {
                    @Suppress("CheckReturnValue")
                    addPreferenceScreen(screenKey, null) { root = screen.toProto() }
                    return actionTargetProto { key = screenKey }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fail to createPreferenceScreen for $fragment", e)
            }
        }
        return null
    }

    private suspend fun Intent.toActionTarget() =
        toActionTarget(context).also {
            resolveActivity(context.packageManager)?.let {
                if (it.packageName == context.packageName) {
                    add(it.className)
                }
            }
        }

    companion object {
        suspend fun of(
            context: Context,
            callingPid: Int,
            callingUid: Int,
            request: GetPreferenceGraphRequest,
        ) = PreferenceGraphBuilder(context, callingPid, callingUid, request).also { it.init() }
    }
}

fun PreferenceMetadata.toProto(
    context: Context,
    callingPid: Int,
    callingUid: Int,
    screenMetadata: PreferenceScreenMetadata,
    isRoot: Boolean,
    flags: Int,
) = preferenceProto {
    val metadata = this@toProto
    key = metadata.key
    if (flags.includeMetadata()) {
        metadata.getTitleTextProto(context, isRoot)?.let { title = it }
        if (metadata.summary != 0) {
            summary = textProto { resourceId = metadata.summary }
        } else {
            (metadata as? PreferenceSummaryProvider)?.getSummary(context)?.let {
                summary = textProto { string = it.toString() }
            }
        }
        val metadataIcon = metadata.getPreferenceIcon(context)
        if (metadataIcon != 0) icon = metadataIcon
        if (metadata.keywords != 0) keywords = metadata.keywords
        val preferenceExtras = metadata.extras(context)
        preferenceExtras?.let { extras = it.toProto() }
        indexable = metadata.isIndexable(context)
        enabled = metadata.isEnabled(context)
        if (metadata is PreferenceAvailabilityProvider) {
            available = metadata.isAvailable(context)
        }
        if (metadata is PreferenceRestrictionProvider) {
            restricted = metadata.isRestricted(context)
        }
        metadata.intent(context)?.let { actionTarget = it.toActionTarget(context) }
        screenMetadata.getLaunchIntent(context, metadata)?.let { launchIntent = it.toProto() }
        for (tag in metadata.tags(context)) addTags(tag)
    }
    persistent = metadata.isPersistent(context)
    if (metadata !is PersistentPreference<*>) return@preferenceProto
    sensitivityLevel = metadata.sensitivityLevel
    metadata.getReadPermissions(context)?.let { if (it.size > 0) readPermissions = it.toProto() }
    metadata.getWritePermissions(context)?.let { if (it.size > 0) writePermissions = it.toProto() }
    val readPermit = metadata.evalReadPermit(context, callingPid, callingUid)
    val writePermit =
        metadata.evalWritePermit(context, callingPid, callingUid) ?: ReadWritePermit.ALLOW
    readWritePermit = ReadWritePermit.make(readPermit, writePermit)
    if (
        flags.includeValue() &&
            enabled &&
            (!hasAvailable() || available) &&
            (!hasRestricted() || !restricted) &&
            readPermit == ReadWritePermit.ALLOW
    ) {
        val storage = metadata.storage(context)
        value = preferenceValueProto {
            when (metadata.valueType) {
                Int::class.javaObjectType -> storage.getInt(metadata.key)?.let { intValue = it }
                Boolean::class.javaObjectType ->
                    storage.getBoolean(metadata.key)?.let { booleanValue = it }
                Float::class.javaObjectType ->
                    storage.getFloat(metadata.key)?.let { floatValue = it }
                else -> {}
            }
        }
    }
    if (flags.includeValueDescriptor()) {
        valueDescriptor = preferenceValueDescriptorProto {
            when (metadata) {
                is IntRangeValuePreference -> rangeValue = rangeValueProto {
                        min = metadata.getMinValue(context)
                        max = metadata.getMaxValue(context)
                        step = metadata.getIncrementStep(context)
                    }
                else -> {}
            }
            when (metadata.valueType) {
                Boolean::class.javaObjectType -> booleanType = true
                Float::class.javaObjectType -> floatType = true
            }
        }
    }
}

/** Evaluates the read permit of a persistent preference. */
fun <T> PersistentPreference<T>.evalReadPermit(
    context: Context,
    callingPid: Int,
    callingUid: Int,
): Int =
    when {
        getReadPermissions(context)?.check(context, callingPid, callingUid) == false ->
            ReadWritePermit.REQUIRE_APP_PERMISSION
        else -> getReadPermit(context, callingPid, callingUid)
    }

/** Evaluates the write permit of a persistent preference. */
fun <T> PersistentPreference<T>.evalWritePermit(
    context: Context,
    callingPid: Int,
    callingUid: Int,
): Int? =
    when {
        sensitivityLevel == UNKNOWN_SENSITIVITY || sensitivityLevel == HIGH_SENSITIVITY ->
            ReadWritePermit.DISALLOW
        getWritePermissions(context)?.check(context, callingPid, callingUid) == false ->
            ReadWritePermit.REQUIRE_APP_PERMISSION
        else -> getWritePermit(context, callingPid, callingUid)
    }

private fun PreferenceMetadata.getTitleTextProto(context: Context, isRoot: Boolean): TextProto? {
    if (isRoot && this is PreferenceScreenMetadata) {
        val titleRes = screenTitle
        if (titleRes != 0) {
            return textProto { resourceId = titleRes }
        } else {
            getScreenTitle(context)?.let {
                return textProto { string = it.toString() }
            }
        }
    } else {
        val titleRes = title
        if (titleRes != 0) {
            return textProto { resourceId = titleRes }
        }
    }
    return (this as? PreferenceTitleProvider)?.getTitle(context)?.let {
        textProto { string = it.toString() }
    }
}

private fun Intent.toActionTarget(context: Context): ActionTarget {
    if (component?.packageName == "") {
        setClassName(context, component!!.className)
    }
    return actionTargetProto { intent = toProto() }
}

@SuppressLint("AppBundleLocaleChanges")
internal fun Context.ofLocale(locale: Locale?): Context {
    if (locale == null) return this
    val baseConfig: Configuration = resources.configuration
    val baseLocale =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            baseConfig.locales[0]
        } else {
            baseConfig.locale
        }
    if (locale == baseLocale) {
        return this
    }
    val newConfig = Configuration(baseConfig)
    newConfig.setLocale(locale)
    return createConfigurationContext(newConfig)
}
