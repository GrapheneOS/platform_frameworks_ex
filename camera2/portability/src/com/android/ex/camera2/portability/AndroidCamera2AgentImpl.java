/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.ex.camera2.portability;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.view.Surface;

import com.android.ex.camera2.portability.debug.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A class to implement {@link CameraAgent} of the Android camera2 framework.
 */
class AndroidCamera2AgentImpl extends CameraAgent {
    private static final Log.Tag TAG = new Log.Tag("AndCam2AgntImp");

    private final Camera2Handler mCameraHandler;
    private final HandlerThread mCameraHandlerThread;
    private final CameraStateHolder mCameraState;
    private final DispatchThread mDispatchThread;
    private final CameraManager mCameraManager;

    /**
     * Number of camera devices.  The length of {@code mCameraDevices} does not reveal this
     * information because that list may contain since-invalidated indices.
     */
    private int mNumCameraDevices;

    /**
     * Transformation between integral camera indices and the {@link java.lang.String} indices used
     * by the underlying API.  Note that devices may disappear because they've been disconnected or
     * have otherwise gone offline.  Because we need to keep the meanings of whatever indices we
     * expose stable, we cannot simply remove them in such a case; instead, we insert {@code null}s
     * to invalidate any such indices.  Whenever new devices appear, they are appended to the end of
     * the list, and thereby assigned the lowest index that has never yet been used.
     */
    private final List<String> mCameraDevices;

    AndroidCamera2AgentImpl(Context context) {
        mCameraHandlerThread = new HandlerThread("Camera2 Handler Thread");
        mCameraHandlerThread.start();
        mCameraHandler = new Camera2Handler(mCameraHandlerThread.getLooper());
        mCameraState = new AndroidCamera2StateHolder();
        mDispatchThread = new DispatchThread(mCameraHandler, mCameraHandlerThread);
        mDispatchThread.start();
        mCameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        mNumCameraDevices = 0;
        mCameraDevices = new ArrayList<String>();
        updateCameraDevices();
    }

    /**
     * Updates the camera device index assignments stored in {@link mCameraDevices}, without
     * reappropriating any currently-assigned index.
     * @return Whether the operation was successful
     */
    private boolean updateCameraDevices() {
        try {
            String[] currentCameraDevices = mCameraManager.getCameraIdList();
            Set<String> currentSet = new HashSet<String>(Arrays.asList(currentCameraDevices));

            // Invalidate the indices assigned to any camera devices that are no longer present
            for (int index = 0; index < mCameraDevices.size(); ++index) {
                if (!currentSet.contains(mCameraDevices.get(index))) {
                    mCameraDevices.set(index, null);
                    --mNumCameraDevices;
                }
            }

            // Assign fresh indices to any new camera devices
            currentSet.removeAll(mCameraDevices); // The devices we didn't know about
            for (String device : currentCameraDevices) {
                if (currentSet.contains(device)) {
                    mCameraDevices.add(device);
                    ++mNumCameraDevices;
                }
            }

            return true;
        } catch (CameraAccessException ex) {
            Log.e(TAG, "Could not get device listing from camera subsystem", ex);
            return false;
        }
    }

    // TODO: Implement
    @Override
    public void setCameraDefaultExceptionCallback(CameraExceptionCallback callback,
            Handler handler) {}

    // TODO: Implement
    @Override
    public void recycle() {}

    // TODO: Some indices may now be invalid; ensure everyone can handle that and update the docs
    @Override
    public CameraDeviceInfo getCameraDeviceInfo() {
        updateCameraDevices();
        return new AndroidCamera2DeviceInfo(mCameraManager, mCameraDevices.toArray(new String[0]),
                mNumCameraDevices);
    }

    @Override
    protected Handler getCameraHandler() {
        return mCameraHandler;
    }

    @Override
    protected DispatchThread getDispatchThread() {
        return mDispatchThread;
    }

    private class Camera2Handler extends HistoryHandler {
        // Caller-provided when leaving CAMERA_UNOPENED state:
        private CameraOpenCallback mOpenCallback;
        private int mCameraIndex;
        private String mCameraId;

        // Available in CAMERA_UNCONFIGURED state and above:
        private CameraDevice mCamera;
        private AndroidCamera2ProxyImpl mCameraProxy;
        private CaptureRequest.Builder mPersistentRequestBuilder;
        private Rect mActiveArray;

