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

package com.android.server.companion.virtual;

import static android.Manifest.permission.ADD_ALWAYS_UNLOCKED_DISPLAY;
import static android.Manifest.permission.ADD_TRUSTED_DISPLAY;
import static android.app.admin.DevicePolicyManager.NEARBY_STREAMING_ENABLED;
import static android.app.admin.DevicePolicyManager.NEARBY_STREAMING_NOT_CONTROLLED_BY_POLICY;
import static android.app.admin.DevicePolicyManager.NEARBY_STREAMING_SAME_MANAGED_ACCOUNT_ONLY;
import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_CUSTOM;
import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_DEFAULT;
import static android.companion.virtual.VirtualDeviceParams.NAVIGATION_POLICY_DEFAULT_ALLOWED;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_ACTIVITY;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_AUDIO;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_BLOCKED_ACTIVITY;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_CAMERA;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_CLIPBOARD;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_RECENTS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.StringRes;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.app.compat.CompatChanges;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.virtual.ActivityPolicyExemption;
import android.companion.virtual.IVirtualDevice;
import android.companion.virtual.IVirtualDeviceActivityListener;
import android.companion.virtual.IVirtualDeviceIntentInterceptor;
import android.companion.virtual.IVirtualDeviceSoundEffectListener;
import android.companion.virtual.VirtualDevice;
import android.companion.virtual.VirtualDeviceManager;
import android.companion.virtual.VirtualDeviceParams;
import android.companion.virtual.audio.IAudioConfigChangedCallback;
import android.companion.virtual.audio.IAudioRoutingCallback;
import android.companion.virtual.camera.VirtualCameraConfig;
import android.companion.virtual.sensor.VirtualSensor;
import android.companion.virtual.sensor.VirtualSensorAdditionalInfo;
import android.companion.virtual.sensor.VirtualSensorEvent;
import android.companion.virtualdevice.flags.Flags;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.PointF;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplayConfig;
import android.hardware.input.VirtualDpadConfig;
import android.hardware.input.VirtualKeyEvent;
import android.hardware.input.VirtualKeyboardConfig;
import android.hardware.input.VirtualMouseButtonEvent;
import android.hardware.input.VirtualMouseConfig;
import android.hardware.input.VirtualMouseRelativeEvent;
import android.hardware.input.VirtualMouseScrollEvent;
import android.hardware.input.VirtualNavigationTouchpadConfig;
import android.hardware.input.VirtualRotaryEncoderConfig;
import android.hardware.input.VirtualRotaryEncoderScrollEvent;
import android.hardware.input.VirtualStylusButtonEvent;
import android.hardware.input.VirtualStylusConfig;
import android.hardware.input.VirtualStylusMotionEvent;
import android.hardware.input.VirtualTouchEvent;
import android.hardware.input.VirtualTouchscreenConfig;
import android.media.AudioManager;
import android.media.audiopolicy.AudioMix;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Toast;
import android.window.DisplayWindowPolicyController;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.BlockedAppStreamingActivity;
import com.android.modules.expresslog.Counter;
import com.android.server.LocalServices;
import com.android.server.companion.virtual.GenericWindowPolicyController.RunningAppsChangedListener;
import com.android.server.companion.virtual.audio.VirtualAudioController;
import com.android.server.companion.virtual.camera.VirtualCameraController;
import com.android.server.inputmethod.InputMethodManagerInternal;

import dalvik.annotation.optimization.FastNative;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

