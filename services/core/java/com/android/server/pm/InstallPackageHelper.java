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

package com.android.server.pm;

import static android.content.pm.Flags.disallowSdkLibsToBeApps;
import static android.content.pm.PackageManager.APP_METADATA_SOURCE_APK;
import static android.content.pm.PackageManager.APP_METADATA_SOURCE_INSTALLER;
import static android.content.pm.PackageManager.APP_METADATA_SOURCE_UNKNOWN;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.INSTALL_FAILED_ALREADY_EXISTS;
import static android.content.pm.PackageManager.INSTALL_FAILED_BAD_PERMISSION_GROUP;
import static android.content.pm.PackageManager.INSTALL_FAILED_DEPRECATED_SDK_VERSION;
import static android.content.pm.PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE;
import static android.content.pm.PackageManager.INSTALL_FAILED_DUPLICATE_PERMISSION;
import static android.content.pm.PackageManager.INSTALL_FAILED_DUPLICATE_PERMISSION_GROUP;
import static android.content.pm.PackageManager.INSTALL_FAILED_INVALID_APK;
import static android.content.pm.PackageManager.INSTALL_FAILED_INVALID_INSTALL_LOCATION;
import static android.content.pm.PackageManager.INSTALL_FAILED_PACKAGE_CHANGED;
import static android.content.pm.PackageManager.INSTALL_FAILED_SESSION_INVALID;
import static android.content.pm.PackageManager.INSTALL_FAILED_TEST_ONLY;
import static android.content.pm.PackageManager.INSTALL_FAILED_UID_CHANGED;
import static android.content.pm.PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE;
import static android.content.pm.PackageManager.INSTALL_STAGED;
import static android.content.pm.PackageManager.INSTALL_SUCCEEDED;
import static android.content.pm.PackageManager.PROPERTY_ANDROID_SAFETY_LABEL;
import static android.content.pm.PackageManager.UNINSTALL_REASON_UNKNOWN;
import static android.content.pm.SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V4;
import static android.content.pm.parsing.ApkLiteParseUtils.isApkFile;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.os.incremental.IncrementalManager.isIncrementalPath;
import static android.os.storage.StorageManager.FLAG_STORAGE_CE;
import static android.os.storage.StorageManager.FLAG_STORAGE_DE;
import static android.os.storage.StorageManager.FLAG_STORAGE_EXTERNAL;

import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.PackageManagerException.INTERNAL_ERROR_ARCHIVE_NO_INSTALLER_TITLE;
import static com.android.server.pm.PackageManagerService.APP_METADATA_FILE_NAME;
import static com.android.server.pm.PackageManagerService.DEBUG_COMPRESSION;
import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.DEBUG_PACKAGE_SCANNING;
import static com.android.server.pm.PackageManagerService.DEBUG_REMOVE;
import static com.android.server.pm.PackageManagerService.DEBUG_UPGRADE;
import static com.android.server.pm.PackageManagerService.DEBUG_VERIFY;
import static com.android.server.pm.PackageManagerService.MIN_INSTALLABLE_TARGET_SDK;
import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.pm.PackageManagerService.POST_INSTALL;
import static com.android.server.pm.PackageManagerService.SCAN_AS_APEX;
import static com.android.server.pm.PackageManagerService.SCAN_AS_APK_IN_APEX;
import static com.android.server.pm.PackageManagerService.SCAN_AS_FACTORY;
import static com.android.server.pm.PackageManagerService.SCAN_AS_FULL_APP;
import static com.android.server.pm.PackageManagerService.SCAN_AS_INSTANT_APP;
import static com.android.server.pm.PackageManagerService.SCAN_AS_ODM;
import static com.android.server.pm.PackageManagerService.SCAN_AS_OEM;
import static com.android.server.pm.PackageManagerService.SCAN_AS_PRIVILEGED;
import static com.android.server.pm.PackageManagerService.SCAN_AS_PRODUCT;
import static com.android.server.pm.PackageManagerService.SCAN_AS_STOPPED_SYSTEM_APP;
import static com.android.server.pm.PackageManagerService.SCAN_AS_SYSTEM;
import static com.android.server.pm.PackageManagerService.SCAN_AS_SYSTEM_EXT;
import static com.android.server.pm.PackageManagerService.SCAN_AS_VENDOR;
import static com.android.server.pm.PackageManagerService.SCAN_AS_VIRTUAL_PRELOAD;
import static com.android.server.pm.PackageManagerService.SCAN_BOOTING;
import static com.android.server.pm.PackageManagerService.SCAN_DONT_KILL_APP;
import static com.android.server.pm.PackageManagerService.SCAN_DROP_CACHE;
import static com.android.server.pm.PackageManagerService.SCAN_FIRST_BOOT_OR_UPGRADE;
import static com.android.server.pm.PackageManagerService.SCAN_IGNORE_FROZEN;
import static com.android.server.pm.PackageManagerService.SCAN_INITIAL;
import static com.android.server.pm.PackageManagerService.SCAN_MOVE;
import static com.android.server.pm.PackageManagerService.SCAN_NEW_INSTALL;
import static com.android.server.pm.PackageManagerService.SCAN_NO_DEX;
import static com.android.server.pm.PackageManagerService.SCAN_REQUIRE_KNOWN;
import static com.android.server.pm.PackageManagerService.SCAN_UPDATE_SIGNATURE;
import static com.android.server.pm.PackageManagerService.TAG;
import static com.android.server.pm.PackageManagerService.WATCHDOG_TIMEOUT;
import static com.android.server.pm.PackageManagerServiceUtils.comparePackageSignatures;
import static com.android.server.pm.PackageManagerServiceUtils.compareSignatures;
import static com.android.server.pm.PackageManagerServiceUtils.compressedFileExists;
import static com.android.server.pm.PackageManagerServiceUtils.deriveAbiOverride;
import static com.android.server.pm.PackageManagerServiceUtils.extractAppMetadataFromApk;
import static com.android.server.pm.PackageManagerServiceUtils.isInstalledByAdb;
import static com.android.server.pm.PackageManagerServiceUtils.logCriticalInfo;
import static com.android.server.pm.PackageManagerServiceUtils.makeDirRecursive;
import static com.android.server.pm.SharedUidMigration.BEST_EFFORT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.apex.ApexInfo;
import android.app.AppOpsManager;
import android.app.ApplicationExitInfo;
import android.app.ApplicationPackageManager;
import android.app.BroadcastOptions;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.backup.IBackupManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.ArchivedPackageParcel;
import android.content.pm.DataLoaderType;
import android.content.pm.Flags;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInfoLite;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.VerifierInfo;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.incremental.IncrementalManager;
import android.os.incremental.IncrementalStorage;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.ExceptionUtils;
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.F2fsUtils;
import com.android.internal.pm.parsing.PackageParser2;
import com.android.internal.pm.parsing.PackageParserException;
import com.android.internal.pm.parsing.pkg.AndroidPackageLegacyUtils;
import com.android.internal.pm.parsing.pkg.ParsedPackage;
import com.android.internal.pm.pkg.component.ComponentMutateUtils;
import com.android.internal.pm.pkg.component.ParsedActivity;
import com.android.internal.pm.pkg.component.ParsedInstrumentation;
import com.android.internal.pm.pkg.component.ParsedIntentInfo;
import com.android.internal.pm.pkg.component.ParsedPermission;
import com.android.internal.pm.pkg.component.ParsedPermissionGroup;
import com.android.internal.pm.pkg.parsing.ParsingPackageUtils;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.server.EventLogTags;
import com.android.server.SystemConfig;
import com.android.server.criticalevents.CriticalEventLog;
import com.android.server.pm.dex.DexManager;
import com.android.server.pm.parsing.PackageCacher;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.permission.Permission;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.SharedLibraryWrapper;
import com.android.server.rollback.RollbackManagerInternal;
import com.android.server.utils.WatchedArrayMap;
import com.android.server.utils.WatchedLongSparseArray;

import dalvik.system.VMRuntime;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;


final class InstallPackageHelper {
    // One minute over PM WATCHDOG_TIMEOUT
    private static final long WAKELOCK_TIMEOUT_MS = WATCHDOG_TIMEOUT + 1000 * 60;
    private static final String INSTALLER_WAKE_LOCK_TAG = "installer:packages";

    private final PackageManagerService mPm;
    private final AppDataHelper mAppDataHelper;
    private final BroadcastHelper mBroadcastHelper;
    private final RemovePackageHelper mRemovePackageHelper;
    private final DeletePackageHelper mDeletePackageHelper;
    private final IncrementalManager mIncrementalManager;
    private final ApexManager mApexManager;
    private final DexManager mDexManager;
    private final Context mContext;
    private final PackageAbiHelper mPackageAbiHelper;
    private final SharedLibrariesImpl mSharedLibraries;
    private final PackageManagerServiceInjector mInjector;
    private final UpdateOwnershipHelper mUpdateOwnershipHelper;

    private final Object mInternalLock = new Object();
    @GuardedBy("mInternalLock")
    private PowerManager.WakeLock mInstallingWakeLock;

    // TODO(b/198166813): remove PMS dependency
    InstallPackageHelper(PackageManagerService pm,
                         AppDataHelper appDataHelper,
                         RemovePackageHelper removePackageHelper,
                         DeletePackageHelper deletePackageHelper,
                         BroadcastHelper broadcastHelper) {
        mPm = pm;
        mInjector = pm.mInjector;
        mAppDataHelper = appDataHelper;
        mBroadcastHelper = broadcastHelper;
        mRemovePackageHelper = removePackageHelper;
        mDeletePackageHelper = deletePackageHelper;
        mIncrementalManager = pm.mInjector.getIncrementalManager();
        mApexManager = pm.mInjector.getApexManager();
        mDexManager = pm.mInjector.getDexManager();
        mContext = pm.mInjector.getContext();
        mPackageAbiHelper = pm.mInjector.getAbiHelper();
        mSharedLibraries = pm.mInjector.getSharedLibrariesImpl();
        mUpdateOwnershipHelper = pm.mInjector.getUpdateOwnershipHelper();
    }

    /**
     * Commits the package scan and modifies system state.
     * <p><em>WARNING:</em> The method may throw an exception in the middle
     * of committing the package, leaving the system in an inconsistent state.
     * This needs to be fixed so, once we get to this point, no errors are
     * possible and the system is not left in an inconsistent state.
     */
    @GuardedBy("mPm.mLock")
    private AndroidPackage commitReconciledScanResultLocked(
            @NonNull ReconciledPackage reconciledPkg, int[] allUsers) {
        final InstallRequest request = reconciledPkg.mInstallRequest;
        // TODO(b/135203078): Move this even further away
        ParsedPackage parsedPackage = request.getParsedPackage();
        if (parsedPackage != null && "android".equals(parsedPackage.getPackageName())) {
            // TODO(b/135203078): Move this to initial parse
            parsedPackage.setVersionCode(mPm.getSdkVersion())
                    .setVersionCodeMajor(0);
        }

        final @PackageManagerService.ScanFlags int scanFlags = request.getScanFlags();
        final PackageSetting oldPkgSetting = request.getScanRequestOldPackageSetting();
        final PackageSetting originalPkgSetting = request.getScanRequestOriginalPackageSetting();
        final String realPkgName = request.getRealPackageName();
        final PackageSetting pkgSetting;
        if (request.getScanRequestPackageSetting() != null) {
            SharedUserSetting requestSharedUserSetting = mPm.mSettings.getSharedUserSettingLPr(
                    request.getScanRequestPackageSetting());
            SharedUserSetting resultSharedUserSetting = mPm.mSettings.getSharedUserSettingLPr(
                    request.getScannedPackageSetting());
            if (requestSharedUserSetting != null
                    && requestSharedUserSetting != resultSharedUserSetting) {
                // shared user changed, remove from old shared user
                requestSharedUserSetting.removePackage(request.getScanRequestPackageSetting());
                // Prune unused SharedUserSetting
                if (mPm.mSettings.checkAndPruneSharedUserLPw(requestSharedUserSetting, false)) {
                    // Set the app ID in removed info for UID_REMOVED broadcasts
                    request.setRemovedAppId(requestSharedUserSetting.mAppId);
                }
            }
        }
        if (request.isExistingSettingCopied()) {
            pkgSetting = request.getScanRequestPackageSetting();
            pkgSetting.updateFrom(request.getScannedPackageSetting());
        } else {
            pkgSetting = request.getScannedPackageSetting();
            if (originalPkgSetting != null) {
                mPm.mSettings.addRenamedPackageLPw(
                        AndroidPackageUtils.getRealPackageOrNull(parsedPackage,
                                pkgSetting.isSystem()),
                        originalPkgSetting.getPackageName());
                mPm.mTransferredPackages.add(originalPkgSetting.getPackageName());
            } else {
                mPm.mSettings.removeRenamedPackageLPw(parsedPackage.getPackageName());
            }
        }
        SharedUserSetting sharedUserSetting = mPm.mSettings.getSharedUserSettingLPr(pkgSetting);
        if (sharedUserSetting != null) {
            sharedUserSetting.addPackage(pkgSetting);
            if (parsedPackage.isLeavingSharedUser()
                    && SharedUidMigration.applyStrategy(BEST_EFFORT)
                    && sharedUserSetting.isSingleUser()) {
                // Attempt the transparent shared UID migration
                mPm.mSettings.convertSharedUserSettingsLPw(sharedUserSetting);
            }
        }
        if (request.isForceQueryableOverride()) {
            pkgSetting.setForceQueryableOverride(true);
        }

        InstallSource installSource = request.getInstallSource();
        final boolean isApex = (scanFlags & SCAN_AS_APEX) != 0;
        final boolean pkgAlreadyExists = oldPkgSetting != null;
        final String oldUpdateOwner =
                pkgAlreadyExists ? oldPkgSetting.getInstallSource().mUpdateOwnerPackageName : null;
        final String updateOwnerFromSysconfig = isApex || !pkgSetting.isSystem() ? null
                : mPm.mInjector.getSystemConfig().getSystemAppUpdateOwnerPackageName(
                        parsedPackage.getPackageName());
        final boolean isUpdateOwnershipDenylisted =
                mUpdateOwnershipHelper.isUpdateOwnershipDenylisted(parsedPackage.getPackageName());
        final boolean isUpdateOwnershipEnabled = oldUpdateOwner != null;

        // For standard install (install via session), the installSource isn't null.
        if (installSource != null) {
            // If this is part of a standard install, set the initiating package name, else rely on
            // previous device state.
            if (!isInstalledByAdb(installSource.mInitiatingPackageName)) {
                final PackageSetting ips = mPm.mSettings.getPackageLPr(
                        installSource.mInitiatingPackageName);
                if (ips != null) {
                    installSource = installSource.setInitiatingPackageSignatures(
                            ips.getSignatures());
                }
            }

            // Handle the update ownership enforcement for APK
            if (!isApex) {
                // User installer UID as "current" userId if present; otherwise, use the userId
                // from InstallRequest.
                final int userId = installSource.mInstallerPackageUid != Process.INVALID_UID
                        ? UserHandle.getUserId(installSource.mInstallerPackageUid)
                        : request.getUserId();
                // Whether the parsedPackage is installed on the userId
                // If the oldPkgSetting doesn't exist, this package isn't installed for all users.
                final boolean isUpdate = pkgAlreadyExists && (userId >= UserHandle.USER_SYSTEM
                        // If userID >= 0, we could check via oldPkgSetting.getInstalled(userId).
                        ? oldPkgSetting.getInstalled(userId)
                        // When userId is -1 (USER_ALL) and it's not installed for any user,
                        // treat it as not installed.
                        : oldPkgSetting.getNotInstalledUserIds().length
                                <= (UserManager.isHeadlessSystemUserMode() ? 1 : 0));
                final boolean isRequestUpdateOwnership = (request.getInstallFlags()
                        & PackageManager.INSTALL_REQUEST_UPDATE_OWNERSHIP) != 0;
                final boolean isSameUpdateOwner =
                        TextUtils.equals(oldUpdateOwner, installSource.mInstallerPackageName);
                final boolean isInstallerUpdateOwnerDenylistProvider =
                        mUpdateOwnershipHelper.isUpdateOwnershipDenyListProvider(
                                installSource.mUpdateOwnerPackageName);

                // Here we handle the update owner for the package, and the rules are:
                // -. Only enabling update ownership enforcement on initial installation if the
                //    installer has requested.
                // -. Once the installer changes and users agree to proceed, clear the update
                //    owner (package state in other users are taken into account as well).
                if (!isUpdate) {
                    if (!isRequestUpdateOwnership
                            || isUpdateOwnershipDenylisted
                            || isInstallerUpdateOwnerDenylistProvider) {
                        installSource = installSource.setUpdateOwnerPackageName(null);
                    } else if ((!isUpdateOwnershipEnabled && pkgAlreadyExists)
                            || (isUpdateOwnershipEnabled && !isSameUpdateOwner)) {
                        installSource = installSource.setUpdateOwnerPackageName(null);
                    }
                } else if (!isSameUpdateOwner
                        || !isUpdateOwnershipEnabled) {
                    installSource = installSource.setUpdateOwnerPackageName(null);
                }
            }

            pkgSetting.setInstallSource(installSource);
        // For non-standard install (addForInit), installSource is null.
        } else if (pkgSetting.isSystem()) {
            // We still honor the manifest attr if the system app wants to opt-out of it.
            final boolean isSameUpdateOwner = isUpdateOwnershipEnabled
                    && TextUtils.equals(oldUpdateOwner, updateOwnerFromSysconfig);

            // Here we handle the update owner for the system package, and the rules are:
            // -. We use the update owner from sysconfig as the initial value.
            // -. Once an app becomes to system app later via OTA, only retains the update
            //    owner if it's consistence with sysconfig.
            // -. Clear the update owner when update owner changes from sysconfig.
            if (!pkgAlreadyExists || isSameUpdateOwner) {
                pkgSetting.setUpdateOwnerPackage(updateOwnerFromSysconfig);
            } else {
                pkgSetting.setUpdateOwnerPackage(null);
            }
        }

        if ((scanFlags & SCAN_AS_APK_IN_APEX) != 0) {
            boolean isFactory = (scanFlags & SCAN_AS_FACTORY) != 0;
            pkgSetting.getPkgState().setApkInUpdatedApex(!isFactory);
        }

        pkgSetting.getPkgState().setApexModuleName(request.getApexModuleName());

        // TODO(toddke): Consider a method specifically for modifying the Package object
        // post scan; or, moving this stuff out of the Package object since it has nothing
        // to do with the package on disk.
        // We need to have this here because addUserToSettingLPw() is sometimes responsible
        // for creating the application ID. If we did this earlier, we would be saving the
        // correct ID.
        parsedPackage.setUid(pkgSetting.getAppId());
        final AndroidPackage pkg = parsedPackage.hideAsFinal();

        mPm.mSettings.writeUserRestrictionsLPw(pkgSetting, oldPkgSetting);

        if (realPkgName != null) {
            mPm.mTransferredPackages.add(pkg.getPackageName());
        }

        if (reconciledPkg.mCollectedSharedLibraryInfos != null
                || (oldPkgSetting != null
                && !oldPkgSetting.getSharedLibraryDependencies().isEmpty())) {
            // Reconcile if the new package or the old package uses shared libraries.
            // It is possible that the old package uses shared libraries but the new one doesn't.
            mSharedLibraries.executeSharedLibrariesUpdate(pkg, pkgSetting, null, null,
                    reconciledPkg.mCollectedSharedLibraryInfos, allUsers);
        }

        final KeySetManagerService ksms = mPm.mSettings.getKeySetManagerService();
        if (reconciledPkg.mRemoveAppKeySetData) {
            ksms.removeAppKeySetDataLPw(pkg.getPackageName());
        }
        if (reconciledPkg.mSharedUserSignaturesChanged) {
            sharedUserSetting.signaturesChanged = Boolean.TRUE;
            sharedUserSetting.signatures.mSigningDetails = reconciledPkg.mSigningDetails;
        }
        pkgSetting.setSigningDetails(reconciledPkg.mSigningDetails);

        final int userId = request.getUserId();
        // Modify state for the given package setting
        commitPackageSettings(pkg, pkgSetting, oldPkgSetting, reconciledPkg);
        if (pkgSetting.getInstantApp(userId)) {
            mPm.mInstantAppRegistry.addInstantApp(userId, pkgSetting.getAppId());
        }

        if (!IncrementalManager.isIncrementalPath(pkgSetting.getPathString())) {
            pkgSetting.setLoadingProgress(1f);
        }

        // TODO: passes the package name as an argument in a message to the handler for V+
        //  so we don't need to rely on creating lambda objects so frequently.
        if (UpdateOwnershipHelper.hasValidOwnershipDenyList(pkgSetting)) {
            mPm.mHandler.post(() -> handleUpdateOwnerDenyList(pkgSetting));
        }
        return pkg;
    }

    private void handleUpdateOwnerDenyList(PackageSetting pkgSetting) {
        ArraySet<String> listItems = mUpdateOwnershipHelper.readUpdateOwnerDenyList(pkgSetting);
        if (listItems != null && !listItems.isEmpty()) {
            mUpdateOwnershipHelper.addToUpdateOwnerDenyList(pkgSetting.getPackageName(),
                    listItems);
            SystemConfig config = SystemConfig.getInstance();
            synchronized (mPm.mLock) {
                for (String unownedPackage : listItems) {
                    PackageSetting unownedSetting = mPm.mSettings.getPackageLPr(unownedPackage);
                    if (unownedSetting != null
                            && config.getSystemAppUpdateOwnerPackageName(unownedPackage) == null) {
                        unownedSetting.setUpdateOwnerPackage(null);
                    }
                }
            }
        }
    }

    /**
     * Adds a scanned package to the system. When this method is finished, the package will
     * be available for query, resolution, etc...
     */
    private void commitPackageSettings(@NonNull AndroidPackage pkg,
            @NonNull PackageSetting pkgSetting, @Nullable PackageSetting oldPkgSetting,
            ReconciledPackage reconciledPkg) {
        final String pkgName = pkg.getPackageName();
        final InstallRequest request = reconciledPkg.mInstallRequest;
        final AndroidPackage oldPkg = request.getScanRequestOldPackage();
        final int scanFlags = request.getScanFlags();
        final boolean chatty = (request.getParseFlags() & ParsingPackageUtils.PARSE_CHATTY) != 0;
        if (mPm.mCustomResolverComponentName != null
                && mPm.mCustomResolverComponentName.getPackageName().equals(pkg.getPackageName())) {
            mPm.setUpCustomResolverActivity(pkg, pkgSetting);
        }

        // When upgrading a package, clear the app metadata file path for the new package.
        if (oldPkgSetting != null
                && oldPkgSetting.getLastUpdateTime() < pkgSetting.getLastUpdateTime()) {
            pkgSetting.setAppMetadataFilePath(null);
            pkgSetting.setAppMetadataSource(APP_METADATA_SOURCE_UNKNOWN);
        }
        // If the app metadata file path is not null then this is a system app with a preloaded app
        // metadata file on the system image. Do not reset the path and source if this is the
        // case.
        if (pkgSetting.getAppMetadataFilePath() == null) {
            String dir = pkg.getPath();
            if (pkgSetting.isSystem()) {
                dir = Environment.getDataDirectoryPath() + "/app-metadata/" + pkg.getPackageName();
            }
            String appMetadataFilePath = dir + "/" + APP_METADATA_FILE_NAME;
            if (request.hasAppMetadataFile()) {
                pkgSetting.setAppMetadataFilePath(appMetadataFilePath);
                if (Flags.aslInApkAppMetadataSource()) {
                    pkgSetting.setAppMetadataSource(APP_METADATA_SOURCE_INSTALLER);
                }
            } else if (Flags.aslInApkAppMetadataSource()) {
                Map<String, PackageManager.Property> properties = pkg.getProperties();
                if (properties.containsKey(PROPERTY_ANDROID_SAFETY_LABEL)) {
                    // ASL file extraction is done in post-install
                    pkgSetting.setAppMetadataFilePath(appMetadataFilePath);
                    pkgSetting.setAppMetadataSource(APP_METADATA_SOURCE_APK);
                }
            }
        }

        if (pkg.getPackageName().equals("android")) {
            mPm.setPlatformPackage(pkg, pkgSetting);
        }

        // writer
        ArrayList<AndroidPackage> clientLibPkgs =
                mSharedLibraries.commitSharedLibraryChanges(pkg, pkgSetting,
                        reconciledPkg.mAllowedSharedLibraryInfos,
                        reconciledPkg.getCombinedAvailablePackages(), scanFlags);

        request.setLibraryConsumers(clientLibPkgs);

        if ((scanFlags & SCAN_BOOTING) != 0) {
            // No apps can run during boot scan, so they don't need to be frozen
        } else if ((scanFlags & SCAN_DONT_KILL_APP) != 0) {
            // Caller asked to not kill app, so it's probably not frozen
        } else if ((scanFlags & SCAN_IGNORE_FROZEN) != 0) {
            // Caller asked us to ignore frozen check for some reason; they
            // probably didn't know the package name
        } else {
            // We're doing major surgery on this package, so it better be frozen
            // right now to keep it from launching
            mPm.snapshotComputer().checkPackageFrozen(pkgName);
        }

        final boolean isReplace = request.isInstallReplace();
        // Also need to kill any apps that are dependent on the library, except the case of
        // installation of new version static shared library.
        if (clientLibPkgs != null) {
            if (pkg.getStaticSharedLibraryName() == null || isReplace) {
                for (int i = 0; i < clientLibPkgs.size(); i++) {
                    AndroidPackage clientPkg = clientLibPkgs.get(i);
                    String packageName = clientPkg.getPackageName();
                    mPm.killApplication(packageName,
                            clientPkg.getUid(), "update lib",
                            ApplicationExitInfo.REASON_DEPENDENCY_DIED);
                }
            }
        }

        // writer
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "updateSettings");