        // Available in CAMERA_CONFIGURED state and above:
        private Size mPreviewSize;
        private Size mPhotoSize;

        // Available in PREVIEW_READY state and above:
        private SurfaceTexture mPreviewTexture;
        private Surface mPreviewSurface;
        private CameraCaptureSession mSession;

        // Available from the beginning of PREVIEW_ACTIVE until the first preview frame arrives:
        private CameraStartPreviewCallback mOneshotPreviewingCallback;

        // Available in FOCUS_LOCKED between AF trigger receipt and whenever the lens stops moving:
        private CameraAFCallback mOneshotAfCallback;

        // Available whenever setAutoFocusMoveCallback() was last invoked with a non-null argument:
        private CameraAFMoveCallback mPassiveAfCallback;

        Camera2Handler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(final Message msg) {
            super.handleMessage(msg);
            try {
                switch(msg.what) {
                    case CameraActions.OPEN_CAMERA:
                    case CameraActions.RECONNECT: {
                        CameraOpenCallback openCallback = (CameraOpenCallback) msg.obj;
                        int cameraIndex = msg.arg1;

                        if (mCameraState.getState() != AndroidCamera2StateHolder.CAMERA_UNOPENED) {
                            openCallback.onDeviceOpenedAlready(cameraIndex,
                                    generateHistoryString(cameraIndex));
                            break;
                        }

                        mOpenCallback = openCallback;
                        mCameraIndex = cameraIndex;
                        mCameraId = mCameraDevices.get(mCameraIndex);

                        if (mCameraId == null) {
                            mOpenCallback.onCameraDisabled(msg.arg1);
                            break;
                        }
                        mCameraManager.openCamera(mCameraId, mCameraDeviceStateListener, this);

                        break;
                    }

                    case CameraActions.RELEASE: {
                        if (mCameraState.getState() == AndroidCamera2StateHolder.CAMERA_UNOPENED) {
                            Log.w(TAG, "Ignoring release at inappropriate time");
                            break;
                        }

                        if (mSession != null) {
                            closePreviewSession();
                            mSession = null;
                        }
                        if (mCamera != null) {
                            mCamera.close();
                            mCamera = null;
                        }
                        mCameraProxy = null;
                        mPersistentRequestBuilder = null;
                        mActiveArray = null;
                        if (mPreviewSurface != null) {
                            mPreviewSurface.release();
                            mPreviewSurface = null;
                        }
                        mPreviewTexture = null;
                        mPreviewSize = null;
                        mPhotoSize = null;
                        mCameraIndex = 0;
                        mCameraId = null;
                        mCameraState.setState(AndroidCamera2StateHolder.CAMERA_UNOPENED);
                        break;
                    }

                    /*case CameraActions.UNLOCK: {
                        break;
                    }

                    case CameraActions.LOCK: {
                        break;
                    }*/

                    case CameraActions.SET_PREVIEW_TEXTURE_ASYNC: {
                        setPreviewTexture((SurfaceTexture) msg.obj);
                        break;
                    }

                    case CameraActions.START_PREVIEW_ASYNC: {
                        if (mCameraState.getState() !=
                                        AndroidCamera2StateHolder.CAMERA_PREVIEW_READY) {
                            // TODO: Provide better feedback here?
                            Log.w(TAG, "Refusing to start preview at inappropriate time");
                            break;
                        }

                        mOneshotPreviewingCallback = (CameraStartPreviewCallback) msg.obj;
                        mCameraState.setState(AndroidCamera2StateHolder.CAMERA_PREVIEW_ACTIVE);
                        try {
                        mSession.setRepeatingRequest(mPersistentRequestBuilder.build(),
                                /*listener*/mCameraFocusStateListener, /*handler*/this);
                        } catch(CameraAccessException ex) {
                            Log.w(TAG, "Unable to start preview", ex);
                            mCameraState.setState(AndroidCamera2StateHolder.CAMERA_PREVIEW_READY);
                        }
                        break;
                    }

                    case CameraActions.STOP_PREVIEW: {
                        if (mCameraState.getState() <
                                        AndroidCamera2StateHolder.CAMERA_PREVIEW_ACTIVE) {
                            Log.w(TAG, "Refusing to stop preview at inappropriate time");
                            break;
                        }

                        mSession.stopRepeating();
                        mCameraState.setState(AndroidCamera2StateHolder.CAMERA_PREVIEW_READY);
                        break;
                    }

                    /*case CameraActions.SET_PREVIEW_CALLBACK_WITH_BUFFER: {
                        break;
                    }

                    case CameraActions.ADD_CALLBACK_BUFFER: {
                        break;
                    }

                    case CameraActions.SET_PREVIEW_DISPLAY_ASYNC: {
                        break;
                    }

                    case CameraActions.SET_PREVIEW_CALLBACK: {
                        break;
                    }

                    case CameraActions.SET_ONE_SHOT_PREVIEW_CALLBACK: {
                        break;
                    }

                    case CameraActions.SET_PARAMETERS: {
                        break;
                    }

                    case CameraActions.GET_PARAMETERS: {
                        break;
                    }

                    case CameraActions.REFRESH_PARAMETERS: {
                        break;
                    }*/

                    case CameraActions.APPLY_SETTINGS: {
                        CameraSettings settings = (CameraSettings) msg.obj;
                        applyToRequest(settings);
                        break;
                    }

                    case CameraActions.AUTO_FOCUS: {
                        // We only support locking the focus while a preview is being displayed.
                        // However, it can be requested multiple times in succession; the effect of
                        // the subsequent invocations is determined by the focus mode defined in the
                        // provided CameraSettings object. In passive (CONTINUOUS_*) mode, the
                        // duplicate requests are no-ops and leave the lens locked at its current
                        // position, but in active (AUTO) mode, they perform another scan and lock
                        // once that is finished. In any manual focus mode, this call is a no-op,
                        // and most notably, this is the only case where the callback isn't invoked.
                        if (mCameraState.getState() <
                                        AndroidCamera2StateHolder.CAMERA_PREVIEW_ACTIVE) {
                            Log.w(TAG, "Ignoring attempt to autofocus without preview");
                            break;
                        }

                        // The earliest we can reliably tell whether the autofocus has locked in
                        // response to our latest request is when our one-time capture completes.
                        // However, it will probably take longer than that, so once that happens,
                        // just start checking the repeating preview requests as they complete.
                        final CameraAFCallback callback = (CameraAFCallback) msg.obj;
                        CameraCaptureSession.CaptureListener deferredCallbackSetter =
                                new CameraCaptureSession.CaptureListener() {
                            @Override
                            public void onCaptureCompleted(CameraCaptureSession session,
                                                           CaptureRequest request,
                                                           TotalCaptureResult result) {
                                // Now our mCameraFocusStateListener will invoke the callback the
                                // first time it finds the focus motor to be locked.
                                mOneshotAfCallback = callback;
                            }

                            @Override
                            public void onCaptureFailed(CameraCaptureSession session,
                                                        CaptureRequest request,
                                                        CaptureFailure failure) {
                                Log.e(TAG, "Focusing failed with reason " + failure.getReason());
                                callback.onAutoFocus(false, mCameraProxy);
                            }};

                        // Send a one-time capture to trigger the camera driver to lock focus.
                        mCameraState.setState(AndroidCamera2StateHolder.CAMERA_FOCUS_LOCKED);
                        mPersistentRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                CaptureRequest.CONTROL_AF_TRIGGER_START);
                        try {
                            mSession.capture(mPersistentRequestBuilder.build(),
                                    /*listener*/deferredCallbackSetter, /*handler*/ this);
                        } catch(CameraAccessException ex) {
                            Log.e(TAG, "Unable to lock autofocus", ex);
                            mCameraState.setState(AndroidCamera2StateHolder.CAMERA_PREVIEW_ACTIVE);
                        }
                        mPersistentRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                        break;
                    }

                    case CameraActions.CANCEL_AUTO_FOCUS: {
                        // Why would you want to unlock the lens if it isn't already locked?
                        if (mCameraState.getState() <
                                AndroidCamera2StateHolder.CAMERA_PREVIEW_ACTIVE) {
                            Log.w(TAG, "Ignoring attempt to release focus lock without preview");
                            break;
                        }

                        // Send a one-time capture to trigger the camera driver to resume scanning.
                        mCameraState.setState(AndroidCamera2StateHolder.CAMERA_PREVIEW_ACTIVE);
                        mPersistentRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                        try {
                            mSession.capture(mPersistentRequestBuilder.build(),
                                    /*listener*/null, /*handler*/this);
                        } catch(CameraAccessException ex) {
                            Log.e(TAG, "Unable to cancel autofocus", ex);
                            mCameraState.setState(
                                    AndroidCamera2StateHolder.CAMERA_FOCUS_LOCKED);
                        }
                        mPersistentRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                                CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                        break;
                    }

                    case CameraActions.SET_AUTO_FOCUS_MOVE_CALLBACK: {
                        mPassiveAfCallback = (CameraAFMoveCallback) msg.obj;
                        break;
                    }

                    /*case CameraActions.SET_ZOOM_CHANGE_LISTENER: {
                        break;
                    }

                    case CameraActions.SET_FACE_DETECTION_LISTENER: {
                        break;
                    }

                    case CameraActions.START_FACE_DETECTION: {
                        break;
                    }

                    case CameraActions.STOP_FACE_DETECTION: {
                        break;
                    }

                    case CameraActions.SET_ERROR_CALLBACK: {
                        break;
                    }

                    case CameraActions.ENABLE_SHUTTER_SOUND: {
                        break;
                    }

                    case CameraActions.SET_DISPLAY_ORIENTATION: {
                        break;
                    }

                    case CameraActions.CAPTURE_PHOTO: {
                        break;
                    }*/

                    default: {
                        // TODO: Rephrase once everything has been implemented
                        throw new RuntimeException("Unimplemented CameraProxy message=" + msg.what);
                    }
                }
            } catch (final Exception ex) {
                if (msg.what != CameraActions.RELEASE && mCamera != null) {
                    // TODO: Handle this better
                    mCamera.close();
                    mCamera = null;
                } else if (mCamera == null) {
                    if (msg.what == CameraActions.OPEN_CAMERA) {
                        if (mOpenCallback != null) {
                            mOpenCallback.onDeviceOpenFailure(mCameraIndex,
                                    generateHistoryString(mCameraIndex));
                        }
                    } else {
                        Log.w(TAG, "Cannot handle message " + msg.what + ", mCamera is null");
                    }
                    return;
                }

                if (ex instanceof RuntimeException) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            sCameraExceptionCallback.onCameraException((RuntimeException) ex);
                        }});
                }
            }
        }

        public CameraSettings buildSettings(AndroidCamera2Capabilities caps) {
            return new AndroidCamera2Settings(caps, mPersistentRequestBuilder, mPreviewSize,
                    mPhotoSize);
        }

        /**
         * Simply propagates settings from provided {@link CameraSettings}
         * object to our {@link CaptureRequest.Builder} for use in captures.
         * <p>Most conversions to match the API 2 formats are performed by
         * {@link AndroidCamera2Capabilities.IntegralStringifier}; otherwise
         * any final adjustments are done here before updating the builder.</p>
         *
         * @param settings The new/updated settings
         */
        // TODO: Finish implementation to add support for all settings
        private void applyToRequest(CameraSettings settings) {
            // TODO: If invoked when in PREVIEW_READY state, a new preview size will not take effect
            AndroidCamera2Capabilities.IntegralStringifier intifier =
                    mCameraProxy.getSpecializedCapabilities().getIntegralStringifier();
            mPreviewSize = settings.getCurrentPreviewSize();
            mPhotoSize = settings.getCurrentPhotoSize();
            mPersistentRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    intifier.intify(settings.getCurrentFocusMode()));

            mPersistentRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS,
                    legacyAreasToMeteringRectangles(settings.getFocusAreas()));
            mPersistentRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS,
                    legacyAreasToMeteringRectangles(settings.getMeteringAreas()));

            if (settings.getCurrentFlashMode() != CameraCapabilities.FlashMode.NO_FLASH) {
                mPersistentRequestBuilder.set(CaptureRequest.FLASH_MODE,
                        intifier.intify(settings.getCurrentFlashMode()));
            }
            if (settings.getCurrentSceneMode() != CameraCapabilities.SceneMode.NO_SCENE_MODE) {
                mPersistentRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE,
                        intifier.intify(settings.getCurrentSceneMode()));
            }

            if (mCameraState.getState() >= AndroidCamera2StateHolder.CAMERA_PREVIEW_ACTIVE) {
                // If we're already previewing, reflect most settings immediately
                try {
                    mSession.setRepeatingRequest(mPersistentRequestBuilder.build(),
                            /*listener*/mCameraFocusStateListener, /*handler*/this);
                } catch (CameraAccessException ex) {
                    Log.e(TAG, "Failed to apply updated request settings", ex);
                }
            } else if (mCameraState.getState() < AndroidCamera2StateHolder.CAMERA_PREVIEW_READY) {
                // If we're already ready to preview, this doesn't regress our state
                mCameraState.setState(AndroidCamera2StateHolder.CAMERA_CONFIGURED);
            }
        }

        private MeteringRectangle[] legacyAreasToMeteringRectangles(
                List<android.hardware.Camera.Area> reference) {
            MeteringRectangle[] transformed = null;
            if (reference.size() > 0) {

                transformed = new MeteringRectangle[reference.size()];
                for (int index = 0; index < reference.size(); ++index) {
                    android.hardware.Camera.Area source = reference.get(index);
                    Rect rectangle = source.rect;

                    // Old API coordinates were [-1000,1000]; new ones are [0,ACTIVE_ARRAY_SIZE).
                    double oldLeft = (rectangle.left + 1000) / 2000.0;
                    double oldTop = (rectangle.top + 1000) / 2000.0;
                    double oldRight = (rectangle.right + 1000) / 2000.0;
                    double oldBottom = (rectangle.bottom + 1000) / 2000.0;
                    int left = toIntConstrained(
                            mActiveArray.width() * oldLeft + mActiveArray.left,
                            0, mActiveArray.width() - 1);
                    int top = toIntConstrained(
                            mActiveArray.height() * oldTop + mActiveArray.top,
                            0, mActiveArray.height() - 1);
                    int right = toIntConstrained(
                            mActiveArray.width() * oldRight + mActiveArray.left,
                            0, mActiveArray.width() - 1);
                    int bottom = toIntConstrained(
                            mActiveArray.height() * oldBottom + mActiveArray.top,
                            0, mActiveArray.height() - 1);
                    transformed[index] = new MeteringRectangle(left, top,
                            right - left, bottom - top, source.weight);
                }
            }
            return transformed;
        }

        private int toIntConstrained(double original, int min, int max) {
            original = Math.max(original, min);
            original = Math.min(original, max);
            return (int) original;
        }

        private void setPreviewTexture(SurfaceTexture surfaceTexture) {
            // TODO: Must be called after providing a .*Settings populated with sizes
            // TODO: We don't technically offer a selection of sizes tailored to SurfaceTextures!

            // TODO: Handle this error condition with a callback or exception
            if (mCameraState.getState() < AndroidCamera2StateHolder.CAMERA_CONFIGURED) {
                Log.w(TAG, "Ignoring texture setting at inappropriate time");
                return;
            }

            // Avoid initializing another capture session unless we absolutely have to
            if (surfaceTexture == mPreviewTexture) {
                Log.i(TAG, "Optimizing out redundant preview texture setting");
                return;
            }

            if (mSession != null) {
                closePreviewSession();
            }

            mPreviewTexture = surfaceTexture;
            surfaceTexture.setDefaultBufferSize(mPreviewSize.width(), mPreviewSize.height());

            if (mPreviewSurface != null) {
                mPersistentRequestBuilder.removeTarget(mPreviewSurface);
                mPreviewSurface.release();
            }
            mPreviewSurface = new Surface(surfaceTexture);
            mPersistentRequestBuilder.addTarget(mPreviewSurface);

            try {
                mCamera.createCaptureSession(Arrays.asList(mPreviewSurface),
                        mCameraPreviewStateListener, this);
            } catch (CameraAccessException ex) {
                Log.e(TAG, "Failed to create camera capture session", ex);
            }
        }

        private void closePreviewSession() {
            try {
                mSession.abortCaptures();
                mSession = null;
            } catch (CameraAccessException ex) {
                Log.e(TAG, "Failed to close existing camera capture session", ex);
            }
            mCameraState.setState(AndroidCamera2StateHolder.CAMERA_CONFIGURED);
        }

        // This listener monitors our connection to and disconnection from camera devices.
        private CameraDevice.StateListener mCameraDeviceStateListener =
                new CameraDevice.StateListener() {
            @Override
            public void onOpened(CameraDevice camera) {
                mCamera = camera;
                if (mOpenCallback != null) {
                    try {
                        CameraCharacteristics props =
                                mCameraManager.getCameraCharacteristics(mCameraId);
                        mCameraProxy = new AndroidCamera2ProxyImpl(mCameraIndex, mCamera,
                                    getCameraDeviceInfo().getCharacteristics(mCameraIndex), props);
                        mPersistentRequestBuilder =
                                camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        mActiveArray =
                                props.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                        mCameraState.setState(AndroidCamera2StateHolder.CAMERA_UNCONFIGURED);
                        mOpenCallback.onCameraOpened(mCameraProxy);
                    } catch (CameraAccessException ex) {
                        mOpenCallback.onDeviceOpenFailure(mCameraIndex,
                                generateHistoryString(mCameraIndex));
                    }
                }
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                Log.w(TAG, "Camera device '" + mCameraIndex + "' was disconnected");
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                Log.e(TAG, "Camera device '" + mCameraIndex + "' encountered error code '" +
                        error + '\'');
                if (mOpenCallback != null) {
                    mOpenCallback.onDeviceOpenFailure(mCameraIndex,
                            generateHistoryString(mCameraIndex));
                }
            }};

        // This listener monitors our camera session (i.e. our transition into and out of preview).
        private CameraCaptureSession.StateListener mCameraPreviewStateListener =
                new CameraCaptureSession.StateListener() {
            @Override
            public void onConfigured(CameraCaptureSession session) {
                mSession = session;
                mCameraState.setState(AndroidCamera2StateHolder.CAMERA_PREVIEW_READY);
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session) {
                // TODO: Invoke a callback
                Log.e(TAG, "Failed to configure the camera for capture");
            }

            @Override
            public void onActive(CameraCaptureSession session) {
                if (mOneshotPreviewingCallback != null) {
                    // The session is up and processing preview requests. Inform the caller.
                    mOneshotPreviewingCallback.onPreviewStarted();
                    mOneshotPreviewingCallback = null;
                }
            }};

        // This listener monitors requested captures and notifies any relevant callbacks.
        private CameraCaptureSession.CaptureListener mCameraFocusStateListener =
                new CameraCaptureSession.CaptureListener() {
            private int mLastAfState = -1;

            @Override
            public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                           TotalCaptureResult result) {
                Integer afStateMaybe = result.get(CaptureResult.CONTROL_AF_STATE);
                if (afStateMaybe != null) {
                    int afState = afStateMaybe;
                    boolean afStateChanged = false;
                    if (afState != mLastAfState) {
                        mLastAfState = afState;
                        afStateChanged = true;
                    }

                    switch (afState) {
                        case CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN:
                        case CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED:
                        case CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED: {
                            if (afStateChanged && mPassiveAfCallback != null) {
                                // A CameraAFMoveCallback is attached. If we just started to scan,
                                // the motor is moving; otherwise, it has settled.
                                mPassiveAfCallback.onAutoFocusMoving(
                                        afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN,
                                        mCameraProxy);
                            }
                            break;
                        }

                        case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED:
                        case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED: {
                            if (mOneshotAfCallback != null) {
                                // A call to autoFocus() was just made to request a focus lock.
                                // Notify the caller that the lens is now indefinitely fixed, and
                                // report whether the image we're now stuck with is in focus.
                                mOneshotAfCallback.onAutoFocus(
                                        afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
                                        mCameraProxy);
                                mOneshotAfCallback = null;
                            }
                            break;
                        }
                    }
                }
            }

            @Override
            public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request,
                                        CaptureFailure failure) {
                Log.e(TAG, "Capture attempt failed with reason " + failure.getReason());
            }};
    }

    private class AndroidCamera2ProxyImpl extends CameraAgent.CameraProxy {
        private final int mCameraIndex;
        private final CameraDevice mCamera;
        private final CameraDeviceInfo.Characteristics mCharacteristics;
        private final AndroidCamera2Capabilities mCapabilities;

        public AndroidCamera2ProxyImpl(int cameraIndex, CameraDevice camera,
                CameraDeviceInfo.Characteristics characteristics,
                CameraCharacteristics properties) {
            mCameraIndex = cameraIndex;
            mCamera = camera;
            mCharacteristics = characteristics;
            mCapabilities = new AndroidCamera2Capabilities(properties);
        }

        // TODO: Implement
        @Override
        public android.hardware.Camera getCamera() { return null; }

        @Override
        public int getCameraId() {
            return mCameraIndex;
        }

        @Override
        public CameraDeviceInfo.Characteristics getCharacteristics() {
            return mCharacteristics;
        }

        @Override
        public CameraCapabilities getCapabilities() {
            return mCapabilities;
        }

        private AndroidCamera2Capabilities getSpecializedCapabilities() {
            return mCapabilities;
        }

        // TODO: Implement
        @Override
        public void setPreviewDataCallback(Handler handler, CameraPreviewDataCallback cb) {}

        // TODO: Implement
        @Override
        public void setOneShotPreviewCallback(Handler handler, CameraPreviewDataCallback cb) {}

        // TODO: Implement
        @Override
        public void setPreviewDataCallbackWithBuffer(Handler handler, CameraPreviewDataCallback cb)
                {}

        @Override
        public void autoFocus(final Handler handler, final CameraAFCallback cb) {
            mDispatchThread.runJob(new Runnable() {
                @Override
                public void run() {
                    CameraAFCallback cbForward = null;
                    if (cb != null) {
                        cbForward = new CameraAFCallback() {
                            @Override
                            public void onAutoFocus(final boolean focused,
                                                    final CameraProxy camera) {
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        cb.onAutoFocus(focused, camera);
                                    }});
                            }};
                    }

                    mCameraState.waitForStates(AndroidCamera2StateHolder.CAMERA_PREVIEW_ACTIVE |
                            AndroidCamera2StateHolder.CAMERA_FOCUS_LOCKED);
                    mCameraHandler.obtainMessage(CameraActions.AUTO_FOCUS, cbForward)
                            .sendToTarget();
                }});
        }

        @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
        @Override
        public void setAutoFocusMoveCallback(final Handler handler, final CameraAFMoveCallback cb) {
            mDispatchThread.runJob(new Runnable() {
                @Override
                public void run() {
                    CameraAFMoveCallback cbForward = null;
                    if (cb != null) {
                        cbForward = new CameraAFMoveCallback() {
                            @Override
                            public void onAutoFocusMoving(final boolean moving,
                                                          final CameraProxy camera) {
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        cb.onAutoFocusMoving(moving, camera);
                                    }});
                                }};
                    }

                    mCameraHandler.obtainMessage(CameraActions.SET_AUTO_FOCUS_MOVE_CALLBACK,
                            cbForward).sendToTarget();
                }});
        }

        // TODO: Implement
        @Override
        public void takePicture(Handler handler,
                                CameraShutterCallback shutter,
                                CameraPictureCallback raw,
                                CameraPictureCallback postview,
                                CameraPictureCallback jpeg) {}

        // TODO: Remove this method override once we handle the message
        @Override
        public void setDisplayOrientation(int degrees) {}

        // TODO: Implement
        @Override
        public void setZoomChangeListener(android.hardware.Camera.OnZoomChangeListener listener) {}

        // TODO: Implement
        @Override
        public void setFaceDetectionCallback(Handler handler, CameraFaceDetectionCallback callback)
                {}

        // TODO: Remove this method override once we handle this message
        @Override
        public void startFaceDetection() {}

        // TODO: Remove this method override once we handle this message
        @Override
        public void stopFaceDetection() {}

        // TODO: Implement
        @Override
        public void setErrorCallback(Handler handler, CameraErrorCallback cb) {}

        // TODO: Implement
        @Override
        public void setParameters(android.hardware.Camera.Parameters params) {}

        // TODO: Implement
        @Override
        public android.hardware.Camera.Parameters getParameters() { return null; }

        @Override
        public CameraSettings getSettings() {
            return mCameraHandler.buildSettings(mCapabilities);
        }

        @Override
        public boolean applySettings(CameraSettings settings) {
            return applySettingsHelper(settings, AndroidCamera2StateHolder.CAMERA_UNCONFIGURED |
                    AndroidCamera2StateHolder.CAMERA_CONFIGURED |
                    AndroidCamera2StateHolder.CAMERA_PREVIEW_READY);
        }

        // TODO: Implement
        @Override
        public String dumpDeviceSettings() { return null; }

        @Override
        public Handler getCameraHandler() {
            return AndroidCamera2AgentImpl.this.getCameraHandler();
        }

        @Override
        public DispatchThread getDispatchThread() {
            return AndroidCamera2AgentImpl.this.getDispatchThread();
        }

        @Override
        public CameraStateHolder getCameraState() {
            return mCameraState;
        }
    }

    /** A linear state machine: each state entails all the states below it. */
    private static class AndroidCamera2StateHolder extends CameraStateHolder {
        // Usage flow: openCamera() -> applySettings() -> setPreviewTexture() -> startPreview() ->
        //             autoFocus() -> takePicture()
        /* Camera states */
        /** No camera device is opened. */
        public static final int CAMERA_UNOPENED = 1;
        /** A camera is opened, but no settings have been provided. */
        public static final int CAMERA_UNCONFIGURED = 2;
        /** The open camera has been configured by providing it with settings. */
        public static final int CAMERA_CONFIGURED = 3;
        /** A capture session is ready to stream a preview, but still has no repeating request. */
        public static final int CAMERA_PREVIEW_READY = 4;
        /** A preview is currently being streamed. */
        public static final int CAMERA_PREVIEW_ACTIVE = 5;
        /** The lens is locked on a particular region. */
        public static final int CAMERA_FOCUS_LOCKED = 6;

        public AndroidCamera2StateHolder() {
            this(CAMERA_UNOPENED);
        }

        public AndroidCamera2StateHolder(int state) {
            super(state);
        }
    }

    private static class AndroidCamera2DeviceInfo implements CameraDeviceInfo {
        private final CameraManager mCameraManager;
        private final String[] mCameraIds;
        private final int mNumberOfCameras;
        private final int mFirstBackCameraId;
        private final int mFirstFrontCameraId;

        public AndroidCamera2DeviceInfo(CameraManager cameraManager,
                                        String[] cameraIds, int numberOfCameras) {
            mCameraManager = cameraManager;
            mCameraIds = cameraIds;
            mNumberOfCameras = numberOfCameras;

            int firstBackId = NO_DEVICE;
            int firstFrontId = NO_DEVICE;
            for (int id = 0; id < cameraIds.length; ++id) {
                try {
                    int lensDirection = cameraManager.getCameraCharacteristics(cameraIds[id])
                            .get(CameraCharacteristics.LENS_FACING);
                    if (firstBackId == NO_DEVICE &&
                            lensDirection == CameraCharacteristics.LENS_FACING_BACK) {
                        firstBackId = id;
                    }
                    if (firstFrontId == NO_DEVICE &&
                            lensDirection == CameraCharacteristics.LENS_FACING_FRONT) {
                        firstFrontId = id;
                    }
                } catch (CameraAccessException ex) {
                    Log.w(TAG, "Couldn't get characteristics of camera '" + id + "'", ex);
                }
            }
            mFirstBackCameraId = firstBackId;
            mFirstFrontCameraId = firstFrontId;
        }

        @Override
        public Characteristics getCharacteristics(int cameraId) {
            String actualId = mCameraIds[cameraId];
            try {
                CameraCharacteristics info = mCameraManager.getCameraCharacteristics(actualId);
                return new AndroidCharacteristics2(info);
            } catch (CameraAccessException ex) {
                return null;
            }
        }

        @Override
        public int getNumberOfCameras() {
            return mNumberOfCameras;
        }

        @Override
        public int getFirstBackCameraId() {
            return mFirstBackCameraId;
        }

        @Override
        public int getFirstFrontCameraId() {
            return mFirstFrontCameraId;
        }

        private static class AndroidCharacteristics2 implements Characteristics {
            private CameraCharacteristics mCameraInfo;

            AndroidCharacteristics2(CameraCharacteristics cameraInfo) {
                mCameraInfo = cameraInfo;
            }

            @Override
            public boolean isFacingBack() {
                return mCameraInfo.get(CameraCharacteristics.LENS_FACING)
                        .equals(CameraCharacteristics.LENS_FACING_BACK);
            }

            @Override
            public boolean isFacingFront() {
                return mCameraInfo.get(CameraCharacteristics.LENS_FACING)
                        .equals(CameraCharacteristics.LENS_FACING_FRONT);
            }

            @Override
            public int getSensorOrientation() {
                return mCameraInfo.get(CameraCharacteristics.SENSOR_ORIENTATION);
            }

            @Override
            public boolean canDisableShutterSound() {
                // The new API doesn't support this operation, so don't encourage people to try it.
                // TODO: What kind of assumptions have callers made about this result's meaning?
                return false;
            }
        }
    }

    private static final CameraExceptionCallback sCameraExceptionCallback =
            new CameraExceptionCallback() {
                @Override
                public synchronized void onCameraException(RuntimeException e) {
                    throw e;
                }
            };
}