/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.people;

import static com.android.server.flags.Flags.earlyDataManagerInit;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.people.ConversationChannel;
import android.app.people.ConversationStatus;
import android.app.people.IConversationListener;
import android.app.people.IPeopleManager;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionSessionId;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.IPredictionCallback;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ShortcutInfo;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.people.data.DataManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A service that manages the people and conversations provided by apps.
 */
public class PeopleService extends SystemService {

    private static final String TAG = "PeopleService";

    private DataManager mLazyDataManager;
    private ConversationListenerHelper mLazyConversationListenerHelper;

    private PackageManagerInternal mPackageManagerInternal;

    /**
     * Initializes the system service.
     *
     * @param context The system server context.
     */
    public PeopleService(Context context) {
        super(context);
    }

    @VisibleForTesting
    ConversationListenerHelper getConversationListenerHelper() {
        if (mLazyConversationListenerHelper == null) {
            initLazyStuff();
        }
        return mLazyConversationListenerHelper;
    }

    private synchronized void initLazyStuff() {
        if (mLazyDataManager == null) {
            mLazyDataManager = new DataManager(getContext());
            mLazyDataManager.initialize();
            mLazyConversationListenerHelper = new ConversationListenerHelper();
            mLazyDataManager.addConversationsListener(mLazyConversationListenerHelper);
        }
    }

    private DataManager getDataManager() {
        if (mLazyDataManager == null) {
            initLazyStuff();
        }
        return mLazyDataManager;
    }

    @Override
    public void onStart() {
        onStart(/* isForTesting= */ false);
    }

