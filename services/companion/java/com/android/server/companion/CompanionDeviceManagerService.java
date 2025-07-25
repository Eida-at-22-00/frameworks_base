/*
 * Copyright (C) 2017 The Android Open Source Project
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


package com.android.server.companion;

import static android.Manifest.permission.ASSOCIATE_COMPANION_DEVICES;
import static android.Manifest.permission.BLUETOOTH_CONNECT;
import static android.Manifest.permission.DELIVER_COMPANION_MESSAGES;
import static android.Manifest.permission.MANAGE_COMPANION_DEVICES;
import static android.Manifest.permission.REQUEST_COMPANION_SELF_MANAGED;
import static android.Manifest.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE;
import static android.Manifest.permission.USE_COMPANION_TRANSPORTS;
import static android.content.pm.PackageManager.CERT_INPUT_SHA256;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.SYSTEM_UID;
import static android.os.UserHandle.getCallingUserId;

import static com.android.internal.util.CollectionUtils.any;
import static com.android.internal.util.Preconditions.checkState;
import static com.android.server.companion.association.DisassociationProcessor.REASON_API;
import static com.android.server.companion.association.DisassociationProcessor.REASON_PKG_DATA_CLEARED;
import static com.android.server.companion.utils.PackageUtils.enforceUsesCompanionDeviceFeature;
import static com.android.server.companion.utils.PackageUtils.isRestrictedSettingsAllowed;
import static com.android.server.companion.utils.PermissionsUtils.enforceCallerCanManageAssociationsForPackage;
import static com.android.server.companion.utils.PermissionsUtils.enforceCallerIsSystemOr;
import static com.android.server.companion.utils.PermissionsUtils.enforceCallerIsSystemOrCanInteractWithUserId;

import static java.util.Objects.requireNonNull;

import android.annotation.EnforcePermission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ecm.EnhancedConfirmationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.DeviceId;
import android.companion.IAssociationRequestCallback;
import android.companion.ICompanionDeviceManager;
import android.companion.IOnAssociationsChangedListener;
import android.companion.IOnMessageReceivedListener;
import android.companion.IOnTransportsChangedListener;
import android.companion.ISystemDataTransferCallback;
import android.companion.ObservingDevicePresenceRequest;
import android.companion.datatransfer.PermissionSyncRequest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManagerInternal;
import android.net.MacAddress;
import android.os.Binder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PowerExemptionManager;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.flags.Flags;
import android.util.ExceptionUtils;
import android.util.Slog;

import com.android.internal.content.PackageMonitor;
import com.android.internal.notification.NotificationAccessConfirmationActivityContract;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.companion.association.AssociationDiskStore;
import com.android.server.companion.association.AssociationRequestsProcessor;
import com.android.server.companion.association.AssociationStore;
import com.android.server.companion.association.DisassociationProcessor;
import com.android.server.companion.association.InactiveAssociationsRemovalService;
import com.android.server.companion.datatransfer.SystemDataTransferProcessor;
import com.android.server.companion.datatransfer.SystemDataTransferRequestStore;
import com.android.server.companion.datatransfer.contextsync.CrossDeviceCall;
import com.android.server.companion.datatransfer.contextsync.CrossDeviceSyncController;
import com.android.server.companion.datatransfer.contextsync.CrossDeviceSyncControllerCallback;
import com.android.server.companion.devicepresence.CompanionAppBinder;
import com.android.server.companion.devicepresence.DevicePresenceProcessor;
import com.android.server.companion.devicepresence.ObservableUuid;
import com.android.server.companion.devicepresence.ObservableUuidStore;
import com.android.server.companion.transport.CompanionTransportManager;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("LongLogTag")
public class CompanionDeviceManagerService extends SystemService {
    private static final String TAG = "CDM_CompanionDeviceManagerService";

    private static final long PAIR_WITHOUT_PROMPT_WINDOW_MS = 10 * 60 * 1000; // 10 min
    private static final int MAX_CN_LENGTH = 500;

    private final AssociationStore mAssociationStore;
    private final SystemDataTransferRequestStore mSystemDataTransferRequestStore;
    private final ObservableUuidStore mObservableUuidStore;

    private final CompanionExemptionProcessor mCompanionExemptionProcessor;
    private final AssociationRequestsProcessor mAssociationRequestsProcessor;
    private final SystemDataTransferProcessor mSystemDataTransferProcessor;
    private final BackupRestoreProcessor mBackupRestoreProcessor;
    private final DevicePresenceProcessor mDevicePresenceProcessor;
    private final CompanionAppBinder mCompanionAppBinder;
    private final CompanionTransportManager mTransportManager;
    private final DisassociationProcessor mDisassociationProcessor;
    private final CrossDeviceSyncController mCrossDeviceSyncController;

    public CompanionDeviceManagerService(Context context) {
        super(context);

        final ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        final PowerExemptionManager powerExemptionManager = context.getSystemService(
                PowerExemptionManager.class);
        final AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);
        final ActivityTaskManagerInternal atmInternal = LocalServices.getService(
                ActivityTaskManagerInternal.class);
        final ActivityManagerInternal amInternal = LocalServices.getService(
                ActivityManagerInternal.class);
        final PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        final UserManager userManager = context.getSystemService(UserManager.class);
        final PowerManagerInternal powerManagerInternal = LocalServices.getService(
                PowerManagerInternal.class);

        final AssociationDiskStore associationDiskStore = new AssociationDiskStore();
        mAssociationStore = new AssociationStore(context, userManager, associationDiskStore);
        mSystemDataTransferRequestStore = new SystemDataTransferRequestStore();
        mObservableUuidStore = new ObservableUuidStore();

        // Init processors
        mAssociationRequestsProcessor = new AssociationRequestsProcessor(context,
                packageManagerInternal, mAssociationStore);
        mBackupRestoreProcessor = new BackupRestoreProcessor(context, packageManagerInternal,
                mAssociationStore, associationDiskStore, mSystemDataTransferRequestStore,
                mAssociationRequestsProcessor);

        mCompanionAppBinder = new CompanionAppBinder(context);

        mCompanionExemptionProcessor = new CompanionExemptionProcessor(context,
                powerExemptionManager, appOpsManager, packageManagerInternal, atmInternal,
                amInternal, mAssociationStore);

        mDevicePresenceProcessor = new DevicePresenceProcessor(context,
                mCompanionAppBinder, userManager, mAssociationStore, mObservableUuidStore,
                powerManagerInternal, mCompanionExemptionProcessor);

        mTransportManager = new CompanionTransportManager(context, mAssociationStore);

        mDisassociationProcessor = new DisassociationProcessor(context, activityManager,
                mAssociationStore, packageManagerInternal, mDevicePresenceProcessor,
                mCompanionAppBinder, mSystemDataTransferRequestStore, mTransportManager);

        mSystemDataTransferProcessor = new SystemDataTransferProcessor(this,
                packageManagerInternal, mAssociationStore,
                mSystemDataTransferRequestStore, mTransportManager);

        // TODO(b/279663946): move context sync to a dedicated system service
        mCrossDeviceSyncController = new CrossDeviceSyncController(getContext(), mTransportManager);
    }

    @Override
    public void onStart() {
        // Init association stores
        mAssociationStore.refreshCache();

        // Init UUID store
        mObservableUuidStore.getObservableUuidsForUser(getContext().getUserId());

        // Publish "binder" service.
        final CompanionDeviceManagerImpl impl = new CompanionDeviceManagerImpl();
        publishBinderService(Context.COMPANION_DEVICE_SERVICE, impl);

        // Publish "local" service.
        LocalServices.addService(CompanionDeviceManagerServiceInternal.class, new LocalService());
    }

    @Override
    public void onBootPhase(int phase) {
        final Context context = getContext();
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            // WARNING: moving PackageMonitor to another thread (Looper) may introduce significant
            // delays (even in case of the Main Thread). It may be fine overall, but would require
            // updating the tests (adding a delay there).
            mPackageMonitor.register(context, FgThread.get().getLooper(), UserHandle.ALL, true);
        } else if (phase == PHASE_BOOT_COMPLETED) {
            mDevicePresenceProcessor.init(context);
            // Run the Inactive Association Removal job service daily.
            InactiveAssociationsRemovalService.schedule(getContext());
            mCrossDeviceSyncController.onBootCompleted();
        }
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        Slog.d(TAG, "onUserUnlocking...");
        final int userId = user.getUserIdentifier();
        final List<AssociationInfo> associations = mAssociationStore.getActiveAssociationsByUser(
                userId);

        if (associations.isEmpty()) return;

        mCompanionExemptionProcessor.updateAtm(userId, associations);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(mCompanionExemptionProcessor::updateAutoRevokeExemptions);
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        Slog.i(TAG, "onUserUnlocked() user=" + user);
        // Notify and bind the app after the phone is unlocked.
        mDevicePresenceProcessor.sendDevicePresenceEventOnUnlocked(user.getUserIdentifier());
    }

    private void onPackageRemoveOrDataClearedInternal(
            @UserIdInt int userId, @NonNull String packageName) {
        // Clear all associations for the package.
        final List<AssociationInfo> associationsForPackage =
                mAssociationStore.getAssociationsByPackage(userId, packageName);
        if (!associationsForPackage.isEmpty()) {
            Slog.i(TAG, "Package removed or data cleared for user=[" + userId + "], package=["
                    + packageName + "]. Cleaning up CDM data...");

            for (AssociationInfo association : associationsForPackage) {
                mDisassociationProcessor.disassociate(association.getId(), REASON_PKG_DATA_CLEARED);
            }

            mCompanionAppBinder.onPackageChanged(userId);
        }

        // Clear observable UUIDs for the package.
        final List<ObservableUuid> uuidsTobeObserved =
                mObservableUuidStore.getObservableUuidsForPackage(userId, packageName);
        for (ObservableUuid uuid : uuidsTobeObserved) {
            mObservableUuidStore.removeObservableUuid(userId, uuid.getUuid(), packageName);
        }
    }

    private void onPackageModifiedInternal(@UserIdInt int userId, @NonNull String packageName) {
        final List<AssociationInfo> associations =
                mAssociationStore.getAssociationsByPackage(userId, packageName);
        if (!associations.isEmpty()) {
            mCompanionExemptionProcessor.exemptPackage(userId, packageName, false);

            mCompanionAppBinder.onPackageChanged(userId);
        }
    }

    private void onPackageAddedInternal(@UserIdInt int userId, @NonNull String packageName) {
        mBackupRestoreProcessor.restorePendingAssociations(userId, packageName);
    }

    void removeInactiveSelfManagedAssociations() {
        mDisassociationProcessor.removeIdleSelfManagedAssociations();
    }

    public class CompanionDeviceManagerImpl extends ICompanionDeviceManager.Stub {
        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            try {
                return super.onTransact(code, data, reply, flags);
            } catch (Throwable e) {
                Slog.e(TAG, "Error during IPC", e);
                throw ExceptionUtils.propagate(e, RemoteException.class);
            }
        }

        @Override
        public void associate(AssociationRequest request, IAssociationRequestCallback callback,
                String packageName, int userId) throws RemoteException {
            Slog.i(TAG, "associate() "
                    + "request=" + request + ", "
                    + "package=u" + userId + "/" + packageName);
            enforceCallerCanManageAssociationsForPackage(getContext(), userId, packageName,
                    "create associations");

            if (request.isSkipRoleGrant()) {
                checkCallerCanSkipRoleGrant();
                mAssociationRequestsProcessor.createAssociation(userId, packageName,
                        /* macAddress= */ null, request.getDisplayName(),
                        request.getDeviceProfile(), /* associatedDevice= */ null,
                        request.isSelfManaged(), callback, /* resultReceiver= */ null,
                        request.getDeviceIcon(), /* skipRoleGrant= */ true);
            } else {
                mAssociationRequestsProcessor.processNewAssociationRequest(
                        request, packageName, userId, callback);
            }
        }

        @Override
        public PendingIntent buildAssociationCancellationIntent(String packageName,
                int userId) throws RemoteException {
            Slog.i(TAG, "buildAssociationCancellationIntent() "
                    + "package=u" + userId + "/" + packageName);
            enforceCallerCanManageAssociationsForPackage(getContext(), userId, packageName,
                    "build association cancellation intent");

            return mAssociationRequestsProcessor.buildAssociationCancellationIntent(
                    packageName, userId);
        }

        @Override
        public List<AssociationInfo> getAssociations(String packageName, int userId) {
            enforceCallerCanManageAssociationsForPackage(getContext(), userId, packageName,
                    "get associations");
            return mAssociationStore.getActiveAssociationsByPackage(userId, packageName);
        }

        @Override
        @EnforcePermission(MANAGE_COMPANION_DEVICES)
        public List<AssociationInfo> getAllAssociationsForUser(int userId) throws RemoteException {
            getAllAssociationsForUser_enforcePermission();

            enforceCallerIsSystemOrCanInteractWithUserId(getContext(), userId);

            if (userId == UserHandle.USER_ALL) {
                return mAssociationStore.getActiveAssociations();
            }
            return mAssociationStore.getActiveAssociationsByUser(userId);
        }

        @Override
        @EnforcePermission(MANAGE_COMPANION_DEVICES)
        public void addOnAssociationsChangedListener(IOnAssociationsChangedListener listener,
                int userId) {
            addOnAssociationsChangedListener_enforcePermission();

            enforceCallerIsSystemOrCanInteractWithUserId(getContext(), userId);

            mAssociationStore.registerRemoteListener(listener, userId);
        }

        @Override
        @EnforcePermission(MANAGE_COMPANION_DEVICES)
        public void removeOnAssociationsChangedListener(IOnAssociationsChangedListener listener,
                int userId) {
            removeOnAssociationsChangedListener_enforcePermission();

            enforceCallerIsSystemOrCanInteractWithUserId(getContext(), userId);

            mAssociationStore.unregisterRemoteListener(listener);
        }

        @Override
        @EnforcePermission(USE_COMPANION_TRANSPORTS)
        public void addOnTransportsChangedListener(IOnTransportsChangedListener listener) {
            addOnTransportsChangedListener_enforcePermission();

            mTransportManager.addListener(listener);
        }

        @Override
        @EnforcePermission(USE_COMPANION_TRANSPORTS)
        public void removeOnTransportsChangedListener(IOnTransportsChangedListener listener) {
            removeOnTransportsChangedListener_enforcePermission();

            mTransportManager.removeListener(listener);
        }

        @Override
        @EnforcePermission(USE_COMPANION_TRANSPORTS)
        public void sendMessage(int messageType, byte[] data, int[] associationIds) {
            sendMessage_enforcePermission();

            mTransportManager.sendMessage(messageType, data, associationIds);
        }

        @Override
        @EnforcePermission(USE_COMPANION_TRANSPORTS)
        public void addOnMessageReceivedListener(int messageType,
                IOnMessageReceivedListener listener) {
            addOnMessageReceivedListener_enforcePermission();

            mTransportManager.addListener(messageType, listener);
        }

        @Override
        @EnforcePermission(USE_COMPANION_TRANSPORTS)
        public void removeOnMessageReceivedListener(int messageType,
                IOnMessageReceivedListener listener) {
            removeOnMessageReceivedListener_enforcePermission();

            mTransportManager.removeListener(messageType, listener);
        }

        /**
         * @deprecated use {@link #disassociate(int)} instead
         */
        @Deprecated
        @Override
        public void legacyDisassociate(String deviceMacAddress, String packageName, int userId) {
            requireNonNull(deviceMacAddress);
            requireNonNull(packageName);

            mDisassociationProcessor.disassociate(userId, packageName, deviceMacAddress);
        }

        @Override
        public void disassociate(int associationId) {
            mDisassociationProcessor.disassociate(associationId, REASON_API);
        }

        @Override
        public PendingIntent requestNotificationAccess(ComponentName component, int userId)
                throws RemoteException {
            int callingUid = getCallingUid();
            final String callingPackage = component.getPackageName();

            checkCanCallNotificationApi(callingPackage, userId);

            if (component.flattenToString().length() > MAX_CN_LENGTH) {
                throw new IllegalArgumentException("Component name is too long.");
            }

            return Binder.withCleanCallingIdentity(() -> {
                final Intent intent;
                if (!isRestrictedSettingsAllowed(getContext(), callingPackage, callingUid)) {
                    Slog.e(TAG, "Side loaded app must enable restricted "
                            + "setting before request the notification access");
                    if (Flags.enhancedConfirmationModeApisEnabled()) {
                        intent = getContext()
                                .getSystemService(EnhancedConfirmationManager.class)
                                .createRestrictedSettingDialogIntent(callingPackage,
                                        AppOpsManager.OPSTR_ACCESS_NOTIFICATIONS);
                    } else {
                        return null;
                    }
                } else {
                    intent = NotificationAccessConfirmationActivityContract.launcherIntent(
                            getContext(), userId, component);
                }

                return PendingIntent.getActivityAsUser(getContext(),
                        0 /* request code */,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT
                                | PendingIntent.FLAG_CANCEL_CURRENT,
                        null /* options */,
                        new UserHandle(userId));
            });
        }

        /**
         * @deprecated Use
         * {@link NotificationManager#isNotificationListenerAccessGranted(ComponentName)} instead.
         */
        @Deprecated
        @Override
        public boolean hasNotificationAccess(ComponentName component) throws RemoteException {
            checkCanCallNotificationApi(component.getPackageName(), getCallingUserId());
            NotificationManager nm = getContext().getSystemService(NotificationManager.class);
            return nm.isNotificationListenerAccessGranted(component);
        }

        @Override
        @EnforcePermission(MANAGE_COMPANION_DEVICES)
        public boolean isDeviceAssociatedForWifiConnection(String packageName, String macAddress,
                int userId) {
            isDeviceAssociatedForWifiConnection_enforcePermission();

            boolean bypassMacPermission = getContext().getPackageManager().checkPermission(
                    android.Manifest.permission.COMPANION_APPROVE_WIFI_CONNECTIONS, packageName)
                    == PERMISSION_GRANTED;
            if (bypassMacPermission) {
                return true;
            }

            return any(mAssociationStore.getActiveAssociationsByPackage(userId, packageName),
                    a -> a.isLinkedTo(macAddress));
        }

        @Override
        @Deprecated
        @EnforcePermission(REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE)
        public void legacyStartObservingDevicePresence(String deviceAddress, String callingPackage,
                int userId) throws RemoteException {
            legacyStartObservingDevicePresence_enforcePermission();

            mDevicePresenceProcessor.startObservingDevicePresence(userId, callingPackage,
                    deviceAddress);
        }

        @Override
        @Deprecated
        @EnforcePermission(REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE)
        public void legacyStopObservingDevicePresence(String deviceAddress, String callingPackage,
                int userId) throws RemoteException {
            legacyStopObservingDevicePresence_enforcePermission();

            mDevicePresenceProcessor.stopObservingDevicePresence(userId, callingPackage,
                    deviceAddress);
        }

        @Override
        @EnforcePermission(REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE)
        public void startObservingDevicePresence(ObservingDevicePresenceRequest request,
                String packageName, int userId) {
            startObservingDevicePresence_enforcePermission();

            mDevicePresenceProcessor.startObservingDevicePresence(
                    request, packageName, userId, /* enforcePermissions */ true);
        }

        @Override
        @EnforcePermission(REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE)
        public void stopObservingDevicePresence(ObservingDevicePresenceRequest request,
                String packageName, int userId) {
            stopObservingDevicePresence_enforcePermission();

            mDevicePresenceProcessor.stopObservingDevicePresence(
                    request, packageName, userId, /* enforcePermissions */ true);
        }

        @Override
        @EnforcePermission(BLUETOOTH_CONNECT)
        public boolean removeBond(int associationId, String packageName, int userId) {
            removeBond_enforcePermission();

            Slog.i(TAG, "removeBond() "
                    + "associationId=" + associationId + ", "
                    + "package=u" + userId + "/" + packageName);
            enforceCallerCanManageAssociationsForPackage(getContext(), userId, packageName,
                    "remove bonds");

            AssociationInfo association = mAssociationStore
                    .getAssociationWithCallerChecks(associationId);
            MacAddress address = association.getDeviceMacAddress();
            if (address == null) {
                throw new IllegalArgumentException(
                        "Association id=[" + associationId + "] doesn't have a device address.");
            }

            BluetoothAdapter btAdapter = getContext().getSystemService(BluetoothManager.class)
                    .getAdapter();
            BluetoothDevice btDevice = btAdapter.getRemoteDevice(address.toString().toUpperCase());
            return btDevice.removeBond();
        }

        @Override
        public PendingIntent buildPermissionTransferUserConsentIntent(String packageName,
                int userId, int associationId) {
            return mSystemDataTransferProcessor.buildPermissionTransferUserConsentIntent(
                    packageName, userId, associationId);
        }

        @Override
        public boolean isPermissionTransferUserConsented(String packageName, int userId,
                int associationId) {
            return mSystemDataTransferProcessor.isPermissionTransferUserConsented(associationId);
        }

        @Override
        public void startSystemDataTransfer(String packageName, int userId, int associationId,
                ISystemDataTransferCallback callback) {
            mSystemDataTransferProcessor.startSystemDataTransfer(packageName, userId,
                    associationId, callback);
        }

        @Override
        @EnforcePermission(DELIVER_COMPANION_MESSAGES)
        public void attachSystemDataTransport(String packageName, int userId, int associationId,
                                              ParcelFileDescriptor fd, int flags) {
            attachSystemDataTransport_enforcePermission();

            mTransportManager.attachSystemDataTransport(associationId, fd, flags);
        }

        @Override
        @EnforcePermission(DELIVER_COMPANION_MESSAGES)
        public void detachSystemDataTransport(String packageName, int userId, int associationId) {
            detachSystemDataTransport_enforcePermission();

            mTransportManager.detachSystemDataTransport(associationId);
        }

        @Override
        @EnforcePermission(MANAGE_COMPANION_DEVICES)
        public void enableSecureTransport(boolean enabled) {
            enableSecureTransport_enforcePermission();

            mTransportManager.enableSecureTransport(enabled);
        }

        @Override
        public void enableSystemDataSync(int associationId, int flags) {
            mAssociationRequestsProcessor.enableSystemDataSync(associationId, flags);
        }

        @Override
        public void disableSystemDataSync(int associationId, int flags) {
            mAssociationRequestsProcessor.disableSystemDataSync(associationId, flags);
        }

        @Override
        public void enablePermissionsSync(int associationId) {
            if (UserHandle.getAppId(Binder.getCallingUid()) != SYSTEM_UID) {
                throw new SecurityException("Caller must be system UID");
            }
            mSystemDataTransferProcessor.enablePermissionsSync(associationId);
        }

        @Override
        public void disablePermissionsSync(int associationId) {
            if (UserHandle.getAppId(Binder.getCallingUid()) != SYSTEM_UID) {
                throw new SecurityException("Caller must be system UID");
            }
            mSystemDataTransferProcessor.disablePermissionsSync(associationId);
        }

        @Override
        public PermissionSyncRequest getPermissionSyncRequest(int associationId) {
            if (UserHandle.getAppId(Binder.getCallingUid()) != SYSTEM_UID) {
                throw new SecurityException("Caller must be system UID");
            }
            return mSystemDataTransferProcessor.getPermissionSyncRequest(associationId);
        }

        @Override
        @EnforcePermission(REQUEST_COMPANION_SELF_MANAGED)
        public void notifySelfManagedDeviceAppeared(int associationId) {
            notifySelfManagedDeviceAppeared_enforcePermission();

            mDevicePresenceProcessor.notifySelfManagedDevicePresenceEvent(associationId, true);
        }

        @Override
        @EnforcePermission(REQUEST_COMPANION_SELF_MANAGED)
        public void notifySelfManagedDeviceDisappeared(int associationId) {
            notifySelfManagedDeviceDisappeared_enforcePermission();

            mDevicePresenceProcessor.notifySelfManagedDevicePresenceEvent(associationId, false);
        }

        @Override
        public boolean isCompanionApplicationBound(String packageName, int userId) {
            return mCompanionAppBinder.isCompanionApplicationBound(userId, packageName);
        }

        @Override
        @EnforcePermission(ASSOCIATE_COMPANION_DEVICES)
        public void createAssociation(String packageName, String macAddress, int userId,
                byte[] certificate) {
            createAssociation_enforcePermission();

            if (!getContext().getPackageManager().hasSigningCertificate(
                    packageName, certificate, CERT_INPUT_SHA256)) {
                Slog.e(TAG, "Given certificate doesn't match the package certificate.");
                return;
            }

            final MacAddress macAddressObj = MacAddress.fromString(macAddress);
            mAssociationRequestsProcessor.createAssociation(userId, packageName, macAddressObj,
                    null, null, null, false, null, null, null, false);
        }

        private void checkCanCallNotificationApi(String callingPackage, int userId) {
            enforceCallerIsSystemOr(userId, callingPackage);

            if (getCallingUid() == SYSTEM_UID) return;

            enforceUsesCompanionDeviceFeature(getContext(), userId, callingPackage);
            checkState(!ArrayUtils.isEmpty(
                            mAssociationStore.getActiveAssociationsByPackage(userId,
                                    callingPackage)),
                    "App must have an association before calling this API");
        }

        private void checkCallerCanSkipRoleGrant() {
            final Context context =
                    getContext().createContextAsUser(Binder.getCallingUserHandle(), 0);
            final KeyguardManager keyguardManager =
                    context.getSystemService(KeyguardManager.class);
            if (keyguardManager != null && keyguardManager.isKeyguardSecure()) {
                throw new SecurityException("Skipping CDM role grant requires insecure keyguard.");
            }
            if (getContext().checkCallingPermission(ASSOCIATE_COMPANION_DEVICES)
                    != PERMISSION_GRANTED) {
                throw new SecurityException(
                        "Skipping CDM role grant requires ASSOCIATE_COMPANION_DEVICES permission.");
            }
        }

        @Override
        public boolean canPairWithoutPrompt(String packageName, String macAddress, int userId) {
            final AssociationInfo association =
                    mAssociationStore.getFirstAssociationByAddress(
                            userId, packageName, macAddress);
            if (association == null) {
                return false;
            }
            return System.currentTimeMillis() - association.getTimeApprovedMs()
                    < PAIR_WITHOUT_PROMPT_WINDOW_MS;
        }

        @Override
        public void setDeviceId(int associationId, DeviceId deviceId) {
            mAssociationRequestsProcessor.setDeviceId(associationId, deviceId);
        }


        @Override
        public byte[] getBackupPayload(int userId) {
            if (UserHandle.getAppId(Binder.getCallingUid()) != SYSTEM_UID) {
                throw new SecurityException("Caller must be system");
            }
            return mBackupRestoreProcessor.getBackupPayload(userId);
        }

        @Override
        public void applyRestoredPayload(byte[] payload, int userId) {
            if (UserHandle.getAppId(Binder.getCallingUid()) != SYSTEM_UID) {
                throw new SecurityException("Caller must be system");
            }
            mBackupRestoreProcessor.applyRestoredPayload(payload, userId);
        }

        @Override
        public int handleShellCommand(@NonNull ParcelFileDescriptor in,
                @NonNull ParcelFileDescriptor out, @NonNull ParcelFileDescriptor err,
                @NonNull String[] args) {
            return new CompanionDeviceShellCommand(CompanionDeviceManagerService.this,
                    mAssociationStore, mDevicePresenceProcessor, mTransportManager,
                    mSystemDataTransferProcessor, mAssociationRequestsProcessor,
                    mBackupRestoreProcessor, mDisassociationProcessor)
                    .exec(this, in.getFileDescriptor(), out.getFileDescriptor(),
                            err.getFileDescriptor(), args);
        }

        @Override
        public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter out,
                @Nullable String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), TAG, out)) {
                return;
            }

            mAssociationStore.dump(out);
            mDevicePresenceProcessor.dump(out);
            mCompanionAppBinder.dump(out);
            mTransportManager.dump(out);
            mSystemDataTransferRequestStore.dump(out);
        }
    }

    private final PackageMonitor mPackageMonitor = new PackageMonitor() {
        @Override
        public void onPackageRemoved(String packageName, int uid) {
            onPackageRemoveOrDataClearedInternal(getChangingUserId(), packageName);
        }

        @Override
        public void onPackageDataCleared(String packageName, int uid) {
            onPackageRemoveOrDataClearedInternal(getChangingUserId(), packageName);
        }

        @Override
        public void onPackageModified(@NonNull String packageName) {
            onPackageModifiedInternal(getChangingUserId(), packageName);
        }

        @Override
        public void onPackageAdded(String packageName, int uid) {
            onPackageAddedInternal(getChangingUserId(), packageName);
        }
    };

    private class LocalService implements CompanionDeviceManagerServiceInternal {

        @Override
        public void removeInactiveSelfManagedAssociations() {
            mDisassociationProcessor.removeIdleSelfManagedAssociations();
        }

        @Override
        public void registerCallMetadataSyncCallback(CrossDeviceSyncControllerCallback callback,
                @CrossDeviceSyncControllerCallback.Type int type) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.registerCallMetadataSyncCallback(callback, type);
            }
        }

        @Override
        public void crossDeviceSync(int userId, Collection<CrossDeviceCall> calls) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.syncToAllDevicesForUserId(userId, calls);
            }
        }

        @Override
        public void crossDeviceSync(AssociationInfo associationInfo,
                Collection<CrossDeviceCall> calls) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.syncToSingleDevice(associationInfo, calls);
            }
        }

        @Override
        public void sendCrossDeviceSyncMessage(int associationId, byte[] message) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.syncMessageToDevice(associationId, message);
            }
        }

        @Override
        public void sendCrossDeviceSyncMessageToAllDevices(int userId, byte[] message) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.syncMessageToAllDevicesForUserId(userId, message);
            }
        }

        @Override
        public void addSelfOwnedCallId(String callId) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.addSelfOwnedCallId(callId);
            }
        }

        @Override
        public void removeSelfOwnedCallId(String callId) {
            if (CompanionDeviceConfig.isEnabled(
                    CompanionDeviceConfig.ENABLE_CONTEXT_SYNC_TELECOM)) {
                mCrossDeviceSyncController.removeSelfOwnedCallId(callId);
            }
        }
    }
}
