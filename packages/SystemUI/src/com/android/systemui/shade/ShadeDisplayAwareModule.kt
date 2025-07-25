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

package com.android.systemui.shade

import android.content.Context
import android.content.res.Resources
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE
import android.window.WindowContext
import com.android.app.tracing.TrackGroupUtils.trackGroup
import com.android.systemui.CoreStartable
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.common.ui.ConfigurationStateImpl
import com.android.systemui.common.ui.data.repository.ConfigurationRepository
import com.android.systemui.common.ui.data.repository.ConfigurationRepositoryImpl
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractorImpl
import com.android.systemui.common.ui.view.ChoreographerUtils
import com.android.systemui.common.ui.view.ChoreographerUtilsImpl
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.res.R
import com.android.systemui.scene.ui.view.WindowRootView
import com.android.systemui.shade.data.repository.MutableShadeDisplaysRepository
import com.android.systemui.shade.data.repository.ShadeDisplaysRepository
import com.android.systemui.shade.data.repository.ShadeDisplaysRepositoryImpl
import com.android.systemui.shade.display.ShadeDisplayPolicyModule
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractor
import com.android.systemui.shade.domain.interactor.ShadeDialogContextInteractorImpl
import com.android.systemui.shade.domain.interactor.ShadeDisplaysInteractor
import com.android.systemui.shade.shared.flag.ShadeWindowGoesAround
import com.android.systemui.statusbar.notification.stack.NotificationStackRebindingHider
import com.android.systemui.statusbar.notification.stack.NotificationStackRebindingHiderImpl
import com.android.systemui.statusbar.phone.ConfigurationControllerImpl
import com.android.systemui.statusbar.phone.ConfigurationForwarder
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.utils.windowmanager.WindowManagerProvider
import com.android.systemui.utils.windowmanager.WindowManagerUtils
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Provider
import javax.inject.Qualifier

/**
 * Module responsible for managing display-specific components and resources for the notification
 * shade window.
 *
 * This isolation is crucial because when the window transitions between displays, its associated
 * context, resources, and display characteristics (like density and size) also change. If the shade
 * window shared the same context as the rest of the system UI, it could lead to inconsistencies and
 * errors due to incorrect display information.
 *
 * By using this dedicated module, we ensure the notification shade window always utilizes the
 * correct display context and resources, regardless of the display it's on.
 */
@Module(includes = [ShadeDisplayPolicyModule::class])
object ShadeDisplayAwareModule {

    /** Creates a new context for the shade window. */
    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideShadeDisplayAwareContext(context: Context): Context {
        return if (ShadeWindowGoesAround.isEnabled) {
            context
                .createWindowContext(context.display, TYPE_NOTIFICATION_SHADE, /* options= */ null)
                .apply { setTheme(R.style.Theme_SystemUI) }
        } else {
            context
        }
    }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideShadeDisplayAwareWindowContext(@ShadeDisplayAware context: Context): WindowContext {
        ShadeWindowGoesAround.isUnexpectedlyInLegacyMode()
        // We rely on the fact context is a WindowContext as the API to reparent windows is only
        // available there.
        return (context as? WindowContext)
            ?: error(
                "ShadeDisplayAware context must be a window context to allow window reparenting."
            )
    }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideShadeWindowLayoutParams(@ShadeDisplayAware context: Context): LayoutParams {
        return ShadeWindowLayoutParams.create(context)
    }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideShadeWindowManager(
        defaultWindowManager: WindowManager,
        @ShadeDisplayAware context: Context,
        windowManagerProvider: WindowManagerProvider
    ): WindowManager {
        return if (ShadeWindowGoesAround.isEnabled) {
            windowManagerProvider.getWindowManager(context)
        } else {
            defaultWindowManager
        }
    }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideShadeDisplayAwareResources(@ShadeDisplayAware context: Context): Resources {
        return context.resources
    }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun providesDisplayAwareLayoutInflater(@ShadeDisplayAware context: Context): LayoutInflater {
        return LayoutInflater.from(context)
    }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideShadeWindowConfigurationController(
        @ShadeDisplayAware shadeContext: Context,
        factory: ConfigurationControllerImpl.Factory,
        @Main globalConfigController: ConfigurationController,
    ): ConfigurationController {
        return if (ShadeWindowGoesAround.isEnabled) {
            factory.create(shadeContext)
        } else {
            globalConfigController
        }
    }

    @Provides
    @ShadeDisplayAware
    @SysUISingleton
    fun provideShadeWindowConfigurationForwarder(
        @ShadeDisplayAware shadeConfigurationController: ConfigurationController
    ): ConfigurationForwarder {
        ShadeWindowGoesAround.isUnexpectedlyInLegacyMode()
        return shadeConfigurationController
    }

