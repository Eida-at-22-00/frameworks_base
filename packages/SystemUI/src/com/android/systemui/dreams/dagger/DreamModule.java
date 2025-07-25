/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.dreams.dagger;

import android.annotation.Nullable;
import android.app.DreamManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;

import com.android.dream.lowlight.dagger.LowLightDreamComponent;
import com.android.settingslib.dream.DreamBackend;
import com.android.systemui.Flags;
import com.android.systemui.ambient.touch.scrim.dagger.ScrimModule;
import com.android.systemui.complication.dagger.RegisteredComplicationsModule;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.DreamOverlayNotificationCountProvider;
import com.android.systemui.dreams.DreamOverlayService;
import com.android.systemui.dreams.SystemDialogsCloser;
import com.android.systemui.dreams.complication.dagger.DreamComplicationComponent;
import com.android.systemui.dreams.homecontrols.HomeControlsDreamService;
import com.android.systemui.dreams.homecontrols.dagger.HomeControlsDataSourceModule;
import com.android.systemui.dreams.homecontrols.dagger.HomeControlsRemoteServiceComponent;
import com.android.systemui.dreams.homecontrols.system.HomeControlsRemoteService;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.pipeline.shared.TileSpec;
import com.android.systemui.qs.shared.model.TileCategory;
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig;
import com.android.systemui.qs.tiles.base.shared.model.QSTilePolicy;
import com.android.systemui.qs.tiles.base.shared.model.QSTileUIConfig;
import com.android.systemui.res.R;
import com.android.systemui.touch.TouchInsetManager;

import com.google.android.systemui.lowlightclock.LowLightClockDreamService;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;
import dagger.multibindings.StringKey;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Named;

/**
 * Dagger Module providing Dream-related functionality.
 */
@Module(includes = {
        RegisteredComplicationsModule.class,
        ScrimModule.class,
        HomeControlsDataSourceModule.class,
},
        subcomponents = {
                DreamComplicationComponent.class,
                DreamOverlayComponent.class,
                HomeControlsRemoteServiceComponent.class,
                LowLightDreamComponent.class,
        })
public interface DreamModule {
    String DREAM_ONLY_ENABLED_FOR_DOCK_USER = "dream_only_enabled_for_dock_user";
    String DREAM_OVERLAY_SERVICE_COMPONENT = "dream_overlay_service_component";
    String DREAM_OVERLAY_ENABLED = "dream_overlay_enabled";
    String DREAM_TOUCH_INSET_MANAGER = "dream_touch_inset_manager";
    String DREAM_SUPPORTED = "dream_supported";
    String DREAM_OVERLAY_WINDOW_TITLE = "dream_overlay_window_title";
    String HOME_CONTROL_PANEL_DREAM_COMPONENT = "home_control_panel_dream_component";
    String DREAM_TILE_SPEC = "dream";
    String LOW_LIGHT_DREAM_SERVICE = "low_light_dream_component";

    String LOW_LIGHT_CLOCK_DREAM = "low_light_clock_dream";

    /**
     * Provides the dream component
     */
    @Provides
    @Named(DREAM_OVERLAY_SERVICE_COMPONENT)
    static ComponentName providesDreamOverlayService(Context context) {
        return new ComponentName(context, DreamOverlayService.class);
    }

    /**
     * Provides the home control panel component
     */
    @Provides
    @Nullable
    @Named(HOME_CONTROL_PANEL_DREAM_COMPONENT)
    static ComponentName providesHomeControlPanelComponent(Context context) {
        final String homeControlPanelComponent = context.getResources()
                .getString(R.string.config_homePanelDreamComponent);
        if (homeControlPanelComponent.isEmpty()) {
            return null;
        }
        return ComponentName.unflattenFromString(homeControlPanelComponent);
    }

    /**
     * Provides Home Controls Dream Service
     */
    @Binds
    @IntoMap
    @ClassKey(HomeControlsDreamService.class)
    Service bindHomeControlsDreamService(
            HomeControlsDreamService service);

    /**
     * Provides Home Controls Remote Service
     */
    @Binds
    @IntoMap
    @ClassKey(HomeControlsRemoteService.class)
    Service bindHomeControlsRemoteService(
            HomeControlsRemoteService service);

    /**
     * Provides a touch inset manager for dreams.
     */
    @Provides
    @Named(DREAM_TOUCH_INSET_MANAGER)
    static TouchInsetManager providesTouchInsetManager(@Main Executor executor) {
        return new TouchInsetManager(executor);
    }

