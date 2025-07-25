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
package com.android.systemui.media;

import static com.android.systemui.Flags.mediaControlsUserInitiatedDeleteintent;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager;
import com.android.systemui.media.controls.shared.model.MediaData;
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.dagger.CentralSurfacesModule;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Handles tasks and state related to media notifications. For example, there is a 'current' media
 * notification, which this class keeps track of.
 */
public class NotificationMediaManager implements Dumpable {
    private static final String TAG = "NotificationMediaManager";
    public static final boolean DEBUG_MEDIA = false;

    private static final HashSet<Integer> PAUSED_MEDIA_STATES = new HashSet<>();
    private static final HashSet<Integer> CONNECTING_MEDIA_STATES = new HashSet<>();
    static {
        PAUSED_MEDIA_STATES.add(PlaybackState.STATE_NONE);
        PAUSED_MEDIA_STATES.add(PlaybackState.STATE_STOPPED);
        PAUSED_MEDIA_STATES.add(PlaybackState.STATE_PAUSED);
        PAUSED_MEDIA_STATES.add(PlaybackState.STATE_ERROR);
        CONNECTING_MEDIA_STATES.add(PlaybackState.STATE_CONNECTING);
        CONNECTING_MEDIA_STATES.add(PlaybackState.STATE_BUFFERING);
    }

    private final NotificationVisibilityProvider mVisibilityProvider;
    private final MediaDataManager mMediaDataManager;
    private final NotifPipeline mNotifPipeline;
    private final NotifCollection mNotifCollection;

    private final Context mContext;
    private final ArrayList<MediaListener> mMediaListeners;

    private final Executor mBackgroundExecutor;
    private final Handler mHandler;

    protected NotificationPresenter mPresenter;
    @VisibleForTesting
    MediaController mMediaController;
    private String mMediaNotificationKey;
    private MediaMetadata mMediaMetadata;

