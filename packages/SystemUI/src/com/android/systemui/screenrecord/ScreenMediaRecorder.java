/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.screenrecord;

import static android.content.Context.MEDIA_PROJECTION_SERVICE;

import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.INTERNAL;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC_AND_INTERNAL;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.ThumbnailUtils;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.media.projection.StopReason;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.Surface;

import com.android.systemui.mediaprojection.MediaProjectionCaptureTarget;
import com.android.systemui.recordissue.ScreenRecordingStartTimeStore;
import com.android.systemui.res.R;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Recording screen and mic/internal audio
 */
public class ScreenMediaRecorder extends MediaProjection.Callback {
    private static final int TOTAL_NUM_TRACKS = 1;
    private static final int VIDEO_FRAME_RATE = 30;
    private static final int VIDEO_FRAME_RATE_TO_RESOLUTION_RATIO = 6;
    private static final int MEDIUM_VIDEO_FRAME_RATE_TO_RESOLUTION_RATIO = 4;
    private static final int LOW_VIDEO_FRAME_RATE_TO_RESOLUTION_RATIO = 2;
    private static final int LOW_FRAME_RATE = 25;
    private static final int AUDIO_BIT_RATE = 196000;
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final long MAX_FILESIZE_BYTES = 16106100000L; // 15 GiB
    private static final String TAG = "ScreenMediaRecorder";


    private File mTempVideoFile;
    private File mTempAudioFile;
    private MediaProjection mMediaProjection;
    private Surface mInputSurface;
    private VirtualDisplay mVirtualDisplay;
    private MediaRecorder mMediaRecorder;
    private int mUid;
    private ScreenRecordingMuxer mMuxer;
    private ScreenInternalAudioRecorder mAudio;
    private ScreenRecordingAudioSource mAudioSource;
    private final MediaProjectionCaptureTarget mCaptureRegion;
    private final ScreenRecordingStartTimeStore mScreenRecordingStartTimeStore;
    private final Handler mHandler;
    private final int mDisplayId;
    private int mMaxRefreshRate;
    private String mAvcProfileLevel;
    private String mAvcProfileLevelMedium;
    private String mAvcProfileLevelLow;
    private String mHevcProfileLevel;
    private String mHevcProfileLevelMedium;
    private String mHevcProfileLevelLow;

    private int mLowQuality;
    private boolean mHEVC;

    private Context mContext;
    ScreenMediaRecorderListener mListener;

    public ScreenMediaRecorder(
            Context context,
            Handler handler,
            int uid,
            ScreenRecordingAudioSource audioSource,
            MediaProjectionCaptureTarget captureRegion,
            int displayId,
            ScreenMediaRecorderListener listener,
            ScreenRecordingStartTimeStore screenRecordingStartTimeStore) {
        mContext = context;
        mHandler = handler;
        mUid = uid;
        mCaptureRegion = captureRegion;
        mListener = listener;
        mAudioSource = audioSource;
        mDisplayId = displayId;
        mScreenRecordingStartTimeStore = screenRecordingStartTimeStore;
        mMaxRefreshRate = mContext.getResources().getInteger(
                R.integer.config_screenRecorderMaxFramerate);
        mAvcProfileLevel = mContext.getResources().getString(
                R.string.config_screenRecorderAVCProfileLevel);
        mAvcProfileLevelMedium = mContext.getResources().getString(
                R.string.config_screenRecorderAVCProfileLevelMedium);
        mAvcProfileLevelLow = mContext.getResources().getString(
                R.string.config_screenRecorderAVCProfileLevelLow);
        mHevcProfileLevel = mContext.getResources().getString(
                R.string.config_screenRecorderHEVCProfileLevel);
        mHevcProfileLevelMedium = mContext.getResources().getString(
                R.string.config_screenRecorderHEVCProfileLevelMedium);
        mHevcProfileLevelLow = mContext.getResources().getString(
                R.string.config_screenRecorderHEVCProfileLevelLow);
    }

    public void setLowQuality(int value) {
        mLowQuality = value;
    }

    public void setHEVC(boolean hevc) {
        mHEVC = hevc;
    }