    @VisibleForTesting
    protected void onStart(boolean isForTesting) {
        if (!isForTesting) {
            publishBinderService(Context.PEOPLE_SERVICE, mService);
        }
        publishLocalService(PeopleServiceInternal.class, new LocalService());
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            if (earlyDataManagerInit()) {
                // Force initialization of DataManager before onUserUnlocked
                // to avoid blocking the ActivityManagerService on
                // shortcutHandleUnlockUser.
                getDataManager();
            }
        }
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        getDataManager().onUserUnlocked(user.getUserIdentifier());
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        getDataManager().onUserStopping(user.getUserIdentifier());
    }

    /**
     * Enforces that only the system or root UID can make certain calls.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or root
     */
    private static void enforceSystemOrRoot(String message) {
        if (!isSystemOrRoot()) {
            throw new SecurityException("Only system may " + message);
        }
    }

    private static boolean isSystemOrRoot() {
        final int uid = Binder.getCallingUid();
        return UserHandle.isSameApp(uid, Process.SYSTEM_UID) || uid == Process.ROOT_UID;
    }

    private int handleIncomingUser(int userId) {
        try {
            return ActivityManager.getService().handleIncomingUser(
                    Binder.getCallingPid(), Binder.getCallingUid(), userId, true, true, "", null);
        } catch (RemoteException re) {
            // Shouldn't happen, local.
        }
        return userId;
    }

    private void checkCallerIsSameApp(String pkg) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);

        if (mPackageManagerInternal.getPackageUid(pkg, /*flags=*/ 0,
                callingUserId) != callingUid) {
            throw new SecurityException("Calling uid " + callingUid + " cannot query events"
                    + "for package " + pkg);
        }
    }

    /**
     * Enforces that only the system, root UID or SystemUI can make certain calls.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or root
     */
    @VisibleForTesting
    protected void enforceSystemRootOrSystemUI(Context context, String message) {
        if (isSystemOrRoot()) return;
        context.enforceCallingPermission(android.Manifest.permission.STATUS_BAR_SERVICE,
                message);
    }

    @VisibleForTesting
    final IBinder mService = new IPeopleManager.Stub() {

        @Override
        public ConversationChannel getConversation(
                String packageName, int userId, String shortcutId) {
            enforceSystemRootOrSystemUI(getContext(), "get conversation");
            return getDataManager().getConversation(packageName, userId, shortcutId);
        }

        @Override
        public ParceledListSlice<ConversationChannel> getRecentConversations() {
            enforceSystemRootOrSystemUI(getContext(), "get recent conversations");
            return new ParceledListSlice<>(
                    getDataManager().getRecentConversations(
                            Binder.getCallingUserHandle().getIdentifier()));
        }

        @Override
        public void removeRecentConversation(String packageName, int userId, String shortcutId) {
            enforceSystemOrRoot("remove a recent conversation");
            getDataManager().removeRecentConversation(packageName, userId, shortcutId,
                    Binder.getCallingUserHandle().getIdentifier());
        }

        @Override
        public void removeAllRecentConversations() {
            enforceSystemOrRoot("remove all recent conversations");
            getDataManager().removeAllRecentConversations(
                    Binder.getCallingUserHandle().getIdentifier());
        }

        @Override
        public boolean isConversation(String packageName, int userId, String shortcutId) {
            enforceHasReadPeopleDataPermission();
            handleIncomingUser(userId);
            return getDataManager().isConversation(packageName, userId, shortcutId);
        }

        private void enforceHasReadPeopleDataPermission() throws SecurityException {
            if (getContext().checkCallingPermission(Manifest.permission.READ_PEOPLE_DATA)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Caller doesn't have READ_PEOPLE_DATA permission.");
            }
        }

        @Override
        public long getLastInteraction(String packageName, int userId, String shortcutId) {
            enforceSystemRootOrSystemUI(getContext(), "get last interaction");
            return getDataManager().getLastInteraction(packageName, userId, shortcutId);
        }

        @Override
        public void addOrUpdateStatus(String packageName, int userId, String conversationId,
                ConversationStatus status) {
            handleIncomingUser(userId);
            checkCallerIsSameApp(packageName);
            if (status.getStartTimeMillis() > System.currentTimeMillis()) {
                throw new IllegalArgumentException("Start time must be in the past");
            }
            getDataManager().addOrUpdateStatus(packageName, userId, conversationId, status);
        }

        @Override
        public void clearStatus(String packageName, int userId, String conversationId,
                String statusId) {
            handleIncomingUser(userId);
            checkCallerIsSameApp(packageName);
            getDataManager().clearStatus(packageName, userId, conversationId, statusId);
        }

        @Override
        public void clearStatuses(String packageName, int userId, String conversationId) {
            handleIncomingUser(userId);
            checkCallerIsSameApp(packageName);
            getDataManager().clearStatuses(packageName, userId, conversationId);
        }

        @Override
        public ParceledListSlice<ConversationStatus> getStatuses(String packageName, int userId,
                String conversationId) {
            handleIncomingUser(userId);
            if (!isSystemOrRoot()) {
                checkCallerIsSameApp(packageName);
            }
            return new ParceledListSlice<>(
                    getDataManager().getStatuses(packageName, userId, conversationId));
        }

        @Override
        public void registerConversationListener(
                String packageName, int userId, String shortcutId, IConversationListener listener) {
            enforceSystemRootOrSystemUI(getContext(), "register conversation listener");
            getConversationListenerHelper().addConversationListener(
                    new ListenerKey(packageName, userId, shortcutId), listener);
        }

        @Override
        public void unregisterConversationListener(IConversationListener listener) {
            enforceSystemRootOrSystemUI(getContext(), "unregister conversation listener");
            getConversationListenerHelper().removeConversationListener(listener);
        }
    };

    /**
     * Listeners for conversation changes.
     *
     * @hide
     */
    public interface ConversationsListener {
        /**
         * Triggers with the list of modified conversations from {@link DataManager} for dispatching
         * relevant updates to clients.
         *
         * @param conversations The conversations with modified data
         * @see IPeopleManager#registerConversationListener(String, int, String,
         * android.app.people.ConversationListener)
         */
        default void onConversationsUpdate(@NonNull List<ConversationChannel> conversations) {
        }
    }

    /**
     * Implements {@code ConversationListenerHelper} to dispatch conversation updates to registered
     * clients.
     */
    public static class ConversationListenerHelper implements ConversationsListener {

        ConversationListenerHelper() {
        }

        @VisibleForTesting
        final RemoteCallbackList<IConversationListener> mListeners =
                new RemoteCallbackList<>();

        /** Adds {@code listener} with {@code key} associated. */
        public synchronized void addConversationListener(ListenerKey key,
                IConversationListener listener) {
            mListeners.unregister(listener);
            mListeners.register(listener, key);
        }

        /** Removes {@code listener}. */
        public synchronized void removeConversationListener(
                IConversationListener listener) {
            mListeners.unregister(listener);
        }

        @Override
        /** Dispatches updates to {@code mListeners} with keys mapped to {@code conversations}. */
        public void onConversationsUpdate(List<ConversationChannel> conversations) {
            int count = mListeners.beginBroadcast();
            // Early opt-out if no listeners are registered.
            if (count == 0) {
                return;
            }
            Map<ListenerKey, ConversationChannel> keyedConversations = new HashMap<>();
            for (ConversationChannel conversation : conversations) {
                keyedConversations.put(getListenerKey(conversation), conversation);
            }
            for (int i = 0; i < count; i++) {
                final ListenerKey listenerKey = (ListenerKey) mListeners.getBroadcastCookie(i);
                if (!keyedConversations.containsKey(listenerKey)) {
                    continue;
                }
                final IConversationListener listener = mListeners.getBroadcastItem(i);
                try {
                    ConversationChannel channel = keyedConversations.get(listenerKey);
                    listener.onConversationUpdate(channel);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the dead object.
                }
            }
            mListeners.finishBroadcast();
        }

        private ListenerKey getListenerKey(ConversationChannel conversation) {
            ShortcutInfo info = conversation.getShortcutInfo();
            return new ListenerKey(info.getPackage(), info.getUserId(),
                    info.getId());
        }
    }

    private static class ListenerKey {
        private final String mPackageName;
        private final Integer mUserId;
        private final String mShortcutId;

        ListenerKey(String packageName, Integer userId, String shortcutId) {
            this.mPackageName = packageName;
            this.mUserId = userId;
            this.mShortcutId = shortcutId;
        }

        public String getPackageName() {
            return mPackageName;
        }

        public Integer getUserId() {
            return mUserId;
        }

        public String getShortcutId() {
            return mShortcutId;
        }

        @Override
        public boolean equals(Object o) {
            ListenerKey key = (ListenerKey) o;
            return key.getPackageName().equals(mPackageName)
                    && Objects.equals(key.getUserId(), mUserId)
                    && key.getShortcutId().equals(mShortcutId);
        }

        @Override
        public int hashCode() {
            return mPackageName.hashCode() + mUserId.hashCode() + mShortcutId.hashCode();
        }
    }

    @VisibleForTesting
    final class LocalService extends PeopleServiceInternal {

        private Map<AppPredictionSessionId, SessionInfo> mSessions = new ArrayMap<>();

        @Override
        public void onCreatePredictionSession(AppPredictionContext appPredictionContext,
                AppPredictionSessionId sessionId) {
            mSessions.put(sessionId,
                    new SessionInfo(appPredictionContext, getDataManager(), sessionId.getUserId(),
                            getContext()));
        }

        @Override
        public void notifyAppTargetEvent(AppPredictionSessionId sessionId, AppTargetEvent event) {
            runForSession(sessionId,
                    sessionInfo -> sessionInfo.getPredictor().onAppTargetEvent(event));
        }

        @Override
        public void notifyLaunchLocationShown(AppPredictionSessionId sessionId,
                String launchLocation, ParceledListSlice targetIds) {
            runForSession(sessionId,
                    sessionInfo -> sessionInfo.getPredictor().onLaunchLocationShown(
                            launchLocation, targetIds.getList()));
        }

        @Override
        public void sortAppTargets(AppPredictionSessionId sessionId, ParceledListSlice targets,
                IPredictionCallback callback) {
            runForSession(sessionId,
                    sessionInfo -> sessionInfo.getPredictor().onSortAppTargets(
                            targets.getList(),
                            targetList -> invokePredictionCallback(callback, targetList)));
        }

        @Override
        public void registerPredictionUpdates(AppPredictionSessionId sessionId,
                IPredictionCallback callback) {
            runForSession(sessionId, sessionInfo -> sessionInfo.addCallback(callback));
        }

        @Override
        public void unregisterPredictionUpdates(AppPredictionSessionId sessionId,
                IPredictionCallback callback) {
            runForSession(sessionId, sessionInfo -> sessionInfo.removeCallback(callback));
        }

        @Override
        public void requestPredictionUpdate(AppPredictionSessionId sessionId) {
            runForSession(sessionId,
                    sessionInfo -> sessionInfo.getPredictor().onRequestPredictionUpdate());
        }

        @Override
        public void onDestroyPredictionSession(AppPredictionSessionId sessionId) {
            runForSession(sessionId, sessionInfo -> {
                sessionInfo.onDestroy();
                mSessions.remove(sessionId);
            });
        }

        @Override
        public void pruneDataForUser(@UserIdInt int userId, @NonNull CancellationSignal signal) {
            getDataManager().pruneDataForUser(userId, signal);
        }

        @Nullable
        @Override
        public byte[] getBackupPayload(@UserIdInt int userId) {
            return getDataManager().getBackupPayload(userId);
        }

        @Override
        public void restore(@UserIdInt int userId, @NonNull byte[] payload) {
            getDataManager().restore(userId, payload);
        }

        @Override
        public void requestServiceFeatures(AppPredictionSessionId sessionId,
                IRemoteCallback callback) {}

        @VisibleForTesting
        SessionInfo getSessionInfo(AppPredictionSessionId sessionId) {
            return mSessions.get(sessionId);
        }

        private void runForSession(AppPredictionSessionId sessionId, Consumer<SessionInfo> method) {
            SessionInfo sessionInfo = mSessions.get(sessionId);
            if (sessionInfo == null) {
                Slog.e(TAG, "Failed to find the session: " + sessionId);
                return;
            }
            method.accept(sessionInfo);
        }

        private void invokePredictionCallback(IPredictionCallback callback,
                List<AppTarget> targets) {
            try {
                callback.onResult(new ParceledListSlice<>(targets));
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to calling callback" + e);
            }
        }
    }
}