    @VisibleForTesting
    final MediaController.Callback mMediaListener = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            super.onPlaybackStateChanged(state);
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: onPlaybackStateChanged: " + state);
            }
            if (state != null) {
                if (!isPlaybackActive(state.getState())) {
                    clearCurrentMediaNotification();
                }
                findAndUpdateMediaNotifications();
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            super.onMetadataChanged(metadata);
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: onMetadataChanged: " + metadata);
            }
            mBackgroundExecutor.execute(() -> setMediaMetadata(metadata));
            dispatchUpdateMediaMetaData();
        }
    };

    private void setMediaMetadata(MediaMetadata metadata) {
        mMediaMetadata = metadata;
    }

    /**
     * Injected constructor. See {@link CentralSurfacesModule}.
     */
    public NotificationMediaManager(
            Context context,
            NotificationVisibilityProvider visibilityProvider,
            NotifPipeline notifPipeline,
            NotifCollection notifCollection,
            MediaDataManager mediaDataManager,
            DumpManager dumpManager,
            @Background Executor backgroundExecutor,
            @Main Handler handler
    ) {
        mContext = context;
        mMediaListeners = new ArrayList<>();
        mVisibilityProvider = visibilityProvider;
        mMediaDataManager = mediaDataManager;
        mNotifPipeline = notifPipeline;
        mNotifCollection = notifCollection;
        mBackgroundExecutor = backgroundExecutor;
        mHandler = handler;

        setupNotifPipeline();

        dumpManager.registerDumpable(this);
    }

    private void setupNotifPipeline() {
        mNotifPipeline.addCollectionListener(new NotifCollectionListener() {
            @Override
            public void onEntryAdded(@NonNull NotificationEntry entry) {
                mMediaDataManager.onNotificationAdded(entry.getKey(), entry.getSbn());
            }

            @Override
            public void onEntryUpdated(NotificationEntry entry) {
                mMediaDataManager.onNotificationAdded(entry.getKey(), entry.getSbn());
            }

            @Override
            public void onEntryBind(NotificationEntry entry, StatusBarNotification sbn) {
                findAndUpdateMediaNotifications();
            }

            @Override
            public void onEntryRemoved(@NonNull NotificationEntry entry, int reason) {
                removeEntry(entry);
            }

            @Override
            public void onEntryCleanUp(@NonNull NotificationEntry entry) {
                removeEntry(entry);
            }
        });

        mMediaDataManager.addListener(new MediaDataManager.Listener() {
            @Override
            public void onMediaDataLoaded(@NonNull String key,
                    @Nullable String oldKey, @NonNull MediaData data, boolean immediately,
                    int receivedSmartspaceCardLatency, boolean isSsReactivated) {
            }

            @Override
            public void onSmartspaceMediaDataLoaded(@NonNull String key,
                    @NonNull SmartspaceMediaData data, boolean shouldPrioritize) {
            }

            @Override
            public void onMediaDataRemoved(@NonNull String key, boolean userInitiated) {
                if (mediaControlsUserInitiatedDeleteintent() && !userInitiated) {
                    // Dismissing the notification will send the app's deleteIntent, so ignore if
                    // this was an automatic removal
                    Log.d(TAG, "Not dismissing " + key + " because it was removed by the system");
                    return;
                }
                mNotifPipeline.getAllNotifs()
                        .stream()
                        .filter(entry -> Objects.equals(entry.getKey(), key))
                        .findAny()
                        .ifPresent(entry -> {
                            mNotifCollection.dismissNotification(entry,
                                    getDismissedByUserStats(entry));
                        });
            }

            @Override
            public void onSmartspaceMediaDataRemoved(@NonNull String key, boolean immediately) {}
        });
    }

    private DismissedByUserStats getDismissedByUserStats(NotificationEntry entry) {
        return new DismissedByUserStats(
                NotificationStats.DISMISSAL_SHADE, // Add DISMISSAL_MEDIA?
                NotificationStats.DISMISS_SENTIMENT_NEUTRAL,
                mVisibilityProvider.obtain(entry, /* visible= */ true));
    }

    private void removeEntry(NotificationEntry entry) {
        onNotificationRemoved(entry.getKey());
        mMediaDataManager.onNotificationRemoved(entry.getKey());
    }

    /**
     * Check if a state should be considered actively playing
     * @param state a PlaybackState
     * @return true if playing
     */
    public static boolean isPlayingState(int state) {
        return !PAUSED_MEDIA_STATES.contains(state)
            && !CONNECTING_MEDIA_STATES.contains(state);
    }

    /**
     * Check if a state should be considered as connecting
     * @param state a PlaybackState
     * @return true if connecting or buffering
     */
    public static boolean isConnectingState(int state) {
        return CONNECTING_MEDIA_STATES.contains(state);
    }

    public void setUpWithPresenter(NotificationPresenter presenter) {
        mPresenter = presenter;
    }

    public void onNotificationRemoved(String key) {
        if (key.equals(mMediaNotificationKey)) {
            clearCurrentMediaNotification();
            dispatchUpdateMediaMetaData();
        }
    }

    @Nullable
    public String getMediaNotificationKey() {
        return mMediaNotificationKey;
    }

    public MediaMetadata getMediaMetadata() {
        return mMediaMetadata;
    }

    public Icon getMediaIcon() {
        if (mMediaNotificationKey == null) {
            return null;
        }
        return Optional.ofNullable(mNotifPipeline.getEntry(mMediaNotificationKey))
            .map(entry -> entry.getIcons().getShelfIcon())
            .map(StatusBarIconView::getSourceIcon)
            .orElse(null);
    }

    public void addCallback(MediaListener callback) {
        mMediaListeners.add(callback);
        mBackgroundExecutor.execute(() -> updateMediaMetaData(callback));
    }

    private void updateMediaMetaData(MediaListener callback) {
        int playbackState = getMediaControllerPlaybackState(mMediaController);
        mHandler.post(
                () -> callback.onPrimaryMetadataOrStateChanged(mMediaMetadata, playbackState));
    }

    public void removeCallback(MediaListener callback) {
        mMediaListeners.remove(callback);
    }

    public void findAndUpdateMediaNotifications() {
        // TODO(b/169655907): get the semi-filtered notifications for current user
        Collection<NotificationEntry> allNotifications = mNotifPipeline.getAllNotifs();
        // Create new sbn list to be accessed in background thread.
        List<StatusBarNotification> statusBarNotifications = new ArrayList<>();
        for (NotificationEntry entry : allNotifications) {
            statusBarNotifications.add(entry.getSbn());
        }
        mBackgroundExecutor.execute(() -> findPlayingMediaNotification(statusBarNotifications));
        dispatchUpdateMediaMetaData();
    }

    /**
     * Find a notification and media controller associated with the playing media session, and
     * update this manager's internal state.
     * This method must be called in background.
     * TODO(b/273443374) check this method
     */
    void findPlayingMediaNotification(@NonNull List<StatusBarNotification> allNotifications) {
        // Promote the media notification with a controller in 'playing' state, if any.
        StatusBarNotification statusBarNotification = null;
        MediaController controller = null;
        for (StatusBarNotification sbn : allNotifications) {
            Notification notif = sbn.getNotification();
            if (notif.isMediaNotification()) {
                final MediaSession.Token token =
                        sbn.getNotification().extras.getParcelable(
                                Notification.EXTRA_MEDIA_SESSION, MediaSession.Token.class);
                if (token != null) {
                    MediaController aController = new MediaController(mContext, token);
                    if (PlaybackState.STATE_PLAYING
                            == getMediaControllerPlaybackState(aController)) {
                        if (DEBUG_MEDIA) {
                            Log.v(TAG, "DEBUG_MEDIA: found mediastyle controller matching "
                                    + sbn.getKey());
                        }
                        statusBarNotification = sbn;
                        controller = aController;
                        break;
                    }
                }
            }
        }

        setUpControllerAndKey(controller, statusBarNotification);
    }

    private void setUpControllerAndKey(
            MediaController controller,
            StatusBarNotification mediaNotification) {
        if (controller != null && !sameSessions(mMediaController, controller)) {
            // We have a new media session
            clearCurrentMediaNotificationSession();
            mMediaController = controller;
            mMediaController.registerCallback(mMediaListener, mHandler);
            mMediaMetadata = mMediaController.getMetadata();
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: insert listener, found new controller: "
                        + mMediaController + ", receive metadata: " + mMediaMetadata);
            }
        }

        if (mediaNotification != null
                && !mediaNotification.getKey().equals(mMediaNotificationKey)) {
            mMediaNotificationKey = mediaNotification.getKey();
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: Found new media notification: key="
                        + mMediaNotificationKey);
            }
        }
    }

    public void clearCurrentMediaNotification() {
        mBackgroundExecutor.execute(this::clearMediaNotification);
    }

    private void clearMediaNotification() {
        mMediaNotificationKey = null;
        clearCurrentMediaNotificationSession();
    }

    private void dispatchUpdateMediaMetaData() {
        ArrayList<MediaListener> callbacks = new ArrayList<>(mMediaListeners);
        mBackgroundExecutor.execute(() -> updateMediaMetaData(callbacks));
    }

    private void updateMediaMetaData(List<MediaListener> callbacks) {
        @PlaybackState.State int state = getMediaControllerPlaybackState(mMediaController);
        mHandler.post(() -> {
            for (int i = 0; i < callbacks.size(); i++) {
                callbacks.get(i).onPrimaryMetadataOrStateChanged(mMediaMetadata, state);
            }
        });
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.print("    mMediaNotificationKey=");
        pw.println(mMediaNotificationKey);
        pw.print("    mMediaController=");
        pw.print(mMediaController);
        if (mMediaController != null) {
            pw.print(" state=" + mMediaController.getPlaybackState());
        }
        pw.println();
        pw.print("    mMediaMetadata=");
        pw.print(mMediaMetadata);
        if (mMediaMetadata != null) {
            pw.print(" title=" + mMediaMetadata.getText(MediaMetadata.METADATA_KEY_TITLE));
        }
        pw.println();
    }

    private boolean isPlaybackActive(int state) {
        return state != PlaybackState.STATE_STOPPED && state != PlaybackState.STATE_ERROR
                && state != PlaybackState.STATE_NONE;
    }

    private boolean sameSessions(MediaController a, MediaController b) {
        if (a == b) {
            return true;
        }
        if (a == null) {
            return false;
        }
        return a.controlsSameSession(b);
    }

    private int getMediaControllerPlaybackState(MediaController controller) {
        if (controller != null) {
            final PlaybackState playbackState = controller.getPlaybackState();
            if (playbackState != null) {
                return playbackState.getState();
            }
        }
        return PlaybackState.STATE_NONE;
    }

    private void clearCurrentMediaNotificationSession() {
        mMediaMetadata = null;
        if (mMediaController != null) {
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: Disconnecting from old controller: "
                        + mMediaController.getPackageName());
            }
            mMediaController.unregisterCallback(mMediaListener);
        }
        mMediaController = null;
    }

    public interface MediaListener {
        /**
         * Called whenever there's new metadata or playback state.
         * @param metadata Current metadata.
         * @param state Current playback state
         * @see PlaybackState.State
         */
        default void onPrimaryMetadataOrStateChanged(MediaMetadata metadata,
                @PlaybackState.State int state) {}
    }
}
