/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.ActivityManager.LOCK_TASK_MODE_NONE;
import static android.app.ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
import static android.app.ActivityOptions.ANIM_CLIP_REVEAL;
import static android.app.ActivityOptions.ANIM_CUSTOM;
import static android.app.ActivityOptions.ANIM_NONE;
import static android.app.ActivityOptions.ANIM_OPEN_CROSS_PROFILE_APPS;
import static android.app.ActivityOptions.ANIM_REMOTE_ANIMATION;
import static android.app.ActivityOptions.ANIM_SCALE_UP;
import static android.app.ActivityOptions.ANIM_SCENE_TRANSITION;
import static android.app.ActivityOptions.ANIM_THUMBNAIL_ASPECT_SCALE_DOWN;
import static android.app.ActivityOptions.ANIM_THUMBNAIL_ASPECT_SCALE_UP;
import static android.app.ActivityOptions.ANIM_THUMBNAIL_SCALE_DOWN;
import static android.app.ActivityOptions.ANIM_THUMBNAIL_SCALE_UP;
import static android.app.ActivityOptions.ANIM_UNDEFINED;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.OP_PICTURE_IN_PICTURE;
import static android.app.WaitResult.INVALID_DELAY;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.app.WindowConfiguration.activityTypeToString;
import static android.content.Context.CONTEXT_RESTRICTED;
import static android.content.Intent.ACTION_MAIN;
import static android.content.Intent.CATEGORY_HOME;
import static android.content.Intent.CATEGORY_LAUNCHER;
import static android.content.Intent.CATEGORY_SECONDARY_HOME;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NO_HISTORY;
import static android.content.pm.ActivityInfo.CONFIG_ORIENTATION;
import static android.content.pm.ActivityInfo.CONFIG_RESOURCES_UNUSED;
import static android.content.pm.ActivityInfo.CONFIG_SCREEN_LAYOUT;
import static android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE;
import static android.content.pm.ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
import static android.content.pm.ActivityInfo.CONFIG_UI_MODE;
import static android.content.pm.ActivityInfo.CONFIG_WINDOW_CONFIGURATION;
import static android.content.pm.ActivityInfo.FLAG_ALWAYS_FOCUSABLE;
import static android.content.pm.ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS;
import static android.content.pm.ActivityInfo.FLAG_IMMERSIVE;
import static android.content.pm.ActivityInfo.FLAG_INHERIT_SHOW_WHEN_LOCKED;
import static android.content.pm.ActivityInfo.FLAG_MULTIPROCESS;
import static android.content.pm.ActivityInfo.FLAG_NO_HISTORY;
import static android.content.pm.ActivityInfo.FLAG_SHOW_FOR_ALL_USERS;
import static android.content.pm.ActivityInfo.FLAG_STATE_NOT_NEEDED;
import static android.content.pm.ActivityInfo.FLAG_TURN_SCREEN_ON;
import static android.content.pm.ActivityInfo.INSETS_DECOUPLED_CONFIGURATION_ENFORCED;
import static android.content.pm.ActivityInfo.LAUNCH_MULTIPLE;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_ALWAYS;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_DEFAULT;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_IF_ALLOWLISTED;
import static android.content.pm.ActivityInfo.LOCK_TASK_LAUNCH_MODE_NEVER;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_INSETS_DECOUPLED_CONFIGURATION;
import static android.content.pm.ActivityInfo.PERSIST_ACROSS_REBOOTS;
import static android.content.pm.ActivityInfo.PERSIST_ROOT_ONLY;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.res.Configuration.ASSETS_SEQ_UNDEFINED;
import static android.content.res.Configuration.EMPTY;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;
import static android.content.res.Configuration.UI_MODE_TYPE_DESK;
import static android.content.res.Configuration.UI_MODE_TYPE_MASK;
import static android.os.Build.VERSION_CODES.HONEYCOMB;
import static android.os.Build.VERSION_CODES.O;
import static android.os.InputConstants.DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
import static android.os.Process.SYSTEM_UID;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.ACTIVITY_EMBEDDING_GUARD_WITH_ANDROID_15;
import static android.view.WindowManager.ENABLE_ACTIVITY_EMBEDDING_FOR_ANDROID_15;
import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.PROPERTY_ACTIVITY_EMBEDDING_SPLITS_ENABLED;
import static android.view.WindowManager.PROPERTY_ALLOW_UNTRUSTED_ACTIVITY_EMBEDDING_STATE_SHARING;
import static android.view.WindowManager.TRANSIT_RELAUNCH;
import static android.view.WindowManager.hasWindowExtensionsEnabled;
import static android.window.TransitionInfo.FLAGS_IS_OCCLUDED_NO_ANIMATION;
import static android.window.TransitionInfo.FLAG_IS_OCCLUDED;
import static android.window.TransitionInfo.FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT;

import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_ADD_REMOVE;
import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_APP_TRANSITIONS;
import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_CONFIGURATION;
import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_CONTAINERS;
import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_FOCUS;
import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_FOCUS_LIGHT;
import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_ORIENTATION;
import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_STARTING_WINDOW;
import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_STATES;
import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_SWITCH;
import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_WINDOW_TRANSITIONS;
import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_WINDOW_TRANSITIONS_MIN;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__STATE__LETTERBOXED_FOR_ASPECT_RATIO;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__STATE__LETTERBOXED_FOR_FIXED_ORIENTATION;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__STATE__LETTERBOXED_FOR_SIZE_COMPAT_MODE;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__STATE__NOT_LETTERBOXED;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__STATE__NOT_VISIBLE;
import static com.android.server.wm.ActivityRecord.State.DESTROYED;
import static com.android.server.wm.ActivityRecord.State.DESTROYING;
import static com.android.server.wm.ActivityRecord.State.FINISHING;
import static com.android.server.wm.ActivityRecord.State.INITIALIZING;
import static com.android.server.wm.ActivityRecord.State.PAUSED;
import static com.android.server.wm.ActivityRecord.State.PAUSING;
import static com.android.server.wm.ActivityRecord.State.RESTARTING_PROCESS;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityRecord.State.STARTED;
import static com.android.server.wm.ActivityRecord.State.STOPPED;
import static com.android.server.wm.ActivityRecord.State.STOPPING;
import static com.android.server.wm.ActivityRecordProto.ALL_DRAWN;
import static com.android.server.wm.ActivityRecordProto.APP_STOPPED;
import static com.android.server.wm.ActivityRecordProto.CLIENT_VISIBLE;
import static com.android.server.wm.ActivityRecordProto.DEFER_HIDING_CLIENT;
import static com.android.server.wm.ActivityRecordProto.ENABLE_RECENTS_SCREENSHOT;
import static com.android.server.wm.ActivityRecordProto.FILLS_PARENT;
import static com.android.server.wm.ActivityRecordProto.FRONT_OF_TASK;
import static com.android.server.wm.ActivityRecordProto.IN_SIZE_COMPAT_MODE;
import static com.android.server.wm.ActivityRecordProto.IS_ANIMATING;
import static com.android.server.wm.ActivityRecordProto.IS_USER_FULLSCREEN_OVERRIDE_ENABLED;
import static com.android.server.wm.ActivityRecordProto.LAST_DROP_INPUT_MODE;
import static com.android.server.wm.ActivityRecordProto.LAST_SURFACE_SHOWING;
import static com.android.server.wm.ActivityRecordProto.MIN_ASPECT_RATIO;
import static com.android.server.wm.ActivityRecordProto.NAME;
import static com.android.server.wm.ActivityRecordProto.NUM_DRAWN_WINDOWS;
import static com.android.server.wm.ActivityRecordProto.NUM_INTERESTING_WINDOWS;
import static com.android.server.wm.ActivityRecordProto.OVERRIDE_ORIENTATION;
import static com.android.server.wm.ActivityRecordProto.PIP_AUTO_ENTER_ENABLED;
import static com.android.server.wm.ActivityRecordProto.PROC_ID;
import static com.android.server.wm.ActivityRecordProto.PROVIDES_MAX_BOUNDS;
import static com.android.server.wm.ActivityRecordProto.REPORTED_DRAWN;
import static com.android.server.wm.ActivityRecordProto.REPORTED_VISIBLE;
import static com.android.server.wm.ActivityRecordProto.REQUEST_OPEN_IN_BROWSER_EDUCATION_TIMESTAMP;
import static com.android.server.wm.ActivityRecordProto.SHOULD_ENABLE_USER_ASPECT_RATIO_SETTINGS;
import static com.android.server.wm.ActivityRecordProto.SHOULD_FORCE_ROTATE_FOR_CAMERA_COMPAT;
import static com.android.server.wm.ActivityRecordProto.SHOULD_IGNORE_ORIENTATION_REQUEST_LOOP;
import static com.android.server.wm.ActivityRecordProto.SHOULD_OVERRIDE_FORCE_RESIZE_APP;
import static com.android.server.wm.ActivityRecordProto.SHOULD_OVERRIDE_MIN_ASPECT_RATIO;
import static com.android.server.wm.ActivityRecordProto.SHOULD_REFRESH_ACTIVITY_FOR_CAMERA_COMPAT;
import static com.android.server.wm.ActivityRecordProto.SHOULD_REFRESH_ACTIVITY_VIA_PAUSE_FOR_CAMERA_COMPAT;
import static com.android.server.wm.ActivityRecordProto.SHOULD_SEND_COMPAT_FAKE_FOCUS;
import static com.android.server.wm.ActivityRecordProto.STARTING_DISPLAYED;
import static com.android.server.wm.ActivityRecordProto.STARTING_MOVED;
import static com.android.server.wm.ActivityRecordProto.STARTING_WINDOW;
import static com.android.server.wm.ActivityRecordProto.STATE;
import static com.android.server.wm.ActivityRecordProto.TRANSLUCENT;
import static com.android.server.wm.ActivityRecordProto.VISIBLE;
import static com.android.server.wm.ActivityRecordProto.VISIBLE_REQUESTED;
import static com.android.server.wm.ActivityRecordProto.VISIBLE_SET_FROM_TRANSFERRED_STARTING_WINDOW;
import static com.android.server.wm.ActivityRecordProto.WINDOW_TOKEN;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_APP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_CLEANUP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RESULTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TRANSITION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_USER_LEAVING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_ADD_REMOVE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_APP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_CONFIGURATION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_CONTAINERS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_FOCUS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_PAUSE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RESULTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_SAVED_STATE;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_STATES;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TRANSITION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_USER_LEAVING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_VISIBILITY;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_FREE_RESIZE;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_NONE;
import static com.android.server.wm.ActivityTaskManagerService.RELAUNCH_REASON_WINDOWING_MODE_RESIZE;
import static com.android.server.wm.ActivityTaskManagerService.getInputDispatchingTimeoutMillisLocked;
import static com.android.server.wm.ActivityTaskManagerService.isPip2ExperimentEnabled;
import static com.android.server.wm.IdentifierProto.HASH_CODE;
import static com.android.server.wm.IdentifierProto.TITLE;
import static com.android.server.wm.IdentifierProto.USER_ID;
import static com.android.server.wm.StartingData.AFTER_TRANSACTION_COPY_TO_CLIENT;
import static com.android.server.wm.StartingData.AFTER_TRANSACTION_IDLE;
import static com.android.server.wm.StartingData.AFTER_TRANSACTION_REMOVE_DIRECTLY;
import static com.android.server.wm.StartingData.AFTER_TRANSITION_FINISH;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_APP_TRANSITION;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_PREDICT_BACK;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION;
import static com.android.server.wm.TaskFragment.TASK_FRAGMENT_VISIBILITY_VISIBLE;
import static com.android.server.wm.TaskPersister.DEBUG;
import static com.android.server.wm.TaskPersister.IMAGE_EXTENSION;
import static com.android.server.wm.WindowContainer.AnimationFlags.CHILDREN;
import static com.android.server.wm.WindowContainer.AnimationFlags.PARENTS;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowContainerChildProto.ACTIVITY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_CONFIGURATION;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYOUT_REPEATS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STARTING_WINDOW_VERBOSE;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_WILL_PLACE_SURFACES;
import static com.android.server.wm.WindowState.LEGACY_POLICY_VISIBILITY;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Size;
import android.app.Activity;
import android.app.ActivityManager.TaskDescription;
import android.app.ActivityOptions;
import android.app.IApplicationThread;
import android.app.IScreenCaptureObserver;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.ResultInfo;
import android.app.WaitResult;
import android.app.WindowConfiguration;
import android.app.assist.ActivityId;
import android.app.compat.CompatChanges;
import android.app.servertransaction.ActivityConfigurationChangeItem;
import android.app.servertransaction.ActivityLifecycleItem;
import android.app.servertransaction.ActivityRelaunchItem;
import android.app.servertransaction.ActivityResultItem;
import android.app.servertransaction.ClientTransactionItem;
import android.app.servertransaction.DestroyActivityItem;
import android.app.servertransaction.MoveToDisplayItem;
import android.app.servertransaction.NewIntentItem;
import android.app.servertransaction.PauseActivityItem;
import android.app.servertransaction.ResumeActivityItem;
import android.app.servertransaction.StartActivityItem;
import android.app.servertransaction.StopActivityItem;
import android.app.servertransaction.TopResumedActivityChangeItem;
import android.app.servertransaction.TransferSplashScreenViewStateItem;
import android.app.usage.UsageEvents.Event;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.LocusId;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConstrainDisplayApisConfig;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserProperties;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.gui.DropInputMode;
import android.hardware.HardwareBuffer;
import android.hardware.power.Mode;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.PersistableBundle;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.service.contentcapture.ActivityEvent;
import android.service.dreams.DreamActivity;
import android.service.voice.IVoiceInteractionSession;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.MergedConfiguration;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.view.InputApplicationHandle;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManager.TransitionOldType;
import android.window.ActivityWindowInfo;
import android.window.ITaskFragmentOrganizer;
import android.window.RemoteTransition;
import android.window.SizeConfigurationBuckets;
import android.window.SplashScreen;
import android.window.SplashScreenView;
import android.window.SplashScreenView.SplashScreenViewParcelable;
import android.window.TaskSnapshot;
import android.window.TransitionInfo.AnimationOptions;
import android.window.WindowContainerToken;
import android.window.WindowOnBackInvokedDispatcher;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ResolverActivity;
import com.android.internal.content.ReferrerIntent;
import com.android.internal.os.TimeoutRecord;
import com.android.internal.os.TransferPipe;
import com.android.internal.policy.PhoneWindow;
import com.android.internal.protolog.ProtoLog;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.LocalServices;
import com.android.server.am.AppTimeTracker;
import com.android.server.am.PendingIntentRecord;
import com.android.server.contentcapture.ContentCaptureManagerInternal;
import com.android.server.display.color.ColorDisplayService;
import com.android.server.pm.UserManagerInternal;
import com.android.server.uri.GrantUri;
import com.android.server.uri.NeededUriGrants;
import com.android.server.uri.UriPermissionOwner;
import com.android.server.wm.ActivityMetricsLogger.TransitionInfoSnapshot;
import com.android.server.wm.WindowManagerService.H;
import com.android.window.flags.Flags;

import dalvik.annotation.optimization.NeverCompile;

import com.google.android.collect.Sets;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * An entry in the history task, representing an activity.
 */
public final class ActivityRecord extends WindowToken {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityRecord" : TAG_ATM;
    private static final String TAG_ADD_REMOVE = TAG + POSTFIX_ADD_REMOVE;
    private static final String TAG_APP = TAG + POSTFIX_APP;
    private static final String TAG_CONFIGURATION = TAG + POSTFIX_CONFIGURATION;
    private static final String TAG_CONTAINERS = TAG + POSTFIX_CONTAINERS;
    private static final String TAG_FOCUS = TAG + POSTFIX_FOCUS;
    private static final String TAG_PAUSE = TAG + POSTFIX_PAUSE;
    private static final String TAG_RESULTS = TAG + POSTFIX_RESULTS;
    private static final String TAG_SAVED_STATE = TAG + POSTFIX_SAVED_STATE;
    private static final String TAG_STATES = TAG + POSTFIX_STATES;
    private static final String TAG_SWITCH = TAG + POSTFIX_SWITCH;
    private static final String TAG_TRANSITION = TAG + POSTFIX_TRANSITION;
    private static final String TAG_USER_LEAVING = TAG + POSTFIX_USER_LEAVING;
    private static final String TAG_VISIBILITY = TAG + POSTFIX_VISIBILITY;

    private static final String ATTR_ID = "id";
    private static final String TAG_INTENT = "intent";
    private static final String ATTR_USERID = "user_id";
    private static final String TAG_PERSISTABLEBUNDLE = "persistable_bundle";
    private static final String ATTR_LAUNCHEDFROMUID = "launched_from_uid";
    private static final String ATTR_LAUNCHEDFROMPACKAGE = "launched_from_package";
    private static final String ATTR_LAUNCHEDFROMFEATURE = "launched_from_feature";
    private static final String ATTR_RESOLVEDTYPE = "resolved_type";
    private static final String ATTR_COMPONENTSPECIFIED = "component_specified";
    private static final String TAG_INITIAL_CALLER_INFO = "initial_caller_info";
    static final String ACTIVITY_ICON_SUFFIX = "_activity_icon_";

    // How many activities have to be scheduled to stop to force a stop pass.
    private static final int MAX_STOPPING_TO_FORCE = 3;

    static final int STARTING_WINDOW_TYPE_NONE = 0;
    static final int STARTING_WINDOW_TYPE_SNAPSHOT = 1;
    static final int STARTING_WINDOW_TYPE_SPLASH_SCREEN = 2;

    static final int INVALID_PID = -1;

    // How long we wait until giving up on the last activity to pause.  This
    // is short because it directly impacts the responsiveness of starting the
    // next activity.
    private static final int PAUSE_TIMEOUT = 500;

    // How long we wait for the activity to tell us it has stopped before
    // giving up.  This is a good amount of time because we really need this
    // from the application in order to get its saved state. Once the stop
    // is complete we may start destroying client resources triggering
    // crashes if the UI thread was hung. We put this timeout one second behind
    // the ANR timeout so these situations will generate ANR instead of
    // Surface lost or other errors.
    private static final int STOP_TIMEOUT = 11 * 1000;

    // How long we wait until giving up on an activity telling us it has
    // finished destroying itself.
    private static final int DESTROY_TIMEOUT = 10 * 1000;

    final ActivityTaskManagerService mAtmService;
    final ActivityCallerState mCallerState;
    @NonNull
    final ActivityInfo info; // activity info provided by developer in AndroidManifest
    // Which user is this running for?
    final int mUserId;
    // The package implementing intent's component
    // TODO: rename to mPackageName
    public final String packageName;
    // the intent component, or target of an alias.
    final ComponentName mActivityComponent;
    // Input application handle used by the input dispatcher.
    private InputApplicationHandle mInputApplicationHandle;

    final int launchedFromPid; // always the pid who started the activity.
    final int launchedFromUid; // always the uid who started the activity.
    final String launchedFromPackage; // always the package who started the activity.
    @Nullable
    final String launchedFromFeatureId; // always the feature in launchedFromPackage
    @LaunchSourceType
    int mLaunchSourceType; // latest launch source type
    final Intent intent;    // the original intent that generated us
    final String shortComponentName; // the short component name of the intent
    final String resolvedType; // as per original caller;
    final String processName; // process where this component wants to run
    final String taskAffinity; // as per ActivityInfo.taskAffinity
    final boolean stateNotNeeded; // As per ActivityInfo.flags
    @Nullable
    final WindowStyle mWindowStyle;
    @VisibleForTesting
    int mHandoverLaunchDisplayId = INVALID_DISPLAY; // Handover launch display id to next activity.
    @VisibleForTesting
    TaskDisplayArea mHandoverTaskDisplayArea; // Handover launch task display area.
    private final boolean componentSpecified;  // did caller specify an explicit component?
    final boolean rootVoiceInteraction;  // was this the root activity of a voice interaction?

    private final int theme;        // resource identifier of activity's theme.
    private Task task;              // the task this is in.
    private long createTime = System.currentTimeMillis();
    long lastVisibleTime;         // last time this activity became visible
    long pauseTime;               // last time we started pausing the activity
    long mStoppedTime;            // last time we completely stopped the activity
    long topResumedStateLossTime; // last time we reported top resumed state loss to an activity
    // Last configuration reported to the activity in the client process.
    private final MergedConfiguration mLastReportedConfiguration;
    private int mLastReportedDisplayId;
    boolean mLastReportedMultiWindowMode;
    boolean mLastReportedPictureInPictureMode;
    private final ActivityWindowInfo mLastReportedActivityWindowInfo = new ActivityWindowInfo();
    ActivityRecord resultTo; // who started this entry, so will get our reply
    final String resultWho; // additional identifier for use by resultTo.
    final int requestCode;  // code given by requester (resultTo)
    ArrayList<ResultInfo> results; // pending ActivityResult objs we have received
    HashSet<WeakReference<PendingIntentRecord>> pendingResults; // all pending intents for this act
    ArrayList<ReferrerIntent> newIntents; // any pending new intents for single-top mode
    Intent mLastNewIntent;  // the last new intent we delivered to client
    /** The most recently given options. */
    private ActivityOptions mPendingOptions;
    /** Non-null if {@link #mPendingOptions} specifies the remote animation. */
    RemoteAnimationAdapter mPendingRemoteAnimation;
    private RemoteTransition mPendingRemoteTransition;
    ActivityOptions returningOptions; // options that are coming back via convertToTranslucent
    AppTimeTracker appTimeTracker; // set if we are tracking the time in this app/task/activity
    @GuardedBy("this")
    ActivityServiceConnectionsHolder mServiceConnectionsHolder; // Service connections.
    /** @see android.content.Context#BIND_ADJUST_WITH_ACTIVITY */
    volatile boolean mVisibleForServiceConnection;
    UriPermissionOwner uriPermissions; // current special URI access perms.
    WindowProcessController app;      // if non-null, hosting application
    private State mState;    // current state we are in
    private Bundle mIcicle;         // last saved activity state
    private PersistableBundle mPersistentState; // last persistently saved activity state
    private boolean mHaveState = true; // Indicates whether the last saved state of activity is
                                       // preserved. This starts out 'true', since the initial state
                                       // of an activity is that we have everything, and we should
                                       // never consider it lacking in state to be removed if it
                                       // dies. After an activity is launched it follows the value
                                       // of #mIcicle.
    boolean launchFailed;   // set if a launched failed, to abort on 2nd try
    boolean delayedResume;  // not yet resumed because of stopped app switches?
    boolean finishing;      // activity in pending finish list?
    private boolean keysPaused;     // has key dispatching been paused for it?
    int launchMode;         // the launch mode activity attribute.
    int lockTaskLaunchMode; // the lockTaskMode manifest attribute, subject to override
    private boolean mVisible;        // Should this token's windows be visible?
    boolean visibleIgnoringKeyguard; // is this activity visible, ignoring the fact that Keyguard
                                     // might hide this activity?
    // True if the visible state of this token was forced to true due to a transferred starting
    // window.
    private boolean mVisibleSetFromTransferredStartingWindow;
    // TODO: figure out how to consolidate with the same variable in ActivityRecord.
    private boolean mDeferHidingClient; // If true we told WM to defer reporting to the client
                                        // process that it is hidden.
    private boolean mLastDeferHidingClient; // If true we will defer setting mClientVisible to false
                                           // and reporting to the client that it is hidden.
    boolean nowVisible;     // is this activity's window visible?
    public boolean launching;      // is activity launch in progress?
    public boolean translucentWindowLaunch; // a translucent window launch?
    boolean mClientVisibilityDeferred;// was the visibility change message to client deferred?
    boolean idle;           // has the activity gone idle?
    boolean hasBeenLaunched;// has this activity ever been launched?
    boolean immersive;      // immersive mode (don't interrupt if possible)
    boolean supportsEnterPipOnTaskSwitch;  // This flag is set by the system to indicate that the
        // activity can enter picture in picture while pausing (only when switching to another task)
    // The PiP params used when deferring the entering of picture-in-picture.
    PictureInPictureParams pictureInPictureArgs = new PictureInPictureParams.Builder().build();
    boolean shouldDockBigOverlays;
    int launchCount;        // count of launches since last state
    long lastLaunchTime;    // time of last launch of this activity
    ComponentName requestedVrComponent; // the requested component for handling VR mode.

    /** Whether this activity is reachable from hierarchy. */
    volatile boolean inHistory;
    final ActivityTaskSupervisor mTaskSupervisor;
    final RootWindowContainer mRootWindowContainer;
    // The token of the TaskFragment that this activity was requested to be launched into.
    IBinder mRequestedLaunchingTaskFragmentToken;

    // Tracking splash screen status from previous activity
    boolean mSplashScreenStyleSolidColor = false;

    boolean mPauseSchedulePendingForPip = false;

    // Gets set to indicate that the activity is currently being auto-pipped.
    boolean mAutoEnteringPip = false;

    static final int LAUNCH_SOURCE_TYPE_SYSTEM = 1;
    static final int LAUNCH_SOURCE_TYPE_HOME = 2;
    static final int LAUNCH_SOURCE_TYPE_SYSTEMUI = 3;
    static final int LAUNCH_SOURCE_TYPE_APPLICATION = 4;

    enum State {
        INITIALIZING,
        STARTED,
        RESUMED,
        PAUSING,
        PAUSED,
        STOPPING,
        STOPPED,
        FINISHING,
        DESTROYING,
        DESTROYED,
        RESTARTING_PROCESS
    }

    /**
     * The type of launch source.
     */
    @IntDef(prefix = {"LAUNCH_SOURCE_TYPE_"}, value = {
            LAUNCH_SOURCE_TYPE_SYSTEM,
            LAUNCH_SOURCE_TYPE_HOME,
            LAUNCH_SOURCE_TYPE_SYSTEMUI,
            LAUNCH_SOURCE_TYPE_APPLICATION
    })
    @interface LaunchSourceType {}

    private boolean mTaskOverlay = false; // Task is always on-top of other activities in the task.

    // Marking the reason why this activity is being relaunched. Mainly used to track that this
    // activity is being relaunched to fulfill a resize request due to compatibility issues, e.g. in
    // pre-NYC apps that don't have a sense of being resized.
    int mRelaunchReason = RELAUNCH_REASON_NONE;

    private boolean mForceSendResultForMediaProjection = false;

    TaskDescription taskDescription; // the recents information for this activity

    // The locusId associated with this activity, if set.
    private LocusId mLocusId;

    // The timestamp of the last request to show the "Open in browser" education
    public long mRequestOpenInBrowserEducationTimestamp;

    // Whether the activity was launched from a bubble.
    private boolean mLaunchedFromBubble;

    private SizeConfigurationBuckets mSizeConfigurations;

    @VisibleForTesting
    final TaskFragment.ConfigOverrideHint mResolveConfigHint;

    final boolean mOptOutEdgeToEdge;

    private static ConstrainDisplayApisConfig sConstrainDisplayApisConfig;

    boolean pendingVoiceInteractionStart;   // Waiting for activity-invoked voice session
    IVoiceInteractionSession voiceSession;  // Voice interaction session for this activity

    boolean mVoiceInteraction;

    int mPendingRelaunchCount;
    long mRelaunchStartTime;

    // True if we are current in the process of removing this app token from the display
    private boolean mRemovingFromDisplay = false;

    private RemoteAnimationDefinition mRemoteAnimationDefinition;

    // Set to the previous Task parent of the ActivityRecord when it is reparented to a new Task
    // due to picture-in-picture. This gets cleared whenever this activity or the Task
    // it references to gets removed. This should also be cleared when we move out of pip.
    private Task mLastParentBeforePip;

    // The token of the previous TaskFragment parent of this embedded ActivityRecord when it is
    // reparented to a new Task due to picture-in-picture.
    // Note that the TaskFragment may be finished and no longer attached in WM hierarchy.
    @Nullable
    private IBinder mLastEmbeddedParentTfTokenBeforePip;

    // Only set if this instance is a launch-into-pip Activity, points to the
    // host Activity the launch-into-pip Activity is originated from.
    private ActivityRecord mLaunchIntoPipHostActivity;

    /**
     * Sets to the previous {@link ITaskFragmentOrganizer} of the {@link TaskFragment} that the
     * activity is embedded in before it is reparented to a new Task due to picture-in-picture.
     */
    @Nullable
    ITaskFragmentOrganizer mLastTaskFragmentOrganizerBeforePip;

    boolean firstWindowDrawn;
    /** Whether the visible window(s) of this activity is drawn. */
    private boolean mReportedDrawn;
    private final WindowState.UpdateReportedVisibilityResults mReportedVisibilityResults =
            new WindowState.UpdateReportedVisibilityResults();

    // TODO(b/317000737): Replace it with visibility states lookup.
    int mTransitionChangeFlags;

    /**
     * @see #currentLaunchCanTurnScreenOn()
     */
    private boolean mCurrentLaunchCanTurnScreenOn = true;

    /** Whether our surface was set to be showing in the last call to {@link #prepareSurfaces} */
    boolean mLastSurfaceShowing;

    /**
     * The activity is opaque and fills the entire space of this task.
     * @see #occludesParent()
     */
    private boolean mOccludesParent;

    /**
     * Unlike {@link #mOccludesParent} which can be changed at runtime. This is a static attribute
     * from the style of activity. Because we don't want {@link WindowContainer#getOrientation()}
     * to be affected by the temporal state of {@link ActivityClientController#convertToTranslucent}
     * when running ANIM_SCENE_TRANSITION.
     * @see WindowContainer#providesOrientation()
     */
    final boolean mStyleFillsParent;

    // The input dispatching timeout for this application token in milliseconds.
    long mInputDispatchingTimeoutMillis = DEFAULT_DISPATCHING_TIMEOUT_MILLIS;

    private boolean mShowWhenLocked;
    private boolean mInheritShownWhenLocked;
    private boolean mTurnScreenOn;

    /**
     * Whether the user is always-visible (e.g. a communal profile). Activities for such users
     * are treated as visible regardless of what user is in the foreground, and can appear
     * over the lockscreen.
     */
    private final boolean mIsUserAlwaysVisible;

    /** Allow activity launches which would otherwise be blocked by
     * {@link BackgroundActivityStartController#checkActivityAllowedToStart}
     */
    boolean mAllowCrossUidActivitySwitchFromBelow;

    // These are used for determining when all windows associated with
    // an activity have been drawn, so they can be made visible together
    // at the same time.
    // initialize so that it doesn't match mTransactionSequence which is an int.
    private long mLastTransactionSequence = Long.MIN_VALUE;
    private int mNumInterestingWindows;
    private int mNumDrawnWindows;
    boolean allDrawn;

    /**
     * Solely for reporting to ActivityMetricsLogger. Just tracks whether, the last time this
     * Activity was part of a syncset, all windows were ready by the time the sync was ready (vs.
     * only the top-occluding ones). The assumption here is if some were not ready, they were
     * covered with starting-window/splash-screen.
     */
    boolean mLastAllReadyAtSync = false;

    private boolean mLastContainsShowWhenLockedWindow;
    private boolean mLastContainsDismissKeyguardWindow;
    private boolean mLastContainsTurnScreenOnWindow;

    /** Whether the IME is showing when transitioning away from this activity. */
    boolean mLastImeShown;

    /**
     * A flag to determine if this AR is in the process of closing or entering PIP. This is needed
     * to help AR know that the app is in the process of closing but hasn't yet started closing on
     * the WM side.
     */
    private boolean mWillCloseOrEnterPip;

    /**
     * App Compat Facade
     */
    @NonNull
    final AppCompatController mAppCompatController;

    /**
     * Whether the activity is to be displayed. See {@link android.R.attr#windowNoDisplay}.
     */
    private boolean mNoDisplay;
    final boolean mShowForAllUsers;
    // TODO: Make this final
    int mTargetSdk;

    // Last visibility state we reported to the app token.
    boolean reportedVisible;

    boolean mEnableRecentsScreenshot = true;

    // Information about an application starting window if displayed.
    // Note: these are de-referenced before the starting window animates away.
    StartingData mStartingData;
    WindowState mStartingWindow;
    StartingSurfaceController.StartingSurface mStartingSurface;
    boolean startingMoved;

    /** The last set {@link DropInputMode} for this activity surface. */
    @DropInputMode
    private int mLastDropInputMode = DropInputMode.NONE;
    /** Whether the input to this activity will be dropped during the current playing animation. */
    private boolean mIsInputDroppedForAnimation;

    /**
     * Whether the application has desk mode resources. Calculated and cached when
     * {@link #hasDeskResources()} is called.
     */
    @Nullable
    private Boolean mHasDeskResources;

    boolean mHandleExitSplashScreen;
    @TransferSplashScreenState
    int mTransferringSplashScreenState = TRANSFER_SPLASH_SCREEN_IDLE;

    /** Idle, can be triggered to do transfer if needed. */
    static final int TRANSFER_SPLASH_SCREEN_IDLE = 0;

    /** Requesting a copy from shell. */
    static final int TRANSFER_SPLASH_SCREEN_COPYING = 1;

    /** Attach the splash screen view to activity. */
    static final int TRANSFER_SPLASH_SCREEN_ATTACH_TO_CLIENT = 2;

    /** Client has taken over splash screen view. */
    static final int TRANSFER_SPLASH_SCREEN_FINISH = 3;

    @IntDef(prefix = {"TRANSFER_SPLASH_SCREEN_"}, value = {
            TRANSFER_SPLASH_SCREEN_IDLE,
            TRANSFER_SPLASH_SCREEN_COPYING,
            TRANSFER_SPLASH_SCREEN_ATTACH_TO_CLIENT,
            TRANSFER_SPLASH_SCREEN_FINISH,
    })
    @interface TransferSplashScreenState {
    }

    // How long we wait until giving up transfer splash screen.
    private static final int TRANSFER_SPLASH_SCREEN_TIMEOUT = 2000;

    /**
     * The icon is shown when the launching activity sets the splashScreenStyle to
     * SPLASH_SCREEN_STYLE_ICON. If the launching activity does not specify any style,
     * follow the system behavior.
     *
     * @see android.R.attr#windowSplashScreenBehavior
     */
    private static final int SPLASH_SCREEN_BEHAVIOR_DEFAULT = 0;
    /**
     * The icon is shown unless the launching app specified SPLASH_SCREEN_STYLE_SOLID_COLOR.
     *
     * @see android.R.attr#windowSplashScreenBehavior
     */
    private static final int SPLASH_SCREEN_BEHAVIOR_ICON_PREFERRED = 1;

    @IntDef(prefix = {"SPLASH_SCREEN_BEHAVIOR_"}, value = {
            SPLASH_SCREEN_BEHAVIOR_DEFAULT,
            SPLASH_SCREEN_BEHAVIOR_ICON_PREFERRED
    })
    @interface SplashScreenBehavior { }

    boolean mEnteringAnimation;
    boolean mOverrideTaskTransition;
    boolean mDismissKeyguardIfInsecure;
    boolean mShareIdentity;

    /** True if the activity has reported stopped; False if the activity becomes visible. */
    boolean mAppStopped;
    // A hint to override the window specified rotation animation, or -1 to use the window specified
    // value. We use this so that we can select the right animation in the cases of starting
    // windows, where the app hasn't had time to set a value on the window.
    int mRotationAnimationHint = -1;

    private AppSaturationInfo mLastAppSaturationInfo;

    private RemoteCallbackList<IScreenCaptureObserver> mCaptureCallbacks;

    private final ColorDisplayService.ColorTransformController mColorTransformController =
            (matrix, translation) -> mWmService.mH.post(() -> {
                synchronized (mWmService.mGlobalLock) {
                    if (mLastAppSaturationInfo == null) {
                        mLastAppSaturationInfo = new AppSaturationInfo();
                    }

                    mLastAppSaturationInfo.setSaturation(matrix, translation);
                    updateColorTransform();
                }
            });

    /**
     * Current sequencing integer of the configuration, for skipping old activity configurations.
     */
    private int mConfigurationSeq;

    /**
     * Temp configs used in {@link #ensureActivityConfiguration()}
     */
    private final Configuration mTmpConfig = new Configuration();
    private final Rect mTmpBounds = new Rect();
    private final ActivityWindowInfo mTmpActivityWindowInfo = new ActivityWindowInfo();

    // Token for targeting this activity for assist purposes.
    final Binder assistToken = new Binder();

    // A reusable token for other purposes, e.g. content capture, translation. It shouldn't be used
    // without security checks
    final Binder shareableActivityToken = new Binder();

    // Token for accessing the initial caller who started the activity.
    final IBinder initialCallerInfoAccessToken = new Binder();

    // Tracking cookie for the launch of this activity and it's task.
    IBinder mLaunchCookie;

    // Tracking indicated launch root in order to propagate it among trampoline activities.
    WindowContainerToken mLaunchRootTask;

    // Entering PiP is usually done in two phases, we put the task into pinned mode first and
    // SystemUi sets the pinned mode on activity after transition is done.
    boolean mWaitForEnteringPinnedMode;

    final ActivityRecordInputSink mActivityRecordInputSink;
    // System activities with INTERNAL_SYSTEM_WINDOW can disable ActivityRecordInputSink.
    boolean mActivityRecordInputSinkEnabled = true;

    // Activities with this uid are allowed to not create an input sink while being in the same
    // task and directly above this ActivityRecord. This field is updated whenever a new activity
    // is launched from this ActivityRecord. Touches are always allowed within the same uid.
    int mAllowedTouchUid;
    // Whether client has requested a scene transition when exiting.
    final boolean mHasSceneTransition;
    // Whether the app has opt-in enableOnBackInvokedCallback.
    final boolean mOptInOnBackInvoked;

    // Whether the ActivityEmbedding is enabled on the app.
    private final boolean mAppActivityEmbeddingSplitsEnabled;

    // Whether the Activity allows state sharing in untrusted embedding
    private final boolean mAllowUntrustedEmbeddingStateSharing;

    // Records whether client has overridden the WindowAnimation_(Open/Close)(Enter/Exit)Animation.
    private CustomAppTransition mCustomOpenTransition;
    private CustomAppTransition mCustomCloseTransition;

    /** Non-zero to pause dispatching configuration changes to the client. */
    int mPauseConfigurationDispatchCount = 0;

    private final PowerManagerInternal mPowerManagerInternal;
    private boolean mIsBoosted;

    private final Runnable mPauseTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            // We don't at this point know if the activity is fullscreen,
            // so we need to be conservative and assume it isn't.
            Slog.w(TAG, "Activity pause timeout for " + ActivityRecord.this);
            synchronized (mAtmService.mGlobalLock) {
                if (!hasProcess()) {
                    return;
                }
                activityPaused(true);
            }
        }
    };

    private final Runnable mDestroyTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mAtmService.mGlobalLock) {
                Slog.w(TAG, "Activity destroy timeout for " + ActivityRecord.this);
                destroyed("destroyTimeout");
            }
        }
    };

    private final Runnable mStopTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mAtmService.mGlobalLock) {
                Slog.w(TAG, "Activity stop timeout for " + ActivityRecord.this);
                if (isInHistory()) {
                    activityStopped(
                            null /*icicle*/, null /*persistentState*/, null /*description*/);
                }
            }
        }
    };

    @NeverCompile // Avoid size overhead of debugging code.
    @Override
    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        final long now = SystemClock.uptimeMillis();
        pw.print(prefix); pw.print("packageName="); pw.print(packageName);
                pw.print(" processName="); pw.println(processName);
        pw.print(prefix); pw.print("launchedFromUid="); pw.print(launchedFromUid);
                pw.print(" launchedFromPackage="); pw.print(launchedFromPackage);
                pw.print(" launchedFromFeature="); pw.print(launchedFromFeatureId);
                pw.print(" userId="); pw.println(mUserId);
        pw.print(prefix); pw.print("app="); pw.println(app);
        pw.print(prefix); pw.println(intent.toInsecureString());
        pw.print(prefix); pw.print("rootOfTask="); pw.print(isRootOfTask());
                pw.print(" task="); pw.println(task);
        pw.print(prefix); pw.print("taskAffinity="); pw.println(taskAffinity);
        pw.print(prefix); pw.print("mActivityComponent=");
                pw.println(mActivityComponent.flattenToShortString());
        final ApplicationInfo appInfo = info.applicationInfo;
        pw.print(prefix); pw.print("baseDir="); pw.println(appInfo.sourceDir);
        if (!Objects.equals(appInfo.sourceDir, appInfo.publicSourceDir)) {
            pw.print(prefix); pw.print("resDir="); pw.println(appInfo.publicSourceDir);
        }
        pw.print(prefix); pw.print("dataDir="); pw.println(appInfo.dataDir);
        if (appInfo.splitSourceDirs != null) {
            pw.print(prefix); pw.print("splitDir=");
            pw.println(Arrays.toString(appInfo.splitSourceDirs));
        }
        pw.print(prefix); pw.print("stateNotNeeded="); pw.print(stateNotNeeded);
                pw.print(" componentSpecified="); pw.print(componentSpecified);
                pw.print(" mActivityType="); pw.println(
                        activityTypeToString(getActivityType()));
        if (rootVoiceInteraction) {
            pw.print(prefix); pw.print("rootVoiceInteraction="); pw.println(rootVoiceInteraction);
        }
        pw.print(prefix); pw.print("compat=");
        pw.print(mAtmService.compatibilityInfoForPackageLocked(info.applicationInfo));
                pw.print(" theme=0x"); pw.println(Integer.toHexString(theme));
        pw.println(prefix + "mLastReportedConfigurations:");
        mLastReportedConfiguration.dump(pw, prefix + "  ");

        pw.print(prefix);
        pw.print("mLastReportedActivityWindowInfo=");
        pw.println(mLastReportedActivityWindowInfo);

        pw.print(prefix); pw.print("CurrentConfiguration="); pw.println(getConfiguration());
        if (!getRequestedOverrideConfiguration().equals(EMPTY)) {
            pw.println(prefix + "RequestedOverrideConfiguration="
                    + getRequestedOverrideConfiguration());
        }
        if (!getResolvedOverrideConfiguration().equals(getRequestedOverrideConfiguration())) {
            pw.println(prefix + "ResolvedOverrideConfiguration="
                    + getResolvedOverrideConfiguration());
        }
        if (!matchParentBounds()) {
            pw.println(prefix + "bounds=" + getBounds());
        }
        if (resultTo != null || resultWho != null) {
            pw.print(prefix); pw.print("resultTo="); pw.print(resultTo);
                    pw.print(" resultWho="); pw.print(resultWho);
                    pw.print(" resultCode="); pw.println(requestCode);
        }
        if (taskDescription != null) {
            final String iconFilename = taskDescription.getIconFilename();
            if (iconFilename != null || taskDescription.getLabel() != null ||
                    taskDescription.getPrimaryColor() != 0) {
                pw.print(prefix); pw.print("taskDescription:");
                        pw.print(" label=\""); pw.print(taskDescription.getLabel());
                                pw.print("\"");
                        pw.print(" icon="); pw.print(taskDescription.getInMemoryIcon() != null
                                ? taskDescription.getInMemoryIcon().getByteCount() + " bytes"
                                : "null");
                        pw.print(" iconResource=");
                                pw.print(taskDescription.getIconResourcePackage());
                                pw.print("/");
                                pw.print(taskDescription.getIconResource());
                        pw.print(" iconFilename="); pw.print(taskDescription.getIconFilename());
                        pw.print(" primaryColor=");
                        pw.println(Integer.toHexString(taskDescription.getPrimaryColor()));
                        pw.print(prefix); pw.print("  backgroundColor=");
                        pw.print(Integer.toHexString(taskDescription.getBackgroundColor()));
                        pw.print(" statusBarColor=");
                        pw.print(Integer.toHexString(taskDescription.getStatusBarColor()));
                        pw.print(" navigationBarColor=");
                        pw.println(Integer.toHexString(taskDescription.getNavigationBarColor()));
                        pw.print(prefix); pw.print(" backgroundColorFloating=");
                        pw.println(Integer.toHexString(
                                taskDescription.getBackgroundColorFloating()));
            }
        }
        if (results != null) {
            pw.print(prefix); pw.print("results="); pw.println(results);
        }
        if (pendingResults != null && pendingResults.size() > 0) {
            pw.print(prefix); pw.println("Pending Results:");
            for (WeakReference<PendingIntentRecord> wpir : pendingResults) {
                PendingIntentRecord pir = wpir != null ? wpir.get() : null;
                pw.print(prefix); pw.print("  - ");
                if (pir == null) {
                    pw.println("null");
                } else {
                    pw.println(pir);
                    pir.dump(pw, prefix + "    ");
                }
            }
        }
        if (newIntents != null && newIntents.size() > 0) {
            pw.print(prefix); pw.println("Pending New Intents:");
            for (int i=0; i<newIntents.size(); i++) {
                Intent intent = newIntents.get(i);
                pw.print(prefix); pw.print("  - ");
                if (intent == null) {
                    pw.println("null");
                } else {
                    pw.println(intent.toShortString(false, true, false, false));
                }
            }
        }
        if (mPendingOptions != null) {
            pw.print(prefix); pw.print("pendingOptions="); pw.println(mPendingOptions);
        }
        if (mPendingRemoteAnimation != null) {
            pw.print(prefix);
            pw.print("pendingRemoteAnimationCallingPid=");
            pw.println(mPendingRemoteAnimation.getCallingPid());
        }
        if (mPendingRemoteTransition != null) {
            pw.print(prefix + " pendingRemoteTransition="
                    + mPendingRemoteTransition.getRemoteTransition());
        }
        if (appTimeTracker != null) {
            appTimeTracker.dumpWithHeader(pw, prefix, false);
        }
        if (uriPermissions != null) {
            uriPermissions.dump(pw, prefix);
        }
        pw.print(prefix); pw.print("launchFailed="); pw.print(launchFailed);
                pw.print(" launchCount="); pw.print(launchCount);
                pw.print(" lastLaunchTime=");
                if (lastLaunchTime == 0) pw.print("0");
                else TimeUtils.formatDuration(lastLaunchTime, now, pw);
                pw.println();
        if (mLaunchCookie != null) {
            pw.print(prefix);
            pw.print("launchCookie=");
            pw.println(mLaunchCookie);
        }
        if (mLaunchRootTask != null) {
            pw.print(prefix);
            pw.print("mLaunchRootTask=");
            pw.println(mLaunchRootTask);
        }
        pw.print(prefix); pw.print("mHaveState="); pw.print(mHaveState);
                pw.print(" mIcicle="); pw.println(mIcicle);
        pw.print(prefix); pw.print("state="); pw.print(mState);
                pw.print(" delayedResume="); pw.print(delayedResume);
                pw.print(" finishing="); pw.println(finishing);
        pw.print(prefix); pw.print("keysPaused="); pw.print(keysPaused);
                pw.print(" inHistory="); pw.print(inHistory);
                pw.print(" idle="); pw.println(idle);
        pw.print(prefix); pw.print("occludesParent="); pw.print(occludesParent());
                pw.print(" mNoDisplay="); pw.print(mNoDisplay);
                pw.print(" immersive="); pw.print(immersive);
                pw.print(" launchMode="); pw.println(launchMode);
        pw.print(prefix); pw.print("mActivityType=");
                pw.println(activityTypeToString(getActivityType()));
        if (requestedVrComponent != null) {
            pw.print(prefix);
            pw.print("requestedVrComponent=");
            pw.println(requestedVrComponent);
        }
        super.dump(pw, prefix, dumpAll);
        if (mVoiceInteraction) {
            pw.println(prefix + "mVoiceInteraction=true");
        }
        pw.print(prefix); pw.print("mOccludesParent="); pw.println(mOccludesParent);
        pw.print(prefix); pw.print("overrideOrientation=");
        pw.println(ActivityInfo.screenOrientationToString(getOverrideOrientation()));
        pw.print(prefix); pw.print("requestedOrientation=");
        pw.println(ActivityInfo.screenOrientationToString(super.getOverrideOrientation()));
        pw.println(prefix + "mVisibleRequested=" + mVisibleRequested
                + " mVisible=" + mVisible + " mClientVisible=" + isClientVisible()
                + ((mDeferHidingClient) ? " mDeferHidingClient=" + mDeferHidingClient : "")
                + " reportedDrawn=" + mReportedDrawn + " reportedVisible=" + reportedVisible);
        if (paused) {
            pw.print(prefix); pw.print("paused="); pw.println(paused);
        }
        if (mAppStopped) {
            pw.print(prefix); pw.print("mAppStopped="); pw.println(mAppStopped);
        }
        if (mNumInterestingWindows != 0 || mNumDrawnWindows != 0 || allDrawn) {
            pw.print(prefix); pw.print("mNumInterestingWindows=");
            pw.print(mNumInterestingWindows);
            pw.print(" mNumDrawnWindows="); pw.print(mNumDrawnWindows);
            pw.print(" allDrawn="); pw.print(allDrawn);
            pw.println(")");
        }
        if (mStartingData != null || firstWindowDrawn) {
            pw.print(prefix); pw.print("startingData="); pw.print(mStartingData);
            pw.print(" firstWindowDrawn="); pw.println(firstWindowDrawn);
        }
        if (mStartingWindow != null || mStartingData != null || mStartingSurface != null
                || startingMoved || mVisibleSetFromTransferredStartingWindow) {
            pw.print(prefix); pw.print("startingWindow="); pw.print(mStartingWindow);
            pw.print(" startingSurface="); pw.print(mStartingSurface);
            pw.print(" startingDisplayed="); pw.print(isStartingWindowDisplayed());
            pw.print(" startingMoved="); pw.print(startingMoved);
            pw.println(" mVisibleSetFromTransferredStartingWindow="
                    + mVisibleSetFromTransferredStartingWindow);
        }
        if (mPendingRelaunchCount != 0) {
            pw.print(prefix); pw.print("mPendingRelaunchCount="); pw.println(mPendingRelaunchCount);
        }
        if (mRemovingFromDisplay) {
            pw.println(prefix + "mRemovingFromDisplay=" + mRemovingFromDisplay);
        }
        if (lastVisibleTime != 0 || nowVisible) {
            pw.print(prefix); pw.print("nowVisible="); pw.print(nowVisible);
                    pw.print(" lastVisibleTime=");
                    if (lastVisibleTime == 0) pw.print("0");
                    else TimeUtils.formatDuration(lastVisibleTime, now, pw);
                    pw.println();
        }
        if (mDeferHidingClient) {
            pw.println(prefix + "mDeferHidingClient=" + mDeferHidingClient);
        }
        if (mServiceConnectionsHolder != null) {
            pw.print(prefix); pw.print("connections="); pw.println(mServiceConnectionsHolder);
        }
        if (info != null) {
            pw.println(prefix + "resizeMode=" + ActivityInfo.resizeModeToString(info.resizeMode));
            pw.println(prefix + "mLastReportedMultiWindowMode=" + mLastReportedMultiWindowMode
                    + " mLastReportedPictureInPictureMode=" + mLastReportedPictureInPictureMode);
            if (info.supportsPictureInPicture()) {
                pw.println(prefix + "supportsPictureInPicture=" + info.supportsPictureInPicture());
                pw.println(prefix + "supportsEnterPipOnTaskSwitch: "
                        + supportsEnterPipOnTaskSwitch);
                pw.println(prefix + "mPauseSchedulePendingForPip=" + mPauseSchedulePendingForPip);
            }
            if (getMaxAspectRatio() != 0) {
                pw.println(prefix + "maxAspectRatio=" + getMaxAspectRatio());
            }
            final float minAspectRatio = getMinAspectRatio();
            if (minAspectRatio != 0) {
                pw.println(prefix + "minAspectRatio=" + minAspectRatio);
            }
            if (minAspectRatio != info.getManifestMinAspectRatio()) {
                // Log the fact that we've overridden the min aspect ratio from the manifest
                pw.println(prefix + "manifestMinAspectRatio="
                        + info.getManifestMinAspectRatio());
            }
            pw.println(
                    prefix + "supportsSizeChanges=" + ActivityInfo.sizeChangesSupportModeToString(
                            mAppCompatController.getSizeCompatModePolicy().supportsSizeChanges()));
            if (info.configChanges != 0) {
                pw.println(prefix + "configChanges=0x" + Integer.toHexString(info.configChanges));
            }
            pw.println(prefix + "neverSandboxDisplayApis=" + info.neverSandboxDisplayApis(
                    sConstrainDisplayApisConfig));
            pw.println(prefix + "alwaysSandboxDisplayApis=" + info.alwaysSandboxDisplayApis(
                    sConstrainDisplayApisConfig));
        }
        if (mLastParentBeforePip != null) {
            pw.println(prefix + "lastParentTaskIdBeforePip=" + mLastParentBeforePip.mTaskId);
        }
        if (mLaunchIntoPipHostActivity != null) {
            pw.println(prefix + "launchIntoPipHostActivity=" + mLaunchIntoPipHostActivity);
        }
        if (mWaitForEnteringPinnedMode) {
            pw.print(prefix); pw.println("mWaitForEnteringPinnedMode=true");
        }

        mAppCompatController.dump(pw, prefix);
    }

    static boolean dumpActivity(FileDescriptor fd, PrintWriter pw, int index, ActivityRecord r,
            String prefix, String label, boolean complete, boolean brief, boolean client,
            String dumpPackage, boolean needNL, Runnable header, Task lastTask) {
        if (dumpPackage != null && !dumpPackage.equals(r.packageName)) {
            return false;
        }

        final boolean full = !brief && (complete || !r.isInHistory());
        if (needNL) {
            pw.println("");
        }
        if (header != null) {
            header.run();
        }

        String innerPrefix = prefix + "  ";
        String[] args = new String[0];
        if (lastTask != r.getTask()) {
            lastTask = r.getTask();
            pw.print(prefix);
            pw.print(full ? "* " : "  ");
            pw.println(lastTask);
            if (full) {
                lastTask.dump(pw, prefix + "  ");
            } else if (complete) {
                // Complete + brief == give a summary.  Isn't that obvious?!?
                if (lastTask.intent != null) {
                    pw.print(prefix);
                    pw.print("  ");
                    pw.println(lastTask.intent.toInsecureString());
                }
            }
        }
        pw.print(prefix); pw.print(full ? "* " : "    "); pw.print(label);
        pw.print(" #"); pw.print(index); pw.print(": ");
        pw.println(r);
        if (full) {
            r.dump(pw, innerPrefix, true /* dumpAll */);
        } else if (complete) {
            // Complete + brief == give a summary.  Isn't that obvious?!?
            pw.print(innerPrefix);
            pw.println(r.intent.toInsecureString());
            if (r.app != null) {
                pw.print(innerPrefix);
                pw.println(r.app);
            }
        }
        if (client && r.attachedToProcess()) {
            // flush anything that is already in the PrintWriter since the thread is going
            // to write to the file descriptor directly
            pw.flush();
            try {
                TransferPipe tp = new TransferPipe();
                try {
                    r.app.getThread().dumpActivity(
                            tp.getWriteFd(), r.token, innerPrefix, args);
                    // Short timeout, since blocking here can deadlock with the application.
                    tp.go(fd, 2000);
                } finally {
                    tp.kill();
                }
            } catch (IOException e) {
                pw.println(innerPrefix + "Failure while dumping the activity: " + e);
            } catch (RemoteException e) {
                pw.println(innerPrefix + "Got a RemoteException while dumping the activity");
            }
        }
        return true;
    }

    /** Update the saved state of an activity. */
    void setSavedState(@Nullable Bundle savedState) {
        mIcicle = savedState;
        mHaveState = mIcicle != null;
    }

    /**
     * Get the actual Bundle instance of the saved state.
     * @see #hasSavedState() for checking if the record has saved state.
     */
    @Nullable Bundle getSavedState() {
        return mIcicle;
    }

    /**
     * Check if the activity has saved state.
     * @return {@code true} if the client reported a non-empty saved state from last onStop(), or
     *         if this record was just created and the client is yet to be launched and resumed.
     */
    boolean hasSavedState() {
        return mHaveState;
    }

    /** @return The actual PersistableBundle instance of the saved persistent state. */
    @Nullable PersistableBundle getPersistentSavedState() {
        return mPersistentState;
    }

    void updateApplicationInfo(ApplicationInfo aInfo) {
        info.applicationInfo = aInfo;
    }

    void setSizeConfigurations(SizeConfigurationBuckets sizeConfigurations) {
        mSizeConfigurations = sizeConfigurations;
    }

    private void scheduleActivityMovedToDisplay(int displayId, @NonNull Configuration config,
            @NonNull ActivityWindowInfo activityWindowInfo) {
        if (!attachedToProcess()) {
            ProtoLog.w(WM_DEBUG_SWITCH, "Can't report activity moved "
                    + "to display - client not running, activityRecord=%s, displayId=%d",
                    this, displayId);
            return;
        }
        ProtoLog.v(WM_DEBUG_SWITCH, "Reporting activity moved to "
                        + "display, activityRecord=%s, displayId=%d, config=%s", this, displayId,
                config);

        final MoveToDisplayItem item =
                new MoveToDisplayItem(token, displayId, config, activityWindowInfo);
        try {
            mAtmService.getLifecycleManager().scheduleTransactionItem(app.getThread(), item);
        } catch (RemoteException e) {
            // TODO(b/323801078): remove Exception when cleanup
            // If process died, whatever.
        }
    }

    private void scheduleConfigurationChanged(@NonNull Configuration config,
            @NonNull ActivityWindowInfo activityWindowInfo) {
        if (!attachedToProcess()) {
            ProtoLog.w(WM_DEBUG_CONFIGURATION, "Can't report activity configuration "
                    + "update - client not running, activityRecord=%s", this);
            return;
        }
        ProtoLog.v(WM_DEBUG_CONFIGURATION, "Sending new config to %s, "
                + "config: %s", this, config);

        final ActivityConfigurationChangeItem item =
                new ActivityConfigurationChangeItem(token, config, activityWindowInfo);
        try {
            mAtmService.getLifecycleManager().scheduleTransactionItem(app.getThread(), item);
        } catch (RemoteException e) {
            // TODO(b/323801078): remove Exception when cleanup
            // If process died, whatever.
        }
    }

    boolean scheduleTopResumedActivityChanged(boolean onTop) {
        if (!attachedToProcess() || isState(DESTROYING, DESTROYED)) {
            ProtoLog.w(WM_DEBUG_STATES,
                    "Can't report activity position update - client not running, "
                            + "activityRecord=%s", this);
            return false;
        }
        if (onTop) {
            app.addToPendingTop();
        }
        ProtoLog.v(WM_DEBUG_STATES, "Sending position change to %s, onTop: %b",
                this, onTop);

        final TopResumedActivityChangeItem item = new TopResumedActivityChangeItem(token, onTop);
        try {
            return mAtmService.getLifecycleManager().scheduleTransactionItem(app.getThread(), item);
        } catch (RemoteException e) {
            // TODO(b/323801078): remove Exception when cleanup
            // If process died, whatever.
            Slog.w(TAG, "Failed to send top-resumed=" + onTop + " to " + this, e);
            return false;
        }
    }

    void updateMultiWindowMode() {
        if (task == null || task.getRootTask() == null || !attachedToProcess()) {
            return;
        }

        // An activity is considered to be in multi-window mode if its task isn't fullscreen.
        final boolean inMultiWindowMode = inMultiWindowMode();
        if (inMultiWindowMode != mLastReportedMultiWindowMode) {
            if (!inMultiWindowMode && mLastReportedPictureInPictureMode) {
                updatePictureInPictureMode(null, false);
            } else {
                mLastReportedMultiWindowMode = inMultiWindowMode;
                ensureActivityConfiguration();
            }
        }
    }

    void updatePictureInPictureMode(Rect targetRootTaskBounds, boolean forceUpdate) {
        if (task == null || task.getRootTask() == null || !attachedToProcess()) {
            return;
        }

        final boolean inPictureInPictureMode =
                inPinnedWindowingMode() && targetRootTaskBounds != null;
        if (inPictureInPictureMode != mLastReportedPictureInPictureMode || forceUpdate) {
            // Picture-in-picture mode changes also trigger a multi-window mode change as well, so
            // update that here in order. Set the last reported MW state to the same as the PiP
            // state since we haven't yet actually resized the task (these callbacks need to
            // precede the configuration change from the resize.)
            mLastReportedPictureInPictureMode = inPictureInPictureMode;
            mLastReportedMultiWindowMode = inPictureInPictureMode;
            if (forceUpdate || !isPip2ExperimentEnabled()) {
                // PiP2 should handle sending out the configuration as a part of Shell Transitions.
                ensureActivityConfiguration(true /* ignoreVisibility */);
            }
            if (inPictureInPictureMode && findMainWindow() == null
                    && task.topRunningActivity() == this) {
                // Prevent malicious app entering PiP without valid WindowState, which can in turn
                // result a non-touchable PiP window since the InputConsumer for PiP requires it.
                EventLog.writeEvent(0x534e4554, "265293293", -1, "");
                removeImmediately();
            }
        }
    }

    Task getTask() {
        return task;
    }

    @Nullable
    TaskFragment getTaskFragment() {
        WindowContainer parent = getParent();
        return parent != null ? parent.asTaskFragment() : null;
    }

    /** Whether we should prepare a transition for this {@link ActivityRecord} parent change. */
    private boolean shouldStartChangeTransition(
            @Nullable TaskFragment newParent, @Nullable TaskFragment oldParent) {
        if (newParent == null || oldParent == null || !canStartChangeTransition()) {
            return false;
        }

        final boolean isInPip2 = ActivityTaskManagerService.isPip2ExperimentEnabled()
                && inPinnedWindowingMode();
        if (!newParent.isOrganizedTaskFragment() && !isInPip2) {
            // Parent TaskFragment isn't associated with a TF organizer and we are not in PiP2,
            // so do not allow for initializeChangeTransition() on parent changes
            return false;
        }
        // Transition change for the activity moving into TaskFragment of different bounds.
        return !newParent.getBounds().equals(oldParent.getBounds());
    }

    @Override
    boolean canStartChangeTransition() {
        final Task task = getTask();
        // Skip change transition when the Task is drag resizing.
        return task != null && !task.isDragResizing() && super.canStartChangeTransition();
    }

    @Override
    void onParentChanged(ConfigurationContainer rawNewParent, ConfigurationContainer rawOldParent) {
        final TaskFragment oldParent = (TaskFragment) rawOldParent;
        final TaskFragment newParent = (TaskFragment) rawNewParent;
        final Task oldTask = oldParent != null ? oldParent.getTask() : null;
        final Task newTask = newParent != null ? newParent.getTask() : null;
        this.task = newTask;

        if (shouldStartChangeTransition(newParent, oldParent)) {
            mTransitionController.collectVisibleChange(this);
        }

        super.onParentChanged(newParent, oldParent);

        if (isPersistable()) {
            if (oldTask != null) {
                mAtmService.notifyTaskPersisterLocked(oldTask, false);
            }
            if (newTask != null) {
                mAtmService.notifyTaskPersisterLocked(newTask, false);
            }
        }

        if (oldParent == null && newParent != null) {
            // First time we are adding the activity to the system.
            mVoiceInteraction = newTask.voiceSession != null;

            // TODO(b/36505427): Maybe this call should be moved inside
            // updateOverrideConfiguration()
            newTask.updateOverrideConfigurationFromLaunchBounds();
            // When an activity is started directly into a split-screen fullscreen root task, we
            // need to update the initial multi-window modes so that the callbacks are scheduled
            // correctly when the user leaves that mode.
            mLastReportedMultiWindowMode = inMultiWindowMode();
            mLastReportedPictureInPictureMode = inPinnedWindowingMode();
        }

        final Task rootTask = getRootTask();
        if (task == mLastParentBeforePip && task != null) {
            // Notify the TaskFragmentOrganizer that the activity is reparented back from pip.
            mAtmService.mWindowOrganizerController.mTaskFragmentOrganizerController
                    .onActivityReparentedToTask(this);
            // Activity's reparented back from pip, clear the links once established
            clearLastParentBeforePip();
        }

        updateColorTransform();

        if (oldParent != null) {
            oldParent.cleanUpActivityReferences(this);
            // Clear the state as this activity is removed from its old parent.
            mRequestedLaunchingTaskFragmentToken = null;
        }

        if (newParent != null) {
            if (isState(RESUMED)) {
                newParent.setResumedActivity(this, "onParentChanged");
            }
            mAppCompatController.getTransparentPolicy().start();
            if (mState == INITIALIZING && isRestrictedFixedOrientation(info.screenOrientation)) {
                Slog.i(TAG, "Ignoring manifest-declared fixed orientation "
                        + ActivityInfo.screenOrientationToString(info.screenOrientation)
                        + " of " + this + " since target sdk 36");
            }
        }

        if (rootTask != null && rootTask.topRunningActivity() == this) {
            // make ensure the TaskOrganizer still works after re-parenting
            if (firstWindowDrawn) {
                rootTask.setHasBeenVisible(true);
            }
        }

        // Update the input mode if the embedded mode is changed.
        updateUntrustedEmbeddingInputProtection();
    }

    @Override
    void setSurfaceControl(SurfaceControl sc) {
        super.setSurfaceControl(sc);
        if (sc != null) {
            mLastDropInputMode = DropInputMode.NONE;
            updateUntrustedEmbeddingInputProtection();
        }
    }

    /** Sets if all input will be dropped as a protection during the client-driven animation. */
    void setDropInputForAnimation(boolean isInputDroppedForAnimation) {
        if (mIsInputDroppedForAnimation == isInputDroppedForAnimation) {
            return;
        }
        mIsInputDroppedForAnimation = isInputDroppedForAnimation;
        updateUntrustedEmbeddingInputProtection();
    }

    /**
     * Sets to drop input when obscured to activity if it is embedded in untrusted mode.
     *
     * Although the untrusted embedded activity should be invisible when behind other overlay,
     * theoretically even if this activity is the top most, app can still move surface of activity
     * below it to the top. As a result, we want to update the input mode to drop when obscured for
     * all untrusted activities.
     */
    private void updateUntrustedEmbeddingInputProtection() {
        if (getSurfaceControl() == null) {
            return;
        }
        if (mIsInputDroppedForAnimation) {
            // Disable all input during the animation.
            setDropInputMode(DropInputMode.ALL);
        } else if (isEmbeddedInUntrustedMode()) {
            // Set drop input to OBSCURED when untrusted embedded.
            setDropInputMode(DropInputMode.OBSCURED);
        } else {
            // Reset drop input mode when this activity is not embedded in untrusted mode.
            setDropInputMode(DropInputMode.NONE);
        }
    }

    @VisibleForTesting
    void setDropInputMode(@DropInputMode int mode) {
        if (mLastDropInputMode != mode) {
            mLastDropInputMode = mode;
            mWmService.mTransactionFactory.get()
                    .setDropInputMode(getSurfaceControl(), mode)
                    .apply();
        }
    }

    private boolean isEmbeddedInUntrustedMode() {
        final TaskFragment organizedTaskFragment = getOrganizedTaskFragment();
        if (organizedTaskFragment == null) {
            // Not embedded.
            return false;
        }
        // Check if trusted.
        return !organizedTaskFragment.isAllowedToEmbedActivityInTrustedMode(this);
    }

    boolean canAutoEnterPip() {
        // beforeStopping=false since the actual pip-ing will take place after startPausing()
        final boolean activityCanPip = checkEnterPictureInPictureState(
                "startActivityUnchecked", false /* beforeStopping */);

        // check if this activity is about to auto-enter pip
        return activityCanPip && pictureInPictureArgs != null
                && pictureInPictureArgs.isAutoEnterEnabled();
    }

    /**
     * Sets {@link #mLastParentBeforePip} to the current parent Task, it's caller's job to ensure
     * {@link #getTask()} is set before this is called.
     *
     * @param launchIntoPipHostActivity {@link ActivityRecord} as the host Activity for the
     *        launch-int-pip Activity see also {@link #mLaunchIntoPipHostActivity}.
     */
    void setLastParentBeforePip(@Nullable ActivityRecord launchIntoPipHostActivity) {
        mLastParentBeforePip = (launchIntoPipHostActivity == null)
                ? getTask()
                : launchIntoPipHostActivity.getTask();
        mLastParentBeforePip.mChildPipActivity = this;
        mLaunchIntoPipHostActivity = launchIntoPipHostActivity;
        final TaskFragment organizedTf = launchIntoPipHostActivity == null
                ? getOrganizedTaskFragment()
                : launchIntoPipHostActivity.getOrganizedTaskFragment();
        mLastTaskFragmentOrganizerBeforePip = organizedTf != null
                ? organizedTf.getTaskFragmentOrganizer()
                : null;
        if (organizedTf != null
                // Not necessary for content pip.
                && launchIntoPipHostActivity == null) {
            mLastEmbeddedParentTfTokenBeforePip = organizedTf.getFragmentToken();
        }
    }

    void clearLastParentBeforePip() {
        if (mLastParentBeforePip != null) {
            mLastParentBeforePip.mChildPipActivity = null;
            mLastParentBeforePip = null;
        }
        mLaunchIntoPipHostActivity = null;
        mLastTaskFragmentOrganizerBeforePip = null;
        mLastEmbeddedParentTfTokenBeforePip = null;
    }

    @Nullable Task getLastParentBeforePip() {
        return mLastParentBeforePip;
    }

    @Nullable IBinder getLastEmbeddedParentTfTokenBeforePip() {
        return mLastEmbeddedParentTfTokenBeforePip;
    }

    @Nullable ActivityRecord getLaunchIntoPipHostActivity() {
        return mLaunchIntoPipHostActivity;
    }

    private void updateColorTransform() {
        if (mSurfaceControl != null && mLastAppSaturationInfo != null) {
            getPendingTransaction().setColorTransform(mSurfaceControl,
                    mLastAppSaturationInfo.mMatrix, mLastAppSaturationInfo.mTranslation);
            mWmService.scheduleAnimationLocked();
        }
    }

    @Override
    void onDisplayChanged(DisplayContent dc) {
        DisplayContent prevDc = mDisplayContent;
        super.onDisplayChanged(dc);
        if (prevDc == mDisplayContent) {
            return;
        }

        mDisplayContent.onRunningActivityChanged();

        if (prevDc == null) {
            return;
        }
        prevDc.onRunningActivityChanged();
        prevDc.getDisplayPolicy().removeRelaunchingApp(this);

        if (prevDc.mFocusedApp == this) {
            prevDc.setFocusedApp(null);
            if (dc.getTopMostActivity() == this) {
                dc.setFocusedApp(this);
            }
        }

        mAppCompatController.getLetterboxPolicy().onMovedToDisplay(mDisplayContent.getDisplayId());
        mAppCompatController.getDisplayCompatModePolicy().onMovedToDisplay();
    }

    void layoutLetterboxIfNeeded(WindowState winHint) {
        mAppCompatController.getLetterboxPolicy().start(winHint);
    }

    boolean hasWallpaperBackgroundForLetterbox() {
        return mAppCompatController.getLetterboxOverrides().hasWallpaperBackgroundForLetterbox();
    }

    void updateLetterboxSurfaceIfNeeded(WindowState winHint, Transaction t) {
        mAppCompatController.getLetterboxPolicy()
                .updateLetterboxSurfaceIfNeeded(winHint, t, getPendingTransaction());
    }

    void updateLetterboxSurfaceIfNeeded(WindowState winHint) {
        mAppCompatController.getLetterboxPolicy().updateLetterboxSurfaceIfNeeded(winHint);
    }

    /** Gets the letterbox insets. The insets will be empty if there is no letterbox. */
    Rect getLetterboxInsets() {
        return mAppCompatController.getLetterboxPolicy().getLetterboxInsets();
    }

    /** Gets the inner bounds of letterbox. The bounds will be empty if there is no letterbox. */
    void getLetterboxInnerBounds(Rect outBounds) {
        mAppCompatController.getLetterboxPolicy().getLetterboxInnerBounds(outBounds);
    }

    /**
     * @return {@code true} if bar shown within a given rectangle is allowed to be fully transparent
     *     when the current activity is displayed.
     */
    boolean isFullyTransparentBarAllowed(Rect rect) {
        return mAppCompatController.getLetterboxPolicy().isFullyTransparentBarAllowed(rect);
    }

    private static class Token extends Binder {
        @NonNull WeakReference<ActivityRecord> mActivityRef;

        @Override
        public String toString() {
            return "Token{" + Integer.toHexString(System.identityHashCode(this)) + " "
                    + mActivityRef.get() + "}";
        }
    }

    /** Gets the corresponding record by the token. Note that it may not exist in the hierarchy. */
    @Nullable
    static ActivityRecord forToken(IBinder token) {
        if (token == null) return null;
        final Token activityToken;
        try {
            activityToken = (Token) token;
        } catch (ClassCastException e) {
            Slog.w(TAG, "Bad activity token: " + token, e);
            return null;
        }
        return activityToken.mActivityRef.get();
    }

    static @Nullable ActivityRecord forTokenLocked(IBinder token) {
        final ActivityRecord r = forToken(token);
        return r == null || r.getRootTask() == null ? null : r;
    }

    static boolean isResolverActivity(String className) {
        return ResolverActivity.class.getName().equals(className);
    }

    boolean isResolverOrDelegateActivity() {
        return isResolverActivity(mActivityComponent.getClassName()) || Objects.equals(
                mActivityComponent, mAtmService.mTaskSupervisor.getSystemChooserActivity());
    }

    boolean isResolverOrChildActivity() {
        if (!"android".equals(packageName)) {
            return false;
        }
        try {
            return ResolverActivity.class.isAssignableFrom(
                    Object.class.getClassLoader().loadClass(mActivityComponent.getClassName()));
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    boolean hasCaller(IBinder callerToken) {
        return mCallerState.hasCaller(callerToken);
    }

    int getCallerUid(IBinder callerToken) {
        return mCallerState.getUid(callerToken);
    }

    String getCallerPackage(IBinder callerToken) {
        return mCallerState.getPackage(callerToken);
    }

    boolean isCallerShareIdentityEnabled(IBinder callerToken) {
        return mCallerState.isShareIdentityEnabled(callerToken);
    }

    void computeInitialCallerInfo() {
        computeCallerInfo(initialCallerInfoAccessToken, intent, launchedFromUid,
                launchedFromPackage, mShareIdentity);
    }

    void computeCallerInfo(IBinder callerToken, Intent intent, int callerUid,
            String callerPackageName, boolean isCallerShareIdentityEnabled) {
        mCallerState.computeCallerInfo(callerToken, intent, callerUid, callerPackageName,
                isCallerShareIdentityEnabled);
    }

    boolean checkContentUriPermission(IBinder callerToken, GrantUri grantUri, int modeFlags) {
        return mCallerState.checkContentUriPermission(callerToken, grantUri, modeFlags);
    }

    private ActivityRecord(ActivityTaskManagerService _service, WindowProcessController _caller,
            int _launchedFromPid, int _launchedFromUid, String _launchedFromPackage,
            @Nullable String _launchedFromFeature, Intent _intent, String _resolvedType,
            ActivityInfo aInfo, Configuration _configuration, ActivityRecord _resultTo,
            String _resultWho, int _reqCode, boolean _componentSpecified,
            boolean _rootVoiceInteraction, ActivityTaskSupervisor supervisor,
            ActivityOptions options, ActivityRecord sourceRecord, PersistableBundle persistentState,
            TaskDescription _taskDescription, long _createTime) {
        super(_service.mWindowManager, new Token(), TYPE_APPLICATION, true,
                null /* displayContent */, false /* ownerCanManageAppTokens */);

        mAtmService = _service;
        ((Token) token).mActivityRef = new WeakReference<>(this);
        info = aInfo;
        mUserId = UserHandle.getUserId(info.applicationInfo.uid);
        packageName = info.applicationInfo.packageName;
        intent = _intent;

        // If the class name in the intent doesn't match that of the target, this is probably an
        // alias. We have to create a new ComponentName object to keep track of the real activity
        // name, so that FLAG_ACTIVITY_CLEAR_TOP is handled properly.
        if (info.targetActivity == null
                || (info.targetActivity.equals(intent.getComponent().getClassName())
                && (info.launchMode == LAUNCH_MULTIPLE
                || info.launchMode == LAUNCH_SINGLE_TOP))) {
            mActivityComponent = intent.getComponent();
        } else {
            mActivityComponent =
                    new ComponentName(info.packageName, info.targetActivity);
        }

        // Don't move below setActivityType since it triggers onConfigurationChange ->
        // resolveOverrideConfiguration that requires having mAppCompatController initialised.
        // Don't move below setOrientation(info.screenOrientation) since it triggers
        // getOverrideOrientation that requires having mAppCompatController initialised.
        mAppCompatController = new AppCompatController(mWmService, this);
        mResolveConfigHint = new TaskFragment.ConfigOverrideHint();
        if (mWmService.mFlags.mInsetsDecoupledConfiguration) {
            // When the stable configuration is the default behavior, override for the legacy apps
            // without forward override flag.
            mResolveConfigHint.mUseOverrideInsetsForConfig =
                    !info.isChangeEnabled(INSETS_DECOUPLED_CONFIGURATION_ENFORCED)
                            && !info.isChangeEnabled(
                                    OVERRIDE_ENABLE_INSETS_DECOUPLED_CONFIGURATION);
        } else {
            // When the stable configuration is not the default behavior, forward overriding the
            // listed apps.
            mResolveConfigHint.mUseOverrideInsetsForConfig =
                    info.isChangeEnabled(OVERRIDE_ENABLE_INSETS_DECOUPLED_CONFIGURATION);
        }

        mTargetSdk = info.applicationInfo.targetSdkVersion;

        final UserManagerInternal umi = LocalServices.getService(UserManagerInternal.class);
        final UserProperties properties = umi.getUserProperties(mUserId);
        mIsUserAlwaysVisible =  properties != null && properties.getAlwaysVisible();

        mShowForAllUsers = (info.flags & FLAG_SHOW_FOR_ALL_USERS) != 0 || mIsUserAlwaysVisible;
        setOverrideOrientation(info.screenOrientation);
        mRotationAnimationHint = info.rotationAnimation;

        mShowWhenLocked = (aInfo.flags & ActivityInfo.FLAG_SHOW_WHEN_LOCKED) != 0;
        mInheritShownWhenLocked = (aInfo.privateFlags & FLAG_INHERIT_SHOW_WHEN_LOCKED) != 0;
        mTurnScreenOn = (aInfo.flags & FLAG_TURN_SCREEN_ON) != 0;

        int realTheme = info.getThemeResource();
        if (realTheme == Resources.ID_NULL) {
            realTheme = aInfo.applicationInfo.targetSdkVersion < HONEYCOMB
                    ? android.R.style.Theme : android.R.style.Theme_Holo;
        }

        final WindowStyle style = mAtmService.getWindowStyle(packageName, realTheme, mUserId);
        mWindowStyle = style;
        if (style != null) {
            mOccludesParent = !(style.isTranslucent() || style.isFloating())
                    // This style is propagated to the main window attributes with
                    // FLAG_SHOW_WALLPAPER from PhoneWindow#generateLayout.
                    || style.showWallpaper();
            mStyleFillsParent = mOccludesParent;
            mNoDisplay = style.noDisplay();
            mOptOutEdgeToEdge = style.optOutEdgeToEdge() && PhoneWindow.isOptOutEdgeToEdgeEnabled(
                    aInfo.applicationInfo, false /* local */);
        } else {
            mStyleFillsParent = mOccludesParent = true;
            mNoDisplay = false;
            mOptOutEdgeToEdge = false;
        }

        if (options != null) {
            mLaunchTaskBehind = options.getLaunchTaskBehind();

            final int rotationAnimation = options.getRotationAnimationHint();
            // Only override manifest supplied option if set.
            if (rotationAnimation >= 0) {
                mRotationAnimationHint = rotationAnimation;
            }

            if (options.getLaunchIntoPipParams() != null) {
                pictureInPictureArgs = options.getLaunchIntoPipParams();
                if (sourceRecord != null) {
                    adjustPictureInPictureParamsIfNeeded(sourceRecord.getBounds());
                }
            }

            mOverrideTaskTransition = options.getOverrideTaskTransition();
            mDismissKeyguardIfInsecure = options.getDismissKeyguardIfInsecure();
            mShareIdentity = options.isShareIdentityEnabled();
        }

        ColorDisplayService.ColorDisplayServiceInternal cds = LocalServices.getService(
                ColorDisplayService.ColorDisplayServiceInternal.class);
        cds.attachColorTransformController(packageName, mUserId,
                new WeakReference<>(mColorTransformController));

        mRootWindowContainer = _service.mRootWindowContainer;
        launchedFromPid = _launchedFromPid;
        launchedFromUid = _launchedFromUid;
        launchedFromPackage = _launchedFromPackage;
        launchedFromFeatureId = _launchedFromFeature;
        mLaunchSourceType = determineLaunchSourceType(_launchedFromUid, _caller);
        shortComponentName = _intent.getComponent().flattenToShortString();
        resolvedType = _resolvedType;
        componentSpecified = _componentSpecified;
        rootVoiceInteraction = _rootVoiceInteraction;
        mLastReportedConfiguration = new MergedConfiguration(_configuration);
        resultTo = _resultTo;
        resultWho = _resultWho;
        requestCode = _reqCode;
        setState(INITIALIZING, "ActivityRecord ctor");
        launchFailed = false;
        delayedResume = false;
        finishing = false;
        keysPaused = false;
        inHistory = false;
        nowVisible = false;
        super.setClientVisible(true);
        idle = false;
        hasBeenLaunched = false;
        launching = false;
        translucentWindowLaunch = false;
        mTaskSupervisor = supervisor;

        info.taskAffinity = computeTaskAffinity(info.taskAffinity, info.applicationInfo.uid);
        taskAffinity = info.taskAffinity;
        final String uid = Integer.toString(info.applicationInfo.uid);
        if (info.windowLayout != null && info.windowLayout.windowLayoutAffinity != null
                && !info.windowLayout.windowLayoutAffinity.startsWith(uid)) {
            info.windowLayout.windowLayoutAffinity =
                    uid + ":" + info.windowLayout.windowLayoutAffinity;
        }
        // Initialize once, when we know all system services are available.
        if (sConstrainDisplayApisConfig == null) {
            sConstrainDisplayApisConfig = new ConstrainDisplayApisConfig();
        }
        stateNotNeeded = (aInfo.flags & FLAG_STATE_NOT_NEEDED) != 0;
        theme = aInfo.getThemeResource();
        if ((aInfo.flags & FLAG_MULTIPROCESS) != 0 && _caller != null
                && (aInfo.applicationInfo.uid == SYSTEM_UID
                    || aInfo.applicationInfo.uid == _caller.mInfo.uid)) {
            processName = _caller.mName;
        } else {
            processName = aInfo.processName;
        }

        if ((aInfo.flags & FLAG_EXCLUDE_FROM_RECENTS) != 0) {
            intent.addFlags(FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        }

        launchMode = aInfo.launchMode;

        setActivityType(_componentSpecified, _launchedFromUid, _intent, options, sourceRecord);

        immersive = (aInfo.flags & FLAG_IMMERSIVE) != 0;

        requestedVrComponent = (aInfo.requestedVrComponent == null) ?
                null : ComponentName.unflattenFromString(aInfo.requestedVrComponent);

        lockTaskLaunchMode = getLockTaskLaunchMode(aInfo, options);

        if (options != null) {
            setOptions(options);
            // The result receiver is the transition receiver, which will handle the shared element
            // exit transition.
            mHasSceneTransition = options.getAnimationType() == ANIM_SCENE_TRANSITION
                    && options.getSceneTransitionInfo() != null
                    && options.getSceneTransitionInfo().getResultReceiver() != null;
            final PendingIntent usageReport = options.getUsageTimeReport();
            if (usageReport != null) {
                appTimeTracker = new AppTimeTracker(usageReport);
            }
            // Gets launch task display area and display id from options. Returns
            // null/INVALID_DISPLAY if not set.
            final WindowContainerToken daToken = options.getLaunchTaskDisplayArea();
            mHandoverTaskDisplayArea = daToken != null
                    ? (TaskDisplayArea) WindowContainer.fromBinder(daToken.asBinder()) : null;
            mHandoverLaunchDisplayId = options.getLaunchDisplayId();
            mLaunchCookie = options.getLaunchCookie();
            mLaunchRootTask = options.getLaunchRootTask();
            if (mLaunchCookie != null) {
                ProtoLog.v(WM_DEBUG_WINDOW_TRANSITIONS,
                        "Activity created with launch cookie=%s act=%s(%d)",
                        mLaunchCookie, packageName, System.identityHashCode(this));
            }
        } else {
            mHasSceneTransition = false;
        }

        mPersistentState = persistentState;
        taskDescription = _taskDescription;

        shouldDockBigOverlays = mWmService.mContext.getResources()
                .getBoolean(R.bool.config_dockBigOverlayWindows);

        if (_createTime > 0) {
            createTime = _createTime;
        }
        mAtmService.mPackageConfigPersister.updateConfigIfNeeded(this, mUserId, packageName);

        final boolean appOptInTouchPassThrough =
                options != null && options.isAllowPassThroughOnTouchOutside();
        mActivityRecordInputSink = new ActivityRecordInputSink(
                this, sourceRecord, appOptInTouchPassThrough);

        mAppActivityEmbeddingSplitsEnabled = isAppActivityEmbeddingSplitsEnabled();
        mAllowUntrustedEmbeddingStateSharing = getAllowUntrustedEmbeddingStateSharingProperty();

        mOptInOnBackInvoked = WindowOnBackInvokedDispatcher
                .isOnBackInvokedCallbackEnabled(info, info.applicationInfo,
                        () -> {
                            Context appContext = null;
                            try {
                                appContext = mAtmService.mContext.createPackageContextAsUser(
                                        info.packageName, CONTEXT_RESTRICTED,
                                        UserHandle.of(mUserId));
                                appContext.setTheme(theme);
                            } catch (PackageManager.NameNotFoundException ignore) {
                            }
                            return appContext;
                        });
        mCallerState = new ActivityCallerState(mAtmService);

        mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
    }

    private boolean isAppActivityEmbeddingSplitsEnabled() {
        if (!hasWindowExtensionsEnabled()) {
            // WM Extensions disabled.
            return false;
        }
        if (ACTIVITY_EMBEDDING_GUARD_WITH_ANDROID_15 && !CompatChanges.isChangeEnabled(
                ENABLE_ACTIVITY_EMBEDDING_FOR_ANDROID_15,
                info.packageName,
                UserHandle.getUserHandleForUid(getUid()))) {
            // Activity Embedding is guarded with Android 15+, but this app is not qualified.
            return false;
        }
        try {
            return mAtmService.mContext.getPackageManager()
                    .getPropertyAsUser(PROPERTY_ACTIVITY_EMBEDDING_SPLITS_ENABLED, packageName,
                            null /* className */, mUserId)
                    .getBoolean();
        } catch (PackageManager.NameNotFoundException e) {
            // No such property name.
            return false;
        }
    }

    /**
     * Generate the task affinity with uid and activity launch mode. For b/35954083, Limit task
     * affinity to uid to avoid issues associated with sharing affinity across uids.
     *
     * @param affinity The affinity of the activity.
     * @param uid The user-ID that has been assigned to this application.
     * @return The task affinity
     */
    static String computeTaskAffinity(String affinity, int uid) {
        final String uidStr = Integer.toString(uid);
        if (affinity != null && !affinity.startsWith(uidStr)) {
            affinity = uidStr + ":" + affinity;
        }
        return affinity;
    }

    static int getLockTaskLaunchMode(ActivityInfo aInfo, @Nullable ActivityOptions options) {
        int lockTaskLaunchMode = aInfo.lockTaskLaunchMode;
        // Non-priv apps are not allowed to use always or never, fall back to default
        if (!aInfo.applicationInfo.isPrivilegedApp()
                && (lockTaskLaunchMode == LOCK_TASK_LAUNCH_MODE_ALWAYS
                || lockTaskLaunchMode == LOCK_TASK_LAUNCH_MODE_NEVER)) {
            lockTaskLaunchMode = LOCK_TASK_LAUNCH_MODE_DEFAULT;
        }
        if (options != null) {
            final boolean useLockTask = options.getLockTaskMode();
            if (useLockTask && lockTaskLaunchMode == LOCK_TASK_LAUNCH_MODE_DEFAULT) {
                lockTaskLaunchMode = LOCK_TASK_LAUNCH_MODE_IF_ALLOWLISTED;
            }
        }
        return lockTaskLaunchMode;
    }

    @NonNull InputApplicationHandle getInputApplicationHandle(boolean update) {
        if (mInputApplicationHandle == null) {
            mInputApplicationHandle = new InputApplicationHandle(token, toString(),
                    mInputDispatchingTimeoutMillis);
        } else if (update) {
            final String name = toString();
            if (mInputDispatchingTimeoutMillis != mInputApplicationHandle.dispatchingTimeoutMillis
                    || !name.equals(mInputApplicationHandle.name)) {
                mInputApplicationHandle = new InputApplicationHandle(token, name,
                        mInputDispatchingTimeoutMillis);
            }
        }
        return mInputApplicationHandle;
    }

    @Override
    ActivityRecord asActivityRecord() {
        // I am an activity record!
        return this;
    }

    @Override
    boolean hasActivity() {
        // I am an activity!
        return true;
    }

    void setProcess(WindowProcessController proc) {
        app = proc;
        final ActivityRecord root = task != null ? task.getRootActivity() : null;
        if (root == this) {
            task.setRootProcess(proc);
        }
        proc.addActivityIfNeeded(this);
        mInputDispatchingTimeoutMillis = getInputDispatchingTimeoutMillisLocked(this);

        // Update the associated task fragment after setting the process, since it's required for
        // filtering to only report activities that belong to the same process.
        final TaskFragment tf = getTaskFragment();
        if (tf != null) {
            tf.sendTaskFragmentInfoChanged();
        }
    }

    boolean hasProcess() {
        return app != null;
    }

    boolean attachedToProcess() {
        return hasProcess() && app.hasThread();
    }

    /**
     * Evaluate the theme for a starting window.
     * @param prev Previous activity which may have a starting window.
     * @param originalTheme The original theme which read from activity or application.
     * @param replaceTheme The replace theme which requested from starter.
     * @return Resolved theme.
     */
    private int evaluateStartingWindowTheme(ActivityRecord prev, String pkg, int originalTheme,
            int replaceTheme) {
        // Skip if the package doesn't want a starting window.
        if (!validateStartingWindowTheme(prev, pkg, originalTheme)) {
            return 0;
        }
        int selectedTheme = originalTheme;
        if (replaceTheme != 0 && validateStartingWindowTheme(prev, pkg, replaceTheme)) {
            // allow to replace theme
            selectedTheme = replaceTheme;
        }
        return selectedTheme;
    }

    /**
     * @return Whether this {@link ActivityRecord} was launched from a system surface (e.g
     * Launcher, Notification,...)
     */
    private boolean launchedFromSystemSurface() {
        return mLaunchSourceType == LAUNCH_SOURCE_TYPE_SYSTEM
                || mLaunchSourceType == LAUNCH_SOURCE_TYPE_HOME
                || mLaunchSourceType == LAUNCH_SOURCE_TYPE_SYSTEMUI;
    }

    boolean isLaunchSourceType(@LaunchSourceType int type) {
        return mLaunchSourceType == type;
    }

    void updateLaunchSourceType(int launchFromUid, WindowProcessController caller) {
        mLaunchSourceType = determineLaunchSourceType(launchFromUid, caller);
    }

    @LaunchSourceType
    private int determineLaunchSourceType(int launchFromUid, WindowProcessController caller) {
        if (launchFromUid == Process.SYSTEM_UID || launchFromUid == Process.ROOT_UID) {
            return LAUNCH_SOURCE_TYPE_SYSTEM;
        }
        if (caller != null) {
            if (caller.isHomeProcess()) {
                return LAUNCH_SOURCE_TYPE_HOME;
            }
            if (mAtmService.getSysUiServiceComponentLocked().getPackageName()
                    .equals(caller.mInfo.packageName)) {
                return LAUNCH_SOURCE_TYPE_SYSTEMUI;
            }
        }
        return LAUNCH_SOURCE_TYPE_APPLICATION;
    }

    private boolean validateStartingWindowTheme(ActivityRecord prev, String pkg, int theme) {
        // If this is a translucent window, then don't show a starting window -- the current
        // effect (a full-screen opaque starting window that fades away to the real contents
        // when it is ready) does not work for this.
        ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Checking theme of starting window: 0x%x", theme);
        if (theme == 0) {
            return false;
        }

        final WindowStyle style = theme == this.theme
                ? mWindowStyle : mAtmService.getWindowStyle(pkg, theme, mUserId);
        if (style == null) {
            // Whoops!  App doesn't exist. Um. Okay. We'll just pretend like we didn't
            // see that.
            return false;
        }
        final boolean windowIsTranslucent = style.isTranslucent();
        final boolean windowIsFloating = style.isFloating();
        final boolean windowShowWallpaper = style.showWallpaper();
        final boolean windowDisableStarting = style.disablePreview();
        ProtoLog.v(WM_DEBUG_STARTING_WINDOW,
                "Translucent=%s Floating=%s ShowWallpaper=%s Disable=%s",
                windowIsTranslucent, windowIsFloating, windowShowWallpaper,
                windowDisableStarting);
        // If this activity is launched from system surface, ignore windowDisableStarting
        if (windowIsTranslucent || windowIsFloating) {
            translucentWindowLaunch = true;
            return false;
        }
        if (windowShowWallpaper
                && getDisplayContent().mWallpaperController.getWallpaperTarget() != null) {
            return false;
        }
        if (windowDisableStarting && !launchedFromSystemSurface()) {
            // Check if previous activity can transfer the starting window to this activity.
            return prev != null && prev.getActivityType() == ACTIVITY_TYPE_STANDARD
                    && prev.mTransferringSplashScreenState == TRANSFER_SPLASH_SCREEN_IDLE
                    && (prev.mStartingData != null
                    || (prev.mStartingWindow != null && prev.mStartingSurface != null));
        }
        return true;
    }

    @VisibleForTesting
    boolean addStartingWindow(String pkg, int resolvedTheme, ActivityRecord from, boolean newTask,
            boolean taskSwitch, boolean processRunning, boolean allowTaskSnapshot,
            boolean activityCreated, boolean isSimple,
            boolean activityAllDrawn) {
        // If the display is frozen, we won't do anything until the actual window is
        // displayed so there is no reason to put in the starting window.
        if (!okToDisplay()) {
            return false;
        }

        if (hasStartingWindow()) {
            return false;
        }

        final WindowState mainWin = findMainWindow(false /* includeStartingApp */);
        if (mainWin != null && mainWin.isDrawn()) {
            // App already has a visible window...why would you want a starting window?
            return false;
        }

        final TaskSnapshot snapshot = mWmService.mTaskSnapshotController.getSnapshot(task.mTaskId,
                false /* isLowResolution */);
        final int type = getStartingWindowType(newTask, taskSwitch, processRunning,
                allowTaskSnapshot, activityCreated, activityAllDrawn, snapshot);

        //TODO(191787740) Remove for V+
        final boolean useLegacy = type == STARTING_WINDOW_TYPE_SPLASH_SCREEN
                && mWmService.mStartingSurfaceController.isExceptionApp(packageName, mTargetSdk,
                    () -> {
                        ActivityInfo activityInfo = intent.resolveActivityInfo(
                                mAtmService.mContext.getPackageManager(),
                                PackageManager.GET_META_DATA);
                        return activityInfo != null ? activityInfo.applicationInfo : null;
                    });

        final int typeParameter = StartingSurfaceController
                .makeStartingWindowTypeParameter(newTask, taskSwitch, processRunning,
                        allowTaskSnapshot, activityCreated, isSimple, useLegacy, activityAllDrawn,
                        type, isIconStylePreferred(resolvedTheme), packageName, mUserId);

        if (type == STARTING_WINDOW_TYPE_SNAPSHOT) {
            if (isActivityTypeHome()) {
                // The snapshot of home is only used once because it won't be updated while screen
                // is on (see {@link TaskSnapshotController#screenTurningOff}).
                final Transition transition = mTransitionController.getCollectingTransition();
                if (transition != null && (transition.getFlags()
                        & WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION) == 0) {
                    mWmService.mTaskSnapshotController.removeSnapshotCache(task.mTaskId);
                    // Only use snapshot of home as starting window when unlocking directly.
                    return false;
                }
                // Add a reference before removing snapshot from cache.
                snapshot.addReference(TaskSnapshot.REFERENCE_WRITE_TO_PARCEL);
                mWmService.mTaskSnapshotController.removeSnapshotCache(task.mTaskId);
            }
            return createSnapshot(snapshot, typeParameter);
        }

        // Original theme can be 0 if developer doesn't request any theme. So if resolved theme is 0
        // but original theme is not 0, means this package doesn't want a starting window.
        if (resolvedTheme == 0 && theme != 0) {
            return false;
        }

        if (from != null && transferStartingWindow(from)) {
            return true;
        }

        // There is no existing starting window, and we don't want to create a splash screen, so
        // that's it!
        if (type != STARTING_WINDOW_TYPE_SPLASH_SCREEN) {
            return false;
        }

        ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Creating SplashScreenStartingData");
        mStartingData = new SplashScreenStartingData(mWmService, resolvedTheme, typeParameter);
        scheduleAddStartingWindow();
        return true;
    }

    private boolean createSnapshot(TaskSnapshot snapshot, int typeParams) {
        if (snapshot == null) {
            return false;
        }

        ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Creating SnapshotStartingData");
        mStartingData = new SnapshotStartingData(mWmService, snapshot, typeParams);
        if ((!mStyleFillsParent && task.getChildCount() > 1)
                || task.forAllLeafTaskFragments(TaskFragment::isEmbedded)) {
            // Case 1:
            // If it is moving a Task{[0]=main activity, [1]=translucent activity} to front, use
            // shared starting window so that the transition doesn't need to wait for the activity
            // behind the translucent activity. Also, onFirstWindowDrawn will check all visible
            // activities are drawn in the task to remove the snapshot starting window.
            // Case 2:
            // Associate with the task so if this activity is resized by task fragment later, the
            // starting window can keep the same bounds as the task.
            associateStartingDataWithTask();
        }
        scheduleAddStartingWindow();
        return true;
    }

    private void scheduleAddStartingWindow() {
        ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Add starting %s: startingData=%s",
                this, mStartingData);

        mStartingSurface = mStartingData.createStartingSurface(ActivityRecord.this);
        if (mStartingSurface != null) {
            ProtoLog.v(WM_DEBUG_STARTING_WINDOW,
                    "Added starting %s: startingWindow=%s startingView=%s",
                    ActivityRecord.this, mStartingWindow, mStartingSurface);
        } else {
            ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Surface returned was null: %s",
                    ActivityRecord.this);
        }
    }

    private int getStartingWindowType(boolean newTask, boolean taskSwitch, boolean processRunning,
            boolean allowTaskSnapshot, boolean activityCreated, boolean activityAllDrawn,
            TaskSnapshot snapshot) {
        // A special case that a new activity is launching to an existing task which is moving to
        // front. If the launching activity is the one that started the task, it could be a
        // trampoline that will be always created and finished immediately. Then give a chance to
        // see if the snapshot is usable for the current running activity so the transition will
        // look smoother, instead of showing a splash screen on the second launch.
        if (!newTask && taskSwitch && !activityCreated && task.intent != null
                // Another case where snapshot is allowed to be used is if this activity has not yet
                // been created && is translucent or floating.
                // The component isn't necessary to be matched in this case.
                && (!mOccludesParent || mActivityComponent.equals(task.intent.getComponent()))) {
            final ActivityRecord topAttached = task.getActivity(ActivityRecord::attachedToProcess);
            if (topAttached != null) {
                if (topAttached.isSnapshotCompatible(snapshot)
                        // This trampoline must be the same rotation.
                        && mDisplayContent.getDisplayRotation().rotationForOrientation(
                                getOverrideOrientation(),
                                mDisplayContent.getRotation()) == snapshot.getRotation()) {
                    return STARTING_WINDOW_TYPE_SNAPSHOT;
                }
                // No usable snapshot. And a splash screen may also be weird because an existing
                // activity may be shown right after the trampoline is finished.
                return STARTING_WINDOW_TYPE_NONE;
            }
        }
        final boolean isActivityHome = isActivityTypeHome();
        if ((newTask || !processRunning || (taskSwitch && !activityCreated))
                && !isActivityHome) {
            return STARTING_WINDOW_TYPE_SPLASH_SCREEN;
        }
        if (taskSwitch) {
            if (allowTaskSnapshot) {
                if (isSnapshotCompatible(snapshot)) {
                    return STARTING_WINDOW_TYPE_SNAPSHOT;
                }
                if (!isActivityHome) {
                    return STARTING_WINDOW_TYPE_SPLASH_SCREEN;
                }
            }
            if (!activityAllDrawn && !isActivityHome) {
                // Only check the special case of a fragment host task because the starting window
                // may not be visible if the client organizer delays the transition ready.
                if (task.mTaskFragmentHostProcessName != null) {
                    // It may be launched from a task trampoline that already has a starting window.
                    // Return NONE because 2 consecutive splashes may not look smooth in visual.
                    final Task prevTask = task.getParent().getTaskBelow(task);
                    if (prevTask != null) {
                        final ActivityRecord prevTaskTop = prevTask.getTopMostActivity();
                        if (prevTaskTop != null && prevTaskTop.hasStartingWindow()) {
                            return STARTING_WINDOW_TYPE_NONE;
                        }
                    }
                }
                return STARTING_WINDOW_TYPE_SPLASH_SCREEN;
            }
        }
        return STARTING_WINDOW_TYPE_NONE;
    }

    /**
     * Returns {@code true} if the task snapshot is compatible with this activity (at least the
     * rotation must be the same).
     */
    @VisibleForTesting
    boolean isSnapshotCompatible(TaskSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        return isSnapshotComponentCompatible(snapshot) && isSnapshotOrientationCompatible(snapshot);
    }

    /**
     * Returns {@code true} if the top activity component of task snapshot equals to this activity.
     */
    boolean isSnapshotComponentCompatible(@NonNull TaskSnapshot snapshot) {
        return snapshot.getTopActivityComponent().equals(mActivityComponent);
    }

    /**
     * Returns {@code true} if the orientation of task snapshot is compatible with this activity.
     */
    boolean isSnapshotOrientationCompatible(@NonNull TaskSnapshot snapshot) {
        final int rotation = mDisplayContent.rotationForActivityInDifferentOrientation(this);
        final int currentRotation = task.getWindowConfiguration().getRotation();
        final int targetRotation = rotation != ROTATION_UNDEFINED
                // The display may rotate according to the orientation of this activity.
                ? rotation
                // The activity won't change display orientation.
                : currentRotation;
        if (snapshot.getRotation() != targetRotation) {
            return false;
        }
        final Rect taskBounds = task.getBounds();
        int w = taskBounds.width();
        int h = taskBounds.height();
        final Point taskSize = snapshot.getTaskSize();
        if ((Math.abs(currentRotation - targetRotation) % 2) == 1) {
            // Flip the size if the activity will show in 90 degree difference.
            final int t = w;
            w = h;
            h = t;
        }
        // Task size might be changed with the same rotation such as on a foldable device.
        return Math.abs(((float) taskSize.x / Math.max(taskSize.y, 1))
                - ((float) w / Math.max(h, 1))) <= 0.01f;
    }

    /**
     * See {@link SplashScreen#setOnExitAnimationListener}.
     */
    void setCustomizeSplashScreenExitAnimation(boolean enable) {
        if (mHandleExitSplashScreen == enable) {
            return;
        }
        mHandleExitSplashScreen = enable;
    }

    private final Runnable mTransferSplashScreenTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mAtmService.mGlobalLock) {
                Slog.w(TAG, "Activity transferring splash screen timeout for "
                        + ActivityRecord.this + " state " + mTransferringSplashScreenState);
                if (isTransferringSplashScreen()) {
                    mTransferringSplashScreenState = TRANSFER_SPLASH_SCREEN_FINISH;
                    removeStartingWindow();
                }
            }
        }
    };

    private void scheduleTransferSplashScreenTimeout() {
        mAtmService.mH.postDelayed(mTransferSplashScreenTimeoutRunnable,
                TRANSFER_SPLASH_SCREEN_TIMEOUT);
    }

    private void removeTransferSplashScreenTimeout() {
        mAtmService.mH.removeCallbacks(mTransferSplashScreenTimeoutRunnable);
    }

    private boolean transferSplashScreenIfNeeded() {
        if (finishing || !mHandleExitSplashScreen || mStartingSurface == null
                || mStartingWindow == null
                || mTransferringSplashScreenState == TRANSFER_SPLASH_SCREEN_FINISH
                // Skip copy splash screen to client if it was resized, or the starting data already
                // requested to be removed after transaction commit.
                || (mStartingData != null && (mStartingData.mResizedFromTransfer
                        || mStartingData.mRemoveAfterTransaction
                        == AFTER_TRANSACTION_REMOVE_DIRECTLY))
                || isRelaunching()) {
            return false;
        }
        if (isTransferringSplashScreen()) {
            return true;
        }
        // Only do transfer after transaction has done when starting window exist.
        if (mStartingData != null) {
            if (getSyncTransactionCommitCallbackDepth() > 0) {
                mStartingData.mRemoveAfterTransaction = AFTER_TRANSACTION_COPY_TO_CLIENT;
                return true;
            }
        }
        requestCopySplashScreen();
        return isTransferringSplashScreen();
    }

    private boolean isTransferringSplashScreen() {
        return mTransferringSplashScreenState == TRANSFER_SPLASH_SCREEN_ATTACH_TO_CLIENT
                || mTransferringSplashScreenState == TRANSFER_SPLASH_SCREEN_COPYING;
    }

    private void requestCopySplashScreen() {
        mTransferringSplashScreenState = TRANSFER_SPLASH_SCREEN_COPYING;
        if (mStartingSurface == null || !mAtmService.mTaskOrganizerController.copySplashScreenView(
                getTask(), mStartingSurface.mTaskOrganizer)) {
            mTransferringSplashScreenState = TRANSFER_SPLASH_SCREEN_FINISH;
            removeStartingWindow();
        }
        scheduleTransferSplashScreenTimeout();
    }

    /**
     * Receive the splash screen data from shell, sending to client.
     * @param parcelable The data to reconstruct the splash screen view, null mean unable to copy.
     */
    void onCopySplashScreenFinish(@Nullable SplashScreenViewParcelable parcelable) {
        removeTransferSplashScreenTimeout();
        final SurfaceControl windowAnimationLeash = (parcelable == null
                || mTransferringSplashScreenState != TRANSFER_SPLASH_SCREEN_COPYING
                || mStartingWindow == null || mStartingWindow.mRemoved
                || finishing) ? null
                : TaskOrganizerController.applyStartingWindowAnimation(mStartingWindow);
        if (windowAnimationLeash == null) {
            // Unable to copy from shell, maybe it's not a splash screen, or something went wrong.
            // Either way, abort and reset the sequence.
            if (parcelable != null) {
                parcelable.clearIfNeeded();
            }
            mTransferringSplashScreenState = TRANSFER_SPLASH_SCREEN_FINISH;
            removeStartingWindow();
            return;
        }
        mTransferringSplashScreenState = TRANSFER_SPLASH_SCREEN_ATTACH_TO_CLIENT;
        final TransferSplashScreenViewStateItem item =
                new TransferSplashScreenViewStateItem(token, parcelable, windowAnimationLeash);
        boolean isSuccessful;
        try {
            isSuccessful = mAtmService.getLifecycleManager().scheduleTransactionItem(
                    app.getThread(), item);
        } catch (RemoteException e) {
            // TODO(b/323801078): remove Exception when cleanup
            Slog.w(TAG, "onCopySplashScreenComplete fail: " + this);
            isSuccessful = false;
        }
        if (isSuccessful) {
            scheduleTransferSplashScreenTimeout();
        } else {
            mStartingWindow.cancelAnimation();
            parcelable.clearIfNeeded();
            mTransferringSplashScreenState = TRANSFER_SPLASH_SCREEN_FINISH;
        }
    }

    private void onSplashScreenAttachComplete() {
        removeTransferSplashScreenTimeout();
        // Client has draw the splash screen, so we can remove the starting window.
        if (mStartingWindow != null) {
            mStartingWindow.cancelAnimation();
            mStartingWindow.hide(false, false);
        }
        // no matter what, remove the starting window.
        mTransferringSplashScreenState = TRANSFER_SPLASH_SCREEN_FINISH;
        removeStartingWindowAnimation(false /* prepareAnimation */);
    }

    /**
     * Notify the shell ({@link com.android.wm.shell.ShellTaskOrganizer} it should clean up any
     * remaining reference to this {@link ActivityRecord}'s splash screen.
     * @see com.android.wm.shell.ShellTaskOrganizer#onAppSplashScreenViewRemoved(int)
     * @see SplashScreenView#remove()
     */
    void cleanUpSplashScreen() {
        // We only clean up the splash screen if we were supposed to handle it. If it was
        // transferred to another activity, the next one will handle the clean up.
        if (mHandleExitSplashScreen && !startingMoved
                && (mTransferringSplashScreenState == TRANSFER_SPLASH_SCREEN_FINISH
                || mTransferringSplashScreenState == TRANSFER_SPLASH_SCREEN_IDLE)) {
            ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Cleaning splash screen token=%s", this);
            mAtmService.mTaskOrganizerController.onAppSplashScreenViewRemoved(getTask(),
                    mStartingSurface != null ? mStartingSurface.mTaskOrganizer : null);
        }
    }

    boolean isStartingWindowDisplayed() {
        final StartingData data = mStartingData != null ? mStartingData : task != null
                ? task.mSharedStartingData : null;
        return data != null && data.mIsDisplayed;
    }

    /** Called when the starting window is added to this activity. */
    void attachStartingWindow(@NonNull WindowState startingWindow) {
        startingWindow.mStartingData = mStartingData;
        mStartingWindow = startingWindow;
        if (mStartingData != null) {
            if (mStartingData.mAssociatedTask != null) {
                // The snapshot type may have called associateStartingDataWithTask().
                // If this activity is rotated, don't attach to task to preserve the transform.
                if (!hasFixedRotationTransform()) {
                    attachStartingSurfaceToAssociatedTask();
                }
            } else if (isEmbedded()) {
                associateStartingWindowWithTaskIfNeeded();
            }
            if (mTransitionController.isCollecting()) {
                mStartingData.mTransitionId = mTransitionController.getCollectingTransitionId();
            }
        }
    }

    /** Makes starting window always fill the associated task. */
    private void attachStartingSurfaceToAssociatedTask() {
        mTransitionController.collect(mStartingWindow);
        // Associate the configuration of starting window with the task.
        overrideConfigurationPropagation(mStartingWindow, mStartingData.mAssociatedTask);
        mStartingWindow.getSyncTransaction().reparent(mStartingWindow.mSurfaceControl,
                mStartingData.mAssociatedTask.mSurfaceControl);
    }

    /** Called when the starting window is not added yet but its data is known to fill the task. */
    private void associateStartingDataWithTask() {
        mStartingData.mAssociatedTask = task;
        task.mSharedStartingData = mStartingData;
    }

    /** Associates and attaches an added starting window to the current task. */
    void associateStartingWindowWithTaskIfNeeded() {
        if (mStartingWindow == null || mStartingData == null
                || mStartingData.mAssociatedTask != null) {
            return;
        }
        if (task.isVisible() && !task.inTransition()) {
            // Don't associated with task if the task is visible especially when the activity is
            // embedded. We just need to show splash screen on the activity in case the first frame
            // is not ready.
            return;
        }
        associateStartingDataWithTask();
        attachStartingSurfaceToAssociatedTask();
    }

    /**
     * If the device is locked and the app does not request showWhenLocked,
     * defer removing the starting window until the transition is complete.
     * This prevents briefly appearing the app context and causing secure concern.
     */
    void deferStartingWindowRemovalForKeyguardUnoccluding() {
        if (mStartingData.mRemoveAfterTransaction != AFTER_TRANSITION_FINISH
                && isKeyguardLocked() && !canShowWhenLockedInner(this) && !isVisibleRequested()
                && mTransitionController.inTransition(this)) {
            mStartingData.mRemoveAfterTransaction = AFTER_TRANSITION_FINISH;
        }
    }

    void removeStartingWindow() {
        final AppCompatLetterboxPolicy letterboxPolicy = mAppCompatController.getLetterboxPolicy();
        boolean prevEligibleForLetterboxEducation =
                letterboxPolicy.isEligibleForLetterboxEducation();

        if (mStartingData != null
                && mStartingData.mRemoveAfterTransaction == AFTER_TRANSITION_FINISH) {
            return;
        }

        if (transferSplashScreenIfNeeded()) {
            return;
        }
        removeStartingWindowAnimation(true /* prepareAnimation */);

        final Task task = getTask();
        if (task != null && prevEligibleForLetterboxEducation
                != letterboxPolicy.isEligibleForLetterboxEducation()) {
            // Trigger TaskInfoChanged to update the letterbox education.
            task.dispatchTaskInfoChangedIfNeeded(true /* force */);
        }
    }

    @Override
    void onSyncTransactionCommitted(SurfaceControl.Transaction t) {
        super.onSyncTransactionCommitted(t);
        if (mStartingData == null) {
            return;
        }
        final StartingData lastData = mStartingData;
        if (lastData.mRemoveAfterTransaction == AFTER_TRANSACTION_REMOVE_DIRECTLY) {
            removeStartingWindowAnimation(lastData.mPrepareRemoveAnimation);
        } else if (lastData.mRemoveAfterTransaction == AFTER_TRANSACTION_COPY_TO_CLIENT) {
            removeStartingWindow();
        }
    }

    void removeStartingWindowAnimation(boolean prepareAnimation) {
        mTransferringSplashScreenState = TRANSFER_SPLASH_SCREEN_IDLE;
        if (mStartingData != null && task != null) {
            task.mSharedStartingData = null;
        }
        if (mStartingWindow == null) {
            if (mStartingData != null) {
                // Starting window has not been added yet, but it is scheduled to be added.
                // Go ahead and cancel the request.
                ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Clearing startingData for token=%s", this);
                mStartingData = null;
                // Clean surface up since we don't want the window to be added back, so we don't
                // need to keep the surface to remove it.
                mStartingSurface = null;
            }
            return;
        }

        final StartingSurfaceController.StartingSurface surface;
        final boolean animate;
        final boolean hasImeSurface;
        if (mStartingData != null) {
            if (getSyncTransactionCommitCallbackDepth() > 0 || mSyncState != SYNC_STATE_NONE) {
                mStartingData.mRemoveAfterTransaction = AFTER_TRANSACTION_REMOVE_DIRECTLY;
                mStartingData.mPrepareRemoveAnimation = prepareAnimation;
                return;
            }
            animate = prepareAnimation && mStartingData.needRevealAnimation()
                    && mStartingWindow.isVisibleByPolicy();
            hasImeSurface = mStartingData.hasImeSurface();
            ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Schedule remove starting %s startingWindow=%s"
                            + " animate=%b Callers=%s", this, mStartingWindow, animate,
                    Debug.getCallers(5));
            surface = mStartingSurface;
            mStartingData = null;
            mStartingSurface = null;
            mStartingWindow = null;
            mTransitionChangeFlags &= ~FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT;
            if (surface == null) {
                ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "startingWindow was set but "
                        + "startingSurface==null, couldn't remove");
                return;
            }
        } else {
            ProtoLog.v(WM_DEBUG_STARTING_WINDOW,
                    "Tried to remove starting window but startingWindow was null: %s",
                    this);
            return;
        }
        surface.remove(animate, hasImeSurface);
    }

    /**
     * Reparents this activity into {@param newTaskFrag} at the provided {@param position}. The
     * caller should ensure that the {@param newTaskFrag} is not already the parent of this
     * activity.
     */
    void reparent(TaskFragment newTaskFrag, int position, String reason) {
        if (getParent() == null) {
            Slog.w(TAG, "reparent: Attempted to reparent non-existing app token: " + token);
            return;
        }
        final TaskFragment prevTaskFrag = getTaskFragment();
        if (prevTaskFrag == newTaskFrag) {
            throw new IllegalArgumentException(reason + ": task fragment =" + newTaskFrag
                    + " is already the parent of r=" + this);
        }

        ProtoLog.i(WM_DEBUG_ADD_REMOVE, "reparent: moving activity=%s"
                + " to new task fragment in task=%d at %d", this, task.mTaskId, position);
        reparent(newTaskFrag, position);
    }

    static boolean isHomeIntent(Intent intent) {
        return ACTION_MAIN.equals(intent.getAction())
                && (intent.hasCategory(CATEGORY_HOME)
                || intent.hasCategory(CATEGORY_SECONDARY_HOME))
                && intent.getCategories().size() == 1
                && intent.getData() == null
                && intent.getType() == null;
    }

    static boolean isMainIntent(Intent intent) {
        return ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(CATEGORY_LAUNCHER)
                && intent.getCategories().size() == 1
                && intent.getData() == null
                && intent.getType() == null;
    }

    @VisibleForTesting
    boolean canLaunchHomeActivity(int uid, ActivityRecord sourceRecord) {
        if (uid == SYSTEM_UID || uid == 0) {
            // System process can launch home activity.
            return true;
        }
        // Allow the recents component to launch the home activity.
        final RecentTasks recentTasks = mTaskSupervisor.mService.getRecentTasks();
        if (recentTasks != null && recentTasks.isCallerRecents(uid)) {
            return true;
        }
        // Resolver or system chooser activity can launch home activity.
        return sourceRecord != null && sourceRecord.isResolverOrDelegateActivity();
    }

    /**
     * @return whether the given package name can launch an assist activity.
     */
    private boolean canLaunchAssistActivity(String packageName) {
        final ComponentName assistComponent =
                mAtmService.mActiveVoiceInteractionServiceComponent;
        if (assistComponent != null) {
            return assistComponent.getPackageName().equals(packageName);
        }
        return false;
    }

    private void setActivityType(boolean componentSpecified, int launchedFromUid, Intent intent,
            ActivityOptions options, ActivityRecord sourceRecord) {
        int activityType = ACTIVITY_TYPE_UNDEFINED;
        if ((!componentSpecified || canLaunchHomeActivity(launchedFromUid, sourceRecord))
                && isHomeIntent(intent) && !isResolverOrDelegateActivity()) {
            // This sure looks like a home activity!
            activityType = ACTIVITY_TYPE_HOME;

            if (info.resizeMode == RESIZE_MODE_FORCE_RESIZEABLE
                    || info.resizeMode == RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION) {
                // We only allow home activities to be resizeable if they explicitly requested it.
                info.resizeMode = RESIZE_MODE_UNRESIZEABLE;
            }
        } else if (mAtmService.getRecentTasks().isRecentsComponent(mActivityComponent,
                info.applicationInfo.uid)) {
            activityType = ACTIVITY_TYPE_RECENTS;
        } else if (options != null && options.getLaunchActivityType() == ACTIVITY_TYPE_ASSISTANT
                && canLaunchAssistActivity(launchedFromPackage)) {
            activityType = ACTIVITY_TYPE_ASSISTANT;
        } else if (options != null && options.getLaunchActivityType() == ACTIVITY_TYPE_DREAM
                && mAtmService.canLaunchDreamActivity(launchedFromPackage)
                && DreamActivity.class.getName() == info.name) {
            activityType = ACTIVITY_TYPE_DREAM;
        }
        setActivityType(activityType);
    }

    void setTaskToAffiliateWith(Task taskToAffiliateWith) {
        if (launchMode != LAUNCH_SINGLE_INSTANCE && launchMode != LAUNCH_SINGLE_TASK) {
            task.setTaskToAffiliateWith(taskToAffiliateWith);
        }
    }

    /** @return Root task of this activity, null if there is no task. */
    @Nullable
    Task getRootTask() {
        return task != null ? task.getRootTask() : null;
    }

    int getRootTaskId() {
        return task != null ? task.getRootTaskId() : INVALID_TASK_ID;
    }

    /** @return the first organized parent task. */
    @Nullable
    Task getOrganizedTask() {
        return task != null ? task.getOrganizedTask() : null;
    }

    /** Returns the organized parent {@link TaskFragment}. */
    @Nullable
    TaskFragment getOrganizedTaskFragment() {
        final TaskFragment parent = getTaskFragment();
        return parent != null ? parent.getOrganizedTaskFragment() : null;
    }

    @Override
    boolean isEmbedded() {
        final TaskFragment parent = getTaskFragment();
        return parent != null && parent.isEmbedded();
    }

    /**
     * Returns {@code true} if the system is allowed to share this activity's state with the host
     * app when this activity is embedded in untrusted mode.
     */
    boolean isUntrustedEmbeddingStateSharingAllowed() {
        if (!Flags.untrustedEmbeddingStateSharing()) {
            return false;
        }
        return mAllowUntrustedEmbeddingStateSharing;
    }

    private boolean getAllowUntrustedEmbeddingStateSharingProperty() {
        if (!Flags.untrustedEmbeddingStateSharing()) {
            return false;
        }
        try {
            return mAtmService.mContext.getPackageManager()
                    .getPropertyAsUser(PROPERTY_ALLOW_UNTRUSTED_ACTIVITY_EMBEDDING_STATE_SHARING,
                            mActivityComponent.getPackageName(),
                            mActivityComponent.getClassName(), mUserId)
                    .getBoolean();
        } catch (PackageManager.NameNotFoundException e) {
            // No such property name.
            return false;
        }
    }

    /**
     * This is different from {@link #isEmbedded()}.
     * {@link #isEmbedded()} is {@code true} when any of the parent {@link TaskFragment} is created
     * by a {@link android.window.TaskFragmentOrganizer}, while this method is {@code true} when
     * the parent {@link TaskFragment} is embedded and has bounds override that does not fill the
     * leaf {@link Task}.
     */
    boolean isEmbeddedInHostContainer() {
        final TaskFragment taskFragment = getOrganizedTaskFragment();
        return taskFragment != null && taskFragment.isEmbeddedWithBoundsOverride();
    }

    @NonNull
    ActivityWindowInfo getActivityWindowInfo() {
        if (!isAttached()) {
            return mTmpActivityWindowInfo;
        }
        if (isFixedRotationTransforming()) {
            // Fixed rotation only applied to fullscreen activity, thus using the activity bounds
            // for Task/TaskFragment so that it is "pre-rotated" and in sync with the Configuration
            // update.
            final Rect bounds = getBounds();
            mTmpActivityWindowInfo.set(false /* isEmbedded */, bounds, bounds);
        } else {
            mTmpActivityWindowInfo.set(
                    isEmbeddedInHostContainer(),
                    getTask().getBounds(),
                    getTaskFragment().getBounds());
        }
        return mTmpActivityWindowInfo;
    }

    @Override
    @Nullable
    TaskDisplayArea getDisplayArea() {
        return (TaskDisplayArea) super.getDisplayArea();
    }

    @Override
    boolean providesOrientation() {
        return mStyleFillsParent || mOccludesParent;
    }

    @Override
    boolean fillsParent() {
        return occludesParent(true /* includingFinishing */);
    }

    boolean isNoDisplay() {
        return mNoDisplay;
    }

    /**
     * Exposed only for testing and should not be used to modify value of {@link #mNoDisplay}.
     */
    @VisibleForTesting
    void setIsNoDisplay(boolean isNoDisplay) {
        mNoDisplay = isNoDisplay;
    }

    /** Returns true if this activity is not finishing, is opaque and fills the entire space of
     * this task. */
    boolean occludesParent() {
        return occludesParent(false /* includingFinishing */);
    }

    @VisibleForTesting
    boolean occludesParent(boolean includingFinishing) {
        if (!includingFinishing && finishing) {
            return false;
        }
        return mOccludesParent || showWallpaper();
    }

    boolean setOccludesParent(boolean occludesParent) {
        final boolean changed = occludesParent != mOccludesParent;
        mOccludesParent = occludesParent;
        setMainWindowOpaque(occludesParent);

        if (changed && task != null) {
            if (!occludesParent) {
                getRootTask().convertActivityToTranslucent(this);
            } else {
                getRootTask().convertActivityFromTranslucent(this);
            }
        }
        // Always ensure visibility if this activity doesn't occlude parent, so the
        // {@link #returningOptions} of the activity under this one can be applied in
        // {@link #handleAlreadyVisible()}.
        if (changed || !occludesParent) {
            mRootWindowContainer.ensureActivitiesVisible();
        }
        return changed;
    }

    void setMainWindowOpaque(boolean isOpaque) {
        final WindowState win = findMainWindow();
        if (win == null) {
            return;
        }
        isOpaque = isOpaque & !PixelFormat.formatHasAlpha(win.mAttrs.format);
        win.mWinAnimator.setOpaqueLocked(isOpaque);
    }

    void takeFromHistory() {
        if (inHistory) {
            inHistory = false;
            if (task != null && !finishing) {
                task = null;
            }
            abortAndClearOptionsAnimation();
        }
    }

    boolean isInHistory() {
        return inHistory;
    }

    boolean isInRootTaskLocked() {
        final Task rootTask = getRootTask();
        return rootTask != null && rootTask.isInTask(this) != null;
    }

    boolean isPersistable() {
        return (info.persistableMode == PERSIST_ROOT_ONLY ||
                info.persistableMode == PERSIST_ACROSS_REBOOTS) &&
                (intent == null || (intent.getFlags() & FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) == 0);
    }

    @Override
    boolean isFocusable() {
        return super.isFocusable() && (canReceiveKeys() || isAlwaysFocusable());
    }

    boolean canReceiveKeys() {
        return getWindowConfiguration().canReceiveKeys() && !mWaitForEnteringPinnedMode;
    }

    /**
     * Returns {@code true} if the orientation will be ignored for {@link #isUniversalResizeable()}.
     */
    private boolean isRestrictedFixedOrientation(
            @ActivityInfo.ScreenOrientation int orientation) {
        // Exclude "locked" because it is not explicit portrait or landscape.
        return orientation != ActivityInfo.SCREEN_ORIENTATION_LOCKED
                && ActivityInfo.isFixedOrientation(orientation)
                && isUniversalResizeable();
    }

    /**
     * Returns {@code true} if the fixed orientation, aspect ratio, resizability of this activity
     * will be ignored.
     */
    boolean isUniversalResizeable() {
        final boolean isLargeScreen = mDisplayContent != null && mDisplayContent.isLargeScreen()
                && mDisplayContent.getIgnoreOrientationRequest();
        if (!canBeUniversalResizeable(info.applicationInfo, mWmService, isLargeScreen,
                true /* forActivity */)) {
            return false;
        }
        if (mAppCompatController.getResizeOverrides().allowRestrictedResizability()) {
            return false;
        }
        // If the user preference respects aspect ratio, then it becomes non-resizable.
        return mAppCompatController.getAspectRatioOverrides()
                .userPreferenceCompatibleWithNonResizability();
    }

    /**
     * Returns {@code true} if the fixed orientation, aspect ratio, resizability of the application
     * can be ignored.
     */
    static boolean canBeUniversalResizeable(@NonNull ApplicationInfo appInfo,
            WindowManagerService wms, boolean isLargeScreen, boolean forActivity) {
        if (appInfo.category == ApplicationInfo.CATEGORY_GAME) {
            return false;
        }
        final boolean compatEnabled = isLargeScreen && Flags.universalResizableByDefault()
                && appInfo.isChangeEnabled(ActivityInfo.UNIVERSAL_RESIZABLE_BY_DEFAULT);
        final boolean configEnabled = (isLargeScreen
                ? wms.mConstants.mIgnoreActivityOrientationRequestLargeScreen
                : wms.mConstants.mIgnoreActivityOrientationRequestSmallScreen)
                && !wms.mConstants.isPackageOptOutIgnoreActivityOrientationRequest(
                        appInfo.packageName);
        if (!compatEnabled && !configEnabled) {
            return false;
        }
        if (forActivity) {
            // The caller will check both application and activity level property.
            return true;
        }
        return !AppCompatResizeOverrides.allowRestrictedResizability(
                wms.mContext.getPackageManager(), appInfo.packageName,
                UserHandle.getUserId(appInfo.uid));
    }

    boolean isResizeable() {
        return mAtmService.mForceResizableActivities
                || ActivityInfo.isResizeableMode(info.resizeMode)
                || info.supportsPictureInPicture()
                || isUniversalResizeable()
                // If the activity can be embedded, it should inherit the bounds of task fragment.
                || isEmbedded();
    }

    /** @return whether this activity is non-resizeable but is forced to be resizable. */
    boolean canForceResizeNonResizable(int windowingMode) {
        if (windowingMode == WINDOWING_MODE_PINNED && info.supportsPictureInPicture()) {
            return false;
        }
        // Activity should be resizable if the task is.
        final boolean supportsMultiWindow = task != null
                ? task.supportsMultiWindow() || supportsMultiWindow()
                : supportsMultiWindow();
        if (WindowConfiguration.inMultiWindowMode(windowingMode) && supportsMultiWindow
                && !mAtmService.mForceResizableActivities) {
            // The non resizable app will be letterboxed instead of being forced resizable.
            return false;
        }
        return info.resizeMode != RESIZE_MODE_RESIZEABLE
                && info.resizeMode != RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
    }

    /**
     * @return whether this activity supports PiP multi-window and can be put in the root pinned
     * task.
     */
    boolean supportsPictureInPicture() {
        return mAtmService.mSupportsPictureInPicture && isActivityTypeStandardOrUndefined()
                && info.supportsPictureInPicture();
    }

    boolean supportsFreeform() {
        return supportsFreeformInDisplayArea(getDisplayArea());
    }

    /**
     * @return whether this activity supports freeform multi-window and can be put in the freeform
     *         windowing mode if it is in the given {@link TaskDisplayArea}.
     */
    boolean supportsFreeformInDisplayArea(@Nullable TaskDisplayArea tda) {
        return mAtmService.mSupportsFreeformWindowManagement
                && supportsMultiWindowInDisplayArea(tda);
    }

    boolean supportsMultiWindow() {
        return supportsMultiWindowInDisplayArea(getDisplayArea());
    }

    /**
     * @return whether this activity supports multi-window if it is in the given
     *         {@link TaskDisplayArea}.
     */
    boolean supportsMultiWindowInDisplayArea(@Nullable TaskDisplayArea tda) {
        if (isActivityTypeHome()) {
            return false;
        }
        if (!mAtmService.mSupportsMultiWindow) {
            return false;
        }
        if (tda == null) {
            return false;
        }

        if (!isResizeable() && !tda.supportsNonResizableMultiWindow()) {
            // Not support non-resizable in multi window.
            return false;
        }

        final ActivityInfo.WindowLayout windowLayout = info.windowLayout;
        return windowLayout == null
                || tda.supportsActivityMinWidthHeightMultiWindow(windowLayout.minWidth,
                windowLayout.minHeight, info);
    }

    /**
     * Check whether this activity can be launched on the specified display.
     *
     * @param displayId Target display id.
     * @return {@code true} if either it is the default display or this activity can be put on a
     *         secondary screen.
     */
    boolean canBeLaunchedOnDisplay(int displayId) {
        return mAtmService.mTaskSupervisor.canPlaceEntityOnDisplay(displayId, launchedFromPid,
                launchedFromUid, info);
    }

    /**
     * @param beforeStopping Whether this check is for an auto-enter-pip operation, that is to say
     *         the activity has requested to enter PiP when it would otherwise be stopped.
     *
     * @return whether this activity is currently allowed to enter PIP.
     */
    boolean checkEnterPictureInPictureState(String caller, boolean beforeStopping) {
        if (!supportsPictureInPicture()) {
            return false;
        }

        // Check app-ops and see if PiP is supported for this package
        if (!checkEnterPictureInPictureAppOpsState()) {
            return false;
        }

        // Check to see if we are in VR mode, and disallow PiP if so
        if (mAtmService.shouldDisableNonVrUiLocked()) {
            return false;
        }

        // Check if activity is top activity of its task fragment - this prevents any trampolines
        // followed by enterPictureInPictureMode() calls by an activity from below in its stack.
        if (getTaskFragment() == null || getTaskFragment().getTopNonFinishingActivity() != this) {
            return false;
        }

        // Check to see if PiP is supported for the display this container is on.
        if (mDisplayContent != null && !mDisplayContent.mDwpcHelper.isEnteringPipAllowed(
                getUid())) {
            Slog.w(TAG, "Display " + mDisplayContent.getDisplayId()
                    + " doesn't support enter picture-in-picture mode. caller = " + caller);
            return false;
        }

        boolean isCurrentAppLocked =
                mAtmService.getLockTaskModeState() != LOCK_TASK_MODE_NONE;
        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        boolean hasRootPinnedTask = taskDisplayArea != null && taskDisplayArea.hasPinnedTask();
        // Don't return early if !isNotLocked, since we want to throw an exception if the activity
        // is in an incorrect state
        boolean isNotLockedOrOnKeyguard = !isKeyguardLocked() && !isCurrentAppLocked;

        // We don't allow auto-PiP when something else is already pipped.
        if (beforeStopping && hasRootPinnedTask) {
            return false;
        }

        switch (mState) {
            case RESUMED:
                // When visible, allow entering PiP if the app is not locked.  If it is over the
                // keyguard, then we will prompt to unlock in the caller before entering PiP.
                return !isCurrentAppLocked &&
                        (supportsEnterPipOnTaskSwitch || !beforeStopping);
            case PAUSING:
            case PAUSED:
                // When pausing, then only allow enter PiP as in the resume state, and in addition,
                // require that there is not an existing PiP activity and that the current system
                // state supports entering PiP
                return isNotLockedOrOnKeyguard && !hasRootPinnedTask
                        && supportsEnterPipOnTaskSwitch;
            case STOPPING:
                // When stopping in a valid state, then only allow enter PiP as in the pause state.
                // Otherwise, fall through to throw an exception if the caller is trying to enter
                // PiP in an invalid stopping state.
                if (supportsEnterPipOnTaskSwitch) {
                    return isNotLockedOrOnKeyguard && !hasRootPinnedTask;
                }
            default:
                return false;
        }
    }

    /**
     * Sets if this {@link ActivityRecord} is in the process of closing or entering PIP.
     * {@link #mWillCloseOrEnterPip}}
     */
    void setWillCloseOrEnterPip(boolean willCloseOrEnterPip) {
        mWillCloseOrEnterPip = willCloseOrEnterPip;
    }

    boolean willCloseOrEnterPip() {
        return mWillCloseOrEnterPip;
    }

    /**
     * @return Whether AppOps allows this package to enter picture-in-picture.
     */
    boolean checkEnterPictureInPictureAppOpsState() {
        return mAtmService.getAppOpsManager().checkOpNoThrow(
                OP_PICTURE_IN_PICTURE, info.applicationInfo.uid, packageName) == MODE_ALLOWED;
    }

    private boolean isAlwaysFocusable() {
        return (info.flags & FLAG_ALWAYS_FOCUSABLE) != 0;
    }

    boolean windowsAreFocusable() {
        return windowsAreFocusable(false /* fromUserTouch */);
    }

    // TODO: Does this really need to be different from isAlwaysFocusable()? For the activity side
    // focusable means resumeable. I guess with that in mind maybe we should rename the other
    // method to isResumeable() or something like that.
    boolean windowsAreFocusable(boolean fromUserTouch) {
        if (!fromUserTouch && mTargetSdk < Build.VERSION_CODES.Q) {
            final int pid = getPid();
            final ActivityRecord topFocusedAppOfMyProcess =
                    mWmService.mRoot.mTopFocusedAppByProcess.get(pid);
            if (topFocusedAppOfMyProcess != null && topFocusedAppOfMyProcess != this) {
                // For the apps below Q, there can be only one app which has the focused window per
                // process, because legacy apps may not be ready for a multi-focus system.
                return false;

            }
        }
        // Check isAttached() because the method may be called when removing this activity from
        // display, and WindowContainer#compareTo will throw exception if it doesn't have a parent
        // when updating focused window from DisplayContent#findFocusedWindow.
        return (canReceiveKeys() || isAlwaysFocusable()) && isAttached();
    }

    /**
     * Move activity with its root task to front and make the root task focused.
     * @param reason the reason to move to top
     * @return {@code true} if the root task is focusable and has been moved to top or the activity
     *         is not yet resumed while the root task is already on top, {@code false} otherwise.
     */
    boolean moveFocusableActivityToTop(String reason) {
        if (!isFocusable()) {
            ProtoLog.d(WM_DEBUG_FOCUS, "moveFocusableActivityToTop: unfocusable "
                    + "activity=%s", this);
            return false;
        }

        final Task rootTask = getRootTask();
        if (rootTask == null) {
            Slog.w(TAG, "moveFocusableActivityToTop: invalid root task: activity="
                    + this + " task=" + task);
            return false;
        }

        // If this activity already positions on the top focused task, moving the task to front
        // is not needed. But we still need to ensure this activity is focused because the
        // current focused activity could be another activity in the same Task if activities are
        // displayed on adjacent TaskFragments.
        final ActivityRecord currentFocusedApp = mDisplayContent.mFocusedApp;
        final int topFocusedDisplayId = mRootWindowContainer.getTopFocusedDisplayContent() != null
                ? mRootWindowContainer.getTopFocusedDisplayContent().getDisplayId()
                : INVALID_DISPLAY;
        if (currentFocusedApp != null && currentFocusedApp.task == task
                && topFocusedDisplayId == mDisplayContent.getDisplayId()) {
            final Task topFocusableTask = mDisplayContent.getTask(
                    (t) -> t.isLeafTask() && t.isFocusable() && !t.inPinnedWindowingMode(),
                    true /*  traverseTopToBottom */);
            if (task == topFocusableTask) {
                if (currentFocusedApp == this) {
                    ProtoLog.d(WM_DEBUG_FOCUS, "moveFocusableActivityToTop: already on top "
                            + "and focused, activity=%s", this);
                } else {
                    ProtoLog.d(WM_DEBUG_FOCUS, "moveFocusableActivityToTop: set focused, "
                            + "activity=%s", this);
                    mDisplayContent.setFocusedApp(this);
                    mAtmService.mWindowManager.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL,
                            true /* updateInputWindows */);
                }
                return !isState(RESUMED);
            }
        }

        ProtoLog.d(WM_DEBUG_FOCUS, "moveFocusableActivityToTop: activity=%s", this);

        rootTask.moveToFront(reason, task);
        // Report top activity change to tracking services and WM
        if (mState == RESUMED && mRootWindowContainer.getTopResumedActivity() == this) {
            mAtmService.setLastResumedActivityUncheckLocked(this, reason);
        }
        return true;
    }

    void finishIfSubActivity(ActivityRecord parent, String otherResultWho, int otherRequestCode) {
        if (resultTo != parent
                || requestCode != otherRequestCode
                || !Objects.equals(resultWho, otherResultWho)) return;

        finishIfPossible("request-sub", false /* oomAdj */);
    }

    /** Finish all activities in the task with the same affinity as this one. */
    boolean finishIfSameAffinity(ActivityRecord r) {
        // End search once we get to the activity that doesn't have the same affinity.
        if (!Objects.equals(r.taskAffinity, taskAffinity)) return true;

        r.finishIfPossible("request-affinity", true /* oomAdj */);
        return false;
    }

    /**
     * Sets the result for activity that started this one, clears the references to activities
     * started for result from this one, and clears new intents.
     */
    private void finishActivityResults(int resultCode, Intent resultData,
            NeededUriGrants resultGrants) {
        // Send the result if needed
        if (resultTo != null) {
            if (DEBUG_RESULTS) {
                Slog.v(TAG_RESULTS, "Adding result to " + resultTo
                        + " who=" + resultWho + " req=" + requestCode
                        + " res=" + resultCode + " data=" + resultData);
            }
            if (resultTo.mUserId != mUserId) {
                if (resultData != null) {
                    resultData.prepareToLeaveUser(mUserId);
                }
            }
            if (info.applicationInfo.uid > 0) {
                mAtmService.mUgmInternal.grantUriPermissionUncheckedFromIntent(resultGrants,
                        resultTo.getUriPermissionsLocked());
            }
            IBinder callerToken = new Binder();
            if (android.security.Flags.contentUriPermissionApis()) {
                try {
                    resultTo.computeCallerInfo(callerToken, resultData, this.getUid(),
                            mAtmService.getPackageManager().getNameForUid(this.getUid()),
                            /* isShareIdentityEnabled */ false);
                    // Result callers cannot share their identity via
                    // {@link ActivityOptions#setShareIdentityEnabled(boolean)} since
                    // {@link android.app.Activity#setResult} doesn't have a
                    // {@link android.os.Bundle}.
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
            if (mForceSendResultForMediaProjection || resultTo.isState(RESUMED)) {
                // Sending the result to the resultTo activity asynchronously to prevent the
                // resultTo activity getting results before this Activity paused.
                final ActivityRecord resultToActivity = resultTo;
                mAtmService.mH.post(() -> {
                    synchronized (mAtmService.mGlobalLock) {
                        resultToActivity.sendResult(this.getUid(), resultWho, requestCode,
                                resultCode, resultData, callerToken, resultGrants,
                                mForceSendResultForMediaProjection);
                    }
                });
            } else {
                resultTo.addResultLocked(this, resultWho, requestCode, resultCode, resultData,
                        callerToken);
            }
            resultTo = null;
        } else if (DEBUG_RESULTS) {
            Slog.v(TAG_RESULTS, "No result destination from " + this);
        }

        // Make sure this HistoryRecord is not holding on to other resources,
        // because clients have remote IPC references to this object so we
        // can't assume that will go away and want to avoid circular IPC refs.
        results = null;
        pendingResults = null;
        newIntents = null;
        setSavedState(null /* savedState */);
    }

    /** Activity finish request was not executed. */
    static final int FINISH_RESULT_CANCELLED = 0;
    /** Activity finish was requested, activity will be fully removed later. */
    static final int FINISH_RESULT_REQUESTED = 1;
    /** Activity finish was requested, activity was removed from history. */
    static final int FINISH_RESULT_REMOVED = 2;

    /** Definition of possible results for activity finish request. */
    @IntDef(prefix = { "FINISH_RESULT_" }, value = {
            FINISH_RESULT_CANCELLED,
            FINISH_RESULT_REQUESTED,
            FINISH_RESULT_REMOVED,
    })
    @interface FinishRequest {}

    /**
     * See {@link #finishIfPossible(int, Intent, NeededUriGrants, String, boolean)}
     */
    @FinishRequest int finishIfPossible(String reason, boolean oomAdj) {
        return finishIfPossible(Activity.RESULT_CANCELED,
                null /* resultData */, null /* resultGrants */, reason, oomAdj);
    }

    /**
     * Finish activity if possible. If activity was resumed - we must first pause it to make the
     * activity below resumed. Otherwise we will try to complete the request immediately by calling
     * {@link #completeFinishing(String)}.
     * @return One of {@link FinishRequest} values:
     * {@link #FINISH_RESULT_REMOVED} if this activity has been removed from the history list.
     * {@link #FINISH_RESULT_REQUESTED} if removal process was started, but it is still in the list
     * and will be removed from history later.
     * {@link #FINISH_RESULT_CANCELLED} if activity is already finishing or in invalid state and the
     * request to finish it was not ignored.
     */
    @FinishRequest int finishIfPossible(int resultCode, Intent resultData,
            NeededUriGrants resultGrants, String reason, boolean oomAdj) {
        ProtoLog.v(WM_DEBUG_STATES, "Finishing activity r=%s, result=%d, data=%s, "
                + "reason=%s", this, resultCode, resultData, reason);

        if (finishing) {
            Slog.w(TAG, "Duplicate finish request for r=" + this);
            return FINISH_RESULT_CANCELLED;
        }

        if (!isInRootTaskLocked()) {
            Slog.w(TAG, "Finish request when not in root task for r=" + this);
            return FINISH_RESULT_CANCELLED;
        }

        final Task rootTask = getRootTask();
        final boolean mayAdjustTop = (isState(RESUMED) || rootTask.getTopResumedActivity() == null)
                && rootTask.isFocusedRootTaskOnDisplay()
                // Do not adjust focus task because the task will be reused to launch new activity.
                && !task.isClearingToReuseTask();
        final boolean shouldAdjustGlobalFocus = mayAdjustTop
                // It must be checked before {@link #makeFinishingLocked} is called, because a
                // root task is not visible if it only contains finishing activities.
                && mRootWindowContainer.isTopDisplayFocusedRootTask(rootTask);

        mAtmService.deferWindowLayout();
        try {
            mTaskSupervisor.mNoHistoryActivities.remove(this);
            makeFinishingLocked();
            // Make a local reference to its task since this.task could be set to null once this
            // activity is destroyed and detached from task.
            final Task task = getTask();
            EventLogTags.writeWmFinishActivity(mUserId, System.identityHashCode(this),
                    task.mTaskId, shortComponentName, reason);
            ActivityRecord next = task.getActivityAbove(this);
            if (next != null) {
                if ((intent.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET) != 0) {
                    // If the caller asked that this activity (and all above it)
                    // be cleared when the task is reset, don't lose that information,
                    // but propagate it up to the next activity.
                    next.intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                }
            }

            pauseKeyDispatchingLocked();

            final boolean endTask = task.getTopNonFinishingActivity() == null
                    && !task.isClearingToReuseTask();
            final WindowContainer<?> trigger = endTask ? task : this;
            final Transition newTransition =
                    mTransitionController.requestCloseTransitionIfNeeded(trigger);
            final Transition transition = newTransition != null
                    ? newTransition : mTransitionController.getCollectingTransition();
            if (transition != null) {
                transition.collectClose(trigger);
            }
            // We are finishing the top focused activity and its task has nothing to be focused so
            // the next focusable task should be focused.
            if (mayAdjustTop && task.topRunningActivity(true /* focusableOnly */)
                    == null) {
                task.adjustFocusToNextFocusableTask("finish-top", false /* allowFocusSelf */,
                            shouldAdjustGlobalFocus);
            }

            finishActivityResults(resultCode, resultData, resultGrants);

            if (isState(RESUMED)) {
                if (endTask) {
                    mAtmService.getTaskChangeNotificationController().notifyTaskRemovalStarted(
                            task.getTaskInfo());
                }
                // Prepare app close transition, but don't execute just yet. It is possible that
                // an activity that will be made resumed in place of this one will immediately
                // launch another new activity. In this case current closing transition will be
                // combined with open transition for the new activity.
                if (DEBUG_VISIBILITY || DEBUG_TRANSITION) {
                    Slog.v(TAG_TRANSITION, "Prepare close transition: finishing " + this);
                }

                // When finishing the activity preemptively take the snapshot before the app window
                // is marked as hidden and any configuration changes take place
                // Note that RecentsAnimation will handle task snapshot while switching apps with
                // the best capture timing (e.g. IME window capture),
                // No need additional task capture while task is controlled by RecentsAnimation.
                if (!mTransitionController.isShellTransitionsEnabled()
                        && !task.isAnimatingByRecents()) {
                    final ArraySet<Task> tasks = Sets.newArraySet(task);
                    mAtmService.mWindowManager.mTaskSnapshotController.snapshotTasks(tasks);
                    mAtmService.mWindowManager.mTaskSnapshotController
                            .addSkipClosingAppSnapshotTasks(tasks);
                }

                // Tell window manager to prepare for this one to be removed.
                setVisibility(false);
                // Propagate the last IME visibility in the same task, so the IME can show
                // automatically if the next activity has a focused editable view.
                if (mLastImeShown && mTransitionController.isShellTransitionsEnabled()) {
                    final ActivityRecord nextRunning = task.topRunningActivity();
                    if (nextRunning != null) {
                        nextRunning.mLastImeShown = true;
                    }
                }

                if (getTaskFragment().getPausingActivity() == null) {
                    ProtoLog.v(WM_DEBUG_STATES, "Finish needs to pause: %s", this);
                    if (DEBUG_USER_LEAVING) {
                        Slog.v(TAG_USER_LEAVING, "finish() => pause with userLeaving=false");
                    }
                    getTaskFragment().startPausing(false /* userLeaving */, false /* uiSleeping */,
                            null /* resuming */, "finish");
                }

                if (endTask) {
                    mAtmService.getLockTaskController().clearLockedTask(task);
                }
            } else if (!isState(PAUSING)) {
                if (mVisibleRequested) {
                    // Prepare and execute close transition.
                    if (mTransitionController.isShellTransitionsEnabled()) {
                        setVisibility(false);
                        if (newTransition != null) {
                            // This is a transition specifically for this close operation, so set
                            // ready now.
                            newTransition.setReady(mDisplayContent, true);
                        }
                    } else {
                        prepareActivityHideTransitionAnimation();
                    }
                }

                final boolean removedActivity = completeFinishing("finishIfPossible") == null;
                // Performance optimization - only invoke OOM adjustment if the state changed to
                // 'STOPPING'. Otherwise it will not change the OOM scores.
                if (oomAdj && isState(STOPPING)) {
                    mAtmService.updateOomAdj();
                }

                // The following code is an optimization. When the last non-task overlay activity
                // is removed from the task, we remove the entire task from the root task. However,
                // since that is done after the scheduled destroy callback from the activity, that
                // call to change the visibility of the task overlay activities would be out of
                // sync with the activity visibility being set for this finishing activity above.
                // In this case, we can set the visibility of all the task overlay activities when
                // we detect the last one is finishing to keep them in sync.
                if (task.onlyHasTaskOverlayActivities(false /* includeFinishing */)) {
                    task.forAllActivities((r) -> {
                        r.prepareActivityHideTransitionAnimationIfOvarlay();
                    });
                }
                return removedActivity ? FINISH_RESULT_REMOVED : FINISH_RESULT_REQUESTED;
            } else {
                ProtoLog.v(WM_DEBUG_STATES, "Finish waiting for pause of: %s", this);
            }

            return FINISH_RESULT_REQUESTED;
        } finally {
            mAtmService.continueWindowLayout();
        }
    }

    void setForceSendResultForMediaProjection() {
        mForceSendResultForMediaProjection = true;
    }

    private void prepareActivityHideTransitionAnimationIfOvarlay() {
        if (mTaskOverlay) {
            prepareActivityHideTransitionAnimation();
        }
    }

    private void prepareActivityHideTransitionAnimation() {
        final DisplayContent dc = mDisplayContent;
        setVisibility(false);
        dc.executeAppTransition();
    }

    ActivityRecord completeFinishing(String reason) {
        return completeFinishing(true /* updateVisibility */, reason);
    }

    /**
     * Complete activity finish request that was initiated earlier. If the activity is still
     * pausing we will wait for it to complete its transition. If the activity that should appear in
     * place of this one is not visible yet - we'll wait for it first. Otherwise - activity can be
     * destroyed right away.
     * @param updateVisibility Indicate if need to update activity visibility.
     * @param reason Reason for finishing the activity.
     * @return Flag indicating whether the activity was removed from history.
     */
    ActivityRecord completeFinishing(boolean updateVisibility, String reason) {
        if (!finishing || isState(RESUMED)) {
            throw new IllegalArgumentException(
                    "Activity must be finishing and not resumed to complete, r=" + this
                            + ", finishing=" + finishing + ", state=" + mState);
        }

        if (isState(PAUSING)) {
            // Activity is marked as finishing and will be processed once it completes.
            return this;
        }

        final boolean isCurrentVisible = mVisibleRequested || isState(PAUSED, STARTED);
        if (updateVisibility && isCurrentVisible
                // Avoid intermediate lifecycle change when launching with clearing task.
                && !task.isClearingToReuseTask()) {
            boolean ensureVisibility = false;
            if (occludesParent(true /* includingFinishing */)) {
                // If the current activity is not opaque, we need to make sure the visibilities of
                // activities be updated, they may be seen by users.
                ensureVisibility = true;
            } else if (isKeyguardLocked()
                    && mTaskSupervisor.getKeyguardController().topActivityOccludesKeyguard(this)) {
                // Ensure activity visibilities and update lockscreen occluded/dismiss state when
                // finishing the top activity that occluded keyguard. So that, the
                // ActivityStack#mTopActivityOccludesKeyguard can be updated and the activity below
                // won't be resumed.
                ensureVisibility = true;
            }

            if (ensureVisibility) {
                mDisplayContent.ensureActivitiesVisible(null /* starting */,
                        true /* notifyClients */);
            }
        }

        boolean activityRemoved = false;

        // If this activity is currently visible, and the resumed activity is not yet visible, then
        // hold off on finishing until the resumed one becomes visible.
        // The activity that we are finishing may be over the lock screen. In this case, we do not
        // want to consider activities that cannot be shown on the lock screen as running and should
        // proceed with finishing the activity if there is no valid next top running activity.
        // Note that if this finishing activity is floating task, we don't need to wait the
        // next activity resume and can destroy it directly.
        // TODO(b/137329632): find the next activity directly underneath this one, not just anywhere
        final ActivityRecord next = getDisplayArea().topRunningActivity(
                true /* considerKeyguardState */);

        // If the finishing activity is the last activity of an organized TaskFragment and has an
        // adjacent TaskFragment, check if the activity removal should be delayed.
        boolean delayRemoval = false;
        final TaskFragment taskFragment = getTaskFragment();
        if (next != null && taskFragment != null && taskFragment.isEmbedded()) {
            final TaskFragment organized = taskFragment.getOrganizedTaskFragment();
            delayRemoval = organized != null
                    && organized.topRunningActivity() == null
                    && organized.isDelayLastActivityRemoval()
                    && organized.forOtherAdjacentTaskFragments(next::isDescendantOf);
        }

        // isNextNotYetVisible is to check if the next activity is invisible, or it has been
        // requested to be invisible but its windows haven't reported as invisible.  If so, it
        // implied that the current finishing activity should be added into stopping list rather
        // than destroy immediately.
        final boolean isNextNotYetVisible = next != null
                && (!next.nowVisible || !next.isVisibleRequested());

        // Clear last paused activity to ensure top activity can be resumed during sleeping.
        if (isNextNotYetVisible && mDisplayContent.isSleeping()
                && next == next.getTaskFragment().mLastPausedActivity) {
            next.getTaskFragment().clearLastPausedActivity();
        }

        if (isCurrentVisible) {
            if (isNextNotYetVisible || delayRemoval || (next != null && isInTransition())) {
                // Add this activity to the list of stopping activities. It will be processed and
                // destroyed when the next activity reports idle.
                addToStopping(false /* scheduleIdle */, false /* idleDelayed */,
                        "completeFinishing");
                setState(STOPPING, "completeFinishing");
            } else if (addToFinishingAndWaitForIdle()) {
                // We added this activity to the finishing list and something else is becoming
                // resumed. The activity will complete finishing when the next activity reports
                // idle. No need to do anything else here.
            } else {
                // Not waiting for the next one to become visible, and nothing else will be
                // resumed in place of this activity - requesting destruction right away.
                activityRemoved = destroyIfPossible(reason);
            }
        } else {
            // Just need to make sure the next activities can be resumed (if needed) and is free
            // to destroy this activity since it is currently not visible.
            addToFinishingAndWaitForIdle();
            activityRemoved = destroyIfPossible(reason);
        }

        return activityRemoved ? null : this;
    }

    /**
     * Destroy and cleanup the activity both on client and server if possible. If activity is the
     * last one left on display with home root task and there is no other running activity - delay
     * destroying it until the next one starts.
     */
    boolean destroyIfPossible(String reason) {
        setState(FINISHING, "destroyIfPossible");

        // Make sure the record is cleaned out of other places.
        mTaskSupervisor.mStoppingActivities.remove(this);

        final Task rootTask = getRootTask();
        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        // TODO(b/137329632): Exclude current activity when looking for the next one with
        // DisplayContent#topRunningActivity().
        final ActivityRecord next = taskDisplayArea.topRunningActivity();
        final boolean isLastRootTaskOverEmptyHome =
                next == null && rootTask.isFocusedRootTaskOnDisplay()
                        && taskDisplayArea.getOrCreateRootHomeTask() != null;
        if (isLastRootTaskOverEmptyHome) {
            // Don't destroy activity immediately if this is the last activity on the display and
            // the display contains root home task. Although there is no next activity at the
            // moment, another home activity should be started later. Keep this activity alive
            // until next home activity is resumed. This way the user won't see a temporary black
            // screen.
            addToFinishingAndWaitForIdle();
            return false;
        }
        makeFinishingLocked();

        final boolean activityRemoved = destroyImmediately("finish-imm:" + reason);

        // If the display does not have running activity, the configuration may need to be
        // updated for restoring original orientation of the display.
        if (next == null) {
            mRootWindowContainer.ensureVisibilityAndConfig(null /* starting */, mDisplayContent,
                    true /* deferResume */);
            if (mDisplayContent.topRunningActivity() == null) {
                // The transition is ready on a display with no running activities.
                mTransitionController.setReady(mDisplayContent);
            }
        }
        if (activityRemoved) {
            mRootWindowContainer.resumeFocusedTasksTopActivities();
        }

        ProtoLog.d(WM_DEBUG_CONTAINERS, "destroyIfPossible: r=%s destroy returned "
                + "removed=%s", this, activityRemoved);

        return activityRemoved;
    }

    /**
     * Add this activity to the list of finishing and trigger resuming of activities in focused
     * root tasks.
     * @return {@code true} if some other activity is being resumed as a result of this call.
     */
    @VisibleForTesting
    boolean addToFinishingAndWaitForIdle() {
        ProtoLog.v(WM_DEBUG_STATES, "Enqueueing pending finish: %s", this);
        setState(FINISHING, "addToFinishingAndWaitForIdle");
        if (!mTaskSupervisor.mFinishingActivities.contains(this)) {
            mTaskSupervisor.mFinishingActivities.add(this);
        }
        resumeKeyDispatchingLocked();
        return mRootWindowContainer.resumeFocusedTasksTopActivities();
    }

    /**
     * Destroy the current CLIENT SIDE instance of an activity. This may be called both when
     * actually finishing an activity, or when performing a configuration switch where we destroy
     * the current client-side object but then create a new client-side object for this same
     * HistoryRecord.
     * Normally the server-side record will be removed when the client reports back after
     * destruction. If, however, at this point there is no client process attached, the record will
     * be removed immediately.
     *
     * @return {@code true} if activity was immediately removed from history, {@code false}
     * otherwise.
     */
    boolean destroyImmediately(String reason) {
        if (DEBUG_SWITCH || DEBUG_CLEANUP) {
            Slog.v(TAG_SWITCH, "Removing activity from " + reason + ": token=" + this
                    + ", app=" + (hasProcess() ? app.mName : "(null)"));
        }

        if (isState(DESTROYING, DESTROYED)) {
            ProtoLog.v(WM_DEBUG_STATES, "activity %s already destroying, skipping "
                    + "request with reason:%s", this, reason);
            return false;
        }

        EventLogTags.writeWmDestroyActivity(mUserId, System.identityHashCode(this),
                task.mTaskId, shortComponentName, reason);

        boolean removedFromHistory = false;

        cleanUp(false /* cleanServices */, false /* setState */);
        setVisibleRequested(false);

        if (hasProcess()) {
            app.removeActivity(this, true /* keepAssociation */);
            if (!app.hasActivities()) {
                mAtmService.clearHeavyWeightProcessIfEquals(app);
            }

            boolean skipDestroy = false;

            if (DEBUG_SWITCH) Slog.i(TAG_SWITCH, "Destroying: " + this);
            boolean isSuccessful;
            final IApplicationThread client = app.getThread();
            if (client == null) {
                Slog.w(TAG_WM, "Failed to schedule DestroyActivityItem because client is inactive");
                isSuccessful = false;
            } else {
                final DestroyActivityItem item = new DestroyActivityItem(token, finishing);
                try {
                    isSuccessful = mAtmService.getLifecycleManager().scheduleTransactionItem(
                            client, item);
                } catch (RemoteException e) {
                    // TODO(b/323801078): remove Exception when cleanup
                    isSuccessful = false;
                }
            }
            if (!isSuccessful) {
                // We can just ignore exceptions here...  if the process has crashed, our death
                // notification will clean things up.
                if (finishing) {
                    removeFromHistory(reason + " exceptionInScheduleDestroy");
                    removedFromHistory = true;
                    skipDestroy = true;
                }
            }

            nowVisible = false;

            // If the activity is finishing, we need to wait on removing it from the list to give it
            // a chance to do its cleanup.  During that time it may make calls back with its token
            // so we need to be able to find it on the list and so we don't want to remove it from
            // the list yet.  Otherwise, we can just immediately put it in the destroyed state since
            // we are not removing it from the list.
            if (finishing && !skipDestroy) {
                ProtoLog.v(WM_DEBUG_STATES, "Moving to DESTROYING: %s (destroy requested)", this);
                setState(DESTROYING,
                        "destroyActivityLocked. finishing and not skipping destroy");
                mAtmService.mH.postDelayed(mDestroyTimeoutRunnable, DESTROY_TIMEOUT);
            } else {
                ProtoLog.v(WM_DEBUG_STATES, "Moving to DESTROYED: %s "
                        + "(destroy skipped)", this);
                setState(DESTROYED,
                        "destroyActivityLocked. not finishing or skipping destroy");
                if (DEBUG_APP) Slog.v(TAG_APP, "Clearing app during destroy for activity " + this);
                detachFromProcess();
            }
        } else {
            // Remove this record from the history.
            if (finishing) {
                removeFromHistory(reason + " hadNoApp");
                removedFromHistory = true;
            } else {
                ProtoLog.v(WM_DEBUG_STATES, "Moving to DESTROYED: %s (no app)", this);
                setState(DESTROYED, "destroyActivityLocked. not finishing and had no app");
            }
        }

        return removedFromHistory;
    }

    /** Note: call {@link #cleanUp(boolean, boolean)} before this method. */
    void removeFromHistory(String reason) {
        finishActivityResults(Activity.RESULT_CANCELED,
                null /* resultData */, null /* resultGrants */);
        makeFinishingLocked();

        ProtoLog.i(WM_DEBUG_ADD_REMOVE, "Removing activity %s, reason= %s "
                        + "callers=%s", this, reason, Debug.getCallers(5));

        takeFromHistory();
        removeTimeouts();
        ProtoLog.v(WM_DEBUG_STATES, "Moving to DESTROYED: %s (removed from history)",
                this);
        setState(DESTROYED, "removeFromHistory");
        if (DEBUG_APP) Slog.v(TAG_APP, "Clearing app during remove for activity " + this);
        detachFromProcess();
        // Resume key dispatching if it is currently paused before we remove the container.
        resumeKeyDispatchingLocked();
        mDisplayContent.removeAppToken(token);

        cleanUpActivityServices();
        removeUriPermissionsLocked();
    }

    void detachFromProcess() {
        if (app != null) {
            app.removeActivity(this, false /* keepAssociation */);
        }
        app = null;
        mInputDispatchingTimeoutMillis = DEFAULT_DISPATCHING_TIMEOUT_MILLIS;
    }

    void makeFinishingLocked() {
        if (finishing) {
            return;
        }
        finishing = true;

        // Transfer the launch cookie to the next running activity above this in the same task.
        if (mLaunchCookie != null && mState != RESUMED && task != null && !task.mInRemoveTask
                && !task.isClearingToReuseTask()) {
            final ActivityRecord nextCookieTarget = task.getActivity(
                    // Intend to only associate the same app by checking uid.
                    r -> r.mLaunchCookie == null && !r.finishing && r.isUid(getUid()),
                    this, false /* includeBoundary */, false /* traverseTopToBottom */);
            if (nextCookieTarget != null) {
                ProtoLog.v(WM_DEBUG_WINDOW_TRANSITIONS,
                        "Transferring launch cookie=%s on finish from=%s(%d) to=%s(%d)",
                        mLaunchCookie, packageName, System.identityHashCode(this),
                        nextCookieTarget.packageName, System.identityHashCode(nextCookieTarget));
                nextCookieTarget.mLaunchCookie = mLaunchCookie;
                mLaunchCookie = null;
            }
        }

        final TaskFragment taskFragment = getTaskFragment();
        if (taskFragment != null) {
            final Task task = taskFragment.getTask();
            if (task != null && task.isClearingToReuseTask()
                    && taskFragment.getTopNonFinishingActivity() == null) {
                taskFragment.mClearedTaskForReuse = true;
            }
            taskFragment.sendTaskFragmentInfoChanged();
        }
        if (mAppStopped) {
            abortAndClearOptionsAnimation();
        }
        if (mDisplayContent != null) {
            mDisplayContent.mUnknownAppVisibilityController.appRemovedOrHidden(this);
        }
    }

    /**
     * This method is to only be called from the client via binder when the activity is destroyed
     * AND finished.
     */
    void destroyed(String reason) {
        removeDestroyTimeout();

        ProtoLog.d(WM_DEBUG_CONTAINERS, "activityDestroyedLocked: r=%s", this);

        if (!isState(DESTROYING, DESTROYED)) {
            throw new IllegalStateException(
                    "Reported destroyed for activity that is not destroying: r=" + this);
        }

        mTaskSupervisor.killTaskProcessesOnDestroyedIfNeeded(task);
        if (isInRootTaskLocked()) {
            cleanUp(true /* cleanServices */, false /* setState */);
            removeFromHistory(reason);
        }

        mRootWindowContainer.resumeFocusedTasksTopActivities();
    }

    /**
     * Perform the common clean-up of an activity record.  This is called both as part of
     * destroyActivityLocked() (when destroying the client-side representation) and cleaning things
     * up as a result of its hosting processing going away, in which case there is no remaining
     * client-side state to destroy so only the cleanup here is needed.
     *
     * Note: Call before {@link #removeFromHistory(String)}.
     */
    void cleanUp(boolean cleanServices, boolean setState) {
        getTaskFragment().cleanUpActivityReferences(this);
        clearLastParentBeforePip();

        // Abort and reset state if the scence transition is playing.
        final Task rootTask = getRootTask();
        if (rootTask != null) {
            rootTask.abortTranslucentActivityWaiting(this);
        }

        // Clean up the splash screen if it was still displayed.
        cleanUpSplashScreen();

        if (setState) {
            setState(DESTROYED, "cleanUp");
            if (DEBUG_APP) Slog.v(TAG_APP, "Clearing app during cleanUp for activity " + this);
            detachFromProcess();
        }

        // Inform supervisor the activity has been removed.
        mTaskSupervisor.cleanupActivity(this);

        // Remove any pending results.
        if (finishing && pendingResults != null) {
            for (WeakReference<PendingIntentRecord> apr : pendingResults) {
                PendingIntentRecord rec = apr.get();
                if (rec != null) {
                    mAtmService.mPendingIntentController.cancelIntentSender(rec,
                            false /* cleanActivity */,
                            PendingIntentRecord.CANCEL_REASON_HOSTING_ACTIVITY_DESTROYED);
                }
            }
            pendingResults = null;
        }

        if (cleanServices) {
            cleanUpActivityServices();
        }

        // Get rid of any pending idle timeouts.
        removeTimeouts();
        // Clean-up activities are no longer relaunching (e.g. app process died). Notify window
        // manager so it can update its bookkeeping.
        clearRelaunching();
    }

    boolean isRelaunching() {
        return mPendingRelaunchCount > 0;
    }

    @VisibleForTesting
    void startRelaunching() {
        if (mPendingRelaunchCount == 0) {
            mRelaunchStartTime = SystemClock.elapsedRealtime();
            if (mVisibleRequested) {
                mDisplayContent.getDisplayPolicy().addRelaunchingApp(this);
            }
        }
        clearAllDrawn();

        mPendingRelaunchCount++;
    }

    void finishRelaunching() {
        mAppCompatController.getOrientationOverrides()
                .setRelaunchingAfterRequestedOrientationChanged(false);
        mTaskSupervisor.getActivityMetricsLogger().notifyActivityRelaunched(this);

        if (mPendingRelaunchCount > 0) {
            mPendingRelaunchCount--;
            if (mPendingRelaunchCount == 0 && !isClientVisible()) {
                // Don't count if the client won't report drawn.
                finishOrAbortReplacingWindow();
            }
        } else {
            // Update keyguard flags upon finishing relaunch.
            checkKeyguardFlagsChanged();
        }

        final Task rootTask = getRootTask();
        if (rootTask != null && rootTask.shouldSleepOrShutDownActivities()) {
            // Activity is always relaunched to either resumed or paused state. If it was
            // relaunched while hidden (by keyguard or smth else), it should be stopped.
            rootTask.ensureActivitiesVisible(null /* starting */);
        }
    }

    void clearRelaunching() {
        if (mPendingRelaunchCount == 0) {
            return;
        }
        mPendingRelaunchCount = 0;
        finishOrAbortReplacingWindow();
    }

    void finishOrAbortReplacingWindow() {
        mRelaunchStartTime = 0;
        mDisplayContent.getDisplayPolicy().removeRelaunchingApp(this);
    }

    ActivityServiceConnectionsHolder getOrCreateServiceConnectionsHolder() {
        synchronized (this) {
            if (mServiceConnectionsHolder == null) {
                mServiceConnectionsHolder = new ActivityServiceConnectionsHolder(this);
            }
            return mServiceConnectionsHolder;
        }
    }

    /**
     * Perform clean-up of service connections in an activity record.
     */
    private void cleanUpActivityServices() {
        synchronized (this) {
            if (mServiceConnectionsHolder == null) {
                return;
            }
            // Throw away any services that have been bound by this activity.
            mServiceConnectionsHolder.disconnectActivityFromServices();
            // This activity record is removing, make sure not to disconnect twice.
            mServiceConnectionsHolder = null;
        }
    }

    private void updateVisibleForServiceConnection() {
        mVisibleForServiceConnection = mVisibleRequested || mState == RESUMED || mState == PAUSING;
    }

    /**
     * Detach this activity from process and clear the references to it. If the activity is
     * finishing or has no saved state or crashed many times, it will also be removed from history.
     */
    void handleAppDied() {
        final boolean remove;
        if (Process.isSdkSandboxUid(getUid())) {
            // Sandbox activities are created for SDKs run in the sandbox process, when the sandbox
            // process dies, the SDKs are unloaded and can not handle the activity, so sandbox
            // activity records should be removed.
            remove = true;
        } else if ((mRelaunchReason == RELAUNCH_REASON_WINDOWING_MODE_RESIZE
                || mRelaunchReason == RELAUNCH_REASON_FREE_RESIZE)
                && launchCount < 3 && !finishing) {
            // If the process crashed during a resize, always try to relaunch it, unless it has
            // failed more than twice. Skip activities that's already finishing cleanly by itself.
            remove = false;
        } else if ((!mHaveState && !stateNotNeeded
                && !isState(State.RESTARTING_PROCESS)) || finishing) {
            // Don't currently have state for the activity, or it is finishing -- always remove it.
            remove = true;
        } else if (!mVisibleRequested && launchCount > 2
                && lastLaunchTime > (SystemClock.uptimeMillis() - 60000)) {
            // We have launched this activity too many times since it was able to run, so give up
            // and remove it.
            remove = true;
        } else {
            // The process may be gone, but the activity lives on!
            remove = false;
        }
        if (remove) {
            ProtoLog.i(WM_DEBUG_ADD_REMOVE, "Removing activity %s hasSavedState=%b "
                    + "stateNotNeeded=%s finishing=%b state=%s callers=%s", this,
                    mHaveState, stateNotNeeded, finishing, mState, Debug.getCallers(5));
            if (!finishing || (app != null && app.isRemoved())) {
                Slog.w(TAG, "Force removing " + this + ": app died, no saved state");
                EventLogTags.writeWmFinishActivity(mUserId, System.identityHashCode(this),
                        task != null ? task.mTaskId : -1, shortComponentName,
                        "proc died without state saved");
            }
        } else {
            // We have the current state for this activity, so it can be restarted later
            // when needed.
            if (DEBUG_APP) {
                Slog.v(TAG_APP, "Keeping entry during removeHistory for activity " + this);
            }
        }
        if (task != null && task.mKillProcessesOnDestroyed) {
            mTaskSupervisor.removeTimeoutOfKillProcessesOnProcessDied(this, task);
        }
        // upgrade transition trigger to task if this is the last activity since it means we are
        // closing the task.
        final WindowContainer trigger = remove && task != null && task.getChildCount() == 1
                ? task : this;
        final Transition tr = mTransitionController.requestCloseTransitionIfNeeded(trigger);
        if (tr != null) {
            tr.collectClose(trigger);
        } else if (mTransitionController.isCollecting()) {
            mTransitionController.getCollectingTransition().collectClose(trigger);
        }
        cleanUp(true /* cleanServices */, true /* setState */);
        if (remove) {
            if (mStartingData != null && mVisible && task != null) {
                // A corner case that the app terminates its trampoline activity on a separated
                // process by killing itself. Transfer the starting window to the next activity
                // which will be visible, so the dead activity can be removed immediately (no
                // longer animating) and the reveal animation can play normally on next activity.
                final ActivityRecord top = task.topRunningActivity();
                if (top != null && !top.mVisible && top.shouldBeVisible()) {
                    top.transferStartingWindow(this);
                }
            }
            removeFromHistory("appDied");
        }
    }

    @Override
    void removeImmediately() {
        if (mState != DESTROYED) {
            Slog.w(TAG, "Force remove immediately " + this + " state=" + mState);
            // If Task#removeImmediately is called directly with alive activities, ensure that the
            // activities are destroyed and detached from process.
            destroyImmediately("removeImmediately");
            // Complete the destruction immediately because this activity will not be found in
            // hierarchy, it is unable to report completion.
            destroyed("removeImmediately");
        } else {
            onRemovedFromDisplay();
        }
        mActivityRecordInputSink.releaseSurfaceControl();

        super.removeImmediately();
    }

    void onRemovedFromDisplay() {
        if (mRemovingFromDisplay) {
            return;
        }
        mRemovingFromDisplay = true;

        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "Removing app token: %s", this);

        getDisplayContent().mUnknownAppVisibilityController.appRemovedOrHidden(this);
        mWmService.mSnapshotController.onAppRemoved(this);
        mAtmService.mStartingProcessActivities.remove(this);

        mTaskSupervisor.getActivityMetricsLogger().notifyActivityRemoved(this);
        mTaskSupervisor.mStoppingActivities.remove(this);

        mAppCompatController.getLetterboxPolicy().stop();
        mAppCompatController.getTransparentPolicy().stop();

        // Defer removal of this activity when either a child is animating, or app transition is on
        // going. The handleCompleteDeferredRemoval will continue the removal.
        final boolean delayed = isAnimating(CHILDREN, ANIMATION_TYPE_WINDOW_ANIMATION)
                || mTransitionController.inTransition();
        // Don't commit visibility if it is waiting to animate. It will be set post animation.
        if (!delayed) {
            commitVisibility(false /* visible */, true /* performLayout */);
        } else {
            setVisibleRequested(false /* visible */);
        }

        // TODO(b/169035022): move to a more-appropriate place.
        mTransitionController.collect(this);

        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                "Removing app %s delayed=%b animation=%s animating=%b", this, delayed,
                getAnimation(),
                isAnimating(TRANSITION | PARENTS, ANIMATION_TYPE_APP_TRANSITION));

        ProtoLog.v(WM_DEBUG_ADD_REMOVE, "removeAppToken: %s"
                + " delayed=%b Callers=%s", this, delayed, Debug.getCallers(4));

        if (mStartingData != null) {
            removeStartingWindow();
        }

        if (delayed && !isEmpty()) {
            // set the token aside because it has an active animation to be finished
            ProtoLog.v(WM_DEBUG_ADD_REMOVE,
                    "removeAppToken make exiting: %s", this);
            mIsExiting = true;
        } else {
            // Make sure there is no animation running on this token, so any windows associated
            // with it will be removed as soon as their animations are complete
            cancelAnimation();
            removeIfPossible();
        }

        final DisplayContent dc = getDisplayContent();
        if (dc.mFocusedApp == this) {
            ProtoLog.v(WM_DEBUG_FOCUS_LIGHT,
                    "Removing focused app token:%s displayId=%d", this,
                    dc.getDisplayId());
            dc.setFocusedApp(null);
            mWmService.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, true /*updateInputWindows*/);
        }

        if (!delayed) {
            updateReportedVisibilityLocked();
        }
        mDisplayContent.onRunningActivityChanged();
        mRemovingFromDisplay = false;
    }

    /**
     * Returns true if the new child window we are adding to this token is considered greater than
     * the existing child window in this token in terms of z-order.
     */
    @Override
    protected boolean isFirstChildWindowGreaterThanSecond(WindowState newWindow,
            WindowState existingWindow) {
        final int type1 = newWindow.mAttrs.type;
        final int type2 = existingWindow.mAttrs.type;

        // Base application windows should be z-ordered BELOW all other windows in the app token.
        if (type1 == TYPE_BASE_APPLICATION && type2 != TYPE_BASE_APPLICATION) {
            return false;
        } else if (type1 != TYPE_BASE_APPLICATION && type2 == TYPE_BASE_APPLICATION) {
            return true;
        }

        // Starting windows should be z-ordered ABOVE all other windows in the app token.
        if (type1 == TYPE_APPLICATION_STARTING && type2 != TYPE_APPLICATION_STARTING) {
            return true;
        } else if (type1 != TYPE_APPLICATION_STARTING && type2 == TYPE_APPLICATION_STARTING) {
            return false;
        }

        // Otherwise the new window is greater than the existing window.
        return true;
    }

    /**
     * @return {@code true} if starting window is in app's hierarchy.
     */
    boolean hasStartingWindow() {
        if (mStartingData != null) {
            return true;
        }
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            if (getChildAt(i).mAttrs.type == TYPE_APPLICATION_STARTING) {
                return true;
            }
        }
        return false;
    }

    boolean isLastWindow(WindowState win) {
        return mChildren.size() == 1 && mChildren.get(0) == win;
    }

    @Override
    void addWindow(WindowState w) {
        super.addWindow(w);
        checkKeyguardFlagsChanged();
    }

    @Override
    void removeChild(WindowState child) {
        if (!mChildren.contains(child)) {
            // This can be true when testing.
            return;
        }
        super.removeChild(child);
        checkKeyguardFlagsChanged();
        updateLetterboxSurfaceIfNeeded(child);
    }

    void setAppLayoutChanges(int changes, String reason) {
        if (!mChildren.isEmpty()) {
            final DisplayContent dc = getDisplayContent();
            dc.pendingLayoutChanges |= changes;
            if (DEBUG_LAYOUT_REPEATS) {
                mWmService.mWindowPlacerLocked.debugLayoutRepeats(reason, dc.pendingLayoutChanges);
            }
        }
    }

    /**
     * Returns {@code true} if the requested orientation of this activity is the same as the
     * resolved orientation of the from activity.
     */
    private boolean isStartingOrientationCompatible(@NonNull ActivityRecord fromActivity) {
        final int fromOrientation = fromActivity.getConfiguration().orientation;
        final int requestedOrientation = getRequestedConfigurationOrientation();
        if (requestedOrientation == ORIENTATION_UNDEFINED) {
            return fromOrientation == getConfiguration().orientation;
        }
        return fromOrientation == requestedOrientation;
    }

    private boolean transferStartingWindow(@NonNull ActivityRecord fromActivity) {
        final WindowState tStartingWindow = fromActivity.mStartingWindow;
        if (tStartingWindow != null && fromActivity.mStartingSurface != null) {
            if (tStartingWindow.getParent() == null) {
                // The window has been detached from the parent, so the window cannot be transfer
                // to another activity because it may be in the remove process.
                // Don't need to remove the starting window at this point because that will happen
                // at #postWindowRemoveCleanupLocked
                return false;
            }

            // Link the fixed rotation transform to this activity since we are transferring the
            // starting window.
            if (fromActivity.hasFixedRotationTransform()) {
                mDisplayContent.handleTopActivityLaunchingInDifferentOrientation(this,
                        false /* checkOpening */);
            }
            // Do not transfer if the orientation doesn't match, redraw starting window while it is
            // on top will cause flicker.
            if (!isStartingOrientationCompatible(fromActivity)) {
                return false;
            }

            // If another activity above the activity which has starting window, allows to steal the
            // starting window if the above activity isn't drawn.
            if (task.getChildCount() >= 3
                    && fromActivity.mStartingData.mAssociatedTask == null) {
                final ActivityRecord aboveFrom = task.getActivityAbove(fromActivity);
                if (aboveFrom != null && aboveFrom != this && !aboveFrom.mReportedDrawn) {
                    return false;
                }
            }

            ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Moving existing starting %s"
                    + " from %s to %s", tStartingWindow, fromActivity, this);

            final long origId = Binder.clearCallingIdentity();
            try {
                // Transfer the starting window over to the new token.
                mStartingData = fromActivity.mStartingData;
                mStartingSurface = fromActivity.mStartingSurface;
                mStartingWindow = tStartingWindow;
                reportedVisible = fromActivity.reportedVisible;
                fromActivity.mStartingData = null;
                fromActivity.mStartingSurface = null;
                fromActivity.mStartingWindow = null;
                fromActivity.startingMoved = true;
                tStartingWindow.mToken = this;
                tStartingWindow.mActivityRecord = this;

                if (mStartingData.mRemoveAfterTransaction == AFTER_TRANSITION_FINISH) {
                    mStartingData.mRemoveAfterTransaction = AFTER_TRANSACTION_IDLE;
                }
                if (mStartingData.mRemoveAfterTransaction == AFTER_TRANSACTION_REMOVE_DIRECTLY) {
                    // The removal of starting window should wait for window drawn of current
                    // activity.
                    final WindowState mainWin = findMainWindow(false /* includeStartingApp */);
                    if (mainWin == null || !mainWin.isDrawn()) {
                        mStartingData.mRemoveAfterTransaction = AFTER_TRANSACTION_IDLE;
                        mStartingData.mPrepareRemoveAnimation = false;
                    }
                }

                ProtoLog.v(WM_DEBUG_ADD_REMOVE,
                        "Removing starting %s from %s", tStartingWindow, fromActivity);
                mTransitionController.collect(tStartingWindow);
                tStartingWindow.reparent(this, POSITION_TOP);

                // Clear the frozen insets state when transferring the existing starting window to
                // the next target activity.  In case the frozen state from a trampoline activity
                // affecting the starting window frame computation to see the window being
                // clipped if the rotation change during the transition animation.
                tStartingWindow.clearFrozenInsetsState();

                // Propagate other interesting state between the tokens. If the old token is
                // displayed, we should immediately force the new one to be displayed. If it is
                // animating, we need to move that animation to the new one.
                if (fromActivity.allDrawn) {
                    allDrawn = true;
                }
                if (fromActivity.firstWindowDrawn) {
                    firstWindowDrawn = true;
                }
                if (fromActivity.isVisible()) {
                    setVisible(true);
                    setVisibleRequested(true);
                    mVisibleSetFromTransferredStartingWindow = true;
                }
                setClientVisible(fromActivity.isClientVisible());

                if (fromActivity.isAnimating()) {
                    transferAnimation(fromActivity);

                    // When transferring an animation, we no longer need to apply an animation to
                    // the token we transfer the animation over. Thus, set this flag to indicate
                    // we've transferred the animation.
                    mTransitionChangeFlags |= FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT;
                } else if (mTransitionController.getTransitionPlayer() != null) {
                    // In the new transit system, just set this every time we transfer the window
                    mTransitionChangeFlags |= FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT;
                }
                // Post cleanup after the visibility and animation are transferred.
                fromActivity.postWindowRemoveStartingWindowCleanup(tStartingWindow);
                fromActivity.mVisibleSetFromTransferredStartingWindow = false;

                mWmService.updateFocusedWindowLocked(
                        UPDATE_FOCUS_WILL_PLACE_SURFACES, true /*updateInputWindows*/);
                getDisplayContent().setLayoutNeeded();
                mWmService.mWindowPlacerLocked.performSurfacePlacement();
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
            return true;
        } else if (fromActivity.mStartingData != null) {
            if (fromActivity.mStartingData instanceof SnapshotStartingData
                    && !isStartingOrientationCompatible(fromActivity)) {
                // Do not transfer because the snapshot will be distorted in different orientation.
                return false;
            }
            // The previous app was getting ready to show a
            // starting window, but hasn't yet done so.  Steal it!
            ProtoLog.v(WM_DEBUG_STARTING_WINDOW,
                    "Moving pending starting from %s to %s", fromActivity, this);
            mStartingData = fromActivity.mStartingData;
            fromActivity.mStartingData = null;
            fromActivity.startingMoved = true;
            scheduleAddStartingWindow();
            return true;
        }

        return false;
    }

    /**
     * Tries to transfer the starting window from a token that's above ourselves in the task but
     * not visible anymore. This is a common scenario apps use: Trampoline activity T start main
     * activity M in the same task. Now, when reopening the task, T starts on top of M but then
     * immediately finishes after, so we have to transfer T to M.
     */
    void transferStartingWindowFromHiddenAboveTokenIfNeeded() {
        final WindowState mainWin = findMainWindow(false);
        if (mainWin != null && mainWin.mWinAnimator.getShown()) {
            // This activity already has a visible window, so doesn't need to transfer the starting
            // window from above activity to here. The starting window will be removed with above
            // activity.
            return;
        }
        task.forAllActivities(fromActivity -> {
            if (fromActivity == this) return true;
            // The snapshot starting window could remove itself when receive resized request without
            // redraw, so transfer it to a different size activity could only cause flicker.
            // By schedule remove snapshot starting window, the remove process will happen when
            // transition ready, transition ready means the app window is drawn.
            final StartingData tmpStartingData = fromActivity.mStartingData;
            if (tmpStartingData != null && tmpStartingData.mAssociatedTask == null
                    && mTransitionController.isCollecting(fromActivity)
                    && tmpStartingData instanceof SnapshotStartingData) {
                final Rect fromBounds = fromActivity.getBounds();
                final Rect myBounds = getBounds();
                if (!fromBounds.equals(myBounds)) {
                    // Mark as no animation, so these changes won't merge into playing transition.
                    if (mTransitionController.inPlayingTransition(fromActivity)) {
                        mTransitionController.setNoAnimation(this);
                        mTransitionController.setNoAnimation(fromActivity);
                    }
                    fromActivity.removeStartingWindow();
                    return true;
                }
            }
            return !fromActivity.isVisibleRequested() && transferStartingWindow(fromActivity);
        });
    }

    boolean isKeyguardLocked() {
        return (mDisplayContent != null) ? mDisplayContent.isKeyguardLocked() :
                mRootWindowContainer.getDefaultDisplay().isKeyguardLocked();
    }

    void checkKeyguardFlagsChanged() {
        final boolean containsDismissKeyguard = containsDismissKeyguardWindow();
        final boolean containsShowWhenLocked = containsShowWhenLockedWindow();
        if (containsDismissKeyguard != mLastContainsDismissKeyguardWindow
                || containsShowWhenLocked != mLastContainsShowWhenLockedWindow) {
            mDisplayContent.notifyKeyguardFlagsChanged();
        }
        mLastContainsDismissKeyguardWindow = containsDismissKeyguard;
        mLastContainsShowWhenLockedWindow = containsShowWhenLocked;
        mLastContainsTurnScreenOnWindow = containsTurnScreenOnWindow();
    }

    boolean containsDismissKeyguardWindow() {
        // Window state is transient during relaunch. We are not guaranteed to be frozen during the
        // entirety of the relaunch.
        if (isRelaunching()) {
            return mLastContainsDismissKeyguardWindow;
        }

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            if ((mChildren.get(i).mAttrs.flags & FLAG_DISMISS_KEYGUARD) != 0) {
                return true;
            }
        }
        return false;
    }

    boolean containsShowWhenLockedWindow() {
        // When we are relaunching, it is possible for us to be unfrozen before our previous
        // windows have been added back. Using the cached value ensures that our previous
        // showWhenLocked preference is honored until relaunching is complete.
        if (isRelaunching()) {
            return mLastContainsShowWhenLockedWindow;
        }

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            if ((mChildren.get(i).mAttrs.flags & FLAG_SHOW_WHEN_LOCKED) != 0) {
                return true;
            }
        }

        return false;
    }

    void setShowWhenLocked(boolean showWhenLocked) {
        mShowWhenLocked = showWhenLocked;
        mAtmService.mRootWindowContainer.ensureActivitiesVisible();
    }

    void setInheritShowWhenLocked(boolean inheritShowWhenLocked) {
        mInheritShownWhenLocked = inheritShowWhenLocked;
        mAtmService.mRootWindowContainer.ensureActivitiesVisible();
    }

    /**
     * @return {@code true} if the activity windowing mode is not in
     *         {@link android.app.WindowConfiguration#WINDOWING_MODE_PINNED} and a) activity
     *         contains windows that have {@link LayoutParams#FLAG_SHOW_WHEN_LOCKED} set or if the
     *         activity has set {@link #mShowWhenLocked}, or if its user
     *         is {@link #mIsUserAlwaysVisible always-visible} or b) if the activity has set
     *         {@link #mInheritShownWhenLocked} and the activity behind this satisfies the
     *         conditions a) above.
     *         Multi-windowing mode will be exited if {@code true} is returned.
     */
    private static boolean canShowWhenLocked(ActivityRecord r) {
        if (r == null || r.getTaskFragment() == null) {
            return false;
        }
        if (canShowWhenLockedInner(r)) {
            return true;
        } else if (r.mInheritShownWhenLocked) {
            final ActivityRecord activity = r.getTaskFragment().getActivityBelow(r);
            return activity != null && canShowWhenLockedInner(activity);
        } else {
            return false;
        }
    }

    /** @see #canShowWhenLocked(ActivityRecord) */
    private static boolean canShowWhenLockedInner(@NonNull ActivityRecord r) {
        return !r.inPinnedWindowingMode() &&
                (r.mShowWhenLocked || r.containsShowWhenLockedWindow() || r.mIsUserAlwaysVisible);
    }

    /**
     *  Determines if the activity can show while lock-screen is displayed. System displays
     *  activities while lock-screen is displayed only if all activities
     *  {@link #canShowWhenLocked(ActivityRecord)}.
     *  @see #canShowWhenLocked(ActivityRecord)
     */
    boolean canShowWhenLocked() {
        if (!canShowWhenLocked(this)) {
            return false;
        }
        final TaskFragment taskFragment = getTaskFragment();
        if (taskFragment == null || !taskFragment.hasAdjacentTaskFragment()
                || !taskFragment.isEmbedded()) {
            // No embedded adjacent that need to be checked.
            return true;
        }

        // Make sure the embedded adjacent can also be shown.
        final boolean hasAdjacentNotAllowToShow = taskFragment.forOtherAdjacentTaskFragments(
                adjacentTF -> !canShowWhenLocked(adjacentTF.getTopNonFinishingActivity()));
        return !hasAdjacentNotAllowToShow;
    }

    /**
     * @return Whether we are allowed to show non-starting windows at the moment.
     */
    boolean canShowWindows() {
        return mTransitionController.isShellTransitionsEnabled()
                ? mSyncState != SYNC_STATE_WAITING_FOR_DRAW : allDrawn;
    }

    @Override
    boolean forAllActivities(Predicate<ActivityRecord> callback, boolean traverseTopToBottom) {
        return callback.test(this);
    }

    @Override
    void forAllActivities(Consumer<ActivityRecord> callback, boolean traverseTopToBottom) {
        callback.accept(this);
    }

    @Override
    ActivityRecord getActivity(Predicate<ActivityRecord> callback, boolean traverseTopToBottom,
            ActivityRecord boundary) {
        return callback.test(this) ? this : null;
    }

    void logStartActivity(int tag, Task task) {
        final Uri data = intent.getData();
        final String strData = data != null ? data.toSafeString() : null;

        EventLog.writeEvent(tag,
                mUserId, System.identityHashCode(this), task.mTaskId,
                shortComponentName, intent.getAction(),
                intent.getType(), strData, intent.getFlags());
    }

    UriPermissionOwner getUriPermissionsLocked() {
        if (uriPermissions == null) {
            uriPermissions = new UriPermissionOwner(mAtmService.mUgmInternal, this);
        }
        return uriPermissions;
    }

    void addResultLocked(ActivityRecord from, String resultWho,
            int requestCode, int resultCode,
            Intent resultData, IBinder callerToken) {
        ActivityResult r = new ActivityResult(from, resultWho,
                requestCode, resultCode, resultData, callerToken);
        if (results == null) {
            results = new ArrayList<ResultInfo>();
        }
        results.add(r);
    }

    void removeResultsLocked(ActivityRecord from, String resultWho,
            int requestCode) {
        if (results != null) {
            for (int i=results.size()-1; i>=0; i--) {
                ActivityResult r = (ActivityResult)results.get(i);
                if (r.mFrom != from) continue;
                if (r.mResultWho == null) {
                    if (resultWho != null) continue;
                } else {
                    if (!r.mResultWho.equals(resultWho)) continue;
                }
                if (r.mRequestCode != requestCode) continue;

                results.remove(i);
            }
        }
    }

    void sendResult(int callingUid, String resultWho, int requestCode, int resultCode,
            Intent data, IBinder callerToken, NeededUriGrants dataGrants) {
        sendResult(callingUid, resultWho, requestCode, resultCode, data, callerToken, dataGrants,
                false /* forceSendForMediaProjection */);
    }

    void sendResult(int callingUid, String resultWho, int requestCode, int resultCode,
            Intent data, IBinder callerToken, NeededUriGrants dataGrants,
            boolean forceSendForMediaProjection) {
        if (android.security.Flags.contentUriPermissionApis()
                && !mCallerState.hasCaller(callerToken)) {
            try {
                computeCallerInfo(callerToken, data, callingUid,
                        mAtmService.getPackageManager().getNameForUid(callingUid),
                        false /* isShareIdentityEnabled */);
                // Result callers cannot share their identity via
                // {@link ActivityOptions#setShareIdentityEnabled(boolean)} since
                // {@link android.app.Activity#setResult} doesn't have a {@link android.os.Bundle}.
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
        if (callingUid > 0) {
            mAtmService.mUgmInternal.grantUriPermissionUncheckedFromIntent(dataGrants,
                    getUriPermissionsLocked());
        }

        if (DEBUG_RESULTS) {
            Slog.v(TAG, "Send activity result to " + this
                    + " : who=" + resultWho + " req=" + requestCode
                    + " res=" + resultCode + " data=" + data
                    + " forceSendForMediaProjection=" + forceSendForMediaProjection);
        }

        if (isState(RESUMED) && attachedToProcess()) {
            final ArrayList<ResultInfo> list = new ArrayList<>();
            list.add(new ResultInfo(resultWho, requestCode, resultCode, data, callerToken));
            final ActivityResultItem item = new ActivityResultItem(token, list);
            try {
                final boolean isSuccessful = mAtmService.getLifecycleManager()
                        .scheduleTransactionItem(app.getThread(), item);
                if (isSuccessful) {
                    return;
                }
            } catch (RemoteException e) {
                // TODO(b/323801078): remove Exception when cleanup
                Slog.w(TAG, "Exception thrown sending result to " + this, e);
            }
        }

        // Schedule sending results now for Media Projection setup.
        if (forceSendForMediaProjection && attachedToProcess() && isState(STARTED, PAUSING, PAUSED,
                STOPPING, STOPPED)) {
            // Build result to be returned immediately.
            final List<ResultInfo> infos = List.of(
                    new ResultInfo(resultWho, requestCode, resultCode, data, callerToken));
            final ActivityResultItem activityResultItem = new ActivityResultItem(token, infos);
            // When the activity result is delivered, the activity will transition to RESUMED.
            // Since the activity is only resumed so the result can be immediately delivered,
            // return it to its original lifecycle state.
            final ActivityLifecycleItem lifecycleItem = getLifecycleItemForCurrentStateForResult();
            try {
                if (lifecycleItem != null) {
                    mAtmService.getLifecycleManager().scheduleTransactionItems(
                            app.getThread(), activityResultItem, lifecycleItem);
                } else {
                    Slog.w(TAG, "Unable to get the lifecycle item for state " + mState
                            + " so couldn't immediately send result");
                    mAtmService.getLifecycleManager().scheduleTransactionItem(
                            app.getThread(), activityResultItem);
                }
            } catch (RemoteException e) {
                // TODO(b/323801078): remove Exception when cleanup
                Slog.w(TAG, "Exception thrown sending result to " + this, e);
            }
            // We return here to ensure that result for media projection setup is not stored as a
            // pending result after being scheduled. This is to prevent this stored result being
            // sent again when the destination component is resumed.
            return;
        }

        addResultLocked(null /* from */, resultWho, requestCode, resultCode, data, callerToken);
    }

    /**
     * Provides a lifecycle item for the current stat. Only to be used when force sending an
     * activity result (as part of MediaProjection setup). Does not support the following states:
     * {@link State#INITIALIZING}, {@link State#RESTARTING_PROCESS},
     * {@link State#FINISHING}, {@link State#DESTROYING}, {@link State#DESTROYED}. It does not make
     * sense to force send a result to an activity in these states. Does not support
     * {@link State#RESUMED} since a resumed activity will end in the resumed state after handling
     * the result.
     *
     * @return an {@link ActivityLifecycleItem} for the current state, or {@code null} if the
     * state is not valid.
     */
    @Nullable
    private ActivityLifecycleItem getLifecycleItemForCurrentStateForResult() {
        switch (mState) {
            case STARTED:
                return new StartActivityItem(token, null);
            case PAUSING:
            case PAUSED:
                return new PauseActivityItem(token);
            case STOPPING:
            case STOPPED:
                return new StopActivityItem(token);
            default:
                // Do not send a result immediately if the activity is in state INITIALIZING,
                // RESTARTING_PROCESS, FINISHING, DESTROYING, or DESTROYED.
                return null;
        }
    }

    private void addNewIntentLocked(ReferrerIntent intent) {
        if (newIntents == null) {
            newIntents = new ArrayList<>();
        }
        newIntents.add(intent);
    }

    boolean isSleeping() {
        return task != null ? task.shouldSleepActivities() : mAtmService.isSleepingLocked();
    }

    /**
     * Deliver a new Intent to an existing activity, so that its onNewIntent()
     * method will be called at the proper time.
     */
    final void deliverNewIntentLocked(int callingUid, Intent intent, NeededUriGrants intentGrants,
            String referrer, boolean isShareIdentityEnabled, int userId, int recipientAppId) {
        IBinder callerToken = new Binder();
        if (android.security.Flags.contentUriPermissionApis()) {
            computeCallerInfo(callerToken, intent, callingUid, referrer, isShareIdentityEnabled);
        }
        // The activity now gets access to the data associated with this Intent.
        mAtmService.mUgmInternal.grantUriPermissionUncheckedFromIntent(intentGrants,
                getUriPermissionsLocked());
        if (isShareIdentityEnabled && android.security.Flags.contentUriPermissionApis()) {
            final PackageManagerInternal pmInternal = mAtmService.getPackageManagerInternalLocked();
            pmInternal.grantImplicitAccess(userId, intent, recipientAppId /*recipient*/,
                    callingUid /*visible*/, true /*direct*/);
        }
        final ReferrerIntent rintent = new ReferrerIntent(intent, getFilteredReferrer(referrer),
                callerToken);
        final boolean isTopActivityWhileSleeping = isSleeping() && isTopRunningActivity();

        // We want to immediately deliver the intent to the activity if:
        // - It is currently resumed or paused. i.e. it is currently visible to the user and we want
        //   the user to see the visual effects caused by the intent delivery now.
        // - The device is sleeping and it is the top activity behind the lock screen (b/6700897).
        if ((mState == RESUMED || mState == PAUSED || isTopActivityWhileSleeping)
                && attachedToProcess()) {
            final ArrayList<ReferrerIntent> ar = new ArrayList<>(1);
            ar.add(rintent);
            // Making sure the client state is RESUMED after transaction completed and doing
            // so only if activity is currently RESUMED. Otherwise, client may have extra
            // life-cycle calls to RESUMED (and PAUSED later).
            final NewIntentItem item = new NewIntentItem(token, ar, mState == RESUMED /* resume */);
            try {
                final boolean isSuccessful = mAtmService.getLifecycleManager()
                        .scheduleTransactionItem(app.getThread(), item);
                if (isSuccessful) {
                    return;
                }
            } catch (RemoteException e) {
                // TODO(b/323801078): remove Exception when cleanup
                Slog.w(TAG, "Exception thrown sending new intent to " + this, e);
            }
        }

        // Didn't send.
        addNewIntentLocked(rintent);
    }

    void updateOptionsLocked(ActivityOptions options) {
        if (options != null) {
            if (DEBUG_TRANSITION) Slog.i(TAG, "Update options for " + this);
            if (mPendingOptions != null) {
                mPendingOptions.abort();
            }
            setOptions(options);
        }
    }

    boolean getLaunchedFromBubble() {
        return mLaunchedFromBubble;
    }

    private void setOptions(@NonNull ActivityOptions options) {
        mLaunchedFromBubble = options.getLaunchedFromBubble();
        mPendingOptions = options;
        if (options.getAnimationType() == ANIM_REMOTE_ANIMATION) {
            mPendingRemoteAnimation = options.getRemoteAnimationAdapter();
        }
        mPendingRemoteTransition = options.getRemoteTransition();
    }

    void applyOptionsAnimation() {
        if (DEBUG_TRANSITION) Slog.i(TAG, "Applying options for " + this);
        if (mPendingRemoteAnimation != null) {
            mTransitionController.setStatusBarTransitionDelay(
                    mPendingRemoteAnimation.getStatusBarTransitionDelay());
        } else {
            if (mPendingOptions == null) {
                return;
            } else if (mPendingOptions.getAnimationType() == ANIM_SCENE_TRANSITION) {
                // Scene transition will run on the client side, so just notify transition
                // controller but don't clear the animation information from the options since they
                // need to be sent to the animating activity.
                mTransitionController.setOverrideAnimation(
                        AnimationOptions.makeSceneTransitionAnimOptions(), this, null, null);
                return;
            }
            applyOptionsAnimation(mPendingOptions, intent);
        }
        clearOptionsAnimationForSiblings();
    }

    /**
     * Apply override app transition base on options & animation type.
     */
    private void applyOptionsAnimation(ActivityOptions pendingOptions, Intent intent) {
        final int animationType = pendingOptions.getAnimationType();
        final DisplayContent displayContent = getDisplayContent();
        AnimationOptions options = null;
        IRemoteCallback startCallback = null;
        IRemoteCallback finishCallback = null;
        switch (animationType) {
            case ANIM_CUSTOM:
                options = AnimationOptions.makeCustomAnimOptions(pendingOptions.getPackageName(),
                        pendingOptions.getCustomEnterResId(), 0 /* changeResId */,
                        pendingOptions.getCustomExitResId(),
                        pendingOptions.getOverrideTaskTransition());
                startCallback = pendingOptions.getAnimationStartedListener();
                finishCallback = pendingOptions.getAnimationFinishedListener();
                break;
            case ANIM_CLIP_REVEAL:
                options = AnimationOptions.makeClipRevealAnimOptions(
                        pendingOptions.getStartX(), pendingOptions.getStartY(),
                        pendingOptions.getWidth(), pendingOptions.getHeight());
                if (intent.getSourceBounds() == null) {
                    intent.setSourceBounds(new Rect(pendingOptions.getStartX(),
                            pendingOptions.getStartY(),
                            pendingOptions.getStartX() + pendingOptions.getWidth(),
                            pendingOptions.getStartY() + pendingOptions.getHeight()));
                }
                break;
            case ANIM_SCALE_UP:
                options = AnimationOptions.makeScaleUpAnimOptions(
                        pendingOptions.getStartX(), pendingOptions.getStartY(),
                        pendingOptions.getWidth(), pendingOptions.getHeight(),
                        pendingOptions.getOverrideTaskTransition());
                if (intent.getSourceBounds() == null) {
                    intent.setSourceBounds(new Rect(pendingOptions.getStartX(),
                            pendingOptions.getStartY(),
                            pendingOptions.getStartX() + pendingOptions.getWidth(),
                            pendingOptions.getStartY() + pendingOptions.getHeight()));
                }
                break;
            case ANIM_THUMBNAIL_SCALE_UP:
            case ANIM_THUMBNAIL_SCALE_DOWN:
                final boolean scaleUp = (animationType == ANIM_THUMBNAIL_SCALE_UP);
                final HardwareBuffer buffer = pendingOptions.getThumbnail();
                options = AnimationOptions.makeThumbnailAnimOptions(buffer,
                        pendingOptions.getStartX(), pendingOptions.getStartY(), scaleUp);
                startCallback = pendingOptions.getAnimationStartedListener();
                if (intent.getSourceBounds() == null && buffer != null) {
                    intent.setSourceBounds(new Rect(pendingOptions.getStartX(),
                            pendingOptions.getStartY(),
                            pendingOptions.getStartX() + buffer.getWidth(),
                            pendingOptions.getStartY() + buffer.getHeight()));
                }
                break;
            case ANIM_THUMBNAIL_ASPECT_SCALE_UP:
            case ANIM_THUMBNAIL_ASPECT_SCALE_DOWN:
                // TODO(b/397847511): remove the related types from ActivityOptions.
                break;
            case ANIM_OPEN_CROSS_PROFILE_APPS:
                options = AnimationOptions.makeCrossProfileAnimOptions();
                break;
            case ANIM_NONE:
            case ANIM_UNDEFINED:
                break;
            default:
                Slog.e(TAG_WM, "applyOptionsLocked: Unknown animationType=" + animationType);
                break;
        }

        if (options != null) {
            mTransitionController.setOverrideAnimation(options, this, startCallback,
                    finishCallback);
        }
        final int backgroundColor = pendingOptions.getCustomBackgroundColor();
        if (backgroundColor != 0) {
            mTransitionController.setOverrideBackgroundColor(backgroundColor);
        }
    }

    void clearAllDrawn() {
        allDrawn = false;
    }

    /**
     * Returns whether the drawn window states of this {@link ActivityRecord} has considered every
     * child {@link WindowState}. A child is considered if it has been passed into
     * {@link #updateDrawnWindowStates(WindowState)} after being added. This is used to determine
     * whether states, such as {@code allDrawn}, can be set, which relies on state variables such as
     * {@code mNumInterestingWindows}, which depend on all {@link WindowState}s being considered.
     *
     * @return {@code true} If all children have been considered, {@code false}.
     */
    private boolean allDrawnStatesConsidered() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState child = mChildren.get(i);
            if (child.mightAffectAllDrawn() && !child.getDrawnStateEvaluated()) {
                return false;
            }
        }
        return true;
    }

    /**
     *  Determines if the token has finished drawing. This should only be called from
     *  {@link DisplayContent#applySurfaceChangesTransaction}
     */
    void updateAllDrawn() {
        if (!allDrawn) {
            // Number of drawn windows can be less when a window is being relaunched, wait for
            // all windows to be launched and drawn for this token be considered all drawn.
            final int numInteresting = mNumInterestingWindows;

            // We must make sure that all present children have been considered (determined by
            // {@link #allDrawnStatesConsidered}) before evaluating whether everything has been
            // drawn.
            if (numInteresting > 0 && allDrawnStatesConsidered()
                    && mNumDrawnWindows >= numInteresting && !isRelaunching()) {
                if (DEBUG_VISIBILITY) Slog.v(TAG, "allDrawn: " + this
                        + " interesting=" + numInteresting + " drawn=" + mNumDrawnWindows);
                allDrawn = true;
                // Force an additional layout pass where
                // WindowStateAnimator#commitFinishDrawingLocked() will call performShowLocked().
                if (mDisplayContent != null) {
                    mDisplayContent.setLayoutNeeded();
                }
                mWmService.mH.obtainMessage(H.NOTIFY_ACTIVITY_DRAWN, this).sendToTarget();
            }
        }
    }

    void abortAndClearOptionsAnimation() {
        if (mPendingOptions != null) {
            mPendingOptions.abort();
        }
        clearOptionsAnimation();
    }

    void clearOptionsAnimation() {
        mPendingOptions = null;
        mPendingRemoteAnimation = null;
        mPendingRemoteTransition = null;
    }

    void clearOptionsAnimationForSiblings() {
        if (task == null) {
            clearOptionsAnimation();
        } else {
            // This will clear the options for all the ActivityRecords for this Task.
            task.forAllActivities(ActivityRecord::clearOptionsAnimation);
        }
    }

    ActivityOptions getOptions() {
        return mPendingOptions;
    }

    ActivityOptions.SceneTransitionInfo takeSceneTransitionInfo() {
        if (DEBUG_TRANSITION) {
            Slog.i(TAG, "Taking SceneTransitionInfo for " + this + " callers="
                    + Debug.getCallers(6));
        }
        if (mPendingOptions == null) return null;
        final ActivityOptions opts = mPendingOptions;
        mPendingOptions = null;
        return opts.getSceneTransitionInfo();
    }

    RemoteTransition takeRemoteTransition() {
        RemoteTransition out = mPendingRemoteTransition;
        mPendingRemoteTransition = null;
        return out;
    }

    boolean allowMoveToFront() {
        return mPendingOptions == null || !mPendingOptions.getAvoidMoveToFront();
    }

    void removeUriPermissionsLocked() {
        if (uriPermissions != null) {
            uriPermissions.removeUriPermissions();
            uriPermissions = null;
        }
    }

    void pauseKeyDispatchingLocked() {
        if (!keysPaused) {
            keysPaused = true;

            if (getDisplayContent() != null) {
                getDisplayContent().getInputMonitor().pauseDispatchingLw(this);
            }
        }
    }

    void resumeKeyDispatchingLocked() {
        if (keysPaused) {
            keysPaused = false;

            if (getDisplayContent() != null) {
                getDisplayContent().getInputMonitor().resumeDispatchingLw(this);
            }
        }
    }

    private void updateTaskDescription(CharSequence description) {
        task.lastDescription = description;
    }

    void setDeferHidingClient() {
        if (Flags.removeDeferHidingClient()) {
            return;
        }
        mDeferHidingClient = true;
    }

    void clearDeferHidingClient() {
        if (Flags.removeDeferHidingClient()) {
            return;
        }
        if (!mDeferHidingClient) return;
        mDeferHidingClient = false;
        if (!mVisibleRequested) {
            // Hiding the client is no longer deferred and the app isn't visible still, go ahead and
            // update the visibility.
            setVisibility(false);
        }
    }

    boolean getDeferHidingClient() {
        return mDeferHidingClient;
    }

    boolean canAffectSystemUiFlags() {
        final TaskFragment taskFragment = getTaskFragment();
        return taskFragment != null && taskFragment.canAffectSystemUiFlags()
                && isVisible()
                && !mWaitForEnteringPinnedMode && !inPinnedWindowingMode();
    }

    @Override
    boolean isVisible() {
        // If the activity isn't hidden then it is considered visible and there is no need to check
        // its children windows to see if they are visible.
        return mVisible;
    }

    void setVisible(boolean visible) {
        if (visible != mVisible) {
            mVisible = visible;
            if (app != null) {
                mTaskSupervisor.onProcessActivityStateChanged(app, false /* forceBatch */);
            }
            scheduleAnimation();
        }
    }

    /**
     * This is the only place that writes {@link #mVisibleRequested} (except unit test). The caller
     * outside of this class should use {@link #setVisibility}.
     */
    @Override
    boolean setVisibleRequested(boolean visible) {
        if (!super.setVisibleRequested(visible)) return false;
        setInsetsFrozen(!visible);
        updateVisibleForServiceConnection();
        if (app != null) {
            mTaskSupervisor.onProcessActivityStateChanged(app, false /* forceBatch */);
        }
        logAppCompatState();
        if (!visible) {
            final InputTarget imeInputTarget = mDisplayContent.getImeInputTarget();
            mLastImeShown = imeInputTarget != null && imeInputTarget.getWindowState() != null
                    && imeInputTarget.getWindowState().mActivityRecord == this
                    && mDisplayContent.mInputMethodWindow != null
                    && mDisplayContent.mInputMethodWindow.isVisible();
            finishOrAbortReplacingWindow();
        }
        return true;
    }

    @Override
    protected boolean onChildVisibleRequestedChanged(@Nullable WindowContainer child) {
        // Activity manages visibleRequested directly (it's not determined by children)
        return false;
    }

    /**
     * Set visibility on this {@link ActivityRecord}
     *
     * <p class="note"><strong>Note: </strong>This function might not update the visibility of
     * this {@link ActivityRecord} immediately. In case we are preparing an app transition, we
     * delay changing the visibility of this {@link ActivityRecord} until we execute that
     * transition.</p>
     *
     * @param visible {@code true} if the {@link ActivityRecord} should become visible, otherwise
     *                this should become invisible.
     */
    void setVisibility(boolean visible) {
        if (getParent() == null) {
            Slog.w(TAG_WM, "Attempted to set visibility of non-existing app token: " + token);
            return;
        }
        if (visible == mVisibleRequested && visible == mVisible && visible == isClientVisible()
                && mTransitionController.isShellTransitionsEnabled()) {
            // For shell transition, it is no-op if there is no state change.
            return;
        }
        if (visible) {
            mDeferHidingClient = false;
        }
        setVisibility(visible, mDeferHidingClient);
        mAtmService.addWindowLayoutReasons(
                ActivityTaskManagerService.LAYOUT_REASON_VISIBILITY_CHANGED);
        mTaskSupervisor.getActivityMetricsLogger().notifyVisibilityChanged(this);
        mTaskSupervisor.mAppVisibilitiesChangedSinceLastPause = true;
    }

    private void setVisibility(boolean visible, boolean deferHidingClient) {
        // Don't set visibility to false if we were already not visible. This prevents WM from
        // adding the app to the closing app list which doesn't make sense for something that is
        // already not visible. However, set visibility to true even if we are already visible.
        // This makes sure the app is added to the opening apps list so that the right
        // transition can be selected.
        // TODO: Probably a good idea to separate the concept of opening/closing apps from the
        // concept of setting visibility...
        if (!visible && !mVisibleRequested) {

            if (!deferHidingClient && mLastDeferHidingClient) {
                // We previously deferred telling the client to hide itself when visibility was
                // initially set to false. Now we would like it to hide, so go ahead and set it.
                mLastDeferHidingClient = deferHidingClient;
                setClientVisible(false);
            }
            return;
        }

        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                "setAppVisibility(%s, visible=%b): visible=%b mVisibleRequested=%b Callers=%s",
                token, visible, isVisible(), mVisibleRequested,
                Debug.getCallers(6));

        // Before setting mVisibleRequested so we can track changes.
        boolean isCollecting = false;
        boolean inFinishingTransition = false;
        if (mTransitionController.isShellTransitionsEnabled()) {
            if (mTransitionController.isCollecting()) {
                isCollecting = true;
                mTransitionController.collect(this);
            } else {
                // Failsafe to make sure that we show any activities that were incorrectly hidden
                // during a transition. If this vis-change is a result of finishing, ignore it.
                // Finish should only ever commit visibility=false, so we can check full containment
                // rather than just direct membership.
                inFinishingTransition = mTransitionController.inFinishingTransition(this);
                if (!inFinishingTransition) {
                    if (visible) {
                        if (!mDisplayContent.isSleeping() || canShowWhenLocked()) {
                            mTransitionController.onVisibleWithoutCollectingTransition(this,
                                    Debug.getCallers(1, 1));
                        }
                    } else if (!mDisplayContent.isSleeping()) {
                        Slog.w(TAG, "Set invisible without transition " + this);
                    }
                }
            }
        }

        mAtmService.mBackNavigationController.onAppVisibilityChanged(this, visible);

        setVisibleRequested(visible);
        mLastDeferHidingClient = deferHidingClient;

        if (!visible) {
            // Because starting window was transferred, this activity may be a trampoline which has
            // been occluded by next activity. If it has added windows, set client visibility
            // immediately to avoid the client getting RELAYOUT_RES_FIRST_TIME from relayout and
            // drawing an unnecessary frame.
            if (startingMoved && !firstWindowDrawn && hasChild()) {
                setClientVisible(false);
            }
        } else {
            startingMoved = false;
            // If the token is currently hidden (should be the common case), or has been
            // stopped, then we need to set up to wait for its windows to be ready.
            if (!isVisible() || mAppStopped) {
                clearAllDrawn();
            }

            // In the case where we are making an app visible but holding off for a transition,
            // we still need to tell the client to make its windows visible so they get drawn.
            // Otherwise, we will wait on performing the transition until all windows have been
            // drawn, they never will be, and we are sad.
            setClientVisible(true);

            if (!mWmService.mFlags.mEnsureWallpaperInTransitions) {
                requestUpdateWallpaperIfNeeded();
            }

            ProtoLog.v(WM_DEBUG_ADD_REMOVE, "No longer Stopped: %s", this);
            mAppStopped = false;

            transferStartingWindowFromHiddenAboveTokenIfNeeded();
        }
        if (mWmService.mFlags.mEnsureWallpaperInTransitions) {
            requestUpdateWallpaperIfNeeded();
        }

        // Defer committing visibility until transition starts.
        if (isCollecting) {
            // It may be occluded by the activity above that calls convertFromTranslucent().
            // Or it may be restoring transient launch to invisible when finishing transition.
            if (!visible) {
                if (mTransitionController.inPlayingTransition(this)) {
                    mTransitionChangeFlags |= FLAG_IS_OCCLUDED;
                    if (mTransitionController.mFinishingTransition != null
                            && mTransitionController.mFinishingTransition.isTransientLaunch(this)) {
                        mTransitionChangeFlags |= FLAGS_IS_OCCLUDED_NO_ANIMATION;
                    }
                }
            } else {
                mTransitionChangeFlags &= ~FLAG_IS_OCCLUDED;
            }
            return;
        }
        if (inFinishingTransition) {
            // Let the finishing transition commit the visibility, but let the controller know
            // about it so that we can recover from degenerate cases.
            mTransitionController.mValidateCommitVis.add(this);
            return;
        }

        commitVisibility(visible, true /* performLayout */);
        updateReportedVisibilityLocked();
    }

    /**
     * Update visibility to this {@link ActivityRecord}.
     *
     * <p class="note"><strong>Note: </strong> Unlike {@link #setVisibility}, this immediately
     * updates the visibility without starting an app transition. Since this function may start
     * animation on {@link WindowState} depending on app transition animation status, an app
     * transition animation must be started before calling this function if necessary.</p>
     *
     * @param visible {@code true} if this {@link ActivityRecord} should become visible, otherwise
     *                this should become invisible.
     * @param performLayout if {@code true}, perform surface placement after committing visibility.
     * @param fromTransition {@code true} if this is part of finishing a transition.
     */
    void commitVisibility(boolean visible, boolean performLayout, boolean fromTransition) {
        // Reset the state of mVisibleSetFromTransferredStartingWindow since visibility is actually
        // been set by the app now.
        mVisibleSetFromTransferredStartingWindow = false;
        if (visible == isVisible()) {
            return;
        }

        if (!visible) {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                mChildren.get(i).onAppCommitInvisible();
            }
        }
        setVisible(visible);
        setVisibleRequested(visible);
        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS, "commitVisibility: %s: visible=%b"
                        + " visibleRequested=%b, inTransition=%b, caller=%s",
                this, visible, mVisibleRequested, inTransition(),
                Debug.getCallers(5));
        if (visible) {
            // If we are being set visible, and the starting window is not yet displayed,
            // then make sure it doesn't get displayed.
            if (mStartingWindow != null && !mStartingWindow.isDrawn()
                    && (firstWindowDrawn || allDrawn)) {
                mStartingWindow.clearPolicyVisibilityFlag(LEGACY_POLICY_VISIBILITY);
                mStartingWindow.mLegacyPolicyVisibilityAfterAnim = false;
            }
        }
        // dispatchTaskInfoChangedIfNeeded() right after ActivityRecord#setVisibility() can report
        // the stale visible state, because the state will be updated after the app transition.
        // So tries to report the actual visible state again where the state is changed.
        Task task = getOrganizedTask();
        while (task != null) {
            task.dispatchTaskInfoChangedIfNeeded(false /* force */);
            task = task.getParent().asTask();
        }
        final DisplayContent displayContent = getDisplayContent();
        displayContent.getInputMonitor().setUpdateInputWindowsNeededLw();
        if (performLayout) {
            mWmService.updateFocusedWindowLocked(UPDATE_FOCUS_WILL_PLACE_SURFACES,
                    false /*updateInputWindows*/);
            mWmService.mWindowPlacerLocked.performSurfacePlacement();
        }
        displayContent.getInputMonitor().updateInputWindowsLw(false /*force*/);
        mTransitionChangeFlags = 0;

        // Set client visibility if:
        // 1. The activity is becoming visible. This is usually no-op because assume that
        //    setVisibility(true) should have been called. Just in case if that was missed.
        // 2. The activity is becoming invisible and not RESUMED state (it is usually PAUSED unless
        //    the activity is transient-hide). If the state is RESUMED, setVisibility(false) will be
        //    called until activityStopped. This is to avoid crashing apps that assume its view root
        //    won't be invisible before the activity is paused.
        if (visible || mState != RESUMED) {
            setClientVisible(visible);
        }
        // Notify the visibility change outside of transition in case onTransitionFinish is not
        // called for updating snapshot states.
        if (!fromTransition) {
            mWmService.mSnapshotController.notifyAppVisibilityChanged(this, visible);
            if (visible) {
                // In case the activity becomes visible without transition, the client still expects
                // to receive Activity#onEnterAnimationComplete.
                mEnteringAnimation = true;
                mWmService.mActivityManagerAppTransitionNotifier.onAppTransitionFinishedLocked(
                        token);
            }
        }
    }

    void commitVisibility(boolean visible, boolean performLayout) {
        commitVisibility(visible, performLayout, false /* fromTransition */);
    }

    /**
     * Sets whether safe region bounds are needed for the Activity. This is called from
     * {@link ActivityStarter} after the source record is created.
     */
    void setNeedsSafeRegionBounds(boolean needsSafeRegionBounds) {
        mAppCompatController.getSafeRegionPolicy().setNeedsSafeRegionBounds(needsSafeRegionBounds);
    }

    /** Updates draw state and shows drawn windows. */
    void commitFinishDrawing(SurfaceControl.Transaction t) {
        boolean committed = false;
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            committed |= mChildren.get(i).commitFinishDrawing(t);
        }
        if (committed) {
            requestUpdateWallpaperIfNeeded();
        }
    }

    /**
     * See {@link Activity#setRecentsScreenshotEnabled}.
     */
    void setRecentsScreenshotEnabled(boolean enabled) {
        mEnableRecentsScreenshot = enabled;
    }

    /**
     * Retrieves whether we'd like to generate a snapshot that's based solely on the theme. This is
     * the case when preview screenshots are disabled {@link #setRecentsScreenshotEnabled} or when
     * we can't take a snapshot for other reasons, for example, if we have a secure window.
     *
     * @return True if we need to generate an app theme snapshot, false if we'd like to take a real
     *         screenshot.
     */
    boolean shouldUseAppThemeSnapshot() {
        return !mEnableRecentsScreenshot || forAllWindows(WindowState::isSecureLocked,
                true /* topToBottom */);
    }

    /**
     * Sets whether the current launch can turn the screen on.
     * @see #currentLaunchCanTurnScreenOn()
     */
    void setCurrentLaunchCanTurnScreenOn(boolean currentLaunchCanTurnScreenOn) {
        mCurrentLaunchCanTurnScreenOn = currentLaunchCanTurnScreenOn;
    }

    /**
     * Indicates whether the current launch can turn the screen on. This is to prevent multiple
     * relayouts from turning the screen back on. The screen should only turn on at most
     * once per activity resume.
     * <p>
     * Note this flag is only meaningful when {@link WindowManager.LayoutParams#FLAG_TURN_SCREEN_ON}
     * or {@link ActivityRecord#canTurnScreenOn} is set.
     *
     * @return {@code true} if the activity is ready to turn on the screen.
     */
    boolean currentLaunchCanTurnScreenOn() {
        return mCurrentLaunchCanTurnScreenOn;
    }

    void setState(State state, String reason) {
        ProtoLog.v(WM_DEBUG_STATES, "State movement: %s from:%s to:%s reason:%s",
                this, mState, state, reason);

        if (state == mState) {
            // No need to do anything if state doesn't change.
            ProtoLog.v(WM_DEBUG_STATES, "State unchanged from:%s", state);
            return;
        }

        final State prevState = mState;
        mState = state;

        if (getTaskFragment() != null) {
            getTaskFragment().onActivityStateChanged(this, state, reason);
        }

        // The WindowManager interprets the app stopping signal as
        // an indication that the Surface will eventually be destroyed.
        // This however isn't necessarily true if we are going to sleep.
        if (state == STOPPING && !isSleeping()) {
            if (getParent() == null) {
                Slog.w(TAG_WM, "Attempted to notify stopping on non-existing app token: "
                        + token);
                return;
            }
        }
        updateVisibleForServiceConnection();
        if (app != null) {
            mTaskSupervisor.onProcessActivityStateChanged(app, false /* forceBatch */);
        }

        switch (state) {
            case RESUMED:
                mAtmService.updateBatteryStats(this, true);
                mAtmService.updateActivityUsageStats(this, Event.ACTIVITY_RESUMED);
                // Fall through.
            case STARTED:
                // Update process info while making an activity from invisible to visible, to make
                // sure the process state is updated to foreground.
                if (app != null) {
                    app.updateProcessInfo(false /* updateServiceConnectionActivities */,
                            true /* activityChange */, true /* updateOomAdj */,
                            true /* addPendingTopUid */);
                }
                mAtmService.mH.post(this::notifyActivityStartedToContentCaptureService);
                break;
            case PAUSED:
                mAtmService.updateBatteryStats(this, false);
                mAtmService.updateActivityUsageStats(this, Event.ACTIVITY_PAUSED);
                break;
            case STOPPING:
                // It is possible that an Activity is scheduled to be STOPPED directly from RESUMED
                // state. Updating the PAUSED usage state in that case, since the Activity will be
                // STOPPED while cycled through the PAUSED state.
                if (prevState == RESUMED) {
                    mAtmService.updateActivityUsageStats(this, Event.ACTIVITY_PAUSED);
                }
                break;
            case STOPPED:
                mAtmService.updateActivityUsageStats(this, Event.ACTIVITY_STOPPED);
                if (mDisplayContent != null) {
                    mDisplayContent.mUnknownAppVisibilityController.appRemovedOrHidden(this);
                }
                break;
            case DESTROYED:
                if (app != null && (mVisible || mVisibleRequested)) {
                    // The app may be died while visible (no PAUSED state).
                    mAtmService.updateBatteryStats(this, false);
                }
                mAtmService.updateActivityUsageStats(this, Event.ACTIVITY_DESTROYED);
                idle = false;
                // Fall through.
            case DESTROYING:
                if (app != null && !app.hasActivities()) {
                    // Update any services we are bound to that might care about whether
                    // their client may have activities.
                    // No longer have activities, so update LRU list and oom adj.
                    app.updateProcessInfo(true /* updateServiceConnectionActivities */,
                            false /* activityChange */, true /* updateOomAdj */,
                            false /* addPendingTopUid */);
                }
                break;
        }
    }

    private void notifyActivityStartedToContentCaptureService() {
        final ContentCaptureManagerInternal contentCaptureService =
                LocalServices.getService(ContentCaptureManagerInternal.class);
        if (contentCaptureService != null) {
            // For ACTIVITY_STARTED content capture is directly invoked to avoid persisting
            // to UsageStats.
            contentCaptureService.notifyActivityEvent(mUserId, mActivityComponent,
                    ActivityEvent.TYPE_ACTIVITY_STARTED,
                    new ActivityId(getTask() != null ? getTask().mTaskId : INVALID_TASK_ID,
                            shareableActivityToken));

            contentCaptureService.sendActivityStartAssistData(mUserId,
                                shareableActivityToken, intent);
        }
    }

    State getState() {
        return mState;
    }

    /**
     * Returns {@code true} if the Activity is in the specified state.
     */
    boolean isState(State state) {
        return state == mState;
    }

    /**
     * Returns {@code true} if the Activity is in one of the specified states.
     */
    boolean isState(State state1, State state2) {
        return state1 == mState || state2 == mState;
    }

    /**
     * Returns {@code true} if the Activity is in one of the specified states.
     */
    boolean isState(State state1, State state2, State state3) {
        return state1 == mState || state2 == mState || state3 == mState;
    }

    /**
     * Returns {@code true} if the Activity is in one of the specified states.
     */
    boolean isState(State state1, State state2, State state3, State state4) {
        return state1 == mState || state2 == mState || state3 == mState || state4 == mState;
    }

    /**
     * Returns {@code true} if the Activity is in one of the specified states.
     */
    boolean isState(State state1, State state2, State state3, State state4, State state5) {
        return state1 == mState || state2 == mState || state3 == mState || state4 == mState
                || state5 == mState;
    }

    /**
     * Returns {@code true} if the Activity is in one of the specified states.
     */
    boolean isState(State state1, State state2, State state3, State state4, State state5,
            State state6) {
        return state1 == mState || state2 == mState || state3 == mState || state4 == mState
                || state5 == mState || state6 == mState;
    }

    void destroySurfaces() {
        destroySurfaces(false /*cleanupOnResume*/);
    }

    /**
     * Destroy surfaces which have been marked as eligible by the animator, taking care to ensure
     * the client has finished with them.
     *
     * @param cleanupOnResume whether this is done when app is resumed without fully stopped. If
     * set to true, destroy only surfaces of removed windows, and clear relevant flags of the
     * others so that they are ready to be reused. If set to false (common case), destroy all
     * surfaces that's eligible, if the app is already stopped.
     */
    private void destroySurfaces(boolean cleanupOnResume) {
        boolean destroyedSomething = false;

        // Copying to a different list as multiple children can be removed.
        final ArrayList<WindowState> children = new ArrayList<>(mChildren);
        for (int i = children.size() - 1; i >= 0; i--) {
            final WindowState win = children.get(i);
            destroyedSomething |= win.destroySurface(cleanupOnResume, mAppStopped);
        }
        if (destroyedSomething) {
            final DisplayContent dc = getDisplayContent();
            dc.assignWindowLayers(true /*setLayoutNeeded*/);
            updateLetterboxSurfaceIfNeeded(null);
        }
    }

    void notifyAppResumed() {
        if (getParent() == null) {
            Slog.w(TAG_WM, "Attempted to notify resumed of non-existing app token: " + token);
            return;
        }
        final boolean wasStopped = mAppStopped;
        ProtoLog.v(WM_DEBUG_ADD_REMOVE, "notifyAppResumed: wasStopped=%b %s",
                wasStopped, this);
        mAppStopped = false;
        // Allow the window to turn the screen on once the app is started and resumed.
        if (mAtmService.getActivityStartController().isInExecution()) {
            setCurrentLaunchCanTurnScreenOn(true);
        }

        if (!wasStopped) {
            destroySurfaces(true /*cleanupOnResume*/);
        }
    }

    /**
     * Suppress transition until the new activity becomes ready, otherwise the keyguard can appear
     * for a short amount of time before the new process with the new activity had the ability to
     * set its showWhenLocked flags.
     */
    void notifyUnknownVisibilityLaunchedForKeyguardTransition() {
        // No display activities never add a window, so there is no point in waiting them for
        // relayout.
        if (mNoDisplay || !isKeyguardLocked()) {
            return;
        }

        mDisplayContent.mUnknownAppVisibilityController.notifyLaunched(this);
    }

    /** @return {@code true} if this activity should be made visible. */
    private boolean shouldBeVisible(boolean behindOccludedContainer, boolean ignoringKeyguard) {
        updateVisibilityIgnoringKeyguard(behindOccludedContainer);

        if (ignoringKeyguard) {
            return visibleIgnoringKeyguard;
        }

        return shouldBeVisibleUnchecked();
    }

    boolean shouldBeVisibleUnchecked() {
        final Task rootTask = getRootTask();
        if (rootTask == null || !visibleIgnoringKeyguard) {
            return false;
        }

        // Activity in a root pinned task should not be visible if the root task is in force
        // hidden state.
        // Typically due to the FLAG_FORCE_HIDDEN_FOR_PINNED_TASK set on the root task, which is a
        // work around to send onStop before windowing mode change callbacks.
        // See also ActivityTaskSupervisor#removePinnedRootTaskInSurfaceTransaction
        // TODO: Should we ever be visible if the rootTask/task is invisible?
        if (inPinnedWindowingMode() && rootTask.isForceHidden()) {
            return false;
        }

        // Untrusted embedded activity can be visible only if there is no other overlay window.
        if (hasOverlayOverUntrustedModeEmbedded()) {
            return false;
        }

        // A presentation stopps all activities behind on the same display.
        if (mWmService.mPresentationController.shouldOccludeActivities(getDisplayId())) {
            return false;
        }

        // Check if the activity is on a sleeping display and keyguard is not going away (to
        // align with TaskFragment#shouldSleepActivities), canTurnScreenOn will also check keyguard
        // visibility
        if (mDisplayContent.isSleeping() && !mDisplayContent.isKeyguardGoingAway()) {
            return canTurnScreenOn();
        } else {
            return mTaskSupervisor.getKeyguardController().checkKeyguardVisibility(this);
        }
    }

    /**
     * Checks if there are any activities or other containers that belong to the same task on top of
     * this activity when embedded in untrusted mode.
     */
    boolean hasOverlayOverUntrustedModeEmbedded() {
        if (!isEmbeddedInUntrustedMode() || getTask() == null) {
            // The activity is not embedded in untrusted mode.
            return false;
        }

        // Check if there are any activities with different UID occluding partially the activity
        // that is embedded in untrusted mode. Traverse bottom to top with boundary so that it will
        // only check activities above this activity.
        final ActivityRecord differentUidOverlayActivity = getTask().getActivity(
                a -> !a.finishing && a.getUid() != getUid() && Rect.intersects(a.getBounds(),
                        getBounds()), this /* boundary */, false /* includeBoundary */,
                false /* traverseTopToBottom */);
        return differentUidOverlayActivity != null;
    }

    void updateVisibilityIgnoringKeyguard(boolean behindOccludedContainer) {
        visibleIgnoringKeyguard = (!behindOccludedContainer || mLaunchTaskBehind)
                && showToCurrentUser();
    }

    boolean shouldBeVisible() {
        return shouldBeVisible(false /* ignoringKeyguard */);
    }

    boolean shouldBeVisible(boolean ignoringKeyguard) {
        final Task task = getTask();
        if (task == null) {
            return false;
        }

        final boolean behindOccludedContainer = !task.shouldBeVisible(null /* starting */)
                || task.getOccludingActivityAbove(this) != null;
        return shouldBeVisible(behindOccludedContainer, ignoringKeyguard);
    }

    void makeVisibleIfNeeded(ActivityRecord starting, boolean reportToClient) {
        // This activity is not currently visible, but is running. Tell it to become visible.
        if ((mState == RESUMED && mVisibleRequested) || this == starting) {
            if (DEBUG_VISIBILITY) Slog.d(TAG_VISIBILITY,
                    "Not making visible, r=" + this + " state=" + mState + " starting=" + starting);
            return;
        }

        // If this activity is paused, tell it to now show its window.
        if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY,
                "Making visible and scheduling visibility: " + this);
        final Task rootTask = getRootTask();
        try {
            if (rootTask.mTranslucentActivityWaiting != null) {
                updateOptionsLocked(returningOptions);
                rootTask.mUndrawnActivitiesBelowTopTranslucent.add(this);
            }
            setVisibility(true);
            app.postPendingUiCleanMsg(true);
            if (reportToClient) {
                mClientVisibilityDeferred = false;
                makeActiveIfNeeded(starting);
            } else {
                mClientVisibilityDeferred = true;
            }
            // The activity may be waiting for stop, but that is no longer appropriate for it.
            mTaskSupervisor.mStoppingActivities.remove(this);
        } catch (Exception e) {
            // Just skip on any failure; we'll make it visible when it next restarts.
            Slog.w(TAG, "Exception thrown making visible: " + intent.getComponent(), e);
        }
        handleAlreadyVisible();
    }

    void makeInvisible() {
        if (!mVisibleRequested) {
            if (DEBUG_VISIBILITY) Slog.v(TAG_VISIBILITY, "Already invisible: " + this);
            return;
        }
        // Now for any activities that aren't visible to the user, make sure they no longer are
        // keeping the screen frozen.
        if (DEBUG_VISIBILITY) {
            Slog.v(TAG_VISIBILITY, "Making invisible: " + this + ", state=" + mState);
        }
        try {
            final boolean canEnterPictureInPicture = checkEnterPictureInPictureState(
                    "makeInvisible", true /* beforeStopping */);
            // Defer telling the client it is hidden if it can enter Pip and isn't current paused,
            // stopped or stopping. This gives it a chance to enter Pip in onPause().
            final boolean deferHidingClient = canEnterPictureInPicture
                    && !isState(STARTED, STOPPING, STOPPED, PAUSED);
            if (deferHidingClient) {
                setDeferHidingClient();
            } else {
                clearDeferHidingClient();
            }
            setVisibility(false);

            switch (mState) {
                case RESUMED:
                case INITIALIZING:
                case PAUSING:
                case PAUSED:
                case STARTED:
                    addToStopping(true /* scheduleIdle */,
                            canEnterPictureInPicture /* idleDelayed */, "makeInvisible");
                    break;

                default:
                    break;
            }
        } catch (Exception e) {
            // Just skip on any failure; we'll make it visible when it next restarts.
            Slog.w(TAG, "Exception thrown making hidden: " + intent.getComponent(), e);
        }
    }

    /**
     * Make activity resumed or paused if needed.
     * @param activeActivity an activity that is resumed or just completed pause action.
     *                       We won't change the state of this activity.
     */
    boolean makeActiveIfNeeded(ActivityRecord activeActivity) {
        if (shouldResumeActivity(activeActivity)) {
            if (DEBUG_VISIBILITY) {
                Slog.v(TAG_VISIBILITY, "Resume visible activity, " + this);
            }
            return getRootTask().resumeTopActivityUncheckedLocked(activeActivity /* prev */,
                    null /* options */, false /* skipPause */);
        } else if (shouldPauseActivity(activeActivity)) {
            if (DEBUG_VISIBILITY) {
                Slog.v(TAG_VISIBILITY, "Pause visible activity, " + this);
            }
            // An activity must be in the {@link PAUSING} state for the system to validate
            // the move to {@link PAUSED}.
            setState(PAUSING, "makeActiveIfNeeded");
            EventLogTags.writeWmPauseActivity(mUserId, System.identityHashCode(this),
                    shortComponentName, "userLeaving=false", "make-active");
            final PauseActivityItem item = new PauseActivityItem(token, finishing,
                    false /* userLeaving */, false /* dontReport */, mAutoEnteringPip);
            try {
                mAtmService.getLifecycleManager().scheduleTransactionItem(app.getThread(), item);
            } catch (RemoteException e) {
                // TODO(b/323801078): remove Exception when cleanup
                Slog.w(TAG, "Exception thrown sending pause: " + intent.getComponent(), e);
            }
        } else if (shouldStartActivity()) {
            if (DEBUG_VISIBILITY) {
                Slog.v(TAG_VISIBILITY, "Start visible activity, " + this);
            }
            setState(STARTED, "makeActiveIfNeeded");
            setActivityBoost(true);

            final StartActivityItem item = new StartActivityItem(token, takeSceneTransitionInfo());
            try {
                mAtmService.getLifecycleManager().scheduleTransactionItem(app.getThread(), item);
            } catch (RemoteException e) {
                // TODO(b/323801078): remove Exception when cleanup
                Slog.w(TAG, "Exception thrown sending start: " + intent.getComponent(), e);
                setActivityBoost(false);
            }
            // The activity may be waiting for stop, but that is no longer appropriate if we are
            // starting the activity again
            mTaskSupervisor.mStoppingActivities.remove(this);
        }
        return false;
    }

    /**
     * Check if activity should be moved to PAUSED state. The activity:
     * - should be eligible to be made active (see {@link #shouldMakeActive(ActivityRecord)})
     * - should be non-focusable
     * - should not be currently pausing or paused
     * @param activeActivity the activity that is active or just completed pause action. We won't
     *                       resume if this activity is active.
     */
    @VisibleForTesting
    boolean shouldPauseActivity(ActivityRecord activeActivity) {
        return shouldMakeActive(activeActivity) && !isFocusable() && !isState(PAUSING, PAUSED)
                // We will only allow pausing if results is null, otherwise it will cause this
                // activity to resume before getting result
                && (results == null);
    }

    /**
     * Check if activity should be moved to RESUMED state.
     * See {@link #shouldBeResumed(ActivityRecord)}
     * @param activeActivity the activity that is active or just completed pause action. We won't
     *                       resume if this activity is active.
     */
    @VisibleForTesting
    boolean shouldResumeActivity(ActivityRecord activeActivity) {
        return shouldBeResumed(activeActivity) && !isState(RESUMED);
    }

    /**
     * Check if activity should be RESUMED now. The activity:
     * - should be eligible to be made active (see {@link #shouldMakeActive(ActivityRecord)})
     * - should be focusable
     */
    private boolean shouldBeResumed(ActivityRecord activeActivity) {
        return shouldMakeActive(activeActivity) && isFocusable()
                && getTaskFragment().getVisibility(activeActivity)
                        == TASK_FRAGMENT_VISIBILITY_VISIBLE
                && canResumeByCompat();
    }

    /**
     * Check if activity should be moved to STARTED state.
     * NOTE: This will not check if activity should be made paused or resumed first, so it must only
     * be called after checking with {@link #shouldResumeActivity(ActivityRecord)}
     * and {@link #shouldPauseActivity(ActivityRecord)}.
     */
    private boolean shouldStartActivity() {
        return mVisibleRequested && (isState(STOPPED) || isState(STOPPING));
    }

    /**
     * Check if activity is eligible to be made active (resumed of paused). The activity:
     * - should be paused, stopped or stopping
     * - should not be the currently active one or launching behind other tasks
     * - should be either the topmost in task, or right below the top activity that is finishing
     * If all of these conditions are not met at the same time, the activity cannot be made active.
     */
    @VisibleForTesting
    boolean shouldMakeActive(ActivityRecord activeActivity) {
        // If the activity is stopped, stopping, cycle to an active state. We avoid doing
        // this when there is an activity waiting to become translucent as the extra binder
        // calls will lead to noticeable jank. A later call to
        // Task#ensureActivitiesVisible will bring the activity to a proper
        // active state.
        if (!isState(STARTED, RESUMED, PAUSED, STOPPED, STOPPING)
                // TODO (b/185876784) Check could we remove the check condition
                //  mTranslucentActivityWaiting != null here
                || getRootTask().mTranslucentActivityWaiting != null) {
            return false;
        }

        if (this == activeActivity) {
            return false;
        }

        if (!mTaskSupervisor.readyToResume()) {
            // Making active is currently deferred (e.g. because an activity launch is in progress).
            return false;
        }

        if (this.mLaunchTaskBehind) {
            // This activity is being launched from behind, which means that it's not intended to be
            // presented to user right now, even if it's set to be visible.
            return false;
        }

        // Check if position in task allows to become paused
        if (!task.hasChild(this)) {
            throw new IllegalStateException("Activity not found in its task");
        }
        return getTaskFragment().topRunningActivity() == this;
    }

    void handleAlreadyVisible() {
        try {
            if (returningOptions != null
                    && returningOptions.getAnimationType() == ANIM_SCENE_TRANSITION
                    && returningOptions.getSceneTransitionInfo() != null) {
                app.getThread().scheduleOnNewSceneTransitionInfo(token,
                        returningOptions.getSceneTransitionInfo());
            }
        } catch(RemoteException e) {
        }
    }

    static void activityResumedLocked(IBinder token, boolean handleSplashScreenExit) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        ProtoLog.i(WM_DEBUG_STATES, "Resumed activity; dropping state of: %s", r);
        if (r == null) {
            // If an app reports resumed after a long delay, the record on server side might have
            // been removed (e.g. destroy timeout), so the token could be null.
            return;
        }
        r.setCustomizeSplashScreenExitAnimation(handleSplashScreenExit);
        r.setSavedState(null /* savedState */);

        r.mDisplayContent.handleActivitySizeCompatModeIfNeeded(r);
        r.mDisplayContent.mUnknownAppVisibilityController.notifyAppResumedFinished(r);
    }

    static void activityRefreshedLocked(IBinder token) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        ProtoLog.i(WM_DEBUG_STATES, "Refreshed activity: %s", r);
        if (r == null) {
            // In case the record on server side has been removed (e.g. destroy timeout)
            // and the token could be null.
            return;
        }
        AppCompatCameraPolicy.onActivityRefreshed(r);
    }

    static void splashScreenAttachedLocked(IBinder token) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        if (r == null) {
            Slog.w(TAG, "splashScreenTransferredLocked cannot find activity");
            return;
        }
        r.onSplashScreenAttachComplete();
    }

    /**
     * Once we know that we have asked an application to put an activity in the resumed state
     * (either by launching it or explicitly telling it), this function updates the rest of our
     * state to match that fact.
     */
    void completeResumeLocked() {
        idle = false;
        results = null;
        if (newIntents != null && newIntents.size() > 0) {
            mLastNewIntent = newIntents.get(newIntents.size() - 1);
        }
        newIntents = null;

        mTaskSupervisor.updateHomeProcessIfNeeded(this);

        if (nowVisible) {
            mTaskSupervisor.stopWaitingForActivityVisible(this);
        }

        // Schedule an idle timeout in case the app doesn't do it for us.
        mTaskSupervisor.scheduleIdleTimeout(this);

        mTaskSupervisor.mStoppingActivities.remove(this);
        if (getDisplayArea().allResumedActivitiesComplete()) {
            // Construct the compat environment at a relatively stable state if needed.
            mAppCompatController.getSizeCompatModePolicy().updateAppCompatDisplayInsets();
            mRootWindowContainer.executeAppTransitionForAllDisplay();
        }

        resumeKeyDispatchingLocked();
        final Task rootTask = getRootTask();
        mTaskSupervisor.mNoAnimActivities.clear();
        returningOptions = null;

        if (canTurnScreenOn()) {
            mTaskSupervisor.wakeUp(getDisplayId(), "turnScreenOnFlag");
        } else {
            // If the screen is going to turn on because the caller explicitly requested it and
            // the keyguard is not showing don't attempt to sleep. Otherwise the Activity will
            // pause and then resume again later, which will result in a double life-cycle event.
            rootTask.checkReadyForSleep();
        }
    }

    void activityPaused(boolean timeout) {
        ProtoLog.v(WM_DEBUG_STATES, "Activity paused: token=%s, timeout=%b", token,
                timeout);

        final TaskFragment taskFragment = getTaskFragment();
        if (taskFragment != null) {
            removePauseTimeout();

            final ActivityRecord pausingActivity = taskFragment.getPausingActivity();
            if (pausingActivity == this) {
                ProtoLog.v(WM_DEBUG_STATES, "Moving to PAUSED: %s %s", this,
                        (timeout ? "(due to timeout)" : " (pause complete)"));
                mAtmService.deferWindowLayout();
                try {
                    taskFragment.completePause(true /* resumeNext */, null /* resumingActivity */);
                } finally {
                    mAtmService.continueWindowLayout();
                }
                return;
            } else {
                EventLogTags.writeWmFailedToPause(mUserId, System.identityHashCode(this),
                        shortComponentName, pausingActivity != null
                                ? pausingActivity.shortComponentName : "(none)");
                if (isState(PAUSING)) {
                    setState(PAUSED, "activityPausedLocked");
                    if (finishing) {
                        ProtoLog.v(WM_DEBUG_STATES,
                                "Executing finish of failed to pause activity: %s", this);
                        completeFinishing("activityPausedLocked");
                    }
                }
            }
        }

        mDisplayContent.handleActivitySizeCompatModeIfNeeded(this);
        mRootWindowContainer.ensureActivitiesVisible();
    }

    /**
     * Schedule a pause timeout in case the app doesn't respond. We don't give it much time because
     * this directly impacts the responsiveness seen by the user.
     */
    void schedulePauseTimeout() {
        pauseTime = SystemClock.uptimeMillis();
        mAtmService.mH.postDelayed(mPauseTimeoutRunnable, PAUSE_TIMEOUT);
        ProtoLog.v(WM_DEBUG_STATES, "Waiting for pause to complete...");
    }

    private void removePauseTimeout() {
        mAtmService.mH.removeCallbacks(mPauseTimeoutRunnable);
    }

    private void removeDestroyTimeout() {
        mAtmService.mH.removeCallbacks(mDestroyTimeoutRunnable);
    }

    private void removeStopTimeout() {
        mAtmService.mH.removeCallbacks(mStopTimeoutRunnable);
    }

    void removeTimeouts() {
        mTaskSupervisor.removeIdleTimeoutForActivity(this);
        removePauseTimeout();
        removeStopTimeout();
        removeDestroyTimeout();
    }

    void stopIfPossible() {
        if (DEBUG_SWITCH) Slog.d(TAG_SWITCH, "Stopping: " + this);
        if (finishing) {
            throw new IllegalStateException("Request to stop a finishing activity: " + this);
        }
        launching = false;
        if (isNoHistory()) {
            if (!task.shouldSleepActivities()) {
                ProtoLog.d(WM_DEBUG_STATES, "no-history finish of %s", this);
                if (finishIfPossible("stop-no-history", false /* oomAdj */)
                        != FINISH_RESULT_CANCELLED) {
                    resumeKeyDispatchingLocked();
                    return;
                }
            } else {
                ProtoLog.d(WM_DEBUG_STATES, "Not finishing noHistory %s on stop "
                        + "because we're just sleeping", this);
            }
        }

        if (!attachedToProcess()) {
            return;
        }
        resumeKeyDispatchingLocked();
        ProtoLog.v(WM_DEBUG_STATES, "Moving to STOPPING: %s (stop requested)", this);

        setState(STOPPING, "stopIfPossible");
        if (DEBUG_VISIBILITY) {
            Slog.v(TAG_VISIBILITY, "Stopping:" + this);
        }
        EventLogTags.writeWmStopActivity(
                mUserId, System.identityHashCode(this), shortComponentName);
        final StopActivityItem item = new StopActivityItem(token);
        boolean isSuccessful;
        try {
            isSuccessful = mAtmService.getLifecycleManager().scheduleTransactionItem(
                    app.getThread(), item);
        } catch (RemoteException e) {
            // TODO(b/323801078): remove Exception when cleanup
            // Maybe just ignore exceptions here...  if the process has crashed, our death
            // notification will clean things up.
            Slog.w(TAG, "Exception thrown during pause", e);
            isSuccessful = false;
        }
        if (isSuccessful) {
            final int lastReportedWinMode = mLastReportedConfiguration.getMergedConfiguration()
                    .windowConfiguration.getWindowingMode();
            if (isPip2ExperimentEnabled()
                    && lastReportedWinMode == WINDOWING_MODE_PINNED && !inPinnedWindowingMode()) {
                // If an activity that was previously reported as pinned has a different windowing
                // mode, then send the latest activity configuration even if this activity is
                // stopping. This ensures that app gets onPictureInPictureModeChanged after onStop.
                updatePictureInPictureMode(null /* targetRootTaskBounds */, true /* forceUpdate */);
            }
            mAtmService.mH.postDelayed(mStopTimeoutRunnable, STOP_TIMEOUT);
        } else {
            // Just in case, assume it to be stopped.
            mAppStopped = true;
            mStoppedTime = SystemClock.uptimeMillis();
            ProtoLog.v(WM_DEBUG_STATES, "Stop failed; moving to STOPPED: %s", this);
            setState(STOPPED, "stopIfPossible");
        }
    }

    /**
     * Notifies that the activity has stopped, and it is okay to destroy any surfaces which were
     * keeping alive in case they were still being used.
     */
    void activityStopped(Bundle newIcicle, PersistableBundle newPersistentState,
            CharSequence description) {
        removeStopTimeout();
        final boolean isStopping = mState == STOPPING;
        if (!isStopping && mState != RESTARTING_PROCESS) {
            Slog.i(TAG, "Activity reported stop, but no longer stopping: " + this + " " + mState);
            return;
        }
        if (newPersistentState != null) {
            mPersistentState = newPersistentState;
            mAtmService.notifyTaskPersisterLocked(task, false);
        }

        if (newIcicle != null) {
            // If icicle is null, this is happening due to a timeout, so we haven't really saved
            // the state.
            setSavedState(newIcicle);
            launchCount = 0;
            updateTaskDescription(description);
        }
        ProtoLog.i(WM_DEBUG_STATES, "Saving icicle of %s: %s", this, mIcicle);

        if (isStopping) {
            ProtoLog.v(WM_DEBUG_STATES, "Moving to STOPPED: %s (stop complete)", this);
            mStoppedTime = SystemClock.uptimeMillis();
            setState(STOPPED, "activityStopped");
        }

        mAppStopped = true;
        firstWindowDrawn = false;
        // This is to fix the edge case that auto-enter-pip is finished in Launcher but app calls
        // setAutoEnterEnabled(false) and transitions to STOPPED state, see b/191930787.
        // Clear any surface transactions and content overlay in this case.
        if (task.mLastRecentsAnimationTransaction != null) {
            task.clearLastRecentsAnimationTransaction(true /* forceRemoveOverlay */);
        }
        if (isClientVisible()) {
            // Though this is usually unlikely to happen, still make sure the client is invisible.
            setClientVisible(false);
        }
        destroySurfaces();
        // Remove any starting window that was added for this app if they are still around.
        removeStartingWindow();
        // This is unlikely to happen because the sequence of lifecycle should invoke
        // finishRelaunching before being stopped. Reset the potential unpaired count in case
        // the binder transaction of relaunch is failed, so the transition won't be blocked.
        if (mPendingRelaunchCount > 0) {
            Slog.i(TAG, "Clear pending relaunch count on stopped " + this);
            clearRelaunching();
        }

        if (finishing) {
            abortAndClearOptionsAnimation();
        } else {
            mAtmService.updatePreviousProcess(this);
        }
        mTaskSupervisor.checkReadyForSleepLocked(true /* allowDelay */);
    }

    void addToStopping(boolean scheduleIdle, boolean idleDelayed, String reason) {
        if (!mTaskSupervisor.mStoppingActivities.contains(this)) {
            EventLogTags.writeWmAddToStopping(mUserId, System.identityHashCode(this),
                    shortComponentName, reason);
            mTaskSupervisor.mStoppingActivities.add(this);
        }

        final Task rootTask = getRootTask();
        // If we already have a few activities waiting to stop, then give up on things going idle
        // and start clearing them out. Or if r is the last of activity of the last task the root
        // task will be empty and must be cleared immediately.
        boolean forceIdle = mTaskSupervisor.mStoppingActivities.size() > MAX_STOPPING_TO_FORCE
                || (isRootOfTask() && rootTask.getChildCount() <= 1);
        if (scheduleIdle || forceIdle) {
            ProtoLog.v(WM_DEBUG_STATES,
                    "Scheduling idle now: forceIdle=%b immediate=%b", forceIdle, !idleDelayed);

            if (!idleDelayed) {
                mTaskSupervisor.scheduleIdle();
            } else {
                mTaskSupervisor.scheduleIdleTimeout(this);
            }
        } else {
            rootTask.checkReadyForSleep();
        }
    }

    void onFirstWindowDrawn(WindowState win) {
        firstWindowDrawn = true;
        // stop tracking
        mSplashScreenStyleSolidColor = true;

        mAtmService.mBackNavigationController.removePredictiveSurfaceIfNeeded(this);
        if (mStartingWindow != null) {
            ProtoLog.v(WM_DEBUG_STARTING_WINDOW, "Finish starting %s"
                    + ": first real window is shown, no animation", win.mToken);
            // If this initial window is animating, stop it -- we will do an animation to reveal
            // it from behind the starting window, so there is no need for it to also be doing its
            // own stuff.
            win.cancelAnimation();
        }

        // Remove starting window directly if is in a pure task. Otherwise if it is associated with
        // a task (e.g. nested task fragment), then remove only if all visible windows in the task
        // are drawn.
        final Task associatedTask = task.mSharedStartingData != null ? task : null;
        if (associatedTask == null) {
            removeStartingWindow();
        } else if (associatedTask.getActivity(
                r -> r.isVisibleRequested() && !r.firstWindowDrawn) == null) {
            // The last drawn activity may not be the one that owns the starting window.
            final ActivityRecord r = associatedTask.getActivity(ar -> ar.mStartingData != null);
            if (r != null) {
                r.removeStartingWindow();
            }
        }
        updateReportedVisibilityLocked();
    }

    /** Sets whether something has been visible in the task. */
    private void setTaskHasBeenVisible() {
        final boolean wasTaskVisible = task.getHasBeenVisible();
        if (wasTaskVisible) {
            return;
        }
        if (inTransition()) {
            // The deferring will be canceled until transition is ready so it won't dispatch
            // intermediate states to organizer.
            task.setDeferTaskAppear(true);
        }
        task.setHasBeenVisible(true);
    }

    void onStartingWindowDrawn() {
        if (task != null) {
            mSplashScreenStyleSolidColor = true;
            setTaskHasBeenVisible();
        }
        if (mStartingData == null || mStartingData.mIsDisplayed) {
            return;
        }
        mStartingData.mIsDisplayed = true;

        // The transition may not be executed if the starting process hasn't attached. But if the
        // starting window is drawn, the transition can start earlier. Exclude finishing and bubble
        // because it may be a trampoline.
        if (app == null && !finishing && !mLaunchedFromBubble
                && mVisibleRequested && mDisplayContent.isNextTransitionForward()) {
            // The pending transition state will be cleared after the transition is started, so
            // save the state for launching the client later (used by LaunchActivityItem).
            mStartingData.mIsTransitionForward = true;
            // Ensure that the transition can run with the latest orientation.
            if (this != mDisplayContent.getLastOrientationSource()) {
                mDisplayContent.updateOrientation();
            }
            mDisplayContent.executeAppTransition();
        }
    }

    protected void setActivityBoost(boolean enable) {
        if (mIsBoosted != enable && mPowerManagerInternal != null) {
            mIsBoosted = enable;
            mPowerManagerInternal.setPowerMode(Mode.LAUNCH, enable);
        }
    }

    /** Called when the windows associated app window container are drawn. */
    private void onWindowsDrawn() {
        setActivityBoost(false);
        final TransitionInfoSnapshot info = mTaskSupervisor
                .getActivityMetricsLogger().notifyWindowsDrawn(this);
        final boolean validInfo = info != null;
        final int windowsDrawnDelayMs = validInfo ? info.windowsDrawnDelayMs : INVALID_DELAY;
        final @WaitResult.LaunchState int launchState =
                validInfo ? info.getLaunchState() : WaitResult.LAUNCH_STATE_UNKNOWN;
        // The activity may have been requested to be invisible (another activity has been launched)
        // so there is no valid info. But if it is the current top activity (e.g. sleeping), the
        // invalid state is still reported to make sure the waiting result is notified.
        if (validInfo || this == getDisplayArea().topRunningActivity()) {
            mTaskSupervisor.reportActivityLaunched(false /* timeout */, this,
                    windowsDrawnDelayMs, launchState);
        }
        if (task != null) {
            setTaskHasBeenVisible();
        }
        // Clear indicated launch root task because there's no trampoline activity to expect after
        // the windows are drawn.
        mLaunchRootTask = null;
    }

    /** Called when the windows associated app window container are visible. */
    void onWindowsVisible() {
        if (DEBUG_VISIBILITY) Slog.v(TAG_WM, "Reporting visible in " + token);
        mTaskSupervisor.stopWaitingForActivityVisible(this);
        if (DEBUG_SWITCH) Log.v(TAG_SWITCH, "windowsVisibleLocked(): " + this);
        if (!nowVisible) {
            nowVisible = true;
            launching = false;
            lastVisibleTime = SystemClock.uptimeMillis();
            mAtmService.scheduleAppGcsLocked();
            // The nowVisible may be false in onAnimationFinished because the transition animation
            // was started by starting window but the main window hasn't drawn so the procedure
            // didn't schedule. Hence also check when nowVisible becomes true (drawn) to avoid the
            // closing activity having to wait until idle timeout to be stopped or destroyed if the
            // next activity won't report idle (e.g. repeated view animation).
            mTaskSupervisor.scheduleProcessStoppingAndFinishingActivitiesIfNeeded();
        }
    }

    /** Called when the windows associated app window container are no longer visible. */
    void onWindowsGone() {
        if (DEBUG_VISIBILITY) Slog.v(TAG_WM, "Reporting gone in " + token);
        if (DEBUG_SWITCH) Log.v(TAG_SWITCH, "windowsGone(): " + this);
        nowVisible = false;
        launching = false;
    }

    void updateReportedVisibilityLocked() {
        if (DEBUG_VISIBILITY) Slog.v(TAG, "Update reported visibility: " + this);
        final int count = mChildren.size();

        mReportedVisibilityResults.reset();

        for (int i = 0; i < count; i++) {
            final WindowState win = mChildren.get(i);
            win.updateReportedVisibility(mReportedVisibilityResults);
        }

        int numInteresting = mReportedVisibilityResults.numInteresting;
        int numVisible = mReportedVisibilityResults.numVisible;
        int numDrawn = mReportedVisibilityResults.numDrawn;
        boolean nowGone = mReportedVisibilityResults.nowGone;

        boolean nowDrawn = numInteresting > 0 && numDrawn >= numInteresting;
        boolean nowVisible = numInteresting > 0 && numVisible >= numInteresting && isVisible();
        if (!nowGone) {
            // If the app is not yet gone, then it can only become visible/drawn.
            if (!nowDrawn) {
                nowDrawn = mReportedDrawn;
            }
            if (!nowVisible) {
                nowVisible = reportedVisible;
            }
        }
        if (DEBUG_VISIBILITY) Slog.v(TAG, "VIS " + this + ": interesting="
                + numInteresting + " visible=" + numVisible);
        if (nowDrawn != mReportedDrawn) {
            if (nowDrawn) {
                onWindowsDrawn();
            }
            mReportedDrawn = nowDrawn;
        }
        if (nowVisible != reportedVisible) {
            if (DEBUG_VISIBILITY) Slog.v(TAG,
                    "Visibility changed in " + this + ": vis=" + nowVisible);
            reportedVisible = nowVisible;
            if (nowVisible) {
                onWindowsVisible();
            } else {
                onWindowsGone();
            }
        }
    }

    boolean isReportedDrawn() {
        return mReportedDrawn;
    }

    @Override
    void setClientVisible(boolean clientVisible) {
        if (!Flags.removeDeferHidingClient()) {
            // TODO(shell-transitions): Remove mDeferHidingClient once everything is
            //  shell-transitions. pip activities should just remain in clientVisible.
            if (!clientVisible && mDeferHidingClient) return;
        }
        super.setClientVisible(clientVisible);
    }

    /**
     * Updated this app token tracking states for interesting and drawn windows based on the window.
     *
     * @return Returns true if the input window is considered interesting and drawn while all the
     *         windows in this app token where not considered drawn as of the last pass.
     */
    boolean updateDrawnWindowStates(WindowState w) {
        w.setDrawnStateEvaluated(true /*evaluated*/);

        if (DEBUG_STARTING_WINDOW_VERBOSE && w == mStartingWindow) {
            Slog.d(TAG, "updateWindows: starting " + w + " isOnScreen=" + w.isOnScreen()
                    + " allDrawn=" + allDrawn);
        }

        if (allDrawn) {
            return false;
        }

        if (mLastTransactionSequence != mWmService.mTransactionSequence) {
            mLastTransactionSequence = mWmService.mTransactionSequence;
            mNumDrawnWindows = 0;

            // There is the main base application window, even if it is exiting, wait for it
            mNumInterestingWindows = findMainWindow(false /* includeStartingApp */) != null ? 1 : 0;
        }

        final WindowStateAnimator winAnimator = w.mWinAnimator;

        boolean isInterestingAndDrawn = false;

        if (!allDrawn && w.mightAffectAllDrawn()) {
            if (DEBUG_VISIBILITY || WM_DEBUG_ORIENTATION.isLogToLogcat()) {
                final boolean isAnimationSet = isAnimating(TRANSITION | PARENTS,
                        ANIMATION_TYPE_APP_TRANSITION);
                Slog.v(TAG, "Eval win " + w + ": isDrawn=" + w.isDrawn()
                        + ", isAnimationSet=" + isAnimationSet);
                if (!w.isDrawn()) {
                    Slog.v(TAG, "Not displayed: s=" + winAnimator.mSurfaceControl
                            + " pv=" + w.isVisibleByPolicy()
                            + " mDrawState=" + winAnimator.drawStateToString()
                            + " ph=" + w.isParentWindowHidden() + " th=" + mVisibleRequested
                            + " a=" + isAnimationSet);
                }
            }

            if (w != mStartingWindow) {
                if (w.isInteresting()) {
                    // Add non-main window as interesting since the main app has already been added
                    if (findMainWindow(false /* includeStartingApp */) != w) {
                        mNumInterestingWindows++;
                    }
                    if (w.isDrawn()) {
                        mNumDrawnWindows++;

                        if (DEBUG_VISIBILITY || WM_DEBUG_ORIENTATION.isLogToLogcat()) {
                            Slog.v(TAG, "tokenMayBeDrawn: " + this + " w=" + w
                                    + " numInteresting=" + mNumInterestingWindows);
                        }

                        isInterestingAndDrawn = true;
                    }
                }
            }
        }

        return isInterestingAndDrawn;
    }

    /**
     * Called when the input dispatching to a window associated with the app window container
     * timed-out.
     *
     * @param reason The reason for input dispatching time out.
     * @param windowPid The pid of the window input dispatching timed out on.
     * @return True if input dispatching should be aborted.
     */
    public boolean inputDispatchingTimedOut(TimeoutRecord timeoutRecord, int windowPid) {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                    "ActivityRecord#inputDispatchingTimedOut()");
            ActivityRecord anrActivity;
            WindowProcessController anrApp;
            boolean blameActivityProcess;
            timeoutRecord.mLatencyTracker.waitingOnGlobalLockStarted();
            synchronized (mAtmService.mGlobalLock) {
                timeoutRecord.mLatencyTracker.waitingOnGlobalLockEnded();
                anrActivity = getWaitingHistoryRecordLocked();
                anrApp = app;
                blameActivityProcess =  hasProcess()
                        && (app.getPid() == windowPid || windowPid == INVALID_PID);
            }

            if (blameActivityProcess) {
                return mAtmService.mAmInternal.inputDispatchingTimedOut(anrApp.mOwner,
                        anrActivity.shortComponentName, anrActivity.info.applicationInfo,
                        shortComponentName, app, false, timeoutRecord);
            } else {
                // In this case another process added windows using this activity token.
                // So, we call the generic service input dispatch timed out method so
                // that the right process is blamed.
                long timeoutMillis = mAtmService.mAmInternal.inputDispatchingTimedOut(
                        windowPid, false /* aboveSystem */, timeoutRecord);
                return timeoutMillis <= 0;
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }

    }

    private ActivityRecord getWaitingHistoryRecordLocked() {
        // First find the real culprit...  if this activity has stopped, then the key dispatching
        // timeout should not be caused by this.
        if (mAppStopped) {
            final Task rootTask = mRootWindowContainer.getTopDisplayFocusedRootTask();
            if (rootTask == null) {
                return this;
            }
            // Try to use the one which is closest to top.
            ActivityRecord r = rootTask.getTopResumedActivity();
            if (r == null) {
                r = rootTask.getTopPausingActivity();
            }
            if (r != null) {
                return r;
            }
        }
        return this;
    }

    boolean canBeTopRunning() {
        return !finishing && showToCurrentUser();
    }

    /**
     * This method will return true if the activity is either visible, is becoming visible, is
     * currently pausing, or is resumed.
     */
    public boolean isInterestingToUserLocked() {
        return mVisibleRequested || nowVisible || mState == PAUSING || mState == RESUMED;
    }

    /**
     * Returns the task id of the activity token. If onlyRoot=true is specified, it will
     * return a valid id only if the activity is root or the activity is immediately above
     * the first non-relinquish-identity activity.
     * TODO(b/297476786): Clarify the use cases about when should get the bottom activity
     *                    or the first non-relinquish-identity activity from bottom.
     */
    static int getTaskForActivityLocked(IBinder token, boolean onlyRoot) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        if (r == null || r.getParent() == null) {
            return INVALID_TASK_ID;
        }
        final Task task = r.task;
        if (onlyRoot && r.compareTo(task.getRootActivity(
                false /*ignoreRelinquishIdentity*/, true /*setToBottomIfNone*/)) > 0) {
            return INVALID_TASK_ID;
        }
        return task.mTaskId;
    }

    static ActivityRecord isInRootTaskLocked(IBinder token) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        return (r != null) ? r.getRootTask().isInTask(r) : null;
    }

    static Task getRootTask(IBinder token) {
        final ActivityRecord r = ActivityRecord.isInRootTaskLocked(token);
        if (r != null) {
            return r.getRootTask();
        }
        return null;
    }

    @Nullable
    static ActivityRecord isInAnyTask(IBinder token) {
        final ActivityRecord r = ActivityRecord.forTokenLocked(token);
        return (r != null && r.isAttached()) ? r : null;
    }

    /**
     * @return display id to which this record is attached,
     *         {@link android.view.Display#INVALID_DISPLAY} if not attached.
     */
    int getDisplayId() {
        return task != null && task.mDisplayContent != null
                 ? task.mDisplayContent.mDisplayId : INVALID_DISPLAY;
    }

    final boolean isDestroyable() {
        if (finishing || !hasProcess()) {
            // This would be redundant.
            return false;
        }
        if (isState(RESUMED) || getRootTask() == null
                || this == getTaskFragment().getPausingActivity()
                || !mHaveState || !mAppStopped) {
            // We're not ready for this kind of thing.
            return false;
        }
        if (mVisibleRequested) {
            // The user would notice this!
            return false;
        }
        return true;
    }

    private static String createImageFilename(long createTime, int taskId) {
        return String.valueOf(taskId) + ACTIVITY_ICON_SUFFIX + createTime +
                IMAGE_EXTENSION;
    }

    void setTaskDescription(TaskDescription _taskDescription) {
        Bitmap icon;
        if (_taskDescription.getIconFilename() == null &&
                (icon = _taskDescription.getIcon()) != null) {
            final String iconFilename = createImageFilename(createTime, task.mTaskId);
            final File iconFile = new File(TaskPersister.getUserImagesDir(task.mUserId),
                    iconFilename);
            final String iconFilePath = iconFile.getAbsolutePath();
            mAtmService.getRecentTasks().saveImage(icon, iconFilePath);
            _taskDescription.setIconFilename(iconFilePath);
        }
        taskDescription = _taskDescription;
        getTask().updateTaskDescription();
    }

    void setLocusId(LocusId locusId) {
        if (Objects.equals(locusId, mLocusId)) return;
        mLocusId = locusId;
        final Task task = getTask();
        if (task != null) getTask().dispatchTaskInfoChangedIfNeeded(false /* force */);
    }

    LocusId getLocusId() {
        return mLocusId;
    }

    void requestOpenInBrowserEducation() {
        mRequestOpenInBrowserEducationTimestamp = System.currentTimeMillis();
        final Task task = getTask();
        if (task != null) {
            final boolean force = isVisibleRequested() && this == task.getTopNonFinishingActivity();
            getTask().dispatchTaskInfoChangedIfNeeded(force);
        }
    }

    public void reportScreenCaptured() {
        if (mCaptureCallbacks != null) {
            final int n = mCaptureCallbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                IScreenCaptureObserver obs = mCaptureCallbacks.getBroadcastItem(i);
                try {
                    obs.onScreenCaptured();
                } catch (RemoteException e) {
                }
            }
            mCaptureCallbacks.finishBroadcast();
        }
    }

    public void registerCaptureObserver(IScreenCaptureObserver observer) {
        synchronized (mWmService.mGlobalLock) {
            if (mCaptureCallbacks == null) {
                mCaptureCallbacks = new RemoteCallbackList<IScreenCaptureObserver>();
            }
            mCaptureCallbacks.register(observer);
        }
    }

    public void unregisterCaptureObserver(IScreenCaptureObserver observer) {
        synchronized (mWmService.mGlobalLock) {
            if (mCaptureCallbacks != null) {
                mCaptureCallbacks.unregister(observer);
            }
        }
    }

    boolean isRegisteredForScreenCaptureCallback() {
        return mCaptureCallbacks != null && mCaptureCallbacks.getRegisteredCallbackCount() > 0;
    }

    void setVoiceSessionLocked(IVoiceInteractionSession session) {
        voiceSession = session;
        pendingVoiceInteractionStart = false;
    }

    void clearVoiceSessionLocked() {
        voiceSession = null;
        pendingVoiceInteractionStart = false;
    }

    void showStartingWindow(boolean taskSwitch) {
        // Pass the activity which contains starting window already.
        final ActivityRecord prev = task.getActivity(
                a -> a != this && a.mStartingData != null && a.showToCurrentUser());
        showStartingWindow(prev, false /* newTask */, taskSwitch, false /* startActivity */, null);
    }

    /**
     * Search for the candidate launching activity from currently visible activities.
     *
     * This activity could be launched from service, so we need to check whether there is existing a
     * foreground activity from the same process or same package.
     *
     */
    private ActivityRecord searchCandidateLaunchingActivity() {
        // Get previous activity below self
        ActivityRecord below = task.getActivityBelow(this);
        if (below == null) {
            below = task.getParent().getActivityBelow(this);
        }

        if (below == null || below.isActivityTypeHome()) {
            return null;
        }
        final WindowProcessController myProcess = app != null
                ? app : mAtmService.mProcessNames.get(processName, info.applicationInfo.uid);
        final WindowProcessController candidateProcess = below.app != null
                        ? below.app
                        : mAtmService.mProcessNames.get(below.processName,
                                below.info.applicationInfo.uid);
        // same process or same package
        if (candidateProcess == myProcess
                || mActivityComponent.getPackageName()
                .equals(below.mActivityComponent.getPackageName())) {
            return below;
        }
        return null;
    }

    private boolean isIconStylePreferred(int theme) {
        if (theme == 0) {
            return false;
        }
        final WindowStyle style = theme == this.theme
                ? mWindowStyle : mAtmService.getWindowStyle(packageName, theme, mUserId);
        if (style != null) {
            return style.mSplashScreenBehavior == SPLASH_SCREEN_BEHAVIOR_ICON_PREFERRED;
        }
        return false;
    }

    /**
     * @return true if a solid color splash screen must be used
     *         false when an icon splash screen can be used, but the final decision for whether to
     *               use an icon or solid color splash screen will be made by WmShell.
     */
    private boolean shouldUseSolidColorSplashScreen(ActivityRecord sourceRecord,
            boolean startActivity, ActivityOptions options, int resolvedTheme,
            boolean newTask) {
        if (sourceRecord == null && !startActivity) {
            // Use simple style if this activity is not top activity. This could happen when adding
            // a splash screen window to the warm start activity which is re-create because top is
            // finishing.
            final ActivityRecord above = task.getActivityAbove(this);
            if (above != null) {
                return true;
            }
        }

        // setSplashScreenStyle decide in priority of windowSplashScreenBehavior.
        final int optionsStyle = options != null ? options.getSplashScreenStyle() :
                SplashScreen.SPLASH_SCREEN_STYLE_UNDEFINED;
        if (optionsStyle == SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR) {
            return true;
        } else if (optionsStyle == SplashScreen.SPLASH_SCREEN_STYLE_ICON
                    || isIconStylePreferred(resolvedTheme)) {
            return false;
        }

        // Choose the default behavior when neither the ActivityRecord nor the activity theme have
        // specified a splash screen style.
        if (mLaunchSourceType == LAUNCH_SOURCE_TYPE_SYSTEMUI) {
            return true;
        } else {
            // Need to check sourceRecord in case this activity is launched from a service.
            if (sourceRecord == null) {
                sourceRecord = searchCandidateLaunchingActivity();
            }
            if (sourceRecord != null) {
                return sourceRecord.mSplashScreenStyleSolidColor; // follow previous activity
            } else if (mLaunchSourceType == LAUNCH_SOURCE_TYPE_HOME
                    || launchedFromUid == Process.SHELL_UID) {
                return !newTask; // only show icon for new task
            }
            // Use an icon if the activity was launched from System for the first start.
            // Otherwise, must use solid color splash screen.
            return mLaunchSourceType != LAUNCH_SOURCE_TYPE_SYSTEM || !startActivity;
        }
    }

    private int getSplashscreenTheme(ActivityOptions options) {
        // Find the splash screen theme. User can override the persisted theme by
        // ActivityOptions.
        String splashScreenThemeResName = options != null
                ? options.getSplashScreenThemeResName() : null;
        if (splashScreenThemeResName == null || splashScreenThemeResName.isEmpty()) {
            try {
                splashScreenThemeResName = mAtmService.getPackageManager()
                        .getSplashScreenTheme(packageName, mUserId);
            } catch (RemoteException ignore) {
                // Just use the default theme
            }
        }
        int splashScreenThemeResId = 0;
        if (splashScreenThemeResName != null && !splashScreenThemeResName.isEmpty()) {
            try {
                final Context packageContext = mAtmService.mContext
                        .createPackageContext(packageName, 0);
                splashScreenThemeResId = packageContext.getResources()
                        .getIdentifier(splashScreenThemeResName, null, null);
            } catch (PackageManager.NameNotFoundException
                    | Resources.NotFoundException ignore) {
                // Just use the default theme
            }
        }
        return splashScreenThemeResId;
    }

    void showStartingWindow(ActivityRecord prev, boolean newTask, boolean taskSwitch,
            boolean startActivity, ActivityRecord sourceRecord) {
        showStartingWindow(prev, newTask, taskSwitch, isProcessRunning(), startActivity,
                sourceRecord, null /* candidateOptions */);
    }

    /**
     * @param prev Previous activity which contains a starting window.
     * @param processRunning Whether the client process is running.
     * @param startActivity Whether this activity is just created from starter.
     * @param sourceRecord The source activity which start this activity.
     * @param candidateOptions The options for the style of starting window.
     */
    void showStartingWindow(ActivityRecord prev, boolean newTask, boolean taskSwitch,
            boolean processRunning, boolean startActivity, ActivityRecord sourceRecord,
            ActivityOptions candidateOptions) {
        if (mTaskOverlay) {
            // We don't show starting window for overlay activities.
            return;
        }
        final ActivityOptions startOptions = candidateOptions != null
                ? candidateOptions : mPendingOptions;
        if (startOptions != null
                && startOptions.getAnimationType() == ActivityOptions.ANIM_SCENE_TRANSITION) {
            // Don't show starting window when using shared element transition.
            return;
        }

        final int splashScreenTheme = startActivity ? getSplashscreenTheme(startOptions) : 0;
        final int resolvedTheme = evaluateStartingWindowTheme(prev, packageName, theme,
                splashScreenTheme);

        mSplashScreenStyleSolidColor = shouldUseSolidColorSplashScreen(sourceRecord, startActivity,
                startOptions, resolvedTheme, newTask);

        final boolean activityCreated =
                mState.ordinal() >= STARTED.ordinal() && mState.ordinal() <= STOPPED.ordinal();
        // If this activity is just created and all activities below are finish, treat this
        // scenario as warm launch.
        final boolean newSingleActivity = !newTask && !activityCreated
                && task.getActivity((r) -> !r.finishing && r != this) == null;

        final boolean scheduled = addStartingWindow(packageName, resolvedTheme,
                prev, newTask || newSingleActivity, taskSwitch, processRunning,
                allowTaskSnapshot(), activityCreated, mSplashScreenStyleSolidColor, allDrawn);
        if (DEBUG_STARTING_WINDOW_VERBOSE && scheduled) {
            Slog.d(TAG, "Scheduled starting window for " + this);
        }
    }

    /**
     * If any activities below the top running one are in the INITIALIZING state and they have a
     * starting window displayed then remove that starting window. It is possible that the activity
     * in this state will never resumed in which case that starting window will be orphaned.
     * <p>
     * It should only be called if this activity is behind other fullscreen activity.
     */
    void cancelInitializing() {
        if (mStartingData != null) {
            // Remove orphaned starting window.
            if (DEBUG_VISIBILITY) Slog.w(TAG_VISIBILITY, "Found orphaned starting window " + this);
            removeStartingWindowAnimation(false /* prepareAnimation */);
        }
        if (!mDisplayContent.mUnknownAppVisibilityController.allResolved()) {
            // Remove the unknown visibility record because an invisible activity shouldn't block
            // the keyguard transition.
            mDisplayContent.mUnknownAppVisibilityController.appRemovedOrHidden(this);
        }
    }

    void postWindowRemoveStartingWindowCleanup(@NonNull WindowState win) {
        if (mStartingWindow == win) {
            // This could only happen when the window is removed from hierarchy. So do not keep its
            // reference anymore.
            if (mStartingSurface != null) {
                // Ensure the reference in client side can be removed.
                mStartingSurface.remove(false /* animate */, false /* hasImeSurface */);
            }
            mStartingWindow = null;
            mStartingData = null;
            mStartingSurface = null;
        }
        if (mChildren.size() == 0 && mVisibleSetFromTransferredStartingWindow) {
            // We set the visible state to true for the token from a transferred starting
            // window. We now reset it back to false since the starting window was the last
            // window in the token.
            setVisible(false);
        }
    }

    void requestUpdateWallpaperIfNeeded() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowState w = mChildren.get(i);
            w.requestUpdateWallpaperIfNeeded();
        }
    }

    WindowState findMainWindow() {
        return findMainWindow(true);
    }

    /**
     * Finds the main window that either has type base application or application starting if
     * requested.
     *
     * @param includeStartingApp Allow to search application-starting windows to also be returned.
     * @return The main window of type base application or application starting if requested.
     */
    WindowState findMainWindow(boolean includeStartingApp) {
        WindowState candidate = null;
        for (int j = mChildren.size() - 1; j >= 0; --j) {
            final WindowState win = mChildren.get(j);
            final int type = win.mAttrs.type;
            // No need to loop through child window as base application and starting types can't be
            // child windows.
            if (type == TYPE_BASE_APPLICATION
                    || (includeStartingApp && type == TYPE_APPLICATION_STARTING)) {
                // In cases where there are multiple windows, we prefer the non-exiting window. This
                // happens for example when replacing windows during an activity relaunch. When
                // constructing the animation, we want the new window, not the exiting one.
                if (win.mAnimatingExit) {
                    candidate = win;
                } else {
                    return win;
                }
            }
        }
        return candidate;
    }

    boolean isTransitionForward() {
        return (mStartingData != null && mStartingData.mIsTransitionForward)
                || mDisplayContent.isNextTransitionForward();
    }

    @Override
    void resetSurfacePositionForAnimationLeash(SurfaceControl.Transaction t) {
        // Noop as Activity may be offset for letterbox
    }

    @Override
    boolean showSurfaceOnCreation() {
        return false;
    }

    @Override
    void prepareSurfaces() {
        final boolean isDecorSurfaceBoosted =
                getTask() != null && getTask().isDecorSurfaceBoosted();
        final boolean show = (isVisible()
                // Ensure that the activity content is hidden when the decor surface is boosted to
                // prevent UI redressing attack.
                && !isDecorSurfaceBoosted)
                || isAnimating(PARENTS, ANIMATION_TYPE_APP_TRANSITION
                        | ANIMATION_TYPE_PREDICT_BACK);

        if (mSurfaceControl != null) {
            if (show && !mLastSurfaceShowing) {
                getSyncTransaction().show(mSurfaceControl);
            } else if (!show && mLastSurfaceShowing) {
                getSyncTransaction().hide(mSurfaceControl);
            }
            // Input sink surface is not a part of animation, so just apply in a steady state
            // (non-sync) with pending transaction.
            if (show && mSyncState == SYNC_STATE_NONE) {
                mActivityRecordInputSink.applyChangesToSurfaceIfChanged(getPendingTransaction());
            }
        }
        mLastSurfaceShowing = show;
        super.prepareSurfaces();
    }

    /**
     * @return Whether our {@link #getSurfaceControl} is currently showing.
     */
    boolean isSurfaceShowing() {
        return mLastSurfaceShowing;
    }

    public @TransitionOldType int getTransit() {
        return mTransit;
    }


    void registerRemoteAnimations(RemoteAnimationDefinition definition) {
        mRemoteAnimationDefinition = definition;
        if (definition != null) {
            definition.linkToDeath(this::unregisterRemoteAnimations);
        }
    }

    void unregisterRemoteAnimations() {
        mRemoteAnimationDefinition = null;
    }

    @Override
    RemoteAnimationDefinition getRemoteAnimationDefinition() {
        return mRemoteAnimationDefinition;
    }

    @Override
    void applyFixedRotationTransform(DisplayInfo info, DisplayFrames displayFrames,
            Configuration config) {
        super.applyFixedRotationTransform(info, displayFrames, config);
        ensureActivityConfiguration();
    }

    /**
     * Returns the requested {@link Configuration.Orientation} for the current activity.
     */
    @Configuration.Orientation
    @Override
    int getRequestedConfigurationOrientation(boolean forDisplay) {
        return getRequestedConfigurationOrientation(forDisplay, getOverrideOrientation());
    }

    /**
     * Returns the requested {@link Configuration.Orientation} for the requested
     * {@link ActivityInfo.ScreenOrientation}.
     *
     * <p>When the current screen orientation is set to {@link SCREEN_ORIENTATION_BEHIND} it returns
     * the requested orientation for the activity below which is the first activity with an explicit
     * (different from {@link SCREEN_ORIENTATION_UNSET}) orientation which is not {@link
     * SCREEN_ORIENTATION_BEHIND}.
     */
    @Configuration.Orientation
    int getRequestedConfigurationOrientation(boolean forDisplay,
            @ActivityInfo.ScreenOrientation int requestedOrientation) {
        if (mAppCompatController.getTransparentPolicy().hasInheritedOrientation()) {
            final RootDisplayArea root = getRootDisplayArea();
            if (forDisplay && root != null && root.isOrientationDifferentFromDisplay()) {
                return reverseConfigurationOrientation(
                        mAppCompatController.getTransparentPolicy().getInheritedOrientation());
            } else {
                return mAppCompatController.getTransparentPolicy().getInheritedOrientation();
            }
        }
        if (task != null && requestedOrientation == SCREEN_ORIENTATION_BEHIND) {
            // We use Task here because we want to be consistent with what happens in
            // multi-window mode where other tasks orientations are ignored.
            final ActivityRecord belowCandidate = task.getActivityBelowForDefiningOrientation(this);
            if (belowCandidate != null) {
                return belowCandidate.getRequestedConfigurationOrientation(forDisplay);
            }
        }
        return super.getRequestedConfigurationOrientation(forDisplay, requestedOrientation);
    }

    /**
     * Returns the reversed configuration orientation.
     * @hide
     */
    @Configuration.Orientation
    public static int reverseConfigurationOrientation(@Configuration.Orientation int orientation) {
        switch (orientation) {
            case ORIENTATION_LANDSCAPE:
                return ORIENTATION_PORTRAIT;
            case ORIENTATION_PORTRAIT:
                return ORIENTATION_LANDSCAPE;
            default:
                return orientation;
        }
    }

    /**
     * Whether this activity can be used as an orientation source for activities above with
     * {@link SCREEN_ORIENTATION_BEHIND}.
     */
    boolean canDefineOrientationForActivitiesAbove() {
        if (finishing) {
            return false;
        }
        final int overrideOrientation = getOverrideOrientation();
        return overrideOrientation != SCREEN_ORIENTATION_UNSET
                && overrideOrientation != SCREEN_ORIENTATION_BEHIND;
    }

    @Override
    void onCancelFixedRotationTransform(int originalDisplayRotation) {
        if (this != mDisplayContent.getLastOrientationSource()) {
            // This activity doesn't affect display rotation.
            return;
        }
        final int requestedOrientation = getRequestedConfigurationOrientation();
        if (requestedOrientation != ORIENTATION_UNDEFINED
                && requestedOrientation != mDisplayContent.getConfiguration().orientation) {
            // Only need to handle the activity that can be rotated with display or the activity
            // has requested the same orientation.
            return;
        }

        mDisplayContent.mPinnedTaskController.onCancelFixedRotationTransform();
        // This activity may relaunch or perform configuration change so once it has reported drawn,
        // the screen can be unfrozen.
        ensureActivityConfiguration();
        if (mTransitionController.isCollecting(this)) {
            // In case the task was changed from PiP but still keeps old transform.
            task.resetSurfaceControlTransforms();
        }
    }

    void setRequestedOrientation(@ActivityInfo.ScreenOrientation int requestedOrientation) {
        if (mAppCompatController.getOrientationPolicy()
                .shouldIgnoreRequestedOrientation(requestedOrientation)) {
            return;
        }
        final int originalRelaunchingCount = mPendingRelaunchCount;
        // This is necessary in order to avoid going into size compat mode when the orientation
        // change request comes from the app
        if (getRequestedConfigurationOrientation(false, requestedOrientation)
                    != getRequestedConfigurationOrientation(false /*forDisplay */)) {
            // Do not change the requested configuration now, because this will be done when setting
            // the orientation below with the new mAppCompatDisplayInsets
            mAppCompatController.getSizeCompatModePolicy().clearSizeCompatModeAttributes();
        }
        ProtoLog.v(WM_DEBUG_ORIENTATION,
                "Setting requested orientation %s for %s",
                ActivityInfo.screenOrientationToString(requestedOrientation), this);
        final int resolvedOrientation = setOrientation(requestedOrientation, this);
        if (resolvedOrientation != requestedOrientation
                && isRestrictedFixedOrientation(requestedOrientation)) {
            Slog.i(TAG, "Ignoring requested fixed orientation "
                    + ActivityInfo.screenOrientationToString(requestedOrientation)
                    + " of " + this + " since target sdk 36");
        }

        // Push the new configuration to the requested app in case where it's not pushed, e.g. when
        // the request is handled at task level with letterbox.
        if (!getMergedOverrideConfiguration().equals(
                mLastReportedConfiguration.getMergedConfiguration())) {
            ensureActivityConfiguration(false /* ignoreVisibility */);
            if (mPendingRelaunchCount > originalRelaunchingCount) {
                mAppCompatController.getOrientationOverrides()
                        .setRelaunchingAfterRequestedOrientationChanged(true);
            }
            if (mTransitionController.inPlayingTransition(this)) {
                mTransitionController.mValidateActivityCompat.add(this);
            }
        }

        mAtmService.getTaskChangeNotificationController().notifyActivityRequestedOrientationChanged(
                task.mTaskId, requestedOrientation);

        mDisplayContent.getDisplayRotation().onSetRequestedOrientation();
    }

    /*
     * Called from {@link RootWindowContainer#ensureVisibilityAndConfig} to make sure the
     * orientation is updated before the app becomes visible.
     */
    void reportDescendantOrientationChangeIfNeeded() {
        // Orientation request is exposed only when we're visible. Therefore visibility change
        // will change requested orientation. Notify upward the hierarchy ladder to adjust
        // configuration. This is important to cases where activities with incompatible
        // orientations launch, or user goes back from an activity of bi-orientation to an
        // activity with specified orientation.
        if (onDescendantOrientationChanged(this)) {
            // WM Shell can show additional UI elements, e.g. a restart button for size compat mode
            // so ensure that WM Shell is called when an activity becomes visible.
            task.dispatchTaskInfoChangedIfNeeded(/* force= */ true);
        }
    }

    /**
     * Ignores the activity orientation request if the App is fixed-orientation portrait and has
     * ActivityEmbedding enabled and is currently running on large screen display. Or the display
     * could be rotated to portrait and not having large enough width for app to split.
     */
    @VisibleForTesting
    boolean shouldIgnoreOrientationRequests() {
        if (!mAppActivityEmbeddingSplitsEnabled
                || !ActivityInfo.isFixedOrientationPortrait(getOverrideOrientation())
                || task.inMultiWindowMode()) {
            return false;
        }

        return getTask().getConfiguration().smallestScreenWidthDp
                >= WindowManager.LARGE_SCREEN_SMALLEST_SCREEN_WIDTH_DP;
    }

    /**
     * We override because this class doesn't want its children affecting its reported orientation
     * in anyway.
     */
    @Override
    int getOrientation(int candidate) {
        if (finishing || shouldIgnoreOrientationRequests()) {
            return SCREEN_ORIENTATION_UNSET;
        }

        if (candidate == SCREEN_ORIENTATION_BEHIND) {
            // Allow app to specify orientation regardless of its visibility state if the current
            // candidate want us to use orientation behind. I.e. the visible app on-top of this one
            // wants us to use the orientation of the app behind it.
            return getOverrideOrientation();
        }

        // The {@link ActivityRecord} should only specify an orientation when it is not closing.
        // Allowing closing {@link ActivityRecord} to participate can lead to an Activity in another
        // task being started in the wrong orientation during the transition.
        if (isVisibleRequested()) {
            return getOverrideOrientation();
        }

        return SCREEN_ORIENTATION_UNSET;
    }

    /**
     * Returns the app's preferred orientation regardless of its current visibility state taking
     * into account orientation per-app overrides applied by the device manufacturers.
     */
    @Override
    @ActivityInfo.ScreenOrientation
    protected int getOverrideOrientation() {
        int candidateOrientation = super.getOverrideOrientation();
        if (isRestrictedFixedOrientation(candidateOrientation)) {
            candidateOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
        return mAppCompatController.getOrientationPolicy()
                .overrideOrientationIfNeeded(candidateOrientation);
    }

    /**
     * Returns the app's preferred orientation regardless of its currently visibility state. This
     * is used to return a requested value to an app if they call {@link
     * android.app.Activity#getRequestedOrientation} since {@link #getOverrideOrientation} value
     * with override can confuse an app if it's different from what they requested with {@link
     * android.app.Activity#setRequestedOrientation}.
     */
    @ActivityInfo.ScreenOrientation
    int getRequestedOrientation() {
        return super.getOverrideOrientation();
    }

    /**
     * Set the last reported global configuration to the client. Should be called whenever a new
     * global configuration is sent to the client for this activity.
     */
    void setLastReportedGlobalConfiguration(@NonNull Configuration config) {
        mLastReportedConfiguration.setGlobalConfiguration(config);
    }

    /**
     * Set the last reported configuration to the client. Should be called whenever
     * a new merged configuration is sent to the client for this activity.
     */
    void setLastReportedConfiguration(@NonNull Configuration global,
            @NonNull Configuration override) {
        mLastReportedConfiguration.setConfiguration(global, override);
    }

    void setLastReportedActivityWindowInfo(@NonNull ActivityWindowInfo activityWindowInfo) {
        mLastReportedActivityWindowInfo.set(activityWindowInfo);
    }

    @Nullable
    AppCompatDisplayInsets getAppCompatDisplayInsets() {
        return mAppCompatController.getSizeCompatModePolicy().getAppCompatDisplayInsets();
    }

    /**
     * @return {@code true} if this activity is in size compatibility mode that uses the different
     *         density than its parent or its bounds don't fit in parent naturally.
     */
    boolean inSizeCompatMode() {
        return mAppCompatController.getSizeCompatModePolicy().inSizeCompatMode();
    }

    /**
     * Indicates the activity will keep the bounds and screen configuration when it was first
     * launched, no matter how its parent changes.
     *
     * <p>If {@true}, then {@link AppCompatDisplayInsets} will be created in {@link
     * #resolveOverrideConfiguration} to "freeze" activity bounds and insets.
     *
     * @return {@code true} if this activity is declared as non-resizable and fixed orientation or
     *         aspect ratio.
     */
    boolean shouldCreateAppCompatDisplayInsets() {
        return mAppCompatController.getSizeCompatModePolicy().shouldCreateAppCompatDisplayInsets();
    }

    @Override
    boolean hasSizeCompatBounds() {
        return mAppCompatController.getSizeCompatModePolicy().hasSizeCompatBounds();
    }

    @Override
    public boolean matchParentBounds() {
        final Rect overrideBounds = getResolvedOverrideBounds();
        if (overrideBounds.isEmpty()) {
            return true;
        }
        // An activity in size compatibility mode may have override bounds which equals to its
        // parent bounds, so the exact bounds should also be checked to allow IME window to attach
        // to the activity. See {@link DisplayContent#shouldImeAttachedToApp}.
        final WindowContainer parent = getParent();
        return parent == null || parent.getBounds().equals(overrideBounds);
    }

    @Override
    float getCompatScale() {
        // We need to invoke {#getCompatScale()} only if the CompatScale is not available.
        return mAppCompatController.getSizeCompatModePolicy()
                .getCompatScaleIfAvailable(ActivityRecord.super::getCompatScale);
    }

    @Override
    void resolveOverrideConfiguration(Configuration newParentConfiguration) {
        final AppCompatSizeCompatModePolicy scmPolicy =
                mAppCompatController.getSizeCompatModePolicy();
        scmPolicy.clearSizeCompatModeIfNeededOnResolveOverrideConfiguration();
        final Configuration requestedOverrideConfig = getRequestedOverrideConfiguration();
        if (requestedOverrideConfig.assetsSeq != ASSETS_SEQ_UNDEFINED
                && newParentConfiguration.assetsSeq > requestedOverrideConfig.assetsSeq) {
            requestedOverrideConfig.assetsSeq = ASSETS_SEQ_UNDEFINED;
        }
        super.resolveOverrideConfiguration(newParentConfiguration);
        final Configuration resolvedConfig = getResolvedOverrideConfiguration();

        applyLocaleOverrideIfNeeded(resolvedConfig);

        if (isFixedRotationTransforming()) {
            // The resolved configuration is applied with rotated display configuration. If this
            // activity matches its parent (the following resolving procedures are no-op), then it
            // can use the resolved configuration directly. Otherwise (e.g. fixed aspect ratio),
            // the rotated configuration is used as parent configuration to compute the actual
            // resolved configuration. It is like putting the activity in a rotated container.
            mTmpConfig.setTo(newParentConfiguration);
            mTmpConfig.updateFrom(resolvedConfig);
            newParentConfiguration = mTmpConfig;
        }
        final AppCompatAspectRatioPolicy aspectRatioPolicy =
                mAppCompatController.getAspectRatioPolicy();
        aspectRatioPolicy.reset();
        final AppCompatSafeRegionPolicy safeRegionPolicy =
                mAppCompatController.getSafeRegionPolicy();
        mAppCompatController.getLetterboxPolicy().resetFixedOrientationLetterboxEligibility();
        mResolveConfigHint.resolveTmpOverrides(mDisplayContent, newParentConfiguration,
                isFixedRotationTransforming(), safeRegionPolicy.getLatestSafeRegionBounds());

        // Can't use resolvedConfig.windowConfiguration.getWindowingMode() because it can be
        // different from windowing mode of the task (PiP) during transition from fullscreen to PiP
        // and back which can cause visible issues (see b/184078928).
        final int parentWindowingMode =
                newParentConfiguration.windowConfiguration.getWindowingMode();

        // Bubble activities should always fill their parent and should not be letterboxed.
        final boolean isFixedOrientationLetterboxAllowed = !getLaunchedFromBubble()
                && (parentWindowingMode == WINDOWING_MODE_MULTI_WINDOW
                        || parentWindowingMode == WINDOWING_MODE_FULLSCREEN
                        || AppCompatCameraPolicy.isFreeformLetterboxingForCameraAllowed(this)
                        // When starting to switch between PiP and fullscreen, the task is pinned
                        // and the activity is fullscreen. But only allow to apply letterbox if the
                        // activity is exiting PiP because an entered PiP should fill the task.
                        || (!mWaitForEnteringPinnedMode
                                && parentWindowingMode == WINDOWING_MODE_PINNED
                                && resolvedConfig.windowConfiguration.getWindowingMode()
                                        == WINDOWING_MODE_FULLSCREEN));
        // TODO(b/181207944): Consider removing the if condition and always run
        // resolveFixedOrientationConfiguration() since this should be applied for all cases.
        if (isFixedOrientationLetterboxAllowed) {
            resolveFixedOrientationConfiguration(newParentConfiguration);
        }
        // If activity in fullscreen mode is letterboxed because of fixed orientation then bounds
        // are already calculated in resolveFixedOrientationConfiguration.
        // Don't apply aspect ratio if app is overridden to fullscreen by device user/manufacturer.
        aspectRatioPolicy.resolveAspectRatioRestrictionIfNeeded(newParentConfiguration);
        final AppCompatDisplayInsets appCompatDisplayInsets = getAppCompatDisplayInsets();
        if (appCompatDisplayInsets != null) {
            scmPolicy.resolveSizeCompatModeConfiguration(newParentConfiguration,
                    appCompatDisplayInsets, mTmpBounds);
        } else if (inMultiWindowMode() && !isFixedOrientationLetterboxAllowed) {
            // We ignore activities' requested orientation in multi-window modes. They may be
            // taken into consideration in resolveFixedOrientationConfiguration call above.
            resolvedConfig.orientation = Configuration.ORIENTATION_UNDEFINED;
            // If the activity has requested override bounds, the configuration needs to be
            // computed accordingly.
            if (!matchParentBounds()) {
                computeConfigByResolveHint(resolvedConfig, newParentConfiguration);
            }
        }

        // If activity can be letterboxed due to a safe region only, use the safe region bounds
        // as the resolved bounds. We ignore cases where the letterboxing can happen due to other
        // app compat conditions and a safe region since the safe region app compat is sandboxed
        // earlier in TaskFragment.ConfigOverrideHint.resolveTmpOverrides.
        mAppCompatController.getSafeRegionPolicy().resolveSafeRegionBoundsConfigurationIfNeeded(
                resolvedConfig, newParentConfiguration);

        if (isFixedOrientationLetterboxAllowed
                || scmPolicy.hasAppCompatDisplayInsetsWithoutInheritance()
                // In fullscreen, can be letterboxed for aspect ratio.
                || !inMultiWindowMode()) {
            updateResolvedBoundsPosition(newParentConfiguration);
        }

        boolean isIgnoreOrientationRequest = mDisplayContent != null
                && mDisplayContent.getIgnoreOrientationRequest();
        if (!scmPolicy.hasAppCompatDisplayInsetsWithoutInheritance()
                // for size compat mode set in updateAppCompatDisplayInsets
                // Fixed orientation letterboxing is possible on both large screen devices
                // with ignoreOrientationRequest enabled and on phones in split screen even with
                // ignoreOrientationRequest disabled.
                && (aspectRatioPolicy.isLetterboxedForFixedOrientationAndAspectRatio()
                        // Limiting check for aspect ratio letterboxing to devices with enabled
                        // ignoreOrientationRequest. This avoids affecting phones where apps may
                        // not expect the change of smallestScreenWidthDp after rotation which is
                        // possible with this logic. Not having smallestScreenWidthDp completely
                        // accurate on phones shouldn't make the big difference and is expected
                        // to be already well-tested by apps.
                        || (isIgnoreOrientationRequest
                && aspectRatioPolicy.isAspectRatioApplied()))) {
            // TODO(b/264034555): Use mDisplayContent to calculate smallestScreenWidthDp from all
            // rotations and only re-calculate if parent bounds have non-orientation size change.
            resolvedConfig.smallestScreenWidthDp =
                    Math.min(resolvedConfig.screenWidthDp, resolvedConfig.screenHeightDp);
        }

        // Assign configuration sequence number into hierarchy because there is a different way than
        // ensureActivityConfiguration() in this class that uses configuration in WindowState during
        // layout traversals.
        mConfigurationSeq = Math.max(++mConfigurationSeq, 1);
        getResolvedOverrideConfiguration().seq = mConfigurationSeq;

        // TODO(b/392069771): Move to AppCompatSandboxingPolicy.
        // Sandbox max bounds by setting it to the activity bounds, if activity is letterboxed, or
        // has or will have mAppCompatDisplayInsets for size compat. Also forces an activity to be
        // sandboxed or not depending upon the configuration settings.
        if (providesMaxBounds()) {
            mTmpBounds.set(resolvedConfig.windowConfiguration.getBounds());
            if (mTmpBounds.isEmpty()) {
                // When there is no override bounds, the activity will inherit the bounds from
                // parent.
                mTmpBounds.set(newParentConfiguration.windowConfiguration.getBounds());
            }
            if (DEBUG_CONFIGURATION) {
                ProtoLog.d(WM_DEBUG_CONFIGURATION, "Sandbox max bounds for uid %s to bounds %s. "
                                + "config to never sandbox = %s, "
                                + "config to always sandbox = %s, "
                                + "letterboxing from mismatch with parent bounds = %s, "
                                + "has mCompatDisplayInsets = %s, "
                                + "should create compatDisplayInsets = %s",
                        getUid(),
                        mTmpBounds,
                        info.neverSandboxDisplayApis(sConstrainDisplayApisConfig),
                        info.alwaysSandboxDisplayApis(sConstrainDisplayApisConfig),
                        !matchParentBounds(),
                        scmPolicy.hasAppCompatDisplayInsetsWithoutInheritance(),
                        shouldCreateAppCompatDisplayInsets());
            }
            resolvedConfig.windowConfiguration.setMaxBounds(mTmpBounds);
        }

        mAppCompatController.getSandboxingPolicy().sandboxBoundsIfNeeded(resolvedConfig,
                parentWindowingMode);

        applySizeOverrideIfNeeded(
                mDisplayContent,
                info.applicationInfo,
                newParentConfiguration,
                resolvedConfig,
                mOptOutEdgeToEdge,
                hasFixedRotationTransform(),
                getAppCompatDisplayInsets() != null,
                task);
        mResolveConfigHint.resetTmpOverrides();

        logAppCompatState();
    }

    @Nullable Rect getParentAppBoundsOverride() {
        return Rect.copyOrNull(mResolveConfigHint.mParentAppBoundsOverride);
    }

    void computeConfigByResolveHint(@NonNull Configuration resolvedConfig,
            @NonNull Configuration parentConfig) {
        task.computeConfigResourceOverrides(resolvedConfig, parentConfig, mResolveConfigHint);
        // Reset the temp info which should only take effect for the specified computation.
        mResolveConfigHint.mTmpCompatInsets = null;
        mResolveConfigHint.mTmpOverrideDisplayInfo = null;
    }

    /**
     * Returns whether activity bounds are letterboxed.
     *
     * <p>Note that letterbox UI may not be shown even when this returns {@code true}. See {@link
     * AppCompatLetterboxOverrides#shouldShowLetterboxUi} for more context.
     */
    boolean areBoundsLetterboxed() {
        return getAppCompatState(/* ignoreVisibility= */ true)
                != APP_COMPAT_STATE_CHANGED__STATE__NOT_LETTERBOXED;
    }

    /**
     * Logs the current App Compat state via {@link ActivityMetricsLogger#logAppCompatState}.
     */
    private void logAppCompatState() {
        mTaskSupervisor.getActivityMetricsLogger().logAppCompatState(this);
    }

    /**
     * Returns the current App Compat state of this activity.
     *
     * <p>The App Compat state indicates whether the activity is visible and letterboxed, and if so
     * what is the reason for letterboxing. The state is used for logging the time spent in
     * letterbox (sliced by the reason) vs non-letterbox per app.
     */
    int getAppCompatState() {
        return getAppCompatState(/* ignoreVisibility= */ false);
    }

    /**
     * Same as {@link #getAppCompatState()} except when {@code ignoreVisibility} the visibility
     * of the activity is ignored.
     *
     * @param ignoreVisibility whether to ignore the visibility of the activity and not return
     *                         NOT_VISIBLE if {@code mVisibleRequested} is false.
     */
    private int getAppCompatState(boolean ignoreVisibility) {
        if (!ignoreVisibility && !mVisibleRequested) {
            return APP_COMPAT_STATE_CHANGED__STATE__NOT_VISIBLE;
        }
        // TODO(b/256564921): Investigate if we need new metrics for translucent activities
        if (mAppCompatController.getTransparentPolicy().isRunning()) {
            return mAppCompatController.getTransparentPolicy().getInheritedAppCompatState();
        }
        final AppCompatSizeCompatModePolicy scmPolicy = mAppCompatController
                .getSizeCompatModePolicy();
        if (scmPolicy.isInSizeCompatModeForBounds()) {
            return APP_COMPAT_STATE_CHANGED__STATE__LETTERBOXED_FOR_SIZE_COMPAT_MODE;
        }
        // Letterbox for fixed orientation. This check returns true only when an activity is
        // letterboxed for fixed orientation. Aspect ratio restrictions are also applied if
        // present. But this doesn't return true when the activity is letterboxed only because
        // of aspect ratio restrictions.
        final AppCompatAspectRatioPolicy aspectRatioPolicy =
                mAppCompatController.getAspectRatioPolicy();
        if (aspectRatioPolicy.isLetterboxedForFixedOrientationAndAspectRatio()) {
            return APP_COMPAT_STATE_CHANGED__STATE__LETTERBOXED_FOR_FIXED_ORIENTATION;
        }
        // Letterbox for limited aspect ratio.
        if (aspectRatioPolicy.isLetterboxedForAspectRatioOnly()) {
            return APP_COMPAT_STATE_CHANGED__STATE__LETTERBOXED_FOR_ASPECT_RATIO;
        }

        return APP_COMPAT_STATE_CHANGED__STATE__NOT_LETTERBOXED;
    }

    /**
     * Adjusts position of resolved bounds if they don't fill the parent using gravity
     * requested in the config or via an ADB command. For more context see {@link
     * AppCompatReachabilityOverrides#getHorizontalPositionMultiplier(Configuration)} and
     * {@link AppCompatReachabilityOverrides#getVerticalPositionMultiplier(Configuration)}
     * <p>
     * Note that this is the final step that can change the resolved bounds. After this method
     * is called, the position of the bounds will be moved to app space as sandboxing if the
     * activity has a size compat scale.
     */
    private void updateResolvedBoundsPosition(Configuration newParentConfiguration) {
        final Configuration resolvedConfig = getResolvedOverrideConfiguration();
        final Rect resolvedBounds = resolvedConfig.windowConfiguration.getBounds();
        if (resolvedBounds.isEmpty()) {
            return;
        }
        final AppCompatSizeCompatModePolicy scmPolicy =
                mAppCompatController.getSizeCompatModePolicy();
        final Rect screenResolvedBounds = scmPolicy.replaceResolvedBoundsIfNeeded(resolvedBounds);
        final Rect parentAppBounds = mResolveConfigHint.mParentAppBoundsOverride;
        final Rect parentBounds = mResolveConfigHint.mParentBoundsOverride;
        final float screenResolvedBoundsWidth = screenResolvedBounds.width();
        final float parentAppBoundsWidth = parentAppBounds.width();
        final boolean isImmersiveMode = isImmersiveMode(parentBounds);
        final Insets navBarInsets;
        if (isImmersiveMode) {
            navBarInsets = mDisplayContent.getInsetsStateController()
                    .getRawInsetsState().calculateInsets(
                            parentBounds,
                            WindowInsets.Type.navigationBars(),
                            true /* ignoreVisibility */);
        } else {
            navBarInsets = Insets.NONE;
        }
        final AppCompatReachabilityOverrides reachabilityOverrides =
                mAppCompatController.getReachabilityOverrides();
        // Horizontal position
        int offsetX = 0;
        if (parentBounds.width() != screenResolvedBoundsWidth) {
            if (screenResolvedBoundsWidth <= parentAppBoundsWidth) {
                float positionMultiplier = reachabilityOverrides.getHorizontalPositionMultiplier(
                        newParentConfiguration);
                // If in immersive mode, always align to right and overlap right insets (task bar)
                // as they are transient and hidden. This removes awkward right spacing.
                final int appWidth = (int) (parentAppBoundsWidth + navBarInsets.right);
                offsetX = Math.max(0, (int) Math.ceil((appWidth
                        - screenResolvedBoundsWidth) * positionMultiplier)
                        // This is added to make sure that insets added inside
                        // AppCompatDisplayInsets#getContainerBounds() do not break the alignment
                        // provided by the positionMultiplier
                        - screenResolvedBounds.left + parentAppBounds.left);
            }
        }

        final float parentAppBoundsHeight = parentAppBounds.height();
        final float parentBoundsHeight = parentBounds.height();
        final float screenResolvedBoundsHeight = screenResolvedBounds.height();
        // Vertical position
        int offsetY = 0;
        if (parentBoundsHeight != screenResolvedBoundsHeight) {
            if (screenResolvedBoundsHeight <= parentAppBoundsHeight) {
                float positionMultiplier = reachabilityOverrides.getVerticalPositionMultiplier(
                        newParentConfiguration);
                // If in immersive mode, always align to bottom and overlap bottom insets (nav bar,
                // task bar) as they are transient and hidden. This removes awkward bottom spacing.
                final int appHeight = (int) (parentAppBoundsHeight + navBarInsets.bottom);
                offsetY = Math.max(0, (int) Math.ceil((appHeight
                        - screenResolvedBoundsHeight) * positionMultiplier)
                        // This is added to make sure that insets added inside
                        // AppCompatDisplayInsets#getContainerBounds() do not break the alignment
                        // provided by the positionMultiplier
                        - screenResolvedBounds.top + parentAppBounds.top);
            }
        }
        // If in SCM, apply offset to resolved bounds relative to size compat bounds. If
        // not, apply directly to resolved bounds.
        if (!scmPolicy.applyOffsetIfNeeded(resolvedBounds, resolvedConfig, offsetX, offsetY)) {
            AppCompatUtils.offsetBounds(resolvedConfig, offsetX, offsetY);
        }

        // If the top is aligned with parentAppBounds add the vertical insets back so that the app
        // content aligns with the status bar
        if (resolvedConfig.windowConfiguration.getAppBounds().top == parentAppBounds.top
                && !isImmersiveMode) {
            resolvedConfig.windowConfiguration.getBounds().top = parentBounds.top;
            scmPolicy.alignToTopIfNeeded(parentBounds);
        }

        // Since bounds has changed, the configuration needs to be computed accordingly.
        computeConfigByResolveHint(resolvedConfig, newParentConfiguration);

        // The position of configuration bounds were calculated in screen space because that is
        // easier to resolve the relative position in parent container. However, if the activity is
        // scaled, the position should follow the scale because the configuration will be sent to
        // the client which is expected to be in a scaled environment.
        scmPolicy.applySizeCompatScaleIfNeeded(resolvedBounds, resolvedConfig);
    }

    boolean isImmersiveMode(@NonNull Rect parentBounds) {
        if (!mResolveConfigHint.mUseOverrideInsetsForConfig
                && mWmService.mFlags.mInsetsDecoupledConfiguration) {
            return false;
        }
        final Insets navBarInsets = mDisplayContent.getInsetsStateController()
                .getRawInsetsState().calculateInsets(
                        parentBounds,
                        WindowInsets.Type.navigationBars(),
                        false /* ignoreVisibility */);
        return Insets.NONE.equals(navBarInsets);
    }

    @NonNull Rect getScreenResolvedBounds() {
        final Configuration resolvedConfig = getResolvedOverrideConfiguration();
        final Rect resolvedBounds = resolvedConfig.windowConfiguration.getBounds();
        final AppCompatSizeCompatModePolicy scmPolicy =
                mAppCompatController.getSizeCompatModePolicy();
        return scmPolicy.replaceResolvedBoundsIfNeeded(resolvedBounds);
    }

    void recomputeConfiguration() {
        // We check if the current activity is transparent. In that case we need to
        // recomputeConfiguration of the first opaque activity beneath, to allow a
        // proper computation of the new bounds.
        if (!mAppCompatController.getTransparentPolicy().applyOnOpaqueActivityBelow(
                ActivityRecord::recomputeConfiguration)) {
            onRequestedOverrideConfigurationChanged(getRequestedOverrideConfiguration());
        }
    }

    boolean isInTransition() {
        return inTransitionSelfOrParent();
    }

    /**
     * In some cases, applying insets to bounds changes the orientation. For example, if a
     * close-to-square display rotates to portrait to respect a portrait orientation activity, after
     * insets such as the status and nav bars are applied, the activity may actually have a
     * landscape orientation. This method checks whether the orientations of the activity window
     * with and without insets match or if the orientation with insets already matches the
     * requested orientation. If not, it may be necessary to letterbox the window.
     * @param parentBounds are the new parent bounds passed down to the activity and should be used
     *                     to compute the stable bounds.
     * @param outStableBounds will store the stable bounds, which are the bounds with insets
     *                        applied, if orientation is not respected when insets are applied.g
     *                        Stable bounds should be used to compute letterboxed bounds if
     *                        orientation is not respected when insets are applied.
     * @param outNonDecorBounds will store the non decor bounds, which are the bounds with non
     *                          decor insets applied, like display cutout and nav bar.
     */
    private boolean orientationRespectedWithInsets(Rect parentBounds, Rect outStableBounds,
            Rect outNonDecorBounds) {
        outStableBounds.setEmpty();
        if (mDisplayContent == null) {
            return true;
        }
        if (!mResolveConfigHint.mUseOverrideInsetsForConfig) {
            // No insets should be considered any more.
            return true;
        }
        // Only need to make changes if activity sets an orientation
        final int requestedOrientation = getRequestedConfigurationOrientation();
        if (requestedOrientation == ORIENTATION_UNDEFINED) {
            return true;
        }
        // Compute parent orientation from bounds
        final int orientation = parentBounds.height() >= parentBounds.width()
                ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE;
        // Compute orientation from stable parent bounds (= parent bounds with insets applied)
        final DisplayInfo di = isFixedRotationTransforming()
                ? getFixedRotationTransformDisplayInfo()
                : mDisplayContent.getDisplayInfo();
        final Task task = getTask();
        task.calculateInsetFrames(outNonDecorBounds /* outNonDecorBounds */,
                outStableBounds /* outStableBounds */, parentBounds /* bounds */, di,
                mResolveConfigHint.mUseOverrideInsetsForConfig);
        final int orientationWithInsets = outStableBounds.height() >= outStableBounds.width()
                ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE;
        // If orientation does not match the orientation with insets applied, then a
        // display rotation will not be enough to respect orientation. However, even if they do
        // not match but the orientation with insets applied matches the requested orientation, then
        // there is no need to modify the bounds because when insets are applied, the activity will
        // have the desired orientation.
        final boolean orientationRespectedWithInsets = orientation == orientationWithInsets
                || orientationWithInsets == requestedOrientation;
        return orientationRespectedWithInsets;
    }

    @Override
    boolean handlesOrientationChangeFromDescendant(int orientation) {
        if (shouldIgnoreOrientationRequests()) {
            return false;
        }
        return super.handlesOrientationChangeFromDescendant(orientation);
    }

    /**
     * Computes bounds (letterbox or pillarbox) when either:
     * 1. The parent doesn't handle the orientation change and the requested orientation is
     *    different from the parent (see {@link DisplayContent#setIgnoreOrientationRequest()}.
     * 2. The parent handling the orientation is not enough. This occurs when the display rotation
     *    may not be enough to respect orientation requests (see {@link
     *    ActivityRecord#orientationRespectedWithInsets}).
     *
     * <p>If letterboxed due to fixed orientation then aspect ratio restrictions are also applied
     * in this method.
     */
    private void resolveFixedOrientationConfiguration(@NonNull Configuration newParentConfig) {
        final Rect parentBounds = mResolveConfigHint.mParentBoundsOverride;
        final Rect stableBounds = new Rect();
        final Rect outNonDecorBounds = mTmpBounds;
        // If orientation is respected when insets are applied, then stableBounds will be empty.
        boolean orientationRespectedWithInsets =
                orientationRespectedWithInsets(parentBounds, stableBounds, outNonDecorBounds);
        if (orientationRespectedWithInsets && handlesOrientationChangeFromDescendant(
                getOverrideOrientation())) {
            // No need to letterbox because of fixed orientation. Display will handle
            // fixed-orientation requests and a display rotation is enough to respect requested
            // orientation with insets applied.
            return;
        }
        // TODO(b/232898850): always respect fixed-orientation request.
        // Ignore orientation request for activity in ActivityEmbedding split.
        final TaskFragment organizedTf = getOrganizedTaskFragment();
        if (organizedTf != null && !organizedTf.fillsParent()) {
            return;
        }

        final Rect resolvedBounds =
                getResolvedOverrideConfiguration().windowConfiguration.getBounds();
        final int stableBoundsOrientation = stableBounds.width() > stableBounds.height()
                ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT;
        final int parentOrientation = mResolveConfigHint.mUseOverrideInsetsForConfig
                ? stableBoundsOrientation : newParentConfig.orientation;

        // If the activity requires a different orientation (either by override or activityInfo),
        // make it fit the available bounds by scaling down its bounds.
        final int forcedOrientation = getRequestedConfigurationOrientation();
        final boolean isEligibleForFixedOrientationLetterbox = mAppCompatController
                .getLetterboxPolicy()
                .resolveFixedOrientationLetterboxEligibility(forcedOrientation, parentOrientation);

        if (!isEligibleForFixedOrientationLetterbox && (forcedOrientation == ORIENTATION_UNDEFINED
                || orientationRespectedWithInsets)) {
            return;
        }
        final AppCompatDisplayInsets appCompatDisplayInsets = getAppCompatDisplayInsets();
        final AppCompatSizeCompatModePolicy scmPolicy =
                mAppCompatController.getSizeCompatModePolicy();

        if (appCompatDisplayInsets != null
                && !appCompatDisplayInsets.mIsInFixedOrientationOrAspectRatioLetterbox) {
            // App prefers to keep its original size.
            // If the size compat is from previous fixed orientation letterboxing, we may want to
            // have fixed orientation letterbox again, otherwise it will show the size compat
            // restart button even if the restart bounds will be the same.
            return;
        }

        final Rect parentAppBounds = mResolveConfigHint.mUseOverrideInsetsForConfig
                ? outNonDecorBounds : newParentConfig.windowConfiguration.getAppBounds();
        // TODO(b/182268157): Explore using only one type of parentBoundsWithInsets, either app
        // bounds or stable bounds to unify aspect ratio logic.
        final Rect parentBoundsWithInsets = orientationRespectedWithInsets
                ? parentAppBounds : stableBounds;
        final Rect containingBounds = new Rect();
        final Rect containingBoundsWithInsets = new Rect();
        // Need to shrink the containing bounds into a square because the parent orientation
        // does not match the activity requested orientation.
        if (forcedOrientation == ORIENTATION_LANDSCAPE) {
            // Landscape is defined as width > height. Make the container respect landscape
            // orientation by shrinking height to one less than width. Landscape activity will be
            // vertically centered within parent bounds with insets, so position vertical bounds
            // within parent bounds with insets to prevent insets from unnecessarily trimming
            // vertical bounds.
            final int bottom = Math.min(parentBoundsWithInsets.top
                            + parentBoundsWithInsets.width() - 1, parentBoundsWithInsets.bottom);
            containingBounds.set(parentBounds.left, parentBoundsWithInsets.top, parentBounds.right,
                    bottom);
            containingBoundsWithInsets.set(parentBoundsWithInsets.left, parentBoundsWithInsets.top,
                    parentBoundsWithInsets.right, bottom);
        } else {
            // Portrait is defined as width <= height. Make the container respect portrait
            // orientation by shrinking width to match height. Portrait activity will be
            // horizontally centered within parent bounds with insets, so position horizontal bounds
            // within parent bounds with insets to prevent insets from unnecessarily trimming
            // horizontal bounds.
            final int right = Math.min(parentBoundsWithInsets.left
                            + parentBoundsWithInsets.height(), parentBoundsWithInsets.right);
            containingBounds.set(parentBoundsWithInsets.left, parentBounds.top, right,
                    parentBounds.bottom);
            containingBoundsWithInsets.set(parentBoundsWithInsets.left, parentBoundsWithInsets.top,
                    right, parentBoundsWithInsets.bottom);
        }

        // Store the current bounds to be able to revert to size compat mode values below if needed.
        final Rect prevResolvedBounds = new Rect(resolvedBounds);
        resolvedBounds.set(containingBounds);

        final AppCompatAspectRatioPolicy aspectRatioPolicy = mAppCompatController
                .getAspectRatioPolicy();

        aspectRatioPolicy.applyDesiredAspectRatio(newParentConfig, parentBounds, resolvedBounds,
                        containingBoundsWithInsets, containingBounds);

        if (appCompatDisplayInsets != null) {
            appCompatDisplayInsets.getBoundsByRotation(mTmpBounds,
                    newParentConfig.windowConfiguration.getRotation());
            if (resolvedBounds.width() != mTmpBounds.width()
                    || resolvedBounds.height() != mTmpBounds.height()) {
                // The app shouldn't be resized, we only do fixed orientation letterboxing if the
                // compat bounds are also from the same fixed orientation letterbox. Otherwise,
                // clear the fixed orientation bounds to show app in size compat mode.
                resolvedBounds.set(prevResolvedBounds);
                return;
            }
        }

        // Fixed orientation bounds are the same as its parent container, so clear the fixed
        // orientation bounds. This can happen in close to square displays where the orientation
        // is not respected with insets, but the display still matches or is less than the
        // activity aspect ratio.
        if (resolvedBounds.equals(parentBounds)) {
            resolvedBounds.set(prevResolvedBounds);
            return;
        }

        // Calculate app bounds using fixed orientation bounds because they will be needed later
        // for comparison with size compat app bounds in {@link resolveSizeCompatModeConfiguration}.
        mResolveConfigHint.mTmpCompatInsets = appCompatDisplayInsets;
        computeConfigByResolveHint(getResolvedOverrideConfiguration(), newParentConfig);
        aspectRatioPolicy.setLetterboxBoundsForFixedOrientationAndAspectRatio(
                new Rect(resolvedBounds));
    }

    @Override
    public Rect getBounds() {
        // TODO(b/268458693): Refactor configuration inheritance in case of translucent activities
        final Rect superBounds = super.getBounds();
        final AppCompatSizeCompatModePolicy scmPolicy =
                mAppCompatController.getSizeCompatModePolicy();
        return mAppCompatController.getTransparentPolicy().findOpaqueNotFinishingActivityBelow()
                .map(ActivityRecord::getBounds)
                .orElseGet(() -> scmPolicy.getAppSizeCompatBoundsIfAvailable(superBounds));
    }

    @Override
    public boolean providesMaxBounds() {
        // System should always be able to access the DisplayArea bounds, so do not provide it with
        // compat max window bounds.
        if (getUid() == SYSTEM_UID) {
            return false;
        }
        // Do not sandbox to activity window bounds if the feature is disabled.
        if (mDisplayContent != null && !mDisplayContent.sandboxDisplayApis()) {
            return false;
        }
        // Never apply sandboxing to an app that should be explicitly excluded from the config.
        if (info.neverSandboxDisplayApis(sConstrainDisplayApisConfig)) {
            return false;
        }
        // Always apply sandboxing to an app that should be explicitly included from the config.
        if (info.alwaysSandboxDisplayApis(sConstrainDisplayApisConfig)) {
            return true;
        }
        // Max bounds should be sandboxed when an activity should have mAppCompatDisplayInsets,
        // and it will keep the same bounds and screen configuration when it was first launched
        // regardless how its parent window changes, so that the sandbox API will provide a
        // consistent result.
        if (getAppCompatDisplayInsets() != null || shouldCreateAppCompatDisplayInsets()) {
            return true;
        }
        // No need to sandbox for resizable apps in (including in multi-window) because
        // resizableActivity=true indicates that they support multi-window. Likewise, do not sandbox
        // for activities in letterbox since the activity has declared it can handle resizing.
        return false;
    }

    @Override
    protected boolean setOverrideGender(Configuration requestsTmpConfig, int gender) {
        return WindowProcessController.applyConfigGenderOverride(
                requestsTmpConfig, gender, mAtmService.mGrammaticalManagerInternal, getUid());
    }

    @VisibleForTesting
    @Override
    Rect getAnimationBounds(int appRootTaskClipMode) {
        // Use TaskFragment-bounds if available so that activity-level letterbox (maxAspectRatio) is
        // included in the animation.
        final TaskFragment taskFragment = getTaskFragment();
        return taskFragment != null ? taskFragment.getBounds() : getBounds();
    }

    @Override
    void getAnimationPosition(Point outPosition) {
        // Always animate from zero because if the activity doesn't fill the task, the letterbox
        // will fill the remaining area that should be included in the animation.
        outPosition.set(0, 0);
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        // We want to collect the ActivityRecord if the windowing mode is changed, so that it will
        // dispatch app transition finished event correctly at the end.
        // Check #isVisible() because we don't want to animate for activity that stays invisible.
        // Activity with #isVisibleRequested() changed should be collected when that is requested.
        if (mTransitionController.isShellTransitionsEnabled() && isVisible()
                && isVisibleRequested()) {
            final int projectedWindowingMode =
                    getRequestedOverrideWindowingMode() == WINDOWING_MODE_UNDEFINED
                            ? newParentConfig.windowConfiguration.getWindowingMode()
                            : getRequestedOverrideWindowingMode();
            if (getWindowingMode() != projectedWindowingMode
                    // Do not collect a pip activity about to enter pinned mode
                    // as a part of WindowOrganizerController#finishTransition().
                    // If not checked the activity might be collected for the wrong transition,
                    // such as a TRANSIT_OPEN transition requested right after TRANSIT_PIP.
                    && !(mWaitForEnteringPinnedMode
                    && mTransitionController.inFinishingTransition(this))) {
                mTransitionController.collect(this);
            }
        }
        if (getAppCompatDisplayInsets() != null) {
            Configuration overrideConfig = getRequestedOverrideConfiguration();
            // Adapt to changes in orientation locking. The app is still non-resizable, but
            // it can change which orientation is fixed. If the fixed orientation changes,
            // update the rotation used on the "compat" display
            boolean wasFixedOrient =
                    overrideConfig.windowConfiguration.getRotation() != ROTATION_UNDEFINED;
            int requestedOrient = getRequestedConfigurationOrientation();
            if (requestedOrient != ORIENTATION_UNDEFINED
                    && requestedOrient != getConfiguration().orientation
                    // The task orientation depends on the top activity orientation, so it
                    // should match. If it doesn't, just wait until it does.
                    && requestedOrient == getParent().getConfiguration().orientation
                    && (overrideConfig.windowConfiguration.getRotation()
                            != getParent().getWindowConfiguration().getRotation())) {
                overrideConfig.windowConfiguration.setRotation(
                        getParent().getWindowConfiguration().getRotation());
                onRequestedOverrideConfigurationChanged(overrideConfig);
                return;
            } else if (wasFixedOrient && requestedOrient == ORIENTATION_UNDEFINED
                    && (overrideConfig.windowConfiguration.getRotation()
                            != ROTATION_UNDEFINED)) {
                overrideConfig.windowConfiguration.setRotation(ROTATION_UNDEFINED);
                onRequestedOverrideConfigurationChanged(overrideConfig);
                return;
            }
        }

        final boolean wasInPictureInPicture = inPinnedWindowingMode();
        final DisplayContent display = mDisplayContent;
        final int activityType = getActivityType();
        if (wasInPictureInPicture && attachedToProcess() && display != null) {
            // If the PIP activity is changing to fullscreen with display orientation change, the
            // fixed rotation will take effect that requires to send fixed rotation adjustments
            // before the process configuration (if the process is a configuration listener of the
            // activity). So when performing process configuration on client side, it can apply
            // the adjustments (see WindowToken#onFixedRotationStatePrepared).
            try {
                app.pauseConfigurationDispatch();
                super.onConfigurationChanged(newParentConfig);
                if (mVisibleRequested && !inMultiWindowMode()) {
                    final int rotation = display.rotationForActivityInDifferentOrientation(this);
                    if (rotation != ROTATION_UNDEFINED) {
                        app.resumeConfigurationDispatch();
                        display.setFixedRotationLaunchingApp(this, rotation);
                    }
                }
            } finally {
                if (app.resumeConfigurationDispatch()) {
                    app.dispatchConfiguration(app.getConfiguration());
                }
            }
        } else {
            super.onConfigurationChanged(newParentConfig);
        }
        if (activityType != ACTIVITY_TYPE_UNDEFINED
                && activityType != getActivityType()) {
            final String errorMessage = "Can't change activity type once set: " + this
                    + " activityType=" + activityTypeToString(getActivityType()) + ", was "
                    + activityTypeToString(activityType);
            if (Build.IS_DEBUGGABLE) {
                throw new IllegalStateException(errorMessage);
            }
            Slog.w(TAG, errorMessage);
        }

        // Before PiP animation is done, th windowing mode of the activity is still the previous
        // mode (see RootWindowContainer#moveActivityToPinnedRootTask). So once the windowing mode
        // of activity is changed, it is the signal of the last step to update the PiP states.
        if (!wasInPictureInPicture && inPinnedWindowingMode() && task != null) {
            mWaitForEnteringPinnedMode = false;
            mTaskSupervisor.scheduleUpdatePictureInPictureModeIfNeeded(task, task.getBounds());
        }

        if (display == null) {
            return;
        }
        if (mVisibleRequested) {
            // It may toggle the UI for user to restart the size compatibility mode activity.
            display.handleActivitySizeCompatModeIfNeeded(this);
        } else if (getAppCompatDisplayInsets() != null && !visibleIgnoringKeyguard
                && (app == null || !app.hasVisibleActivities())) {
            // visibleIgnoringKeyguard is checked to avoid clearing mAppCompatDisplayInsets during
            // displays change. Displays are turned off during the change so mVisibleRequested
            // can be false.
            // The override changes can only be obtained from display, because we don't have the
            // difference of full configuration in each hierarchy.
            final int displayChanges = display.getCurrentOverrideConfigurationChanges();
            final int orientationChanges = CONFIG_WINDOW_CONFIGURATION
                    | CONFIG_SCREEN_SIZE | CONFIG_ORIENTATION;
            final boolean hasNonOrienSizeChanged = hasResizeChange(displayChanges)
                    // Filter out the case of simple orientation change.
                    && (displayChanges & orientationChanges) != orientationChanges;
            // For background activity that uses size compatibility mode, if the size or density of
            // the display is changed, then reset the override configuration and kill the activity's
            // process if its process state is not important to user.
            if (hasNonOrienSizeChanged || (displayChanges & ActivityInfo.CONFIG_DENSITY) != 0) {
                restartProcessIfVisible();
            }
        }
    }

    @Override
    void dispatchConfigurationToChild(WindowState child, Configuration config) {
        if (isConfigurationDispatchPaused()) {
            return;
        }
        super.dispatchConfigurationToChild(child, config);
    }

    /**
     * Pauses dispatch of configuration changes to the client. This includes any
     * configuration-triggered lifecycle changes, WindowState configs, and surface changes. If
     * a lifecycle change comes from another source (eg. stop), it will still run but will use the
     * paused configuration.
     *
     * The main way this works is by blocking calls to {@link #updateReportedConfigurationAndSend}.
     * That method is responsible for evaluating whether the activity needs to be relaunched and
     * sending configurations.
     */
    void pauseConfigurationDispatch() {
        ++mPauseConfigurationDispatchCount;
        if (mPauseConfigurationDispatchCount == 1) {
            ProtoLog.v(WM_DEBUG_WINDOW_TRANSITIONS_MIN, "Pausing configuration dispatch for "
                    + " %s", this);
        }
    }

    /** @return `true` if configuration actually changed. */
    boolean resumeConfigurationDispatch() {
        --mPauseConfigurationDispatchCount;
        if (mPauseConfigurationDispatchCount > 0) {
            return false;
        }
        ProtoLog.v(WM_DEBUG_WINDOW_TRANSITIONS_MIN, "Resuming configuration dispatch for %s", this);
        if (mPauseConfigurationDispatchCount < 0) {
            Slog.wtf(TAG, "Trying to resume non-paused configuration dispatch");
            mPauseConfigurationDispatchCount = 0;
            return false;
        }
        if (mLastReportedDisplayId == getDisplayId()
                && getConfiguration().equals(mLastReportedConfiguration.getMergedConfiguration())) {
            return false;
        }
        for (int i = getChildCount() - 1; i >= 0; --i) {
            dispatchConfigurationToChild(getChildAt(i), getConfiguration());
        }
        updateReportedConfigurationAndSend();
        return true;
    }

    boolean isConfigurationDispatchPaused() {
        return mPauseConfigurationDispatchCount > 0;
    }

    /**
     * Returns the min aspect ratio of this activity.
     */
    float getMinAspectRatio() {
        return mAppCompatController.getAspectRatioPolicy().getMinAspectRatio();
    }

    float getMaxAspectRatio() {
        return mAppCompatController.getAspectRatioPolicy().getMaxAspectRatio();
    }

    /**
     * @return {@code true} if this activity was reparented to another display but
     *         {@link #ensureActivityConfiguration} is not called.
     */
    boolean shouldUpdateConfigForDisplayChanged() {
        return mLastReportedDisplayId != getDisplayId();
    }

    boolean ensureActivityConfiguration() {
        return ensureActivityConfiguration(false /* ignoreVisibility */);
    }

    /**
     * Make sure the given activity matches the current configuration. Ensures the HistoryRecord
     * is updated with the correct configuration and all other bookkeeping is handled.
     *
     * @param ignoreVisibility If we should try to relaunch the activity even if it is invisible
     *                         (stopped state). This is useful for the case where we know the
     *                         activity will be visible soon and we want to ensure its configuration
     *                         before we make it visible.
     * @return False if the activity was relaunched and true if it wasn't relaunched because we
     *         can't or the app handles the specific configuration that is changing.
     */
    boolean ensureActivityConfiguration(boolean ignoreVisibility) {
        final Task rootTask = getRootTask();
        if (rootTask.mConfigWillChange) {
            ProtoLog.v(WM_DEBUG_CONFIGURATION, "Skipping config check "
                    + "(will change): %s", this);
            return true;
        }

        // We don't worry about activities that are finishing.
        if (finishing) {
            ProtoLog.v(WM_DEBUG_CONFIGURATION, "Configuration doesn't matter "
                    + "in finishing %s", this);
            return true;
        }

        if (isState(DESTROYED)) {
            ProtoLog.v(WM_DEBUG_CONFIGURATION, "Skipping config check "
                    + "in destroyed state %s", this);
            return true;
        }

        if (!ignoreVisibility && (mState == STOPPING || mState == STOPPED || !shouldBeVisible())) {
            ProtoLog.v(WM_DEBUG_CONFIGURATION, "Skipping config check "
                    + "invisible: %s", this);
            return true;
        }

        if (isConfigurationDispatchPaused()) {
            return true;
        }

        return updateReportedConfigurationAndSend();
    }

    boolean updateReportedConfigurationAndSend() {
        if (isConfigurationDispatchPaused()) {
            Slog.wtf(TAG, "trying to update reported(client) config while dispatch is paused");
        }
        ProtoLog.v(WM_DEBUG_CONFIGURATION, "Ensuring correct "
                + "configuration: %s", this);

        final int newDisplayId = getDisplayId();
        final boolean displayChanged = mLastReportedDisplayId != newDisplayId;
        if (displayChanged) {
            mLastReportedDisplayId = newDisplayId;
        }

        // Calling from here rather than from onConfigurationChanged because it's possible that
        // onConfigurationChanged was called before mVisibleRequested became true and
        // mAppCompatDisplayInsets may not be called again when mVisibleRequested changes. And we
        // don't want to save mAppCompatDisplayInsets in onConfigurationChanged without visibility
        // check to avoid remembering obsolete configuration which can lead to unnecessary
        // size-compat mode.
        if (mVisibleRequested) {
            // Calling from here rather than resolveOverrideConfiguration to ensure that this is
            // called after full config is updated in ConfigurationContainer#onConfigurationChanged.
            mAppCompatController.getSizeCompatModePolicy().updateAppCompatDisplayInsets();
        }

        // Short circuit: if the two full configurations are equal (the common case), then there is
        // nothing to do.  We test the full configuration instead of the global and merged override
        // configurations because there are cases (like moving a task to the root pinned task) where
        // the combine configurations are equal, but would otherwise differ in the override config
        mTmpConfig.setTo(mLastReportedConfiguration.getMergedConfiguration());
        final ActivityWindowInfo newActivityWindowInfo = getActivityWindowInfo();
        final boolean isActivityWindowInfoChanged =
                !mLastReportedActivityWindowInfo.equals(newActivityWindowInfo);
        if (!displayChanged && !isActivityWindowInfoChanged
                && getConfiguration().equals(mTmpConfig)) {
            ProtoLog.v(WM_DEBUG_CONFIGURATION, "Configuration & display "
                    + "unchanged in %s", this);
            return true;
        }

        // Okay we now are going to make this activity have the new config.
        // But then we need to figure out how it needs to deal with that.

        // Find changes between last reported merged configuration and the current one. This is used
        // to decide whether to relaunch an activity or just report a configuration change.
        final int changes = getConfigurationChanges(mTmpConfig);

        // Update last reported values.
        final Configuration newMergedOverrideConfig = getMergedOverrideConfiguration();

        setLastReportedConfiguration(getProcessGlobalConfiguration(), newMergedOverrideConfig);
        setLastReportedActivityWindowInfo(newActivityWindowInfo);

        if (mState == INITIALIZING) {
            // No need to relaunch or schedule new config for activity that hasn't been launched
            // yet. We do, however, return after applying the config to activity record, so that
            // it will use it for launch transaction.
            ProtoLog.v(WM_DEBUG_CONFIGURATION, "Skipping config check for "
                    + "initializing activity: %s", this);
            return true;
        }

        if (changes == 0) {
            ProtoLog.v(WM_DEBUG_CONFIGURATION, "Configuration no differences in %s",
                    this);
            // There are no significant differences, so we won't relaunch but should still deliver
            // the new configuration to the client process.
            if (displayChanged) {
                scheduleActivityMovedToDisplay(newDisplayId, newMergedOverrideConfig,
                        newActivityWindowInfo);
            } else {
                scheduleConfigurationChanged(newMergedOverrideConfig, newActivityWindowInfo);
            }
            notifyActivityRefresherAboutConfigurationChange(
                    mLastReportedConfiguration.getMergedConfiguration(), mTmpConfig);
            return true;
        }

        ProtoLog.v(WM_DEBUG_CONFIGURATION, "Configuration changes for %s, "
                + "allChanges=%s", this, Configuration.configurationDiffToString(changes));

        // If the activity isn't currently running, just leave the new configuration and it will
        // pick that up next time it starts.
        if (!attachedToProcess()) {
            ProtoLog.v(WM_DEBUG_CONFIGURATION, "Configuration doesn't matter not running %s", this);
            return true;
        }

        // Figure out how to handle the changes between the configurations.
        ProtoLog.v(WM_DEBUG_CONFIGURATION, "Checking to restart %s: changed=%s, "
                + "handles=%s, not-handles=%s, mLastReportedConfiguration=%s", info.name,
                Configuration.configurationDiffToString(changes),
                Configuration.configurationDiffToString(info.getRealConfigChanged()),
                Configuration.configurationDiffToString(changes & ~(info.getRealConfigChanged())),
                mLastReportedConfiguration);

        if (shouldRelaunchLocked(changes, mTmpConfig)) {
            final boolean displayMayChange = mTmpConfig.windowConfiguration.getDisplayRotation()
                    != getWindowConfiguration().getDisplayRotation()
                    || !mTmpConfig.windowConfiguration.getMaxBounds().equals(
                            getWindowConfiguration().getMaxBounds());
            final boolean isAppResizeOnly = !displayMayChange
                    && (changes & ~(CONFIG_SCREEN_SIZE | CONFIG_SMALLEST_SCREEN_SIZE
                            | CONFIG_ORIENTATION | CONFIG_SCREEN_LAYOUT)) == 0;
            // TODO(b/258618073): Always preserve if possible.
            final boolean preserveWindow = isAppResizeOnly;
            final boolean hasResizeChange = hasResizeChange(changes & ~info.getRealConfigChanged());
            if (hasResizeChange) {
                final boolean isDragResizing = task.isDragResizing();
                mRelaunchReason = isDragResizing ? RELAUNCH_REASON_FREE_RESIZE
                        : RELAUNCH_REASON_WINDOWING_MODE_RESIZE;
            } else {
                mRelaunchReason = RELAUNCH_REASON_NONE;
            }
            ProtoLog.v(WM_DEBUG_CONFIGURATION, "Config is relaunching %s", this);
            if (!mVisibleRequested) {
                ProtoLog.v(WM_DEBUG_STATES, "Config is relaunching invisible "
                        + "activity %s called by %s", this, Debug.getCallers(4));
            }
            relaunchActivityLocked(preserveWindow, changes);

            // All done...  tell the caller we weren't able to keep this activity around.
            return false;
        }

        // Default case: the activity can handle this new configuration, so hand it over.
        // NOTE: We only forward the override configuration as the system level configuration
        // changes is always sent to all processes when they happen so it can just use whatever
        // system level configuration it last got.
        if (displayChanged) {
            scheduleActivityMovedToDisplay(newDisplayId, newMergedOverrideConfig,
                    newActivityWindowInfo);
        } else {
            scheduleConfigurationChanged(newMergedOverrideConfig, newActivityWindowInfo);
        }
        notifyActivityRefresherAboutConfigurationChange(
                mLastReportedConfiguration.getMergedConfiguration(), mTmpConfig);
        return true;
    }

    private void notifyActivityRefresherAboutConfigurationChange(
            Configuration newConfig, Configuration lastReportedConfig) {
        if (!shouldBeResumed(/* activeActivity */ null)) {
            return;
        }

        AppCompatCameraPolicy.onActivityConfigurationChanging(this, newConfig, lastReportedConfig);
    }

    /** Get process configuration, or global config if the process is not set. */
    private Configuration getProcessGlobalConfiguration() {
        return app != null ? app.getConfiguration() : mAtmService.getGlobalConfiguration();
    }

    /**
     * When assessing a configuration change, decide if the changes flags and the new configurations
     * should cause the Activity to relaunch.
     *
     * @param changes the changes due to the given configuration.
     * @param changesConfig the configuration that was used to calculate the given changes via a
     *        call to getConfigurationChanges.
     */
    private boolean shouldRelaunchLocked(int changes, Configuration changesConfig) {
        int configChanged = info.getRealConfigChanged();
        if (android.content.res.Flags.handleAllConfigChanges()) {
            if ((configChanged & CONFIG_RESOURCES_UNUSED) != 0) {
                // Don't relaunch any activities that claim they do not use resources at all.
                // If they still do, the onConfigurationChanged() callback will get called to
                // let them know anyway.
                return false;
            }
        }

        boolean onlyVrUiModeChanged = onlyVrUiModeChanged(changes, changesConfig);

        // Override for apps targeting pre-O sdks
        // If a device is in VR mode, and we're transitioning into VR ui mode, add ignore ui mode
        // to the config change.
        // For O and later, apps will be required to add configChanges="uimode" to their manifest.
        if (info.applicationInfo.targetSdkVersion < O
                && requestedVrComponent != null
                && onlyVrUiModeChanged) {
            configChanged |= CONFIG_UI_MODE;
        }

        // TODO(b/274944389): remove workaround after long-term solution is implemented
        // Don't restart due to desk mode change if the app does not have desk resources.
        if (mWmService.mSkipActivityRelaunchWhenDocking && onlyDeskInUiModeChanged(changesConfig)
                && !hasDeskResources()) {
            configChanged |= CONFIG_UI_MODE;
        }

        return (changes & (~configChanged)) != 0;
    }

    /**
     * Returns true if the configuration change is solely due to the UI mode switching into or out
     * of UI_MODE_TYPE_VR_HEADSET.
     */
    private boolean onlyVrUiModeChanged(int changes, Configuration lastReportedConfig) {
        final Configuration currentConfig = getConfiguration();
        return changes == CONFIG_UI_MODE && (AppCompatUtils.isInVrUiMode(currentConfig)
            != AppCompatUtils.isInVrUiMode(lastReportedConfig));
    }

    /**
     * Returns true if the uiMode configuration changed, and desk mode
     * ({@link android.content.res.Configuration#UI_MODE_TYPE_DESK}) was the only change to uiMode.
     */
    private boolean onlyDeskInUiModeChanged(Configuration lastReportedConfig) {
        final Configuration currentConfig = getConfiguration();

        boolean deskModeChanged = isInDeskUiMode(currentConfig) != isInDeskUiMode(
                lastReportedConfig);
        // UI mode contains fields other than the UI mode type, so determine if any other fields
        // changed.
        boolean uiModeOtherFieldsChanged =
                (currentConfig.uiMode & ~UI_MODE_TYPE_MASK) != (lastReportedConfig.uiMode
                        & ~UI_MODE_TYPE_MASK);

        return deskModeChanged && !uiModeOtherFieldsChanged;
    }

    /**
     * Determines whether or not the application has desk mode resources.
     */
    boolean hasDeskResources() {
        if (mHasDeskResources != null) {
            // We already determined this, return the cached value.
            return mHasDeskResources;
        }

        mHasDeskResources = false;
        try {
            Resources packageResources = mAtmService.mContext.createPackageContextAsUser(
                    packageName, 0, UserHandle.of(mUserId)).getResources();
            for (Configuration sizeConfiguration :
                    packageResources.getSizeAndUiModeConfigurations()) {
                if (isInDeskUiMode(sizeConfiguration)) {
                    mHasDeskResources = true;
                    break;
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Exception thrown during checking for desk resources " + this, e);
        }
        return mHasDeskResources;
    }

    private int getConfigurationChanges(Configuration lastReportedConfig) {
        // Determine what has changed.  May be nothing, if this is a config that has come back from
        // the app after going idle.  In that case we just want to leave the official config object
        // now in the activity and do nothing else.
        int changes = lastReportedConfig.diff(getConfiguration());
        changes = SizeConfigurationBuckets.filterDiff(
                    changes, lastReportedConfig, getConfiguration(), mSizeConfigurations);
        // We don't want window configuration to cause relaunches.
        if ((changes & CONFIG_WINDOW_CONFIGURATION) != 0) {
            changes &= ~CONFIG_WINDOW_CONFIGURATION;
        }

        return changes;
    }

    private static boolean hasResizeChange(int change) {
        return (change & (CONFIG_SCREEN_SIZE | CONFIG_SMALLEST_SCREEN_SIZE | CONFIG_ORIENTATION
                | CONFIG_SCREEN_LAYOUT)) != 0;
    }

    void relaunchActivityLocked(boolean preserveWindow, int configChangeFlags) {
        if (mAtmService.mSuppressResizeConfigChanges && preserveWindow) {
            return;
        }
        if (!preserveWindow) {
            // If the activity is the IME input target, ensure storing the last IME shown state
            // before relaunching it for restoring the IME visibility once its new window focused.
            final InputTarget imeInputTarget = mDisplayContent.getImeInputTarget();
            mLastImeShown = imeInputTarget != null && imeInputTarget.getWindowState() != null
                    && imeInputTarget.getWindowState().mActivityRecord == this
                    && mDisplayContent.mInputMethodWindow != null
                    && mDisplayContent.mInputMethodWindow.isVisible();
        }
        // Do not waiting for translucent activity if it is going to relaunch.
        final Task rootTask = getRootTask();
        if (rootTask != null && rootTask.mTranslucentActivityWaiting == this) {
            rootTask.checkTranslucentActivityWaiting(null);
        }
        final boolean andResume = isState(RESUMED) || shouldBeResumed(null /*activeActivity*/);
        List<ResultInfo> pendingResults = null;
        List<ReferrerIntent> pendingNewIntents = null;
        if (andResume) {
            pendingResults = results;
            pendingNewIntents = newIntents;
        }
        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH,
                "Relaunching: " + this + " with results=" + pendingResults
                        + " newIntents=" + pendingNewIntents + " andResume=" + andResume
                        + " preserveWindow=" + preserveWindow);
        if (andResume) {
            EventLogTags.writeWmRelaunchResumeActivity(mUserId, System.identityHashCode(this),
                    task.mTaskId, shortComponentName, Integer.toHexString(configChangeFlags));
        } else {
            EventLogTags.writeWmRelaunchActivity(mUserId, System.identityHashCode(this),
                    task.mTaskId, shortComponentName, Integer.toHexString(configChangeFlags));
        }

        ProtoLog.i(WM_DEBUG_STATES, "Moving to %s Relaunching %s callers=%s" ,
                (andResume ? "RESUMED" : "PAUSED"), this, Debug.getCallers(6));
        final ClientTransactionItem callbackItem = new ActivityRelaunchItem(token,
                pendingResults, pendingNewIntents, configChangeFlags,
                new MergedConfiguration(getProcessGlobalConfiguration(),
                        getMergedOverrideConfiguration()),
                preserveWindow, getActivityWindowInfo());
        final ActivityLifecycleItem lifecycleItem;
        if (andResume) {
            lifecycleItem = new ResumeActivityItem(token, isTransitionForward(),
                    shouldSendCompatFakeFocus());
        } else {
            lifecycleItem = new PauseActivityItem(token);
        }
        boolean isSuccessful;
        try {
            isSuccessful = mAtmService.getLifecycleManager().scheduleTransactionItems(
                    app.getThread(), callbackItem, lifecycleItem);
        } catch (RemoteException e) {
            // TODO(b/323801078): remove Exception when cleanup
            Slog.w(TAG, "Failed to relaunch " + this + ": " + e);
            isSuccessful = false;
        }
        if (isSuccessful) {
            startRelaunching();
            // Note: don't need to call pauseIfSleepingLocked() here, because the caller will only
            // request resume if this activity is currently resumed, which implies we aren't
            // sleeping.
        }

        if (andResume) {
            ProtoLog.d(WM_DEBUG_STATES, "Resumed after relaunch %s", this);
            results = null;
            newIntents = null;
            mAtmService.getAppWarningsLocked().onResumeActivity(this);
        } else {
            removePauseTimeout();
            setState(PAUSED, "relaunchActivityLocked");
        }

        // The activity may be waiting for stop, but that is no longer appropriate for it.
        mTaskSupervisor.mStoppingActivities.remove(this);
    }

    /**
     * Request the process of the activity to restart with its saved state (from
     * {@link android.app.Activity#onSaveInstanceState}) if possible. It also forces to recompute
     * the override configuration. Note if the activity is in background, the process will be killed
     * directly with keeping its record.
     */
    void restartProcessIfVisible() {
        if (finishing) return;
        Slog.i(TAG, "Request to restart process of " + this);

        // Reset the existing override configuration so it can be updated according to the latest
        // configuration.
        mAppCompatController.getSizeCompatModePolicy().clearSizeCompatMode();
        mAppCompatController.getDisplayCompatModePolicy().onProcessRestarted();

        if (!attachedToProcess()) {
            return;
        }

        // The restarting state avoids removing this record when process is died.
        setState(RESTARTING_PROCESS, "restartActivityProcess");

        if (!mVisibleRequested || mHaveState) {
            // Kill its process immediately because the activity should be in background.
            // The activity state will be update to {@link #DESTROYED} in
            // {@link ActivityStack#cleanUp} when handling process died.
            mAtmService.mH.post(() -> {
                final WindowProcessController wpc;
                synchronized (mAtmService.mGlobalLock) {
                    if (!hasProcess()
                            || app.getReportedProcState() <= PROCESS_STATE_IMPORTANT_FOREGROUND) {
                        return;
                    }
                    wpc = app;
                }
                mAtmService.mAmInternal.killProcess(wpc.mName, wpc.mUid, "resetConfig");
            });
            return;
        }

        if (mTransitionController.isShellTransitionsEnabled()) {
            final Transition transition = new Transition(TRANSIT_RELAUNCH, 0 /* flags */,
                    mTransitionController, mWmService.mSyncEngine);
            mTransitionController.startCollectOrQueue(transition, (deferred) -> {
                if (mState != RESTARTING_PROCESS || !attachedToProcess()) {
                    transition.abort();
                    return;
                }
                // Request invisible so there will be a change after the activity is restarted
                // to be visible.
                setVisibleRequested(false);
                transition.collect(this);
                mTransitionController.requestStartTransition(transition, task,
                        null /* remoteTransition */, null /* displayChange */);
                scheduleStopForRestartProcess();
            });
        } else {
            scheduleStopForRestartProcess();
        }
    }

    private void scheduleStopForRestartProcess() {
        // The process will be killed until the activity reports stopped with saved state (see
        // {@link ActivityTaskManagerService.activityStopped}).
        final StopActivityItem item = new StopActivityItem(token);
        try {
            mAtmService.getLifecycleManager().scheduleTransactionItem(app.getThread(), item);
        } catch (RemoteException e) {
            // TODO(b/323801078): remove Exception when cleanup
            Slog.w(TAG, "Exception thrown during restart " + this, e);
        }
        mTaskSupervisor.scheduleRestartTimeout(this);
    }

    boolean isProcessRunning() {
        WindowProcessController proc = app;
        if (proc == null) {
            proc = mAtmService.mProcessNames.get(processName, info.applicationInfo.uid);
        }
        return proc != null && proc.hasThread();
    }

    /**
     * @return Whether a task snapshot starting window may be shown.
     */
    private boolean allowTaskSnapshot() {
        if (newIntents == null) {
            return true;
        }

        // Restrict task snapshot starting window to launcher start, or is same as the last
        // delivered intent, or there is no intent at all (eg. task being brought to front). If
        // the intent is something else, likely the app is going to show some specific page or
        // view, instead of what's left last time.
        for (int i = newIntents.size() - 1; i >= 0; i--) {
            final Intent intent = newIntents.get(i);
            if (intent == null || ActivityRecord.isMainIntent(intent)) {
                continue;
            }

            final boolean sameIntent = mLastNewIntent != null ? mLastNewIntent.filterEquals(intent)
                    : this.intent.filterEquals(intent);
            if (!sameIntent || intent.getExtras() != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} if the associated activity has the no history flag set on it.
     * {@code false} otherwise.
     */
    boolean isNoHistory() {
        return (intent.getFlags() & FLAG_ACTIVITY_NO_HISTORY) != 0
                || (info.flags & FLAG_NO_HISTORY) != 0;
    }

    void saveToXml(TypedXmlSerializer out) throws IOException, XmlPullParserException {
        out.attributeLong(null, ATTR_ID, createTime);
        out.attributeInt(null, ATTR_LAUNCHEDFROMUID, launchedFromUid);
        if (launchedFromPackage != null) {
            out.attribute(null, ATTR_LAUNCHEDFROMPACKAGE, launchedFromPackage);
        }
        if (launchedFromFeatureId != null) {
            out.attribute(null, ATTR_LAUNCHEDFROMFEATURE, launchedFromFeatureId);
        }
        if (resolvedType != null) {
            out.attribute(null, ATTR_RESOLVEDTYPE, resolvedType);
        }
        out.attributeBoolean(null, ATTR_COMPONENTSPECIFIED, componentSpecified);
        out.attributeInt(null, ATTR_USERID, mUserId);

        if (taskDescription != null) {
            taskDescription.saveToXml(out);
        }

        out.startTag(null, TAG_INTENT);
        intent.saveToXml(out);
        out.endTag(null, TAG_INTENT);

        if (isPersistable() && mPersistentState != null) {
            out.startTag(null, TAG_PERSISTABLEBUNDLE);
            mPersistentState.saveToXml(out);
            out.endTag(null, TAG_PERSISTABLEBUNDLE);
        }

        if (android.security.Flags.contentUriPermissionApis()) {
            ActivityCallerState.CallerInfo initialCallerInfo = mCallerState.getCallerInfoOrNull(
                    initialCallerInfoAccessToken);
            if (initialCallerInfo != null) {
                out.startTag(null, TAG_INITIAL_CALLER_INFO);
                initialCallerInfo.saveToXml(out);
                out.endTag(null, TAG_INITIAL_CALLER_INFO);
            }
        }
    }

    static ActivityRecord restoreFromXml(TypedXmlPullParser in,
            ActivityTaskSupervisor taskSupervisor) throws IOException, XmlPullParserException {
        Intent intent = null;
        PersistableBundle persistentState = null;
        int launchedFromUid = in.getAttributeInt(null, ATTR_LAUNCHEDFROMUID, 0);
        String launchedFromPackage = in.getAttributeValue(null, ATTR_LAUNCHEDFROMPACKAGE);
        String launchedFromFeature = in.getAttributeValue(null, ATTR_LAUNCHEDFROMFEATURE);
        String resolvedType = in.getAttributeValue(null, ATTR_RESOLVEDTYPE);
        boolean componentSpecified = in.getAttributeBoolean(null, ATTR_COMPONENTSPECIFIED, false);
        int userId = in.getAttributeInt(null, ATTR_USERID, 0);
        long createTime = in.getAttributeLong(null, ATTR_ID, -1);
        final int outerDepth = in.getDepth();
        ActivityCallerState.CallerInfo initialCallerInfo = null;

        TaskDescription taskDescription = new TaskDescription();
        taskDescription.restoreFromXml(in);

        int event;
        while (((event = in.next()) != END_DOCUMENT) &&
                (event != END_TAG || in.getDepth() >= outerDepth)) {
            if (event == START_TAG) {
                final String name = in.getName();
                if (DEBUG)
                        Slog.d(TaskPersister.TAG, "ActivityRecord: START_TAG name=" + name);
                if (TAG_INTENT.equals(name)) {
                    intent = Intent.restoreFromXml(in);
                    if (DEBUG)
                            Slog.d(TaskPersister.TAG, "ActivityRecord: intent=" + intent);
                } else if (TAG_PERSISTABLEBUNDLE.equals(name)) {
                    persistentState = PersistableBundle.restoreFromXml(in);
                    if (DEBUG) Slog.d(TaskPersister.TAG,
                            "ActivityRecord: persistentState=" + persistentState);
                } else if (android.security.Flags.contentUriPermissionApis()
                        && TAG_INITIAL_CALLER_INFO.equals(name)) {
                    initialCallerInfo = ActivityCallerState.CallerInfo.restoreFromXml(in);
                } else {
                    Slog.w(TAG, "restoreActivity: unexpected name=" + name);
                    XmlUtils.skipCurrentTag(in);
                }
            }
        }

        if (intent == null) {
            throw new XmlPullParserException("restoreActivity error intent=" + intent);
        }

        final ActivityTaskManagerService service = taskSupervisor.mService;
        final ActivityInfo aInfo = taskSupervisor.resolveActivity(intent, resolvedType, 0, null,
                userId, Binder.getCallingUid(), 0);
        if (aInfo == null) {
            throw new XmlPullParserException("restoreActivity resolver error. Intent=" + intent +
                    " resolvedType=" + resolvedType);
        }
        final ActivityRecord r = new ActivityRecord.Builder(service)
                .setLaunchedFromUid(launchedFromUid)
                .setLaunchedFromPackage(launchedFromPackage)
                .setLaunchedFromFeature(launchedFromFeature)
                .setIntent(intent)
                .setResolvedType(resolvedType)
                .setActivityInfo(aInfo)
                .setComponentSpecified(componentSpecified)
                .setPersistentState(persistentState)
                .setTaskDescription(taskDescription)
                .setCreateTime(createTime)
                .build();

        if (android.security.Flags.contentUriPermissionApis() && initialCallerInfo != null) {
            r.mCallerState.add(r.initialCallerInfoAccessToken, initialCallerInfo);
        }
        return r;
    }

    private static boolean isInDeskUiMode(Configuration config) {
        return (config.uiMode & UI_MODE_TYPE_MASK) == UI_MODE_TYPE_DESK;
    }

    String getProcessName() {
        return info.applicationInfo.processName;
    }

    int getUid() {
        return info.applicationInfo.uid;
    }

    boolean isUid(int uid) {
        return info.applicationInfo.uid == uid;
    }

    int getPid() {
        return app != null ? app.getPid() : 0;
    }

    int getLaunchedFromPid() {
        return launchedFromPid;
    }

    int getLaunchedFromUid() {
        return launchedFromUid;
    }

    /**
     * Gets the referrer package name with respect to package visibility. This method returns null
     * if the given package is not visible to this activity.
     */
    String getFilteredReferrer(String referrerPackage) {
        if (referrerPackage == null || (!referrerPackage.equals(packageName)
                && mWmService.mPmInternal.filterAppAccess(
                        referrerPackage, info.applicationInfo.uid, mUserId))) {
            return null;
        }
        return referrerPackage;
    }

    /**
     * Determines whether this ActivityRecord can turn the screen on. It checks whether the flag
     * {@link ActivityRecord#getTurnScreenOnFlag} is set and checks whether the ActivityRecord
     * should be visible depending on Keyguard state.
     *
     * @return true if the screen can be turned on, false otherwise.
     */
    boolean canTurnScreenOn() {
        if (!getTurnScreenOnFlag()) {
            return false;
        }
        return mCurrentLaunchCanTurnScreenOn
                && mTaskSupervisor.getKeyguardController().checkKeyguardVisibility(this);
    }

    void setTurnScreenOn(boolean turnScreenOn) {
        mTurnScreenOn = turnScreenOn;
    }

    void setAllowCrossUidActivitySwitchFromBelow(boolean allowed) {
        mAllowCrossUidActivitySwitchFromBelow = allowed;
    }

    boolean getTurnScreenOnFlag() {
        return mTurnScreenOn || containsTurnScreenOnWindow();
    }

    private boolean containsTurnScreenOnWindow() {
        // When we are relaunching, it is possible for us to be unfrozen before our previous
        // windows have been added back. Using the cached value ensures that our previous
        // showWhenLocked preference is honored until relaunching is complete.
        if (isRelaunching()) {
            return mLastContainsTurnScreenOnWindow;
        }
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            if ((mChildren.get(i).mAttrs.flags & LayoutParams.FLAG_TURN_SCREEN_ON) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if this activity is able to resume. For pre-Q apps, only the topmost activities of each
     * process are allowed to be resumed.
     *
     * @return true if this activity can be resumed.
     */
    boolean canResumeByCompat() {
        return app == null || app.updateTopResumingActivityInProcessIfNeeded(this);
    }

    boolean isTopRunningActivity() {
        return mRootWindowContainer.topRunningActivity() == this;
    }

    /**
     * @return {@code true} if this is the focused activity on its current display, {@code false}
     * otherwise.
     */
    boolean isFocusedActivityOnDisplay() {
        return mDisplayContent.forAllTaskDisplayAreas(taskDisplayArea ->
                taskDisplayArea.getFocusedActivity() == this);
    }


    /**
     * Check if this is the root of the task - first activity that is not finishing, starting from
     * the bottom of the task. If all activities are finishing - then this method will return
     * {@code true} if the activity is at the bottom.
     *
     * NOTE: This is different from 'effective root' - an activity that defines the task identity.
     */
    boolean isRootOfTask() {
        if (task == null) {
            return false;
        }
        final ActivityRecord rootActivity = task.getRootActivity(true);
        return this == rootActivity;
    }

    void setTaskOverlay(boolean taskOverlay) {
        mTaskOverlay = taskOverlay;
        setAlwaysOnTop(mTaskOverlay);
    }

    boolean isTaskOverlay() {
        return mTaskOverlay;
    }

    @Override
    public boolean isAlwaysOnTop() {
        return mTaskOverlay || super.isAlwaysOnTop();
    }

    @Override
    boolean showToCurrentUser() {
        return mShowForAllUsers || mWmService.isUserVisible(mUserId);
    }

    @Override
    boolean canCustomizeAppTransition() {
        return true;
    }

    @Override
    public String toString() {
        if (stringName != null) {
            return stringName + " t" + (task == null ? INVALID_TASK_ID : task.mTaskId) +
                    (finishing ? " f}" : "") + (mIsExiting ? " isExiting" : "") + "}";
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("ActivityRecord{");
        sb.append(System.identityHashCode(this));
        sb.append(" u");
        sb.append(mUserId);
        sb.append(' ');
        sb.append(intent.getComponent().flattenToShortString());
        stringName = sb.toString();
        return stringName;
    }

    /**
     * Write all fields to an {@code ActivityRecordProto}. This assumes the
     * {@code ActivityRecordProto} is the outer-most proto data.
     */
    void dumpDebug(ProtoOutputStream proto, @WindowTracingLogLevel int logLevel) {
        writeNameToProto(proto, NAME);
        super.dumpDebug(proto, WINDOW_TOKEN, logLevel);
        proto.write(LAST_SURFACE_SHOWING, mLastSurfaceShowing);
        proto.write(IS_ANIMATING, isAnimating(TRANSITION | PARENTS | CHILDREN,
                ANIMATION_TYPE_APP_TRANSITION | ANIMATION_TYPE_WINDOW_ANIMATION));
        proto.write(FILLS_PARENT, fillsParent());
        proto.write(APP_STOPPED, mAppStopped);
        proto.write(TRANSLUCENT, !occludesParent());
        proto.write(VISIBLE, mVisible);
        proto.write(VISIBLE_REQUESTED, mVisibleRequested);
        proto.write(CLIENT_VISIBLE, isClientVisible());
        proto.write(DEFER_HIDING_CLIENT, mDeferHidingClient);
        proto.write(REPORTED_DRAWN, mReportedDrawn);
        proto.write(REPORTED_VISIBLE, reportedVisible);
        proto.write(NUM_INTERESTING_WINDOWS, mNumInterestingWindows);
        proto.write(NUM_DRAWN_WINDOWS, mNumDrawnWindows);
        proto.write(ALL_DRAWN, allDrawn);
        if (mStartingWindow != null) {
            mStartingWindow.writeIdentifierToProto(proto, STARTING_WINDOW);
        }
        proto.write(STARTING_DISPLAYED, isStartingWindowDisplayed());
        proto.write(STARTING_MOVED, startingMoved);
        proto.write(VISIBLE_SET_FROM_TRANSFERRED_STARTING_WINDOW,
                mVisibleSetFromTransferredStartingWindow);

        proto.write(STATE, mState.toString());
        proto.write(FRONT_OF_TASK, isRootOfTask());
        if (hasProcess()) {
            proto.write(PROC_ID, app.getPid());
        }
        proto.write(PIP_AUTO_ENTER_ENABLED, pictureInPictureArgs.isAutoEnterEnabled());
        proto.write(IN_SIZE_COMPAT_MODE, inSizeCompatMode());
        proto.write(MIN_ASPECT_RATIO, getMinAspectRatio());
        // Only record if max bounds sandboxing is applied, if the caller has the necessary
        // permission to access the device configs.
        proto.write(PROVIDES_MAX_BOUNDS, providesMaxBounds());
        proto.write(ENABLE_RECENTS_SCREENSHOT, mEnableRecentsScreenshot);
        proto.write(LAST_DROP_INPUT_MODE, mLastDropInputMode);
        proto.write(OVERRIDE_ORIENTATION, getOverrideOrientation());
        proto.write(SHOULD_SEND_COMPAT_FAKE_FOCUS, shouldSendCompatFakeFocus());
        final AppCompatCameraOverrides cameraOverrides =
                mAppCompatController.getCameraOverrides();
        proto.write(SHOULD_FORCE_ROTATE_FOR_CAMERA_COMPAT,
                cameraOverrides.shouldForceRotateForCameraCompat());
        proto.write(SHOULD_REFRESH_ACTIVITY_FOR_CAMERA_COMPAT,
                cameraOverrides.shouldRefreshActivityForCameraCompat());
        proto.write(SHOULD_REFRESH_ACTIVITY_VIA_PAUSE_FOR_CAMERA_COMPAT,
                cameraOverrides.shouldRefreshActivityViaPauseForCameraCompat());
        final AppCompatAspectRatioOverrides aspectRatioOverrides =
                mAppCompatController.getAspectRatioOverrides();
        proto.write(SHOULD_OVERRIDE_MIN_ASPECT_RATIO,
                aspectRatioOverrides.shouldOverrideMinAspectRatio());
        proto.write(SHOULD_IGNORE_ORIENTATION_REQUEST_LOOP,
                mAppCompatController.getOrientationOverrides()
                        .shouldIgnoreOrientationRequestLoop());
        proto.write(SHOULD_OVERRIDE_FORCE_RESIZE_APP,
                mAppCompatController.getResizeOverrides().shouldOverrideForceResizeApp());
        proto.write(SHOULD_ENABLE_USER_ASPECT_RATIO_SETTINGS,
                aspectRatioOverrides.shouldEnableUserAspectRatioSettings());
        proto.write(IS_USER_FULLSCREEN_OVERRIDE_ENABLED,
                aspectRatioOverrides.isUserFullscreenOverrideEnabled());
        proto.write(REQUEST_OPEN_IN_BROWSER_EDUCATION_TIMESTAMP,
                mRequestOpenInBrowserEducationTimestamp);
    }

    @Override
    long getProtoFieldId() {
        return ACTIVITY;
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId,
            @WindowTracingLogLevel int logLevel) {
        // Critical log level logs only visible elements to mitigate performance overheard
        if (logLevel == WindowTracingLogLevel.CRITICAL && !isVisible()) {
            return;
        }

        final long token = proto.start(fieldId);
        dumpDebug(proto, logLevel);
        proto.end(token);
    }

    void writeNameToProto(ProtoOutputStream proto, long fieldId) {
        proto.write(fieldId, shortComponentName);
    }

    @Override
    void writeIdentifierToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(HASH_CODE, System.identityHashCode(this));
        proto.write(USER_ID, mUserId);
        proto.write(TITLE, intent.getComponent().flattenToShortString());
        proto.end(token);
    }

    private static class AppSaturationInfo {
        float[] mMatrix = new float[9];
        float[] mTranslation = new float[3];

        void setSaturation(@Size(9) float[] matrix, @Size(3) float[] translation) {
            System.arraycopy(matrix, 0, mMatrix, 0, mMatrix.length);
            System.arraycopy(translation, 0, mTranslation, 0, mTranslation.length);
        }
    }

    @Override
    boolean canCreateRemoteAnimationTarget() {
        return true;
    }

    @Override
    void getAnimationFrames(Rect outFrame, Rect outInsets, Rect outStableInsets,
            Rect outSurfaceInsets) {
        final WindowState win = findMainWindow();
        if (win == null) {
            return;
        }
        win.getAnimationFrames(outFrame, outInsets, outStableInsets, outSurfaceInsets);
    }

    void setPictureInPictureParams(PictureInPictureParams p) {
        pictureInPictureArgs.copyOnlySet(p);
        adjustPictureInPictureParamsIfNeeded(getBounds());
        getTask().getRootTask().onPictureInPictureParamsChanged();
    }

    void setShouldDockBigOverlays(boolean shouldDockBigOverlays) {
        this.shouldDockBigOverlays = shouldDockBigOverlays;
        getTask().getRootTask().onShouldDockBigOverlaysChanged();
    }

    @Override
    boolean isSyncFinished(BLASTSyncEngine.SyncGroup group) {
        if (task != null && task.mSharedStartingData != null) {
            final WindowState startingWin = task.topStartingWindow();
            if (startingWin != null && startingWin.mSyncState == SYNC_STATE_READY
                    && mDisplayContent.mUnknownAppVisibilityController.allResolved()) {
                // The sync is ready if a drawn starting window covered the task.
                return true;
            }
        }
        if (mAtmService.mBackNavigationController.isStartingSurfaceShown(this)) {
            return true;
        }
        if (!super.isSyncFinished(group)) return false;
        if (mDisplayContent != null && mDisplayContent.mUnknownAppVisibilityController
                .isVisibilityUnknown(this)) {
            return false;
        }
        if (!isVisibleRequested()) {
            // TODO(b/294925498): Remove this finishing check once we have accurate ready tracking.
            if (task != null && task.getPausingActivity() == this
                    // Display is asleep, so nothing will be visible anyways.
                    && !mDisplayContent.isSleeping()) {
                // Visibility of starting activities isn't calculated until pause-complete, so if
                // this is not paused yet, don't consider it ready.
                // However, due to pip1 having an intermediate state, add a special exception here
                // that skips waiting if the next activity is already visible.
                final ActivityRecord toResume = isPip2ExperimentEnabled() ? null
                        : mDisplayContent.getActivity((r) -> !r.finishing
                        && r.isVisibleRequested()
                        && !r.isTaskOverlay()
                        && !r.isAlwaysOnTop());
                if (toResume == null || !toResume.isVisible()) {
                    return false;
                } else {
                    Slog.i(TAG, "Assuming sync-finish while pausing due to visible target");
                }
            }
            return true;
        }
        if (mPendingRelaunchCount > 0) return false;
        // Wait for attach. That is the earliest time where we know if there will be an associated
        // display rotation. If we don't wait, the starting-window can finishDrawing first and
        // cause the display rotation to end-up in a following transition.
        if (!isAttached()) return false;
        // If visibleRequested, wait for at-least one visible child.
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            if (mChildren.get(i).isVisibleRequested()) {
                return true;
            }
        }
        return false;
    }

    @Override
    void finishSync(Transaction outMergedTransaction, BLASTSyncEngine.SyncGroup group,
            boolean cancel) {
        // This override is just for getting metrics. allFinished needs to be checked before
        // finish because finish resets all the states.
        if (isDifferentSyncGroup(group)) return;
        mLastAllReadyAtSync = allSyncFinished();
        super.finishSync(outMergedTransaction, group, cancel);
    }

    @Nullable
    Point getMinDimensions() {
        final ActivityInfo.WindowLayout windowLayout = info.windowLayout;
        if (windowLayout == null) {
            return null;
        }
        return new Point(windowLayout.minWidth, windowLayout.minHeight);
    }

    /**
     * Returns the {@link #createTime} if the top window is the `base` window. Note that do not
     * use the window creation time because the window could be re-created when the activity
     * relaunched if configuration changed.
     * <p>
     * Otherwise, return the creation time of the top window.
     */
    long getLastWindowCreateTime() {
        final WindowState window = getWindow(alwaysTruePredicate());
        return window != null && window.mAttrs.type != TYPE_BASE_APPLICATION
                ? window.getCreateTime()
                : createTime;
    }

    /**
     * Adjust the source rect hint in {@link #pictureInPictureArgs} by window bounds since
     * it is relative to its root view (see also b/235599028).
     * It is caller's responsibility to make sure this is called exactly once when we update
     * {@link #pictureInPictureArgs} to avoid double offset.
     */
    private void adjustPictureInPictureParamsIfNeeded(Rect windowBounds) {
        if (pictureInPictureArgs != null && pictureInPictureArgs.hasSourceBoundsHint()) {
            pictureInPictureArgs.getSourceRectHint().offset(windowBounds.left, windowBounds.top);
        }

        if (android.app.Flags.enableTvImplicitEnterPipRestriction()) {
            PackageManager pm = mAtmService.mContext.getPackageManager();
            if (pictureInPictureArgs.isAutoEnterEnabled()
                    && pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                    && pm.checkPermission(Manifest.permission.TV_IMPLICIT_ENTER_PIP, packageName)
                    == PackageManager.PERMISSION_DENIED) {
                Log.i(TAG,
                        "Auto-enter PiP only allowed on TV if android.permission"
                                + ".TV_IMPLICIT_ENTER_PIP permission is held by the app.");
                PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
                builder.setAutoEnterEnabled(false);
                pictureInPictureArgs.copyOnlySet(builder.build());
            }
        }
    }

    private void applyLocaleOverrideIfNeeded(Configuration resolvedConfig) {
        // We always align the locale for ActivityEmbedding apps. System apps or some apps which
        // has set known cert apps can embed across different uid activity.
        boolean shouldAlignLocale = isEmbedded()
                || (task != null && task.mAlignActivityLocaleWithTask);
        if (!shouldAlignLocale) {
            return;
        }

        boolean differentPackage = task != null
                && task.realActivity != null
                && !task.realActivity.getPackageName().equals(packageName);
        if (!differentPackage) {
            return;
        }

        final ActivityTaskManagerInternal.PackageConfig appConfig =
                mAtmService.mPackageConfigPersister.findPackageConfiguration(
                        task.realActivity.getPackageName(), mUserId);
        // If package lookup yields locales, set the target activity's locales to match,
        // otherwise leave target activity as-is.
        if (appConfig != null && appConfig.mLocales != null && !appConfig.mLocales.isEmpty()) {
            resolvedConfig.setLocales(appConfig.mLocales);
        }
    }

    /**
     * Whether we should send fake focus when the activity is resumed. This is done because some
     * game engines wait to get focus before drawing the content of the app.
     */
    boolean shouldSendCompatFakeFocus() {
        return mAppCompatController.getFocusOverrides().shouldSendFakeFocus();
    }

    boolean canCaptureSnapshot() {
        if (!isSurfaceShowing() || findMainWindow() == null) {
            return false;
        }
        return forAllWindows(
                // Ensure at least one window for the top app is visible before attempting to
                // take a screenshot. Visible here means that the WSA surface is shown and has
                // an alpha greater than 0.
                ws -> ws.mWinAnimator != null && ws.mWinAnimator.getShown()
                        && ws.mWinAnimator.mLastAlpha > 0f, true  /* traverseTopToBottom */);
    }

    void overrideCustomTransition(boolean open, int enterAnim, int exitAnim, int backgroundColor) {
        CustomAppTransition transition = getCustomAnimation(open);
        if (transition == null) {
            transition = new CustomAppTransition();
            if (open) {
                mCustomOpenTransition = transition;
            } else {
                mCustomCloseTransition = transition;
            }
        }

        transition.mEnterAnim = enterAnim;
        transition.mExitAnim = exitAnim;
        transition.mBackgroundColor = backgroundColor;
    }

    void clearCustomTransition(boolean open) {
        if (open) {
            mCustomOpenTransition = null;
        } else {
            mCustomCloseTransition = null;
        }
    }

    CustomAppTransition getCustomAnimation(boolean open) {
        return open ? mCustomOpenTransition : mCustomCloseTransition;
    }

    // Override the WindowAnimation_(Open/Close)(Enter/Exit)Animation
    static class CustomAppTransition {
        int mEnterAnim;
        int mExitAnim;
        int mBackgroundColor;
    }

    static class WindowStyle {
        private static final int FLAG_IS_TRANSLUCENT = 1;
        private static final int FLAG_IS_FLOATING = 1 << 1;
        private static final int FLAG_SHOW_WALLPAPER = 1 << 2;
        private static final int FLAG_NO_DISPLAY = 1 << 3;
        private static final int FLAG_DISABLE_PREVIEW = 1 << 4;
        private static final int FLAG_OPT_OUT_EDGE_TO_EDGE = 1 << 5;

        final int mFlags;

        @SplashScreenBehavior
        final int mSplashScreenBehavior;

        WindowStyle(TypedArray array) {
            int flags = 0;
            if (array.getBoolean(R.styleable.Window_windowIsTranslucent, false)) {
                flags |= FLAG_IS_TRANSLUCENT;
            }
            if (array.getBoolean(R.styleable.Window_windowIsFloating, false)) {
                flags |= FLAG_IS_FLOATING;
            }
            if (array.getBoolean(R.styleable.Window_windowShowWallpaper, false)) {
                flags |= FLAG_SHOW_WALLPAPER;
            }
            if (array.getBoolean(R.styleable.Window_windowNoDisplay, false)) {
                flags |= FLAG_NO_DISPLAY;
            }
            if (array.getBoolean(R.styleable.Window_windowDisablePreview, false)) {
                flags |= FLAG_DISABLE_PREVIEW;
            }
            if (array.getBoolean(R.styleable.Window_windowOptOutEdgeToEdgeEnforcement, false)) {
                flags |= FLAG_OPT_OUT_EDGE_TO_EDGE;
            }
            mFlags = flags;
            mSplashScreenBehavior = array.getInt(R.styleable.Window_windowSplashScreenBehavior, 0);
        }

        boolean isTranslucent() {
            return (mFlags & FLAG_IS_TRANSLUCENT) != 0;
        }

        boolean isFloating() {
            return (mFlags & FLAG_IS_FLOATING) != 0;
        }

        boolean showWallpaper() {
            return (mFlags & FLAG_SHOW_WALLPAPER) != 0;
        }

        boolean noDisplay() {
            return (mFlags & FLAG_NO_DISPLAY) != 0;
        }

        boolean disablePreview() {
            return (mFlags & FLAG_DISABLE_PREVIEW) != 0;
        }

        boolean optOutEdgeToEdge() {
            return (mFlags & FLAG_OPT_OUT_EDGE_TO_EDGE) != 0;
        }
    }

    static class Builder {
        private final ActivityTaskManagerService mAtmService;
        private WindowProcessController mCallerApp;
        private int mLaunchedFromPid;
        private int mLaunchedFromUid;
        private String mLaunchedFromPackage;
        private String mLaunchedFromFeature;
        private Intent mIntent;
        private String mResolvedType;
        private ActivityInfo mActivityInfo;
        private Configuration mConfiguration;
        private ActivityRecord mResultTo;
        private String mResultWho;
        private int mRequestCode;
        private boolean mComponentSpecified;
        private boolean mRootVoiceInteraction;
        private ActivityOptions mOptions;
        private ActivityRecord mSourceRecord;
        private PersistableBundle mPersistentState;
        private TaskDescription mTaskDescription;
        private long mCreateTime;

        Builder(ActivityTaskManagerService service) {
            mAtmService = service;
        }

        Builder setCaller(@NonNull WindowProcessController caller) {
            mCallerApp = caller;
            return this;
        }

        Builder setLaunchedFromPid(int pid) {
            mLaunchedFromPid = pid;
            return this;
        }

        Builder setLaunchedFromUid(int uid) {
            mLaunchedFromUid = uid;
            return this;
        }

        Builder setLaunchedFromPackage(String fromPackage) {
            mLaunchedFromPackage = fromPackage;
            return this;
        }

        Builder setLaunchedFromFeature(String fromFeature) {
            mLaunchedFromFeature = fromFeature;
            return this;
        }

        Builder setIntent(Intent intent) {
            mIntent = intent;
            return this;
        }

        Builder setResolvedType(String resolvedType) {
            mResolvedType = resolvedType;
            return this;
        }

        Builder setActivityInfo(ActivityInfo activityInfo) {
            mActivityInfo = activityInfo;
            return this;
        }

        Builder setResultTo(ActivityRecord resultTo) {
            mResultTo = resultTo;
            return this;
        }

        Builder setResultWho(String resultWho) {
            mResultWho = resultWho;
            return this;
        }

        Builder setRequestCode(int reqCode) {
            mRequestCode = reqCode;
            return this;
        }

        Builder setComponentSpecified(boolean componentSpecified) {
            mComponentSpecified = componentSpecified;
            return this;
        }

        Builder setRootVoiceInteraction(boolean rootVoiceInteraction) {
            mRootVoiceInteraction = rootVoiceInteraction;
            return this;
        }

        Builder setActivityOptions(ActivityOptions options) {
            mOptions = options;
            return this;
        }

        Builder setConfiguration(Configuration config) {
            mConfiguration = config;
            return this;
        }

        Builder setSourceRecord(ActivityRecord source) {
            mSourceRecord = source;
            return this;
        }

        private Builder setPersistentState(PersistableBundle persistentState) {
            mPersistentState = persistentState;
            return this;
        }

        private Builder setTaskDescription(TaskDescription taskDescription) {
            mTaskDescription = taskDescription;
            return this;
        }

        private Builder setCreateTime(long createTime) {
            mCreateTime = createTime;
            return this;
        }

        ActivityRecord build() {
            if (mConfiguration == null) {
                mConfiguration = mAtmService.getConfiguration();
            }
            return new ActivityRecord(mAtmService, mCallerApp, mLaunchedFromPid,
                    mLaunchedFromUid, mLaunchedFromPackage, mLaunchedFromFeature, mIntent,
                    mResolvedType, mActivityInfo, mConfiguration, mResultTo, mResultWho,
                    mRequestCode, mComponentSpecified, mRootVoiceInteraction,
                    mAtmService.mTaskSupervisor, mOptions, mSourceRecord, mPersistentState,
                    mTaskDescription, mCreateTime);
        }
    }
}