    /**
     * Provides whether dream overlay is enabled.
     */
    @Provides
    @Named(DREAM_OVERLAY_ENABLED)
    static Boolean providesDreamOverlayEnabled(PackageManager packageManager,
            @Named(DREAM_OVERLAY_SERVICE_COMPONENT) ComponentName component) {
        try {
            return packageManager.getServiceInfo(component, PackageManager.GET_META_DATA).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Provides an instance of the dream backend.
     */
    @Provides
    static DreamBackend providesDreamBackend(Context context) {
        return DreamBackend.getInstance(context);
    }

    /**
     * Provides an instance of a {@link DreamOverlayNotificationCountProvider}.
     */
    @SysUISingleton
    @Provides
    static Optional<DreamOverlayNotificationCountProvider>
            providesDreamOverlayNotificationCountProvider() {
        // If we decide to bring this back, we should gate it on a config that can be changed in
        // an overlay.
        return Optional.empty();
    }

    /**
     * Provides an implementation for {@link SystemDialogsCloser} that calls
     * {@link Context.closeSystemDialogs}.
     */
    @Provides
    static SystemDialogsCloser providesSystemDialogsCloser(Context context) {
        return () -> context.closeSystemDialogs();
    }

    /** */
    @Provides
    @Named(DREAM_ONLY_ENABLED_FOR_DOCK_USER)
    static boolean providesDreamOnlyEnabledForDockUser(@Main Resources resources) {
        return resources.getBoolean(
                com.android.internal.R.bool.config_dreamsOnlyEnabledForDockUser);
    }

    /** */
    @Provides
    @Named(DREAM_SUPPORTED)
    static boolean providesDreamSupported(@Main Resources resources) {
        return resources.getBoolean(com.android.internal.R.bool.config_dreamsSupported);
    }

    /** */
    @Provides
    @Named(DREAM_OVERLAY_WINDOW_TITLE)
    static String providesDreamOverlayWindowTitle(@Main Resources resources) {
        return resources.getString(R.string.app_label);
    }

    /** Provides config for the dream tile */
    @Provides
    @IntoMap
    @StringKey(DREAM_TILE_SPEC)
    static QSTileConfig provideDreamTileConfig(QsEventLogger uiEventLogger) {
        TileSpec tileSpec = TileSpec.create(DREAM_TILE_SPEC);
        return new QSTileConfig(tileSpec,
                new QSTileUIConfig.Resource(
                        R.drawable.ic_qs_screen_saver,
                        R.string.quick_settings_screensaver_label),
                uiEventLogger.getNewInstanceId(),
                TileCategory.UTILITIES,
                tileSpec.getSpec(),
                QSTilePolicy.NoRestrictions.INSTANCE
                );
    }

    /**
     * Provides dream manager.
     */
    @Provides
    static DreamManager providesDreamManager(Context context) {
        return Objects.requireNonNull(context.getSystemService(DreamManager.class));
    }

    /**
     * Binds a default (unset) clock dream.
     */
    @BindsOptionalOf
    @Named(LOW_LIGHT_CLOCK_DREAM)
    ComponentName bindsLowLightClockDream();

    /**
     * Provides low light clock dream service component.
     */
    @Provides
    @Named(LOW_LIGHT_CLOCK_DREAM)
    static ComponentName providesLowLightClockDream(Context context) {
        return new ComponentName(context, LowLightClockDreamService.class);
    }

    /**
     * Provides the component name of the low light dream, or null if not configured.
     */
    @Provides
    @Nullable
    @Named(LOW_LIGHT_DREAM_SERVICE)
    static ComponentName providesLowLightDreamService(Context context,
            @Named(LOW_LIGHT_CLOCK_DREAM) ComponentName clockDream) {
        if (Flags.lowLightClockDream()) {
            return clockDream;
        }

        String lowLightDreamComponent = context.getResources().getString(
                R.string.config_lowLightDreamComponent
        );
        return lowLightDreamComponent.isEmpty()
                ? null : ComponentName.unflattenFromString(lowLightDreamComponent);
    }

    /**
     * Provides Dagger component for low light dependencies.
     */
    @Provides
    @SysUISingleton
    static LowLightDreamComponent providesLowLightDreamComponent(
            LowLightDreamComponent.Factory factory, DreamManager dreamManager,
            @Named(LOW_LIGHT_DREAM_SERVICE) ComponentName lowLightDreamService) {
        return factory.create(dreamManager, lowLightDreamService);
    }
}
