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
 * limitations under the License.
 */

package com.android.server.credentials;

import static com.android.server.credentials.CredentialManagerService.getPrimaryProvidersForUserId;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.credentials.CredentialManager;
import android.credentials.CredentialProviderInfo;
import android.credentials.flags.Flags;
import android.service.credentials.CredentialProviderInfoFactory;
import android.service.credentials.CredentialProviderService;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.infra.AbstractPerUserSystemService;

import java.util.List;
import java.util.Set;


/**
 * Per-user, per remote service implementation of {@link CredentialManagerService}
 */
public final class CredentialManagerServiceImpl extends
        AbstractPerUserSystemService<CredentialManagerServiceImpl, CredentialManagerService> {
    private static final String TAG = CredentialManager.TAG;

    @GuardedBy("mLock")
    @NonNull
    private CredentialProviderInfo mInfo;

    CredentialManagerServiceImpl(
            @NonNull CredentialManagerService master,
            @NonNull Object lock, int userId, String serviceName)
            throws PackageManager.NameNotFoundException {
        super(master, lock, userId);
        Slog.i(TAG, "CredentialManagerServiceImpl constructed for: " + serviceName);
        synchronized (mLock) {
            newServiceInfoLocked(ComponentName.unflattenFromString(serviceName));
        }
    }

    @Nullable
    @Override
    @GuardedBy("mLock")
    public ComponentName getServiceComponentName() {
        return getComponentName();
    }

    @GuardedBy("mLock")
    public ComponentName getComponentName() {
        return mInfo.getServiceInfo().getComponentName();
    }

    CredentialManagerServiceImpl(
            @NonNull CredentialManagerService master,
            @NonNull Object lock, int userId, CredentialProviderInfo providerInfo) {
        super(master, lock, userId);
        Slog.i(TAG, "CredentialManagerServiceImpl constructed for: "
                + providerInfo.getServiceInfo().getComponentName().flattenToString());
        mInfo = providerInfo;
    }

    @Override // from PerUserSystemService when a new service is to be created
    @GuardedBy("mLock")
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws PackageManager.NameNotFoundException, SecurityException, NullPointerException {
        boolean isSystemProvider = false;
        if (mInfo != null) {
            Slog.i(TAG, "newServiceInfoLocked, mInfo not null : "
                    + mInfo.getServiceInfo().getComponentName().flattenToString() + " , "
                    + serviceComponent.flattenToString());
            isSystemProvider = mInfo.isSystemProvider();
        } else {
            Slog.i(TAG, "newServiceInfoLocked, mInfo null, "
                    + serviceComponent.flattenToString());
        }
        Set<ComponentName> primaryProviders =
                getPrimaryProvidersForUserId(mMaster.getContext(), mUserId);
        mInfo = CredentialProviderInfoFactory.create(
                getContext(), serviceComponent,
                mUserId, isSystemProvider,
                primaryProviders.contains(serviceComponent));
        return mInfo.getServiceInfo();
    }

    /**
     * Starts a provider session and associates it with the given request session.
     */
    @Nullable
    @GuardedBy("mLock")
    public ProviderSession initiateProviderSessionForRequestLocked(
            RequestSession requestSession, List<String> requestOptions) {
        if (!requestOptions.isEmpty() && !isServiceCapableLocked(requestOptions)) {
            if (mInfo != null) {
                Slog.i(TAG, "Service does not have the required capabilities: "
                        + mInfo.getComponentName());
            }
            return null;
        }
        if (mInfo == null) {
            Slog.w(TAG, "Initiating provider session for request "
                    + "but mInfo is null. This shouldn't happen");
            return null;
        }
        final RemoteCredentialService remoteService = new RemoteCredentialService(
                getContext(), mInfo.getServiceInfo().getComponentName(), mUserId);
        return requestSession.initiateProviderSession(mInfo, remoteService);
    }

    /** Return true if at least one capability found. */
    @GuardedBy("mLock")
    boolean isServiceCapableLocked(List<String> requestedOptions) {
        if (mInfo == null) {
            return false;
        }
        for (String capability : requestedOptions) {
            if (mInfo.hasCapability(capability)) {
                return true;
            }
        }
        return false;
    }

    @GuardedBy("mLock")
    public CredentialProviderInfo getCredentialProviderInfo() {
        return mInfo;
    }

    /**
     * Callback called when an app has been updated.
     *
     * @param packageName package of the app being updated.
     */
    @GuardedBy("mLock")
    @SuppressWarnings("GuardedBy") // ErrorProne requires this.mMaster.mLock which is the case
    // because this method is called by this.mMaster anyway
    protected void handlePackageUpdateLocked(@NonNull String packageName) {
        if (mInfo != null && mInfo.getServiceInfo() != null
                && mInfo.getServiceInfo().getComponentName()
                .getPackageName().equals(packageName)) {
            if (Flags.packageUpdateFixEnabled()) {
                try {
                    updateCredentialProviderInfo(mInfo.getServiceInfo().getComponentName(),
                            mInfo.isSystemProvider());
                } catch (SecurityException | PackageManager.NameNotFoundException
                         | NullPointerException e) {
                    Slog.w(TAG, "Unable to update provider, must be removed: " + e.getMessage());
                    mMaster.handleServiceRemovedMultiModeLocked(mInfo.getComponentName(), mUserId);
                }
            } else {
                try {
                    newServiceInfoLocked(mInfo.getServiceInfo().getComponentName());
                } catch (PackageManager.NameNotFoundException e) {
                    Slog.e(TAG, "Issue while updating serviceInfo: " + e.getMessage());
                }
            }
        }
    }

    @GuardedBy("mLock")
    private void updateCredentialProviderInfo(ComponentName componentName, boolean isSystemProvider)
            throws SecurityException, PackageManager.NameNotFoundException {
        Slog.d(TAG, "Updating credential provider: " + componentName.flattenToString());
        if (!isValidCredentialProviderInfo(componentName, mUserId, isSystemProvider)) {
            throw new SecurityException("Service has not been set up correctly");
        }
        newServiceInfoLocked(componentName);
    }

    private boolean isValidCredentialProviderInfo(ComponentName componentName, int userId,
            boolean isSystemProvider) {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        String serviceInterface = CredentialProviderService.SERVICE_INTERFACE;
        if (isSystemProvider) {
            serviceInterface = CredentialProviderService.SYSTEM_SERVICE_INTERFACE;
        }
        final List<ResolveInfo> resolveInfos =
                context.getPackageManager()
                        .queryIntentServicesAsUser(
                                new Intent(serviceInterface),
                                PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA),
                                userId);
        for (ResolveInfo resolveInfo : resolveInfos) {
            final ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            if (serviceInfo.getComponentName().equals(componentName)) {
                return true;
            }
        }
        return false;
    }
}
