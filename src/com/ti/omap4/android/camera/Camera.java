/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.ti.omap4.android.camera;

import com.ti.omap4.android.camera.ui.CameraPicker;
import com.ti.omap4.android.camera.ui.FaceView;
import com.ti.omap4.android.camera.ui.IndicatorControlContainer;
import com.ti.omap4.android.camera.ui.PopupManager;
import com.ti.omap4.android.camera.ui.ManualConvergenceSettings;
import com.ti.omap4.android.camera.ui.ManualGainExposureSettings;
import com.ti.omap4.android.camera.ui.Rotatable;
import com.ti.omap4.android.camera.ui.RotateImageView;
import com.ti.omap4.android.camera.ui.RotateLayout;
import com.ti.omap4.android.camera.ui.RotateTextToast;
import com.ti.omap4.android.camera.ui.SharePopup;
import com.ti.omap4.android.camera.ui.ZoomControl;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences.Editor;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Face;
import android.hardware.Camera.FaceDetectionListener;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.Size;
import android.hardware.CameraSound;
import android.location.Location;
import android.media.CameraProfile;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Formatter;
import java.util.List;


/** The Camera activity which can preview and take pictures. */
public class Camera extends ActivityBase implements FocusManager.Listener,
        View.OnTouchListener, ShutterButton.OnShutterButtonListener,
        SurfaceHolder.Callback, ModePicker.OnModeChangeListener,
        FaceDetectionListener, CameraPreference.OnPreferenceChangedListener,
        TouchManager.Listener,
        LocationManager.Listener, ShutterButton.OnShutterButtonLongPressListener {

    private static final String TAG = "camera";

    private static final int CROP_MSG = 1;
    private static final int FIRST_TIME_INIT = 2;
    private static final int CLEAR_SCREEN_DELAY = 3;
    private static final int SET_CAMERA_PARAMETERS_WHEN_IDLE = 4;
    private static final int CHECK_DISPLAY_ROTATION = 5;
    private static final int SHOW_TAP_TO_FOCUS_TOAST = 6;
    private static final int UPDATE_THUMBNAIL = 7;

    private static final int MODE_RESTART = 9;
    private static final int RESTART_PREVIEW = 10;
    public static final int MANUAL_CONVERGENCE_CHANGED = 11;
    public static final int MANUAL_GAIN_EXPOSURE_CHANGED = 12;
    public static final int RELEASE_CAMERA = 13;
    // The subset of parameters we need to update in setCameraParameters().
    private static final int UPDATE_PARAM_INITIALIZE = 1;
    private static final int UPDATE_PARAM_ZOOM = 2;
    private static final int UPDATE_PARAM_PREFERENCE = 4;
    private static final int UPDATE_PARAM_MODE = 8;
    private static final int UPDATE_PARAM_ALL = -1;

    // When setCameraParametersWhenIdle() is called, we accumulate the subsets
    // needed to be updated in mUpdateSet.
    private int mUpdateSet;

    private static final int SCREEN_DELAY = 2 * 60 * 1000;
    private static final int CAMERA_RELEASE_DELAY = 3000;

    private static final int ZOOM_STOPPED = 0;
    private static final int ZOOM_START = 1;
    private static final int ZOOM_STOPPING = 2;

    private int mZoomState = ZOOM_STOPPED;
    private boolean mSmoothZoomSupported = false;
    private boolean mBurstRunning = false;
    private int mZoomValue;  // The current zoom value.
    private int mZoomMax;
    private int mTargetZoomValue;
    private ZoomControl mZoomControl;

    private Parameters mParameters;
    private Parameters mInitialParams;
    private boolean mFocusAreaSupported;
    private boolean mMeteringAreaSupported;
    private boolean mAeLockSupported;
    private boolean mAwbLockSupported;

    private MyOrientationEventListener mOrientationListener;
    // The degrees of the device rotated clockwise from its natural orientation.
    private int mOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;
    // The orientation compensation for icons and thumbnails. Ex: if the value
    // is 90, the UI components should be rotated 90 degrees counter-clockwise.
    private int mOrientationCompensation = 0;
    private ComboPreferences mPreferences;

    private static final String sTempCropFilename = "crop-temp";

    private ContentProviderClient mMediaProviderClient;
    private SurfaceHolder mSurfaceHolder = null;
    private ShutterButton mShutterButton;
    private GestureDetector mPopupGestureDetector;
    private GestureDetector mGestureDetector;
    private boolean mOpenCameraFail = false;
    private boolean mCameraDisabled = false;
    public  static boolean mIsTestExecuting = false;
    private boolean mFaceDetectionStarted = false;

    private View mPreviewPanel;  // The container of PreviewFrameLayout.
    private PreviewFrameLayout mPreviewFrameLayout;
    private View mPreviewFrame;  // Preview frame area.
    private RotateDialogController mRotateDialog;

    // A popup window that contains a bigger thumbnail and a list of apps to share.
    private SharePopup mSharePopup;
    // The bitmap of the last captured picture thumbnail and the URI of the
    // original picture.
    private Thumbnail mThumbnail;
    // An imageview showing showing the last captured picture thumbnail.
    private RotateImageView mThumbnailView;
    private ModePicker mModePicker;
    private FaceView mFaceView;
    private RotateLayout mFocusAreaIndicator;
    private Rotatable mReviewCancelButton;
    private Rotatable mReviewDoneButton;

    // mCropValue and mSaveUri are used only if isImageCaptureIntent() is true.
    private String mCropValue;
    private Uri mSaveUri;

    // Small indicators which show the camera settings in the viewfinder.
    private TextView mExposureIndicator;
    private ImageView mGpsIndicator;
    private ImageView mFlashIndicator;
    private ImageView mSceneIndicator;
    private ImageView mWhiteBalanceIndicator;
    private ImageView mFocusIndicator;
    // A view group that contains all the small indicators.
    private Rotatable mOnScreenIndicators;

    // We use a thread in ImageSaver to do the work of saving images and
    // generating thumbnails. This reduces the shot-to-shot time.
    private ImageSaver mImageSaver;

    private CameraSound mCameraSound;

    private S3DViewWrapper s3dView;
    private boolean mS3dViewEnabled = false;

    private Runnable mDoSnapRunnable = new Runnable() {
        public void run() {
            onShutterButtonClick();
        }
    };

    private final StringBuilder mBuilder = new StringBuilder();
    private final Formatter mFormatter = new Formatter(mBuilder);
    private final Object[] mFormatterArgs = new Object[1];

    /**
     * An unpublished intent flag requesting to return as soon as capturing
     * is completed.
     *
     * TODO: consider publishing by moving into MediaStore.
     */
    private static final String EXTRA_QUICK_CAPTURE =
            "android.intent.extra.quickCapture";

    // The display rotation in degrees. This is only valid when mCameraState is
    // not PREVIEW_STOPPED.
    private int mDisplayRotation;
    // The value for android.hardware.Camera.setDisplayOrientation.
    private int mDisplayOrientation;
    private boolean mPausing;
    private boolean mFirstTimeInitialized;
    private boolean mIsImageCaptureIntent;

    private static final int PREVIEW_STOPPED = 0;
    private static final int IDLE = 1;  // preview is active
    // Focus is in progress. The exact focus state is in Focus.java.
    private static final int FOCUSING = 2;
    private static final int SNAPSHOT_IN_PROGRESS = 3;
    private int mCameraState = PREVIEW_STOPPED;
    private Object mCameraStateLock = new Object();
    private boolean mSnapshotOnIdle = false;

    private ContentResolver mContentResolver;
    private boolean mDidRegister = false;

    private LocationManager mLocationManager;

    private final ShutterCallback mShutterCallback = new ShutterCallback();
    private final PostViewPictureCallback mPostViewPictureCallback =
            new PostViewPictureCallback();
    private final RawPictureCallback mRawPictureCallback =
            new RawPictureCallback();
    private final AutoFocusCallback mAutoFocusCallback =
            new AutoFocusCallback();
    private final ZoomListener mZoomListener = new ZoomListener();
    private final CameraErrorCallback mErrorCallback = new CameraErrorCallback();

    private static final String PARM_SENSOR_ORIENTATION = "sensor-orientation";
    private static final String PARM_GBCE = "gbce";
    private static final String PARM_GLBCE = "glbce";
    private static final String PARM_GBCE_OFF = "disable";
    private static final String PARM_IPP = "ipp";
    private static final String PARM_IPP_LDCNSF = "ldc-nsf";
    private static final String PARM_IPP_NONE = "off";
    private static final String PARM_BURST = "burst-capture";
    private static final String PARM_TEMPORAL_BRACKETING_ENABLE = "enable";
    private static final String PARM_TEMPORAL_BRACKETING_DISABLE = "disable";
    private static final String PARM_EXPOSURE_BRACKETING_RANGE = "exp-bracketing-range";
    private static final String EXPOSURE_BRACKETING_RANGE_VALUE = "-30,0,30";
    private static final int EXPOSURE_BRACKETING_COUNT = 3;
    private static final String PARM_TEMPORAL_BRACKETING_RANGE_POS = "temporal-bracketing-range-positive";
    private static final String PARM_TEMPORAL_BRACKETING_RANGE_NEG = "temporal-bracketing-range-negative";
    public static final String PARM_SUPPORTED_ISO_MODES = "iso-mode-values";
    public static final String PARM_SUPPORTED_EXPOSURE_MODES = "exposure-mode-values";
    public static final String PARM_SUPPORTED_GBCE = "gbce-supported";
    public static final String PARM_SUPPORTED_GLBCE = "glbce-supported";
    private static final String PARM_ISO = "iso";
    private static final String PARM_EXPOSURE_MODE = "exposure";
    private static final String PARM_CONTRAST = "contrast";
    private static final String PARM_BRIGHTNESS = "brightness";
    private static final String PARM_SHARPNESS = "sharpness";
    private static final String PARM_SATURATION = "saturation";

    public static final String TRUE = "true";
    public static final String FALSE = "false";

    private String mTouchConvergence;
    private String mManualConvergence;
    private String mTemporalBracketing;
    private String mExposureBracketing;
    private String mHighPerformance;
    private String mHighQuality;
    private String mHighQualityZsl;
    private String mGBCEOff;
    private String mManualExposure;

    // Limits ZSL capture size due to some hardware limitations
    private static final String PARM_ZSL_SIZE = "2016x1512";

    private int mBurstImages = 0;
    private boolean mTempBracketingEnabled = false;

    private String mCaptureMode = "high-quality";
    private String mGBCE = "off";
    private String mBracketRange;
    private String mISO = null;
    private String mExposureMode = null;
    private String mPreviewSize = null;
    private String mColorEffect = null;
    private String mAntibanding = null;
    private String mContrast = null;
    private String mBrightness = null;
    public  static String mIMGscriptTitle;
    private String mLastPreviewFramerate;
    private String mSaturation = null;
    private String mSharpness = null;
    private int mJpegQuality = CameraProfile.QUALITY_HIGH;
    private String mAutoConvergence = null;
    private String mMechanicalMisalignmentCorrection = null;
    private String mPreviewLayout = null;
    private boolean mIsPreviewLayoutInit = false;
    private boolean mIsCaptureLayoutInit = false;
    private String mCaptureLayout = null;
    private String mPictureFormat = null;
    private long mFocusStartTime;
    private long mCaptureStartTime;
    private long mShutterCallbackTime;
    private long mPostViewPictureCallbackTime;
    private long mRawPictureCallbackTime;
    private long mJpegPictureCallbackTime;
    private long mOnResumeTime;
    private long mPicturesRemaining;
    private byte[] mJpegImageData;
    private boolean isExposureInit = false;
    private boolean isManualExposure = false;
    private Integer mManualExposureLeft = new Integer (0);
    private Integer mManualExposureRight = new Integer (0);
    private Integer mManualGainISOLeft = new Integer (0);
    private Integer mManualGainISORight = new Integer (0);
    private Integer mManualExposureControl = new Integer(0);
    private Integer mManualGainISO = new Integer(0);

    // These latency time are for the CameraLatency test.
    public long mAutoFocusTime;
    public long mShutterLag;
    public long mShutterToPictureDisplayedTime;
    public long mPictureDisplayedToJpegCallbackTime;
    public long mJpegCallbackFinishTime;

    private Integer mManualConvergenceValue = new Integer(0);
    private boolean isManualConvergence = false;
    private boolean isConvergenceInit = false;

    private TouchManager mTouchManager;

    // This handles everything about focus.
    private FocusManager mFocusManager;
    private String mSceneMode;
    private Toast mNotSelectableToast;
    private Toast mNoShareToast;
    private boolean mTouchFocusEnabled = false;

    private final Handler mHandler = new MainHandler();
    private IndicatorControlContainer mIndicatorControlContainer;
    private PreferenceGroup mPreferenceGroup;

    // multiple cameras support
    private int mNumberOfCameras;
    private int mCameraId;

    private boolean mQuickCapture;
    private IntentFilter mTestIntent = null;
    private final BroadcastReceiver mTestReceiver = new CameraBroadcastReceiverTest();

    /**
     * This Handler is used to post message back onto the main thread of the
     * application
     */
    private class MainHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case RESTART_PREVIEW: {
                    boolean updateAllParams = true;
                    if ( msg.arg1 == MODE_RESTART ) {
                        updateAllParams = false;
                    }
                    startPreview(updateAllParams);
                    startFaceDetection();
                    if (mJpegPictureCallbackTime != 0) {
                        long now = System.currentTimeMillis();
                        mJpegCallbackFinishTime = now - mJpegPictureCallbackTime;
                        Log.v(TAG, "mJpegCallbackFinishTime = "
                                + mJpegCallbackFinishTime + "ms");
                        mJpegPictureCallbackTime = 0;
                    }
                    break;
                }

                case CLEAR_SCREEN_DELAY: {
                    getWindow().clearFlags(
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    break;
                }

                case FIRST_TIME_INIT: {
                    initializeFirstTime();
                    break;
                }

                case SET_CAMERA_PARAMETERS_WHEN_IDLE: {
                    setCameraParametersWhenIdle(0);
                    break;
                }

                case CHECK_DISPLAY_ROTATION: {
                    // Set the display orientation if display rotation has changed.
                    // Sometimes this happens when the device is held upside
                    // down and camera app is opened. Rotation animation will
                    // take some time and the rotation value we have got may be
                    // wrong. Framework does not have a callback for this now.
                    if (Util.getDisplayRotation(Camera.this) != mDisplayRotation
                            && isCameraIdle()) {
                        startPreview(true);
                    }
                    if (SystemClock.uptimeMillis() - mOnResumeTime < 5000) {
                        mHandler.sendEmptyMessageDelayed(CHECK_DISPLAY_ROTATION, 100);
                    }
                    break;
                }

                case SHOW_TAP_TO_FOCUS_TOAST: {
                    showTapToFocusToast();
                    break;
                }

                case UPDATE_THUMBNAIL: {
                    mImageSaver.updateThumbnail();
                    break;
                }
                case MANUAL_CONVERGENCE_CHANGED: {
                    mManualConvergenceValue = (Integer) msg.obj;
                    mParameters.set(CameraSettings.KEY_MANUAL_CONVERGENCE, mManualConvergenceValue.intValue());
                    if (mCameraDevice != null) {
                        mCameraDevice.setParameters(mParameters);
                    }
                    Log.e(TAG,"mManualConvergenceValue = "+ mManualConvergenceValue);
                    break;
                }
                case MANUAL_GAIN_EXPOSURE_CHANGED: {
                    Bundle data;
                    data = msg.getData();
                    if (is2DMode()) {
                        mManualExposureControl = data.getInt("EXPOSURE");
                        if (mManualExposureControl < 1) {
                            mManualExposureControl = 1;
                        }
                        mManualGainISO = data.getInt("ISO");
                        mParameters.set(CameraSettings.KEY_MANUAL_EXPOSURE, mManualExposureControl.intValue());
                        mParameters.set(CameraSettings.KEY_MANUAL_GAIN_ISO, mManualGainISO.intValue());
                    } else {
                        mManualExposureRight = data.getInt("EXPOSURE_RIGHT");
                        if (mManualExposureRight < 1) {
                            mManualExposureRight = 1;
                        }
                        mManualExposureLeft = data.getInt("EXPOSURE_LEFT");
                        if (mManualExposureLeft < 1) {
                            mManualExposureLeft = 1;
                        }
                        mManualGainISORight = data.getInt("ISO_RIGHT");
                        mManualGainISOLeft = data.getInt("ISO_LEFT");

                        mParameters.set(CameraSettings.KEY_MANUAL_EXPOSURE_RIGHT, mManualExposureRight.intValue());
                        mParameters.set(CameraSettings.KEY_MANUAL_EXPOSURE, mManualExposureLeft.intValue());
                        mParameters.set(CameraSettings.KEY_MANUAL_GAIN_ISO, mManualGainISOLeft.intValue());
                        mParameters.set(CameraSettings.KEY_MANUAL_GAIN_ISO_RIGHT, mManualGainISORight.intValue());
                    }
                    if (mCameraDevice != null) {
                        mCameraDevice.setParameters(mParameters);
                    }
                    break;
                }
                case RELEASE_CAMERA: {
                    stopPreview();
                    closeCamera();
                    break;
                }
            }
        }
    }

    private void resetExposureCompensation() {
        String value = mPreferences.getString(CameraSettings.KEY_EXPOSURE_COMPENSATION_MENU,
                CameraSettings.EXPOSURE_DEFAULT_VALUE);
        if (!CameraSettings.EXPOSURE_DEFAULT_VALUE.equals(value)) {
            Editor editor = mPreferences.edit();
            editor.putString(CameraSettings.KEY_EXPOSURE_COMPENSATION_MENU, "0");
            editor.apply();
            if (mIndicatorControlContainer != null) {
                mIndicatorControlContainer.reloadPreferences();
            }
        }
    }

    private void keepMediaProviderInstance() {
        // We want to keep a reference to MediaProvider in camera's lifecycle.
        // TODO: Utilize mMediaProviderClient instance to replace
        // ContentResolver calls.
        if (mMediaProviderClient == null) {
            mMediaProviderClient = getContentResolver()
                    .acquireContentProviderClient(MediaStore.AUTHORITY);
        }
    }

    // Snapshots can only be taken after this is called. It should be called
    // once only. We could have done these things in onCreate() but we want to
    // make preview screen appear as soon as possible.
    private void initializeFirstTime() {
        if (mFirstTimeInitialized) return;

        // Create orientation listenter. This should be done first because it
        // takes some time to get first orientation.
        mOrientationListener = new MyOrientationEventListener(Camera.this);
        mOrientationListener.enable();

        // Initialize location sevice.
        boolean recordLocation = RecordLocationPreference.get(
                mPreferences, getContentResolver());
        initOnScreenIndicator();
        mLocationManager.recordLocation(recordLocation);

        keepMediaProviderInstance();
        checkStorage();

        // Initialize last picture button.
        mContentResolver = getContentResolver();
        if (!mIsImageCaptureIntent) {  // no thumbnail in image capture intent
            initThumbnailButton();
        }

        // Initialize shutter button.
        mShutterButton = (ShutterButton) findViewById(R.id.shutter_button);
        mShutterButton.setOnShutterButtonListener(this);
        mShutterButton.setOnShutterButtonLongPressListener(this);
        mShutterButton.setVisibility(View.VISIBLE);

        // Initialize focus UI.
        mPreviewFrame = findViewById(R.id.camera_preview);
        mPreviewFrame.setOnTouchListener(this);
        mFocusAreaIndicator = (RotateLayout) findViewById(R.id.focus_indicator_rotate_layout);
        CameraInfo info = CameraHolder.instance().getCameraInfo()[mCameraId];
        boolean mirror = (info.facing == CameraInfo.CAMERA_FACING_FRONT);
        mFocusManager.initialize(mFocusAreaIndicator, mPreviewFrame, mFaceView, this,
                mirror, mDisplayOrientation);
        mImageSaver = new ImageSaver();
        Util.initializeScreenBrightness(getWindow(), getContentResolver());
        installIntentFilter();
        initializeZoom();
        updateOnScreenIndicators();
        startFaceDetection();
        // Show the tap to focus toast if this is the first start.
        if (mFocusAreaSupported &&
                mPreferences.getBoolean(CameraSettings.KEY_CAMERA_FIRST_USE_HINT_SHOWN, true)) {
            // Delay the toast for one second to wait for orientation.
            mHandler.sendEmptyMessageDelayed(SHOW_TAP_TO_FOCUS_TOAST, 1000);
        }

        mFirstTimeInitialized = true;
        addIdleHandler();
    }

    private void addIdleHandler() {
        MessageQueue queue = Looper.myQueue();
        queue.addIdleHandler(new MessageQueue.IdleHandler() {
            public boolean queueIdle() {
                Storage.ensureOSXCompatible();
                return false;
            }
        });
    }

    private void initThumbnailButton() {
        // Load the thumbnail from the disk.
        mThumbnail = Thumbnail.loadFrom(new File(getFilesDir(), Thumbnail.LAST_THUMB_FILENAME));
        updateThumbnailButton();
    }

    private void updateThumbnailButton() {
        // Update last image if URI is invalid and the storage is ready.
        if ((mThumbnail == null || !Util.isUriValid(mThumbnail.getUri(), mContentResolver))
                && mPicturesRemaining >= 0) {
            mThumbnail = Thumbnail.getLastThumbnail(mContentResolver);
        }
        if (mThumbnail != null) {
            mThumbnailView.setBitmap(mThumbnail.getBitmap());
        } else {
            mThumbnailView.setBitmap(null);
        }
    }

    // If the activity is paused and resumed, this method will be called in
    // onResume.
    private void initializeSecondTime() {
        // Start orientation listener as soon as possible because it takes
        // some time to get first orientation.
        mOrientationListener.enable();

        // Start location update if needed.
        boolean recordLocation = RecordLocationPreference.get(
                mPreferences, getContentResolver());
        mLocationManager.recordLocation(recordLocation);

        installIntentFilter();
        mImageSaver = new ImageSaver();
        initializeZoom();
        keepMediaProviderInstance();
        checkStorage();
        hidePostCaptureAlert();

        if (!mIsImageCaptureIntent) {
            updateThumbnailButton();
            mModePicker.setCurrentMode(ModePicker.MODE_CAMERA);
        }
    }

    private class ZoomChangeListener implements ZoomControl.OnZoomChangedListener {
        // only for immediate zoom
        @Override
        public void onZoomValueChanged(int index) {
            Camera.this.onZoomValueChanged(index);
        }

        // only for smooth zoom
        @Override
        public void onZoomStateChanged(int state) {
            if (mPausing) return;

            Log.v(TAG, "zoom picker state=" + state);
            if (state == ZoomControl.ZOOM_IN) {
                Camera.this.onZoomValueChanged(mZoomMax);
            } else if (state == ZoomControl.ZOOM_OUT) {
                Camera.this.onZoomValueChanged(0);
            } else {
                mTargetZoomValue = -1;
                if (mZoomState == ZOOM_START) {
                    mZoomState = ZOOM_STOPPING;
                    mCameraDevice.stopSmoothZoom();
                }
            }
        }
    }

    private void initializeZoom() {
        // Get the parameter to make sure we have the up-to-date zoom value.
        mParameters = mCameraDevice.getParameters();
        if (!mParameters.isZoomSupported()) return;
        mZoomMax = mParameters.getMaxZoom();
        // Currently we use immediate zoom for fast zooming to get better UX and
        // there is no plan to take advantage of the smooth zoom.
        mZoomControl.setZoomMax(mZoomMax);
        mZoomControl.setZoomIndex(mParameters.getZoom());
        mZoomControl.setSmoothZoomSupported(mSmoothZoomSupported);
        mZoomControl.setOnZoomChangeListener(new ZoomChangeListener());

        mGestureDetector = new GestureDetector(this, new ZoomGestureListener());
        mCameraDevice.setZoomChangeListener(mZoomListener);
    }

    private void onZoomValueChanged(int index) {
        // Not useful to change zoom value when the activity is paused.
        if (mPausing) return;

        if (mSmoothZoomSupported) {
            if (mTargetZoomValue != index && mZoomState != ZOOM_STOPPED) {
                mTargetZoomValue = index;
                if (mZoomState == ZOOM_START) {
                    mZoomState = ZOOM_STOPPING;
                    mCameraDevice.stopSmoothZoom();
                }
            } else if (mZoomState == ZOOM_STOPPED && mZoomValue != index) {
                mTargetZoomValue = index;
                mCameraDevice.startSmoothZoom(index);
                mZoomState = ZOOM_START;
            }
        } else {
            mZoomValue = index;
            setCameraParametersWhenIdle(UPDATE_PARAM_ZOOM);
        }
    }

    @Override
    public void startFaceDetection() {
        if (mFaceDetectionStarted || mCameraState != IDLE) return;
        if ( ( mParameters.getMaxNumDetectedFaces() > 0 ) && ( null != mCameraDevice )) {
            mFaceDetectionStarted = true;
            mFaceView = (FaceView) findViewById(R.id.face_view);
            mFaceView.clear();
            mFaceView.setVisibility(View.VISIBLE);
            mFaceView.setDisplayOrientation(mDisplayOrientation);
            CameraInfo info = CameraHolder.instance().getCameraInfo()[mCameraId];
            mFaceView.setMirror(info.facing == CameraInfo.CAMERA_FACING_FRONT);
            mFaceView.resume();
            mCameraDevice.setFaceDetectionListener(this);
            try {
                mCameraDevice.startFaceDetection();
            } catch ( RuntimeException e ) {
                Log.e(TAG, "Face detection already started. ", e);
                return;
            }
        }
    }

    @Override
    public void stopFaceDetection() {
        if (!mFaceDetectionStarted) return;
        if (mParameters.getMaxNumDetectedFaces() > 0) {
            mFaceDetectionStarted = false;
            mCameraDevice.setFaceDetectionListener(null);
            try {
                mCameraDevice.stopFaceDetection();
            } catch (RuntimeException e) {
                e.printStackTrace();
                Log.e(TAG, "Face detection already stopped!");
            }
            if (mFaceView != null) mFaceView.clear();
        }
    }

    private class PopupGestureListener
            extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            // Check if the popup window is visible.
            View popup = mIndicatorControlContainer.getActiveSettingPopup();
            if (popup == null) return false;


            // Let popup window, indicator control or preview frame handle the
            // event by themselves. Dismiss the popup window if users touch on
            // other areas.
            if (!Util.pointInView(e.getX(), e.getY(), popup)
                    && !Util.pointInView(e.getX(), e.getY(), mIndicatorControlContainer)
                    && !Util.pointInView(e.getX(), e.getY(), mPreviewFrame)) {
                mIndicatorControlContainer.dismissSettingPopup();
                // Let event fall through.
            }
            return false;
        }
    }

    private class ZoomGestureListener extends
            GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            int doubleCLick_X = (int) e.getX();
            int doubleClick_Y = (int) e.getY();

            // Do not trigger double tap zoom if popup window is opened.
            if (collapseCameraControls()) return false;

            SurfaceView mSurfaceView = (SurfaceView) findViewById(R.id.camera_preview);
            int frameOffsetLeft = mPreviewFrameLayout.getLeft();
            int frameOffsetTop = mPreviewFrameLayout.getTop();
            frameOffsetLeft += mSurfaceView.getLeft();
            frameOffsetTop += mSurfaceView.getTop();

            if ( !mTouchFocusEnabled ) {
                // Perform zoom only when preview is started and snapshot is not in
                // progress.
                if (mPausing || !isCameraIdle() || mCameraState == PREVIEW_STOPPED
                             || mZoomState != ZOOM_STOPPED) {
                    return false;
                } else if( ( doubleCLick_X < frameOffsetLeft ) ||
                        ( doubleCLick_X > mSurfaceView.getWidth() ) ) {
                    return false;
                } else if ( ( doubleClick_Y < frameOffsetTop ) ||
                        ( doubleClick_Y > mSurfaceView.getHeight() ) ) {
                    return false;
                }

                if (mZoomValue < mZoomMax) {
                    // Zoom in to the maximum.
                    mZoomValue = mZoomMax;
                } else {
                    mZoomValue = 0;
                }

                setCameraParametersWhenIdle(UPDATE_PARAM_ZOOM);

                mZoomControl.setZoomIndex(mZoomValue);
            }

            return true;
          }
    }


    @Override
    public boolean dispatchTouchEvent(MotionEvent m) {
        // Check if the popup window should be dismissed first.
        if ( (mPopupGestureDetector != null && mPopupGestureDetector.onTouchEvent(m)) ||
             (mGestureDetector != null && mGestureDetector.onTouchEvent(m)) ) {
            return true;
        }

        return super.dispatchTouchEvent(m);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "Received intent action=" + action);
            if (action.equals(Intent.ACTION_MEDIA_MOUNTED)
                    || action.equals(Intent.ACTION_MEDIA_UNMOUNTED)
                    || action.equals(Intent.ACTION_MEDIA_CHECKING)) {
                checkStorage();
            } else if (action.equals(Intent.ACTION_MEDIA_SCANNER_FINISHED)) {
                checkStorage();
                if (!mIsImageCaptureIntent) {
                    updateThumbnailButton();
                }
            }
        }
    };

    private void initOnScreenIndicator() {
        mGpsIndicator = (ImageView) findViewById(R.id.onscreen_gps_indicator);
        mExposureIndicator = (TextView) findViewById(R.id.onscreen_exposure_indicator);
        mFlashIndicator = (ImageView) findViewById(R.id.onscreen_flash_indicator);
        mSceneIndicator = (ImageView) findViewById(R.id.onscreen_scene_indicator);
        mWhiteBalanceIndicator =
                (ImageView) findViewById(R.id.onscreen_white_balance_indicator);
        mFocusIndicator = (ImageView) findViewById(R.id.onscreen_focus_indicator);
    }

    @Override
    public void showGpsOnScreenIndicator(boolean hasSignal) {
        if (mGpsIndicator == null) {
            return;
        }
        if (hasSignal) {
            mGpsIndicator.setImageResource(R.drawable.ic_viewfinder_gps_on);
        } else {
            mGpsIndicator.setImageResource(R.drawable.ic_viewfinder_gps_no_signal);
        }
        mGpsIndicator.setVisibility(View.VISIBLE);
    }

    @Override
    public void hideGpsOnScreenIndicator() {
        if (mGpsIndicator == null) {
            return;
        }
        mGpsIndicator.setVisibility(View.GONE);
    }

    private void updateExposureOnScreenIndicator(int value) {
        if (mExposureIndicator == null) {
            return;
        }
        if (value == 0) {
            mExposureIndicator.setText("");
            mExposureIndicator.setVisibility(View.GONE);
        } else {
            float step = mParameters.getExposureCompensationStep();
            mFormatterArgs[0] = value * step;
            mBuilder.delete(0, mBuilder.length());
            mFormatter.format("%+1.1f", mFormatterArgs);
            String exposure = mFormatter.toString();
            mExposureIndicator.setText(exposure);
            mExposureIndicator.setVisibility(View.VISIBLE);
        }
    }

    private void updateFlashOnScreenIndicator(String value) {
        if (mFlashIndicator == null) {
            return;
        }
        if (Parameters.FLASH_MODE_AUTO.equals(value)) {
            mFlashIndicator.setImageResource(R.drawable.ic_indicators_landscape_flash_auto);
            mFlashIndicator.setVisibility(View.VISIBLE);
        } else if (Parameters.FLASH_MODE_ON.equals(value)) {
            mFlashIndicator.setImageResource(R.drawable.ic_indicators_landscape_flash_on);
            mFlashIndicator.setVisibility(View.VISIBLE);
        } else if (Parameters.FLASH_MODE_OFF.equals(value)) {
            mFlashIndicator.setVisibility(View.GONE);
        }
    }

    private void updateSceneOnScreenIndicator(boolean isVisible) {
        if (mSceneIndicator == null) {
            return;
        }
        mSceneIndicator.setVisibility(isVisible ? View.VISIBLE : View.GONE);
    }

    private void updateWhiteBalanceOnScreenIndicator(String value) {
        if (mWhiteBalanceIndicator == null) {
            return;
        }
        if (Parameters.WHITE_BALANCE_AUTO.equals(value)) {
            mWhiteBalanceIndicator.setVisibility(View.GONE);
        } else {
            if (Parameters.WHITE_BALANCE_FLUORESCENT.equals(value)) {
                mWhiteBalanceIndicator.setImageResource(R.drawable.ic_indicators_fluorescent);
            } else if (Parameters.WHITE_BALANCE_INCANDESCENT.equals(value)) {
                mWhiteBalanceIndicator.setImageResource(R.drawable.ic_indicators_incandescent);
            } else if (Parameters.WHITE_BALANCE_DAYLIGHT.equals(value)) {
                mWhiteBalanceIndicator.setImageResource(R.drawable.ic_indicators_sunlight);
            } else if (Parameters.WHITE_BALANCE_CLOUDY_DAYLIGHT.equals(value)) {
                mWhiteBalanceIndicator.setImageResource(R.drawable.ic_indicators_cloudy);
            }
            mWhiteBalanceIndicator.setVisibility(View.VISIBLE);
        }
    }

    private void updateFocusOnScreenIndicator(String value) {
        if (mFocusIndicator == null) {
            return;
        }
        if (Parameters.FOCUS_MODE_INFINITY.equals(value)) {
            mFocusIndicator.setImageResource(R.drawable.ic_indicators_landscape);
            mFocusIndicator.setVisibility(View.VISIBLE);
        } else if (Parameters.FOCUS_MODE_MACRO.equals(value)) {
            mFocusIndicator.setImageResource(R.drawable.ic_indicators_macro);
            mFocusIndicator.setVisibility(View.VISIBLE);
        } else {
            mFocusIndicator.setVisibility(View.GONE);
        }
    }

    private void updateOnScreenIndicators() {
        boolean isAutoScene = !(Parameters.SCENE_MODE_AUTO.equals(mParameters.getSceneMode()));
        updateSceneOnScreenIndicator(isAutoScene);
        updateExposureOnScreenIndicator(CameraSettings.readExposure(mPreferences));
        updateFlashOnScreenIndicator(mParameters.getFlashMode());
        updateWhiteBalanceOnScreenIndicator(mParameters.getWhiteBalance());
        updateFocusOnScreenIndicator(mParameters.getFocusMode());
    }
    private final class ShutterCallback
            implements android.hardware.Camera.ShutterCallback {
        public void onShutter() {
            mShutterCallbackTime = System.currentTimeMillis();
            mShutterLag = mShutterCallbackTime - mCaptureStartTime;
            Log.v(TAG, "mShutterLag = " + mShutterLag + "ms");
            mFocusManager.onShutter();
        }
    }

    private final class PostViewPictureCallback implements PictureCallback {
        public void onPictureTaken(
                byte [] data, android.hardware.Camera camera) {
            mPostViewPictureCallbackTime = System.currentTimeMillis();
            Log.v(TAG, "mShutterToPostViewCallbackTime = "
                    + (mPostViewPictureCallbackTime - mShutterCallbackTime)
                    + "ms");
        }
    }

    private final class RawPictureCallback implements PictureCallback {
        public void onPictureTaken(
                byte [] rawData, android.hardware.Camera camera) {
            mRawPictureCallbackTime = System.currentTimeMillis();
            Log.v(TAG, "mShutterToRawCallbackTime = "
                    + (mRawPictureCallbackTime - mShutterCallbackTime) + "ms");
        }
    }

    private final class JpegPictureCallback implements PictureCallback {
        Location mLocation;

        public JpegPictureCallback(Location loc) {
            mLocation = loc;
        }

        public void onPictureTaken(
                final byte [] jpegData, final android.hardware.Camera camera) {
            if (mPausing) {
                if (mBurstImages > 0) {
                    resetBurst();
                    mBurstImages = 0;
                    mHandler.sendEmptyMessageDelayed(RELEASE_CAMERA,
                                                     CAMERA_RELEASE_DELAY);
                }
                return;
            }

            FocusManager.TempBracketingStates tempState = mFocusManager.getTempBracketingState();
            mJpegPictureCallbackTime = System.currentTimeMillis();
            // If postview callback has arrived, the captured image is displayed
            // in postview callback. If not, the captured image is displayed in
            // raw picture callback.
            if (mPostViewPictureCallbackTime != 0) {
                mShutterToPictureDisplayedTime =
                        mPostViewPictureCallbackTime - mShutterCallbackTime;
                mPictureDisplayedToJpegCallbackTime =
                        mJpegPictureCallbackTime - mPostViewPictureCallbackTime;
            } else {
                mShutterToPictureDisplayedTime =
                        mRawPictureCallbackTime - mShutterCallbackTime;
                mPictureDisplayedToJpegCallbackTime =
                        mJpegPictureCallbackTime - mRawPictureCallbackTime;
            }
            Log.v(TAG, "mPictureDisplayedToJpegCallbackTime = "
                    + mPictureDisplayedToJpegCallbackTime + "ms");

            if (!mIsImageCaptureIntent) {
                enableCameraControls(true);

                if (( tempState != FocusManager.TempBracketingStates.RUNNING ) &&
                      !mCaptureMode.equals(mExposureBracketing) &&
                      !mBurstRunning == true) {
                // We want to show the taken picture for a while, so we wait
                // for at least 0.5 second before restarting the preview.
                    long delay = 500 - mPictureDisplayedToJpegCallbackTime;
                    if (delay < 0) {
                        startPreview(true);
                        startFaceDetection();
                    } else {
                        mHandler.sendEmptyMessageDelayed(RESTART_PREVIEW, delay);
                    }
                }

            }

            if (!mIsImageCaptureIntent) {
                Size s = mParameters.getPictureSize();
                mImageSaver.addImage(jpegData, mLocation, s.width, s.height);
            } else {
                mJpegImageData = jpegData;
                if (!mQuickCapture) {
                    showPostCaptureAlert();
                } else {
                    doAttach();
                }
            }

            // Check this in advance of each shot so we don't add to shutter
            // latency. It's true that someone else could write to the SD card in
            // the mean time and fill it, but that could have happened between the
            // shutter press and saving the JPEG too.
            checkStorage();

            if (!mHandler.hasMessages(RESTART_PREVIEW)) {
                long now = System.currentTimeMillis();
                mJpegCallbackFinishTime = now - mJpegPictureCallbackTime;
                Log.v(TAG, "mJpegCallbackFinishTime = "
                        + mJpegCallbackFinishTime + "ms");
                mJpegPictureCallbackTime = 0;
            }

            if (mCaptureMode.equals(mExposureBracketing) ) {
                mBurstImages --;
                if (mBurstImages == 0 ) {
                    mHandler.sendEmptyMessageDelayed(RESTART_PREVIEW, 0);
                }
            }

          //reset burst in case of exposure bracketing
            if (mCaptureMode.equals(mExposureBracketing) && mBurstImages == 0) {
                mBurstImages = EXPOSURE_BRACKETING_COUNT;
                mParameters.set(PARM_BURST, mBurstImages);
                mCameraDevice.setParameters(mParameters);
            }

            if ( tempState == FocusManager.TempBracketingStates.RUNNING ) {
                mBurstImages --;
                if (mBurstImages == 0 ) {
                    mHandler.sendEmptyMessageDelayed(RESTART_PREVIEW, 0);
                    mTempBracketingEnabled = true;
                    stopTemporalBracketing();
                }
            }

            if (mBurstRunning) {
                mBurstImages --;
                if (mBurstImages == 0) {
                    resetBurst();
                    mBurstRunning = false;
                    mHandler.sendEmptyMessageDelayed(RESTART_PREVIEW, 0);
                }
            }
        }
    }

    private final class AutoFocusCallback
            implements android.hardware.Camera.AutoFocusCallback {
        public void onAutoFocus(
                boolean focused, android.hardware.Camera camera) {
            if (mPausing) return;

            mAutoFocusTime = System.currentTimeMillis() - mFocusStartTime;
            Log.v(TAG, "mAutoFocusTime = " + mAutoFocusTime + "ms");
            setCameraState(IDLE);

            // If focus completes and the snapshot is not started, enable the
            // controls.
            if (mFocusManager.isFocusCompleted() && (!mTempBracketingEnabled)) {
                enableCameraControls(true);
            } else if ( mTempBracketingEnabled ) {
                startTemporalBracketing();
            }

            mFocusManager.onAutoFocus(focused);
        }
    }

    private final class ZoomListener
            implements android.hardware.Camera.OnZoomChangeListener {
        @Override
        public void onZoomChange(
            int value, boolean stopped, android.hardware.Camera camera) {
            Log.v(TAG, "Zoom changed: value=" + value + ". stopped="+ stopped);
            mZoomValue = value;

            // Update the UI when we get zoom value.
            mZoomControl.setZoomIndex(value);

            // Keep mParameters up to date. We do not getParameter again in
            // takePicture. If we do not do this, wrong zoom value will be set.
            mParameters.setZoom(value);

            if (stopped && mZoomState != ZOOM_STOPPED) {
                if (mTargetZoomValue != -1 && value != mTargetZoomValue) {
                    mCameraDevice.startSmoothZoom(mTargetZoomValue);
                    mZoomState = ZOOM_START;
                } else {
                    mZoomState = ZOOM_STOPPED;
                }
            }
        }
    }

    // Each SaveRequest remembers the data needed to save an image.
    private static class SaveRequest {
        byte[] data;
        Location loc;
        int width, height;
        long dateTaken;
        int previewWidth;
    }

    // We use a queue to store the SaveRequests that have not been completed
    // yet. The main thread puts the request into the queue. The saver thread
    // gets it from the queue, does the work, and removes it from the queue.
    //
    // There are several cases the main thread needs to wait for the saver
    // thread to finish all the work in the queue:
    // (1) When the activity's onPause() is called, we need to finish all the
    // work, so other programs (like Gallery) can see all the images.
    // (2) When we need to show the SharePop, we need to finish all the work
    // too, because we want to show the thumbnail of the last image taken.
    //
    // If the queue becomes too long, adding a new request will block the main
    // thread until the queue length drops below the threshold (QUEUE_LIMIT).
    // If we don't do this, we may face several problems: (1) We may OOM
    // because we are holding all the jpeg data in memory. (2) We may ANR
    // when we need to wait for saver thread finishing all the work (in
    // onPause() or showSharePopup()) because the time to finishing a long queue
    // of work may be too long.
    private class ImageSaver extends Thread {
        private static final int QUEUE_LIMIT = 15;

        private ArrayList<SaveRequest> mQueue;
        private Thumbnail mPendingThumbnail;
        private Object mUpdateThumbnailLock = new Object();
        private boolean mStop;

        // Runs in main thread
        public ImageSaver() {
            mQueue = new ArrayList<SaveRequest>();
            start();
        }

        // Runs in main thread
        public void addImage(final byte[] data, Location loc, int width,
                int height) {
            SaveRequest r = new SaveRequest();
            r.data = data;
            r.loc = (loc == null) ? null : new Location(loc);  // make a copy
            r.width = width;
            r.height = height;
            r.dateTaken = System.currentTimeMillis();
            r.previewWidth = mPreviewFrameLayout.getWidth();
            synchronized (this) {
                while (mQueue.size() >= QUEUE_LIMIT) {
                    try {
                        wait();
                    } catch (InterruptedException ex) {
                        // ignore.
                    }
                }
                mQueue.add(r);
                notifyAll();  // Tell saver thread there is new work to do.
            }
        }

        // Runs in saver thread
        @Override
        public void run() {
            while (true) {
                SaveRequest r;
                synchronized (this) {
                    if (mQueue.isEmpty()) {
                        notifyAll();  // notify main thread in waitDone

                        // Note that we can only stop after we saved all images
                        // in the queue.
                        if (mStop) break;

                        try {
                            wait();
                        } catch (InterruptedException ex) {
                            // ignore.
                        }
                        continue;
                    }
                    r = mQueue.get(0);
                }
                storeImage(r.data, r.loc, r.width, r.height, r.dateTaken,
                        r.previewWidth);
                synchronized(this) {
                    mQueue.remove(0);
                    notifyAll();  // the main thread may wait in addImage
                }
            }
        }

        // Runs in main thread
        public void waitDone() {
            synchronized (this) {
                while (!mQueue.isEmpty()) {
                    try {
                        wait();
                    } catch (InterruptedException ex) {
                        // ignore.
                    }
                }
            }
            updateThumbnail();
        }

        // Runs in main thread
        public void finish() {
            waitDone();
            synchronized (this) {
                mStop = true;
                notifyAll();
            }
            try {
                join();
            } catch (InterruptedException ex) {
                // ignore.
            }
        }

        // Runs in main thread (because we need to update mThumbnailView in the
        // main thread)
        public void updateThumbnail() {
            Thumbnail t;
            synchronized (mUpdateThumbnailLock) {
                mHandler.removeMessages(UPDATE_THUMBNAIL);
                t = mPendingThumbnail;
                mPendingThumbnail = null;
            }

            if (t != null) {
                mThumbnail = t;
                mThumbnailView.setBitmap(mThumbnail.getBitmap());
                if (!mIsImageCaptureIntent && !mThumbnail.fromFile()) {
                    mThumbnail.saveTo(new File(getFilesDir(), Thumbnail.LAST_THUMB_FILENAME));
                }
            }
            // Share popup may still have the reference to the old thumbnail. Clear it.
            mSharePopup = null;
        }

        // Runs in saver thread
        private void storeImage(final byte[] data, Location loc, int width,
                int height, long dateTaken, int previewWidth) {
            String title = Util.createJpegName(dateTaken);

            if(mIsTestExecuting) {
                title = title + "_" + mIMGscriptTitle;
                mIsTestExecuting = false;
            }

            int orientation = Exif.getOrientation(data);
            Uri uri = Storage.addImage(mContentResolver, title, mPictureFormat, dateTaken,
                    loc, orientation, data, width, height);
            if (uri != null) {
                boolean needThumbnail;
                synchronized (this) {
                    // If the number of requests in the queue (include the
                    // current one) is greater than 1, we don't need to generate
                    // thumbnail for this image. Because we'll soon replace it
                    // with the thumbnail for some image later in the queue.
                    needThumbnail = (mQueue.size() <= 1);
                }
                if (needThumbnail) {
                    // Create a thumbnail whose width is equal or bigger than
                    // that of the preview.
                    int ratio = (int) Math.ceil((double) width / previewWidth);
                    int inSampleSize = Integer.highestOneBit(ratio);
                    Thumbnail t = Thumbnail.createThumbnail(
                                data, orientation, inSampleSize, uri);
                    synchronized (mUpdateThumbnailLock) {
                        // We need to update the thumbnail in the main thread,
                        // so send a message to run updateThumbnail().
                        mPendingThumbnail = t;
                        mHandler.sendEmptyMessage(UPDATE_THUMBNAIL);
                    }
                }
                Util.broadcastNewPicture(Camera.this, uri);
            }
        }
    }

    private void setCameraState(int state) {
        mCameraState = state;
        switch (state) {
            case SNAPSHOT_IN_PROGRESS:
            case FOCUSING:
                enableCameraControls(false);
                break;
            case IDLE:
            case PREVIEW_STOPPED:
                enableCameraControls(true);
                break;
        }
    }

    @Override
    public boolean capture() {
        synchronized (mCameraStateLock) {
            // If we are already in the middle of taking a snapshot then ignore.
            if (mCameraState == SNAPSHOT_IN_PROGRESS || mCameraDevice == null) {
                return false;
            }
            mCaptureStartTime = System.currentTimeMillis();
            mPostViewPictureCallbackTime = 0;
            mJpegImageData = null;

            // Set rotation and gps data.
            Util.setRotationParameter(mParameters, mCameraId, mOrientation);
            Location loc = mLocationManager.getCurrentLocation();
            Util.setGpsParameters(mParameters, loc);
            if (canSetParameters()) {
                mCameraDevice.setParameters(mParameters);
            }

            try {
                mCameraDevice.takePicture(mShutterCallback, mRawPictureCallback,
                        mPostViewPictureCallback, new JpegPictureCallback(loc));
            } catch (RuntimeException e ) {
                e.printStackTrace();
                return false;
            }
            mFaceDetectionStarted = false;
            setCameraState(SNAPSHOT_IN_PROGRESS);
            return true;
        }
    }

    @Override
    public void setFocusParameters() {
        setCameraParameters(UPDATE_PARAM_PREFERENCE);
    }

    @Override
    public void setTouchParameters() {
        mParameters = mCameraDevice.getParameters();
        mParameters.setMeteringAreas(mTouchManager.getMeteringAreas());
        mCameraDevice.setParameters(mParameters);
    }

    @Override
    public void playSound(int soundId) {
        mCameraSound.playSound(soundId);
    }

    private boolean saveDataToFile(String filePath, byte[] data) {
        FileOutputStream f = null;
        try {
            f = new FileOutputStream(filePath);
            f.write(data);
        } catch (IOException e) {
            return false;
        } finally {
            Util.closeSilently(f);
        }
        return true;
    }

    private void getPreferredCameraId() {
        mPreferences = new ComboPreferences(this);
        CameraSettings.upgradeGlobalPreferences(mPreferences.getGlobal());
        mCameraId = CameraSettings.readPreferredCameraId(mPreferences);

        // Testing purpose. Launch a specific camera through the intent extras.
        int intentCameraId = Util.getCameraFacingIntentExtras(this);
        if (intentCameraId != -1) {
            mCameraId = intentCameraId;
        }
    }

    Thread mCameraOpenThread = new Thread(new Runnable() {
        public void run() {
            try {
                mCameraDevice = Util.openCamera(Camera.this, mCameraId);
            } catch (CameraHardwareException e) {
                mOpenCameraFail = true;
            } catch (CameraDisabledException e) {
                mCameraDisabled = true;
            }
        }
    });

    Thread mCameraPreviewThread = new Thread(new Runnable() {
        public void run() {
            initializeCapabilities();
            startPreview(true);
        }
    });

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        getPreferredCameraId();
        String[] defaultFocusModes = getResources().getStringArray(
                R.array.pref_camera_focusmode_default_array);
        mFocusManager = new FocusManager(mPreferences, defaultFocusModes);

        /*
         * To reduce startup time, we start the camera open and preview threads.
         * We make sure the preview is started at the end of onCreate.
         */
        mCameraOpenThread.start();

        PreferenceInflater inflater = new PreferenceInflater(this);
        PreferenceGroup group =
                (PreferenceGroup) inflater.inflate(R.xml.camera_preferences);

        ListPreference gbce = group.findPreference(CameraSettings.KEY_GBCE);
        if (gbce != null) {
            mGBCEOff = gbce.findEntryValueByEntry(getString(R.string.pref_camera_gbce_entry_off));
            if (mGBCEOff == null) {
                mGBCEOff = "";
            }
        }

        ListPreference autoConvergencePreference = group.findPreference(CameraSettings.KEY_AUTO_CONVERGENCE);
        if (autoConvergencePreference != null) {
            mTouchConvergence = autoConvergencePreference.findEntryValueByEntry(getString(R.string.pref_camera_autoconvergence_entry_mode_touch));
            if (mTouchConvergence == null) {
                mTouchConvergence = "";
            }
            mManualConvergence = autoConvergencePreference.findEntryValueByEntry(getString(R.string.pref_camera_autoconvergence_entry_mode_manual));
            if (mManualConvergence == null) {
                mManualConvergence = "";
            }
        }

        ListPreference exposure = group.findPreference(CameraSettings.KEY_EXPOSURE_MODE_MENU);
        if (exposure != null) {
            mManualExposure = exposure.findEntryValueByEntry(getString(R.string.pref_camera_exposuremode_entry_manual));
            if (mManualExposure == null) {
                mManualExposure = "";
            }
        }

        ListPreference temp = group.findPreference(CameraSettings.KEY_MODE_MENU);
        if (temp != null) {
            mTemporalBracketing = temp.findEntryValueByEntry(getString(R.string.pref_camera_mode_entry_temporal_bracketing));
            if (mTemporalBracketing == null) {
                mTemporalBracketing = "";
            }

            mExposureBracketing = temp.findEntryValueByEntry(getString(R.string.pref_camera_mode_entry_exp_bracketing));
            if (mExposureBracketing == null) {
                mExposureBracketing = "";
            }

            mHighPerformance = temp.findEntryValueByEntry(getString(R.string.pref_camera_mode_entry_hs));
            if (mHighPerformance == null) {
                mHighPerformance = "";
            }

            mHighQuality = temp.findEntryValueByEntry(getString(R.string.pref_camera_mode_entry_hq));
            if (mHighQuality == null) {
                mHighQuality = "";
            }

            mHighQualityZsl = temp.findEntryValueByEntry(getString(R.string.pref_camera_mode_entry_zsl));
            if (mHighQualityZsl == null) {
                mHighQualityZsl = "";
            }
        }

        getPreferredCameraId();
        mFocusManager = new FocusManager(mPreferences,
                defaultFocusModes);
        mTouchManager = new TouchManager();

        mIsImageCaptureIntent = isImageCaptureIntent();
        setContentView(R.layout.camera);
        if (mIsImageCaptureIntent) {
            mReviewDoneButton = (Rotatable) findViewById(R.id.btn_done);
            mReviewCancelButton = (Rotatable) findViewById(R.id.btn_cancel);
            findViewById(R.id.btn_cancel).setVisibility(View.VISIBLE);
        } else {
            mThumbnailView = (RotateImageView) findViewById(R.id.thumbnail);
            mThumbnailView.enableFilter(false);
            mThumbnailView.setVisibility(View.VISIBLE);
        }

        mRotateDialog = new RotateDialogController(this, R.layout.rotate_dialog);
        mCaptureLayout = getString(R.string.pref_camera_capture_layout_default);

        mPreferences.setLocalId(this, mCameraId);
        CameraSettings.upgradeLocalPreferences(mPreferences.getLocal());

        mNumberOfCameras = CameraHolder.instance().getNumberOfCameras();
        mQuickCapture = getIntent().getBooleanExtra(EXTRA_QUICK_CAPTURE, false);

        // we need to reset exposure for the preview
        resetExposureCompensation();

        Util.enterLightsOutMode(getWindow());

        // don't set mSurfaceHolder here. We have it set ONLY within
        // surfaceChanged / surfaceDestroyed, other parts of the code
        // assume that when it is set, the surface is also set.
        SurfaceView preview = (SurfaceView) findViewById(R.id.camera_preview);
        SurfaceHolder holder = preview.getHolder();
        holder.addCallback(this);

        s3dView = new S3DViewWrapper(holder);

        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        // Make sure camera device is opened.
        try {
            mCameraOpenThread.join();
            mCameraOpenThread = null;
            if (mOpenCameraFail) {
                Util.showErrorAndFinish(this, R.string.cannot_connect_camera);
                return;
            } else if (mCameraDisabled) {
                Util.showErrorAndFinish(this, R.string.camera_disabled);
                return;
            }
        } catch (InterruptedException ex) {
            // ignore
        }
        mCameraPreviewThread.start();

        if (mIsImageCaptureIntent) {
            setupCaptureParams();
        } else {
            mModePicker = (ModePicker) findViewById(R.id.mode_picker);
            mModePicker.setVisibility(View.VISIBLE);
            mModePicker.setOnModeChangeListener(this);
            mModePicker.setCurrentMode(ModePicker.MODE_CAMERA);
        }

        mZoomControl = (ZoomControl) findViewById(R.id.zoom_control);
        mOnScreenIndicators = (Rotatable) findViewById(R.id.on_screen_indicators);
        mLocationManager = new LocationManager(this, this);

        // Wait until the camera settings are retrieved.
        synchronized (mCameraPreviewThread) {
            try {
                mCameraPreviewThread.wait();
            } catch (InterruptedException ex) {
                // ignore
            }
        }

        // Do this after starting preview because it depends on camera
        // parameters.
        initializeIndicatorControl();
        mCameraSound = new CameraSound();

        // Make sure preview is started.
        try {
            mCameraPreviewThread.join();
        } catch (InterruptedException ex) {
            // ignore
        }
        mCameraPreviewThread = null;
    }

    private void overridePictureSettings() {
        if ( mCameraId == CameraHolder.instance().getBackCameraId() ) {
            // We limit the picture size during ZSL on
            // the back facing sensor.
            Editor editor = mPreferences.edit();
            editor.putString(CameraSettings.KEY_PICTURE_SIZE, PARM_ZSL_SIZE);
            editor.apply();

            if (mIndicatorControlContainer != null) {
                mIndicatorControlContainer.reloadPreferences();
                mIndicatorControlContainer.overrideSettings(
                        CameraSettings.KEY_PICTURE_SIZE, PARM_ZSL_SIZE);
            }
        }
    }

    private void overrideCameraSettings(final String flashMode,
            final String whiteBalance, final String focusMode) {
        if (mIndicatorControlContainer != null) {
            mIndicatorControlContainer.overrideSettings(
                    CameraSettings.KEY_FLASH_MODE, flashMode,
                    CameraSettings.KEY_WHITE_BALANCE, whiteBalance,
                    CameraSettings.KEY_FOCUS_MODE, focusMode);
        }
    }

    private void overrideCameraGBCE(final String gbce) {
        if (gbce != null) {
            Editor editor = mPreferences.edit();
            editor.putString(CameraSettings.KEY_GBCE, gbce);
            editor.apply();
            mParameters.set(PARM_GBCE, gbce);
            mCameraDevice.setParameters(mParameters);
            if (mIndicatorControlContainer != null) {
                mIndicatorControlContainer.reloadPreferences();
            }
        }
    }

    private void overrideCameraBurst(final String burst) {
        if (burst != null) {
            Editor editor = mPreferences.edit();
            editor.putString(CameraSettings.KEY_BURST, burst);
            editor.apply();
            mParameters.set(PARM_BURST, burst);
            mCameraDevice.setParameters(mParameters);
            mBurstRunning = false;
            if (mIndicatorControlContainer != null) {
                mIndicatorControlContainer.reloadPreferences();
            }
        }
    }

    private void overrideCameraExposure(final String exposure) {
        if (mIndicatorControlContainer != null) {
            mIndicatorControlContainer.overrideSettings(
                    CameraSettings.KEY_EXPOSURE_COMPENSATION_MENU, exposure);
        }
    }

    private void updateSceneModeUI() {
        // If scene mode is set, we cannot set flash mode, white balance, and
        // focus mode, instead, we read it from driver
        if (!Parameters.SCENE_MODE_AUTO.equals(mSceneMode)) {
            overrideCameraSettings(mParameters.getFlashMode(),
                    mParameters.getWhiteBalance(), mParameters.getFocusMode());
            overrideCameraExposure(getString(R.string.pref_exposure_default));
        } else {
            overrideCameraSettings(null, null, null);
            overrideCameraExposure(null);
        }

        // GBCE/GLBCE is only available when in HQ mode
        if ( !mCaptureMode.equals(mHighQuality) ) {
            overrideCameraGBCE(mGBCEOff);
        } else {
            overrideCameraGBCE(null);
        }

        if ( !mCaptureMode.equals(mHighPerformance) ) {
            overrideCameraBurst(getString(R.string.pref_camera_burst_default));
        } else {
            overrideCameraBurst(null);
        }
    }

    private void loadCameraPreferences() {
        CameraSettings settings = new CameraSettings(this, mInitialParams,
                mCameraId, CameraHolder.instance().getCameraInfo());
        mPreferenceGroup = settings.getPreferenceGroup(R.xml.camera_preferences);
    }

    private void initializeIndicatorControl() {
        // setting the indicator buttons.
        mIndicatorControlContainer =
                (IndicatorControlContainer) findViewById(R.id.indicator_control);
        if (mIndicatorControlContainer == null) return;
        loadCameraPreferences();
        final String[] SETTING_KEYS = {
                CameraSettings.KEY_FLASH_MODE,
                CameraSettings.KEY_WHITE_BALANCE,
                CameraSettings.KEY_EXPOSURE_COMPENSATION_MENU,
                CameraSettings.KEY_SCENE_MODE};
        final String[] OTHER_SETTING_KEYS = {
                CameraSettings.KEY_CAPTURE_LAYOUT,
                CameraSettings.KEY_PREVIEW_LAYOUT,
                CameraSettings.KEY_S3D_MENU,
                CameraSettings.KEY_PICTURE_FORMAT_MENU,
                CameraSettings.KEY_AUTO_CONVERGENCE,
                CameraSettings.KEY_MECHANICAL_MISALIGNMENT_CORRECTION_MENU,
                CameraSettings.KEY_RECORD_LOCATION,
                CameraSettings.KEY_FOCUS_MODE,
                CameraSettings.KEY_MODE_MENU,
                CameraSettings.KEY_GBCE,
                CameraSettings.KEY_CONTRAST,
                CameraSettings.KEY_BRIGHTNESS,
                CameraSettings.KEY_SHARPNESS,
                CameraSettings.KEY_SATURATION,
                CameraSettings.KEY_BURST,
                CameraSettings.KEY_ISO,
                CameraSettings.KEY_EXPOSURE_MODE_MENU,
                CameraSettings.KEY_PREVIEW_FRAMERATE,
                CameraSettings.KEY_BRACKET_RANGE,
                CameraSettings.KEY_COLOR_EFFECT,
                CameraSettings.KEY_JPEG_QUALITY,
                CameraSettings.KEY_ANTIBANDING,
                CameraSettings.KEY_PREVIEW_SIZE,
                CameraSettings.KEY_PICTURE_SIZE};

        CameraPicker.setImageResourceId(R.drawable.ic_switch_photo_facing_holo_light);
        mIndicatorControlContainer.initialize(this, mPreferenceGroup,
                mParameters.isZoomSupported(),
                SETTING_KEYS, OTHER_SETTING_KEYS);
        updateSceneModeUI();
        mIndicatorControlContainer.setListener(this);
    }

    private boolean collapseCameraControls() {
        if ((mIndicatorControlContainer != null)
                && mIndicatorControlContainer.dismissSettingPopup()) {
            return true;
        }
        return false;
    }

    private void enableCameraControls(boolean enable) {
        if (mIndicatorControlContainer != null) {
            mIndicatorControlContainer.setEnabled(enable);
        }
        if (mModePicker != null) mModePicker.setEnabled(enable);
        if (mZoomControl != null) mZoomControl.setEnabled(enable);
        if (mThumbnailView != null) mThumbnailView.setEnabled(enable);
    }

    private class MyOrientationEventListener
            extends OrientationEventListener {
        public MyOrientationEventListener(Context context) {
            super(context);
        }

        @Override
        public void onOrientationChanged(int orientation) {
            // We keep the last known orientation. So if the user first orient
            // the camera then point the camera to floor or sky, we still have
            // the correct orientation.
            if (orientation == ORIENTATION_UNKNOWN) return;
            mOrientation = Util.roundOrientation(orientation, mOrientation);
            // When the screen is unlocked, display rotation may change. Always
            // calculate the up-to-date orientationCompensation.
            int orientationCompensation = mOrientation
                    + Util.getDisplayRotation(Camera.this);
            if (mOrientationCompensation != orientationCompensation) {
                mOrientationCompensation = orientationCompensation;
                setOrientationIndicator(mOrientationCompensation);
            }

            // Show the toast after getting the first orientation changed.
            if (mHandler.hasMessages(SHOW_TAP_TO_FOCUS_TOAST)) {
                mHandler.removeMessages(SHOW_TAP_TO_FOCUS_TOAST);
                showTapToFocusToast();
            }
        }
    }

    private void setOrientationIndicator(int orientation) {
        Rotatable[] indicators = {mThumbnailView, mModePicker, mSharePopup,
                mIndicatorControlContainer, mZoomControl, mFocusAreaIndicator, mFaceView,
                mReviewCancelButton, mReviewDoneButton, mRotateDialog, mOnScreenIndicators};
        for (Rotatable indicator : indicators) {
            if (indicator != null) indicator.setOrientation(orientation);
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mMediaProviderClient != null) {
            mMediaProviderClient.release();
            mMediaProviderClient = null;
        }
    }

    private void checkStorage() {
        mPicturesRemaining = Storage.getAvailableSpace();
        if (mPicturesRemaining > Storage.LOW_STORAGE_THRESHOLD) {
            mPicturesRemaining = (mPicturesRemaining - Storage.LOW_STORAGE_THRESHOLD)
                    / Storage.PICTURE_SIZE;
        } else if (mPicturesRemaining > 0) {
            mPicturesRemaining = 0;
        }

        updateStorageHint();
    }

    @OnClickAttr
    public void onThumbnailClicked(View v) {
        if (isCameraIdle() && mThumbnail != null) {
            showSharePopup();
        }
    }

    @OnClickAttr
    public void onReviewRetakeClicked(View v) {
        hidePostCaptureAlert();
        startPreview(true);
        startFaceDetection();
    }

    @OnClickAttr
    public void onReviewDoneClicked(View v) {
        setResultEx(RESULT_OK);
        finish();
    }

    @OnClickAttr
    public void onReviewCancelClicked(View v) {
        doCancel();
    }

    private void doAttach() {
        if (mPausing) {
            return;
        }

        byte[] data = mJpegImageData;

        if (mCropValue == null) {
            // First handle the no crop case -- just return the value.  If the
            // caller specifies a "save uri" then write the data to it's
            // stream. Otherwise, pass back a scaled down version of the bitmap
            // directly in the extras.
            if (mSaveUri != null) {
                OutputStream outputStream = null;
                try {
                    outputStream = mContentResolver.openOutputStream(mSaveUri);
                    outputStream.write(data);
                    outputStream.close();

                    setResultEx(RESULT_OK);
                } catch (IOException ex) {
                    // ignore exception
                } finally {
                    Util.closeSilently(outputStream);
                }
            } else {
                int orientation = Exif.getOrientation(data);
                Bitmap bitmap = Util.makeBitmap(data, 50 * 1024);
                bitmap = Util.rotate(bitmap, orientation);
                setResultEx(RESULT_OK,
                        new Intent("inline-data").putExtra("data", bitmap));
            }
        } else {
            // Save the image to a temp file and invoke the cropper
            Uri tempUri = null;
            FileOutputStream tempStream = null;
            try {
                File path = getFileStreamPath(sTempCropFilename);
                path.delete();
                tempStream = openFileOutput(sTempCropFilename, 0);
                tempStream.write(data);
                tempStream.close();
                tempUri = Uri.fromFile(path);
            } catch (FileNotFoundException ex) {
                setResultEx(Activity.RESULT_CANCELED);
                finish();
                return;
            } catch (IOException ex) {
                setResultEx(Activity.RESULT_CANCELED);
                finish();
                return;
            } finally {
                Util.closeSilently(tempStream);
            }

            Bundle newExtras = new Bundle();
            if (mCropValue.equals("circle")) {
                newExtras.putString("circleCrop", "true");
            }
            if (mSaveUri != null) {
                newExtras.putParcelable(MediaStore.EXTRA_OUTPUT, mSaveUri);
            } else {
                newExtras.putBoolean("return-data", true);
            }

            Intent cropIntent = new Intent("com.ti.omap4.android.camera.action.CROP");

            cropIntent.setData(tempUri);
            cropIntent.putExtras(newExtras);

            startActivityForResult(cropIntent, CROP_MSG);
        }
    }

    private void doCancel() {
        setResultEx(RESULT_CANCELED, new Intent());
        finish();
    }

    @Override
    public void onShutterButtonFocus(boolean pressed) {
        if (mPausing || collapseCameraControls() || mCameraState == SNAPSHOT_IN_PROGRESS) return;

        // Do not do focus if there is not enough storage.
        if (pressed && !canTakePicture()) return;

        if (pressed) {
            mFocusManager.onShutterDown();
        } else {
            mFocusManager.onShutterUp();
        }
    }

    @Override
    public void onShutterButtonClick() {
        if (mPausing || collapseCameraControls()) return;

        // Do not take the picture if there is not enough storage.
        if (mPicturesRemaining <= 0) {
            Log.i(TAG, "Not enough space or storage not ready. remaining=" + mPicturesRemaining);
            return;
        }

        Log.v(TAG, "onShutterButtonClick: mCameraState=" + mCameraState);

        // If the user wants to do a snapshot while the previous one is still
        // in progress, remember the fact and do it after we finish the previous
        // one and re-start the preview. Snapshot in progress also includes the
        // state that autofocus is focusing and a picture will be taken when
        // focus callback arrives.
        if (mFocusManager.isFocusingSnapOnFinish() || mCameraState == SNAPSHOT_IN_PROGRESS) {
            mSnapshotOnIdle = true;
            return;
        }

        mSnapshotOnIdle = false;
        mFocusManager.doSnap();
    }

    @Override
    public void onShutterButtonLongPressed() {
        if (mPausing || mCameraState == SNAPSHOT_IN_PROGRESS
                || mCameraDevice == null || mPicturesRemaining <= 0) return;

        Log.v(TAG, "onShutterButtonLongPressed");
        mFocusManager.shutterLongPressed();
    }

    private OnScreenHint mStorageHint;

    private void updateStorageHint() {
        String noStorageText = null;

        if (mPicturesRemaining == Storage.UNAVAILABLE) {
            noStorageText = getString(R.string.no_storage);
        } else if (mPicturesRemaining == Storage.PREPARING) {
            noStorageText = getString(R.string.preparing_sd);
        } else if (mPicturesRemaining == Storage.UNKNOWN_SIZE) {
            noStorageText = getString(R.string.access_sd_fail);
        } else if (mPicturesRemaining < 1L) {
            noStorageText = getString(R.string.not_enough_space);
        }

        if (noStorageText != null) {
            if (mStorageHint == null) {
                mStorageHint = OnScreenHint.makeText(this, noStorageText);
            } else {
                mStorageHint.setText(noStorageText);
            }
            mStorageHint.show();
        } else if (mStorageHint != null) {
            mStorageHint.cancel();
            mStorageHint = null;
        }
    }

    private void installIntentFilter() {
        // install an intent filter to receive SD card related events.
        IntentFilter intentFilter =
                new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
        intentFilter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        intentFilter.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        intentFilter.addAction(Intent.ACTION_MEDIA_CHECKING);
        intentFilter.addDataScheme("file");
        registerReceiver(mReceiver, intentFilter);
        mDidRegister = true;
    }

    private void initDefaults() {
        if ( null == mAutoConvergence || mPausing ) {
            mAutoConvergence = getString(R.string.pref_camera_autoconvergence_default);
        }

        if ( null == mMechanicalMisalignmentCorrection || mPausing ) {
            mMechanicalMisalignmentCorrection = getString(R.string.pref_camera_mechanical_misalignment_correction_default);
        }

        if ( null == mContrast || mPausing ) {
            mContrast = getString(R.string.pref_camera_contrast_default);
        }

        if ( null == mBrightness || mPausing ) {
            mBrightness = getString(R.string.pref_camera_brightness_default);
        }

        if ( null == mColorEffect || mPausing ) {
            mColorEffect = getString(R.string.pref_camera_coloreffect_default);
        }

        if ( null == mAntibanding || mPausing ) {
            mAntibanding = getString(R.string.pref_camera_antibanding_default);
        }

        if ( null == mISO || mPausing ) {
            mISO = getString(R.string.pref_camera_iso_default);
        }

        if ( null == mExposureMode || mPausing ) {
            mExposureMode = getString(R.string.pref_camera_exposuremode_default);
        }

        if ( null == mSaturation || mPausing ) {
            mSaturation = getString(R.string.pref_camera_saturation_default);
        }

        if ( null == mSharpness || mPausing ) {
            mSharpness = getString(R.string.pref_camera_sharpness_default);
        }

        if ( null == mPictureFormat ) {
            mPictureFormat = getString(R.string.pref_camera_picture_format_default);
        }

        if (mParameters.isZoomSupported()) {
            mZoomValue = 0;
            mZoomControl.setZoomIndex(mZoomValue);
            mZoomControl.startZoomControl();
        }
        // Preview layout and size
        if (mPausing) {
            mPreviewLayout = null;
            mCaptureLayout = getString(R.string.pref_camera_capture_layout_default);
        }
    }

    @Override
    protected void doOnResume() {
        if (mOpenCameraFail || mCameraDisabled) return;

        mHandler.removeMessages(RELEASE_CAMERA);

        initDefaults();

        mPausing = false;

        mJpegPictureCallbackTime = 0;
        mZoomValue = 0;

        // Start the preview if it is not started.
        if (mCameraState == PREVIEW_STOPPED) {
            try {
                if ( null == mCameraDevice ) {
                    mCameraDevice = Util.openCamera(this, mCameraId);
                }
                initializeCapabilities();
                resetExposureCompensation();
                startPreview(true);
                startFaceDetection();
            } catch (CameraHardwareException e) {
                Util.showErrorAndFinish(this, R.string.cannot_connect_camera);
                return;
            } catch (CameraDisabledException e) {
                Util.showErrorAndFinish(this, R.string.camera_disabled);
                return;
            }
        }

        if (mSurfaceHolder != null) {
            // If first time initialization is not finished, put it in the
            // message queue.
            if (!mFirstTimeInitialized) {
                mHandler.sendEmptyMessage(FIRST_TIME_INIT);
            } else {
                initializeSecondTime();
            }
        }
        keepScreenOnAwhile();

        if (mCameraState == IDLE) {
            mOnResumeTime = SystemClock.uptimeMillis();
            mHandler.sendEmptyMessageDelayed(CHECK_DISPLAY_ROTATION, 100);
        }
        // Dismiss open menu if exists.
        PopupManager.getInstance(this).notifyShowPopup(null);
    }

    @Override
    protected void onPause() {
        mPausing = true;

        // Delay Camera release if
        // burst is still running
        if ( !mBurstRunning ) {
            stopPreview();
            closeCamera();
        } else {
            try {
                mCameraDevice.startPreview();
            } catch (Throwable ex) {
                closeCamera();
                throw new RuntimeException("startPreview failed", ex);
            }

            setCameraState(IDLE);
            mBurstRunning = false;
        }

        if (mCameraSound != null) mCameraSound.release();

        resetScreenOn();

        // Clear UI.
        collapseCameraControls();
        if (mSharePopup != null) mSharePopup.dismiss();
        if (mFaceView != null) mFaceView.clear();

        if (mFirstTimeInitialized) {
            mOrientationListener.disable();
            if (mImageSaver != null) {
                mImageSaver.finish();
                mImageSaver = null;
            }
        }

	    if (mTestIntent != null) {
            unregisterReceiver(mTestReceiver);
            mTestIntent = null;
        }

        if (mDidRegister) {
            unregisterReceiver(mReceiver);
            mDidRegister = false;
        }
        if (mLocationManager != null) mLocationManager.recordLocation(false);
        updateExposureOnScreenIndicator(0);

        if (mStorageHint != null) {
            mStorageHint.cancel();
            mStorageHint = null;
        }

        // If we are in an image capture intent and has taken
        // a picture, we just clear it in onPause.
        mJpegImageData = null;

        // Remove the messages in the event queue.
        mHandler.removeMessages(FIRST_TIME_INIT);
        mHandler.removeMessages(CHECK_DISPLAY_ROTATION);
        mFocusManager.removeMessages();

        super.onPause();
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case CROP_MSG: {
                Intent intent = new Intent();
                if (data != null) {
                    Bundle extras = data.getExtras();
                    if (extras != null) {
                        intent.putExtras(extras);
                    }
                }
                setResultEx(resultCode, intent);
                finish();

                File path = getFileStreamPath(sTempCropFilename);
                path.delete();

                break;
            }
        }
    }

    private boolean canTakePicture() {
        return isCameraIdle() && (mPicturesRemaining > 0);
    }

    @Override
    public boolean autoFocus() {
        synchronized (mCameraStateLock) {
            if ( mCameraState == IDLE ) {
                mFocusStartTime = System.currentTimeMillis();
                try {
                    mCameraDevice.autoFocus(mAutoFocusCallback);
                } catch ( RuntimeException e ) {
                    e.printStackTrace();
                    return false;
                }
                setCameraState(FOCUSING);
                return true;
            } else {
                return false;
            }
        }
    }

    @Override
    public void cancelAutoFocus() {
        mCameraDevice.cancelAutoFocus();
        setCameraState(IDLE);
        setCameraParameters(UPDATE_PARAM_PREFERENCE);
    }

    // Preview area is touched. Handle touch focus and touch convergence
    @Override
    public boolean onTouch(View v, MotionEvent e) {
        if (mPausing || mCameraDevice == null || !mFirstTimeInitialized
                || mCameraState == SNAPSHOT_IN_PROGRESS) {
            return false;
        }

        // Do not trigger touch focus if popup window is opened.
        if (collapseCameraControls()) return false;

        String focusMode = mFocusManager.getFocusMode();
        boolean cafActive = false;
        if ( Parameters.FOCUS_MODE_CONTINUOUS_PICTURE.equals(focusMode) ||
                ( ( Parameters.FOCUS_MODE_AUTO.equals(focusMode) ) &&
                  ( null != mFocusManager.getFocusAreas() ))) {
            cafActive = true;
        }

        // Check if metering area is supported and touch convergence is selected
        if (mMeteringAreaSupported && !cafActive &&
                mAutoConvergence.equals(mTouchConvergence)) {
            if (mS3dViewEnabled || is2DMode()) {
                return mTouchManager.onTouch(e);
            } else {
                return mTouchManager.onTouch(e, mPreviewLayout);
            }
        }

        // Check if metering area or focus area is supported.
        if (!mFocusAreaSupported && !mMeteringAreaSupported) return false;

        // Do touch AF only in continuous AF mode.
        if ( !cafActive ) {
            return false;
        }

        if (mS3dViewEnabled || is2DMode()) {
            return mFocusManager.onTouch(e);
        } else {
            return mFocusManager.onTouch(e, mPreviewLayout);
        }
    }

    @Override
    public void onBackPressed() {
        if (!isCameraIdle()) {
            // ignore backs while we're taking a picture
            return;
        } else if (!collapseCameraControls()) {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_F:
            case KeyEvent.KEYCODE_FOCUS:
                if (mFirstTimeInitialized && event.getRepeatCount() == 0) {
                    onShutterButtonFocus(true);
                }
                return true;
            case KeyEvent.KEYCODE_CAMERA:
                if (mFirstTimeInitialized && event.getRepeatCount() == 0) {
                    onShutterButtonClick();
                }
                return true;
            case KeyEvent.KEYCODE_P:
                if (mFirstTimeInitialized && event.getRepeatCount() == 0) {
                    onShutterButtonClick();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
                // If we get a dpad center event without any focused view, move
                // the focus to the shutter button and press it.
                if (mFirstTimeInitialized && event.getRepeatCount() == 0) {
                    // Start auto-focus immediately to reduce shutter lag. After
                    // the shutter button gets the focus, onShutterButtonFocus()
                    // will be called again but it is fine.
                    if (collapseCameraControls()) return true;
                    onShutterButtonFocus(true);
                    if (mShutterButton.isInTouchMode()) {
                        mShutterButton.requestFocusFromTouch();
                    } else {
                        mShutterButton.requestFocus();
                    }
                    mShutterButton.setPressed(true);
                }
                return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_FOCUS:
                if (mFirstTimeInitialized) {
                    onShutterButtonFocus(false);
                }
                return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        // Make sure we have a surface in the holder before proceeding.
        if (holder.getSurface() == null) {
            Log.d(TAG, "holder.getSurface() == null");
            return;
        }

        Log.v(TAG, "surfaceChanged. w=" + w + ". h=" + h);

        // We need to save the holder for later use, even when the mCameraDevice
        // is null. This could happen if onResume() is invoked after this
        // function.
        mSurfaceHolder = holder;

        // The mCameraDevice will be null if it fails to connect to the camera
        // hardware. In this case we will show a dialog and then finish the
        // activity, so it's OK to ignore it.
        if (mCameraDevice == null) return;

        // Sometimes surfaceChanged is called after onPause or before onResume.
        // Ignore it.
        if (mPausing || isFinishing()) return;

        setSurfaceLayout();

        // Set preview display if the surface is being created. Preview was
        // already started. Also restart the preview if display rotation has
        // changed. Sometimes this happens when the device is held in portrait
        // and camera app is opened. Rotation animation takes some time and
        // display rotation in onCreate may not be what we want.
        if (mCameraState == PREVIEW_STOPPED) {
            startPreview(true);
            startFaceDetection();
        } else {
            if (Util.getDisplayRotation(this) != mDisplayRotation) {
                setDisplayOrientation();
            }
            if (holder.isCreating()) {
                // Set preview display if the surface is being created and preview
                // was already started. That means preview display was set to null
                // and we need to set it now.
                setPreviewDisplay(holder);
            }
        }

        // If first time initialization is not finished, send a message to do
        // it later. We want to finish surfaceChanged as soon as possible to let
        // user see preview first.
        if (!mFirstTimeInitialized) {
            mHandler.sendEmptyMessage(FIRST_TIME_INIT);
        } else {
            initializeSecondTime();
        }

        SurfaceView preview = (SurfaceView) findViewById(R.id.camera_preview);
        CameraInfo info = CameraHolder.instance().getCameraInfo()[mCameraId];
        boolean mirror = (info.facing == CameraInfo.CAMERA_FACING_FRONT);
        int displayRotation = Util.getDisplayRotation(this);
        int displayOrientation = Util.getDisplayOrientation(displayRotation, mCameraId);

        mTouchManager.initialize(preview.getHeight() / 3, preview.getHeight() / 3,
               preview, this, mirror, displayOrientation);

    }

    public void surfaceCreated(SurfaceHolder holder) {
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        stopPreview();
        mSurfaceHolder = null;
    }

    private void setSurfaceLayout() {
        if (mPreviewLayout == null || s3dView == null) {
            return;
        }

        if (!mS3dViewEnabled) {
            s3dView.setMonoLayout();
            return;
        }
        if (mPreviewLayout.equals(CameraSettings.TB_FULL_S3D_LAYOUT) ||
            mPreviewLayout.equals(CameraSettings.TB_SUB_S3D_LAYOUT)) {
            s3dView.setTopBottomLayout();
        } else if (mPreviewLayout.equals(CameraSettings.SS_FULL_S3D_LAYOUT) ||
                   mPreviewLayout.equals(CameraSettings.SS_SUB_S3D_LAYOUT)) {
            s3dView.setSideBySideLayout();
        } else {
            s3dView.setMonoLayout();
        }
    }

    private void closeCamera() {
        if (mCameraDevice != null) {
            CameraHolder.instance().release();
            mFaceDetectionStarted = false;
            mCameraDevice.setZoomChangeListener(null);
            mCameraDevice.setFaceDetectionListener(null);
            mCameraDevice.setErrorCallback(null);
            mCameraDevice = null;
            setCameraState(PREVIEW_STOPPED);
            mFocusManager.onCameraReleased();
        }
    }

    private void setPreviewDisplay(SurfaceHolder holder) {
        try {
            mCameraDevice.setPreviewDisplay(holder);
        } catch (Throwable ex) {
            closeCamera();
            throw new RuntimeException("setPreviewDisplay failed", ex);
        }
        if (mTestIntent == null) {
            mTestIntent = new IntentFilter("cameraTest");
            registerReceiver(mTestReceiver, mTestIntent);
        }else {
            unregisterReceiver(mTestReceiver);
            registerReceiver(mTestReceiver, mTestIntent);
        }
    }

    private void setDisplayOrientation() {
        mDisplayRotation = Util.getDisplayRotation(this);
        mDisplayOrientation = Util.getDisplayOrientation(mDisplayRotation, mCameraId);
        mCameraDevice.setDisplayOrientation(mDisplayOrientation);
        if (mFaceView != null) {
            mFaceView.setDisplayOrientation(mDisplayOrientation);
        }
    }

    private void startPreview(boolean updateAll) {
        if (mPausing || isFinishing()) return;

        mFocusManager.resetTouchFocus();

        mCameraDevice.setErrorCallback(mErrorCallback);

        // If we're previewing already, stop the preview first (this will blank
        // the screen).
        if (mCameraState != PREVIEW_STOPPED) stopPreview();

        setPreviewDisplay(mSurfaceHolder);
        setDisplayOrientation();

        if (!mSnapshotOnIdle) {
            // If the focus mode is continuous autofocus, call cancelAutoFocus to
            // resume it because it may have been paused by autoFocus call.
            if (Parameters.FOCUS_MODE_CONTINUOUS_PICTURE.equals(mFocusManager.getFocusMode())) {
                mCameraDevice.cancelAutoFocus();
            }
            mFocusManager.setAeAwbLock(false); // Unlock AE and AWB.
        }

        if ( updateAll ) {
            Log.v(TAG, "Updating all parameters!");
            setCameraParameters(UPDATE_PARAM_INITIALIZE | UPDATE_PARAM_ZOOM | UPDATE_PARAM_PREFERENCE);
        } else {
            setCameraParameters(UPDATE_PARAM_MODE);
        }

        //setCameraParameters(UPDATE_PARAM_ALL);

        // Inform the mainthread to go on the UI initialization.
        if (mCameraPreviewThread != null) {
            synchronized (mCameraPreviewThread) {
                mCameraPreviewThread.notify();
            }
        }

        try {
            Log.v(TAG, "startPreview");
            mCameraDevice.startPreview();
        } catch (Throwable ex) {
            closeCamera();
            throw new RuntimeException("startPreview failed", ex);
        }

        mZoomState = ZOOM_STOPPED;
        setCameraState(IDLE);
        mFocusManager.onPreviewStarted();

        if (mSnapshotOnIdle) {
            mHandler.post(mDoSnapRunnable);
        }
    }

    private void startTemporalBracketing() {
        mParameters.set(CameraSettings.KEY_TEMPORAL_BRACKETING,
                PARM_TEMPORAL_BRACKETING_ENABLE);
        mCameraDevice.setParameters(mParameters);
    }

    private void stopTemporalBracketing() {
        mFocusManager.setTempBracketingState(FocusManager.TempBracketingStates.OFF);
        mParameters.set(CameraSettings.KEY_TEMPORAL_BRACKETING,
                PARM_TEMPORAL_BRACKETING_DISABLE);
        mCameraDevice.setParameters(mParameters);
        mParameters.remove(CameraSettings.KEY_TEMPORAL_BRACKETING);
    }

    private void stopPreview() {
        if (mCameraDevice != null && mCameraState != PREVIEW_STOPPED) {
            Log.v(TAG, "stopPreview");

            if ( mTempBracketingEnabled ) {
                stopTemporalBracketing();
            }

            mCameraDevice.cancelAutoFocus(); // Reset the focus.
            mCameraDevice.stopPreview();
            mFaceDetectionStarted = false;
        }
        setCameraState(PREVIEW_STOPPED);
        mFocusManager.onPreviewStopped();
    }

    private static boolean isSupported(String value, List<String> supported) {
        return supported == null ? false : supported.indexOf(value) >= 0;
    }

    private void updateCameraParametersInitialize() {
        // Reset preview frame rate to the maximum because it may be lowered by
        // video camera application.
        List<Integer> frameRates = mParameters.getSupportedPreviewFrameRates();
        if (frameRates != null) {
            Integer max = Collections.max(frameRates);
            mParameters.setPreviewFrameRate(max);
        }

        mParameters.setRecordingHint(false);

        // Disable video stabilization. Convenience methods not available in API
        // level <= 14
        String vstabSupported = mParameters.get("video-stabilization-supported");
        if ("true".equals(vstabSupported)) {
            mParameters.set("video-stabilization", "false");
        }
    }

    private void updateCameraParametersZoom() {
        // Set zoom.
        if (mParameters.isZoomSupported()) {
            mParameters.setZoom(mZoomValue);
        }
    }

    private boolean is2DMode(){
        if (mParameters == null) mParameters = mCameraDevice.getParameters();
        String currentPreviewLayout = mParameters.get(CameraSettings.KEY_S3D_PRV_FRAME_LAYOUT);
        // if there isn't selected layout  -> 2d mode
        if (currentPreviewLayout == null ||
                currentPreviewLayout.equals("") ||
                currentPreviewLayout.equals("none")) {
            return true;
        } else {
            return false;
        }
    }

    private String elementExists(String[] firstArr, String[] secondArr){
        for (int i = 0; i < firstArr.length; i++) {
            for (int j = 0; j < secondArr.length; j++) {
                if (firstArr[i].equals(secondArr[j])) {
                    return firstArr[i];
                }
            }
        }
        return null;
    }

    private ListPreference getSupportedListPreference(String supportedKey, String menuKey){
        List<String> supported = new ArrayList<String>();
        ListPreference menu = mPreferenceGroup.findPreference(menuKey);
        if (menu == null) return null;
        if (supportedKey != null) {
            String supp = mParameters.get(supportedKey);
                if (supp !=null && !supp.equals("")) {
                    for (String item : supp.split(",")) {
                        supported.add(item);
                    }
                }
        }
        CameraSettings.filterUnsupportedOptions(mPreferenceGroup, menu, supported);
        return menu;
    }

    private boolean updateCameraParametersPreference() {
        boolean restartNeeded = false;
        boolean previewLayoutUpdated = false;
        boolean captureLayoutUpdated = false;

        if (mAeLockSupported) {
            mParameters.setAutoExposureLock(mFocusManager.getAeAwbLock());
        }

        if (mAwbLockSupported) {
            mParameters.setAutoWhiteBalanceLock(mFocusManager.getAeAwbLock());
        }

        if (mFocusAreaSupported) {
            mParameters.setFocusAreas(mFocusManager.getFocusAreas());
        }

        if (mMeteringAreaSupported) {
            // Use the same area for focus and metering.
            mParameters.setMeteringAreas(mFocusManager.getMeteringAreas());
        }

        // Color Effects
        String colorEffect = mPreferences.getString(
                CameraSettings.KEY_COLOR_EFFECT,
                getString(R.string.pref_camera_coloreffect_default));

        if (isSupported(colorEffect, mParameters.getSupportedColorEffects()) &&
             !colorEffect.equals(mColorEffect) ) {
            mParameters.setColorEffect(colorEffect);
            mColorEffect = colorEffect;
        }

        // Layouts
        String previewLayout = null;
        if (is2DMode()) {
            previewLayout = "none";
        } else {
            previewLayout = mPreferences.getString(
                    CameraSettings.KEY_PREVIEW_LAYOUT,
                    getString(R.string.pref_camera_preview_layout_default));
        }
        if (previewLayout!=null && !previewLayout.equals(mPreviewLayout)) {
            if (!mIsPreviewLayoutInit) {
                mInitialParams.set(CameraSettings.KEY_S3D_PRV_FRAME_LAYOUT, previewLayout);
                CameraSettings settings = new CameraSettings(this, mInitialParams,
                        mCameraId, CameraHolder.instance().getCameraInfo());
                mPreferenceGroup = settings.getPreferenceGroup(R.xml.camera_preferences);
                mIsPreviewLayoutInit = true;
            }
            mParameters.set(CameraSettings.KEY_S3D_PRV_FRAME_LAYOUT, previewLayout);
            mPreviewLayout = previewLayout;
            previewLayoutUpdated = true;
            restartNeeded  = true;
        }

        if (!is2DMode()) {
            String s3dViewEnabled = mPreferences.getString(
                    CameraSettings.KEY_S3D_MENU,
                    getString(R.string.pref_camera_s3d_default));
            mS3dViewEnabled = s3dViewEnabled.equals("on");
        } else {
            mS3dViewEnabled = false;
        }

        String captureLayout = null;
        if (is2DMode()) {
            captureLayout = "none";
        } else {
            captureLayout = mPreferences.getString(
                CameraSettings.KEY_CAPTURE_LAYOUT,
                getString(R.string.pref_camera_capture_layout_default));
        }
        if (captureLayout != null && !mCaptureLayout.equals(captureLayout)) {
            if (!mIsCaptureLayoutInit) {
                mInitialParams.set(CameraSettings.KEY_S3D_CAP_FRAME_LAYOUT, captureLayout);
                CameraSettings settings = new CameraSettings(this, mInitialParams,
                        mCameraId, CameraHolder.instance().getCameraInfo());
                mPreferenceGroup = settings.getPreferenceGroup(R.xml.camera_preferences);
                mIsCaptureLayoutInit = true;
            }
            mParameters.set(CameraSettings.KEY_S3D_CAP_FRAME_LAYOUT, captureLayout);
            mCaptureLayout = captureLayout;
            captureLayoutUpdated = true;
        }

        if ((previewLayoutUpdated || captureLayoutUpdated) && mPreferenceGroup !=null) {
            CameraSettings settings = new CameraSettings(this, mInitialParams,
                    mCameraId, CameraHolder.instance().getCameraInfo());
             mPreferenceGroup = settings.getPreferenceGroup(R.xml.camera_preferences);

             // Update preview size UI with the new sizes
             if (previewLayoutUpdated) {
                 ListPreference tbMenuSizes = null;
                 ListPreference ssMenuSizes = null;
                 ListPreference menu2dSizes = getSupportedListPreference(CameraSettings.KEY_SUPPORTED_PREVIEW_SUBSAMPLED_SIZES,
                         CameraSettings.KEY_PREVIEW_SIZE_2D);
                 if (!is2DMode()) {
                     tbMenuSizes =  getSupportedListPreference(CameraSettings.KEY_SUPPORTED_PREVIEW_TOPBOTTOM_SIZES,CameraSettings.KEY_PREVIEW_SIZES_TB);
                     ssMenuSizes =  getSupportedListPreference(CameraSettings.KEY_SUPPORTED_PREVIEW_SIDEBYSIDE_SIZES,CameraSettings.KEY_PREVIEW_SIZES_SS);
                 }
                 ListPreference newPreviewSizes = null;
                 if (mPreviewLayout.equals(CameraSettings.SS_FULL_S3D_LAYOUT)) {
                     newPreviewSizes = ssMenuSizes;
                 } else if (mPreviewLayout.equals(CameraSettings.TB_FULL_S3D_LAYOUT)) {
                     newPreviewSizes = tbMenuSizes;
                 } else {
                     newPreviewSizes = menu2dSizes;
                 }
                 ArrayList<CharSequence[]> allEntries = new ArrayList<CharSequence[]>();
                 ArrayList<CharSequence[]> allEntryValues = new ArrayList<CharSequence[]>();
                 allEntries.add(menu2dSizes.getEntries());
                 allEntryValues.add(menu2dSizes.getEntryValues());
                 if (!is2DMode()) {
                     if (tbMenuSizes != null) {
                         allEntries.add(tbMenuSizes.getEntries());
                         allEntryValues.add(tbMenuSizes.getEntryValues());
                     }
                     if (ssMenuSizes != null) {
                         allEntries.add(ssMenuSizes.getEntries());
                         allEntryValues.add(ssMenuSizes.getEntryValues());
                     }
                 }

                 if (mIndicatorControlContainer != null ) {
                     mIndicatorControlContainer.replace(CameraSettings.KEY_PREVIEW_SIZE, newPreviewSizes, allEntries, allEntryValues);
                 }
             }

            // Update picture size UI with the new sizes
             if (captureLayoutUpdated) {
                 ListPreference menu2dSizes = getSupportedListPreference(CameraSettings.KEY_SUPPORTED_PICTURE_SUBSAMPLED_SIZES,
                         CameraSettings.KEY_PICTURE_SIZE_2D);
                 ListPreference tbMenuSizes = null;
                 ListPreference ssMenuSizes = null;
                 if (!is2DMode()) {
                     tbMenuSizes =  getSupportedListPreference(CameraSettings.KEY_SUPPORTED_PICTURE_TOPBOTTOM_SIZES,CameraSettings.KEY_PICTURE_SIZES_TB);
                     ssMenuSizes =  getSupportedListPreference(CameraSettings.KEY_SUPPORTED_PICTURE_SIDEBYSIDE_SIZES,CameraSettings.KEY_PICTURE_SIZES_SS);
                 }
                 ListPreference newPictureSizes = null;
                 if (mCaptureLayout.equals(CameraSettings.SS_FULL_S3D_LAYOUT)) {
                     newPictureSizes = ssMenuSizes;
                 } else if (mCaptureLayout.equals(CameraSettings.TB_FULL_S3D_LAYOUT)) {
                     newPictureSizes = tbMenuSizes;
                 } else {
                     newPictureSizes = menu2dSizes;
                 }
                 ArrayList<CharSequence[]> allEntries = new ArrayList<CharSequence[]>();
                 ArrayList<CharSequence[]> allEntryValues = new ArrayList<CharSequence[]>();
                 if (menu2dSizes != null) {
                     allEntries.add(menu2dSizes.getEntries());
                     allEntryValues.add(menu2dSizes.getEntryValues());
                 }
                 if (!is2DMode()) {
                     if (tbMenuSizes != null) {
                         allEntries.add(tbMenuSizes.getEntries());
                         allEntryValues.add(tbMenuSizes.getEntryValues());
                     }
                     if (ssMenuSizes != null) {
                         allEntries.add(ssMenuSizes.getEntries());
                         allEntryValues.add(ssMenuSizes.getEntryValues());
                     }
                 }

                 if (mIndicatorControlContainer != null ) {
                     mIndicatorControlContainer.replace(CameraSettings.KEY_PICTURE_SIZE, newPictureSizes, allEntries, allEntryValues);
                 }
             }
        }

        // Set Default Sensor Orientation
        String sensorOrientation =
           getString(R.string.pref_omap4_camera_sensor_orientation_default);
        mParameters.set(PARM_SENSOR_ORIENTATION, sensorOrientation);

        // ISO
        String iso = mPreferences.getString(
                    CameraSettings.KEY_ISO, getString(R.string.pref_camera_iso_default));

        if ( !iso.equals(mISO) ) {
            mParameters.set(PARM_ISO, iso);
            mISO = iso;
        }

        // Mechanical Misalignment Correction
        String mechanicalMisalignmentCorrection = mPreferences.getString(
                    CameraSettings.KEY_MECHANICAL_MISALIGNMENT_CORRECTION_MENU,
                    getString(R.string.pref_camera_mechanical_misalignment_correction_default));

        if( !mechanicalMisalignmentCorrection.equals(mMechanicalMisalignmentCorrection)) {
            mParameters.set(CameraSettings.KEY_MECHANICAL_MISALIGNMENT_CORRECTION, mechanicalMisalignmentCorrection);
            mMechanicalMisalignmentCorrection = mechanicalMisalignmentCorrection;
        }

        String contrast = mPreferences.getString(
                    CameraSettings.KEY_CONTRAST,
                    getString(R.string.pref_camera_contrast_default));

        if ( !contrast.equals(mContrast) ) {
            mParameters.set(PARM_CONTRAST, Integer.parseInt(contrast) );
            mContrast = contrast;
        }

        String brightness = mPreferences.getString(
                    CameraSettings.KEY_BRIGHTNESS,
                    getString(R.string.pref_camera_brightness_default));

        if ( !brightness.equals(mBrightness) ) {
            mParameters.set(PARM_BRIGHTNESS, Integer.parseInt(brightness) );
            mBrightness = brightness;
        }
        // Set Saturation
        String saturation =  mPreferences.getString(
                    CameraSettings.KEY_SATURATION,
                    getString(R.string.pref_camera_saturation_default));

        if ( !saturation.equals(mSaturation) ) {
            mParameters.set(PARM_SATURATION, Integer.parseInt(saturation) );
            mSaturation = saturation;
        }
        // Set Sharpness
        String sharpness = mPreferences.getString(
                    CameraSettings.KEY_SHARPNESS,
                    getString(R.string.pref_camera_sharpness_default));

        if ( !sharpness.equals(mSharpness) ) {
            mParameters.set(PARM_SHARPNESS, Integer.parseInt(sharpness) );
            mSharpness = sharpness;
        }

        String antibanding = mPreferences.getString(
                    CameraSettings.KEY_ANTIBANDING,
                    getString(R.string.pref_camera_antibanding_default));

        if ( !antibanding.equals(mAntibanding) ) {
            mParameters.setAntibanding(antibanding);
            mAntibanding = antibanding;
        }

        // Set Preview Framerate
        String framerate =  mPreferences.getString(
                CameraSettings.KEY_PREVIEW_FRAMERATE, null);

        if ( framerate == null ) {
            CameraSettings.initialFrameRate(this, mParameters);
        } else if (!framerate.equals(mLastPreviewFramerate) ) {
            mParameters.setPreviewFpsRange(Integer.parseInt(framerate) * 1000,
                    Integer.parseInt(framerate) * 1000);
            mLastPreviewFramerate = framerate;
            restartNeeded = true;
        }

        // Auto Convergence
        String autoConvergence = mPreferences.getString(
                CameraSettings.KEY_AUTO_CONVERGENCE,
                getString(R.string.pref_camera_autoconvergence_default));

        if (!autoConvergence.equals(mAutoConvergence)) {
            mParameters.set(CameraSettings.KEY_AUTOCONVERGENCE_MODE, autoConvergence);
            mAutoConvergence = autoConvergence;
            if (!isConvergenceInit) isConvergenceInit = true;
            else if (autoConvergence.equals(mManualConvergence) && isManualConvergence) {
                int convergenceMin = Integer.parseInt(mParameters.get(CameraSettings.KEY_SUPPORTED_MANUAL_CONVERGENCE_MIN));
                int convergenceMax = Integer.parseInt(mParameters.get(CameraSettings.KEY_SUPPORTED_MANUAL_CONVERGENCE_MAX));
                int convergenceStep = Integer.parseInt(mParameters.get(CameraSettings.KEY_SUPPORTED_MANUAL_CONVERGENCE_STEP));
                int convergenceValue = Integer.parseInt(mParameters.get(CameraSettings.KEY_MANUAL_CONVERGENCE));
                ManualConvergenceSettings manualConvergenceDialog = new ManualConvergenceSettings(this, mHandler,
                        convergenceValue, convergenceMin, convergenceMax, convergenceStep);
                Editor edit = mPreferences.edit();
                edit.putString(CameraSettings.KEY_AUTO_CONVERGENCE, autoConvergence);
                edit.commit();
                manualConvergenceDialog.show();
                isManualConvergence = false;
            } else {
                isManualConvergence = true;
            }
        }

        // Set picture size.
        String pictureSize = mPreferences.getString(
                CameraSettings.KEY_PICTURE_SIZE, null);
        if (pictureSize == null) {
            CameraSettings.initialCameraPictureSize(this, mParameters);
        } else {
            List<String> supported = new ArrayList<String>();
            String supp = null;
            if (mCaptureLayout.equals(CameraSettings.TB_FULL_S3D_LAYOUT)) {
                supp = mParameters.get(CameraSettings.KEY_SUPPORTED_PICTURE_TOPBOTTOM_SIZES);
                if(supp !=null && !supp.equals("")){
                    for(String item : supp.split(",")){
                        supported.add(item);
                    }
                }
            } else if (mCaptureLayout.equals(CameraSettings.SS_FULL_S3D_LAYOUT)) {
                supp = mParameters.get(CameraSettings.KEY_SUPPORTED_PICTURE_SIDEBYSIDE_SIZES);
                if (supp !=null && !supp.equals("")) {
                    for (String item : supp.split(",")) {
                        supported.add(item);
                    }
                }
            }else if (mCaptureLayout.equals(CameraSettings.SS_SUB_S3D_LAYOUT) ||
                    mCaptureLayout.equals(CameraSettings.TB_SUB_S3D_LAYOUT)) {
                supp = mParameters.get(CameraSettings.KEY_SUPPORTED_PICTURE_SUBSAMPLED_SIZES);
                if (supp !=null && !supp.equals("")) {
                    for (String item : supp.split(",")) {
                        supported.add(item);
                    }
                }
            } else {
                supported = CameraSettings.sizeListToStringList(mParameters.getSupportedPictureSizes());
            }
             CameraSettings.setCameraPictureSize(
                     pictureSize, supported, mParameters);
        }

        mPreviewPanel = findViewById(R.id.frame_layout);
        mPreviewFrameLayout = (PreviewFrameLayout) findViewById(R.id.frame);

        String[] previewDefaults = getResources().getStringArray(R.array.pref_camera_previewsize_default_array);
        String defaultPreviewSize = "";
        if (mPreviewLayout.equals(CameraSettings.TB_FULL_S3D_LAYOUT)) {
            String[] tbPreviewSizes = getResources().getStringArray(R.array.pref_camera_tb_previewsize_entryvalues);
            defaultPreviewSize = elementExists(previewDefaults, tbPreviewSizes);
        } else if (mPreviewLayout.equals(CameraSettings.SS_FULL_S3D_LAYOUT)) {
            String[] ssPreviewSizes = getResources().getStringArray(R.array.pref_camera_ss_previewsize_entryvalues);
            defaultPreviewSize = elementExists(previewDefaults, ssPreviewSizes);
        } else {
            String[] previewSizes2D = getResources().getStringArray(R.array.pref_camera_previewsize_entryvalues);
            defaultPreviewSize = elementExists(previewDefaults, previewSizes2D);
        }

        String previewSize = mPreferences.getString(CameraSettings.KEY_PREVIEW_SIZE, defaultPreviewSize);
        if (previewSize !=null && (!previewSize.equals(mPreviewSize) || previewLayoutUpdated )) {
            List<String> supported = new ArrayList<String>();
            String supp = null;
            if (mPreviewLayout.equals(CameraSettings.TB_FULL_S3D_LAYOUT)) {
                supp = mParameters.get(CameraSettings.KEY_SUPPORTED_PREVIEW_TOPBOTTOM_SIZES);
                if (supp !=null && !supp.equals("")) {
                    for (String item : supp.split(",")) {
                        supported.add(item);
                    }
                }
            } else if (mPreviewLayout.equals(CameraSettings.SS_FULL_S3D_LAYOUT)) {
                supp = mParameters.get(CameraSettings.KEY_SUPPORTED_PREVIEW_SIDEBYSIDE_SIZES);
                if (supp !=null && !supp.equals("")) {
                    for (String item : supp.split(",")) {
                        supported.add(item);
                    }
                }
            } else if (mPreviewLayout.equals(CameraSettings.SS_SUB_S3D_LAYOUT) ||
                    mPreviewLayout.equals(CameraSettings.TB_SUB_S3D_LAYOUT)) {
                supp = mParameters.get(CameraSettings.KEY_SUPPORTED_PREVIEW_SUBSAMPLED_SIZES);
                if (supp !=null && !supp.equals("")) {
                    for (String item : supp.split(",")) {
                        supported.add(item);
                    }
                }
            } else {
                supported = CameraSettings.sizeListToStringList(mParameters.getSupportedPreviewSizes());
            }
            CameraSettings.setCameraPreviewSize(previewSize, supported, mParameters);
            mPreviewSize = previewSize;
            enableCameraControls(true);
            restartNeeded = true;
        }
        Size size = mParameters.getPreviewSize();
        if (mS3dViewEnabled) {
            if (mPreviewLayout.equals(CameraSettings.TB_FULL_S3D_LAYOUT)) {
                size.height /= 2;
            } else if (mPreviewLayout.equals(CameraSettings.SS_FULL_S3D_LAYOUT)) {
                size.width /= 2;
            }
        }
        mPreviewFrameLayout.setAspectRatio((double) size.width / size.height);

        // Exposure mode
        String exposureMode = mPreferences.getString(
                    CameraSettings.KEY_EXPOSURE_MODE_MENU, getString(R.string.pref_camera_exposuremode_default));

        if (exposureMode != null && !exposureMode.equals(mExposureMode)) { // 3d DualCamera Mode
            mParameters.set(PARM_EXPOSURE_MODE, exposureMode);
            mExposureMode = exposureMode;
            if (!isExposureInit) isExposureInit = true;
            else if (exposureMode.equals(mManualExposure) && isManualExposure) { // ManualExposure Mode
                isManualExposure = false;
                ManualGainExposureSettings manualGainExposureDialog = null;
                if (is2DMode()) {
                    int expMin = Integer.parseInt(mParameters.get(CameraSettings.KEY_SUPPORTED_MANUAL_EXPOSURE_MIN));
                    int expMax = Integer.parseInt(mParameters.get(CameraSettings.KEY_SUPPORTED_MANUAL_EXPOSURE_MAX));
                    int expStep = Integer.parseInt(mParameters.get(CameraSettings.KEY_SUPPORTED_MANUAL_EXPOSURE_STEP));
                    int isoMin = Integer.parseInt(mParameters.get(CameraSettings.KEY_SUPPORTED_MANUAL_GAIN_ISO_MIN));
                    int isoMax = Integer.parseInt(mParameters.get(CameraSettings.KEY_SUPPORTED_MANUAL_GAIN_ISO_MAX));
                    int isoStep = Integer.parseInt(mParameters.get(CameraSettings.KEY_SUPPORTED_MANUAL_GAIN_ISO_STEP));
                    int expValue = Integer.parseInt(mParameters.get(CameraSettings.KEY_MANUAL_EXPOSURE));
                    int isoValue = Integer.parseInt(mParameters.get(CameraSettings.KEY_MANUAL_GAIN_ISO));

                    manualGainExposureDialog = new ManualGainExposureSettings(this, mHandler,
                            expValue, isoValue,expMin, expMax,
                            isoMin, isoMax,expStep,isoStep);
                    Editor edit = mPreferences.edit();
                    edit.putString(CameraSettings.KEY_EXPOSURE_MODE_MENU, exposureMode);
                    edit.commit();
                    manualGainExposureDialog.show();
                } else {
                    int expMin = Integer.parseInt(mParameters.get(CameraSettings.KEY_SUPPORTED_MANUAL_EXPOSURE_MIN));
                    int expMax = Integer.parseInt(mParameters.get(CameraSettings.KEY_SUPPORTED_MANUAL_EXPOSURE_MAX));
                    int expStep = Integer.parseInt(mParameters.get(CameraSettings.KEY_SUPPORTED_MANUAL_EXPOSURE_STEP));
                    int isoMin = Integer.parseInt(mParameters.get(CameraSettings.KEY_SUPPORTED_MANUAL_GAIN_ISO_MIN));
                    int isoMax = Integer.parseInt(mParameters.get(CameraSettings.KEY_SUPPORTED_MANUAL_GAIN_ISO_MAX));
                    int isoStep = Integer.parseInt(mParameters.get(CameraSettings.KEY_SUPPORTED_MANUAL_GAIN_ISO_STEP));
                    int expRightValue = Integer.parseInt(mParameters.get(CameraSettings.KEY_MANUAL_EXPOSURE_RIGHT));
                    int expLeftValue = Integer.parseInt(mParameters.get(CameraSettings.KEY_MANUAL_EXPOSURE));
                    int isoRightValue = Integer.parseInt(mParameters.get(CameraSettings.KEY_MANUAL_GAIN_ISO_RIGHT));
                    int isoLeftValue = Integer.parseInt(mParameters.get(CameraSettings.KEY_MANUAL_GAIN_ISO));

                    manualGainExposureDialog = new ManualGainExposureSettings(this, mHandler,
                            expRightValue, expLeftValue, isoRightValue, isoLeftValue,
                            expMin, expMax, isoMin, isoMax,expStep,isoStep);
                    Editor edit = mPreferences.edit();
                    edit.putString(CameraSettings.KEY_EXPOSURE_MODE_MENU, exposureMode);
                    edit.commit();
                }
                manualGainExposureDialog.show();
            } else {
                isManualExposure = true;
                if (is2DMode()) {
                    mParameters.set(CameraSettings.KEY_MANUAL_EXPOSURE, 0);
                    mParameters.set(CameraSettings.KEY_MANUAL_GAIN_ISO, 0);
                } else {
                    mParameters.set(CameraSettings.KEY_MANUAL_EXPOSURE, 0);
                    mParameters.set(CameraSettings.KEY_MANUAL_EXPOSURE_RIGHT, 0);
                    mParameters.set(CameraSettings.KEY_MANUAL_GAIN_ISO, 0);
                    mParameters.set(CameraSettings.KEY_MANUAL_GAIN_ISO_RIGHT, 0);
                }
            }
        }
        // Since change scene mode may change supported values,
        // Set scene mode first,
        mSceneMode = mPreferences.getString(
                CameraSettings.KEY_SCENE_MODE,
                getString(R.string.pref_camera_scenemode_default));
        if (isSupported(mSceneMode, mParameters.getSupportedSceneModes())) {
            if (!mParameters.getSceneMode().equals(mSceneMode)) {
                mParameters.setSceneMode(mSceneMode);
                mCameraDevice.setParameters(mParameters);

                // Setting scene mode will change the settings of flash mode,
                // white balance, and focus mode. Here we read back the
                // parameters, so we can know those settings.
                mParameters = mCameraDevice.getParameters();
            }
        } else {
            mSceneMode = mParameters.getSceneMode();
            if (mSceneMode == null) {
                mSceneMode = Parameters.SCENE_MODE_AUTO;
            }
        }

        // Set JPEG quality.
        String quality = mPreferences.getString(CameraSettings.KEY_JPEG_QUALITY,
                                                 getString(R.string.pref_camera_jpegquality_default));
        int jpegQuality = CameraProfile.QUALITY_HIGH;

        try {
            jpegQuality = Integer.parseInt(quality);
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }

        if ( mJpegQuality != jpegQuality ) {
            mJpegQuality = jpegQuality;
            jpegQuality = CameraProfile.getJpegEncodingQualityParameter(mCameraId, mJpegQuality);
            mParameters.setJpegQuality(jpegQuality);
        }

        // For the following settings, we need to check if the settings are
        // still supported by latest driver, if not, ignore the settings.

        if (Parameters.SCENE_MODE_AUTO.equals(mSceneMode)) {
            // Set flash mode.
            String flashMode = mPreferences.getString(
                    CameraSettings.KEY_FLASH_MODE,
                    getString(R.string.pref_camera_flashmode_default));
            List<String> supportedFlash = mParameters.getSupportedFlashModes();
            if (isSupported(flashMode, supportedFlash)) {
                mParameters.setFlashMode(flashMode);
            } else {
                flashMode = mParameters.getFlashMode();
                if (flashMode == null) {
                    flashMode = getString(
                            R.string.pref_camera_flashmode_no_flash);
                }
            }

            // Set white balance parameter.
            String whiteBalance = mPreferences.getString(
                    CameraSettings.KEY_WHITE_BALANCE,
                    getString(R.string.pref_camera_whitebalance_default));
            if (isSupported(whiteBalance,
                    mParameters.getSupportedWhiteBalance())) {
                mParameters.setWhiteBalance(whiteBalance);
            } else {
                whiteBalance = mParameters.getWhiteBalance();
                if (whiteBalance == null) {
                    whiteBalance = Parameters.WHITE_BALANCE_AUTO;
                }
            }

            // Set focus mode.
            String focusMode = mFocusManager.getFocusMode();
            mFocusManager.overrideFocusMode(null);
            mParameters.setFocusMode(focusMode);
        } else {
            resetExposureCompensation();

            mFocusManager.overrideFocusMode(mParameters.getFocusMode());
            // Set Color Effects to None.
            Editor editor = mPreferences.edit();
            editor.putString(CameraSettings.KEY_COLOR_EFFECT, Parameters.EFFECT_NONE);
            editor.commit();
            mParameters.setColorEffect(Parameters.EFFECT_NONE);

            // Set ISO to auto
            editor.putString(CameraSettings.KEY_ISO, "auto");
            editor.commit();
            mParameters.set(PARM_ISO, getString(R.string.pref_camera_iso_default));

            // Set Exposure mode to auto
            editor.putString(CameraSettings.KEY_EXPOSURE_MODE_MENU,
                    getString(R.string.pref_camera_exposuremode_default));
            editor.commit();
            mParameters.set(PARM_EXPOSURE_MODE, getString(R.string.pref_camera_exposuremode_default));

            // Set Contrast to Normal
            editor.putString(CameraSettings.KEY_CONTRAST, getString(R.string.pref_camera_contrast_default));
            editor.commit();
            mParameters.set(PARM_CONTRAST, getString(R.string.pref_camera_contrast_default));

            // Set brightness to Normal
            editor.putString(CameraSettings.KEY_BRIGHTNESS, getString(R.string.pref_camera_brightness_default));
            editor.commit();
            mParameters.set(PARM_BRIGHTNESS, getString(R.string.pref_camera_brightness_default));

            // Set antibanding to Normal
            editor.putString(CameraSettings.KEY_ANTIBANDING, getString(R.string.pref_camera_antibanding_default));
            editor.commit();
            mParameters.setAntibanding(getString(R.string.pref_camera_antibanding_default));

            if (mIndicatorControlContainer != null) {
                mIndicatorControlContainer.reloadPreferences();
            }
        }

        // Set exposure compensation
        int value = CameraSettings.readExposure(mPreferences);
        int max = mParameters.getMaxExposureCompensation();
        int min = mParameters.getMinExposureCompensation();
        if (value >= min && value <= max) {
            mParameters.setExposureCompensation(value);
        } else {
            Log.w(TAG, "invalid exposure range: " + value);
        }

        // GBCE/GLBCE
        String gbce = mPreferences.getString(
                CameraSettings.KEY_GBCE, PARM_GBCE_OFF);

        if ( PARM_GLBCE.equals(gbce) ) {
            mParameters.set(PARM_GBCE, FALSE);
            mParameters.set(PARM_GLBCE, TRUE);
        } else if ( PARM_GBCE.equals(gbce) ) {
            mParameters.set(PARM_GBCE, TRUE);
            mParameters.set(PARM_GLBCE, FALSE);
        } else {
            mParameters.set(PARM_GBCE, FALSE);
            mParameters.set(PARM_GLBCE, FALSE);
        }

        // Image Capture Pixel Format
        String pictureFormat = mPreferences.getString(
                    CameraSettings.KEY_PICTURE_FORMAT_MENU,
                    getString(R.string.pref_camera_picture_format_default));

        if (!pictureFormat.equals(mPictureFormat)) {
            mParameters.set(CameraSettings.KEY_PICTURE_FORMAT, pictureFormat);
            mPictureFormat = pictureFormat;
        }

        // Capture mode
        String mode = mPreferences.getString(
                CameraSettings.KEY_MODE_MENU, getString(R.string.pref_camera_mode_default));
        setCaptureMode(mode, mParameters);

        if ( mode.equals(mHighQualityZsl) ) {
            overridePictureSettings();
        }

        String bracketRange =  mPreferences.getString(
                CameraSettings.KEY_BRACKET_RANGE,
                getString(R.string.pref_camera_bracketrange_default));

        if ( mTempBracketingEnabled ) {
            mParameters.set(PARM_TEMPORAL_BRACKETING_RANGE_POS, Integer.parseInt(bracketRange) );
            mParameters.set(PARM_TEMPORAL_BRACKETING_RANGE_NEG, Integer.parseInt(bracketRange) );
            mBracketRange = bracketRange;
            mBurstImages = Integer.parseInt(mBracketRange) * 2 + 1;
        }

        int burst = Integer.parseInt(mPreferences.getString(
                CameraSettings.KEY_BURST,
                getString(R.string.pref_camera_burst_default)));

        if (( burst != mBurstImages ) &&
                ( mode.equals(mHighPerformance) ) ) {
              mBurstImages = burst;
              mParameters.set(PARM_BURST, mBurstImages);
              if ( 0 < mBurstImages ) {
                  mBurstRunning = true;
              }
        }

        if ( !mCaptureMode.equals(mode) ) {
            restartNeeded = true;
            mCaptureMode = mode;
            Log.v(TAG,"Capture mode set: " + mParameters.get(CameraSettings.KEY_MODE));
        }

        if (!restartNeeded) {
            setSurfaceLayout();
        }
        return restartNeeded;
    }

    private boolean canSetParameters() {
        boolean ret = true;

        //Hack: Don't apply any new parameters during
        //      temporal bracketing.
        if ( null != mFocusManager ) {
            if ( mFocusManager.getTempBracketingState() == FocusManager.TempBracketingStates.RUNNING ) {
                ret = false;
            }
        }

        return ret;
    }
    // We separate the parameters into several subsets, so we can update only
    // the subsets actually need updating. The PREFERENCE set needs extra
    // locking because the preference can be changed from GLThread as well.
    private void setCameraParameters(int updateSet) {
        boolean restartPreview = false;

        if ( !canSetParameters() ) {
            return;
        }

        mParameters = mCameraDevice.getParameters();

        if ((updateSet & UPDATE_PARAM_INITIALIZE) != 0) {
            updateCameraParametersInitialize();
        }

        if ((updateSet & UPDATE_PARAM_ZOOM) != 0) {
            updateCameraParametersZoom();
        }

        if ((updateSet & UPDATE_PARAM_PREFERENCE) != 0) {
            restartPreview = updateCameraParametersPreference();
        }

        if ((updateSet & UPDATE_PARAM_MODE ) != 0 ) {
            updateCameraParametersPreference();
        }

        mCameraDevice.setParameters(mParameters);

        if ( ( restartPreview ) && ( mCameraState != PREVIEW_STOPPED ) ) {
            // This will only restart the preview
            // without trying to apply any new
            // camera parameters.
            Message msg = new Message();
            msg.what = RESTART_PREVIEW;
            msg.arg1 = MODE_RESTART;
            mHandler.sendMessage(msg);
        }
    }

    // If the Camera is idle, update the parameters immediately, otherwise
    // accumulate them in mUpdateSet and update later.
    private void setCameraParametersWhenIdle(int additionalUpdateSet) {
        mUpdateSet |= additionalUpdateSet;
        if (mCameraDevice == null) {
            // We will update all the parameters when we open the device, so
            // we don't need to do anything now.
            mUpdateSet = 0;
            return;
        } else if (isCameraIdle()) {
            setCameraParameters(mUpdateSet);
            updateSceneModeUI();
            mUpdateSet = 0;
        } else {
            if (!mHandler.hasMessages(SET_CAMERA_PARAMETERS_WHEN_IDLE)) {
                mHandler.sendEmptyMessageDelayed(
                        SET_CAMERA_PARAMETERS_WHEN_IDLE, 1000);
            }
        }
    }

    private void setCaptureMode(String mode, Parameters params) {
        if ( mode.equals(mHighPerformance) ) {
            params.set(CameraSettings.KEY_MODE, mHighPerformance);
            params.set(PARM_IPP, PARM_IPP_NONE);
            mTempBracketingEnabled = false;
            params.remove(PARM_EXPOSURE_BRACKETING_RANGE);
            params.set(CameraSettings.KEY_TEMPORAL_BRACKETING,
                    PARM_TEMPORAL_BRACKETING_DISABLE);
        } else if ( mode.equals(mHighQuality) ) {
            params.set(CameraSettings.KEY_MODE, mHighQuality);
            params.set(PARM_IPP, PARM_IPP_LDCNSF);
            mTempBracketingEnabled = false;
            params.remove(PARM_EXPOSURE_BRACKETING_RANGE);
            params.set(CameraSettings.KEY_TEMPORAL_BRACKETING,
                    PARM_TEMPORAL_BRACKETING_DISABLE);
        } else if ( mode.equals(mHighQualityZsl) ) {
            params.set(CameraSettings.KEY_MODE, mHighQualityZsl);
            params.set(PARM_IPP, PARM_IPP_NONE);
            mTempBracketingEnabled = false;
            params.remove(PARM_EXPOSURE_BRACKETING_RANGE);
            params.set(CameraSettings.KEY_TEMPORAL_BRACKETING,
                    PARM_TEMPORAL_BRACKETING_DISABLE);
        } else if ( mode.equals(mTemporalBracketing) ) {
            params.set(CameraSettings.KEY_MODE, mHighPerformance);
            params.set(PARM_IPP, PARM_IPP_NONE);
            params.set(PARM_GBCE, PARM_GBCE_OFF);

            //Enable Temporal Bracketing
            mTempBracketingEnabled = true;
            params.remove(PARM_EXPOSURE_BRACKETING_RANGE);

        } else if ( mode.equals(mExposureBracketing) ) {

            params.set(CameraSettings.KEY_MODE, mExposureBracketing);
            //Disable GBCE by default
            params.set(PARM_GBCE, PARM_GBCE_OFF);
            params.set(PARM_IPP, PARM_IPP_NONE);
            params.set(PARM_BURST, EXPOSURE_BRACKETING_COUNT);
            params.set(PARM_EXPOSURE_BRACKETING_RANGE,
                              EXPOSURE_BRACKETING_RANGE_VALUE);
            mBurstImages = EXPOSURE_BRACKETING_COUNT;

            //Disable Temporal Brackerting
            mTempBracketingEnabled = false;
            params.set(CameraSettings.KEY_TEMPORAL_BRACKETING,
                                       PARM_TEMPORAL_BRACKETING_DISABLE);

        } else {
            // Default to HQ with LDC&NSF
            params.set(CameraSettings.KEY_MODE, mHighQuality);
            params.set(PARM_IPP, PARM_IPP_LDCNSF);
            params.remove(PARM_EXPOSURE_BRACKETING_RANGE);
        }

        if ( null != mFocusManager ) {
            if( mTempBracketingEnabled ) {
                mFocusManager.setTempBracketingState(FocusManager.TempBracketingStates.ACTIVE);
            }
            else {
                mFocusManager.setTempBracketingState(FocusManager.TempBracketingStates.OFF);
            }
        }

    }

    private void gotoGallery() {
        MenuHelper.gotoCameraImageGallery(this);
    }

    private boolean isCameraIdle() {
        return (mCameraState == IDLE) || (mFocusManager.isFocusCompleted());
    }

    private boolean isImageCaptureIntent() {
        String action = getIntent().getAction();
        return (MediaStore.ACTION_IMAGE_CAPTURE.equals(action));
    }

    private void setupCaptureParams() {
        Bundle myExtras = getIntent().getExtras();
        if (myExtras != null) {
            mSaveUri = (Uri) myExtras.getParcelable(MediaStore.EXTRA_OUTPUT);
            mCropValue = myExtras.getString("crop");
        }
    }

    private void showPostCaptureAlert() {
        if (mIsImageCaptureIntent) {
            Util.fadeOut(mIndicatorControlContainer);
            Util.fadeOut(mShutterButton);

            int[] pickIds = {R.id.btn_retake, R.id.btn_done};
            for (int id : pickIds) {
                Util.fadeIn(findViewById(id));
            }
        }
    }

    private void hidePostCaptureAlert() {
        if (mIsImageCaptureIntent) {
            int[] pickIds = {R.id.btn_retake, R.id.btn_done};
            for (int id : pickIds) {
                Util.fadeOut(findViewById(id));
            }

            Util.fadeIn(mShutterButton);
            Util.fadeIn(mIndicatorControlContainer);
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        // Only show the menu when camera is idle.
        for (int i = 0; i < menu.size(); i++) {
            menu.getItem(i).setVisible(isCameraIdle());
        }

        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);

        if (mIsImageCaptureIntent) {
            // No options menu for attach mode.
            return false;
        } else {
            addBaseMenuItems(menu);
        }
        return true;
    }

    private void addBaseMenuItems(Menu menu) {
        MenuHelper.addSwitchModeMenuItem(menu, ModePicker.MODE_VIDEO, new Runnable() {
            public void run() {
                switchToOtherMode(ModePicker.MODE_VIDEO);
            }
        });
        MenuHelper.addSwitchModeMenuItem(menu, ModePicker.MODE_PANORAMA, new Runnable() {
            public void run() {
                switchToOtherMode(ModePicker.MODE_PANORAMA);
            }
        });

        if (mNumberOfCameras > 1) {
            menu.add(R.string.switch_camera_id)
                    .setOnMenuItemClickListener(new OnMenuItemClickListener() {
                public boolean onMenuItemClick(MenuItem item) {
                    CameraSettings.writePreferredCameraId(mPreferences,
                            (((mCameraId + 1) < mNumberOfCameras)
                            ? (mCameraId + 1) : 0));
                    onSharedPreferenceChanged();
                    return true;
                }
            }).setIcon(android.R.drawable.ic_menu_camera);
        }
    }

    private boolean switchToOtherMode(int mode) {
        if (isFinishing()) return false;
        if (mImageSaver != null) mImageSaver.waitDone();
        MenuHelper.gotoMode(mode, Camera.this);
        mHandler.removeMessages(FIRST_TIME_INIT);
        finish();
        return true;
    }

    public boolean onModeChanged(int mode) {
        if (mode != ModePicker.MODE_CAMERA) {
            return switchToOtherMode(mode);
        } else {
            return true;
        }
    }

    public void onSharedPreferenceChanged() {
        // ignore the events after "onPause()"
        if (mPausing) return;

        boolean recordLocation = RecordLocationPreference.get(
                mPreferences, getContentResolver());
        mLocationManager.recordLocation(recordLocation);

        int cameraId = CameraSettings.readPreferredCameraId(mPreferences);
        if (mCameraId != cameraId) {
            // Restart the activity to have a crossfade animation.
            // TODO: Use SurfaceTexture to implement a better and faster
            // animation.
            if (mIsImageCaptureIntent) {
                // If the intent is camera capture, stay in camera capture mode.
                MenuHelper.gotoCameraMode(this, getIntent());
            } else {
                MenuHelper.gotoCameraMode(this);
            }

            finish();
        } else {
            setCameraParametersWhenIdle(UPDATE_PARAM_PREFERENCE);
        }

        updateOnScreenIndicators();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        keepScreenOnAwhile();
    }

    private void resetBurst() {
        mParameters.set(PARM_BURST, 0);
        mCameraDevice.setParameters(mParameters);
        Editor editor = mPreferences.edit();
        editor.putString(CameraSettings.KEY_BURST, "0");
        editor.apply();
        mIndicatorControlContainer.reloadPreferences();
    }

    private void resetScreenOn() {
        mHandler.removeMessages(CLEAR_SCREEN_DELAY);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void keepScreenOnAwhile() {
        mHandler.removeMessages(CLEAR_SCREEN_DELAY);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mHandler.sendEmptyMessageDelayed(CLEAR_SCREEN_DELAY, SCREEN_DELAY);
    }

    public void onRestorePreferencesClicked() {
        if (mPausing) return;
        Runnable runnable = new Runnable() {
            public void run() {
                restorePreferences();
            }
        };
        mRotateDialog.showAlertDialog(
                getString(R.string.confirm_restore_title),
                getString(R.string.confirm_restore_message),
                getString(android.R.string.ok), runnable,
                getString(android.R.string.cancel), null);
    }

    //Receiver for Automated Testing intent broadcasts
    private class CameraBroadcastReceiverTest extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();
            Log.d(TAG,"CameraTest Broadcast received: " + action);
            Bundle bundle = intent.getExtras();
            Log.d(TAG,"CameraTest Broadcast received: " + bundle);
            if(bundle != null) {

                mIsTestExecuting = true;
                String actionTest = bundle.getString("params");
                String actionValue = bundle.getString("value");

                if(actionTest.equals("script_title")) {
                    Log.d(TAG, "Executing Automated Test");
                    Log.d(TAG, actionValue + " is running");
                    mIMGscriptTitle = actionValue;
                    return;
                }

                if(actionTest.equals("capture")) {
                    Log.d(TAG, "Capture selected");
                    if (mFirstTimeInitialized) {
                        mShutterButton.setPressed(true);
                        mFocusManager.doSnap();
                        mShutterButton.setPressed(false);
                    }
                    return;
                }

                if(actionTest.equals("primary")) {
                    mCameraId = CameraHolder.instance().getFrontCameraId();
                    CameraSettings.writePreferredCameraId(mPreferences,
                            mCameraId);
                    onSharedPreferenceChanged();
                    return;
                }

                if(actionTest.equals("secondary")) {
                    mCameraId = CameraHolder.instance().getBackCameraId();
                    CameraSettings.writePreferredCameraId(mPreferences,
                            mCameraId);
                    onSharedPreferenceChanged();
                    return;
                }
                if(actionTest.equals("restore")) {
                    restorePreferences();
                    return;
                }

                if(actionTest.equals("quit")) {
                    onDestroy();
                    finish();
                }

                Editor editor = mPreferences.edit();
                editor.putString(actionTest,actionValue);
                if(editor.commit() == true) {
                    setCameraParameters(UPDATE_PARAM_ALL);
                    if (mIndicatorControlContainer != null) {
                        mIndicatorControlContainer.reloadPreferences();
                    }
                }
            }
        }
    }

    private void restorePreferences() {
        // Reset the zoom. Zoom value is not stored in preference.
        if (mParameters.isZoomSupported()) {
            mZoomValue = 0;
            setCameraParametersWhenIdle(UPDATE_PARAM_ZOOM);
            mZoomControl.setZoomIndex(mZoomValue);
            mZoomControl.startZoomControl();
        }
        if (mIndicatorControlContainer != null) {
            mIndicatorControlContainer.dismissSettingPopup();

            CameraSettings.restorePreferences(Camera.this, mPreferences,
                    mParameters);
            mIndicatorControlContainer.reloadPreferences();
            onSharedPreferenceChanged();
        }
    }

    public void onOverriddenPreferencesClicked() {
        if (mPausing) return;
        if (mNotSelectableToast == null) {
            String str = getResources().getString(R.string.not_selectable_in_scene_mode);
            mNotSelectableToast = Toast.makeText(Camera.this, str, Toast.LENGTH_SHORT);
        }
        mNotSelectableToast.show();
    }

    private void showSharePopup() {
        mImageSaver.waitDone();
        Uri uri = mThumbnail.getUri();
        if (mSharePopup == null || !uri.equals(mSharePopup.getUri())) {
            // SharePopup window takes the mPreviewPanel as its size reference.
            mSharePopup = new SharePopup(this, uri, mThumbnail.getBitmap(),
                    mOrientationCompensation, mPreviewPanel);
        }
        mSharePopup.showAtLocation(mThumbnailView, Gravity.NO_GRAVITY, 0, 0);
    }

    @Override
    public void onFaceDetection(Face[] faces, android.hardware.Camera camera) {
        mFaceView.setFaces(faces);
    }

    private void showTapToFocusToast() {
        new RotateTextToast(this, R.string.tap_to_focus, mOrientation).show();
        // Clear the preference.
        Editor editor = mPreferences.edit();
        editor.putBoolean(CameraSettings.KEY_CAMERA_FIRST_USE_HINT_SHOWN, false);
        editor.apply();
    }

    private void initializeCapabilities() {
        mInitialParams = mCameraDevice.getParameters();
        mFocusManager.initializeParameters(mInitialParams);
        mFocusAreaSupported = (mInitialParams.getMaxNumFocusAreas() > 0
                && isSupported(Parameters.FOCUS_MODE_AUTO,
                        mInitialParams.getSupportedFocusModes()));
        mMeteringAreaSupported = (mInitialParams.getMaxNumMeteringAreas() > 0);
        mAeLockSupported = mInitialParams.isAutoExposureLockSupported();
        mAwbLockSupported = mInitialParams.isAutoWhiteBalanceLockSupported();
    }
}