final class VirtualDeviceImpl extends IVirtualDevice.Stub
        implements IBinder.DeathRecipient, RunningAppsChangedListener {

    private static final String TAG = "VirtualDeviceImpl";

    /**
     * Do not show a toast on the virtual display when a secure surface is shown after
     * {@link android.os.Build.VERSION_CODES#VANILLA_ICE_CREAM}. VDM clients should use
     * {@link VirtualDeviceManager.ActivityListener#onSecureWindowShown} instead to provide
     * a custom notification if desired.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.VANILLA_ICE_CREAM)
    public static final long DO_NOT_SHOW_TOAST_WHEN_SECURE_SURFACE_SHOWN = 311101667L;

    private static final int DEFAULT_VIRTUAL_DISPLAY_FLAGS =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS;

    private static final String PERSISTENT_ID_PREFIX_CDM_ASSOCIATION = "companion:";

    private static final List<String> DEVICE_PROFILES_ALLOWING_MIRROR_DISPLAYS = List.of(
            AssociationRequest.DEVICE_PROFILE_APP_STREAMING);

    /**
     * Timeout until {@link #launchPendingIntent} stops waiting for an activity to be launched.
     */
    private static final long PENDING_TRAMPOLINE_TIMEOUT_MS = 5000;

    /**
     * Global lock for this Virtual Device.
     *
     * Never call outside this class while holding this lock. A number of other system services like
     * WindowManager, DisplayManager, etc. call into this device to get device-specific information,
     * while holding their own global locks.
     *
     * Making a call to another service while holding this lock creates lock order inversion and
     * will potentially cause a deadlock.
     */
    private final Object mVirtualDeviceLock = new Object();

    private final int mBaseVirtualDisplayFlags;

    private final Context mContext;
    private final AssociationInfo mAssociationInfo;
    private final VirtualDeviceManagerService mService;
    private final PendingTrampolineCallback mPendingTrampolineCallback;
    private final int mOwnerUid;
    private final VirtualDeviceLog mVirtualDeviceLog;
    private final String mOwnerPackageName;
    @NonNull
    private final AttributionSource mAttributionSource;
    private final int mDeviceId;
    @Nullable
    private final String mPersistentDeviceId;
    private final InputController mInputController;
    private final SensorController mSensorController;
    private final CameraAccessController mCameraAccessController;
    @Nullable // Null if virtual camera flag is off.
    private final VirtualCameraController mVirtualCameraController;
    private VirtualAudioController mVirtualAudioController;
    private final IBinder mAppToken;
    private final VirtualDeviceParams mParams;
    @GuardedBy("mVirtualDeviceLock")
    private final SparseIntArray mDevicePolicies;
    @GuardedBy("mVirtualDeviceLock")
    private final SparseArray<VirtualDisplayWrapper> mVirtualDisplays = new SparseArray<>();
    private IVirtualDeviceActivityListener mActivityListener;
    private GenericWindowPolicyController.ActivityListener mActivityListenerAdapter = null;
    private IVirtualDeviceSoundEffectListener mSoundEffectListener;
    private final DisplayManagerGlobal mDisplayManager;
    private final DisplayManagerInternal mDisplayManagerInternal;
    private final PowerManager mPowerManager;
    @GuardedBy("mIntentInterceptors")
    private final Map<IBinder, IntentFilter> mIntentInterceptors = new ArrayMap<>();
    @NonNull
    private final Consumer<ArraySet<Integer>> mRunningAppsChangedCallback;

    // The default setting for showing the pointer on new displays.
    @GuardedBy("mVirtualDeviceLock")
    private boolean mDefaultShowPointerIcon = true;
    @GuardedBy("mVirtualDeviceLock")
    @Nullable
    private LocaleList mLocaleList = null;

    // Lock for power operations for this virtual device that allow calling PowerManager.
    private final Object mPowerLock = new Object();
    @GuardedBy("mPowerLock")
    private boolean mLockdownActive = false;
    @GuardedBy("mPowerLock")
    private boolean mRequestedToBeAwake = true;

    @NonNull
    private final VirtualDevice mPublicVirtualDeviceObject;

    @GuardedBy("mVirtualDeviceLock")
    @NonNull
    private final Set<ComponentName> mActivityPolicyExemptions;
    @GuardedBy("mVirtualDeviceLock")
    @NonNull
    private final Set<String> mActivityPolicyPackageExemptions = new ArraySet<>();

    private class GwpcActivityListener implements GenericWindowPolicyController.ActivityListener {

        @Override
        public void onTopActivityChanged(int displayId, @NonNull ComponentName topActivity,
                @UserIdInt int userId) {
            try {
                mActivityListener.onTopActivityChanged(displayId, topActivity, userId);
            } catch (RemoteException e) {
                Slog.w(TAG, "Unable to call mActivityListener for display: " + displayId, e);
            }
        }

        @Override
        public void onDisplayEmpty(int displayId) {
            try {
                mActivityListener.onDisplayEmpty(displayId);
            } catch (RemoteException e) {
                Slog.w(TAG, "Unable to call mActivityListener for display: " + displayId, e);
            }
        }

        @Override
        public void onActivityLaunchBlocked(int displayId, @NonNull ActivityInfo activityInfo,
                @Nullable IntentSender intentSender) {
            Intent intent =
                    BlockedAppStreamingActivity.createIntent(activityInfo, getDisplayName());
            if (shouldShowBlockedActivityDialog(
                    activityInfo.getComponentName(), intent.getComponent())) {
                mContext.startActivityAsUser(
                        intent.addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle(),
                        UserHandle.SYSTEM);
            }

            if (Flags.activityControlApi()) {
                try {
                    mActivityListener.onActivityLaunchBlocked(
                            displayId,
                            activityInfo.getComponentName(),
                            UserHandle.getUserHandleForUid(activityInfo.applicationInfo.uid),
                            intentSender);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Unable to call mActivityListener for display: " + displayId, e);
                }
            }
        }

        @Override
        public void onSecureWindowShown(int displayId, @NonNull ActivityInfo activityInfo) {
            if (Flags.activityControlApi()) {
                try {
                    mActivityListener.onSecureWindowShown(
                            displayId,
                            activityInfo.getComponentName(),
                            UserHandle.getUserHandleForUid(activityInfo.applicationInfo.uid));
                } catch (RemoteException e) {
                    Slog.w(TAG, "Unable to call mActivityListener for display: " + displayId, e);
                }

                if (CompatChanges.isChangeEnabled(DO_NOT_SHOW_TOAST_WHEN_SECURE_SURFACE_SHOWN,
                        mOwnerPackageName,  UserHandle.getUserHandleForUid(mOwnerUid))) {
                    return;
                }
            }

            // If a virtual display isn't secure, the screen can't be captured. Show a warning toast
            // if the secure window is shown on a non-secure virtual display.
            DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
            Display display = displayManager.getDisplay(displayId);
            if (display != null) {
                if ((display.getFlags() & Display.FLAG_SECURE) == 0) {
                    showToastWhereUidIsRunning(activityInfo.applicationInfo.uid,
                            com.android.internal.R.string.vdm_secure_window,
                            Toast.LENGTH_LONG, mContext.getMainLooper());

                    Counter.logIncrementWithUid(
                            "virtual_devices.value_secure_window_blocked_count",
                            mAttributionSource.getUid());
                }
            } else {
                Slog.e(TAG, "Calling onSecureWindowShown on a non existent/connected display: "
                        + displayId);
            }
        }

        @Override
        public void onSecureWindowHidden(int displayId) {
            if (Flags.activityControlApi()) {
                try {
                    mActivityListener.onSecureWindowHidden(displayId);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Unable to call mActivityListener for display: " + displayId, e);
                }
            }
        }

        /**
         * Intercepts intent when matching any of the IntentFilter of any interceptor. Returns true
         * if the intent matches any filter notifying the DisplayPolicyController to abort the
         * activity launch to be replaced by the interception.
         */
        @Override
        public boolean shouldInterceptIntent(@NonNull Intent intent) {
            synchronized (mIntentInterceptors) {
                boolean hasInterceptedIntent = false;
                for (Map.Entry<IBinder, IntentFilter> interceptor
                        : mIntentInterceptors.entrySet()) {
                    IntentFilter intentFilter = interceptor.getValue();
                    // Explicitly match the actions because the intent filter will match any intent
                    // without an explicit action. If the intent has no action, then require that
                    // there are no actions specified in the filter either.
                    boolean explicitActionMatch =
                            intent.getAction() != null || intentFilter.countActions() == 0;
                    if (explicitActionMatch && intentFilter.match(
                            intent.getAction(), intent.getType(), intent.getScheme(),
                            intent.getData(), intent.getCategories(), TAG) >= 0) {
                        try {
                            // For privacy reasons, only returning the intents action and data.
                            // Any other required field will require a review.
                            IVirtualDeviceIntentInterceptor.Stub.asInterface(interceptor.getKey())
                                    .onIntentIntercepted(
                                            new Intent(intent.getAction(), intent.getData()));
                            hasInterceptedIntent = true;
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Unable to call mActivityListener", e);
                        }
                    }
                }
                return hasInterceptedIntent;
            }
        }
    }

    VirtualDeviceImpl(
            Context context,
            AssociationInfo associationInfo,
            VirtualDeviceManagerService service,
            VirtualDeviceLog virtualDeviceLog,
            IBinder token,
            AttributionSource attributionSource,
            int deviceId,
            CameraAccessController cameraAccessController,
            PendingTrampolineCallback pendingTrampolineCallback,
            IVirtualDeviceActivityListener activityListener,
            IVirtualDeviceSoundEffectListener soundEffectListener,
            Consumer<ArraySet<Integer>> runningAppsChangedCallback,
            VirtualDeviceParams params) {
        this(
                context,
                associationInfo,
                service,
                virtualDeviceLog,
                token,
                attributionSource,
                deviceId,
                /* inputController= */ null,
                cameraAccessController,
                pendingTrampolineCallback,
                activityListener,
                soundEffectListener,
                runningAppsChangedCallback,
                params,
                DisplayManagerGlobal.getInstance(),
                isVirtualCameraEnabled()
                        ? new VirtualCameraController(
                                params.getDevicePolicy(POLICY_TYPE_CAMERA), deviceId)
                        : null);
    }

    @VisibleForTesting
    VirtualDeviceImpl(
            Context context,
            AssociationInfo associationInfo,
            VirtualDeviceManagerService service,
            VirtualDeviceLog virtualDeviceLog,
            IBinder token,
            AttributionSource attributionSource,
            int deviceId,
            InputController inputController,
            CameraAccessController cameraAccessController,
            PendingTrampolineCallback pendingTrampolineCallback,
            IVirtualDeviceActivityListener activityListener,
            IVirtualDeviceSoundEffectListener soundEffectListener,
            Consumer<ArraySet<Integer>> runningAppsChangedCallback,
            VirtualDeviceParams params,
            DisplayManagerGlobal displayManager,
            VirtualCameraController virtualCameraController) {
        mVirtualDeviceLog = virtualDeviceLog;
        mOwnerPackageName = attributionSource.getPackageName();
        mAttributionSource = attributionSource;
        UserHandle ownerUserHandle = UserHandle.getUserHandleForUid(attributionSource.getUid());
        mContext = context.createContextAsUser(ownerUserHandle, 0);
        mAssociationInfo = associationInfo;
        mPersistentDeviceId = associationInfo == null
                ? null
                : createPersistentDeviceId(associationInfo.getId());
        mService = service;
        mPendingTrampolineCallback = pendingTrampolineCallback;
        mActivityListener = activityListener;
        mSoundEffectListener = soundEffectListener;
        mRunningAppsChangedCallback = runningAppsChangedCallback;
        mOwnerUid = attributionSource.getUid();
        mDeviceId = deviceId;
        mAppToken = token;
        mParams = params;
        mDevicePolicies = params.getDevicePolicies();
        mDisplayManager = displayManager;
        mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
        mPowerManager = context.getSystemService(PowerManager.class);

        if (mDevicePolicies.get(POLICY_TYPE_CLIPBOARD, DEVICE_POLICY_DEFAULT)
                != DEVICE_POLICY_DEFAULT) {
            if (mContext.checkCallingOrSelfPermission(ADD_TRUSTED_DISPLAY)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires ADD_TRUSTED_DISPLAY permission to "
                        + "set a custom clipboard policy.");
            }
        }

        int flags = DEFAULT_VIRTUAL_DISPLAY_FLAGS;
        if (mParams.getLockState() == VirtualDeviceParams.LOCK_STATE_ALWAYS_UNLOCKED) {
            if (mContext.checkCallingOrSelfPermission(ADD_ALWAYS_UNLOCKED_DISPLAY)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires ADD_ALWAYS_UNLOCKED_DISPLAY permission to "
                        + "create an always unlocked virtual device.");
            }
            flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED;
        }
        mBaseVirtualDisplayFlags = flags;

        if (inputController == null) {
            mInputController = new InputController(
                    context.getMainThreadHandler(),
                    context.getSystemService(WindowManager.class), mAttributionSource);
        } else {
            mInputController = inputController;
        }
        mSensorController = new SensorController(this, mDeviceId, mAttributionSource,
                mParams.getVirtualSensorCallback(), mParams.getVirtualSensorConfigs());
        mCameraAccessController = cameraAccessController;
        if (mCameraAccessController != null) {
            mCameraAccessController.startObservingIfNeeded();
        }
        mVirtualCameraController = virtualCameraController;
        try {
            token.linkToDeath(this, 0);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        mVirtualDeviceLog.logCreated(deviceId, mOwnerUid);

        mPublicVirtualDeviceObject = new VirtualDevice(
                this, getDeviceId(), getPersistentDeviceId(), mParams.getName(), getDisplayName());

        mActivityPolicyExemptions = new ArraySet<>(
                mParams.getDevicePolicy(POLICY_TYPE_ACTIVITY) == DEVICE_POLICY_DEFAULT
                        ? mParams.getBlockedActivities()
                        : mParams.getAllowedActivities());

        if (mParams.getInputMethodComponent() != null) {
            final String imeId = mParams.getInputMethodComponent().flattenToShortString();
            Slog.d(TAG, "Setting custom input method " + imeId + " as default for virtual device "
                    + deviceId);
            InputMethodManagerInternal.get().setVirtualDeviceInputMethodForAllUsers(
                    mDeviceId, imeId);
        }
    }

    void onLockdownChanged(boolean lockdownActive) {
        synchronized (mPowerLock) {
            if (lockdownActive != mLockdownActive) {
                mLockdownActive = lockdownActive;
                if (mLockdownActive) {
                    goToSleepInternal(PowerManager.GO_TO_SLEEP_REASON_DISPLAY_GROUPS_TURNED_OFF);
                } else if (mRequestedToBeAwake) {
                    wakeUpInternal(PowerManager.WAKE_REASON_DISPLAY_GROUP_TURNED_ON,
                            "android.server.companion.virtual:LOCKDOWN_ENDED");
                }
            }
        }
    }

    @VisibleForTesting
    SensorController getSensorControllerForTest() {
        return mSensorController;
    }

    static String createPersistentDeviceId(int associationId) {
        return PERSISTENT_ID_PREFIX_CDM_ASSOCIATION + associationId;
    }

    /**
     * Returns the flags that should be added to any virtual displays created on this virtual
     * device.
     */
    int getBaseVirtualDisplayFlags() {
        return mBaseVirtualDisplayFlags;
    }

    /** Returns the camera access controller of this device. */
    CameraAccessController getCameraAccessController() {
        return mCameraAccessController;
    }

    /** Returns the device display name. */
    CharSequence getDisplayName() {
        return mAssociationInfo == null ? mParams.getName() : mAssociationInfo.getDisplayName();
    }

    String getDeviceProfile() {
        return mAssociationInfo == null ? null : mAssociationInfo.getDeviceProfile();
    }

    /** Returns the public representation of the device. */
    VirtualDevice getPublicVirtualDeviceObject() {
        return mPublicVirtualDeviceObject;
    }

    /** Returns the locale of the device. */
    LocaleList getDeviceLocaleList() {
        synchronized (mVirtualDeviceLock) {
            return mLocaleList;
        }
    }

    /**
     * Setter for listeners that live in the client process, namely in
     * {@link android.companion.virtual.VirtualDeviceInternal}.
     *
     * This is needed for virtual devices that are created by the system, as the VirtualDeviceImpl
     * object is created before the returned VirtualDeviceInternal one.
     */
    @Override // Binder call
    public void setListeners(@NonNull IVirtualDeviceActivityListener activityListener,
            @NonNull IVirtualDeviceSoundEffectListener soundEffectListener) {
        mActivityListener = Objects.requireNonNull(activityListener);
        mSoundEffectListener = Objects.requireNonNull(soundEffectListener);
    }

    @Override  // Binder call
    public @VirtualDeviceParams.DevicePolicy int getDevicePolicy(
            @VirtualDeviceParams.PolicyType int policyType) {
        synchronized (mVirtualDeviceLock) {
            return mDevicePolicies.get(policyType, DEVICE_POLICY_DEFAULT);
        }
    }

    /** Returns device-specific audio session id for playback. */
    public int getAudioPlaybackSessionId() {
        return mParams.getAudioPlaybackSessionId();
    }

    /** Returns device-specific audio session id for recording. */
    public int getAudioRecordingSessionId() {
        return mParams.getAudioRecordingSessionId();
    }

    /** Returns the unique device ID of this device. */
    @Override // Binder call
    public int getDeviceId() {
        return mDeviceId;
    }

    /** Returns the unique device ID of this device. */
    @Override // Binder call
    public @Nullable String getPersistentDeviceId() {
        return mPersistentDeviceId;
    }

    @Override // Binder call
    public int getAssociationId() {
        return mAssociationInfo == null
                ? VirtualDeviceManagerService.CDM_ASSOCIATION_ID_NONE
                : mAssociationInfo.getId();
    }

    @Override // Binder call
    public void goToSleep() {
        checkCallerIsDeviceOwner();
        synchronized (mPowerLock) {
            mRequestedToBeAwake = false;
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            goToSleepInternal(PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public void wakeUp() {
        checkCallerIsDeviceOwner();
        synchronized (mPowerLock) {
            mRequestedToBeAwake = true;
            if (mLockdownActive) {
                Slog.w(TAG, "Cannot wake up device during lockdown.");
                return;
            }
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            wakeUpInternal(PowerManager.WAKE_REASON_POWER_BUTTON,
                    "android.server.companion.virtual:DEVICE_ON");
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public void launchPendingIntent(int displayId, PendingIntent pendingIntent,
            ResultReceiver resultReceiver) {
        checkCallerIsDeviceOwner();
        Objects.requireNonNull(pendingIntent);
        synchronized (mVirtualDeviceLock) {
            checkDisplayOwnedByVirtualDeviceLocked(displayId);
        }
        if (pendingIntent.isActivity()) {
            try {
                sendPendingIntent(displayId, pendingIntent);
                resultReceiver.send(VirtualDeviceManager.LAUNCH_SUCCESS, null);
            } catch (PendingIntent.CanceledException e) {
                Slog.w(TAG, "Pending intent canceled", e);
                resultReceiver.send(
                        VirtualDeviceManager.LAUNCH_FAILURE_PENDING_INTENT_CANCELED, null);
            }
        } else {
            PendingTrampoline pendingTrampoline = new PendingTrampoline(pendingIntent,
                    resultReceiver, displayId);
            mPendingTrampolineCallback.startWaitingForPendingTrampoline(pendingTrampoline);
            mContext.getMainThreadHandler().postDelayed(() -> {
                pendingTrampoline.mResultReceiver.send(
                        VirtualDeviceManager.LAUNCH_FAILURE_NO_ACTIVITY, null);
                mPendingTrampolineCallback.stopWaitingForPendingTrampoline(pendingTrampoline);
            }, PENDING_TRAMPOLINE_TIMEOUT_MS);
            try {
                sendPendingIntent(displayId, pendingIntent);
            } catch (PendingIntent.CanceledException e) {
                Slog.w(TAG, "Pending intent canceled", e);
                resultReceiver.send(
                        VirtualDeviceManager.LAUNCH_FAILURE_PENDING_INTENT_CANCELED, null);
                mPendingTrampolineCallback.stopWaitingForPendingTrampoline(pendingTrampoline);
            }
        }
    }

    @Override // Binder call
    public void addActivityPolicyExemption(@NonNull ActivityPolicyExemption exemption) {
        checkCallerIsDeviceOwner();
        final int displayId = exemption.getDisplayId();
        if (exemption.getComponentName() == null || displayId != Display.INVALID_DISPLAY) {
            if (!Flags.activityControlApi()) {
                return;
            }
        }
        synchronized (mVirtualDeviceLock) {
            if (displayId != Display.INVALID_DISPLAY) {
                checkDisplayOwnedByVirtualDeviceLocked(displayId);
                if (exemption.getComponentName() != null) {
                    mVirtualDisplays.get(displayId).getWindowPolicyController()
                            .addActivityPolicyExemption(exemption.getComponentName());
                } else if (exemption.getPackageName() != null) {
                    mVirtualDisplays.get(displayId).getWindowPolicyController()
                            .addActivityPolicyExemption(exemption.getPackageName());
                }
            } else {
                if (exemption.getComponentName() != null
                        && mActivityPolicyExemptions.add(exemption.getComponentName())) {
                    for (int i = 0; i < mVirtualDisplays.size(); i++) {
                        mVirtualDisplays.valueAt(i).getWindowPolicyController()
                                .addActivityPolicyExemption(exemption.getComponentName());
                    }
                } else if (exemption.getPackageName() != null
                        && mActivityPolicyPackageExemptions.add(exemption.getPackageName())) {
                    for (int i = 0; i < mVirtualDisplays.size(); i++) {
                        mVirtualDisplays.valueAt(i).getWindowPolicyController()
                                .addActivityPolicyExemption(exemption.getPackageName());
                    }
                }
            }
        }
    }

    @Override // Binder call
    public void removeActivityPolicyExemption(@NonNull ActivityPolicyExemption exemption) {
        checkCallerIsDeviceOwner();
        final int displayId = exemption.getDisplayId();
        if (exemption.getComponentName() == null || displayId != Display.INVALID_DISPLAY) {
            if (!Flags.activityControlApi()) {
                return;
            }
        }
        synchronized (mVirtualDeviceLock) {
            if (displayId != Display.INVALID_DISPLAY) {
                checkDisplayOwnedByVirtualDeviceLocked(displayId);
                if (exemption.getComponentName() != null) {
                    mVirtualDisplays.get(displayId).getWindowPolicyController()
                            .removeActivityPolicyExemption(exemption.getComponentName());
                } else if (exemption.getPackageName() != null) {
                    mVirtualDisplays.get(displayId).getWindowPolicyController()
                            .removeActivityPolicyExemption(exemption.getPackageName());
                }
            } else {
                if (exemption.getComponentName() != null
                        && mActivityPolicyExemptions.remove(exemption.getComponentName())) {
                    for (int i = 0; i < mVirtualDisplays.size(); i++) {
                        mVirtualDisplays.valueAt(i).getWindowPolicyController()
                                .removeActivityPolicyExemption(exemption.getComponentName());
                    }
                } else if (exemption.getPackageName() != null
                        && mActivityPolicyPackageExemptions.remove(exemption.getPackageName())) {
                    for (int i = 0; i < mVirtualDisplays.size(); i++) {
                        mVirtualDisplays.valueAt(i).getWindowPolicyController()
                                .removeActivityPolicyExemption(exemption.getPackageName());
                    }
                }
            }
        }
    }

    private void sendPendingIntent(int displayId, PendingIntent pendingIntent)
            throws PendingIntent.CanceledException {
        final ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId);
        options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS);
        pendingIntent.send(
                mContext,
                /* code= */ 0,
                /* intent= */ null,
                /* onFinished= */ null,
                /* handler= */ null,
                /* requiredPermission= */ null,
                options.toBundle());
    }

    @Override // Binder call
    public void close() {
        // Remove about-to-be-closed virtual device from the service before butchering it.
        if (!mService.removeVirtualDevice(mDeviceId)) {
            // Device is already closed.
            return;
        }

        mVirtualDeviceLog.logClosed(mDeviceId, mOwnerUid);

        final long ident = Binder.clearCallingIdentity();
        try {
            VirtualDisplayWrapper[] virtualDisplaysToBeReleased;
            synchronized (mVirtualDeviceLock) {
                if (mVirtualAudioController != null) {
                    mVirtualAudioController.stopListening();
                    mVirtualAudioController = null;
                }
                mLocaleList = null;
                virtualDisplaysToBeReleased = new VirtualDisplayWrapper[mVirtualDisplays.size()];
                for (int i = 0; i < mVirtualDisplays.size(); i++) {
                    virtualDisplaysToBeReleased[i] = mVirtualDisplays.valueAt(i);
                }
                mVirtualDisplays.clear();
            }
            // Destroy the display outside locked section.
            for (VirtualDisplayWrapper virtualDisplayWrapper : virtualDisplaysToBeReleased) {
                mDisplayManager.releaseVirtualDisplay(virtualDisplayWrapper.getToken());
                // The releaseVirtualDisplay call above won't trigger
                // VirtualDeviceImpl.onVirtualDisplayRemoved callback because we already removed the
                // virtual device from the service - we release the other display-tied resources
                // here with the guarantee it will be done exactly once.
                releaseOwnedVirtualDisplayResources(virtualDisplayWrapper);
            }

            mAppToken.unlinkToDeath(this, 0);
            if (mCameraAccessController != null) {
                mCameraAccessController.stopObservingIfNeeded();
            }

            // Clear any previously set custom IME components.
            if (mParams.getInputMethodComponent() != null) {
                InputMethodManagerInternal.get().setVirtualDeviceInputMethodForAllUsers(
                        mDeviceId, null);
            }

            mInputController.close();
            mSensorController.close();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        if (mVirtualCameraController != null) {
            mVirtualCameraController.close();
        }
    }

    @Override
    public void binderDied() {
        close();
    }

    @Override
    @RequiresPermission(android.Manifest.permission.CAMERA_INJECT_EXTERNAL_CAMERA)
    public void onRunningAppsChanged(ArraySet<Integer> runningUids) {
        if (mCameraAccessController != null) {
            mCameraAccessController.blockCameraAccessIfNeeded(runningUids);
        }
        mRunningAppsChangedCallback.accept(runningUids);
    }

    @VisibleForTesting
    VirtualAudioController getVirtualAudioControllerForTesting() {
        return mVirtualAudioController;
    }

    @Override // Binder call
    public void onAudioSessionStarting(int displayId,
            @NonNull IAudioRoutingCallback routingCallback,
            @Nullable IAudioConfigChangedCallback configChangedCallback) {
        checkCallerIsDeviceOwner();
        synchronized (mVirtualDeviceLock) {
            checkDisplayOwnedByVirtualDeviceLocked(displayId);
            if (mVirtualAudioController == null) {
                mVirtualAudioController = new VirtualAudioController(mContext, mAttributionSource);
                GenericWindowPolicyController gwpc =
                        mVirtualDisplays.get(displayId).getWindowPolicyController();
                mVirtualAudioController.startListening(gwpc, routingCallback,
                        configChangedCallback);
            }
        }
    }

    @Override // Binder call
    public void onAudioSessionEnded() {
        checkCallerIsDeviceOwner();
        synchronized (mVirtualDeviceLock) {
            if (mVirtualAudioController != null) {
                mVirtualAudioController.stopListening();
                mVirtualAudioController = null;
            }
        }
    }

    @Override // Binder call
    public void setDevicePolicy(@VirtualDeviceParams.DynamicPolicyType int policyType,
            @VirtualDeviceParams.DevicePolicy int devicePolicy) {
        checkCallerIsDeviceOwner();

        switch (policyType) {
            case POLICY_TYPE_RECENTS:
                synchronized (mVirtualDeviceLock) {
                    mDevicePolicies.put(policyType, devicePolicy);
                    for (int i = 0; i < mVirtualDisplays.size(); i++) {
                        VirtualDisplayWrapper wrapper = mVirtualDisplays.valueAt(i);
                        if (wrapper.isTrusted()) {
                            wrapper.getWindowPolicyController()
                                    .setShowInHostDeviceRecents(
                                            devicePolicy == DEVICE_POLICY_DEFAULT);
                        }
                    }
                }
                break;
            case POLICY_TYPE_ACTIVITY:
                synchronized (mVirtualDeviceLock) {
                    if (getDevicePolicy(policyType) != devicePolicy) {
                        mActivityPolicyExemptions.clear();
                        mActivityPolicyPackageExemptions.clear();
                    }
                    mDevicePolicies.put(policyType, devicePolicy);
                    for (int i = 0; i < mVirtualDisplays.size(); i++) {
                        mVirtualDisplays.valueAt(i).getWindowPolicyController()
                                .setActivityLaunchDefaultAllowed(
                                        devicePolicy == DEVICE_POLICY_DEFAULT);
                    }
                }
                break;
            case POLICY_TYPE_CLIPBOARD:
                if (devicePolicy == DEVICE_POLICY_CUSTOM
                            && mContext.checkCallingOrSelfPermission(ADD_TRUSTED_DISPLAY)
                            != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Requires ADD_TRUSTED_DISPLAY permission to "
                            + "set a custom clipboard policy.");
                }
                synchronized (mVirtualDeviceLock) {
                    for (int i = 0; i < mVirtualDisplays.size(); i++) {
                        VirtualDisplayWrapper wrapper = mVirtualDisplays.valueAt(i);
                        if (!wrapper.isTrusted() && !wrapper.isMirror()) {
                            throw new SecurityException("All displays must be trusted for "
                                    + "devices with custom clipboard policy.");
                        }
                    }
                    mDevicePolicies.put(policyType, devicePolicy);
                }
                break;
            case POLICY_TYPE_BLOCKED_ACTIVITY:
                if (Flags.activityControlApi()) {
                    synchronized (mVirtualDeviceLock) {
                        mDevicePolicies.put(policyType, devicePolicy);
                    }
                }
                break;
            default:
                throw new IllegalArgumentException("Device policy " + policyType
                        + " cannot be changed at runtime. ");
        }
    }

    @Override // Binder call
    public void setDevicePolicyForDisplay(int displayId,
            @VirtualDeviceParams.DynamicDisplayPolicyType int policyType,
            @VirtualDeviceParams.DevicePolicy int devicePolicy) {
        checkCallerIsDeviceOwner();
        if (!Flags.activityControlApi()) {
            return;
        }
        synchronized (mVirtualDeviceLock) {
            checkDisplayOwnedByVirtualDeviceLocked(displayId);
            switch (policyType) {
                case POLICY_TYPE_RECENTS:
                    VirtualDisplayWrapper wrapper = mVirtualDisplays.get(displayId);
                    if (wrapper.isTrusted()) {
                        wrapper.getWindowPolicyController()
                                .setShowInHostDeviceRecents(devicePolicy == DEVICE_POLICY_DEFAULT);
                    }
                    break;
                case POLICY_TYPE_ACTIVITY:
                    mVirtualDisplays.get(displayId).getWindowPolicyController()
                            .setActivityLaunchDefaultAllowed(devicePolicy == DEVICE_POLICY_DEFAULT);
                    break;
                default:
                    throw new IllegalArgumentException("Device policy " + policyType
                            + " cannot be changed for a specific display. ");
            }
        }
    }

    @Override // Binder call
    public void createVirtualDpad(VirtualDpadConfig config, @NonNull IBinder deviceToken) {
        checkCallerIsDeviceOwner();
        Objects.requireNonNull(config);
        checkVirtualInputDeviceDisplayIdAssociation(config.getAssociatedDisplayId());
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.createDpad(config.getInputDeviceName(), config.getVendorId(),
                    config.getProductId(), deviceToken,
                    getTargetDisplayIdForInput(config.getAssociatedDisplayId()));
        } catch (InputController.DeviceCreationException e) {
            throw new IllegalArgumentException(e);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public void createVirtualKeyboard(VirtualKeyboardConfig config, @NonNull IBinder deviceToken) {
        checkCallerIsDeviceOwner();
        Objects.requireNonNull(config);
        checkVirtualInputDeviceDisplayIdAssociation(config.getAssociatedDisplayId());
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.createKeyboard(config.getInputDeviceName(), config.getVendorId(),
                    config.getProductId(), deviceToken,
                    getTargetDisplayIdForInput(config.getAssociatedDisplayId()),
                    config.getLanguageTag(), config.getLayoutType());
            synchronized (mVirtualDeviceLock) {
                mLocaleList = LocaleList.forLanguageTags(config.getLanguageTag());
            }
        } catch (InputController.DeviceCreationException e) {
            throw new IllegalArgumentException(e);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public void createVirtualMouse(VirtualMouseConfig config, @NonNull IBinder deviceToken) {
        checkCallerIsDeviceOwner();
        Objects.requireNonNull(config);
        checkVirtualInputDeviceDisplayIdAssociation(config.getAssociatedDisplayId());
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.createMouse(config.getInputDeviceName(), config.getVendorId(),
                    config.getProductId(), deviceToken, config.getAssociatedDisplayId());
        } catch (InputController.DeviceCreationException e) {
            throw new IllegalArgumentException(e);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public void createVirtualTouchscreen(VirtualTouchscreenConfig config,
            @NonNull IBinder deviceToken) {
        checkCallerIsDeviceOwner();
        Objects.requireNonNull(config);
        checkVirtualInputDeviceDisplayIdAssociation(config.getAssociatedDisplayId());
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.createTouchscreen(config.getInputDeviceName(), config.getVendorId(),
                    config.getProductId(), deviceToken, config.getAssociatedDisplayId(),
                    config.getHeight(), config.getWidth());
        } catch (InputController.DeviceCreationException e) {
            throw new IllegalArgumentException(e);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public void createVirtualNavigationTouchpad(VirtualNavigationTouchpadConfig config,
            @NonNull IBinder deviceToken) {
        checkCallerIsDeviceOwner();
        Objects.requireNonNull(config);
        checkVirtualInputDeviceDisplayIdAssociation(config.getAssociatedDisplayId());
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.createNavigationTouchpad(
                    config.getInputDeviceName(), config.getVendorId(),
                    config.getProductId(), deviceToken,
                    getTargetDisplayIdForInput(config.getAssociatedDisplayId()),
                    config.getHeight(), config.getWidth());
        } catch (InputController.DeviceCreationException e) {
            throw new IllegalArgumentException(e);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public void createVirtualStylus(@NonNull VirtualStylusConfig config,
            @NonNull IBinder deviceToken) {
        checkCallerIsDeviceOwner();
        Objects.requireNonNull(config);
        Objects.requireNonNull(deviceToken);
        checkVirtualInputDeviceDisplayIdAssociation(config.getAssociatedDisplayId());
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.createStylus(config.getInputDeviceName(), config.getVendorId(),
                    config.getProductId(), deviceToken, config.getAssociatedDisplayId(),
                    config.getHeight(), config.getWidth());
        } catch (InputController.DeviceCreationException e) {
            throw new IllegalArgumentException(e);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public void createVirtualRotaryEncoder(@NonNull VirtualRotaryEncoderConfig config,
            @NonNull IBinder deviceToken) {
        checkCallerIsDeviceOwner();
        Objects.requireNonNull(config);
        Objects.requireNonNull(deviceToken);
        checkVirtualInputDeviceDisplayIdAssociation(config.getAssociatedDisplayId());
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.createRotaryEncoder(config.getInputDeviceName(), config.getVendorId(),
                    config.getProductId(), deviceToken,
                    getTargetDisplayIdForInput(config.getAssociatedDisplayId()));
        } catch (InputController.DeviceCreationException e) {
            throw new IllegalArgumentException(e);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public void unregisterInputDevice(IBinder token) {
        checkCallerIsDeviceOwner();
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.unregisterInputDevice(token);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public int getInputDeviceId(IBinder token) {
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.getInputDeviceId(token);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }


    @Override // Binder call
    public boolean sendDpadKeyEvent(IBinder token, VirtualKeyEvent event) {
        checkCallerIsDeviceOwner();
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.sendDpadKeyEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public boolean sendKeyEvent(IBinder token, VirtualKeyEvent event) {
        checkCallerIsDeviceOwner();
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.sendKeyEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public boolean sendButtonEvent(IBinder token, VirtualMouseButtonEvent event) {
        checkCallerIsDeviceOwner();
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.sendButtonEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public boolean sendTouchEvent(IBinder token, VirtualTouchEvent event) {
        checkCallerIsDeviceOwner();
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.sendTouchEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public boolean sendRelativeEvent(IBinder token, VirtualMouseRelativeEvent event) {
        checkCallerIsDeviceOwner();
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.sendRelativeEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public boolean sendScrollEvent(IBinder token, VirtualMouseScrollEvent event) {
        checkCallerIsDeviceOwner();
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.sendScrollEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public PointF getCursorPosition(IBinder token) {
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.getCursorPosition(token);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public boolean sendStylusMotionEvent(@NonNull IBinder token,
            @NonNull VirtualStylusMotionEvent event) {
        checkCallerIsDeviceOwner();
        Objects.requireNonNull(token);
        Objects.requireNonNull(event);
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.sendStylusMotionEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public boolean sendStylusButtonEvent(@NonNull IBinder token,
            @NonNull VirtualStylusButtonEvent event) {
        checkCallerIsDeviceOwner();
        Objects.requireNonNull(token);
        Objects.requireNonNull(event);
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.sendStylusButtonEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public boolean sendRotaryEncoderScrollEvent(@NonNull IBinder token,
            @NonNull VirtualRotaryEncoderScrollEvent event) {
        checkCallerIsDeviceOwner();
        final long ident = Binder.clearCallingIdentity();
        try {
            return mInputController.sendRotaryEncoderScrollEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public void setShowPointerIcon(boolean showPointerIcon) {
        checkCallerIsDeviceOwner();
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mVirtualDeviceLock) {
                mDefaultShowPointerIcon = showPointerIcon;
                for (int i = 0; i < mVirtualDisplays.size(); i++) {
                    VirtualDisplayWrapper wrapper = mVirtualDisplays.valueAt(i);
                    if (wrapper.isTrusted() || wrapper.isMirror()) {
                        mInputController.setShowPointerIcon(
                                mDefaultShowPointerIcon, mVirtualDisplays.keyAt(i));
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public void setDisplayImePolicy(int displayId, @WindowManager.DisplayImePolicy int policy) {
        checkCallerIsDeviceOwner();
        synchronized (mVirtualDeviceLock) {
            checkDisplayOwnedByVirtualDeviceLocked(displayId);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            mInputController.setDisplayImePolicy(displayId, policy);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    @Nullable
    public List<VirtualSensor> getVirtualSensorList() {
        checkCallerIsDeviceOwner();
        return mSensorController.getSensorList();
    }

    @Nullable
    VirtualSensor getVirtualSensorByHandle(int handle) {
        return mSensorController.getSensorByHandle(handle);
    }

    @Override // Binder call
    public boolean sendSensorEvent(@NonNull IBinder token, @NonNull VirtualSensorEvent event) {
        checkCallerIsDeviceOwner();
        final long ident = Binder.clearCallingIdentity();
        try {
            return mSensorController.sendSensorEvent(token, event);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public boolean sendSensorAdditionalInfo(@NonNull IBinder token,
            @NonNull VirtualSensorAdditionalInfo info) {
        checkCallerIsDeviceOwner();
        final long ident = Binder.clearCallingIdentity();
        try {
            return mSensorController.sendSensorAdditionalInfo(token, info);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public void registerIntentInterceptor(IVirtualDeviceIntentInterceptor intentInterceptor,
            IntentFilter filter) {
        checkCallerIsDeviceOwner();
        Objects.requireNonNull(intentInterceptor);
        Objects.requireNonNull(filter);
        synchronized (mIntentInterceptors) {
            mIntentInterceptors.put(intentInterceptor.asBinder(), filter);
        }
    }

    @Override // Binder call
    public void unregisterIntentInterceptor(
            @NonNull IVirtualDeviceIntentInterceptor intentInterceptor) {
        checkCallerIsDeviceOwner();
        Objects.requireNonNull(intentInterceptor);
        synchronized (mIntentInterceptors) {
            mIntentInterceptors.remove(intentInterceptor.asBinder());
        }
    }

    @Override // Binder call
    public void registerVirtualCamera(@NonNull VirtualCameraConfig cameraConfig)
            throws RemoteException {
        checkCallerIsDeviceOwner();
        Objects.requireNonNull(cameraConfig);
        if (mVirtualCameraController == null) {
            throw new UnsupportedOperationException("Virtual camera controller is not available");
        }
        mVirtualCameraController.registerCamera(cameraConfig, mAttributionSource);
    }

    @Override // Binder call
    public void unregisterVirtualCamera(@NonNull VirtualCameraConfig cameraConfig)
            throws RemoteException {
        checkCallerIsDeviceOwner();
        Objects.requireNonNull(cameraConfig);
        if (mVirtualCameraController == null) {
            throw new UnsupportedOperationException("Virtual camera controller is not available");
        }
        mVirtualCameraController.unregisterCamera(cameraConfig);
    }

    @Override // Binder call
    public String getVirtualCameraId(@NonNull VirtualCameraConfig cameraConfig)
            throws RemoteException {
        Objects.requireNonNull(cameraConfig);
        if (mVirtualCameraController == null) {
            throw new UnsupportedOperationException("Virtual camera controller is not available");
        }
        return mVirtualCameraController.getCameraId(cameraConfig);
    }

    @Override
    public boolean hasCustomAudioInputSupport() throws RemoteException {
        return hasCustomAudioInputSupportInternal();
    }

    @Override
    public boolean canCreateMirrorDisplays() {
        return DEVICE_PROFILES_ALLOWING_MIRROR_DISPLAYS.contains(getDeviceProfile());
    }

    private boolean hasCustomAudioInputSupportInternal() {
        if (!android.media.audiopolicy.Flags.audioMixTestApi()) {
            return false;
        }
        if (!android.media.audiopolicy.Flags.recordAudioDeviceAwarePermission()) {
            return false;
        }

        if (getDevicePolicy(POLICY_TYPE_AUDIO) == VirtualDeviceParams.DEVICE_POLICY_CUSTOM) {
            return true;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            AudioManager audioManager = mContext.getSystemService(AudioManager.class);
            for (AudioMix mix : audioManager.getRegisteredPolicyMixes()) {
                if (mix.matchesVirtualDeviceId(getDeviceId())
                        && mix.getMixType() == AudioMix.MIX_TYPE_RECORDERS) {
                    return true;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return false;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        String indent = "    ";
        fout.println("  VirtualDevice: ");
        fout.println(indent + "mDeviceId: " + mDeviceId);
        fout.println(indent + "mAssociationId: " + getAssociationId());
        fout.println(indent + "mOwnerPackageName: " + mOwnerPackageName);
        fout.println(indent + "mParams: ");
        mParams.dump(fout, indent + indent);
        fout.println(indent + "mVirtualDisplayIds: ");
        synchronized (mVirtualDeviceLock) {
            for (int i = 0; i < mVirtualDisplays.size(); i++) {
                fout.println(indent + "  " + mVirtualDisplays.keyAt(i));
            }
            fout.println("    mDevicePolicies: " + mDevicePolicies);
            fout.println(indent + "mDefaultShowPointerIcon: " + mDefaultShowPointerIcon);
        }
        mInputController.dump(fout);
        mSensorController.dump(fout);
        if (mVirtualCameraController != null) {
            mVirtualCameraController.dump(fout, indent);
        }
        fout.println(
                indent + "hasCustomAudioInputSupport: " + hasCustomAudioInputSupportInternal());
    }

    // For display mirroring, we want to dispatch all key events to the source (default) display,
    // as the virtual display doesn't have any focused windows. Hence, call this for
    // associating any input device to the source display if the input device emits any key events.
    private int getTargetDisplayIdForInput(int displayId) {
        DisplayManagerInternal displayManager = LocalServices.getService(
                DisplayManagerInternal.class);
        int mirroredDisplayId = displayManager.getDisplayIdToMirror(displayId);
        return mirroredDisplayId == Display.INVALID_DISPLAY ? displayId : mirroredDisplayId;
    }

    private GenericWindowPolicyController createWindowPolicyController(
            @NonNull Set<String> displayCategories) {
        final boolean activityLaunchAllowedByDefault =
                getDevicePolicy(POLICY_TYPE_ACTIVITY) == DEVICE_POLICY_DEFAULT;
        final boolean crossTaskNavigationAllowedByDefault =
                mParams.getDefaultNavigationPolicy() == NAVIGATION_POLICY_DEFAULT_ALLOWED;
        final boolean showTasksInHostDeviceRecents =
                getDevicePolicy(POLICY_TYPE_RECENTS) == DEVICE_POLICY_DEFAULT;

        synchronized (mVirtualDeviceLock) {
            if (mActivityListenerAdapter == null) {
                mActivityListenerAdapter = new GwpcActivityListener();
            }

            return new GenericWindowPolicyController(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS,
                    mAttributionSource,
                    getAllowedUserHandles(),
                    activityLaunchAllowedByDefault,
                    mActivityPolicyExemptions,
                    mActivityPolicyPackageExemptions,
                    crossTaskNavigationAllowedByDefault,
                    /* crossTaskNavigationExemptions= */crossTaskNavigationAllowedByDefault
                            ? mParams.getBlockedCrossTaskNavigations()
                            : mParams.getAllowedCrossTaskNavigations(),
                    mActivityListenerAdapter,
                    displayCategories,
                    showTasksInHostDeviceRecents,
                    mParams.getHomeComponent());
        }
    }

    @Override // Binder call
    public int createVirtualDisplay(@NonNull VirtualDisplayConfig virtualDisplayConfig,
            @NonNull IVirtualDisplayCallback callback) {
        checkCallerIsDeviceOwner();

        final boolean isTrustedDisplay =
                (virtualDisplayConfig.getFlags() & DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED)
                        == DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED;
        if (!isTrustedDisplay && getDevicePolicy(POLICY_TYPE_CLIPBOARD) != DEVICE_POLICY_DEFAULT) {
            throw new SecurityException(
                "All displays must be trusted for devices with custom clipboard policy.");
        }

        GenericWindowPolicyController gwpc =
                createWindowPolicyController(virtualDisplayConfig.getDisplayCategories());

        // Create the display outside of the lock to avoid deadlock. DisplayManagerService will
        // acquire the global WM lock while creating the display. At the same time, WM may query
        // VDM and this virtual device to get policies, display ownership, etc.
        int displayId = mDisplayManagerInternal.createVirtualDisplay(virtualDisplayConfig,
                    callback, this, gwpc, mOwnerPackageName);
        if (displayId == Display.INVALID_DISPLAY) {
            return displayId;
        }

        // DisplayManagerService will call onVirtualDisplayCreated() after the display is created,
        // while holding its own lock to ensure that this device knows about the display before any
        // other display listeners are notified about the display creation.
        VirtualDisplayWrapper displayWrapper;
        boolean showPointer;
        synchronized (mVirtualDeviceLock) {
            if (!mVirtualDisplays.contains(displayId)) {
                throw new IllegalStateException("Virtual device was not notified about the "
                        + "creation of display with ID " + displayId);
            }
            displayWrapper = mVirtualDisplays.get(displayId);
            showPointer = mDefaultShowPointerIcon;
        }
        displayWrapper.acquireWakeLock();
        gwpc.registerRunningAppsChangedListener(/* listener= */ this);

        Binder.withCleanCallingIdentity(() -> {
            mInputController.setMouseScalingEnabled(false, displayId);
            mInputController.setDisplayEligibilityForPointerCapture(/* isEligible= */ false,
                    displayId);
            if (displayWrapper.isTrusted()) {
                mInputController.setShowPointerIcon(showPointer, displayId);
                mInputController.setDisplayImePolicy(displayId,
                        WindowManager.DISPLAY_IME_POLICY_LOCAL);
            } else {
                gwpc.setShowInHostDeviceRecents(true);
            }
        });

        Counter.logIncrementWithUid(
                "virtual_devices.value_virtual_display_created_count",
                mAttributionSource.getUid());
        return displayId;
    }

    private PowerManager.WakeLock createWakeLockForDisplay(int displayId) {
        if (Flags.deviceAwareDisplayPower()) {
            return null;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            PowerManager powerManager = mContext.getSystemService(PowerManager.class);
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                    TAG + ":" + displayId, displayId);
            return wakeLock;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean shouldShowBlockedActivityDialog(ComponentName blockedComponent,
            ComponentName blockedAppStreamingActivityComponent) {
        if (Objects.equals(blockedComponent, blockedAppStreamingActivityComponent)) {
            // Do not show the dialog if it was blocked for some reason already to avoid
            // infinite blocking loop.
            return false;
        }
        if (!Flags.activityControlApi()) {
            return true;
        }
        // Do not show the dialog if disabled by policy.
        return getDevicePolicy(POLICY_TYPE_BLOCKED_ACTIVITY) == DEVICE_POLICY_DEFAULT;
    }

    private ArraySet<UserHandle> getAllowedUserHandles() {
        ArraySet<UserHandle> result = new ArraySet<>();
        final long token = Binder.clearCallingIdentity();
        try {
            DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
            UserManager userManager = mContext.getSystemService(UserManager.class);
            for (UserHandle profile : userManager.getAllProfiles()) {
                int nearbyAppStreamingPolicy = dpm.getNearbyAppStreamingPolicy(
                        profile.getIdentifier());
                if (nearbyAppStreamingPolicy == NEARBY_STREAMING_ENABLED
                        || nearbyAppStreamingPolicy == NEARBY_STREAMING_NOT_CONTROLLED_BY_POLICY) {
                    result.add(profile);
                } else if (nearbyAppStreamingPolicy == NEARBY_STREAMING_SAME_MANAGED_ACCOUNT_ONLY) {
                    if (mParams.getUsersWithMatchingAccounts().contains(profile)) {
                        result.add(profile);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return result;
    }

    /**
     * DisplayManagerService is notifying this virtual device about the display creation. This
     * should happen before the DisplayManagerInternal#createVirtualDisplay() call above
     * returns.
     * This is called while holding the DisplayManagerService lock, so no heavy-weight work must
     * be done here and especially *** no calls to WindowManager! ***
     */
    public void onVirtualDisplayCreated(int displayId, IVirtualDisplayCallback callback,
            DisplayWindowPolicyController dwpc) {
        final boolean isMirrorDisplay =
                mDisplayManagerInternal.getDisplayIdToMirror(displayId) != Display.INVALID_DISPLAY;
        final boolean isTrustedDisplay =
                (mDisplayManagerInternal.getDisplayInfo(displayId).flags & Display.FLAG_TRUSTED)
                        == Display.FLAG_TRUSTED;

        GenericWindowPolicyController gwpc = (GenericWindowPolicyController) dwpc;
        gwpc.setDisplayId(displayId, isMirrorDisplay);
        PowerManager.WakeLock wakeLock =
                isTrustedDisplay ? createWakeLockForDisplay(displayId) : null;
        synchronized (mVirtualDeviceLock) {
            if (mVirtualDisplays.contains(displayId)) {
                Slog.wtf(TAG, "Virtual device already has a virtual display with ID " + displayId);
                return;
            }
            mVirtualDisplays.put(displayId, new VirtualDisplayWrapper(callback, gwpc, wakeLock,
                    isTrustedDisplay, isMirrorDisplay));
        }
    }

    /**
     * This is callback invoked by VirtualDeviceManagerService when VirtualDisplay was released
     * by DisplayManager (most probably caused by someone calling VirtualDisplay.close()).
     * At this point, the display is already released, but we still need to release the
     * corresponding wakeLock and unregister the RunningAppsChangedListener from corresponding
     * WindowPolicyController.
     *
     * Note that when the display is destroyed during VirtualDeviceImpl.close() call,
     * this callback won't be invoked because the display is removed from
     * VirtualDeviceManagerService before any resources are released.
     */
    void onVirtualDisplayRemoved(int displayId) {
        VirtualDisplayWrapper virtualDisplayWrapper;
        synchronized (mVirtualDeviceLock) {
            virtualDisplayWrapper = mVirtualDisplays.removeReturnOld(displayId);
        }

        if (virtualDisplayWrapper == null) {
            Slog.w(TAG, "Virtual device " + mDeviceId + " doesn't have a virtual display with ID "
                    + displayId);
            return;
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            releaseOwnedVirtualDisplayResources(virtualDisplayWrapper);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @SuppressWarnings("AndroidFrameworkRequiresPermission")
    private void checkVirtualInputDeviceDisplayIdAssociation(int displayId) {
        // The INJECT_EVENTS permission allows for injecting input to any window / display.
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.INJECT_EVENTS)
                    != PackageManager.PERMISSION_GRANTED) {
            synchronized (mVirtualDeviceLock) {
                checkDisplayOwnedByVirtualDeviceLocked(displayId);
                VirtualDisplayWrapper wrapper = mVirtualDisplays.get(displayId);
                if (!wrapper.isTrusted() && !wrapper.isMirror()) {
                    throw new SecurityException(
                            "Cannot create input device associated with an untrusted display");
                }
            }
        }
    }

    @GuardedBy("mVirtualDeviceLock")
    private void checkDisplayOwnedByVirtualDeviceLocked(int displayId) {
        if (!mVirtualDisplays.contains(displayId)) {
            throw new SecurityException(
                    "Invalid displayId: Display " + displayId
                            + " is not associated with this virtual device");
        }
    }

    private void checkCallerIsDeviceOwner() {
        if (Binder.getCallingUid() != mOwnerUid) {
            throw new SecurityException(
                "Caller is not the owner of this virtual device");
        }
    }

    void goToSleepInternal(@PowerManager.GoToSleepReason int reason) {
        final long now = SystemClock.uptimeMillis();
        IntArray displayIds = new IntArray();
        synchronized (mVirtualDeviceLock) {
            for (int i = 0; i < mVirtualDisplays.size(); i++) {
                VirtualDisplayWrapper wrapper = mVirtualDisplays.valueAt(i);
                if (!wrapper.isTrusted() || wrapper.isMirror()) {
                    continue;
                }
                int displayId = mVirtualDisplays.keyAt(i);
                displayIds.add(displayId);
            }
        }
        for (int i = 0; i < displayIds.size(); ++i) {
            mPowerManager.goToSleep(displayIds.get(i), now, reason, /* flags= */ 0);
        }
    }

    void wakeUpInternal(@PowerManager.WakeReason int reason, String details) {
        final long now = SystemClock.uptimeMillis();
        IntArray displayIds = new IntArray();
        synchronized (mVirtualDeviceLock) {
            for (int i = 0; i < mVirtualDisplays.size(); i++) {
                VirtualDisplayWrapper wrapper = mVirtualDisplays.valueAt(i);
                if (!wrapper.isTrusted() || wrapper.isMirror()) {
                    continue;
                }
                int displayId = mVirtualDisplays.keyAt(i);
                displayIds.add(displayId);
            }
        }
        for (int i = 0; i < displayIds.size(); ++i) {
            mPowerManager.wakeUp(now, reason, details, displayIds.get(i));
        }
    }

    /**
     * Release resources tied to virtual display owned by this VirtualDevice instance.
     *
     * Note that this method won't release the virtual display itself.
     *
     * @param virtualDisplayWrapper - VirtualDisplayWrapper to release resources for.
     */
    private void releaseOwnedVirtualDisplayResources(VirtualDisplayWrapper virtualDisplayWrapper) {
        virtualDisplayWrapper.releaseWakeLock();
        virtualDisplayWrapper.getWindowPolicyController().unregisterRunningAppsChangedListener(
                this);
    }

    int getOwnerUid() {
        return mOwnerUid;
    }

    long getDimDurationMillis() {
        return mParams.getDimDuration().toMillis();
    }

    long getScreenOffTimeoutMillis() {
        return mParams.getScreenOffTimeout().toMillis();
    }

    @Override  // Binder call
    public int[] getDisplayIds() {
        synchronized (mVirtualDeviceLock) {
            final int size = mVirtualDisplays.size();
            int[] displayIds = new int[size];
            for (int i = 0; i < size; i++) {
                displayIds[i] = mVirtualDisplays.keyAt(i);
            }
            return displayIds;
        }
    }

    @VisibleForTesting
    GenericWindowPolicyController getDisplayWindowPolicyControllerForTest(int displayId) {
        VirtualDisplayWrapper virtualDisplayWrapper;
        synchronized (mVirtualDeviceLock) {
            virtualDisplayWrapper = mVirtualDisplays.get(displayId);
        }
        return virtualDisplayWrapper != null ? virtualDisplayWrapper.getWindowPolicyController()
                : null;
    }

    /**
     * Returns true if an app with the given {@code uid} is currently running on this virtual
     * device.
     */
    boolean isAppRunningOnVirtualDevice(int uid) {
        synchronized (mVirtualDeviceLock) {
            for (int i = 0; i < mVirtualDisplays.size(); i++) {
                if (mVirtualDisplays.valueAt(i).getWindowPolicyController().containsUid(uid)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Shows a toast on virtual displays owned by this device which have a given uid running.
     */
    void showToastWhereUidIsRunning(int uid, @StringRes int resId, @Toast.Duration int duration,
            Looper looper) {
        showToastWhereUidIsRunning(uid, mContext.getString(resId), duration, looper);
    }

    /**
     * Shows a toast on virtual displays owned by this device which have a given uid running.
     */
    void showToastWhereUidIsRunning(int uid, String text, @Toast.Duration int duration,
            Looper looper) {
        IntArray displayIdsForUid = getDisplayIdsWhereUidIsRunning(uid);
        if (displayIdsForUid.size() == 0) {
            return;
        }
        DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        for (int i = 0; i < displayIdsForUid.size(); i++) {
            Display display = displayManager.getDisplay(displayIdsForUid.get(i));
            if (display != null && display.isValid()) {
                Toast.makeText(mContext.createDisplayContext(display), looper, text,
                        duration).show();
            }
        }
    }

    private IntArray getDisplayIdsWhereUidIsRunning(int uid) {
        IntArray displayIdsForUid = new IntArray();
        synchronized (mVirtualDeviceLock) {
            for (int i = 0; i < mVirtualDisplays.size(); i++) {
                if (mVirtualDisplays.valueAt(i).getWindowPolicyController().containsUid(uid)) {
                    displayIdsForUid.add(mVirtualDisplays.keyAt(i));
                }
            }
        }
        return displayIdsForUid;
    }

    boolean isDisplayOwnedByVirtualDevice(int displayId) {
        synchronized (mVirtualDeviceLock) {
            return mVirtualDisplays.contains(displayId);
        }
    }

    boolean isInputDeviceOwnedByVirtualDevice(int inputDeviceId) {
        return mInputController.getInputDeviceDescriptors().values().stream().anyMatch(
                inputDeviceDescriptor -> inputDeviceDescriptor.getInputDeviceId() == inputDeviceId);
    }

    void playSoundEffect(int effectType) {
        try {
            mSoundEffectListener.onPlaySoundEffect(effectType);
        } catch (RemoteException exception) {
            Slog.w(TAG, "Unable to invoke sound effect listener", exception);
        }
    }

    interface PendingTrampolineCallback {
        /**
         * Called when the callback should start waiting for the given pending trampoline.
         * Implementations should try to listen for activity starts associated with the given
         * {@code pendingTrampoline}, and launch the activity on the display with
         * {@link PendingTrampoline#mDisplayId}.
         */
        void startWaitingForPendingTrampoline(PendingTrampoline pendingTrampoline);

        /**
         * Called when the callback should stop waiting for the given pending trampoline. This can
         * happen, for example, when the pending intent failed to send.
         */
        void stopWaitingForPendingTrampoline(PendingTrampoline pendingTrampoline);
    }

    /**
     * A data class storing a pending trampoline this device is expecting.
     */
    static class PendingTrampoline {

        /**
         * The original pending intent sent, for which a trampoline activity launch is expected.
         */
        final PendingIntent mPendingIntent;

        /**
         * The result receiver associated with this pending call. {@link Activity#RESULT_OK} will
         * be sent to the receiver if the trampoline activity was captured successfully.
         * {@link Activity#RESULT_CANCELED} is sent otherwise.
         */
        final ResultReceiver mResultReceiver;

        /**
         * The display ID to send the captured trampoline activity launch to.
         */
        final int mDisplayId;

        private PendingTrampoline(PendingIntent pendingIntent, ResultReceiver resultReceiver,
                int displayId) {
            mPendingIntent = pendingIntent;
            mResultReceiver = resultReceiver;
            mDisplayId = displayId;
        }

        @Override
        public String toString() {
            return "PendingTrampoline{"
                    + "pendingIntent=" + mPendingIntent
                    + ", resultReceiver=" + mResultReceiver
                    + ", displayId=" + mDisplayId + "}";
        }
    }

    /** Data class wrapping resources tied to single virtual display. */
    private static final class VirtualDisplayWrapper {
        private final IVirtualDisplayCallback mToken;
        private final GenericWindowPolicyController mWindowPolicyController;
        private final PowerManager.WakeLock mWakeLock;
        private final boolean mIsTrusted;
        private final boolean mIsMirror;

        VirtualDisplayWrapper(@NonNull IVirtualDisplayCallback token,
                @NonNull GenericWindowPolicyController windowPolicyController,
                @Nullable PowerManager.WakeLock wakeLock, boolean isTrusted, boolean isMirror) {
            mToken = Objects.requireNonNull(token);
            mWindowPolicyController = Objects.requireNonNull(windowPolicyController);
            mWakeLock = wakeLock;
            mIsTrusted = isTrusted;
            mIsMirror = isMirror;
        }

        GenericWindowPolicyController getWindowPolicyController() {
            return mWindowPolicyController;
        }

        void acquireWakeLock() {
            if (mWakeLock != null && !mWakeLock.isHeld()) {
                mWakeLock.acquire();
            }
        }

        void releaseWakeLock() {
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
            }
        }

        boolean isTrusted() {
            return mIsTrusted;
        }

        boolean isMirror() {
            return mIsMirror;
        }

        IVirtualDisplayCallback getToken() {
            return mToken;
        }
    }

    private static boolean isVirtualCameraEnabled() {
        return nativeVirtualCameraServiceBuildFlagEnabled();
    }

    // Returns true if virtual_camera service is enabled in this build.
    @FastNative
    private static native boolean nativeVirtualCameraServiceBuildFlagEnabled();
}