    @SysUISingleton
    @Provides
    @ShadeDisplayAware
    fun provideShadeDisplayAwareConfigurationState(
        factory: ConfigurationStateImpl.Factory,
        @ShadeDisplayAware configurationController: ConfigurationController,
        @ShadeDisplayAware context: Context,
        @Main configurationState: ConfigurationState,
    ): ConfigurationState {
        return if (ShadeWindowGoesAround.isEnabled) {
            factory.create(context, configurationController)
        } else {
            configurationState
        }
    }

    @SysUISingleton
    @Provides
    @ShadeDisplayAware
    fun provideShadeDisplayAwareConfigurationRepository(
        factory: ConfigurationRepositoryImpl.Factory,
        @ShadeDisplayAware configurationController: ConfigurationController,
        @ShadeDisplayAware context: Context,
        @Main globalConfigurationRepository: ConfigurationRepository,
    ): ConfigurationRepository {
        return if (ShadeWindowGoesAround.isEnabled) {
            factory.create(context, configurationController)
        } else {
            globalConfigurationRepository
        }
    }

    @SysUISingleton
    @Provides
    @ShadeDisplayAware
    fun provideShadeAwareConfigurationInteractor(
        @ShadeDisplayAware configurationRepository: ConfigurationRepository,
        @Main configurationInteractor: ConfigurationInteractor,
    ): ConfigurationInteractor {
        return if (ShadeWindowGoesAround.isEnabled) {
            ConfigurationInteractorImpl(configurationRepository)
        } else {
            configurationInteractor
        }
    }

    @SysUISingleton
    @Provides
    fun provideShadePositionRepository(
        impl: MutableShadeDisplaysRepository
    ): ShadeDisplaysRepository {
        ShadeWindowGoesAround.isUnexpectedlyInLegacyMode()
        return impl
    }

    @SysUISingleton
    @Provides
    fun provideMutableShadePositionRepository(
        impl: ShadeDisplaysRepositoryImpl
    ): MutableShadeDisplaysRepository {
        ShadeWindowGoesAround.isUnexpectedlyInLegacyMode()
        return impl
    }

    @Provides
    @SysUISingleton
    fun provideShadeDialogContextInteractor(
        impl: ShadeDialogContextInteractorImpl
    ): ShadeDialogContextInteractor = impl

    @Provides
    @IntoMap
    @ClassKey(ShadeDialogContextInteractor::class)
    fun provideShadeDialogContextInteractorCoreStartable(
        impl: Provider<ShadeDialogContextInteractorImpl>
    ): CoreStartable {
        return if (ShadeWindowGoesAround.isEnabled) {
            impl.get()
        } else {
            CoreStartable.NOP
        }
    }

    @Provides
    @IntoMap
    @ClassKey(ShadePrimaryDisplayCommand::class)
    fun provideShadePrimaryDisplayCommand(
        impl: Provider<ShadePrimaryDisplayCommand>
    ): CoreStartable {
        return if (ShadeWindowGoesAround.isEnabled) {
            impl.get()
        } else {
            CoreStartable.NOP
        }
    }

    /**
     * Provided for making classes easier to test. In tests, a custom method to wait for the next
     * frame can be easily provided.
     */
    @Provides fun provideChoreographerUtils(): ChoreographerUtils = ChoreographerUtilsImpl

    @Provides
    @ShadeOnDefaultDisplayWhenLocked
    fun provideShadeOnDefaultDisplayWhenLocked(): Boolean = true

    /** Provides a [LogBuffer] for use by classes related to shade movement */
    @Provides
    @SysUISingleton
    @ShadeDisplayLog
    fun provideShadeDisplayLogLogBuffer(factory: LogBufferFactory): LogBuffer {
        val logBufferName = "ShadeDisplayLog"
        return factory.create(
            logBufferName,
            maxSize = 400,
            alwaysLogToLogcat = true,
            systraceTrackName = trackGroup("shade", logBufferName),
        )
    }
}

/** Module that should be included only if the shade window [WindowRootView] is available. */
@Module
object ShadeDisplayAwareWithShadeWindowModule {
    @Provides
    @IntoMap
    @ClassKey(ShadeDisplaysInteractor::class)
    fun provideShadeDisplaysInteractor(impl: Provider<ShadeDisplaysInteractor>): CoreStartable {
        return if (ShadeWindowGoesAround.isEnabled) {
            impl.get()
        } else {
            CoreStartable.NOP
        }
    }

    @Provides
    @SysUISingleton
    fun bindNotificationStackRebindingHider(
        impl: NotificationStackRebindingHiderImpl
    ): NotificationStackRebindingHider = impl
}

/**
 * Annotates the boolean value that defines whether the shade window should go back to the default
 * display when the keyguard is visible.
 *
 * As of today (Dec 2024), This is a configuration parameter provided in the dagger graph as the
 * final policy around keyguard display is still under discussion, and will be evaluated based on
 * how well this solution behaves from the performance point of view.
 */
@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class ShadeOnDefaultDisplayWhenLocked

/** A [com.android.systemui.log.LogBuffer] for changes to the shade display. */
@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class ShadeDisplayLog