    private void prepare() throws IOException, RemoteException, RuntimeException {
        //Setup media projection
        IBinder b = ServiceManager.getService(MEDIA_PROJECTION_SERVICE);
        IMediaProjectionManager mediaService =
                IMediaProjectionManager.Stub.asInterface(b);
        IMediaProjection proj =
                mediaService.createProjection(
                        mUid,
                        mContext.getPackageName(),
                        MediaProjectionManager.TYPE_SCREEN_CAPTURE,
                        false,
                        mDisplayId);
        IMediaProjection projection = IMediaProjection.Stub.asInterface(proj.asBinder());
        if (mCaptureRegion != null) {
            projection.setLaunchCookie(mCaptureRegion.getLaunchCookie());
            projection.setTaskId(mCaptureRegion.getTaskId());
        }
        mMediaProjection = new MediaProjection(mContext, projection);
        mMediaProjection.registerCallback(this, mHandler);

        File cacheDir = mContext.getCacheDir();
        cacheDir.mkdirs();
        mTempVideoFile = File.createTempFile("temp", ".mp4", cacheDir);

        // Set up media recorder
        mMediaRecorder = new MediaRecorder();

        // Set up audio source
        if (mAudioSource == MIC) {
            mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.DEFAULT);
        }
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);


        // Set up video
        DisplayMetrics metrics = new DisplayMetrics();
        DisplayManager dm = mContext.getSystemService(DisplayManager.class);
        Display display = dm.getDisplay(mDisplayId);
        display.getRealMetrics(metrics);
        int refreshRate = mLowQuality == 2 ? LOW_FRAME_RATE : (int) display.getRefreshRate();
        int[] dimens = getSupportedSize(metrics.widthPixels, metrics.heightPixels, refreshRate);
        int width = dimens[0];
        int height = dimens[1];
        refreshRate = dimens[2];
        int resRatio = VIDEO_FRAME_RATE_TO_RESOLUTION_RATIO;
        if (mLowQuality == 1) resRatio = MEDIUM_VIDEO_FRAME_RATE_TO_RESOLUTION_RATIO;
        else if (mLowQuality == 2) resRatio = LOW_VIDEO_FRAME_RATE_TO_RESOLUTION_RATIO;
        int vidBitRate = width * height * refreshRate / VIDEO_FRAME_RATE * resRatio;
        boolean unlimit = Settings.System.getInt(
                mContext.getContentResolver(),
                Settings.System.UNLIMIT_SCREENRECORD, 0) != 0;
        /* PS: HEVC can be set too, to reduce file size without quality loss (h265 is more efficient than h264),
        but at the same time the cpu load is 8-10 times higher and some devices don't support it yet */
        if (!mHEVC) {
            int pl = getAvcProfileLevelCodeByName(mAvcProfileLevel);
            if (mLowQuality == 1)
                pl = getAvcProfileLevelCodeByName(mAvcProfileLevelMedium);
            else if (mLowQuality == 2)
                pl = getAvcProfileLevelCodeByName(mAvcProfileLevelLow);

            mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mMediaRecorder.setVideoEncodingProfileLevel(
                        MediaCodecInfo.CodecProfileLevel.AVCProfileMain, pl);
        } else {
            int pl = getHevcProfileLevelCodeByName(mHevcProfileLevel);
            if (mLowQuality == 1)
                pl = getHevcProfileLevelCodeByName(mHevcProfileLevelMedium);
            else if (mLowQuality == 2)
                pl = getHevcProfileLevelCodeByName(mHevcProfileLevelLow);

            mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC);
            mMediaRecorder.setVideoEncodingProfileLevel(
                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain, pl);
        }
        mMediaRecorder.setVideoSize(width, height);
        mMediaRecorder.setVideoFrameRate(refreshRate);
        mMediaRecorder.setVideoEncodingBitRate(vidBitRate);
        mMediaRecorder.setMaxDuration(0); // unlimited duration
        mMediaRecorder.setMaxFileSize(unlimit ? 0 : MAX_FILESIZE_BYTES);

        // Set up audio
        if (mAudioSource == MIC) {
            mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC);
            mMediaRecorder.setAudioChannels(TOTAL_NUM_TRACKS);
            mMediaRecorder.setAudioEncodingBitRate(AUDIO_BIT_RATE);
            mMediaRecorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE);
        }

        mMediaRecorder.setOutputFile(mTempVideoFile);
        mMediaRecorder.prepare();
        // Create surface
        mInputSurface = mMediaRecorder.getSurface();
        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                "Recording Display",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mInputSurface,
                new VirtualDisplay.Callback() {
                    @Override
                    public void onStopped() {
                        onStop();
                    }
                },
                mHandler);

        mMediaRecorder.setOnInfoListener((mr, what, extra) -> mListener.onInfo(mr, what, extra));
        if (mAudioSource == INTERNAL ||
                mAudioSource == MIC_AND_INTERNAL) {
            mTempAudioFile = File.createTempFile("temp", ".aac",
                    mContext.getCacheDir());
            mAudio = new ScreenInternalAudioRecorder(mTempAudioFile.getAbsolutePath(),
                    mMediaProjection, mAudioSource == MIC_AND_INTERNAL);
        }

    }

    /**
     * Match human-readable AVC level name to its constant value.
     */
    private int getAvcProfileLevelCodeByName(final String levelName) {
        switch (levelName) {
            case "3": return MediaCodecInfo.CodecProfileLevel.AVCLevel3;
            case "3.1": return MediaCodecInfo.CodecProfileLevel.AVCLevel31;
            case "3.2": return MediaCodecInfo.CodecProfileLevel.AVCLevel32;
            case "4": return MediaCodecInfo.CodecProfileLevel.AVCLevel4;
            case "4.1": return MediaCodecInfo.CodecProfileLevel.AVCLevel41;
            default:
            case "4.2": return MediaCodecInfo.CodecProfileLevel.AVCLevel42;
        }
    }

    /**
     * Match human-readable HEVC level name to its constant value.
     */
    private int getHevcProfileLevelCodeByName(final String levelName) {
        switch (levelName) {
            case "3": return MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel3;
            case "3h": return MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel3;
            case "3.1": return MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31;
            case "3.1h": return MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel31;
            case "4": return MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4;
            case "4h": return MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4;
            case "4.1": return MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41;
            default:
            case "4.1h": return MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel41;
        }
    }

    /**
     * Find the highest supported screen resolution and refresh rate for the given dimensions on
     * this device, up to actual size and given rate.
     * If possible this will return the same values as given, but values may be smaller on some
     * devices.
     *
     * @param screenWidth Actual pixel width of screen
     * @param screenHeight Actual pixel height of screen
     * @param refreshRate Desired refresh rate
     * @return array with supported width, height, and refresh rate
     */
    private int[] getSupportedSize(final int screenWidth, final int screenHeight, int refreshRate)
            throws IOException {
        String videoType = MediaFormat.MIMETYPE_VIDEO_AVC;

        // Get max size from the decoder, to ensure recordings will be playable on device
        MediaCodec decoder = MediaCodec.createDecoderByType(videoType);
        MediaCodecInfo.VideoCapabilities vc = decoder.getCodecInfo()
                .getCapabilitiesForType(videoType).getVideoCapabilities();
        decoder.release();

        // Check if we can support screen size as-is
        int width = vc.getSupportedWidths().getUpper();
        int height = vc.getSupportedHeights().getUpper();

        int screenWidthAligned = screenWidth;
        if (screenWidthAligned % vc.getWidthAlignment() != 0) {
            screenWidthAligned -= (screenWidthAligned % vc.getWidthAlignment());
        }
        int screenHeightAligned = screenHeight;
        if (screenHeightAligned % vc.getHeightAlignment() != 0) {
            screenHeightAligned -= (screenHeightAligned % vc.getHeightAlignment());
        }

        if (width >= screenWidthAligned && height >= screenHeightAligned
                && vc.isSizeSupported(screenWidthAligned, screenHeightAligned)) {
            // Desired size is supported, now get the rate
            int maxRate = vc.getSupportedFrameRatesFor(screenWidthAligned,
                    screenHeightAligned).getUpper().intValue();

            if (maxRate < refreshRate) {
                refreshRate = maxRate;
            }
            Log.d(TAG, "Screen size supported at rate " + refreshRate);
            return new int[]{screenWidthAligned, screenHeightAligned, refreshRate};
        }

        // Otherwise, resize for max supported size
        double scale = Math.min(((double) width / screenWidth),
                ((double) height / screenHeight));

        int scaledWidth = (int) (screenWidth * scale);
        int scaledHeight = (int) (screenHeight * scale);
        if (scaledWidth % vc.getWidthAlignment() != 0) {
            scaledWidth -= (scaledWidth % vc.getWidthAlignment());
        }
        if (scaledHeight % vc.getHeightAlignment() != 0) {
            scaledHeight -= (scaledHeight % vc.getHeightAlignment());
        }

        // Find max supported rate for size
        int maxRate = vc.getSupportedFrameRatesFor(scaledWidth, scaledHeight)
                .getUpper().intValue();
        if (maxRate < refreshRate) {
            refreshRate = maxRate;
        }

        Log.d(TAG, "Resized by " + scale + ": " + scaledWidth + ", " + scaledHeight
                + ", " + refreshRate);
        return new int[]{scaledWidth, scaledHeight, refreshRate};
    }

    /**
    * Start screen recording
    */
    void start() throws IOException, RemoteException, RuntimeException {
        Log.d(TAG, "start recording");
        prepare();
        mMediaRecorder.start();
        mScreenRecordingStartTimeStore.markStartTime();
        recordInternalAudio();
    }

    /**
     * End screen recording, throws an exception if stopping recording failed
     */
    void end(@StopReason int stopReason) throws IOException {
        Closer closer = new Closer();

        // MediaRecorder might throw RuntimeException if stopped immediately after starting
        // We should remove the recording in this case as it will be invalid
        closer.register(mMediaRecorder::stop);
        closer.register(mMediaRecorder::release);
        closer.register(mInputSurface::release);
        closer.register(mVirtualDisplay::release);
        closer.register(() -> {
            if (stopReason == StopReason.STOP_UNKNOWN) {
                // Attempt to call MediaProjection#stop() even if it might have already been called.
                // If projection has already been stopped, then nothing will happen. Else, stop
                // will be logged as a manually requested stop from host app.
                mMediaProjection.stop();
            } else {
                // In any other case, the stop reason is related to the recorder, so pass it on here
                mMediaProjection.stop(stopReason);
            }
        });
        closer.register(this::stopInternalAudioRecording);

        closer.close();

        mMediaRecorder = null;
        mMediaProjection = null;

        Log.d(TAG, "end recording");
    }

    @Override
    public void onStop() {
        Log.d(TAG, "The system notified about stopping the projection");
        mListener.onStopped(StopReason.STOP_UNKNOWN);
    }

    private void stopInternalAudioRecording() {
        if (mAudioSource == INTERNAL || mAudioSource == MIC_AND_INTERNAL) {
            mAudio.end();
            mAudio = null;
        }
    }

    private  void recordInternalAudio() throws IllegalStateException {
        if (mAudioSource == INTERNAL || mAudioSource == MIC_AND_INTERNAL) {
            mAudio.start();
        }
    }

    /**
     * Store recorded video
     */
    protected SavedRecording save() throws IOException, IllegalStateException {
        String fileName = new SimpleDateFormat("'screen-'yyyyMMdd-HHmmss'.mp4'")
                .format(new Date());

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis());
        values.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + File.separator + "ScreenRecords");

        ContentResolver resolver = mContext.getContentResolver();
        Uri collectionUri = MediaStore.Video.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri itemUri = resolver.insert(collectionUri, values);

        Log.d(TAG, itemUri.toString());
        if (mAudioSource == MIC_AND_INTERNAL || mAudioSource == INTERNAL) {
            try {
                Log.d(TAG, "muxing recording");
                File file = File.createTempFile("temp", ".mp4",
                        mContext.getCacheDir());
                mMuxer = new ScreenRecordingMuxer(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                        file.getAbsolutePath(),
                        mTempVideoFile.getAbsolutePath(),
                        mTempAudioFile.getAbsolutePath());
                mMuxer.mux();
                mTempVideoFile.delete();
                mTempVideoFile = file;
            } catch (IOException e) {
                Log.e(TAG, "muxing recording " + e.getMessage());
                e.printStackTrace();
            }
        }

        // Add to the mediastore
        OutputStream os = resolver.openOutputStream(itemUri, "w");
        Files.copy(mTempVideoFile.toPath(), os);
        os.close();
        if (mTempAudioFile != null) mTempAudioFile.delete();
        SavedRecording recording = new SavedRecording(
                itemUri, mTempVideoFile, getRequiredThumbnailSize());
        mTempVideoFile.delete();
        return recording;
    }

    /**
     * Returns the required {@code Size} of the thumbnail.
     */
    private Size getRequiredThumbnailSize() {
        boolean isLowRam = ActivityManager.isLowRamDeviceStatic();
        int thumbnailIconHeight = mContext.getResources().getDimensionPixelSize(isLowRam
                ? com.android.internal.R.dimen.notification_big_picture_max_height_low_ram
                : com.android.internal.R.dimen.notification_big_picture_max_height);
        int thumbnailIconWidth = mContext.getResources().getDimensionPixelSize(isLowRam
                ? com.android.internal.R.dimen.notification_big_picture_max_width_low_ram
                : com.android.internal.R.dimen.notification_big_picture_max_width);
        return new Size(thumbnailIconWidth, thumbnailIconHeight);
    }

    /**
     * Release the resources without saving the data
     */
    protected void release() {
        if (mTempVideoFile != null) {
            mTempVideoFile.delete();
        }
        if (mTempAudioFile != null) {
            mTempAudioFile.delete();
        }
    }

    /**
    * Object representing the recording
    */
    public class SavedRecording {

        private Uri mUri;
        private Icon mThumbnailIcon;

        protected SavedRecording(Uri uri, File file, Size thumbnailSize) {
            mUri = uri;
            try {
                Bitmap thumbnailBitmap = ThumbnailUtils.createVideoThumbnail(
                        file, thumbnailSize, null);
                mThumbnailIcon = Icon.createWithBitmap(thumbnailBitmap);
            } catch (IOException e) {
                Log.e(TAG, "Error creating thumbnail", e);
            }
        }

        public Uri getUri() {
            return mUri;
        }

        public @Nullable Icon getThumbnail() {
            return mThumbnailIcon;
        }
    }

    interface ScreenMediaRecorderListener {
        /**
         * Called to indicate an info or a warning during recording.
         * See {@link MediaRecorder.OnInfoListener} for the full description.
         */
        void onInfo(MediaRecorder mr, int what, int extra);

        /**
         * Called when the recording stopped by the system.
         * For example, this might happen when doing partial screen sharing of an app
         * and the app that is being captured is closed.
         */
        void onStopped(@StopReason int stopReason);
    }

    /**
     * Allows to register multiple {@link Closeable} objects and close them all by calling
     * {@link Closer#close}. If there is an exception thrown during closing of one
     * of the registered closeables it will continue trying closing the rest closeables.
     * If there are one or more exceptions thrown they will be re-thrown at the end.
     * In case of multiple exceptions only the first one will be thrown and all the rest
     * will be printed.
     */
    private static class Closer implements Closeable {
        private final List<Closeable> mCloseables = new ArrayList<>();

        void register(Closeable closeable) {
            mCloseables.add(closeable);
        }

        @Override
        public void close() throws IOException {
            Throwable throwable = null;

            for (int i = 0; i < mCloseables.size(); i++) {
                Closeable closeable = mCloseables.get(i);

                try {
                    closeable.close();
                } catch (Throwable e) {
                    if (throwable == null) {
                        throwable = e;
                    } else {
                        e.printStackTrace();
                    }
                }
            }

            if (throwable != null) {
                if (throwable instanceof IOException) {
                    throw (IOException) throwable;
                }

                if (throwable instanceof RuntimeException) {
                    throw (RuntimeException) throwable;
                }

                throw (Error) throwable;
            }
        }
    }
}
