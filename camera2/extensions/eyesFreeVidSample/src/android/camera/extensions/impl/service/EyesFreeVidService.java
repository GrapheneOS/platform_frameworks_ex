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

package android.camera.extensions.impl.service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.annotation.FlaggedApi;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.extension.AdvancedExtender;
import android.hardware.camera2.extension.CameraExtensionService;
import android.hardware.camera2.extension.CharacteristicsMap;
import android.hardware.camera2.extension.SessionProcessor;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.IBinder;
import android.util.Size;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;

import com.android.internal.camera.flags.Flags;

@FlaggedApi(Flags.FLAG_CONCERT_MODE)
public class EyesFreeVidService extends CameraExtensionService {

    private static final String TAG = "EyesFreeVidService";

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Set<IBinder> mAttachedClients = new HashSet<>();
    CameraManager mCameraManager;

    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    @Override
    public boolean onRegisterClient(IBinder token) {
        synchronized (mLock) {
            if (mAttachedClients.contains(token)) {
                return false;
            }
            mAttachedClients.add(token);
            return true;
        }
    }

    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    @Override
    public void onUnregisterClient(IBinder token) {
        synchronized (mLock) {
            mAttachedClients.remove(token);
        }
    }

    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    @Override
    public AdvancedExtender onInitializeAdvancedExtension(int extensionType) {
        mCameraManager = getSystemService(CameraManager.class);
        return new AdvancedExtenderEyesFreeImpl(mCameraManager);
    }

    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public static class AdvancedExtenderEyesFreeImpl extends AdvancedExtender {
        private CameraCharacteristics mCameraCharacteristics;

        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        public AdvancedExtenderEyesFreeImpl(@NonNull CameraManager cameraManager) {
            super(cameraManager);
        }

        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        @Override
        public boolean isExtensionAvailable(String cameraId,
                CharacteristicsMap charsMap) {
            return true;
        }

        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        @Override
        public void init(String cameraId, CharacteristicsMap map) {
            mCameraCharacteristics = map.get(cameraId);
        }

        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        @Override
        public Map<Integer, List<Size>> getSupportedPreviewOutputResolutions(
                String cameraId) {
            return filterOutputResolutions(Arrays.asList(ImageFormat.YUV_420_888,
                    ImageFormat.PRIVATE));
        }

        protected Map<Integer, List<Size>> filterOutputResolutions(List<Integer> formats) {
            Map<Integer, List<Size>> formatResolutions = new HashMap<>();

            StreamConfigurationMap map = mCameraCharacteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (map != null) {
                for (Integer format : formats) {
                    if (map.getOutputSizes(format) != null) {
                        formatResolutions.put(format, Arrays.asList(map.getOutputSizes(format)));
                    }
                }
            }

            return formatResolutions;
        }

        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        @Override
        public Map<Integer, List<Size>> getSupportedCaptureOutputResolutions(
                String cameraId) {
            return filterOutputResolutions(Arrays.asList(ImageFormat.YUV_420_888,
                    ImageFormat.JPEG));
        }

        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        @Override
        public SessionProcessor getSessionProcessor() {
            return new EyesFreeVidSessionProcessor(this);
        }

        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        @Override
        public List<CaptureRequest.Key> getAvailableCaptureRequestKeys(
                String cameraId) {
            final CaptureRequest.Key [] CAPTURE_REQUEST_SET = {CaptureRequest.CONTROL_ZOOM_RATIO,
                CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_REGIONS,
                CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.JPEG_QUALITY,
                CaptureRequest.JPEG_ORIENTATION};
            return Arrays.asList(CAPTURE_REQUEST_SET);
        }

        @FlaggedApi(Flags.FLAG_CONCERT_MODE)
        @Override
        public List<CaptureResult.Key> getAvailableCaptureResultKeys(
                String cameraId) {
            final CaptureResult.Key [] CAPTURE_RESULT_SET = {CaptureResult.CONTROL_ZOOM_RATIO,
                CaptureResult.CONTROL_AF_MODE, CaptureResult.CONTROL_AF_REGIONS,
                CaptureResult.CONTROL_AF_TRIGGER, CaptureResult.CONTROL_AF_STATE,
                CaptureResult.JPEG_QUALITY, CaptureResult.JPEG_ORIENTATION};
            return Arrays.asList(CAPTURE_RESULT_SET);
        }
    }
}