        synchronized (mPm.mLock) {
            // We don't expect installation to fail beyond this point
            // Add the new setting to mSettings
            mPm.mSettings.insertPackageSettingLPw(pkgSetting, pkg);
            // Add the new setting to mPackages
            mPm.mPackages.put(pkg.getPackageName(), pkg);
            if ((scanFlags & SCAN_AS_APK_IN_APEX) != 0) {
                mApexManager.registerApkInApex(pkg);
            }

            if ((mPm.isDeviceUpgrading() && pkgSetting.isSystem()) || isReplace) {
                for (int userId : mPm.mUserManager.getUserIds()) {
                    pkgSetting.restoreComponentSettings(userId);
                }
            }

            // Don't add keysets for APEX as their package settings are not persisted and will
            // result in orphaned keysets.
            if ((scanFlags & SCAN_AS_APEX) == 0) {
                // Add the package's KeySets to the global KeySetManagerService
                KeySetManagerService ksms = mPm.mSettings.getKeySetManagerService();
                ksms.addScannedPackageLPw(pkg);
            }

            final Computer snapshot = mPm.snapshotComputer();
            mPm.mComponentResolver.addAllComponents(pkg, chatty, mPm.mSetupWizardPackage, snapshot);
            mPm.mAppsFilter.addPackage(snapshot, pkgSetting, isReplace,
                    (scanFlags & SCAN_DONT_KILL_APP) != 0 /* retainImplicitGrantOnReplace */);
            mPm.addAllPackageProperties(pkg);

            // Only verify app links for non-archival installations, otherwise there won't be any
            // declared app links.
            if (!request.isArchived()) {
                if (oldPkgSetting == null || oldPkgSetting.getPkg() == null) {
                    mPm.mDomainVerificationManager.addPackage(pkgSetting,
                            request.getPreVerifiedDomains());
                } else {
                    mPm.mDomainVerificationManager.migrateState(oldPkgSetting, pkgSetting,
                            request.getPreVerifiedDomains());
                }
            }

            int collectionSize = ArrayUtils.size(pkg.getInstrumentations());
            StringBuilder r = null;
            int i;
            for (i = 0; i < collectionSize; i++) {
                ParsedInstrumentation a = pkg.getInstrumentations().get(i);
                ComponentMutateUtils.setPackageName(a, pkg.getPackageName());
                mPm.addInstrumentation(a.getComponentName(), a);
                if (chatty) {
                    if (r == null) {
                        r = new StringBuilder(256);
                    } else {
                        r.append(' ');
                    }
                    r.append(a.getName());
                }
            }
            if (r != null) {
                if (DEBUG_PACKAGE_SCANNING) Log.d(TAG, "  Instrumentation: " + r);
            }

            final List<String> protectedBroadcasts = pkg.getProtectedBroadcasts();
            if (!protectedBroadcasts.isEmpty()) {
                synchronized (mPm.mProtectedBroadcasts) {
                    mPm.mProtectedBroadcasts.addAll(protectedBroadcasts);
                }
            }

            mPm.mPermissionManager.onPackageAdded(pkgSetting,
                    (scanFlags & SCAN_AS_INSTANT_APP) != 0, oldPkg);
        }

        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
    }

    public Pair<Integer, IntentSender> installExistingPackageAsUser(@Nullable String packageName,
            @UserIdInt int userId, @PackageManager.InstallFlags int installFlags,
            @PackageManager.InstallReason int installReason,
            @Nullable List<String> allowlistedRestrictedPermissions,
            @Nullable IntentSender intentSender) {
        if (DEBUG_INSTALL) {
            Log.v(TAG, "installExistingPackageAsUser package=" + packageName + " userId=" + userId
                    + " installFlags=" + installFlags + " installReason=" + installReason
                    + " allowlistedRestrictedPermissions=" + allowlistedRestrictedPermissions);
        }

        final int callingUid = Binder.getCallingUid();
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.INSTALL_PACKAGES)
                != PackageManager.PERMISSION_GRANTED
                && mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.INSTALL_EXISTING_PACKAGES)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Neither user " + callingUid + " nor current process has "
                    + android.Manifest.permission.INSTALL_PACKAGES + ".");
        }
        PackageSetting pkgSetting;
        final Computer preLockSnapshot = mPm.snapshotComputer();
        preLockSnapshot.enforceCrossUserPermission(callingUid, userId,
                true /* requireFullPermission */, true /* checkShell */,
                "installExistingPackage for user " + userId);
        if (mPm.isUserRestricted(userId, UserManager.DISALLOW_INSTALL_APPS)) {
            return Pair.create(PackageManager.INSTALL_FAILED_USER_RESTRICTED, intentSender);
        }

        final long callingId = Binder.clearCallingIdentity();
        try {
            boolean installed = false;
            final boolean instantApp =
                    (installFlags & PackageManager.INSTALL_INSTANT_APP) != 0;
            final boolean fullApp =
                    (installFlags & PackageManager.INSTALL_FULL_APP) != 0;
            final boolean isPackageDeviceAdmin = mPm.isPackageDeviceAdmin(packageName, userId);
            final boolean isProtectedPackage = mPm.mProtectedPackages != null
                    && mPm.mProtectedPackages.isPackageStateProtected(userId, packageName);

            // writer
            synchronized (mPm.mLock) {
                final Computer snapshot = mPm.snapshotComputer();
                pkgSetting = mPm.mSettings.getPackageLPr(packageName);
                if (pkgSetting == null || pkgSetting.getPkg() == null) {
                    return Pair.create(PackageManager.INSTALL_FAILED_INVALID_URI, intentSender);
                }
                if (instantApp && (pkgSetting.isSystem() || pkgSetting.isUpdatedSystemApp()
                        || isPackageDeviceAdmin || isProtectedPackage)) {
                    return Pair.create(PackageManager.INSTALL_FAILED_INVALID_URI, intentSender);
                }
                if (!snapshot.canViewInstantApps(callingUid, UserHandle.getUserId(callingUid))) {
                    // only allow the existing package to be used if it's installed as a full
                    // application for at least one user
                    boolean installAllowed = false;
                    for (int checkUserId : mPm.mUserManager.getUserIds()) {
                        installAllowed = !pkgSetting.getInstantApp(checkUserId);
                        if (installAllowed) {
                            break;
                        }
                    }
                    if (!installAllowed) {
                        return Pair.create(PackageManager.INSTALL_FAILED_INVALID_URI, intentSender);
                    }
                }
                if (!pkgSetting.getInstalled(userId)) {
                    pkgSetting.setInstalled(true, userId);
                    pkgSetting.setHidden(false, userId);
                    pkgSetting.setInstallReason(installReason, userId);
                    pkgSetting.setUninstallReason(PackageManager.UNINSTALL_REASON_UNKNOWN, userId);
                    pkgSetting.setFirstInstallTime(System.currentTimeMillis(), userId);
                    // Clear any existing archive state.
                    mPm.mInstallerService.mPackageArchiver.clearArchiveState(pkgSetting, userId);
                    mPm.mSettings.writePackageRestrictionsLPr(userId);
                    mPm.mSettings.writeKernelMappingLPr(pkgSetting);
                    installed = true;
                } else if (fullApp && pkgSetting.getInstantApp(userId)) {
                    // upgrade app from instant to full; we don't allow app downgrade
                    installed = true;
                }
                ScanPackageUtils.setInstantAppForUser(mPm.mInjector, pkgSetting, userId, instantApp,
                        fullApp);
            }

            if (installed) {
                final String updateOwner = pkgSetting.getInstallSource().mUpdateOwnerPackageName;
                final var dpmi = mInjector.getLocalService(DevicePolicyManagerInternal.class);
                final boolean isFromManagedUserOrProfile =
                        dpmi != null && dpmi.isUserOrganizationManaged(userId);
                // Here we handle the update owner when install existing package, and the rules are:
                // -. Retain the update owner when enable a system app in managed user or profile.
                // -. Retain the update owner if the installer is the same.
                // -. Clear the update owner when update owner changes.
                if (!preLockSnapshot.isCallerSameApp(updateOwner, callingUid)
                        && (!pkgSetting.isSystem() || !isFromManagedUserOrProfile)) {
                    pkgSetting.setUpdateOwnerPackage(null);
                }
                if (pkgSetting.getPkg() != null) {
                    final PermissionManagerServiceInternal.PackageInstalledParams.Builder
                            permissionParamsBuilder =
                            new PermissionManagerServiceInternal.PackageInstalledParams.Builder();
                    if ((installFlags & PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS)
                            != 0) {
                        permissionParamsBuilder.setAllowlistedRestrictedPermissions(
                                new ArrayList<>(pkgSetting.getPkg().getRequestedPermissions()));
                    }
                    mPm.mPermissionManager.onPackageInstalled(pkgSetting.getPkg(),
                            Process.INVALID_UID /* previousAppId */,
                            permissionParamsBuilder.build(), userId);

                    try (PackageManagerTracedLock installLock = mPm.mInstallLock.acquireLock()) {
                        // We don't need to freeze for a brand new install
                        mAppDataHelper.prepareAppDataPostCommitLIF(
                                pkgSetting, /* previousAppId= */0, new int[] { userId });
                    }
                }
                // TODO(b/278553670) Store archive state for the user.
                boolean isArchived = (pkgSetting.getPkg() == null);
                mBroadcastHelper.sendPackageAddedForUser(mPm.snapshotComputer(), packageName,
                        pkgSetting, userId, isArchived, DataLoaderType.NONE,
                        mPm.mAppPredictionServicePackage);
                synchronized (mPm.mLock) {
                    mPm.updateSequenceNumberLP(pkgSetting, new int[]{ userId });
                }
                // start async restore with no post-install since we finish install here

                final IntentSender onCompleteSender = intentSender;
                intentSender = null;

                InstallRequest request = new InstallRequest(userId,
                        PackageManager.INSTALL_SUCCEEDED, pkgSetting.getPkg(), new int[]{ userId },
                        () -> {
                            mPm.restorePermissionsAndUpdateRolesForNewUserInstall(packageName,
                                    userId);
                            if (onCompleteSender != null) {
                                onInstallComplete(PackageManager.INSTALL_SUCCEEDED, mContext,
                                        onCompleteSender);
                            }
                        }, pkgSetting.getAppId(), callingUid, pkgSetting.isSystem());
                restoreAndPostInstall(request);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }

        return Pair.create(PackageManager.INSTALL_SUCCEEDED, intentSender);
    }

    static void onInstallComplete(int returnCode, Context context, IntentSender target) {
        Intent fillIn = new Intent();
        fillIn.putExtra(PackageInstaller.EXTRA_STATUS,
                PackageManager.installStatusToPublicStatus(returnCode));
        try {
            final BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setPendingIntentBackgroundActivityLaunchAllowed(false);
            target.sendIntent(context, 0, fillIn,
                    null /* requiredPermission */, options.toBundle(),
                    null /* executor */, null /* onFinished*/);
        } catch (IntentSender.SendIntentException ignored) {
        }
    }

    public void restoreAndPostInstall(InstallRequest request) {
        final int userId = request.getUserId();
        if (DEBUG_INSTALL) {
            Log.v(TAG,
                    "restoreAndPostInstall userId=" + userId + " package=" + request.getPkg());
        }

        PackageSetting packageSetting = null;

        final boolean update = request.isUpdate();
        boolean doRestore = false;
        if (request.getPkg() != null && !request.isArchived()) {
            // A restore should be requested at this point:
            // if the install succeeded and it's not an archived install
            if (!update) {
                // AND the operation is not an update,
                doRestore = true;
            } else {
                // OR the package has never been restored.
                String packageName = request.getPkg().getPackageName();
                synchronized (mPm.mLock) {
                    packageSetting = mPm.mSettings.getPackageLPr(packageName);
                    if (packageSetting != null && packageSetting.isPendingRestore()) {
                        doRestore = true;
                    }
                }
            }
        }

        // Set up the post-install work request bookkeeping.  This will be used
        // and cleaned up by the post-install event handling regardless of whether
        // there's a restore pass performed.  Token values are >= 1.
        int token;
        if (mPm.mNextInstallToken < 0) mPm.mNextInstallToken = 1;
        token = mPm.mNextInstallToken++;
        synchronized (mPm.mRunningInstalls) {
            mPm.mRunningInstalls.put(token, request);
        }

        if (DEBUG_INSTALL) Log.v(TAG, "+ starting restore round-trip " + token);

        final boolean succeeded = request.getReturnCode() == PackageManager.INSTALL_SUCCEEDED;
        if (succeeded) {
            request.onRestoreStarted();
            if (doRestore) {
                // Pass responsibility to the Backup Manager.  It will perform a
                // restore if appropriate, then pass responsibility back to the
                // Package Manager to run the post-install observer callbacks
                // and broadcasts.
                // Note: MUST close freezer before backup/restore, otherwise test
                // of CtsBackupHostTestCases will fail.
                request.closeFreezer();
                doRestore = performBackupManagerRestore(userId, token, request);
            }

            // If this is an update to a package that might be potentially downgraded, then we
            // need to check with the rollback manager whether there's any userdata that might
            // need to be snapshotted or restored for the package.
            //
            // TODO(narayan): Get this working for cases where userId == UserHandle.USER_ALL.
            if (!doRestore && update) {
                doRestore = performRollbackManagerRestore(userId, token, request);
            }

            if (doRestore && !request.hasPostInstallRunnable()) {
                boolean hasNeverBeenRestored =
                        packageSetting != null && packageSetting.isPendingRestore();
                request.setPostInstallRunnable(() -> {
                    // Permissions should be restored on each user that has the app installed for
                    // the first time, unless it's an unarchive install for an archived app, in
                    // which case the permissions should be restored on each user that has the
                    // app updated.
                    int[] userIdsToRestorePermissions = hasNeverBeenRestored
                            ? request.getUpdateBroadcastUserIds()
                            : request.getFirstTimeBroadcastUserIds();
                    for (int restorePermissionUserId : userIdsToRestorePermissions) {
                        mPm.restorePermissionsAndUpdateRolesForNewUserInstall(request.getName(),
                                restorePermissionUserId);
                    }
                });
            }
        }

        if (doRestore) {
            if (packageSetting != null) {
                synchronized (mPm.mLock) {
                    packageSetting.setPendingRestore(false);
                }
            }
        } else {
            // No restore possible, or the Backup Manager was mysteriously not available.
            // we don't need to wait for restore to complete before closing the freezer,
            // so we can close the freezer right away.
            // Also just fire the post-install work request directly.
            request.closeFreezer();
            if (DEBUG_INSTALL) Log.v(TAG, "No restore - queue post-install for " + token);

            Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "postInstall", token);

            Message msg = mPm.mHandler.obtainMessage(POST_INSTALL, token, 0);
            mPm.mHandler.sendMessage(msg);
        }
    }

    /**
     * Perform Backup Manager restore for a given {@link InstallRequest}.
     * Returns whether the restore successfully completed.
     */
    private boolean performBackupManagerRestore(int userId, int token, InstallRequest request) {
        if (request.getPkg() == null) {
            return false;
        }
        IBackupManager iBackupManager = mInjector.getIBackupManager();
        if (iBackupManager != null) {
            // For backwards compatibility as USER_ALL previously routed directly to USER_SYSTEM
            // in the BackupManager. USER_ALL is used in compatibility tests.
            if (userId == UserHandle.USER_ALL) {
                userId = UserHandle.USER_SYSTEM;
            }
            if (DEBUG_INSTALL) {
                Log.v(TAG, "token " + token + " to BM for possible restore for user " + userId);
            }
            Trace.asyncTraceBegin(TRACE_TAG_PACKAGE_MANAGER, "restore", token);
            try {
                if (iBackupManager.isUserReadyForBackup(userId)) {
                    iBackupManager.restoreAtInstallForUser(
                            userId, request.getPkg().getPackageName(), token);
                } else {
                    Slog.w(TAG, "User " + userId + " is not ready. Restore at install "
                            + "didn't take place.");
                    return false;
                }
            } catch (RemoteException e) {
                // can't happen; the backup manager is local
            } catch (Exception e) {
                Slog.e(TAG, "Exception trying to enqueue restore", e);
                return false;
            }
        } else {
            Slog.e(TAG, "Backup Manager not found!");
            return false;
        }
        return true;
    }

    /**
     * Perform Rollback Manager restore for a given {@link InstallRequest}.
     * Returns whether the restore successfully completed.
     */
    private boolean performRollbackManagerRestore(int userId, int token, InstallRequest request) {
        if (request.getPkg() == null) {
            return false;
        }
        final String packageName = request.getPkg().getPackageName();
        final int[] allUsers = mPm.mUserManager.getUserIds();
        final int[] installedUsers;

        final PackageSetting ps;
        int appId = -1;
        long ceDataInode = -1;
        synchronized (mPm.mLock) {
            ps = mPm.mSettings.getPackageLPr(packageName);
            if (ps != null) {
                appId = ps.getAppId();
                ceDataInode = ps.getCeDataInode(userId);
                // NOTE: We ignore the user specified in the InstallParam because we know this is
                // an update, and hence need to restore data for all installed users.
                installedUsers = ps.queryInstalledUsers(allUsers, true);
            } else {
                installedUsers = new int[0];
            }
        }

        final int installFlags = request.getInstallFlags();
        boolean doSnapshotOrRestore = ((installFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) != 0
                || (installFlags & PackageManager.INSTALL_REQUEST_DOWNGRADE) != 0);

        if (ps != null && doSnapshotOrRestore) {
            final String seInfo = ps.getSeInfo();
            final RollbackManagerInternal rollbackManager =
                    mInjector.getLocalService(RollbackManagerInternal.class);
            rollbackManager.snapshotAndRestoreUserData(packageName,
                    UserHandle.toUserHandles(installedUsers), appId, ceDataInode, seInfo, token);
            return true;
        }
        return false;
    }

    /**
     * Installs one or more packages atomically. This operation is broken up into four phases:
     * <ul>
     *     <li><b>Prepare</b>
     *         <br/>Analyzes any current install state, parses the package and does initial
     *         validation on it.</li>
     *     <li><b>Scan</b>
     *         <br/>Interrogates the parsed packages given the context collected in prepare.</li>
     *     <li><b>Reconcile</b>
     *         <br/>Validates scanned packages in the context of each other and the current system
     *         state to ensure that the install will be successful.
     *     <li><b>Commit</b>
     *         <br/>Commits all scanned packages and updates system state. This is the only place
     *         that system state may be modified in the install flow and all predictable errors
     *         must be determined before this phase.</li>
     * </ul>
     *
     * Failure at any phase will result in a full failure to install all packages.
     */
    void installPackagesTraced(List<InstallRequest> requests, MoveInfo moveInfo) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "installPackages");
        boolean pendingForDexopt = false;
        boolean success = false;
        final Map<String, Boolean> createdAppId = new ArrayMap<>(requests.size());
        final Map<String, Settings.VersionInfo> versionInfos = new ArrayMap<>(requests.size());
        final long acquireTime = acquireWakeLock(requests.size());
        try {
            CriticalEventLog.getInstance().logInstallPackagesStarted();
            if (prepareInstallPackages(requests)
                    && scanInstallPackages(requests, createdAppId, versionInfos)) {
                List<ReconciledPackage> reconciledPackages =
                        reconcileInstallPackages(requests, versionInfos);
                if (reconciledPackages == null) {
                    return;
                }

                if (renameAndUpdatePaths(requests)) {
                    // rename before dexopt because art will encoded the path in the odex/vdex file
                    if (Flags.improveInstallFreeze()) {
                        pendingForDexopt = true;
                        final Runnable actionsAfterDexopt = () ->
                                doPostDexopt(reconciledPackages, requests,
                                        createdAppId, moveInfo, acquireTime);
                        prepPerformDexoptIfNeeded(reconciledPackages, actionsAfterDexopt);
                    } else {
                        if (commitInstallPackages(reconciledPackages)) {
                            success = true;
                        }
                    }
                }
            }
        } finally {
            if (!pendingForDexopt) {
                completeInstallProcess(requests, createdAppId, success);
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                doPostInstall(requests, moveInfo);
                releaseWakeLock(acquireTime, requests.size());
            }
        }
    }

    void doPostDexopt(List<ReconciledPackage> reconciledPackages,
            List<InstallRequest> requests, Map<String, Boolean> createdAppId,
            MoveInfo moveInfo, long acquireTime) {
        for (InstallRequest request : requests) {
            request.onWaitDexoptFinished();
        }
        boolean success = false;
        try {
            if (commitInstallPackages(reconciledPackages)) {
                success = true;
            }
        } finally {
            completeInstallProcess(requests, createdAppId, success);
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            doPostInstall(requests, moveInfo);
            releaseWakeLock(acquireTime, requests.size());
        }
    }

    private void doPostInstall(List<InstallRequest> requests, MoveInfo moveInfo) {
        for (InstallRequest request : requests) {
            doPostInstallCleanUp(request, moveInfo);
        }

        for (InstallRequest request : requests) {
            restoreAndPostInstall(request);
        }
    }

    private void doPostInstallCleanUp(InstallRequest request, MoveInfo moveInfo) {
        if (moveInfo != null) {
            if (request.getReturnCode() == PackageManager.INSTALL_SUCCEEDED) {
                mRemovePackageHelper.cleanUpForMoveInstall(moveInfo.mFromUuid,
                        moveInfo.mPackageName, moveInfo.mFromCodePath);
            } else {
                mRemovePackageHelper.cleanUpForMoveInstall(moveInfo.mToUuid,
                        moveInfo.mPackageName, moveInfo.mFromCodePath);
            }
        } else {
            if (request.getReturnCode() != PackageManager.INSTALL_SUCCEEDED) {
                mRemovePackageHelper.removeCodePath(request.getCodeFile());
            }
        }
    }

    private long acquireWakeLock(int count) {
        if (!mPm.isSystemReady()) {
            return -1;
        }
        synchronized (mInternalLock) {
            if (mInstallingWakeLock == null) {
                PowerManager pwm = mContext.getSystemService(PowerManager.class);
                if (pwm != null) {
                    mInstallingWakeLock = pwm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                            INSTALLER_WAKE_LOCK_TAG);
                } else {
                    Slog.w(TAG, "Unable to obtain power manager while obtaining wake lock");
                    return -1;
                }
            }

            mInstallingWakeLock.acquire(WAKELOCK_TIMEOUT_MS * count);
            return SystemClock.elapsedRealtime();
        }
    }

    private void releaseWakeLock(final long acquireTime, int count) {
        if (acquireTime < 0) {
            return;
        }
        synchronized (mInternalLock) {
            try {
                if (mInstallingWakeLock == null) {
                    return;
                }
                if (mInstallingWakeLock.isHeld()) {
                    mInstallingWakeLock.release();
                }
            } catch (RuntimeException e) {
                Slog.wtf(TAG, "Error while releasing installer lock", e);
            }
        }
    }

    private int[] getNewUsers(InstallRequest installRequest, int[] allUsers)
            throws PackageManagerException {
        final int userId = installRequest.getUserId();
        if (userId != UserHandle.USER_ALL && userId != UserHandle.USER_CURRENT
                && !ArrayUtils.contains(allUsers, userId)) {
            throw new PackageManagerException(PackageManagerException.INTERNAL_ERROR_MISSING_USER,
                    "User " + userId + " doesn't exist or has been removed");
        }

        final IntArray newUserIds = new IntArray();
        if (userId != UserHandle.USER_ALL) {
            newUserIds.add(userId);
        } else if (allUsers != null) {
            final int[] installedForUsers = installRequest.getOriginUsers();
            for (int currentUserId : allUsers) {
                final boolean installedForCurrentUser = ArrayUtils.contains(
                        installedForUsers, currentUserId);
                final boolean restrictedByPolicy =
                        mPm.isUserRestricted(currentUserId,
                                UserManager.DISALLOW_INSTALL_APPS)
                                || mPm.isUserRestricted(currentUserId,
                                UserManager.DISALLOW_DEBUGGING_FEATURES);
                if (installedForCurrentUser || !restrictedByPolicy) {
                    newUserIds.add(currentUserId);
                }
            }
        }

        if (newUserIds.size() == 0) {
            throw new PackageManagerException(PackageManagerException.INTERNAL_ERROR_MISSING_USER,
                    "User " + userId + " doesn't exist or has been removed");
        } else {
            return newUserIds.toArray();
        }
    }

    private void prepPerformDexoptIfNeeded(List<ReconciledPackage> reconciledPackages,
            Runnable actionsAfterDexopt) {
        List<CompletableFuture<Void>> completableFutures = new ArrayList<>();
        for (ReconciledPackage reconciledPkg : reconciledPackages) {
            final InstallRequest request = reconciledPkg.mInstallRequest;
            // prepare profiles
            final PackageSetting ps = request.getScannedPackageSetting();
            final PackageSetting oldPkgSetting = request.getScanRequestOldPackageSetting();
            final int[] allUsers = mPm.mUserManager.getUserIds();
            if (reconciledPkg.mCollectedSharedLibraryInfos != null
                    || (oldPkgSetting != null
                    && !oldPkgSetting.getSharedLibraryDependencies().isEmpty())) {
                // Reconcile if the new package or the old package uses shared libraries.
                // It is possible that the old package uses shared libraries but the new
                // one doesn't.
                mSharedLibraries.executeSharedLibrariesUpdate(request.getParsedPackage(), ps,
                        null, null, reconciledPkg.mCollectedSharedLibraryInfos, allUsers);
            }

            try (PackageManagerTracedLock installLock = mPm.mInstallLock.acquireLock()) {
                final int[] newUsers = getNewUsers(request, allUsers);
                // Hardcode previousAppId to 0 to disable any data migration (http://b/221088088)
                mAppDataHelper.prepareAppDataPostCommitLIF(ps, 0, newUsers);
                if (request.isClearCodeCache()) {
                    mAppDataHelper.clearAppDataLIF(ps.getPkg(), UserHandle.USER_ALL,
                            FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL
                                    | Installer.FLAG_CLEAR_CODE_CACHE_ONLY);
                }
            } catch (PackageManagerException e) {
                request.setError(e.error, e.getMessage());
                break;
            }
            request.setKeepArtProfile(true);

            CompletableFuture<Void> future =
                    DexOptHelper.performDexoptIfNeededAsync(request, mDexManager);
            completableFutures.add(future);
            request.onWaitDexoptStarted();
        }

        if (!completableFutures.isEmpty()) {
            CompletableFuture<Void> allFutures =
                    CompletableFuture.allOf(
                            completableFutures.toArray(CompletableFuture[]::new));
            var unused = allFutures.thenRun(() -> mPm.mHandler.post(actionsAfterDexopt));
        } else {
            actionsAfterDexopt.run();
        }
    }

    private boolean renameAndUpdatePaths(List<InstallRequest> requests) {
        try (PackageManagerTracedLock installLock = mPm.mInstallLock.acquireLock()) {
            for (InstallRequest request : requests) {
                ParsedPackage parsedPackage = request.getParsedPackage();
                final boolean isApex = (request.getScanFlags() & SCAN_AS_APEX) != 0;
                if (isApex) {
                    continue;
                }
                try {
                    doRenameLI(request, parsedPackage);
                } catch (PrepareFailure e) {
                    request.setError(e);
                    return false;
                }

                // update paths that are set before renaming
                PackageSetting scannedPackageSetting = request.getScannedPackageSetting();
                scannedPackageSetting.setPath(new File(parsedPackage.getPath()));
                scannedPackageSetting.setLegacyNativeLibraryPath(
                        parsedPackage.getNativeLibraryRootDir());
            }
            return true;
        }
    }

    private boolean prepareInstallPackages(List<InstallRequest> requests) {
        for (InstallRequest request : requests) {
            try {
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "preparePackage");
                request.onPrepareStarted();
                preparePackage(request);
            } catch (PrepareFailure prepareFailure) {
                request.setError(prepareFailure.error,
                        prepareFailure.getMessage());
                request.setOriginPackage(prepareFailure.mConflictingPackage);
                request.setOriginPermission(prepareFailure.mConflictingPermission);
                return false;
            } finally {
                request.onPrepareFinished();
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }
        }
        return true;
    }

    private boolean scanInstallPackages(List<InstallRequest> requests,
            Map<String, Boolean> createdAppId, Map<String, Settings.VersionInfo> versionInfos) {
        final Set<String> scannedPackages = new ArraySet<>(requests.size());
        for (InstallRequest request : requests) {
            final ParsedPackage packageToScan = request.getParsedPackage();
            if (packageToScan == null) {
                request.setError(INSTALL_FAILED_SESSION_INVALID,
                        "Failed to obtain package to scan");
                return false;
            }
            request.setReturnCode(PackageManager.INSTALL_SUCCEEDED);
            final String packageName = packageToScan.getPackageName();
            try {
                request.onScanStarted();
                final ScanResult scanResult = scanPackageTraced(request.getParsedPackage(),
                        request.getParseFlags(), request.getScanFlags(),
                        System.currentTimeMillis(), request.getUser(),
                        request.getAbiOverride());
                request.setScanResult(scanResult);
                request.onScanFinished();
                if (!scannedPackages.add(packageName)) {
                    request.setError(
                            PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE,
                            "Duplicate package "
                                    + packageName
                                    + " in multi-package install request.");
                    return false;
                }
                if (!checkNoAppStorageIsConsistent(
                        request.getScanRequestOldPackage(), packageToScan)) {
                    // TODO: INSTALL_FAILED_UPDATE_INCOMPATIBLE is about incomptabible
                    //  signatures. Is there a better error code?
                    request.setError(
                            INSTALL_FAILED_UPDATE_INCOMPATIBLE,
                            "Update attempted to change value of "
                                    + PackageManager.PROPERTY_NO_APP_DATA_STORAGE);
                    return false;
                }
                final boolean isApex = (request.getScanFlags() & SCAN_AS_APEX) != 0;
                final boolean isSdkLibrary = packageToScan.isSdkLibrary();
                if (isApex || (isSdkLibrary && disallowSdkLibsToBeApps())) {
                    request.getScannedPackageSetting().setAppId(Process.INVALID_UID);
                } else {
                    createdAppId.put(packageName, optimisticallyRegisterAppId(request));
                }
                versionInfos.put(packageName,
                        mPm.getSettingsVersionForPackage(packageToScan));
            } catch (PackageManagerException e) {
                request.setError("Scanning Failed.", e);
                return false;
            }
            if (request.isArchived()) {
                final SparseArray<String> responsibleInstallerTitles =
                        PackageArchiver.getResponsibleInstallerTitles(mContext,
                                mPm.snapshotComputer(), request.getInstallSource(),
                                request.getUserId(), mPm.mUserManager.getUserIds());
                if (responsibleInstallerTitles == null
                        || responsibleInstallerTitles.size() == 0) {
                    request.setError(PackageManagerException.ofInternalError(
                            "Failed to obtain the responsible installer info",
                            INTERNAL_ERROR_ARCHIVE_NO_INSTALLER_TITLE));
                    return false;
                }
                request.setResponsibleInstallerTitles(responsibleInstallerTitles);
            }
        }
        return true;
    }

    private List<ReconciledPackage> reconcileInstallPackages(List<InstallRequest> requests,
            Map<String, Settings.VersionInfo> versionInfos) {
        try (PackageManagerTracedLock installLock = mPm.mInstallLock.acquireLock()) {
            synchronized (mPm.mLock) {
                try {
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "reconcilePackages");
                    return ReconcilePackageUtils.reconcilePackages(
                            requests, Collections.unmodifiableMap(mPm.mPackages),
                            versionInfos, mSharedLibraries, mPm.mSettings.getKeySetManagerService(),
                            mPm.mSettings, mPm.mInjector.getSystemConfig());
                } catch (ReconcileFailure e) {
                    for (InstallRequest request : requests) {
                        request.setError("Reconciliation failed...", e);
                    }
                    return null;
                } finally {
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }
            }
        }
    }


    private boolean commitInstallPackages(List<ReconciledPackage> reconciledPackages) {
        try (PackageManagerTracedLock installLock = mPm.mInstallLock.acquireLock()) {
            if (Flags.improveInstallFreeze()) {
                // Postpone freezer until after reconcile
                for (ReconciledPackage reconciledPkg : reconciledPackages) {
                    InstallRequest installRequest = reconciledPkg.mInstallRequest;
                    String packageName = installRequest.getParsedPackage().getPackageName();
                    PackageFreezer freezer = freezePackageForInstall(packageName,
                            UserHandle.USER_ALL, installRequest.getInstallFlags(),
                            "installPackageLI", ApplicationExitInfo.REASON_PACKAGE_UPDATED,
                            installRequest);
                    installRequest.setFreezer(freezer);
                }
            }
            synchronized (mPm.mLock) {
                try {
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "commitPackages");
                    commitPackagesLocked(reconciledPackages, mPm.mUserManager.getUserIds());
                } finally {
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }
            }
            executePostCommitStepsLIF(reconciledPackages);
        }
        return true;
    }

    private void completeInstallProcess(List<InstallRequest> requests,
            Map<String, Boolean> createdAppId, boolean success) {
        if (success) {
            for (InstallRequest request : requests) {
                mInjector.getAppOpsManagerInternal().onPackageAdded(
                        request.getName(), request.getAppId());
                if (request.getDataLoaderType() != DataLoaderType.INCREMENTAL) {
                    continue;
                }
                if (request.getSignatureSchemeVersion() != SIGNING_BLOCK_V4) {
                    continue;
                }
                // For incremental installs, we bypass the verifier prior to install. Now
                // that we know the package is valid, send a notice to the verifier with
                // the root hash of the base.apk.
                final String baseCodePath = request.getPkg().getBaseApkPath();
                final String[] splitCodePaths = request.getPkg().getSplitCodePaths();
                final Uri originUri = request.getOriginUri();
                final int verificationId = mPm.mPendingVerificationToken++;
                final String rootHashString = PackageManagerServiceUtils
                        .buildVerificationRootHashString(baseCodePath, splitCodePaths);
                VerificationUtils.broadcastPackageVerified(verificationId, originUri,
                        PackageManager.VERIFICATION_ALLOW, rootHashString,
                        request.getDataLoaderType(), request.getUser(), mContext);
            }
        } else {
            for (InstallRequest installRequest : requests) {
                if (installRequest.getParsedPackage() != null && createdAppId.getOrDefault(
                        installRequest.getParsedPackage().getPackageName(), false)) {
                    cleanUpAppIdCreation(installRequest);
                }
            }
            // TODO(b/194319951): create a more descriptive reason than unknown
            // mark all non-failure installs as UNKNOWN so we do not treat them as success
            for (InstallRequest request : requests) {
                request.closeFreezer();
                if (request.getReturnCode() == PackageManager.INSTALL_SUCCEEDED) {
                    request.setReturnCode(PackageManager.INSTALL_UNKNOWN);
                }
            }
        }
    }

    private boolean checkNoAppStorageIsConsistent(AndroidPackage oldPkg, AndroidPackage newPkg) {
        if (oldPkg == null) {
            // New install, nothing to check against.
            return true;
        }
        final PackageManager.Property curProp =
                oldPkg.getProperties().get(PackageManager.PROPERTY_NO_APP_DATA_STORAGE);
        final PackageManager.Property newProp =
                newPkg.getProperties().get(PackageManager.PROPERTY_NO_APP_DATA_STORAGE);
        if (curProp == null || !curProp.getBoolean()) {
            return newProp == null || !newProp.getBoolean();
        }
        return newProp != null && newProp.getBoolean();
    }

    private void preparePackage(InstallRequest request) throws PrepareFailure {
        final int[] allUsers =  mPm.mUserManager.getUserIds();
        final int installFlags = request.getInstallFlags();
        final boolean onExternal = request.getVolumeUuid() != null;
        final boolean instantApp = ((installFlags & PackageManager.INSTALL_INSTANT_APP) != 0);
        final boolean fullApp = ((installFlags & PackageManager.INSTALL_FULL_APP) != 0);
        final boolean virtualPreload =
                ((installFlags & PackageManager.INSTALL_VIRTUAL_PRELOAD) != 0);
        final boolean isApex = ((installFlags & PackageManager.INSTALL_APEX) != 0);
        final boolean isRollback =
                request.getInstallReason() == PackageManager.INSTALL_REASON_ROLLBACK;
        @PackageManagerService.ScanFlags int scanFlags = SCAN_NEW_INSTALL | SCAN_UPDATE_SIGNATURE;
        if (request.isInstallMove()) {
            // moving a complete application; perform an initial scan on the new install location
            scanFlags |= SCAN_INITIAL;
        }
        if ((installFlags & PackageManager.INSTALL_DONT_KILL_APP) != 0) {
            scanFlags |= SCAN_DONT_KILL_APP;
        }
        if (instantApp) {
            scanFlags |= SCAN_AS_INSTANT_APP;
        }
        if (fullApp) {
            scanFlags |= SCAN_AS_FULL_APP;
        }
        if (virtualPreload) {
            scanFlags |= SCAN_AS_VIRTUAL_PRELOAD;
        }
        if (isApex) {
            scanFlags |= SCAN_AS_APEX;
        }

        final File tmpPackageFile = new File(
                isApex ? request.getApexInfo().modulePath : request.getCodePath());
        if (DEBUG_INSTALL) Slog.d(TAG, "installPackageLI: path=" + tmpPackageFile);

        // Validity check
        if (instantApp && onExternal) {
            Slog.i(TAG, "Incompatible ephemeral install; external=" + onExternal);
            throw new PrepareFailure(PackageManager.INSTALL_FAILED_SESSION_INVALID);
        }

        // Retrieve PackageSettings and parse package
        @ParsingPackageUtils.ParseFlags final int parseFlags =
                mPm.getDefParseFlags() | ParsingPackageUtils.PARSE_CHATTY
                        | ParsingPackageUtils.PARSE_ENFORCE_CODE
                        | (onExternal ? ParsingPackageUtils.PARSE_EXTERNAL_STORAGE : 0);

        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "parsePackage");
        final ParsedPackage parsedPackage;
        final ArchivedPackageParcel archivedPackage;
        try (PackageParser2 pp = mPm.mInjector.getPreparingPackageParser()) {
            if (request.getPackageLite() == null || !request.isArchived()) {
                // TODO: pass packageLite from install request instead of reparsing the package
                parsedPackage = pp.parsePackage(tmpPackageFile, parseFlags, false);
                AndroidPackageUtils.validatePackageDexMetadata(parsedPackage);
                archivedPackage = null;
            } else {
                // Archived install mode, no APK.
                parsedPackage = pp.parsePackageFromPackageLite(request.getPackageLite(),
                        parseFlags);
                archivedPackage = request.getPackageLite().getArchivedPackage();
            }
        } catch (PackageParserException e) {
            throw new PrepareFailure(e.error,
                    ExceptionUtils.getCompleteMessage("Failed parse during installPackageLI", e));
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }

        // Block the install of apps using a lower target SDK version than required.
        // This helps improve security and privacy as malware can target older SDK versions
        // to avoid enforcement of new API behavior.
        boolean bypassLowTargetSdkBlock =
                ((installFlags & PackageManager.INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK) != 0);

        // Skip enforcement when the testOnly flag is set
        if (!bypassLowTargetSdkBlock && parsedPackage.isTestOnly()) {
            bypassLowTargetSdkBlock = true;
        }

        // Enforce the low target sdk install block except when
        // the --bypass-low-target-sdk-block is set for the install
        if (!bypassLowTargetSdkBlock
                && parsedPackage.getTargetSdkVersion() < MIN_INSTALLABLE_TARGET_SDK) {
            Slog.w(TAG, "App " + parsedPackage.getPackageName()
                    + " targets deprecated sdk version");
            throw new PrepareFailure(INSTALL_FAILED_DEPRECATED_SDK_VERSION,
                    "App package must target at least SDK version "
                            + MIN_INSTALLABLE_TARGET_SDK + ", but found "
                            + parsedPackage.getTargetSdkVersion());
        }

        // Instant apps have several additional install-time checks.
        if (instantApp) {
            if (parsedPackage.getTargetSdkVersion() < Build.VERSION_CODES.O) {
                Slog.w(TAG, "Instant app package " + parsedPackage.getPackageName()
                        + " does not target at least O");
                throw new PrepareFailure(INSTALL_FAILED_SESSION_INVALID,
                        "Instant app package must target at least O");
            }
            if (parsedPackage.getSharedUserId() != null) {
                Slog.w(TAG, "Instant app package " + parsedPackage.getPackageName()
                        + " may not declare sharedUserId.");
                throw new PrepareFailure(INSTALL_FAILED_SESSION_INVALID,
                        "Instant app package may not declare a sharedUserId");
            }
        }

        if (parsedPackage.isStaticSharedLibrary()) {
            // Static shared libraries have synthetic package names
            PackageManagerService.renameStaticSharedLibraryPackage(parsedPackage);

            // No static shared libs on external storage
            if (onExternal) {
                Slog.i(TAG, "Static shared libs can only be installed on internal storage.");
                throw new PrepareFailure(INSTALL_FAILED_INVALID_INSTALL_LOCATION,
                        "Static shared libs can only be installed on internal storage.");
            }
        }

        String pkgName = parsedPackage.getPackageName();
        request.setName(pkgName);
        if (parsedPackage.isTestOnly()) {
            if ((installFlags & PackageManager.INSTALL_ALLOW_TEST) == 0) {
                throw new PrepareFailure(INSTALL_FAILED_TEST_ONLY,
                        "Failed to install test-only apk. Did you forget to add -t?");
            }
        }

        // either use what we've been given or parse directly from the APK
        if (request.getSigningDetails() != SigningDetails.UNKNOWN) {
            parsedPackage.setSigningDetails(request.getSigningDetails());
        } else {
            final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
            final ParseResult<SigningDetails> result = ParsingPackageUtils.getSigningDetails(
                    input, parsedPackage, false /*skipVerify*/);
            if (result.isError()) {
                throw new PrepareFailure("Failed collect during installPackageLI",
                        result.getException());
            }
            parsedPackage.setSigningDetails(result.getResult());
        }

        if (instantApp && parsedPackage.getSigningDetails().getSignatureSchemeVersion()
                < SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V2) {
            Slog.w(TAG, "Instant app package " + parsedPackage.getPackageName()
                    + " is not signed with at least APK Signature Scheme v2");
            throw new PrepareFailure(INSTALL_FAILED_SESSION_INVALID,
                    "Instant app package must be signed with APK Signature Scheme v2 or greater");
        }

        boolean systemApp = false;
        boolean replace = false;
        synchronized (mPm.mLock) {
            PackageSetting ps = mPm.mSettings.getPackageLPr(pkgName);
            // Check if installing already existing package
            if ((installFlags & PackageManager.INSTALL_REPLACE_EXISTING) != 0) {
                String oldName = mPm.mSettings.getRenamedPackageLPr(pkgName);
                if (parsedPackage.getOriginalPackages().contains(oldName)
                        && mPm.mPackages.containsKey(oldName)) {
                    // This package is derived from an original package,
                    // and this device has been updating from that original
                    // name.  We must continue using the original name, so
                    // rename the new package here.
                    parsedPackage.setPackageName(oldName);
                    pkgName = oldName;
                    ps = mPm.mSettings.getPackageLPr(oldName);
                    if (DEBUG_INSTALL) {
                        Slog.d(TAG, "Replacing existing renamed package: oldName="
                                + oldName + " pkgName=" + pkgName);
                    }
                }
                if (ps != null) {
                    // This package, under its official name or its old name, already exists
                    // on the device; we should replace it.
                    replace = true;
                    if (DEBUG_INSTALL) Slog.d(TAG, "Replace existing package: " + pkgName);
                }
                if (replace) {
                    // Prevent apps opting out from runtime permissions
                    final int oldTargetSdk = ps.getTargetSdkVersion();
                    final int newTargetSdk = parsedPackage.getTargetSdkVersion();
                    if (oldTargetSdk > Build.VERSION_CODES.LOLLIPOP_MR1
                            && newTargetSdk <= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        throw new PrepareFailure(
                                PackageManager.INSTALL_FAILED_PERMISSION_MODEL_DOWNGRADE,
                                "Package " + parsedPackage.getPackageName()
                                        + " new target SDK " + newTargetSdk
                                        + " doesn't support runtime permissions but the old"
                                        + " target SDK " + oldTargetSdk + " does.");
                    }
                    // Prevent persistent apps from being updated
                    if (ps.isPersistent()
                            && ((installFlags & PackageManager.INSTALL_STAGED) == 0)) {
                        throw new PrepareFailure(PackageManager.INSTALL_FAILED_INVALID_APK,
                                "Package " + pkgName + " is a persistent app. "
                                        + "Persistent apps are not updateable.");
                    }
                    // When updating an sdk library, make sure that the versionMajor is
                    // changed if the targetSdkVersion and minSdkVersion have changed
                    if (parsedPackage.isSdkLibrary() && ps.getPkg() != null
                            && ps.getPkg().isSdkLibrary()) {
                        final int oldMinSdk = ps.getPkg().getMinSdkVersion();
                        final int newMinSdk = parsedPackage.getMinSdkVersion();
                        if (oldTargetSdk != newTargetSdk || oldMinSdk != newMinSdk) {
                            final int oldVersionMajor = ps.getPkg().getSdkLibVersionMajor();
                            final int newVersionMajor = parsedPackage.getSdkLibVersionMajor();
                            if (oldVersionMajor == newVersionMajor) {
                                throw new PrepareFailure(
                                        PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE,
                                        "Failure updating " + pkgName + " as it updates"
                                                + " an sdk library <"
                                                + parsedPackage.getSdkLibraryName() + ">"
                                                + " without changing the versionMajor, but the"
                                                + " targetSdkVersion or minSdkVersion has changed:"
                                                + " Old targetSdkVersion: " + oldTargetSdk
                                                + " new targetSdkVersion: " + newTargetSdk
                                                + " Old minSdkVersion: " + oldMinSdk
                                                + " new minSdkVersion: " + newMinSdk
                                                + " versionMajor: " + newVersionMajor
                                    );
                            }
                        }
                    }
                }

            }

            PackageSetting signatureCheckPs = ps;

            // SDK libs can have other major versions with different package names.
            if (signatureCheckPs == null && parsedPackage.isSdkLibrary()) {
                WatchedLongSparseArray<SharedLibraryInfo> libraryInfos =
                        mSharedLibraries.getSharedLibraryInfos(
                                parsedPackage.getSdkLibraryName());
                if (libraryInfos != null && libraryInfos.size() > 0) {
                    // Any existing version would do.
                    SharedLibraryInfo libraryInfo = libraryInfos.valueAt(0);
                    signatureCheckPs = mPm.mSettings.getPackageLPr(libraryInfo.getPackageName());
                }
            }

            // Static shared libs have same package with different versions where
            // we internally use a synthetic package name to allow multiple versions
            // of the same package, therefore we need to compare signatures against
            // the package setting for the latest library version.
            if (parsedPackage.isStaticSharedLibrary()) {
                SharedLibraryInfo libraryInfo =
                        mSharedLibraries.getLatestStaticSharedLibraVersion(parsedPackage);
                if (libraryInfo != null) {
                    signatureCheckPs = mPm.mSettings.getPackageLPr(libraryInfo.getPackageName());
                }
            }

            if (signatureCheckPs != null) {
                if (DEBUG_INSTALL) {
                    Slog.d(TAG,
                            "Existing package for signature checking: " + signatureCheckPs);
                }

                // Quick validity check that we're signed correctly if updating;
                // we'll check this again later when scanning, but we want to
                // bail early here before tripping over redefined permissions.
                final KeySetManagerService ksms = mPm.mSettings.getKeySetManagerService();
                final SharedUserSetting signatureCheckSus = mPm.mSettings.getSharedUserSettingLPr(
                        signatureCheckPs);
                if (ksms.shouldCheckUpgradeKeySetLocked(signatureCheckPs, signatureCheckSus,
                        scanFlags)) {
                    if (!ksms.checkUpgradeKeySetLocked(signatureCheckPs, parsedPackage)) {
                        throw new PrepareFailure(INSTALL_FAILED_UPDATE_INCOMPATIBLE, "Package "
                                + parsedPackage.getPackageName() + " upgrade keys do not match the "
                                + "previously installed version");
                    }
                } else {
                    try {
                        final boolean compareCompat =
                                ReconcilePackageUtils.isCompatSignatureUpdateNeeded(
                                        mPm.getSettingsVersionForPackage(parsedPackage));
                        final boolean compareRecover =
                                ReconcilePackageUtils.isRecoverSignatureUpdateNeeded(
                                        mPm.getSettingsVersionForPackage(parsedPackage));
                        // We don't care about disabledPkgSetting on install for now.
                        final boolean compatMatch =
                                PackageManagerServiceUtils.verifySignatures(signatureCheckPs,
                                        signatureCheckSus, null,
                                parsedPackage.getSigningDetails(), compareCompat, compareRecover,
                                isRollback);
                        // The new KeySets will be re-added later in the scanning process.
                        if (compatMatch) {
                            synchronized (mPm.mLock) {
                                ksms.removeAppKeySetDataLPw(parsedPackage.getPackageName());
                            }
                        }
                    } catch (PackageManagerException e) {
                        throw new PrepareFailure(e.error, e.getMessage());
                    }
                }
            }

            if (ps != null) {
                if (DEBUG_INSTALL) Slog.d(TAG, "Existing package: " + ps);

                systemApp = ps.isSystem();
                request.setOriginUsers(ps.queryUsersInstalledOrHasData(
                        allUsers));
            }

            final int numGroups = ArrayUtils.size(parsedPackage.getPermissionGroups());
            for (int groupNum = 0; groupNum < numGroups; groupNum++) {
                final ParsedPermissionGroup group =
                        parsedPackage.getPermissionGroups().get(groupNum);
                final PermissionGroupInfo sourceGroup = mPm.getPermissionGroupInfo(group.getName(),
                        0);

                if (sourceGroup != null && cannotInstallWithBadPermissionGroups(parsedPackage)) {
                    final String sourcePackageName = sourceGroup.packageName;

                    if ((replace || !parsedPackage.getPackageName().equals(sourcePackageName))
                            && !doesSignatureMatchForPermissions(sourcePackageName, parsedPackage,
                            scanFlags)) {
                        EventLog.writeEvent(0x534e4554, "146211400", -1,
                                parsedPackage.getPackageName());

                        throw new PrepareFailure(INSTALL_FAILED_DUPLICATE_PERMISSION_GROUP,
                                "Package "
                                        + parsedPackage.getPackageName()
                                        + " attempting to redeclare permission group "
                                        + group.getName() + " already owned by "
                                        + sourcePackageName);
                    }
                }
            }

            // TODO: Move logic for checking permission compatibility into PermissionManagerService
            final int n = ArrayUtils.size(parsedPackage.getPermissions());
            for (int i = n - 1; i >= 0; i--) {
                final ParsedPermission perm = parsedPackage.getPermissions().get(i);
                final Permission bp = mPm.mPermissionManager.getPermissionTEMP(perm.getName());

                // Don't allow anyone but the system to define ephemeral permissions.
                if ((perm.getProtectionLevel() & PermissionInfo.PROTECTION_FLAG_INSTANT) != 0
                        && !systemApp) {
                    Slog.w(TAG, "Non-System package " + parsedPackage.getPackageName()
                            + " attempting to delcare ephemeral permission "
                            + perm.getName() + "; Removing ephemeral.");
                    ComponentMutateUtils.setProtectionLevel(perm,
                            perm.getProtectionLevel() & ~PermissionInfo.PROTECTION_FLAG_INSTANT);
                }

                // Check whether the newly-scanned package wants to define an already-defined perm
                if (bp != null) {
                    final String sourcePackageName = bp.getPackageName();

                    if (!doesSignatureMatchForPermissions(sourcePackageName, parsedPackage,
                            scanFlags)) {
                        // If the owning package is the system itself, we log but allow
                        // install to proceed; we fail the install on all other permission
                        // redefinitions.
                        if (!sourcePackageName.equals("android")) {
                            throw new PrepareFailure(INSTALL_FAILED_DUPLICATE_PERMISSION,
                                    "Package "
                                            + parsedPackage.getPackageName()
                                            + " attempting to redeclare permission "
                                            + perm.getName() + " already owned by "
                                            + sourcePackageName)
                                    .conflictsWithExistingPermission(perm.getName(),
                                            sourcePackageName);
                        } else {
                            Slog.w(TAG, "Package " + parsedPackage.getPackageName()
                                    + " attempting to redeclare system permission "
                                    + perm.getName() + "; ignoring new declaration");
                            parsedPackage.removePermission(i);
                        }
                    } else if (!PLATFORM_PACKAGE_NAME.equals(parsedPackage.getPackageName())) {
                        // Prevent apps to change protection level to dangerous from any other
                        // type as this would allow a privilege escalation where an app adds a
                        // normal/signature permission in other app's group and later redefines
                        // it as dangerous leading to the group auto-grant.
                        if ((perm.getProtectionLevel() & PermissionInfo.PROTECTION_MASK_BASE)
                                == PermissionInfo.PROTECTION_DANGEROUS) {
                            if (!bp.isRuntime()) {
                                Slog.w(TAG, "Package " + parsedPackage.getPackageName()
                                        + " trying to change a non-runtime permission "
                                        + perm.getName()
                                        + " to runtime; keeping old protection level");
                                ComponentMutateUtils.setProtectionLevel(perm,
                                        bp.getProtectionLevel());
                            }
                        }
                    }
                }

                if (perm.getGroup() != null
                        && cannotInstallWithBadPermissionGroups(parsedPackage)) {
                    boolean isPermGroupDefinedByPackage = false;
                    for (int groupNum = 0; groupNum < numGroups; groupNum++) {
                        if (parsedPackage.getPermissionGroups().get(groupNum).getName()
                                .equals(perm.getGroup())) {
                            isPermGroupDefinedByPackage = true;
                            break;
                        }
                    }

                    if (!isPermGroupDefinedByPackage) {
                        final PermissionGroupInfo sourceGroup =
                                mPm.getPermissionGroupInfo(perm.getGroup(), 0);

                        if (sourceGroup == null) {
                            EventLog.writeEvent(0x534e4554, "146211400", -1,
                                    parsedPackage.getPackageName());

                            throw new PrepareFailure(INSTALL_FAILED_BAD_PERMISSION_GROUP,
                                    "Package "
                                            + parsedPackage.getPackageName()
                                            + " attempting to declare permission "
                                            + perm.getName() + " in non-existing group "
                                            + perm.getGroup());
                        } else {
                            String groupSourcePackageName = sourceGroup.packageName;

                            if (!PLATFORM_PACKAGE_NAME.equals(groupSourcePackageName)
                                    && !doesSignatureMatchForPermissions(groupSourcePackageName,
                                    parsedPackage, scanFlags)) {
                                EventLog.writeEvent(0x534e4554, "146211400", -1,
                                        parsedPackage.getPackageName());

                                throw new PrepareFailure(INSTALL_FAILED_BAD_PERMISSION_GROUP,
                                        "Package "
                                                + parsedPackage.getPackageName()
                                                + " attempting to declare permission "
                                                + perm.getName() + " in group "
                                                + perm.getGroup() + " owned by package "
                                                + groupSourcePackageName
                                                + " with incompatible certificate");
                            }
                        }
                    }
                }
            }
        }

        if (systemApp) {
            if (onExternal) {
                // Abort update; system app can't be replaced with app on sdcard
                throw new PrepareFailure(INSTALL_FAILED_INVALID_INSTALL_LOCATION,
                        "Cannot install updates to system apps on sdcard");
            } else if (instantApp) {
                // Abort update; system app can't be replaced with an instant app
                throw new PrepareFailure(INSTALL_FAILED_SESSION_INVALID,
                        "Cannot update a system app with an instant app");
            }
        }

        if (request.isInstallMove()) {
            // We did an in-place move, so dex is ready to roll
            scanFlags |= SCAN_NO_DEX;
            scanFlags |= SCAN_MOVE;

            synchronized (mPm.mLock) {
                final PackageSetting ps = mPm.mSettings.getPackageLPr(pkgName);
                if (ps == null) {
                    request.setError(PackageManagerException.ofInternalError(
                            "Missing settings for moved package " + pkgName,
                            PackageManagerException.INTERNAL_ERROR_MISSING_SETTING_FOR_MOVE));
                }

                // We moved the entire application as-is, so bring over the
                // previously derived ABI information.
                parsedPackage.setPrimaryCpuAbi(ps.getPrimaryCpuAbiLegacy())
                        .setSecondaryCpuAbi(ps.getSecondaryCpuAbiLegacy());
            }

        } else {
            // Enable SCAN_NO_DEX flag to skip dexopt at a later stage
            scanFlags |= SCAN_NO_DEX;
            // The native libs of Apex is located in apex_payload.img, don't need to parse it from
            // the original apex file
            if (!isApex) {
                try {
                    PackageSetting pkgSetting;
                    synchronized (mPm.mLock) {
                        pkgSetting = mPm.mSettings.getPackageLPr(pkgName);
                    }
                    boolean isUpdatedSystemAppFromExistingSetting = pkgSetting != null
                            && pkgSetting.isUpdatedSystemApp();
                    final String abiOverride = deriveAbiOverride(request.getAbiOverride());

                    // TODO: Are these system flags actually set properly at this stage?
                    boolean isUpdatedSystemAppInferred =
                            pkgSetting != null && pkgSetting.isSystem();
                    // derivePackageAbi works OK for archived packages despite logging some errors.
                    final Pair<PackageAbiHelper.Abis, PackageAbiHelper.NativeLibraryPaths>
                            derivedAbi = mPackageAbiHelper.derivePackageAbi(parsedPackage,
                            systemApp, (isUpdatedSystemAppFromExistingSetting
                                    || isUpdatedSystemAppInferred), abiOverride,
                            ScanPackageUtils.getAppLib32InstallDir());
                    derivedAbi.first.applyTo(parsedPackage);
                    derivedAbi.second.applyTo(parsedPackage);
                } catch (PackageManagerException pme) {
                    Slog.e(TAG, "Error deriving application ABI", pme);
                    throw PrepareFailure.ofInternalError(
                            "Error deriving application ABI: " + pme.getMessage(),
                            PackageManagerException.INTERNAL_ERROR_DERIVING_ABI);
                }
            }
        }

        if (isApex) {
            // Use the path returned by apexd
            parsedPackage.setPath(request.getApexInfo().modulePath);
            parsedPackage.setBaseApkPath(request.getApexInfo().modulePath);
        }

        PackageFreezer freezer = null;
        if (!Flags.improveInstallFreeze()) {
            freezer = freezePackageForInstall(pkgName, UserHandle.USER_ALL, installFlags,
                    "installPackageLI", ApplicationExitInfo.REASON_PACKAGE_UPDATED, request);
        }


        boolean shouldCloseFreezerBeforeReturn = true;
        try {
            final PackageSetting oldPackageState;
            final AndroidPackage oldPackage;
            String renamedPackage;
            boolean sysPkg = false;
            int targetScanFlags = scanFlags;
            int targetParseFlags = parseFlags;
            final PackageSetting ps;
            final PackageSetting disabledPs;
            final SharedUserSetting sharedUserSetting;
            if (replace) {
                final String pkgName11 = parsedPackage.getPackageName();
                synchronized (mPm.mLock) {
                    oldPackageState = mPm.mSettings.getPackageLPr(pkgName11);
                }
                oldPackage = oldPackageState.getAndroidPackage();
                if (parsedPackage.isStaticSharedLibrary()) {
                    // Static libs have a synthetic package name containing the version
                    // and cannot be updated as an update would get a new package name,
                    // unless this is installed from adb which is useful for development.
                    if (oldPackage != null
                            && (installFlags & PackageManager.INSTALL_FROM_ADB) == 0) {
                        throw new PrepareFailure(INSTALL_FAILED_DUPLICATE_PACKAGE,
                                "Packages declaring "
                                        + "static-shared libs cannot be updated");
                    }
                }

                final boolean isInstantApp = (scanFlags & SCAN_AS_INSTANT_APP) != 0;

                final int[] installedUsers;
                final int[] uninstalledUsers;

                synchronized (mPm.mLock) {
                    if (DEBUG_INSTALL) {
                        Slog.d(TAG,
                                "replacePackageLI: new=" + parsedPackage
                                        + ", old=" + oldPackageState.getName());
                    }

                    ps = mPm.mSettings.getPackageLPr(pkgName11);
                    disabledPs = mPm.mSettings.getDisabledSystemPkgLPr(ps);
                    sharedUserSetting = mPm.mSettings.getSharedUserSettingLPr(ps);

                    // verify signatures are valid
                    final KeySetManagerService ksms = mPm.mSettings.getKeySetManagerService();
                    if (ksms.shouldCheckUpgradeKeySetLocked(ps, sharedUserSetting, scanFlags)) {
                        if (!ksms.checkUpgradeKeySetLocked(ps, parsedPackage)) {
                            throw new PrepareFailure(INSTALL_FAILED_UPDATE_INCOMPATIBLE,
                                    "New package not signed by keys specified by upgrade-keysets: "
                                            + pkgName11);
                        }
                    } else {
                        SigningDetails parsedPkgSigningDetails = parsedPackage.getSigningDetails();
                        SigningDetails oldPkgSigningDetails = oldPackageState.getSigningDetails();
                        // default to original signature matching
                        if (!parsedPkgSigningDetails.checkCapability(oldPkgSigningDetails,
                                SigningDetails.CertCapabilities.INSTALLED_DATA)
                                && !oldPkgSigningDetails.checkCapability(parsedPkgSigningDetails,
                                SigningDetails.CertCapabilities.ROLLBACK)) {
                            // Allow the update to proceed if this is a rollback and the parsed
                            // package's current signing key is the current signer or in the lineage
                            // of the old package; this allows a rollback to a previously installed
                            // version after an app's signing key has been rotated without requiring
                            // the rollback capability on the previous signing key.
                            if (!isRollback || !oldPkgSigningDetails.hasAncestorOrSelf(
                                    parsedPkgSigningDetails)) {
                                throw new PrepareFailure(INSTALL_FAILED_UPDATE_INCOMPATIBLE,
                                        "New package has a different signature: " + pkgName11);
                            }
                        }
                    }

                    // don't allow a system upgrade unless the upgrade hash matches
                    if (oldPackageState.getRestrictUpdateHash() != null
                            && oldPackageState.isSystem()) {
                        final byte[] digestBytes;
                        try {
                            final MessageDigest digest = MessageDigest.getInstance("SHA-512");
                            updateDigest(digest, new File(parsedPackage.getBaseApkPath()));
                            if (!ArrayUtils.isEmpty(parsedPackage.getSplitCodePaths())) {
                                for (String path : parsedPackage.getSplitCodePaths()) {
                                    updateDigest(digest, new File(path));
                                }
                            }
                            digestBytes = digest.digest();
                        } catch (NoSuchAlgorithmException | IOException e) {
                            throw new PrepareFailure(INSTALL_FAILED_INVALID_APK,
                                    "Could not compute hash: " + pkgName11);
                        }
                        if (!Arrays.equals(oldPackageState.getRestrictUpdateHash(), digestBytes)) {
                            throw new PrepareFailure(INSTALL_FAILED_INVALID_APK,
                                    "New package fails restrict-update check: " + pkgName11);
                        }
                        // retain upgrade restriction
                        parsedPackage.setRestrictUpdateHash(
                                oldPackageState.getRestrictUpdateHash());
                    }

                    // APK should not change its sharedUserId declarations
                    final String oldSharedUid;
                    if (mPm.mSettings.getSharedUserSettingLPr(oldPackageState) != null) {
                        oldSharedUid = mPm.mSettings.getSharedUserSettingLPr(oldPackageState).name;
                    } else {
                        oldSharedUid = "<nothing>";
                    }
                    String newSharedUid = parsedPackage.getSharedUserId() != null
                            ? parsedPackage.getSharedUserId() : "<nothing>";
                    // If the previously installed app version doesn't have sharedUserSetting,
                    // check that the new apk either doesn't have sharedUserId or it is leaving one.
                    // If it contains sharedUserId but it is also leaving it, it's ok to proceed.
                    if (oldSharedUid.equals("<nothing>")) {
                        if (parsedPackage.isLeavingSharedUser()) {
                            newSharedUid = "<nothing>";
                        }
                    }

                    if (!oldSharedUid.equals(newSharedUid)) {
                        if (!(oldSharedUid.equals("<nothing>") && ps.getPkg() == null
                                && ps.isArchivedOnAnyUser(allUsers))) {
                            // Only allow changing sharedUserId if unarchiving
                            // TODO(b/361558423): remove this check after pre-archiving installs
                            // accept a sharedUserId param in the API
                            throw new PrepareFailure(INSTALL_FAILED_UID_CHANGED,
                                    "Package " + parsedPackage.getPackageName()
                                            + " shared user changed from "
                                            + oldSharedUid + " to " + newSharedUid);
                        }
                    }

                    // APK should not re-join shared UID
                    if (oldPackageState.isLeavingSharedUser()
                            && !parsedPackage.isLeavingSharedUser()) {
                        throw new PrepareFailure(INSTALL_FAILED_UID_CHANGED,
                                "Package " + parsedPackage.getPackageName()
                                        + " attempting to rejoin " + newSharedUid);
                    }

                    // In case of rollback, remember per-user/profile install state
                    installedUsers = ps.queryInstalledUsers(allUsers, true);
                    uninstalledUsers = ps.queryInstalledUsers(allUsers, false);

                    // don't allow an upgrade from full to ephemeral
                    if (isInstantApp) {
                        if (request.getUserId() == UserHandle.USER_ALL) {
                            for (int currentUser : allUsers) {
                                if (!ps.getInstantApp(currentUser)) {
                                    // can't downgrade from full to instant
                                    Slog.w(TAG,
                                            "Can't replace full app with instant app: " + pkgName11
                                                    + " for user: " + currentUser);
                                    throw new PrepareFailure(
                                            PackageManager.INSTALL_FAILED_SESSION_INVALID);
                                }
                            }
                        } else if (!ps.getInstantApp(request.getUserId())) {
                            // can't downgrade from full to instant
                            Slog.w(TAG, "Can't replace full app with instant app: " + pkgName11
                                    + " for user: " + request.getUserId());
                            throw new PrepareFailure(
                                    PackageManager.INSTALL_FAILED_SESSION_INVALID);
                        }
                    }
                }

                // Update what is removed
                PackageRemovedInfo removedInfo = new PackageRemovedInfo();
                removedInfo.mUid = ps.getAppId();
                removedInfo.mRemovedPackage = ps.getPackageName();
                removedInfo.mInstallerPackageName =
                        ps.getInstallSource().mInstallerPackageName;
                removedInfo.mIsStaticSharedLib =
                        parsedPackage.getStaticSharedLibraryName() != null;
                removedInfo.mIsUpdate = true;
                removedInfo.mOrigUsers = installedUsers;
                removedInfo.mInstallReasons = new SparseIntArray(installedUsers.length);
                for (int i = 0; i < installedUsers.length; i++) {
                    final int userId = installedUsers[i];
                    removedInfo.mInstallReasons.put(userId,
                            ps.getInstallReason(userId));
                }
                removedInfo.mUninstallReasons = new SparseIntArray(uninstalledUsers.length);
                for (int i = 0; i < uninstalledUsers.length; i++) {
                    final int userId = uninstalledUsers[i];
                    removedInfo.mUninstallReasons.put(userId,
                            ps.getUninstallReason(userId));
                }
                removedInfo.mIsExternal = oldPackageState.isExternalStorage();
                removedInfo.mRemovedPackageVersionCode = oldPackageState.getVersionCode();
                request.setRemovedInfo(removedInfo);

                sysPkg = oldPackageState.isSystem();
                if (sysPkg) {
                    // Set the system/privileged/oem/vendor/product flags as needed
                    final boolean privileged = oldPackageState.isPrivileged();
                    final boolean oem = oldPackageState.isOem();
                    final boolean vendor = oldPackageState.isVendor();
                    final boolean product = oldPackageState.isProduct();
                    final boolean odm = oldPackageState.isOdm();
                    final boolean systemExt = oldPackageState.isSystemExt();
                    final @ParsingPackageUtils.ParseFlags int systemParseFlags = parseFlags;
                    final @PackageManagerService.ScanFlags int systemScanFlags = scanFlags
                            | SCAN_AS_SYSTEM
                            | (privileged ? SCAN_AS_PRIVILEGED : 0)
                            | (oem ? SCAN_AS_OEM : 0)
                            | (vendor ? SCAN_AS_VENDOR : 0)
                            | (product ? SCAN_AS_PRODUCT : 0)
                            | (odm ? SCAN_AS_ODM : 0)
                            | (systemExt ? SCAN_AS_SYSTEM_EXT : 0);

                    if (DEBUG_INSTALL) {
                        Slog.d(TAG, "replaceSystemPackageLI: new=" + parsedPackage
                                + ", old=" + oldPackageState.getName());
                    }
                    request.setReturnCode(PackageManager.INSTALL_SUCCEEDED);
                    request.setApexModuleName(oldPackageState.getApexModuleName());
                    targetParseFlags = systemParseFlags;
                    targetScanFlags = systemScanFlags;
                } else { // non system replace
                    if (DEBUG_INSTALL) {
                        Slog.d(TAG,
                                "replaceNonSystemPackageLI: new=" + parsedPackage + ", old="
                                        + oldPackageState.getName());
                    }
                }
            } else { // new package install
                ps = null;
                disabledPs = null;
                oldPackageState = null;
                // Remember this for later, in case we need to rollback this install
                String pkgName1 = parsedPackage.getPackageName();

                if (DEBUG_INSTALL) Slog.d(TAG, "installNewPackageLI: " + parsedPackage);

                // TODO(b/194319951): MOVE TO RECONCILE
                synchronized (mPm.mLock) {
                    renamedPackage = mPm.mSettings.getRenamedPackageLPr(pkgName1);
                    if (renamedPackage != null) {
                        // A package with the same name is already installed, though
                        // it has been renamed to an older name.  The package we
                        // are trying to install should be installed as an update to
                        // the existing one, but that has not been requested, so bail.
                        throw new PrepareFailure(INSTALL_FAILED_ALREADY_EXISTS,
                                "Attempt to re-install " + pkgName1
                                        + " without first uninstalling package running as "
                                        + renamedPackage);
                    }
                    if (mPm.mPackages.containsKey(pkgName1)) {
                        // Don't allow installation over an existing package with the same name.
                        throw new PrepareFailure(INSTALL_FAILED_ALREADY_EXISTS,
                                "Attempt to re-install " + pkgName1
                                        + " without first uninstalling.");
                    }
                }
            }
            // we're passing the freezer back to be closed in a later phase of install
            shouldCloseFreezerBeforeReturn = false;

            request.setPrepareResult(replace, targetScanFlags, targetParseFlags,
                    oldPackageState, parsedPackage, archivedPackage,
                    replace /* clearCodeCache */, sysPkg, ps, disabledPs);
        } finally {
            if (freezer != null) {
                request.setFreezer(freezer);
                if (shouldCloseFreezerBeforeReturn) {
                    freezer.close();
                }
            }
        }
    }

    /**
     * Rename package into final resting place. All paths on the given
     * scanned package should be updated to reflect the rename.
     */
    @GuardedBy("mPm.mInstallLock")
    private void doRenameLI(InstallRequest request,
            ParsedPackage parsedPackage) throws PrepareFailure {
        final int status = request.getReturnCode();
        final String statusMsg = request.getReturnMsg();
        if (request.isInstallMove()) {
            if (status != PackageManager.INSTALL_SUCCEEDED) {
                mRemovePackageHelper.cleanUpForMoveInstall(request.getMoveToUuid(),
                        request.getMovePackageName(), request.getMoveFromCodePath());
                throw new PrepareFailure(status, statusMsg);
            }
            return;
        }
        // For file installations
        if (status != PackageManager.INSTALL_SUCCEEDED) {
            mRemovePackageHelper.removeCodePath(request.getCodeFile());
            throw new PrepareFailure(status, statusMsg);
        }

        final File targetDir = resolveTargetDir(request.getInstallFlags(), request.getCodeFile());
        final File beforeCodeFile = request.getCodeFile();
        final File afterCodeFile = PackageManagerServiceUtils.getNextCodePath(targetDir,
                parsedPackage.getPackageName());

        if (DEBUG_INSTALL) Slog.d(TAG, "Renaming " + beforeCodeFile + " to " + afterCodeFile);
        final boolean onIncremental = mPm.mIncrementalManager != null
                && isIncrementalPath(beforeCodeFile.getAbsolutePath());
        try {
            makeDirRecursive(afterCodeFile.getParentFile(), 0771);
            if (onIncremental) {
                // Just link files here. The stage dir will be removed when the installation
                // session is completed.
                mPm.mIncrementalManager.linkCodePath(beforeCodeFile, afterCodeFile);
            } else {
                Os.rename(beforeCodeFile.getAbsolutePath(), afterCodeFile.getAbsolutePath());
            }
        } catch (IOException | ErrnoException e) {
            Slog.w(TAG, "Failed to rename", e);
            throw new PrepareFailure(PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE,
                    "Failed to rename");
        }

        if (!onIncremental && !SELinux.restoreconRecursive(afterCodeFile)) {
            Slog.w(TAG, "Failed to restorecon");
            throw new PrepareFailure(PackageManager.INSTALL_FAILED_MEDIA_UNAVAILABLE,
                    "Failed to restorecon");
        }

        // Reflect the rename internally
        request.setCodeFile(afterCodeFile);

        // Reflect the rename in scanned details
        try {
            String afterCanonicalPath = afterCodeFile.getCanonicalPath();
            String beforeCanonicalPath = beforeCodeFile.getCanonicalPath();
            parsedPackage.setPath(afterCanonicalPath);

            parsedPackage.setNativeLibraryDir(
                    parsedPackage.getNativeLibraryDir()
                            .replace(beforeCanonicalPath, afterCanonicalPath));
            parsedPackage.setNativeLibraryRootDir(
                    parsedPackage.getNativeLibraryRootDir()
                            .replace(beforeCanonicalPath, afterCanonicalPath));
            String secondaryNativeLibraryDir = parsedPackage.getSecondaryNativeLibraryDir();
            if (secondaryNativeLibraryDir != null) {
                parsedPackage.setSecondaryNativeLibraryDir(
                        secondaryNativeLibraryDir.replace(beforeCanonicalPath, afterCanonicalPath));
            }
        } catch (IOException e) {
            Slog.e(TAG, "Failed to get path: " + afterCodeFile, e);
            throw new PrepareFailure(PackageManager.INSTALL_FAILED_MEDIA_UNAVAILABLE,
                    "Failed to get path: " + afterCodeFile);
        }
        parsedPackage.setBaseApkPath(FileUtils.rewriteAfterRename(beforeCodeFile,
                afterCodeFile, parsedPackage.getBaseApkPath()));
        parsedPackage.setSplitCodePaths(FileUtils.rewriteAfterRename(beforeCodeFile,
                afterCodeFile, parsedPackage.getSplitCodePaths()));
        request.updateAllCodePaths(AndroidPackageUtils.getAllCodePaths(parsedPackage));
    }

    // TODO(b/168126411): Once staged install flow starts using the same folder as non-staged
    //  flow, we won't need this method anymore.
    private File resolveTargetDir(int installFlags, File codeFile) {
        boolean isStagedInstall = (installFlags & INSTALL_STAGED) != 0;
        if (isStagedInstall) {
            return Environment.getDataAppDirectory(null);
        } else {
            return codeFile.getParentFile();
        }
    }

    /*
     * Cannot properly check CANNOT_INSTALL_WITH_BAD_PERMISSION_GROUPS using CompatChanges
     * as this only works for packages that are installed
     *
     * TODO: Move logic for permission group compatibility into PermissionManagerService
     */
    @SuppressWarnings("AndroidFrameworkCompatChange")
    private static boolean cannotInstallWithBadPermissionGroups(ParsedPackage parsedPackage) {
        return parsedPackage.getTargetSdkVersion() >= Build.VERSION_CODES.S;
    }

    private boolean doesSignatureMatchForPermissions(@NonNull String sourcePackageName,
            @NonNull ParsedPackage parsedPackage, int scanFlags) {
        // If the defining package is signed with our cert, it's okay.  This
        // also includes the "updating the same package" case, of course.
        // "updating same package" could also involve key-rotation.

        final PackageSetting sourcePackageSetting;
        final KeySetManagerService ksms;
        final SharedUserSetting sharedUserSetting;
        synchronized (mPm.mLock) {
            sourcePackageSetting = mPm.mSettings.getPackageLPr(sourcePackageName);
            ksms = mPm.mSettings.getKeySetManagerService();
            sharedUserSetting = mPm.mSettings.getSharedUserSettingLPr(sourcePackageSetting);
        }

        final SigningDetails sourceSigningDetails = (sourcePackageSetting == null
                ? SigningDetails.UNKNOWN : sourcePackageSetting.getSigningDetails());
        if (sourcePackageName.equals(parsedPackage.getPackageName())
                && (ksms.shouldCheckUpgradeKeySetLocked(
                        sourcePackageSetting, sharedUserSetting, scanFlags))) {
            return ksms.checkUpgradeKeySetLocked(sourcePackageSetting, parsedPackage);
        } else {

            // in the event of signing certificate rotation, we need to see if the
            // package's certificate has rotated from the current one, or if it is an
            // older certificate with which the current is ok with sharing permissions
            if (sourceSigningDetails.checkCapability(
                    parsedPackage.getSigningDetails(),
                    SigningDetails.CertCapabilities.PERMISSION)) {
                return true;
            } else if (parsedPackage.getSigningDetails().checkCapability(
                    sourceSigningDetails,
                    SigningDetails.CertCapabilities.PERMISSION)) {
                // the scanned package checks out, has signing certificate rotation
                // history, and is newer; bring it over
                synchronized (mPm.mLock) {
                    sourcePackageSetting.setSigningDetails(parsedPackage.getSigningDetails());
                }
                return true;
            } else {
                return false;
            }
        }
    }

    private PackageFreezer freezePackageForInstall(String packageName, int userId, int installFlags,
            String killReason, int exitInfoReason, InstallRequest request) {
        if ((installFlags & PackageManager.INSTALL_DONT_KILL_APP) != 0) {
            return new PackageFreezer(mPm, request);
        } else {
            return mPm.freezePackage(packageName, userId, killReason, exitInfoReason, request);
        }
    }

    private static void updateDigest(MessageDigest digest, File file) throws IOException {
        try (DigestInputStream digestStream =
                     new DigestInputStream(new FileInputStream(file), digest)) {
            int length, total = 0;
            while ((length = digestStream.read()) != -1) {
                total += length;
            } // just plow through the file
        }
    }

    @GuardedBy("mPm.mLock")
    private void commitPackagesLocked(List<ReconciledPackage> reconciledPackages,
            @NonNull int[] allUsers) {
        // TODO: remove any expected failures from this method; this should only be able to fail due
        //       to unavoidable errors (I/O, etc.)
        for (ReconciledPackage reconciledPkg : reconciledPackages) {
            final InstallRequest installRequest = reconciledPkg.mInstallRequest;
            final ParsedPackage parsedPackage = installRequest.getParsedPackage();
            final String packageName = parsedPackage.getPackageName();

            installRequest.onCommitStarted();
            if (installRequest.isInstallReplace()) {
                AndroidPackage oldPackage = mPm.mPackages.get(packageName);

                // Set the update and install times
                PackageStateInternal deletedPkgSetting = mPm.snapshotComputer()
                        .getPackageStateInternal(packageName);
                // TODO(b/225756739): For rebootless APEX, consider using lastUpdateMillis provided
                //  by apexd to be more accurate.
                installRequest.setScannedPackageSettingFirstInstallTimeFromReplaced(
                        deletedPkgSetting, allUsers);
                long currentTime = System.currentTimeMillis();
                installRequest.setScannedPackageSettingLastUpdateTime(currentTime);
                installRequest.setScannedPackageSettingFirstInstallTime(currentTime);

                installRequest.getRemovedInfo().mBroadcastAllowList =
                        mPm.mAppsFilter.getVisibilityAllowList(mPm.snapshotComputer(),
                                installRequest.getScannedPackageSetting(),
                                allUsers, mPm.mSettings.getPackagesLocked());
                if (installRequest.isInstallSystem()) {
                    // Remove existing system package
                    mRemovePackageHelper.removePackage(oldPackage, true);
                    if (!disableSystemPackageLPw(oldPackage)) {
                        // We didn't need to disable the .apk as a current system package,
                        // which means we are replacing another update that is already
                        // installed.  We need to make sure to delete the older one's .apk.
                        installRequest.getRemovedInfo().mArgs = new CleanUpArgs(
                                packageName,
                                oldPackage.getPath(),
                                getAppDexInstructionSets(
                                        deletedPkgSetting.getPrimaryCpuAbi(),
                                        deletedPkgSetting.getSecondaryCpuAbi()));
                    } else {
                        installRequest.getRemovedInfo().mArgs = null;
                    }
                } else {
                    try {
                        // Settings will be written during the call to updateSettingsLI().
                        mDeletePackageHelper.executeDeletePackage(
                                reconciledPkg.mDeletePackageAction, packageName,
                                true, allUsers, false, installRequest.isKeepArtProfile());
                    } catch (SystemDeleteException e) {
                        if (mPm.mIsEngBuild) {
                            throw new RuntimeException("Unexpected failure", e);
                            // ignore; not possible for non-system app
                        }
                    }

                    if (installRequest.getReturnCode() == PackageManager.INSTALL_SUCCEEDED) {
                        PackageSetting ps2 = mPm.mSettings.getPackageLPr(
                                parsedPackage.getPackageName());
                        if (ps2 != null) {
                            installRequest.getRemovedInfo().mRemovedForAllUsers =
                                    mPm.mPackages.get(ps2.getPackageName()) == null;
                        }
                    }
                }
            }

            AndroidPackage pkg = commitReconciledScanResultLocked(reconciledPkg, allUsers);
            updateSettingsLI(pkg, allUsers, installRequest);

            final PackageSetting ps = mPm.mSettings.getPackageLPr(packageName);
            if (ps != null) {
                installRequest.setNewUsers(
                        ps.queryInstalledUsers(allUsers, true));
                ps.setUpdateAvailable(false /*updateAvailable*/);
            }
            if (installRequest.getReturnCode() == PackageManager.INSTALL_SUCCEEDED) {
                // If this is an archival installation then we'll initialize the archive status,
                // while also marking package as not installed.
                // Doing this at the very end of the install as we are using ps.getInstalled
                // to figure out which users were changed.
                mPm.markPackageAsArchivedIfNeeded(ps,
                        installRequest.getArchivedPackage(),
                        installRequest.getResponsibleInstallerTitles(),
                        installRequest.getNewUsers());
                mPm.updateSequenceNumberLP(ps, installRequest.getNewUsers());
                mPm.updateInstantAppInstallerLocked(packageName);

                // The installation is success, remove the split info copy stored in package
                // setting for the downgrade version check of DELETE_KEEP_DATA and archived app
                // cases.
                ps.setSplitNames(null);
                ps.setSplitRevisionCodes(null);
            }
            installRequest.onCommitFinished();
        }
        ApplicationPackageManager.invalidateGetPackagesForUidCache();
    }

    @GuardedBy("mPm.mLock")
    private boolean disableSystemPackageLPw(AndroidPackage oldPkg) {
        return mPm.mSettings.disableSystemPackageLPw(oldPkg.getPackageName(), true);
    }

    private void updateSettingsLI(AndroidPackage newPackage,
            int[] allUsers, InstallRequest installRequest) {
        updateSettingsInternalLI(newPackage, allUsers, installRequest);
    }

    private void updateSettingsInternalLI(AndroidPackage pkg,
            int[] allUsers, InstallRequest installRequest) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "updateSettingsInternal");

        final String pkgName = pkg.getPackageName();
        final int[] installedForUsers = installRequest.getOriginUsers();
        final int installReason = installRequest.getInstallReason();
        final String installerPackageName = installRequest.getInstallerPackageName();

        if (DEBUG_INSTALL) Slog.d(TAG, "New package installed in " + pkg.getPath());
        final int userId = installRequest.getUserId();
        if (userId != UserHandle.USER_ALL && userId != UserHandle.USER_CURRENT
                && !mPm.mUserManager.exists(userId)) {
            installRequest.setError(PackageManagerException.ofInternalError(
                    "User " + userId + " doesn't exist or has been removed",
                    PackageManagerException.INTERNAL_ERROR_MISSING_USER));
            return;
        }
        synchronized (mPm.mLock) {
            // For system-bundled packages, we assume that installing an upgraded version
            // of the package implies that the user actually wants to run that new code,
            // so we enable the package.
            final PackageSetting ps = mPm.mSettings.getPackageLPr(pkgName);
            if (ps != null) {
                if (ps.isSystem()) {
                    if (DEBUG_INSTALL) {
                        Slog.d(TAG, "Implicitly enabling system package on upgrade: " + pkgName);
                    }
                    // Enable system package for requested users
                    if (installedForUsers != null
                            && !installRequest.isApplicationEnabledSettingPersistent()) {
                        for (int origUserId : installedForUsers) {
                            if (userId == UserHandle.USER_ALL || userId == origUserId) {
                                ps.setEnabled(COMPONENT_ENABLED_STATE_DEFAULT,
                                        origUserId, installerPackageName);
                            }
                        }
                    }
                    // Also convey the prior install/uninstall state
                    if (allUsers != null && installedForUsers != null) {
                        for (int currentUserId : allUsers) {
                            final boolean installed = ArrayUtils.contains(
                                    installedForUsers, currentUserId);
                            if (DEBUG_INSTALL) {
                                Slog.d(TAG, "    user " + currentUserId + " => " + installed);
                            }
                            ps.setInstalled(installed, currentUserId);
                        }
                        // these install state changes will be persisted in the
                        // upcoming call to mSettings.writeLPr().
                    }

                    if (allUsers != null) {
                        for (int currentUserId : allUsers) {
                            ps.resetOverrideComponentLabelIcon(currentUserId);
                        }
                    }
                }

                // Retrieve the overlays for shared libraries of the package.
                if (!ps.getPkgState().getUsesLibraryInfos().isEmpty()) {
                    for (SharedLibraryWrapper sharedLib : ps.getPkgState().getUsesLibraryInfos()) {
                        for (int currentUserId : allUsers) {
                            if (sharedLib.getType() != SharedLibraryInfo.TYPE_DYNAMIC) {
                                // TODO(146804378): Support overlaying static shared libraries
                                continue;
                            }
                            final PackageSetting libPs = mPm.mSettings.getPackageLPr(
                                    sharedLib.getPackageName());
                            if (libPs == null) {
                                continue;
                            }
                            ps.setOverlayPathsForLibrary(sharedLib.getName(),
                                    libPs.getOverlayPaths(currentUserId), currentUserId);
                        }
                    }
                }

                if (userId != UserHandle.USER_ALL) {
                    // It's implied that when a user requests installation, they want the app to
                    // be installed and enabled. The caller, however, can explicitly specify to
                    // keep the existing enabled state.
                    ps.setInstalled(true, userId);
                    if (!installRequest.isApplicationEnabledSettingPersistent()) {
                        ps.setEnabled(COMPONENT_ENABLED_STATE_DEFAULT, userId,
                                installerPackageName);
                    }
                    // Clear any existing archive state.
                    mPm.mInstallerService.mPackageArchiver.clearArchiveState(ps, userId);
                } else if (allUsers != null) {
                    // The caller explicitly specified INSTALL_ALL_USERS flag.
                    // Thus, updating the settings to install the app for all users.
                    for (int currentUserId : allUsers) {
                        // If the app is already installed for the currentUser,
                        // keep it as installed as we might be updating the app at this place.
                        // If not currently installed, check if the currentUser is restricted by
                        // DISALLOW_INSTALL_APPS or DISALLOW_DEBUGGING_FEATURES device policy.
                        // Install / update the app if the user isn't restricted. Skip otherwise.
                        final boolean installedForCurrentUser = ArrayUtils.contains(
                                installedForUsers, currentUserId);
                        final boolean restrictedByPolicy =
                                mPm.isUserRestricted(currentUserId,
                                        UserManager.DISALLOW_INSTALL_APPS)
                                || mPm.isUserRestricted(currentUserId,
                                        UserManager.DISALLOW_DEBUGGING_FEATURES);
                        if (installedForCurrentUser || !restrictedByPolicy) {
                            ps.setInstalled(true, currentUserId);
                            if (!installRequest.isApplicationEnabledSettingPersistent()) {
                                ps.setEnabled(COMPONENT_ENABLED_STATE_DEFAULT, currentUserId,
                                        installerPackageName);
                            }
                            // Clear any existing archive state.
                            mPm.mInstallerService.mPackageArchiver.clearArchiveState(ps,
                                    currentUserId);
                        } else {
                            ps.setInstalled(false, currentUserId);
                        }
                    }
                }

                mPm.mSettings.addInstallerPackageNames(ps.getInstallSource());

                // When replacing an existing package, preserve the original install reason for all
                // users that had the package installed before. Similarly for uninstall reasons.
                final Set<Integer> previousUserIds = new ArraySet<>();
                if (installRequest.getRemovedInfo() != null
                        && installRequest.getRemovedInfo().mInstallReasons != null) {
                    final int installReasonCount =
                            installRequest.getRemovedInfo().mInstallReasons.size();
                    for (int i = 0; i < installReasonCount; i++) {
                        final int previousUserId =
                                installRequest.getRemovedInfo().mInstallReasons.keyAt(i);
                        final int previousInstallReason =
                                installRequest.getRemovedInfo().mInstallReasons.valueAt(i);
                        ps.setInstallReason(previousInstallReason, previousUserId);
                        previousUserIds.add(previousUserId);
                    }
                }
                if (installRequest.getRemovedInfo() != null
                        && installRequest.getRemovedInfo().mUninstallReasons != null) {
                    for (int i = 0; i < installRequest.getRemovedInfo().mUninstallReasons.size();
                            i++) {
                        final int previousUserId =
                                installRequest.getRemovedInfo().mUninstallReasons.keyAt(i);
                        final int previousReason =
                                installRequest.getRemovedInfo().mUninstallReasons.valueAt(i);
                        ps.setUninstallReason(previousReason, previousUserId);
                    }
                }

                // Set install reason for users that are having the package newly installed.
                if (userId == UserHandle.USER_ALL) {
                    for (int currentUserId : allUsers) {
                        if (!previousUserIds.contains(currentUserId)
                                && ps.getInstalled(currentUserId)) {
                            ps.setInstallReason(installReason, currentUserId);
                        }
                    }
                } else if (!previousUserIds.contains(userId)) {
                    ps.setInstallReason(installReason, userId);
                }

                // TODO(b/169721400): generalize Incremental States and create a Callback object
                // that can be used for all the packages.
                final String codePath = ps.getPathString();
                if (IncrementalManager.isIncrementalPath(codePath)
                        && mIncrementalManager != null) {
                    mIncrementalManager.registerLoadingProgressCallback(codePath,
                            new IncrementalProgressListener(ps.getPackageName(), mPm));
                }

                // Ensure that the uninstall reason is UNKNOWN for users with the package installed.
                for (int currentUserId : allUsers) {
                    if (ps.getInstalled(currentUserId)) {
                        ps.setUninstallReason(UNINSTALL_REASON_UNKNOWN, currentUserId);
                    }
                }

                mPm.mSettings.writeKernelMappingLPr(ps);

                final PermissionManagerServiceInternal.PackageInstalledParams.Builder
                        permissionParamsBuilder =
                        new PermissionManagerServiceInternal.PackageInstalledParams.Builder();
                final boolean grantRequestedPermissions = (installRequest.getInstallFlags()
                        & PackageManager.INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS) != 0;
                if (grantRequestedPermissions) {
                    var permissionStates = new ArrayMap<String, Integer>();
                    for (var permissionName : pkg.getRequestedPermissions()) {
                        permissionStates.put(permissionName,
                                PackageInstaller.SessionParams.PERMISSION_STATE_GRANTED);
                    }
                    permissionParamsBuilder.setPermissionStates(permissionStates);
                } else {
                    var permissionStates = installRequest.getPermissionStates();
                    if (permissionStates != null) {
                        permissionParamsBuilder
                                .setPermissionStates(permissionStates);
                    }
                }
                final boolean allowlistAllRestrictedPermissions =
                        (installRequest.getInstallFlags()
                                & PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS) != 0;
                final List<String> allowlistedRestrictedPermissions =
                        allowlistAllRestrictedPermissions
                                ? new ArrayList<>(pkg.getRequestedPermissions())
                                : installRequest.getAllowlistedRestrictedPermissions();
                if (allowlistedRestrictedPermissions != null) {
                    permissionParamsBuilder.setAllowlistedRestrictedPermissions(
                            allowlistedRestrictedPermissions);
                }
                final int autoRevokePermissionsMode = installRequest.getAutoRevokePermissionsMode();
                permissionParamsBuilder.setAutoRevokePermissionsMode(autoRevokePermissionsMode);
                mPm.mPermissionManager.onPackageInstalled(pkg, installRequest.getPreviousAppId(),
                        permissionParamsBuilder.build(), userId);
            }
            installRequest.setName(pkgName);
            installRequest.setAppId(pkg.getUid());
            installRequest.setPkg(pkg);
            installRequest.setReturnCode(PackageManager.INSTALL_SUCCEEDED);
            //to update install status
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "writeSettings");
            mPm.writeSettingsLPrTEMP();
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }

        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
    }

    private void setAccessRestrictedSettingsMode(String pkgName, int appId, int userId, int mode) {
        final AppOpsManager appOpsManager = mPm.mContext.getSystemService(AppOpsManager.class);
        final int uid = UserHandle.getUid(userId, appId);
        appOpsManager.setMode(AppOpsManager.OP_ACCESS_RESTRICTED_SETTINGS,
                uid,
                pkgName,
                mode);
    }

    /**
     * On successful install, executes remaining steps after commit completes and the package lock
     * is released. These are typically more expensive or require calls to installd, which often
     * locks on {@link com.android.server.pm.PackageManagerService.mLock}.
     */
    @GuardedBy("mPm.mInstallLock")
    private void executePostCommitStepsLIF(List<ReconciledPackage> reconciledPackages) {
        final ArraySet<IncrementalStorage> incrementalStorages = new ArraySet<>();
        for (ReconciledPackage reconciledPkg : reconciledPackages) {
            final InstallRequest installRequest = reconciledPkg.mInstallRequest;
            final PackageSetting ps = installRequest.getScannedPackageSetting();
            final String packageName = ps.getPackageName();
            final String codePath = ps.getPathString();
            final AndroidPackage pkg = ps.getPkg();
            final boolean onIncremental = mIncrementalManager != null
                    && isIncrementalPath(codePath);
            if (onIncremental) {
                IncrementalStorage storage = mIncrementalManager.openStorage(codePath);
                if (storage == null) {
                    throw new IllegalArgumentException(
                            "Install: null storage for incremental package " + packageName);
                }
                incrementalStorages.add(storage);
            }

            if (installRequest.isInstallReplace() && pkg != null) {
                mDexManager.notifyPackageUpdated(packageName,
                        pkg.getBaseApkPath(), pkg.getSplitCodePaths());
            }
            if (!Flags.improveInstallFreeze()) {
                // Hardcode previousAppId to 0 to disable any data migration (http://b/221088088)
                mAppDataHelper.prepareAppDataPostCommitLIF(ps, 0, installRequest.getNewUsers());
                if (installRequest.isClearCodeCache()) {
                    mAppDataHelper.clearAppDataLIF(ps.getPkg(), UserHandle.USER_ALL,
                            FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL
                                    | Installer.FLAG_CLEAR_CODE_CACHE_ONLY);
                }

                // run synchronous dexopt if the freeze improvement is not supported
                DexOptHelper.performDexoptIfNeeded(
                        installRequest, mDexManager, mPm.mInstallLock.getRawLock());
            }
        }
        PackageManagerServiceUtils.waitForNativeBinariesExtractionForIncremental(
                incrementalStorages);
    }

    Pair<Integer, String> verifyReplacingVersionCode(PackageInfoLite pkgLite,
            long requiredInstalledVersionCode, int installFlags) {
        if ((installFlags & PackageManager.INSTALL_APEX) != 0) {
            return verifyReplacingVersionCodeForApex(
                    pkgLite, requiredInstalledVersionCode, installFlags);
        }

        String packageName = pkgLite.packageName;
        synchronized (mPm.mLock) {
            PackageSetting dataOwnerPs = mPm.mSettings.getPackageLPr(packageName);
            if (dataOwnerPs == null) {
                if (requiredInstalledVersionCode != PackageManager.VERSION_CODE_HIGHEST) {
                    String errorMsg = "Required installed version code was "
                            + requiredInstalledVersionCode
                            + " but package is not installed";
                    Slog.w(TAG, errorMsg);
                    return Pair.create(
                            PackageManager.INSTALL_FAILED_WRONG_INSTALLED_VERSION, errorMsg);
                }
                // The package doesn't exist in the system, don't need to check the version
                // replacing.
                return Pair.create(PackageManager.INSTALL_SUCCEEDED, null);
            }

            // Package which currently owns the data that the new package will own if installed.
            // If an app is uninstalled while keeping data (e.g. adb uninstall -k), dataOwnerPkg
            // will be null whereas dataOwnerPs will contain information about the package
            // which was uninstalled while keeping its data. The AndroidPackage object that the
            // PackageSetting refers to is the same object that is stored in mPackages.
            AndroidPackage dataOwnerPkg = dataOwnerPs.getPkg();

            if (requiredInstalledVersionCode != PackageManager.VERSION_CODE_HIGHEST) {
                if (dataOwnerPkg == null) {
                    String errorMsg = "Required installed version code was "
                            + requiredInstalledVersionCode
                            + " but package is not installed";
                    Slog.w(TAG, errorMsg);
                    return Pair.create(
                            PackageManager.INSTALL_FAILED_WRONG_INSTALLED_VERSION, errorMsg);
                }

                if (dataOwnerPkg.getLongVersionCode() != requiredInstalledVersionCode) {
                    String errorMsg = "Required installed version code was "
                            + requiredInstalledVersionCode
                            + " but actual installed version is "
                            + dataOwnerPkg.getLongVersionCode();
                    Slog.w(TAG, errorMsg);
                    return Pair.create(
                            PackageManager.INSTALL_FAILED_WRONG_INSTALLED_VERSION, errorMsg);
                }
            }

            // If dataOwnerPkg is null but dataOwnerPs is not null, there is always data on
            // some users. Wwe should do the downgrade check. E.g. DELETE_KEEP_DATA and
            // archived apps
            if (dataOwnerPkg == null) {
                if (!PackageManagerServiceUtils.isDowngradePermitted(installFlags,
                        dataOwnerPs.isDebuggable())) {
                    // The data exists on some users and downgrade is not permitted; a lower
                    // version of the app will not be allowed.
                    try {
                        PackageManagerServiceUtils.checkDowngrade(dataOwnerPs, pkgLite);
                    } catch (PackageManagerException e) {
                        String errorMsg = "Downgrade detected on app uninstalled with"
                                + " DELETE_KEEP_DATA: " + e.getMessage();
                        Slog.w(TAG, errorMsg);
                        return Pair.create(
                                PackageManager.INSTALL_FAILED_VERSION_DOWNGRADE, errorMsg);
                    }
                }
                // dataOwnerPs.getPkg() is not null on system apps case. Don't need to consider
                // system apps case like below.
            } else if (dataOwnerPkg != null && !dataOwnerPkg.isSdkLibrary()) {
                if (!PackageManagerServiceUtils.isDowngradePermitted(installFlags,
                        dataOwnerPkg.isDebuggable())) {
                    // Downgrade is not permitted; a lower version of the app will not be allowed
                    try {
                        PackageManagerServiceUtils.checkDowngrade(dataOwnerPkg, pkgLite);
                    } catch (PackageManagerException e) {
                        String errorMsg = "Downgrade detected: " + e.getMessage();
                        Slog.w(TAG, errorMsg);
                        return Pair.create(
                                PackageManager.INSTALL_FAILED_VERSION_DOWNGRADE, errorMsg);
                    }
                } else if (dataOwnerPs.isSystem()) {
                    // Downgrade is permitted, but system apps can't be downgraded below
                    // the version preloaded onto the system image
                    final PackageSetting disabledPs = mPm.mSettings.getDisabledSystemPkgLPr(
                            dataOwnerPs);
                    if (disabledPs != null) {
                        dataOwnerPkg = disabledPs.getPkg();
                    }
                    if (!Build.IS_DEBUGGABLE && !dataOwnerPkg.isDebuggable()) {
                        // Only restrict non-debuggable builds and non-debuggable version of the app
                        try {
                            PackageManagerServiceUtils.checkDowngrade(dataOwnerPkg, pkgLite);
                        } catch (PackageManagerException e) {
                            String errorMsg =
                                    "System app: " + packageName + " cannot be downgraded to"
                                            + " older than its preloaded version on the system"
                                            + " image. " + e.getMessage();
                            Slog.w(TAG, errorMsg);
                            return Pair.create(
                                    PackageManager.INSTALL_FAILED_VERSION_DOWNGRADE, errorMsg);
                        }
                    }
                }
            }
        }
        return Pair.create(PackageManager.INSTALL_SUCCEEDED, null);
    }

    private Pair<Integer, String> verifyReplacingVersionCodeForApex(PackageInfoLite pkgLite,
            long requiredInstalledVersionCode, int installFlags) {
        String packageName = pkgLite.packageName;

        final PackageInfo activePackage = mPm.snapshotComputer().getPackageInfo(
                packageName, PackageManager.MATCH_APEX, UserHandle.USER_SYSTEM);
        if (activePackage == null) {
            String errorMsg = "Attempting to install new APEX package " + packageName;
            Slog.w(TAG, errorMsg);
            return Pair.create(PackageManager.INSTALL_FAILED_PACKAGE_CHANGED, errorMsg);
        }

        final long activeVersion = activePackage.getLongVersionCode();
        if (requiredInstalledVersionCode != PackageManager.VERSION_CODE_HIGHEST
                && activeVersion != requiredInstalledVersionCode) {
            String errorMsg = "Installed version of APEX package " + packageName
                    + " does not match required. Active version: " + activeVersion
                    + " required: " + requiredInstalledVersionCode;
            Slog.w(TAG, errorMsg);
            return Pair.create(PackageManager.INSTALL_FAILED_WRONG_INSTALLED_VERSION, errorMsg);
        }

        final boolean isAppDebuggable = (activePackage.applicationInfo.flags
                & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        final long newVersionCode = pkgLite.getLongVersionCode();
        if (!PackageManagerServiceUtils.isDowngradePermitted(installFlags, isAppDebuggable)
                && newVersionCode < activeVersion) {
            String errorMsg = "Downgrade of APEX package " + packageName
                    + " is not allowed. Active version: " + activeVersion
                    + " attempted: " + newVersionCode;
            Slog.w(TAG, errorMsg);
            return Pair.create(PackageManager.INSTALL_FAILED_VERSION_DOWNGRADE, errorMsg);
        }

        return Pair.create(PackageManager.INSTALL_SUCCEEDED, null);
    }

    int getUidForVerifier(VerifierInfo verifierInfo) {
        synchronized (mPm.mLock) {
            final AndroidPackage pkg = mPm.mPackages.get(verifierInfo.packageName);
            if (pkg == null) {
                return -1;
            } else if (pkg.getSigningDetails().getSignatures().length != 1) {
                Slog.i(TAG, "Verifier package " + verifierInfo.packageName
                        + " has more than one signature; ignoring");
                return -1;
            }

            /*
             * If the public key of the package's signature does not match
             * our expected public key, then this is a different package and
             * we should skip.
             */

            final byte[] expectedPublicKey;
            try {
                final Signature verifierSig = pkg.getSigningDetails().getSignatures()[0];
                final PublicKey publicKey = verifierSig.getPublicKey();
                expectedPublicKey = publicKey.getEncoded();
            } catch (CertificateException e) {
                return -1;
            }

            final byte[] actualPublicKey = verifierInfo.publicKey.getEncoded();

            if (!Arrays.equals(actualPublicKey, expectedPublicKey)) {
                Slog.i(TAG, "Verifier package " + verifierInfo.packageName
                        + " does not have the expected public key; ignoring");
                return -1;
            }

            return pkg.getUid();
        }
    }

    public void sendPendingBroadcasts(String reasonForTrace, int callingUidForTrace) {
        String[] packages;
        ArrayList<String>[] components;
        int numBroadcasts = 0, numUsers;
        int[] uids;

        synchronized (mPm.mLock) {
            final SparseArray<ArrayMap<String, ArrayList<String>>> userIdToPackagesToComponents =
                    mPm.mPendingBroadcasts.copiedMap();
            numUsers = userIdToPackagesToComponents.size();
            for (int n = 0; n < numUsers; n++) {
                numBroadcasts += userIdToPackagesToComponents.valueAt(n).size();
            }
            if (numBroadcasts == 0) {
                // Nothing to be done. Just return
                return;
            }
            packages = new String[numBroadcasts];
            components = new ArrayList[numBroadcasts];
            uids = new int[numBroadcasts];
            int i = 0;  // filling out the above arrays

            for (int n = 0; n < numUsers; n++) {
                final int packageUserId = userIdToPackagesToComponents.keyAt(n);
                final ArrayMap<String, ArrayList<String>> componentsToBroadcast =
                        userIdToPackagesToComponents.valueAt(n);
                final int numComponents = CollectionUtils.size(componentsToBroadcast);
                for (int index = 0; index < numComponents; index++) {
                    packages[i] = componentsToBroadcast.keyAt(index);
                    components[i] = componentsToBroadcast.valueAt(index);
                    final PackageSetting ps = mPm.mSettings.getPackageLPr(packages[i]);
                    uids[i] = (ps != null)
                            ? UserHandle.getUid(packageUserId, ps.getAppId())
                            : -1;
                    i++;
                }
            }
            numBroadcasts = i;
            mPm.mPendingBroadcasts.clear();
        }
        final Computer snapshot = mPm.snapshotComputer();
        // Send broadcasts
        for (int i = 0; i < numBroadcasts; i++) {
            mBroadcastHelper.sendPackageChangedBroadcast(snapshot, packages[i],
                    true /* dontKillApp */, components[i], uids[i], null /* reason */,
                    reasonForTrace, callingUidForTrace);
        }
    }

    void handlePackagePostInstall(InstallRequest request, boolean launchedForRestore) {
        final boolean killApp =
                (request.getInstallFlags() & PackageManager.INSTALL_DONT_KILL_APP) == 0;
        final boolean succeeded = request.getReturnCode() == PackageManager.INSTALL_SUCCEEDED;
        final boolean update = request.isUpdate();
        final boolean archived = request.isArchived();
        final String packageName = request.getName();
        final Computer snapshot = mPm.snapshotComputer();
        final PackageStateInternal pkgSetting =
                succeeded ? snapshot.getPackageStateInternal(packageName) : null;
        final boolean removedBeforeUpdate = (pkgSetting == null)
                || (pkgSetting.isSystem() && !pkgSetting.getPath().getPath().equals(
                request.getPkg().getPath()));
        if (succeeded && removedBeforeUpdate) {
            Slog.e(TAG, packageName + " was removed before handlePackagePostInstall "
                    + "could be executed");
            request.setReturnCode(INSTALL_FAILED_PACKAGE_CHANGED);
            request.setReturnMessage("Package was removed before install could complete.");

            // Remove the update failed package's older resources safely now
            mRemovePackageHelper.cleanUpResources(packageName, request.getOldCodeFile(),
                    request.getOldInstructionSet());
            mPm.notifyInstallObserver(request);
            return;
        }

        if (succeeded) {
            Slog.i(TAG, "installation completed for package:" + packageName
                    + ". Final code path: " + pkgSetting.getPath().getPath());

            if (Flags.aslInApkAppMetadataSource()
                    && pkgSetting.getAppMetadataSource() == APP_METADATA_SOURCE_APK) {
                if (!extractAppMetadataFromApk(request.getPkg(),
                        pkgSetting.getAppMetadataFilePath(), pkgSetting.isSystem())) {
                    synchronized (mPm.mLock) {
                        PackageSetting setting = mPm.mSettings.getPackageLPr(packageName);
                        if (setting != null) {
                            setting.setAppMetadataFilePath(null)
                                    .setAppMetadataSource(APP_METADATA_SOURCE_UNKNOWN);
                        }
                    }
                }
            }

            // Clear the uid cache after we installed a new package.
            mPm.mPerUidReadTimeoutsCache = null;

            mPm.notifyInstantAppPackageInstalled(request.getPkg().getPackageName(),
                    request.getNewUsers());

            final int[] firstUserIds = request.getFirstTimeBroadcastUserIds();

            if (request.getPkg().getStaticSharedLibraryName() == null) {
                mPm.mProcessLoggingHandler.invalidateBaseApkHash(request.getPkg().getBaseApkPath());
            }

            mBroadcastHelper.sendPostInstallBroadcasts(mPm::snapshotComputer, request, packageName,
                    mPm.mRequiredPermissionControllerPackage, mPm.mRequiredVerifierPackages,
                    mPm.mRequiredInstallerPackage,
                    /* packageSender= */ mPm, launchedForRestore, killApp, update, archived);

            if (request.isAllNewUsers() && !update) {
                mPm.notifyPackageAdded(packageName, request.getAppId());
            } else {
                mPm.notifyPackageChanged(packageName, request.getAppId());
            }

            // Set the OP_ACCESS_RESTRICTED_SETTINGS op, which is used by ECM (see {@link
            // EnhancedConfirmationManager}) as a persistent state denoting whether an app is
            // currently guarded by ECM, not guarded by ECM or (in Android V+) that this should
            // be decided later. In Android B, the op's default mode was updated to the
            // "should be decided later" case, and so this step is now unnecessary.
            if (!android.permission.flags.Flags.enhancedConfirmationModeApisEnabled()
                    || !android.security.Flags.extendEcmToAllSettings()) {
                // Apply restricted settings on potentially dangerous packages. Needs to happen
                // after appOpsManager is notified of the new package
                if (request.getPackageSource() == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
                        || request.getPackageSource()
                        == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE) {
                    final int appId = request.getAppId();
                    mPm.mHandler.post(() -> {
                        for (int userId : firstUserIds) {
                            // MODE_ERRORED means that the app is explicitly guarded
                            setAccessRestrictedSettingsMode(packageName, appId, userId,
                                    AppOpsManager.MODE_ERRORED);
                        }
                    });
                }
            }

            // Log current value of "unknown sources" setting
            EventLog.writeEvent(EventLogTags.UNKNOWN_SOURCES_ENABLED,
                    getUnknownSourcesSettings());

            // Remove the replaced package's older resources safely now
            CleanUpArgs args = request.getRemovedInfo() != null
                    ? request.getRemovedInfo().mArgs : null;
            if (args != null) {
                if (!killApp) {
                    // If we didn't kill the app, defer the deletion of code/resource files,
                    // since the old code/resource files may still be in use by the running
                    // application. This mitigates problems and cases where resources or
                    // code is loaded by a new Activity before ApplicationInfo changes have
                    // propagated to all application threads.
                    mPm.scheduleDeferredNoKillPostDelete(args);
                    if (Flags.improveInstallDontKill()) {
                        try (var installLock = mPm.mInstallLock.acquireLock()) {
                            PackageManagerServiceUtils.linkFilesToOldDirs(mPm.mInstaller,
                                    packageName, pkgSetting.getPath(), pkgSetting.getOldPaths());
                        }
                    }
                } else {
                    mRemovePackageHelper.cleanUpResources(packageName, args.getCodeFile(),
                            args.getInstructionSets());
                }
            } else {
                // Force a gc to clear up things. Ask for a background one, it's fine to go on
                // and not block here.
                VMRuntime.getRuntime().requestConcurrentGC();
            }

            if (!archived) {
                // Notify DexManager that the package was installed for new users.
                // The updated users should already be indexed and the package code paths
                // should not change.
                // Don't notify the manager for ephemeral apps as they are not expected to
                // survive long enough to benefit of background optimizations.
                for (int userId : firstUserIds) {
                    PackageInfo info = snapshot.getPackageInfo(packageName, /*flags*/ 0, userId);
                    // There's a race currently where some install events may interleave with an
                    // uninstall. This can lead to package info being null (b/36642664).
                    if (info != null) {
                        mDexManager.notifyPackageInstalled(info, userId);
                    }
                }
            } else {
                // Now send PACKAGE_REMOVED + EXTRA_REPLACING broadcast.
                final PackageRemovedInfo info = new PackageRemovedInfo();
                info.mRemovedPackage = packageName;
                info.mInstallerPackageName = request.getInstallerPackageName();
                info.mRemovedUsers = firstUserIds;
                info.mBroadcastUsers = firstUserIds;
                info.mUid = request.getAppId();
                info.mRemovedPackageVersionCode = request.getPkg().getLongVersionCode();
                info.mRemovedForAllUsers = true;

                mBroadcastHelper.sendPackageRemovedBroadcasts(info, mPm,
                        false /*killApp*/, false /*removedBySystem*/, true /*isArchived*/);
            }
        }

        final boolean deferInstallObserver = succeeded && update;
        if (deferInstallObserver) {
            if (killApp) {
                mPm.scheduleDeferredPendingKillInstallObserver(request);
            } else {
                mPm.scheduleDeferredNoKillInstallObserver(request);
            }
        } else {
            mPm.notifyInstallObserver(request);
        }

        // Prune unused static shared libraries which have been cached a period of time
        mPm.schedulePruneUnusedStaticSharedLibraries(true /* delay */);

        // Log tracing if needed
        if (request.getTraceMethod() != null) {
            Trace.asyncTraceEnd(TRACE_TAG_PACKAGE_MANAGER, request.getTraceMethod(),
                    request.getTraceCookie());
        }
    }

    /**
     * Get the "allow unknown sources" setting.
     *
     * @return the current "allow unknown sources" setting
     */
    private int getUnknownSourcesSettings() {
        return android.provider.Settings.Secure.getIntForUser(mContext.getContentResolver(),
                android.provider.Settings.Secure.INSTALL_NON_MARKET_APPS,
                -1, UserHandle.USER_SYSTEM);
    }

    /**
     * Uncompress and install stub applications.
     * <p>In order to save space on the system partition, some applications are shipped in a
     * compressed form. In addition the compressed bits for the full application, the
     * system image contains a tiny stub comprised of only the Android manifest.
     * <p>During the first boot, attempt to uncompress and install the full application. If
     * the application can't be installed for any reason, disable the stub and prevent
     * uncompressing the full application during future boots.
     * <p>In order to forcefully attempt an installation of a full application, go to app
     * settings and enable the application.
     */
    @GuardedBy({"mPm.mLock", "mPm.mInstallLock"})
    void installSystemStubPackages(@NonNull List<String> systemStubPackageNames,
            @PackageManagerService.ScanFlags int scanFlags) {
        for (int i = systemStubPackageNames.size() - 1; i >= 0; --i) {
            final String packageName = systemStubPackageNames.get(i);
            // skip if the system package is already disabled
            if (mPm.mSettings.isDisabledSystemPackageLPr(packageName)) {
                systemStubPackageNames.remove(i);
                continue;
            }
            // skip if the package isn't installed (?!); this should never happen
            final AndroidPackage pkg = mPm.mPackages.get(packageName);
            if (pkg == null) {
                systemStubPackageNames.remove(i);
                continue;
            }
            // skip if the package has been disabled by the user
            final PackageSetting ps = mPm.mSettings.getPackageLPr(packageName);
            if (ps != null) {
                final int enabledState = ps.getEnabled(UserHandle.USER_SYSTEM);
                if (enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                    systemStubPackageNames.remove(i);
                    continue;
                }
            }

            // install the package to replace the stub on /system
            try {
                installStubPackageLI(pkg, 0, scanFlags);
                ps.setEnabled(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                        UserHandle.USER_SYSTEM, "android");
                systemStubPackageNames.remove(i);
            } catch (PackageManagerException e) {
                Slog.e(TAG, "Failed to parse uncompressed system package: " + e.getMessage());
            }

            // any failed attempt to install the package will be cleaned up later
        }

        // disable any stub still left; these failed to install the full application
        for (int i = systemStubPackageNames.size() - 1; i >= 0; --i) {
            final String pkgName = systemStubPackageNames.get(i);
            final PackageSetting ps = mPm.mSettings.getPackageLPr(pkgName);
            ps.setEnabled(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    UserHandle.USER_SYSTEM, "android");
            logCriticalInfo(Log.ERROR, "Stub disabled; pkg: " + pkgName);
        }
    }

    /**
     * Extract, install and enable a stub package.
     * <p>If the compressed file can not be extracted / installed for any reason, the stub
     * APK will be installed and the package will be disabled. To recover from this situation,
     * the user will need to go into system settings and re-enable the package.
     */
    boolean enableCompressedPackage(AndroidPackage stubPkg,
            @NonNull PackageSetting stubPkgSetting) {
        final int parseFlags = mPm.getDefParseFlags() | ParsingPackageUtils.PARSE_CHATTY
                | ParsingPackageUtils.PARSE_ENFORCE_CODE;
        try (PackageManagerTracedLock installLock = mPm.mInstallLock.acquireLock()) {
            final AndroidPackage pkg;
            try (PackageFreezer freezer =
                         mPm.freezePackage(stubPkg.getPackageName(), UserHandle.USER_ALL,
                                 "setEnabledSetting",
                                 ApplicationExitInfo.REASON_PACKAGE_UPDATED, null /* request */)) {
                pkg = installStubPackageLI(stubPkg, parseFlags, 0 /*scanFlags*/);
                mAppDataHelper.prepareAppDataAfterInstallLIF(pkg);
                synchronized (mPm.mLock) {
                    try {
                        mSharedLibraries.updateSharedLibraries(
                                pkg, stubPkgSetting, null, null,
                                Collections.unmodifiableMap(mPm.mPackages));
                    } catch (PackageManagerException e) {
                        Slog.w(TAG, "updateAllSharedLibrariesLPw failed: ", e);
                    }
                    mPm.mPermissionManager.onPackageInstalled(pkg,
                            Process.INVALID_UID /* previousAppId */,
                            PermissionManagerServiceInternal.PackageInstalledParams.DEFAULT,
                            UserHandle.USER_ALL);
                    mPm.writeSettingsLPrTEMP();
                    // Since compressed package can be system app only, we do not need to
                    // set restricted settings on it.
                }
            } catch (PackageManagerException e) {
                // Whoops! Something went very wrong; roll back to the stub and disable the package
                try (PackageFreezer freezer =
                             mPm.freezePackage(stubPkg.getPackageName(), UserHandle.USER_ALL,
                                     "setEnabledSetting",
                                     ApplicationExitInfo.REASON_PACKAGE_UPDATED,
                                     null /* request */)) {
                    synchronized (mPm.mLock) {
                        // NOTE: Ensure the system package is enabled; even for a compressed stub.
                        // If we don't, installing the system package fails during scan
                        mPm.mSettings.enableSystemPackageLPw(stubPkg.getPackageName());
                    }
                    installPackageFromSystemLIF(stubPkg.getPath(),
                            mPm.mUserManager.getUserIds() /*allUserHandles*/,
                            null /*origUserHandles*/,
                            true /*writeSettings*/);
                } catch (PackageManagerException pme) {
                    // Serious WTF; we have to be able to install the stub
                    Slog.wtf(TAG, "Failed to restore system package:" + stubPkg.getPackageName(),
                            pme);
                } finally {
                    // Disable the package; the stub by itself is not runnable
                    synchronized (mPm.mLock) {
                        final PackageSetting stubPs = mPm.mSettings.getPackageLPr(
                                stubPkg.getPackageName());
                        if (stubPs != null) {
                            stubPs.setEnabled(COMPONENT_ENABLED_STATE_DISABLED,
                                    UserHandle.USER_SYSTEM, "android");
                        }
                        mPm.writeSettingsLPrTEMP();
                    }
                }
                return false;
            }
            mAppDataHelper.clearAppDataLIF(pkg, UserHandle.USER_ALL,
                    FLAG_STORAGE_DE | FLAG_STORAGE_CE | FLAG_STORAGE_EXTERNAL
                            | Installer.FLAG_CLEAR_CODE_CACHE_ONLY);
            mDexManager.notifyPackageUpdated(pkg.getPackageName(),
                    pkg.getBaseApkPath(), pkg.getSplitCodePaths());
        }
        return true;
    }

    @GuardedBy("mPm.mInstallLock")
    private AndroidPackage installStubPackageLI(AndroidPackage stubPkg,
            @ParsingPackageUtils.ParseFlags int parseFlags,
            @PackageManagerService.ScanFlags int scanFlags)
            throws PackageManagerException {
        if (DEBUG_COMPRESSION) {
            Slog.i(TAG, "Uncompressing system stub; pkg: " + stubPkg.getPackageName());
        }
        // uncompress the binary to its eventual destination on /data
        final File scanFile = decompressPackage(stubPkg.getPackageName(), stubPkg.getPath());
        if (scanFile == null) {
            throw PackageManagerException.ofInternalError(
                    "Unable to decompress stub at " + stubPkg.getPath(),
                    PackageManagerException.INTERNAL_ERROR_DECOMPRESS_STUB);
        }
        synchronized (mPm.mLock) {
            mPm.mSettings.disableSystemPackageLPw(stubPkg.getPackageName(), true /*replaced*/);
        }
        mRemovePackageHelper.removePackage(stubPkg, true /*chatty*/);
        try {
            return initPackageTracedLI(scanFile, parseFlags, scanFlags);
        } catch (PackageManagerException e) {
            Slog.w(TAG, "Failed to install compressed system package:" + stubPkg.getPackageName(),
                    e);
            // Remove the failed install
            mRemovePackageHelper.removeCodePath(scanFile);
            throw e;
        }
    }

    /**
     * Decompresses the given package on the system image onto
     * the /data partition.
     * @return The directory the package was decompressed into. Otherwise, {@code null}.
     */
    @GuardedBy("mPm.mInstallLock")
    private File decompressPackage(String packageName, String codePath) {
        if (!compressedFileExists(codePath)) {
            if (DEBUG_COMPRESSION) {
                Slog.i(TAG, "No files to decompress at: " + codePath);
            }
            return null;
        }
        final File dstCodePath =
                PackageManagerServiceUtils.getNextCodePath(Environment.getDataAppDirectory(null),
                        packageName);
        int ret = PackageManagerServiceUtils.decompressFiles(codePath, dstCodePath, packageName);
        if (ret == PackageManager.INSTALL_SUCCEEDED) {
            ret = PackageManagerServiceUtils.extractNativeBinaries(dstCodePath, packageName);
        }
        if (ret == PackageManager.INSTALL_SUCCEEDED) {
            // NOTE: During boot, we have to delay releasing cblocks for no other reason than
            // we cannot retrieve the setting {@link Secure#RELEASE_COMPRESS_BLOCKS_ON_INSTALL}.
            // When we no longer need to read that setting, cblock release can occur always
            // occur here directly
            if (!mPm.isSystemReady()) {
                if (mPm.mReleaseOnSystemReady == null) {
                    mPm.mReleaseOnSystemReady = new ArrayList<>();
                }
                mPm.mReleaseOnSystemReady.add(dstCodePath);
            } else {
                final ContentResolver resolver = mContext.getContentResolver();
                F2fsUtils.releaseCompressedBlocks(resolver, dstCodePath);
            }
        } else {
            if (!dstCodePath.exists()) {
                return null;
            }
            mRemovePackageHelper.removeCodePath(dstCodePath);
            return null;
        }

        return dstCodePath;
    }

    /**
     * Tries to restore the disabled system package after an update has been deleted.
     */
    @GuardedBy("mPm.mInstallLock")
    public void restoreDisabledSystemPackageLIF(DeletePackageAction action,
            @NonNull int[] allUserHandles, boolean writeSettings) throws SystemDeleteException {
        final PackageSetting deletedPs = action.mDeletingPs;
        final PackageRemovedInfo outInfo = action.mRemovedInfo;
        final PackageSetting disabledPs = action.mDisabledPs;

        synchronized (mPm.mLock) {
            // NOTE: The system package always needs to be enabled; even if it's for
            // a compressed stub. If we don't, installing the system package fails
            // during scan [scanning checks the disabled packages]. We will reverse
            // this later, after we've "installed" the stub.
            // Reinstate the old system package
            mPm.mSettings.enableSystemPackageLPw(disabledPs.getPkg().getPackageName());
            // Remove any native libraries from the upgraded package.
            PackageManagerServiceUtils.removeNativeBinariesLI(deletedPs);
        }
        // Install the system package
        if (DEBUG_REMOVE) Slog.d(TAG, "Re-installing system package: " + disabledPs);
        try {
            final int[] origUsers = outInfo == null ? null : outInfo.mOrigUsers;
            try (PackageManagerTracedLock installLock = mPm.mInstallLock.acquireLock()) {
                installPackageFromSystemLIF(disabledPs.getPathString(), allUserHandles,
                        origUsers, writeSettings);
            }
            if (origUsers != null) {
                mPm.commitPackageStateMutation(null, mutator -> {
                    for (int userId : origUsers) {
                        mutator.forPackage(disabledPs.getPackageName())
                                .userState(userId)
                                .setOverlayPaths(deletedPs.getOverlayPaths(userId));
                    }
                });
            }
        } catch (PackageManagerException e) {
            Slog.w(TAG, "Failed to restore system package:" + deletedPs.getPackageName() + ": "
                    + e.getMessage());
            // TODO(b/194319951): can we avoid this; throw would come from scan...
            throw new SystemDeleteException(e);
        } finally {
            if (disabledPs.getPkg().isStub()) {
                // We've re-installed the stub; make sure it's disabled here. If package was
                // originally enabled, we'll install the compressed version of the application
                // and re-enable it afterward.
                synchronized (mPm.mLock) {
                    disableStubPackage(action, deletedPs, allUserHandles);
                }
            }
        }
    }

    @GuardedBy("mPm.mLock")
    private void disableStubPackage(DeletePackageAction action, PackageSetting deletedPs,
            @NonNull int[] allUserHandles) {
        final PackageSetting stubPs = mPm.mSettings.getPackageLPr(
                deletedPs.getPackageName());
        if (stubPs != null) {
            int userId = action.mUser == null
                    ? UserHandle.USER_ALL : action.mUser.getIdentifier();
            if (userId == UserHandle.USER_ALL) {
                for (int aUserId : allUserHandles) {
                    stubPs.setEnabled(COMPONENT_ENABLED_STATE_DISABLED, aUserId, "android");
                }
            } else if (userId >= UserHandle.USER_SYSTEM) {
                stubPs.setEnabled(COMPONENT_ENABLED_STATE_DISABLED, userId, "android");
            }
        }
    }

    /**
     * Installs a package that's already on the system partition.
     */
    @GuardedBy("mPm.mInstallLock")
    private void installPackageFromSystemLIF(@NonNull String codePathString,
            @NonNull int[] allUserHandles, @Nullable int[] origUserHandles,
            boolean writeSettings)
            throws PackageManagerException {
        final File codePath = new File(codePathString);
        @ParsingPackageUtils.ParseFlags int parseFlags =
                mPm.getDefParseFlags()
                        | ParsingPackageUtils.PARSE_MUST_BE_APK
                        | ParsingPackageUtils.PARSE_IS_SYSTEM_DIR;
        @PackageManagerService.ScanFlags int scanFlags = mPm.getSystemPackageScanFlags(codePath);
        final AndroidPackage pkg = initPackageTracedLI(codePath, parseFlags, scanFlags);

        synchronized (mPm.mLock) {
            PackageSetting pkgSetting = mPm.mSettings.getPackageLPr(pkg.getPackageName());
            try {
                // update shared libraries for the newly re-installed system package
                mSharedLibraries.updateSharedLibraries(pkg, pkgSetting, null, null,
                        Collections.unmodifiableMap(mPm.mPackages));
            } catch (PackageManagerException e) {
                Slog.e(TAG, "updateAllSharedLibrariesLPw failed: " + e.getMessage());
            }
        }
        setPackageInstalledForSystemPackage(pkg, allUserHandles, origUserHandles, writeSettings);

        mAppDataHelper.prepareAppDataAfterInstallLIF(pkg);
    }

    private void setPackageInstalledForSystemPackage(@NonNull AndroidPackage pkg,
            @NonNull int[] allUserHandles, @Nullable int[] origUserHandles,
            boolean writeSettings) {
        // writer
        synchronized (mPm.mLock) {
            PackageSetting ps = mPm.mSettings.getPackageLPr(pkg.getPackageName());

            final boolean applyUserRestrictions = origUserHandles != null;
            if (applyUserRestrictions) {
                boolean installedStateChanged = false;
                if (DEBUG_REMOVE) {
                    Slog.d(TAG, "Propagating install state across reinstall");
                }
                for (int userId : allUserHandles) {
                    final boolean installed = ArrayUtils.contains(origUserHandles, userId);
                    if (DEBUG_REMOVE) {
                        Slog.d(TAG, "    user " + userId + " => " + installed);
                    }
                    if (installed != ps.getInstalled(userId)) {
                        installedStateChanged = true;
                    }
                    ps.setInstalled(installed, userId);
                    if (installed) {
                        ps.setUninstallReason(UNINSTALL_REASON_UNKNOWN, userId);
                    }
                }
                // Regardless of writeSettings we need to ensure that this restriction
                // state propagation is persisted
                mPm.mSettings.writeAllUsersPackageRestrictionsLPr();
                if (installedStateChanged) {
                    mPm.mSettings.writeKernelMappingLPr(ps);
                }
            }

            // The method below will take care of removing obsolete permissions and granting
            // install permissions.
            mPm.mPermissionManager.onPackageInstalled(pkg, Process.INVALID_UID,
                    PermissionManagerServiceInternal.PackageInstalledParams.DEFAULT,
                    UserHandle.USER_ALL);
            for (final int userId : allUserHandles) {
                if (applyUserRestrictions) {
                    mPm.mSettings.writePermissionStateForUserLPr(userId, false);
                }
            }

            // can downgrade to reader here
            if (writeSettings) {
                mPm.writeSettingsLPrTEMP();
            }
        }
    }

    @GuardedBy("mPm.mLock")
    public void prepareSystemPackageCleanUp(
            WatchedArrayMap<String, PackageSetting> packageSettings,
            List<String> possiblyDeletedUpdatedSystemApps,
            ArrayMap<String, File> expectingBetter, int[] userIds) {
        // Iterates PackageSettings in reversed order because the item could be removed
        // during the iteration.
        for (int index = packageSettings.size() - 1; index >= 0; index--) {
            final PackageSetting ps = packageSettings.valueAt(index);
            final String packageName = ps.getPackageName();
            /*
             * If this is not a system app, it can't be a
             * disable system app.
             */
            if (!ps.isSystem()) {
                continue;
            }

            /*
             * If the package is scanned, it's not erased.
             */
            final AndroidPackage scannedPkg = mPm.mPackages.get(packageName);
            final PackageSetting disabledPs =
                    mPm.mSettings.getDisabledSystemPkgLPr(packageName);
            if (scannedPkg != null) {
                if (scannedPkg.isApex()) {
                    // APEX on /data has been scanned. No need to expect better.
                    continue;
                }
                /*
                 * If the system app is both scanned and in the
                 * disabled packages list, then it must have been
                 * added via OTA. Remove it from the currently
                 * scanned package so the previously user-installed
                 * application can be scanned.
                 */
                if (disabledPs != null) {
                    logCriticalInfo(Log.WARN,
                            "Expecting better updated system app for "
                                    + packageName
                                    + "; removing system app.  Last known"
                                    + " codePath=" + ps.getPathString()
                                    + ", versionCode=" + ps.getVersionCode()
                                    + "; scanned versionCode="
                                    + scannedPkg.getLongVersionCode());
                    mRemovePackageHelper.removePackage(scannedPkg, true);
                    expectingBetter.put(ps.getPackageName(), ps.getPath());
                }

                continue;
            }

            if (disabledPs == null) {
                logCriticalInfo(Log.WARN, "System package " + packageName
                        + " no longer exists; its data will be wiped");
                mRemovePackageHelper.removePackageData(ps, userIds);
            } else {
                // we still have a disabled system package, but, it still might have
                // been removed. check the code path still exists and check there's
                // still a package. the latter can happen if an OTA keeps the same
                // code path, but, changes the package name.
                if (disabledPs.getPath() == null || !disabledPs.getPath().exists()
                        || disabledPs.getPkg() == null) {
                    possiblyDeletedUpdatedSystemApps.add(packageName);
                } else {
                    // We're expecting that the system app should remain disabled, but add
                    // it to expecting better to recover in case the data version cannot
                    // be scanned.
                    expectingBetter.put(disabledPs.getPackageName(), disabledPs.getPath());
                }
            }
        }
    }

    @GuardedBy("mPm.mLock")
    // Remove disable package settings for updated system apps that were
    // removed via an OTA. If the update is no longer present, remove the
    // app completely. Otherwise, revoke their system privileges.
    public void cleanupDisabledPackageSettings(List<String> possiblyDeletedUpdatedSystemApps,
            int[] userIds, int scanFlags) {
        for (int i = possiblyDeletedUpdatedSystemApps.size() - 1; i >= 0; --i) {
            final String packageName = possiblyDeletedUpdatedSystemApps.get(i);
            final AndroidPackage pkg = mPm.mPackages.get(packageName);
            final String msg;

            // remove from the disabled system list; do this first so any future
            // scans of this package are performed without this state
            mPm.mSettings.removeDisabledSystemPackageLPw(packageName);

            if (pkg == null) {
                // should have found an update, but, we didn't; remove everything
                msg = "Updated system package " + packageName
                        + " no longer exists; removing its data";
                // Actual deletion of code and data will be handled by later
                // reconciliation step
            } else {
                // found an update; revoke system privileges
                msg = "Updated system package " + packageName
                        + " no longer exists; rescanning package on data";

                // NOTE: We don't do anything special if a stub is removed from the
                // system image. But, if we were [like removing the uncompressed
                // version from the /data partition], this is where it'd be done.

                // remove the package from the system and re-scan it without any
                // special privileges
                mRemovePackageHelper.removePackage(pkg, true);
                PackageSetting ps = mPm.mSettings.getPackageLPr(packageName);
                if (ps != null) {
                    ps.getPkgState().setUpdatedSystemApp(false);
                }
                final File codePath = new File(pkg.getPath());
                try (PackageManagerTracedLock installLock = mPm.mInstallLock.acquireLock()) {
                    initPackageTracedLI(codePath, 0, scanFlags);
                } catch (PackageManagerException e) {
                    Slog.e(TAG, "Failed to parse updated, ex-system package: "
                            + e.getMessage());
                }
            }

            // one final check. if we still have a package setting [ie. it was
            // previously scanned and known to the system], but, we don't have
            // a package [ie. there was an error scanning it from the /data
            // partition], completely remove the package data.
            final PackageSetting ps = mPm.mSettings.getPackageLPr(packageName);
            if (ps != null && mPm.mPackages.get(packageName) == null) {
                mRemovePackageHelper.removePackageData(ps, userIds);
            }
            logCriticalInfo(Log.WARN, msg);
        }
    }

    /**
     * Scans APEX packages and registers them with the system.
     *
     * apexd has its own policy to decide which APEX to activate and which not. The policy might
     * conflicts that of PMS. The APEX package info stored in PMS is a mirror of that managed by
     * apexd. To keep things simple and keep activation status in sync for both apexd and PMS, we
     * don't persist APEX in settings and always scan APEX from scratch during boot. However, some
     * data like lastUpdateTime will be lost if PackageSetting is not persisted for APEX.
     *
     * TODO(b/225756739): Read lastUpdateTime from ApexInfoList to populate PackageSetting correctly
     */
    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    public List<ApexManager.ScanResult> scanApexPackages(ApexInfo[] allPackages, int parseFlags,
            int scanFlags, PackageParser2 packageParser, ExecutorService executorService) {
        if (allPackages == null) {
            return Collections.EMPTY_LIST;
        }

        ParallelPackageParser parallelPackageParser =
                new ParallelPackageParser(packageParser, executorService);

        // Submit files for parsing in parallel
        ArrayMap<File, ApexInfo> parsingApexInfo = new ArrayMap<>();
        for (ApexInfo ai : allPackages) {
            File apexFile = new File(ai.modulePath);
            parallelPackageParser.submit(apexFile, parseFlags);
            parsingApexInfo.put(apexFile, ai);
        }

        List<ParallelPackageParser.ParseResult> parseResults =
                new ArrayList<>(parsingApexInfo.size());
        for (int i = 0; i < parsingApexInfo.size(); i++) {
            ParallelPackageParser.ParseResult parseResult = parallelPackageParser.take();
            parseResults.add(parseResult);
        }
        // Sort the list to ensure we always process factory packages first
        Collections.sort(parseResults, (a, b) -> {
            ApexInfo i1 = parsingApexInfo.get(a.scanFile);
            ApexInfo i2 = parsingApexInfo.get(b.scanFile);
            return Boolean.compare(i2.isFactory, i1.isFactory);
        });


        // Process results one by one
        List<ApexManager.ScanResult> results = new ArrayList<>(parsingApexInfo.size());
        for (int i = 0; i < parseResults.size(); i++) {
            ParallelPackageParser.ParseResult parseResult = parseResults.get(i);
            Throwable throwable = parseResult.throwable;
            ApexInfo ai = parsingApexInfo.get(parseResult.scanFile);
            int newParseFlags = parseFlags;
            int newScanFlags = scanFlags | SCAN_AS_APEX
                    | mPm.getSystemPackageScanFlags(parseResult.scanFile);
            if (!ai.isFactory) {
                newParseFlags &= ~ParsingPackageUtils.PARSE_IS_SYSTEM_DIR;
                newScanFlags |= SCAN_NEW_INSTALL;
            }

            if (throwable == null) {
                try {
                    addForInitLI(parseResult.parsedPackage, newParseFlags, newScanFlags, null,
                            new ApexManager.ActiveApexInfo(ai));
                    AndroidPackage pkg = parseResult.parsedPackage.hideAsFinal();
                    if (ai.isFactory && !ai.isActive) {
                        disableSystemPackageLPw(pkg);
                    }
                    results.add(new ApexManager.ScanResult(ai, pkg, pkg.getPackageName()));
                } catch (PackageManagerException e) {
                    throw new IllegalStateException("Failed to scan: " + ai.modulePath, e);
                }
            } else if (throwable instanceof PackageManagerException) {
                throw new IllegalStateException("Unable to parse: " + ai.modulePath, throwable);
            } else {
                throw new IllegalStateException("Unexpected exception occurred while parsing "
                        + ai.modulePath, throwable);
            }
        }

        return results;
    }

    @GuardedBy({"mPm.mInstallLock", "mPm.mLock"})
    public void installPackagesFromDir(File scanDir, int parseFlags,
            int scanFlags, PackageParser2 packageParser, ExecutorService executorService,
            @Nullable ApexManager.ActiveApexInfo apexInfo) {
        final File[] files = scanDir.listFiles();
        if (ArrayUtils.isEmpty(files)) {
            Log.d(TAG, "No files in app dir " + scanDir);
            return;
        }

        if (DEBUG_PACKAGE_SCANNING) {
            Log.d(TAG, "Scanning app dir " + scanDir + " scanFlags=" + scanFlags
                    + " flags=0x" + Integer.toHexString(parseFlags));
        }
        ParallelPackageParser parallelPackageParser =
                new ParallelPackageParser(packageParser, executorService);

        // Submit files for parsing in parallel
        int fileCount = 0;
        for (File file : files) {
            final boolean isPackage = (isApkFile(file) || file.isDirectory())
                    && !PackageInstallerService.isStageName(file.getName());
            if (!isPackage) {
                // Ignore entries which are not packages
                continue;
            }
            if ((scanFlags & SCAN_DROP_CACHE) != 0) {
                final PackageCacher cacher = new PackageCacher(mPm.getCacheDir(),
                        mPm.mPackageParserCallback);
                Log.w(TAG, "Dropping cache of " + file.getAbsolutePath());
                cacher.cleanCachedResult(file);
            }
            parallelPackageParser.submit(file, parseFlags);
            fileCount++;
        }

        // Process results one by one
        for (; fileCount > 0; fileCount--) {
            ParallelPackageParser.ParseResult parseResult = parallelPackageParser.take();
            Throwable throwable = parseResult.throwable;
            int errorCode = PackageManager.INSTALL_SUCCEEDED;
            String errorMsg = null;

            if (throwable == null) {
                try {
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "addForInitLI");
                    addForInitLI(parseResult.parsedPackage, parseFlags, scanFlags,
                            new UserHandle(UserHandle.USER_SYSTEM), apexInfo);
                } catch (PackageManagerException e) {
                    errorCode = e.error;
                    errorMsg = "Failed to scan " + parseResult.scanFile + ": " + e.getMessage();
                    Slog.w(TAG, errorMsg);
                } finally {
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }
            } else if (throwable instanceof PackageManagerException) {
                PackageManagerException e = (PackageManagerException) throwable;
                errorCode = e.error;
                errorMsg = "Failed to parse " + parseResult.scanFile + ": " + e.getMessage();
                Slog.w(TAG, errorMsg);
            } else {
                throw new IllegalStateException("Unexpected exception occurred while parsing "
                        + parseResult.scanFile, throwable);
            }

            if ((scanFlags & SCAN_AS_APK_IN_APEX) != 0 && errorCode != INSTALL_SUCCEEDED) {
                mApexManager.reportErrorWithApkInApex(scanDir.getAbsolutePath(), errorMsg);
            }

            // Delete invalid userdata apps
            if ((scanFlags & SCAN_AS_SYSTEM) == 0
                    && errorCode != PackageManager.INSTALL_SUCCEEDED) {
                logCriticalInfo(Log.WARN,
                        "Deleting invalid package at " + parseResult.scanFile);
                mRemovePackageHelper.removeCodePath(parseResult.scanFile);
            }
        }
    }

    /**
     * Make sure all system apps that we expected to appear on
     * the userdata partition actually showed up. If they never
     * appeared, crawl back and revive the system version.
     */
    @GuardedBy("mPm.mLock")
    public void checkExistingBetterPackages(ArrayMap<String, File> expectingBetterPackages,
            List<String> stubSystemApps, int systemScanFlags, int systemParseFlags) {
        for (int i = 0; i < expectingBetterPackages.size(); i++) {
            final String packageName = expectingBetterPackages.keyAt(i);
            if (mPm.mPackages.containsKey(packageName)) {
                continue;
            }
            final File scanFile = expectingBetterPackages.valueAt(i);

            logCriticalInfo(Log.WARN, "Expected better " + packageName
                    + " but never showed up; reverting to system");

            final Pair<Integer, Integer> rescanAndReparseFlags =
                    mPm.getSystemPackageRescanFlagsAndReparseFlags(scanFile,
                            systemScanFlags, systemParseFlags);
            @PackageManagerService.ScanFlags int rescanFlags = rescanAndReparseFlags.first;
            @ParsingPackageUtils.ParseFlags int reparseFlags = rescanAndReparseFlags.second;

            if (rescanFlags == 0) {
                Slog.e(TAG, "Ignoring unexpected fallback path " + scanFile);
                continue;
            }
            mPm.mSettings.enableSystemPackageLPw(packageName);

            try (PackageManagerTracedLock installLock = mPm.mInstallLock.acquireLock()) {
                final AndroidPackage newPkg = initPackageTracedLI(
                         scanFile, reparseFlags, rescanFlags);
                 // We rescanned a stub, add it to the list of stubbed system packages
                if (newPkg.isStub()) {
                    stubSystemApps.add(packageName);
                }
            } catch (PackageManagerException e) {
                Slog.e(TAG, "Failed to parse original system package: "
                        + e.getMessage());
            }
        }
    }

    /**
     *  Traces a package scan and registers it with the system.
     *  @see #initPackageLI(File, int, int)
     */
    @GuardedBy("mPm.mInstallLock")
    public AndroidPackage initPackageTracedLI(File scanFile, final int parseFlags, int scanFlags)
            throws PackageManagerException {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "scanPackage [" + scanFile.toString() + "]");
        try {
            return initPackageLI(scanFile, parseFlags, scanFlags);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    /**
     *  Scans a package, registers it with the system and returns the newly parsed package.
     *  Returns {@code null} in case of errors and the error code is stored in mLastScanError
     */
    @GuardedBy("mPm.mInstallLock")
    private AndroidPackage initPackageLI(File scanFile, int parseFlags, int scanFlags)
            throws PackageManagerException {
        if (DEBUG_INSTALL) Slog.d(TAG, "Parsing: " + scanFile);

        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "parsePackage");
        final ParsedPackage parsedPackage;
        try (PackageParser2 pp = mPm.mInjector.getScanningPackageParser()) {
            parsedPackage = pp.parsePackage(scanFile, parseFlags, false);
        } catch (PackageParserException e) {
            throw new PackageManagerException(e.error, e.getMessage(), e);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }

        return addForInitLI(parsedPackage, parseFlags, scanFlags,
                new UserHandle(UserHandle.USER_SYSTEM), null);
    }

    /**
     * Adds a new package to the internal data structures during platform initialization.
     * <p>After adding, the package is known to the system and available for querying.
     * <p>For packages located on the device ROM [eg. packages located in /system, /vendor,
     * etc...], additional checks are performed. Basic verification [such as ensuring
     * matching signatures, checking version codes, etc...] occurs if the package is
     * identical to a previously known package. If the package fails a signature check,
     * the version installed on /data will be removed. If the version of the new package
     * is less than or equal than the version on /data, it will be ignored.
     * <p>Regardless of the package location, the results are applied to the internal
     * structures and the package is made available to the rest of the system.
     * <p>NOTE: The return value should be removed. It's the passed in package object.
     */
    @GuardedBy("mPm.mInstallLock")
    private AndroidPackage addForInitLI(ParsedPackage parsedPackage,
            @ParsingPackageUtils.ParseFlags int parseFlags,
            @PackageManagerService.ScanFlags int scanFlags,
            @Nullable UserHandle user, @Nullable ApexManager.ActiveApexInfo activeApexInfo)
            throws PackageManagerException {
        PackageSetting disabledPkgSetting;
        synchronized (mPm.mLock) {
            // Static shared libraries have synthetic package names
            if (activeApexInfo == null && parsedPackage.isStaticSharedLibrary()) {
                PackageManagerService.renameStaticSharedLibraryPackage(parsedPackage);
            }
            disabledPkgSetting =
                    mPm.mSettings.getDisabledSystemPkgLPr(parsedPackage.getPackageName());
            if (activeApexInfo != null && disabledPkgSetting != null) {
                // When a disabled system package is scanned, its final PackageSetting is actually
                // skipped and not added to any data structures, instead relying on the disabled
                // setting read from the persisted Settings XML file. This persistence does not
                // include the APEX module name, so here, re-set it from the active APEX info.
                //
                // This also has the (beneficial) side effect where if a package disappears from an
                // APEX, leaving only a /data copy, it will lose its apexModuleName.
                //
                // This must be done before scanPackageForInitLI as that will throw in the case of a
                // system -> data package.
                disabledPkgSetting.setApexModuleName(activeApexInfo.apexModuleName);
            }
        }

        final Pair<ScanResult, Boolean> scanResultPair = scanPackageForInitLI(
                parsedPackage, parseFlags, scanFlags, user);
        final ScanResult scanResult = scanResultPair.first;
        boolean shouldHideSystemApp = scanResultPair.second;
        final InstallRequest installRequest = new InstallRequest(
                parsedPackage, parseFlags, scanFlags, user, scanResult, disabledPkgSetting);

        String existingApexModuleName = null;
        synchronized (mPm.mLock) {
            var existingPkgSetting = mPm.mSettings.getPackageLPr(parsedPackage.getPackageName());
            if (existingPkgSetting != null) {
                existingApexModuleName = existingPkgSetting.getApexModuleName();
            }
        }

        if (activeApexInfo != null) {
            installRequest.setApexModuleName(activeApexInfo.apexModuleName);
        } else {
            if (disabledPkgSetting != null) {
                installRequest.setApexModuleName(disabledPkgSetting.getApexModuleName());
            } else if (existingApexModuleName != null) {
                installRequest.setApexModuleName(existingApexModuleName);
            }
        }

        synchronized (mPm.mLock) {
            boolean appIdCreated = false;
            try {
                final String pkgName = scanResult.mPkgSetting.getPackageName();
                final List<ReconciledPackage> reconcileResult =
                        ReconcilePackageUtils.reconcilePackages(
                                Collections.singletonList(installRequest),
                                mPm.mPackages, Collections.singletonMap(pkgName,
                                        mPm.getSettingsVersionForPackage(parsedPackage)),
                                mSharedLibraries, mPm.mSettings.getKeySetManagerService(),
                                mPm.mSettings, mPm.mInjector.getSystemConfig());
                if ((scanFlags & SCAN_AS_APEX) == 0) {
                    appIdCreated = optimisticallyRegisterAppId(installRequest);
                } else {
                    installRequest.setScannedPackageSettingAppId(Process.INVALID_UID);
                }
                commitReconciledScanResultLocked(reconcileResult.get(0),
                        mPm.mUserManager.getUserIds());
            } catch (PackageManagerException e) {
                if (appIdCreated) {
                    cleanUpAppIdCreation(installRequest);
                }
                throw e;
            }
        }

        if (shouldHideSystemApp) {
            synchronized (mPm.mLock) {
                mPm.mSettings.disableSystemPackageLPw(parsedPackage.getPackageName(), true);
            }
        }

        if (mIncrementalManager != null && isIncrementalPath(parsedPackage.getPath())) {
            if (scanResult.mPkgSetting != null && scanResult.mPkgSetting.isLoading()) {
                // Continue monitoring loading progress of active incremental packages
                mIncrementalManager.registerLoadingProgressCallback(parsedPackage.getPath(),
                        new IncrementalProgressListener(parsedPackage.getPackageName(), mPm));
            }
        }

        if (Flags.aslInApkAppMetadataSource()
                && scanResult.mPkgSetting.getAppMetadataSource() == APP_METADATA_SOURCE_APK) {
            if (!extractAppMetadataFromApk(parsedPackage,
                    scanResult.mPkgSetting.getAppMetadataFilePath(),
                    scanResult.mPkgSetting.isSystem())) {
                synchronized (mPm.mLock) {
                    scanResult.mPkgSetting.setAppMetadataFilePath(null)
                            .setAppMetadataSource(APP_METADATA_SOURCE_UNKNOWN);
                }
            }
        }
        return scanResult.mPkgSetting.getPkg();
    }

    /**
     * Prepares the system to commit a {@link ScanResult} in a way that will not fail by registering
     * the app ID required for reconcile.
     * @return {@code true} if a new app ID was registered and will need to be cleaned up on
     *         failure.
     */
    private boolean optimisticallyRegisterAppId(@NonNull InstallRequest installRequest)
            throws PackageManagerException {
        if (!installRequest.isExistingSettingCopied() || installRequest.needsNewAppId()) {
            synchronized (mPm.mLock) {
                // THROWS: when we can't allocate a user id. add call to check if there's
                // enough space to ensure we won't throw; otherwise, don't modify state
                return mPm.mSettings.registerAppIdLPw(installRequest.getScannedPackageSetting(),
                        installRequest.needsNewAppId());
            }
        }
        return false;
    }

    /**
     * Reverts any app ID creation that were made by
     * {@link #optimisticallyRegisterAppId(InstallRequest)}. Note: this is only necessary if the
     * referenced method returned true.
     */
    private void cleanUpAppIdCreation(@NonNull InstallRequest installRequest) {
        // iff we've acquired an app ID for a new package setting, remove it so that it can be
        // acquired by another request.
        if (installRequest.getScannedPackageSetting() != null
                && installRequest.getScannedPackageSetting().getAppId() > 0) {
            synchronized (mPm.mLock) {
                mPm.mSettings.removeAppIdLPw(installRequest.getScannedPackageSetting().getAppId());
            }
        }
    }

    private ScanResult scanPackageTraced(ParsedPackage parsedPackage,
            final @ParsingPackageUtils.ParseFlags int parseFlags,
            @PackageManagerService.ScanFlags int scanFlags, long currentTime,
            @Nullable UserHandle user, String cpuAbiOverride) throws PackageManagerException {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "scanPackage");
        try {
            return scanPackageNew(parsedPackage, parseFlags, scanFlags, currentTime, user,
                    cpuAbiOverride);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    private ScanRequest prepareInitialScanRequest(@NonNull ParsedPackage parsedPackage,
            @ParsingPackageUtils.ParseFlags int parseFlags,
            @PackageManagerService.ScanFlags int scanFlags,
            @Nullable UserHandle user, String cpuAbiOverride)
            throws PackageManagerException {
        final AndroidPackage platformPackage;
        final String realPkgName;
        final PackageSetting disabledPkgSetting;
        final PackageSetting installedPkgSetting;
        final PackageSetting originalPkgSetting;
        final SharedUserSetting sharedUserSetting;
        SharedUserSetting oldSharedUserSetting = null;

        synchronized (mPm.mLock) {
            platformPackage = mPm.getPlatformPackage();
            var isSystemApp = AndroidPackageLegacyUtils.isSystem(parsedPackage);
            final String renamedPkgName = mPm.mSettings.getRenamedPackageLPr(
                    AndroidPackageUtils.getRealPackageOrNull(parsedPackage, isSystemApp));
            realPkgName = ScanPackageUtils.getRealPackageName(parsedPackage, renamedPkgName,
                    isSystemApp);
            if (realPkgName != null) {
                ScanPackageUtils.ensurePackageRenamed(parsedPackage, renamedPkgName);
            }
            originalPkgSetting = getOriginalPackageLocked(parsedPackage, renamedPkgName);
            installedPkgSetting = mPm.mSettings.getPackageLPr(parsedPackage.getPackageName());
            if (mPm.mTransferredPackages.contains(parsedPackage.getPackageName())) {
                Slog.w(TAG, "Package " + parsedPackage.getPackageName()
                        + " was transferred to another, but its .apk remains");
            }
            disabledPkgSetting = mPm.mSettings.getDisabledSystemPkgLPr(
                    parsedPackage.getPackageName());

            boolean ignoreSharedUserId = false;
            if (installedPkgSetting == null || !installedPkgSetting.hasSharedUser()) {
                // Directly ignore sharedUserSetting for new installs, or if the app has
                // already left shared UID
                ignoreSharedUserId = parsedPackage.isLeavingSharedUser();
            }

            if (!ignoreSharedUserId && parsedPackage.getSharedUserId() != null) {
                sharedUserSetting = mPm.mSettings.getSharedUserLPw(
                        parsedPackage.getSharedUserId(),
                        0 /*pkgFlags*/, 0 /*pkgPrivateFlags*/, true /*create*/);
            } else {
                sharedUserSetting = null;
            }
            if (DEBUG_PACKAGE_SCANNING
                    && (parseFlags & ParsingPackageUtils.PARSE_CHATTY) != 0
                    && sharedUserSetting != null) {
                Log.d(TAG, "Shared UserID " + parsedPackage.getSharedUserId()
                        + " (uid=" + sharedUserSetting.mAppId + "):"
                        + " packages=" + sharedUserSetting.getPackageStates());
            }
            if (installedPkgSetting != null) {
                oldSharedUserSetting = mPm.mSettings.getSharedUserSettingLPr(installedPkgSetting);
            }
        }

        final boolean isPlatformPackage = platformPackage != null
                && platformPackage.getPackageName().equals(parsedPackage.getPackageName());

        return new ScanRequest(parsedPackage, oldSharedUserSetting,
                installedPkgSetting == null ? null : installedPkgSetting.getPkg() /* oldPkg */,
                installedPkgSetting /* packageSetting */,
                sharedUserSetting,
                disabledPkgSetting /* disabledPackageSetting */,
                originalPkgSetting  /* originalPkgSetting */,
                realPkgName, parseFlags, scanFlags, isPlatformPackage, user, cpuAbiOverride);
    }

    private ScanResult scanPackageNew(@NonNull ParsedPackage parsedPackage,
            final @ParsingPackageUtils.ParseFlags int parseFlags,
            @PackageManagerService.ScanFlags int scanFlags, long currentTime,
            @Nullable UserHandle user, String cpuAbiOverride)
            throws PackageManagerException {
        final ScanRequest initialScanRequest = prepareInitialScanRequest(parsedPackage, parseFlags,
                scanFlags, user, cpuAbiOverride);
        final PackageSetting installedPkgSetting = initialScanRequest.mPkgSetting;
        final PackageSetting disabledPkgSetting = initialScanRequest.mDisabledPkgSetting;

        boolean isUpdatedSystemApp;
        if (installedPkgSetting != null) {
            isUpdatedSystemApp = installedPkgSetting.isUpdatedSystemApp();
        } else {
            isUpdatedSystemApp = disabledPkgSetting != null;
        }

        final int newScanFlags = adjustScanFlags(scanFlags, installedPkgSetting, disabledPkgSetting,
                user, parsedPackage);
        ScanPackageUtils.applyPolicy(parsedPackage, newScanFlags,
                mPm.getPlatformPackage(), isUpdatedSystemApp);

        synchronized (mPm.mLock) {
            assertPackageIsValid(parsedPackage, parseFlags, newScanFlags);
            final ScanRequest request = new ScanRequest(parsedPackage,
                    initialScanRequest.mOldSharedUserSetting,
                    initialScanRequest.mOldPkg, installedPkgSetting,
                    initialScanRequest.mSharedUserSetting, disabledPkgSetting,
                    initialScanRequest.mOriginalPkgSetting, initialScanRequest.mRealPkgName,
                    parseFlags, scanFlags, initialScanRequest.mIsPlatformPackage, user,
                    cpuAbiOverride);
            return ScanPackageUtils.scanPackageOnly(request, mPm.mInjector, mPm.mFactoryTest,
                    currentTime);
        }
    }

    private Pair<ScanResult, Boolean> scanPackageForInitLI(ParsedPackage parsedPackage,
            @ParsingPackageUtils.ParseFlags int parseFlags,
            @PackageManagerService.ScanFlags int scanFlags,
            @Nullable UserHandle user) throws PackageManagerException {
        final boolean scanSystemPartition =
                (parseFlags & ParsingPackageUtils.PARSE_IS_SYSTEM_DIR) != 0;
        final ScanRequest initialScanRequest = prepareInitialScanRequest(parsedPackage, parseFlags,
                scanFlags, user, null);
        final PackageSetting installedPkgSetting = initialScanRequest.mPkgSetting;
        final PackageSetting originalPkgSetting = initialScanRequest.mOriginalPkgSetting;
        final PackageSetting pkgSetting =
                originalPkgSetting == null ? installedPkgSetting : originalPkgSetting;
        final boolean pkgAlreadyExists = pkgSetting != null;
        final String disabledPkgName = pkgAlreadyExists
                ? pkgSetting.getPackageName() : parsedPackage.getPackageName();
        final boolean isSystemPkgUpdated;
        final PackageSetting disabledPkgSetting;
        final boolean isUpgrade;
        synchronized (mPm.mLock) {
            isUpgrade = mPm.isDeviceUpgrading();
            if (scanSystemPartition && !pkgAlreadyExists
                    && mPm.mSettings.getDisabledSystemPkgLPr(disabledPkgName) != null) {
                // The updated-package data for /system apk remains inconsistently
                // after the package data for /data apk is lost accidentally.
                // To recover it, enable /system apk and install it as non-updated system app.
                Slog.w(TAG, "Inconsistent package setting of updated system app for "
                        + disabledPkgName + ". To recover it, enable the system app "
                        + "and install it as non-updated system app.");
                mPm.mSettings.removeDisabledSystemPackageLPw(disabledPkgName);
            }
            disabledPkgSetting = mPm.mSettings.getDisabledSystemPkgLPr(disabledPkgName);
            isSystemPkgUpdated = disabledPkgSetting != null;

            if (DEBUG_INSTALL && isSystemPkgUpdated) {
                Slog.d(TAG, "updatedPkg = " + disabledPkgSetting);
            }

            if (scanSystemPartition && isSystemPkgUpdated) {
                // we're updating the disabled package, so, scan it as the package setting
                final ScanRequest request = new ScanRequest(parsedPackage,
                        mPm.mSettings.getSharedUserSettingLPr(disabledPkgSetting),
                        null, disabledPkgSetting /* pkgSetting */,
                        initialScanRequest.mSharedUserSetting,
                        null /* disabledPkgSetting */, null /* originalPkgSetting */,
                        null, parseFlags, scanFlags,
                        initialScanRequest.mIsPlatformPackage, user, null);
                ScanPackageUtils.applyPolicy(parsedPackage, scanFlags,
                        mPm.getPlatformPackage(), true);
                final ScanResult scanResult =
                        ScanPackageUtils.scanPackageOnly(request, mPm.mInjector,
                                mPm.mFactoryTest, -1L);
                if (scanResult.mExistingSettingCopied
                        && scanResult.mRequest.mPkgSetting != null) {
                    scanResult.mRequest.mPkgSetting.updateFrom(scanResult.mPkgSetting);
                }
            }
        } // End of mLock

        final boolean newPkgChangedPaths = pkgAlreadyExists
                && !pkgSetting.getPathString().equals(parsedPackage.getPath());
        final boolean newPkgVersionGreater = pkgAlreadyExists
                && parsedPackage.getLongVersionCode() > pkgSetting.getVersionCode();
        final boolean newSharedUserSetting = pkgAlreadyExists
                && (initialScanRequest.mOldSharedUserSetting
                != initialScanRequest.mSharedUserSetting);
        final boolean isSystemPkgBetter = scanSystemPartition && isSystemPkgUpdated
                && newPkgChangedPaths && (newPkgVersionGreater || newSharedUserSetting);
        if (isSystemPkgBetter) {
            // The version of the application on /system is greater than the version on
            // /data. Switch back to the application on /system.
            // It's safe to assume the application on /system will correctly scan. If not,
            // there won't be a working copy of the application.
            // Also, if the sharedUserSetting of the application on /system is different
            // from the sharedUserSetting on /data, switch back to the application on /system.
            // We should trust the sharedUserSetting on /system, even if the application
            // version on /system is smaller than the version on /data.
            synchronized (mPm.mLock) {
                // just remove the loaded entries from package lists
                mPm.mPackages.remove(pkgSetting.getPackageName());
            }

            logCriticalInfo(Log.WARN,
                    "System package updated;"
                            + " name: " + pkgSetting.getPackageName()
                            + "; " + pkgSetting.getVersionCode() + " --> "
                            + parsedPackage.getLongVersionCode()
                            + "; " + pkgSetting.getPathString()
                            + " --> " + parsedPackage.getPath());

            mRemovePackageHelper.cleanUpResources(pkgSetting.getPackageName(),
                    new File(pkgSetting.getPathString()),
                    getAppDexInstructionSets(pkgSetting.getPrimaryCpuAbiLegacy(),
                            pkgSetting.getSecondaryCpuAbiLegacy()));
            synchronized (mPm.mLock) {
                mPm.mSettings.enableSystemPackageLPw(pkgSetting.getPackageName());
            }
        }

        // The version of the application on the /system partition is less than or
        // equal to the version on the /data partition. Throw an exception and use
        // the application already installed on the /data partition.
        if (scanSystemPartition && isSystemPkgUpdated && !isSystemPkgBetter) {
            // For some updated system packages, during addForInit we want to ensure the
            // PackageSetting has the correct SigningDetails compares to the original version on
            // the system partition. For the check to happen later during the /data scan, update
            // the disabled package setting per the original APK on a system partition so that it
            // can be trusted during reconcile.
            if (needSignatureMatchToSystem(parsedPackage.getPackageName())) {
                final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
                final ParseResult<SigningDetails> result =
                        ParsingPackageUtils.getSigningDetails(input, parsedPackage,
                                false /*skipVerify*/);
                if (result.isError()) {
                    throw new PrepareFailure("Failed collect during scanPackageForInitLI",
                            result.getException());
                }
                disabledPkgSetting.setSigningDetails(result.getResult());
            }

            // In the case of a skipped package, commitReconciledScanResultLocked is not called to
            // add the object to the "live" data structures, so this is the final mutation step
            // for the package. Which means it needs to be finalized here to cache derived fields.
            // This is relevant for cases where the disabled system package is used for flags or
            // other metadata.
            parsedPackage.hideAsFinal();
            throw PackageManagerException.ofInternalError(
                    "Package " + parsedPackage.getPackageName()
                    + " at " + parsedPackage.getPath() + " ignored: updated version "
                    + (pkgAlreadyExists ? String.valueOf(pkgSetting.getVersionCode()) : "unknown")
                    + " better than this " + parsedPackage.getLongVersionCode(),
                    PackageManagerException.INTERNAL_ERROR_UPDATED_VERSION_BETTER_THAN_SYSTEM);
        }

        // Verify certificates against what was last scanned. Force re-collecting certificate in two
        // special cases:
        // 1) when scanning system, force re-collect only if system is upgrading.
        // 2) when scanning /data, force re-collect only if the package name is allowlisted.
        final boolean forceCollect = scanSystemPartition ? isUpgrade
                : pkgAlreadyExists && needSignatureMatchToSystem(pkgSetting.getPackageName());
        if (DEBUG_VERIFY && forceCollect) {
            Slog.d(TAG, "Force collect certificate of " + parsedPackage.getPackageName());
        }

        // APK verification can be skipped during certificate collection, only if the file is in a
        // verified partition.
        final boolean skipVerify = scanSystemPartition;
        ScanPackageUtils.collectCertificatesLI(pkgSetting, parsedPackage,
                mPm.getSettingsVersionForPackage(parsedPackage), forceCollect, skipVerify,
                mPm.isPreNMR1Upgrade());

        // Reset profile if the application version is changed
        maybeClearProfilesForUpgradesLI(pkgSetting, parsedPackage);

        /*
         * A new system app appeared, but we already had a non-system one of the
         * same name installed earlier.
         */
        boolean shouldHideSystemApp = false;
        // A new application appeared on /system, but, we already have a copy of
        // the application installed on /data.
        if (scanSystemPartition && !isSystemPkgUpdated && pkgAlreadyExists
                && !pkgSetting.isSystem()) {

            if (!parsedPackage.getSigningDetails()
                    .checkCapability(pkgSetting.getSigningDetails(),
                            SigningDetails.CertCapabilities.INSTALLED_DATA)
                    && !pkgSetting.getSigningDetails().checkCapability(
                    parsedPackage.getSigningDetails(),
                    SigningDetails.CertCapabilities.ROLLBACK)) {
                logCriticalInfo(Log.WARN,
                        "System package signature mismatch;"
                                + " name: " + pkgSetting.getPackageName());
                try (@SuppressWarnings("unused") PackageFreezer freezer = mPm.freezePackage(
                        parsedPackage.getPackageName(), UserHandle.USER_ALL,
                        "scanPackageInternalLI", ApplicationExitInfo.REASON_OTHER,
                        null /* request */)) {
                    mDeletePackageHelper.deletePackageLIF(
                            parsedPackage.getPackageName(), null, true,
                            mPm.mUserManager.getUserIds(), 0, new PackageRemovedInfo(), false);
                }
            } else if (newPkgVersionGreater || newSharedUserSetting) {
                // The application on /system is newer than the application on /data.
                // Simply remove the application on /data [keeping application data]
                // and replace it with the version on /system.
                // Also, if the sharedUserSetting of the application on /system is different
                // from the sharedUserSetting on data, we should trust the sharedUserSetting
                // on /system, even if the application version on /system is smaller than
                // the version on /data.
                logCriticalInfo(Log.WARN,
                        "System package enabled;"
                                + " name: " + pkgSetting.getPackageName()
                                + "; " + pkgSetting.getVersionCode() + " --> "
                                + parsedPackage.getLongVersionCode()
                                + "; " + pkgSetting.getPathString() + " --> "
                                + parsedPackage.getPath());
                mRemovePackageHelper.cleanUpResources(pkgSetting.getPackageName(),
                        new File(pkgSetting.getPathString()),
                        getAppDexInstructionSets(
                                pkgSetting.getPrimaryCpuAbiLegacy(), pkgSetting.getSecondaryCpuAbiLegacy()));
            } else {
                // The application on /system is older than the application on /data. Hide
                // the application on /system and the version on /data will be scanned later
                // and re-added like an update.
                shouldHideSystemApp = true;
                logCriticalInfo(Log.INFO,
                        "System package disabled;"
                                + " name: " + pkgSetting.getPackageName()
                                + "; old: " + pkgSetting.getPathString() + " @ "
                                + pkgSetting.getVersionCode()
                                + "; new: " + parsedPackage.getPath() + " @ "
                                + parsedPackage.getLongVersionCode());
            }
        }

        // A new application appeared on /system, and we are seeing it for the first time.
        // Its also not updated as we don't have a copy of it on /data. So, scan it in a
        // STOPPED state.
        // We'll skip this step under the following conditions:
        //   - It's "android"
        //   - It's an APEX or overlay package since stopped state does not affect them.
        //   - It is enumerated with a <initial-package-state> tag having the stopped attribute
        //     set to false
        //   - It doesn't have an enabled and exported launcher activity, which means the user
        //     wouldn't have a way to un-stop it
        final boolean isApexPkg = (scanFlags & SCAN_AS_APEX) != 0;
        if (mPm.mShouldStopSystemPackagesByDefault
                && scanSystemPartition
                && !pkgAlreadyExists
                && !isApexPkg
                && !parsedPackage.isOverlayIsStatic()
        ) {
            String packageName = parsedPackage.getPackageName();
            if (!"android".contentEquals(packageName)
                    && !mPm.mInitialNonStoppedSystemPackages.contains(packageName)
                    && hasLauncherEntry(parsedPackage)) {
                scanFlags |= SCAN_AS_STOPPED_SYSTEM_APP;
            }
        }

        final long firstInstallTime = Flags.fixSystemAppsFirstInstallTime()
                ? System.currentTimeMillis() : 0;
        final ScanResult scanResult = scanPackageNew(parsedPackage, parseFlags,
                scanFlags | SCAN_UPDATE_SIGNATURE, firstInstallTime, user, null);
        return new Pair<>(scanResult, shouldHideSystemApp);
    }

    private static boolean hasLauncherEntry(ParsedPackage parsedPackage) {
        final HashSet<String> categories = new HashSet<>();
        categories.add(Intent.CATEGORY_LAUNCHER);
        final List<ParsedActivity> activities = parsedPackage.getActivities();
        for (int indexActivity = 0; indexActivity < activities.size(); indexActivity++) {
            final ParsedActivity activity = activities.get(indexActivity);
            if (!activity.isEnabled() || !activity.isExported()) {
                continue;
            }
            final List<ParsedIntentInfo> intents = activity.getIntents();
            for (int indexIntent = 0; indexIntent < intents.size(); indexIntent++) {
                final IntentFilter intentFilter = intents.get(indexIntent).getIntentFilter();
                if (intentFilter != null && intentFilter.matchCategories(categories) == null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns whether the package needs a signature verification against the pre-installed version
     * at boot.
     */
    private boolean needSignatureMatchToSystem(String packageName) {
        return mPm.mInjector.getSystemConfig().getPreinstallPackagesWithStrictSignatureCheck()
            .contains(packageName);
    }

    /**
     * Clear the package profile if this was an upgrade and the package
     * version was updated.
     */
    private void maybeClearProfilesForUpgradesLI(
            @Nullable PackageSetting originalPkgSetting,
            @NonNull AndroidPackage pkg) {
        if (originalPkgSetting == null || !mPm.isDeviceUpgrading()) {
            return;
        }
        if (originalPkgSetting.getVersionCode() == pkg.getLongVersionCode()) {
            return;
        }

        mAppDataHelper.clearAppProfilesLIF(pkg);
        if (DEBUG_INSTALL) {
            Slog.d(TAG, originalPkgSetting.getPackageName()
                    + " clear profile due to version change "
                    + originalPkgSetting.getVersionCode() + " != "
                    + pkg.getLongVersionCode());
        }
    }

    /**
     * Returns the original package setting.
     * <p>A package can migrate its name during an update. In this scenario, a package
     * designates a set of names that it considers as one of its original names.
     * <p>An original package must be signed identically and it must have the same
     * shared user [if any].
     */
    @GuardedBy("mPm.mLock")
    @Nullable
    private PackageSetting getOriginalPackageLocked(@NonNull AndroidPackage pkg,
            @Nullable String renamedPkgName) {
        if (ScanPackageUtils.isPackageRenamed(pkg, renamedPkgName)) {
            return null;
        }
        for (int i = ArrayUtils.size(pkg.getOriginalPackages()) - 1; i >= 0; --i) {
            final PackageSetting originalPs =
                    mPm.mSettings.getPackageLPr(pkg.getOriginalPackages().get(i));
            if (originalPs != null) {
                // the package is already installed under its original name...
                // but, should we use it?
                if (!verifyPackageUpdateLPr(originalPs, pkg)) {
                    // the new package is incompatible with the original
                    continue;
                } else if (mPm.mSettings.getSharedUserSettingLPr(originalPs) != null) {
                    final String sharedUserSettingsName =
                            mPm.mSettings.getSharedUserSettingLPr(originalPs).name;
                    if (!sharedUserSettingsName.equals(pkg.getSharedUserId())) {
                        // the shared user id is incompatible with the original
                        Slog.w(TAG, "Unable to migrate data from " + originalPs.getPackageName()
                                + " to " + pkg.getPackageName() + ": old shared user settings name "
                                + sharedUserSettingsName
                                + " differs from " + pkg.getSharedUserId());
                        continue;
                    }
                    // TODO: Add case when shared user id is added [b/28144775]
                } else {
                    if (DEBUG_UPGRADE) {
                        Log.v(TAG, "Renaming new package "
                                + pkg.getPackageName() + " to old name "
                                + originalPs.getPackageName());
                    }
                }
                return originalPs;
            }
        }
        return null;
    }

    @GuardedBy("mPm.mLock")
    private boolean verifyPackageUpdateLPr(PackageSetting oldPkg, AndroidPackage newPkg) {
        if ((oldPkg.getFlags() & ApplicationInfo.FLAG_SYSTEM) == 0) {
            Slog.w(TAG, "Unable to update from " + oldPkg.getPackageName()
                    + " to " + newPkg.getPackageName()
                    + ": old package not in system partition");
            return false;
        } else if (mPm.mPackages.get(oldPkg.getPackageName()) != null) {
            Slog.w(TAG, "Unable to update from " + oldPkg.getPackageName()
                    + " to " + newPkg.getPackageName()
                    + ": old package still exists");
            return false;
        }
        return true;
    }

    /**
     * Asserts the parsed package is valid according to the given policy. If the
     * package is invalid, for whatever reason, throws {@link PackageManagerException}.
     * <p>
     * Implementation detail: This method must NOT have any side effects. It would
     * ideally be static, but, it requires locks to read system state.
     *
     * @throws PackageManagerException If the package fails any of the validation checks
     */
    private void assertPackageIsValid(AndroidPackage pkg,
            final @ParsingPackageUtils.ParseFlags int parseFlags,
            final @PackageManagerService.ScanFlags int scanFlags)
            throws PackageManagerException {
        if ((parseFlags & ParsingPackageUtils.PARSE_ENFORCE_CODE) != 0) {
            ScanPackageUtils.assertCodePolicy(pkg);
        }

        if (pkg.getPath() == null) {
            // Bail out. The resource and code paths haven't been set.
            throw new PackageManagerException(INSTALL_FAILED_INVALID_APK,
                    "Code and resource paths haven't been set correctly");
        }

        // Check that there is an APEX package with the same name only during install/first boot
        // after OTA.
        final boolean isUserInstall = (scanFlags & SCAN_BOOTING) == 0;
        final boolean isFirstBootOrUpgrade = (scanFlags & SCAN_FIRST_BOOT_OR_UPGRADE) != 0;
        // It is allowed to install a new APEX with the same name. But there shouldn't be
        // conflicting names between APK and APEX.
        final boolean installApex = (scanFlags & SCAN_AS_APEX) != 0;
        if ((isUserInstall || isFirstBootOrUpgrade)
                && mPm.snapshotComputer().isApexPackage(pkg.getPackageName())
                && !installApex) {
            throw new PackageManagerException(INSTALL_FAILED_DUPLICATE_PACKAGE,
                    pkg.getPackageName()
                            + " is an APEX package and can't be installed as an APK.");
        }

        // Make sure we're not adding any bogus keyset info
        final KeySetManagerService ksms = mPm.mSettings.getKeySetManagerService();
        ksms.assertScannedPackageValid(pkg);

        synchronized (mPm.mLock) {
            // The special "android" package can only be defined once
            if (pkg.getPackageName().equals("android")) {
                if (mPm.getCoreAndroidApplication() != null) {
                    Slog.w(TAG, "*************************************************");
                    Slog.w(TAG, "Core android package being redefined.  Skipping.");
                    Slog.w(TAG, " codePath=" + pkg.getPath());
                    Slog.w(TAG, "*************************************************");
                    throw new PackageManagerException(INSTALL_FAILED_DUPLICATE_PACKAGE,
                            "Core android package being redefined.  Skipping.");
                }
            }

            // A package name must be unique; don't allow duplicates
            if ((scanFlags & SCAN_NEW_INSTALL) == 0
                    && mPm.mPackages.containsKey(pkg.getPackageName())) {
                throw new PackageManagerException(INSTALL_FAILED_DUPLICATE_PACKAGE,
                        "Application package " + pkg.getPackageName()
                                + " already installed.  Skipping duplicate.");
            }

            if (pkg.isStaticSharedLibrary()) {
                // Static libs have a synthetic package name containing the version
                // but we still want the base name to be unique.
                if ((scanFlags & SCAN_NEW_INSTALL) == 0
                        && mPm.mPackages.containsKey(pkg.getManifestPackageName())) {
                    throw PackageManagerException.ofInternalError(
                            "Duplicate static shared lib provider package",
                            PackageManagerException.INTERNAL_ERROR_DUP_STATIC_SHARED_LIB_PROVIDER);
                }
                ScanPackageUtils.assertStaticSharedLibraryIsValid(pkg, scanFlags);
                assertStaticSharedLibraryVersionCodeIsValid(pkg);
            }

            // If we're only installing presumed-existing packages, require that the
            // scanned APK is both already known and at the path previously established
            // for it.  Previously unknown packages we pick up normally, but if we have an
            // a priori expectation about this package's install presence, enforce it.
            // With a singular exception for new system packages. When an OTA contains
            // a new system package, we allow the codepath to change from a system location
            // to the user-installed location. If we don't allow this change, any newer,
            // user-installed version of the application will be ignored.
            if ((scanFlags & SCAN_REQUIRE_KNOWN) != 0) {
                if (mPm.isExpectingBetter(pkg.getPackageName())) {
                    Slog.w(TAG, "Relax SCAN_REQUIRE_KNOWN requirement for package "
                            + pkg.getPackageName());
                } else {
                    PackageSetting known = mPm.mSettings.getPackageLPr(pkg.getPackageName());
                    if (known != null) {
                        if (DEBUG_PACKAGE_SCANNING) {
                            Log.d(TAG, "Examining " + pkg.getPath()
                                    + " and requiring known path " + known.getPathString());
                        }
                        if (!pkg.getPath().equals(known.getPathString())) {
                            throw new PackageManagerException(INSTALL_FAILED_PACKAGE_CHANGED,
                                    "Application package " + pkg.getPackageName()
                                            + " found at " + pkg.getPath()
                                            + " but expected at " + known.getPathString()
                                            + "; ignoring.");
                        }
                    } else {
                        throw new PackageManagerException(INSTALL_FAILED_INVALID_INSTALL_LOCATION,
                                "Application package " + pkg.getPackageName()
                                        + " not found; ignoring.");
                    }
                }
            }

            // Verify that this new package doesn't have any content providers
            // that conflict with existing packages.  Only do this if the
            // package isn't already installed, since we don't want to break
            // things that are installed.
            if ((scanFlags & SCAN_NEW_INSTALL) != 0) {
                mPm.mComponentResolver.assertProvidersNotDefined(pkg);
            }

            // If this package has defined explicit processes, then ensure that these are
            // the only processes used by its components.
            ScanPackageUtils.assertProcessesAreValid(pkg);

            // Verify that packages sharing a user with a privileged app are marked as privileged.
            assertPackageWithSharedUserIdIsPrivileged(pkg);

            // Apply policies specific for runtime resource overlays (RROs).
            if (pkg.getOverlayTarget() != null) {
                assertOverlayIsValid(pkg, parseFlags, scanFlags);
            }

            // Ensure the package is signed with at least the minimum signature scheme version
            // required for its target SDK.
            ScanPackageUtils.assertMinSignatureSchemeIsValid(pkg, parseFlags);
        }
    }

    private void assertStaticSharedLibraryVersionCodeIsValid(AndroidPackage pkg)
            throws PackageManagerException {
        // The version codes must be ordered as lib versions
        long minVersionCode = Long.MIN_VALUE;
        long maxVersionCode = Long.MAX_VALUE;

        WatchedLongSparseArray<SharedLibraryInfo> versionedLib =
                mSharedLibraries.getSharedLibraryInfos(pkg.getStaticSharedLibraryName());
        if (versionedLib != null) {
            final int versionCount = versionedLib.size();
            for (int i = 0; i < versionCount; i++) {
                SharedLibraryInfo libInfo = versionedLib.valueAt(i);
                final long libVersionCode = libInfo.getDeclaringPackage()
                        .getLongVersionCode();
                if (libInfo.getLongVersion() < pkg.getStaticSharedLibraryVersion()) {
                    minVersionCode = Math.max(minVersionCode, libVersionCode + 1);
                } else if (libInfo.getLongVersion()
                        > pkg.getStaticSharedLibraryVersion()) {
                    maxVersionCode = Math.min(maxVersionCode, libVersionCode - 1);
                } else {
                    minVersionCode = maxVersionCode = libVersionCode;
                    break;
                }
            }
        }
        if (pkg.getLongVersionCode() < minVersionCode
                || pkg.getLongVersionCode() > maxVersionCode) {
            throw PackageManagerException.ofInternalError("Static shared"
                    + " lib version codes must be ordered as lib versions",
                    PackageManagerException.INTERNAL_ERROR_STATIC_SHARED_LIB_VERSION_CODES_ORDER);
        }
    }

    private void assertOverlayIsValid(AndroidPackage pkg,
            @ParsingPackageUtils.ParseFlags int parseFlags,
            @PackageManagerService.ScanFlags int scanFlags) throws PackageManagerException {
        // System overlays have some restrictions on their use of the 'static' state.
        if ((scanFlags & SCAN_AS_SYSTEM) != 0) {
            // We are scanning a system overlay. This can be the first scan of the
            // system/vendor/oem partition, or an update to the system overlay.
            if ((parseFlags & ParsingPackageUtils.PARSE_IS_SYSTEM_DIR) == 0) {
                // This must be an update to a system overlay. Immutable overlays cannot be
                // upgraded.
                if (!mPm.isOverlayMutable(pkg.getPackageName())) {
                    throw PackageManagerException.ofInternalError("Overlay "
                            + pkg.getPackageName()
                            + " is static and cannot be upgraded.",
                            PackageManagerException.INTERNAL_ERROR_SYSTEM_OVERLAY_STATIC);
                }
            } else {
                if ((scanFlags & (SCAN_AS_VENDOR | SCAN_AS_ODM)) != 0) {
                    if (pkg.getTargetSdkVersion() < ScanPackageUtils.getVendorPartitionVersion()) {
                        Slog.w(TAG, "System overlay " + pkg.getPackageName()
                                + " targets an SDK below the required SDK level of vendor"
                                + " overlays ("
                                + ScanPackageUtils.getVendorPartitionVersion()
                                + ")."
                                + " This will become an install error in a future release");
                    }
                } else if (pkg.getTargetSdkVersion() < Build.VERSION.SDK_INT) {
                    Slog.w(TAG, "System overlay " + pkg.getPackageName()
                            + " targets an SDK below the required SDK level of system"
                            + " overlays (" + Build.VERSION.SDK_INT + ")."
                            + " This will become an install error in a future release");
                }
            }
        } else {
            // A non-preloaded overlay packages must have targetSdkVersion >= Q, or be
            // signed with the platform certificate. Check this in increasing order of
            // computational cost.
            if (pkg.getTargetSdkVersion() < Build.VERSION_CODES.Q) {
                final PackageSetting platformPkgSetting;
                synchronized (mPm.mLock) {
                    platformPkgSetting = mPm.mSettings.getPackageLPr("android");
                }
                if (!comparePackageSignatures(platformPkgSetting, pkg.getSigningDetails())) {
                    throw PackageManagerException.ofInternalError("Overlay "
                            + pkg.getPackageName()
                            + " must target Q or later, "
                            + "or be signed with the platform certificate",
                            PackageManagerException.INTERNAL_ERROR_OVERLAY_LOW_TARGET_SDK);
                }
            }

            // A non-preloaded overlay package, without <overlay android:targetName>, will
            // only be used if it is signed with the same certificate as its target OR if
            // it is signed with the same certificate as a reference package declared
            // in 'overlay-config-signature' tag of SystemConfig.
            // If the target is already installed or 'overlay-config-signature' tag in
            // SystemConfig is set, check this here to augment the last line of defense
            // which is OMS.
            if (pkg.getOverlayTargetOverlayableName() == null) {
                final PackageSetting targetPkgSetting;
                synchronized (mPm.mLock) {
                    targetPkgSetting = mPm.mSettings.getPackageLPr(pkg.getOverlayTarget());
                }
                if (targetPkgSetting != null) {
                    if (!comparePackageSignatures(targetPkgSetting, pkg.getSigningDetails())) {
                        // check reference signature
                        if (mPm.mOverlayConfigSignaturePackage == null) {
                            throw PackageManagerException.ofInternalError("Overlay "
                                    + pkg.getPackageName() + " and target "
                                    + pkg.getOverlayTarget() + " signed with"
                                    + " different certificates, and the overlay lacks"
                                    + " <overlay android:targetName>",
                                    PackageManagerException.INTERNAL_ERROR_OVERLAY_SIGNATURE1);
                        }
                        final PackageSetting refPkgSetting;
                        synchronized (mPm.mLock) {
                            refPkgSetting = mPm.mSettings.getPackageLPr(
                                    mPm.mOverlayConfigSignaturePackage);
                        }
                        if (!comparePackageSignatures(refPkgSetting, pkg.getSigningDetails())) {
                            throw PackageManagerException.ofInternalError("Overlay "
                                    + pkg.getPackageName() + " signed with a different "
                                    + "certificate than both the reference package and "
                                    + "target " + pkg.getOverlayTarget() + ", and the "
                                    + "overlay lacks <overlay android:targetName>",
                                    PackageManagerException.INTERNAL_ERROR_OVERLAY_SIGNATURE2);
                        }
                    }
                }
            }
        }
    }

    private void assertPackageWithSharedUserIdIsPrivileged(AndroidPackage pkg)
            throws PackageManagerException {
        if (!AndroidPackageLegacyUtils.isPrivileged(pkg)
                && (pkg.getSharedUserId() != null)
                && !pkg.isLeavingSharedUser()) {
            SharedUserSetting sharedUserSetting = null;
            try {
                synchronized (mPm.mLock) {
                    sharedUserSetting = mPm.mSettings.getSharedUserLPw(pkg.getSharedUserId(),
                            0, 0, false);
                }
            } catch (PackageManagerException ignore) {
            }
            if (sharedUserSetting != null && sharedUserSetting.isPrivileged()) {
                // Exempt SharedUsers signed with the platform key.
                final PackageSetting platformPkgSetting;
                synchronized (mPm.mLock) {
                    platformPkgSetting = mPm.mSettings.getPackageLPr("android");
                }
                if (!comparePackageSignatures(platformPkgSetting, pkg.getSigningDetails())) {
                    throw PackageManagerException.ofInternalError("Apps that share a user with a "
                            + "privileged app must themselves be marked as privileged. "
                            + pkg.getPackageName() + " shares privileged user "
                            + pkg.getSharedUserId() + ".",
                            PackageManagerException.INTERNAL_ERROR_NOT_PRIV_SHARED_USER);
                }
            }
        }
    }

    private @PackageManagerService.ScanFlags int adjustScanFlags(
            @PackageManagerService.ScanFlags int scanFlags,
            @Nullable PackageSetting existingPkgSetting,
            @Nullable PackageSetting disabledPkgSetting, UserHandle user,
            @NonNull AndroidPackage pkg) {
        scanFlags = ScanPackageUtils.adjustScanFlagsWithPackageSetting(scanFlags, existingPkgSetting,
                disabledPkgSetting, user);

        // Exception for privileged apps that share a user with a priv-app.
        final boolean skipVendorPrivilegeScan = ((scanFlags & SCAN_AS_VENDOR) != 0)
                && ScanPackageUtils.getVendorPartitionVersion() < 28;
        if (((scanFlags & SCAN_AS_PRIVILEGED) == 0)
                && !AndroidPackageLegacyUtils.isPrivileged(pkg)
                && (pkg.getSharedUserId() != null)
                && !skipVendorPrivilegeScan
                && !pkg.isLeavingSharedUser()) {
            SharedUserSetting sharedUserSetting = null;
            synchronized (mPm.mLock) {
                try {
                    sharedUserSetting = mPm.mSettings.getSharedUserLPw(pkg.getSharedUserId(), 0,
                            0, false);
                } catch (PackageManagerException ignore) {
                }
                if (sharedUserSetting != null && sharedUserSetting.isPrivileged()) {
                    // Exempt SharedUsers signed with the platform key.
                    // TODO(b/72378145) Fix this exemption. Force signature apps
                    // to allowlist their privileged permissions just like other
                    // priv-apps.
                    PackageSetting platformPkgSetting = mPm.mSettings.getPackageLPr("android");
                    if ((compareSignatures(platformPkgSetting.getSigningDetails(),
                            pkg.getSigningDetails()) != PackageManager.SIGNATURE_MATCH)) {
                        scanFlags |= SCAN_AS_PRIVILEGED;
                    }
                }
            }
        }

        return scanFlags;
    }
}
