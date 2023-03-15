/*
 * Copyright 2019 The Android Open Source Project
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
package androidx.camera.extensions.impl;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.util.Pair;
import android.util.Range;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Stub implementation for bokeh image capture use case.
 *
 * <p>This class should be implemented by OEM and deployed to the target devices.
 *
 * @since 1.0
 */
public final class BokehImageCaptureExtenderImpl implements ImageCaptureExtenderImpl {
    public BokehImageCaptureExtenderImpl() {}

    @Override
    public boolean isExtensionAvailable(@NonNull String cameraId,
            @Nullable CameraCharacteristics cameraCharacteristics) {
        return true;
    }

    @Override
    public void init(@NonNull String cameraId,
            @NonNull CameraCharacteristics cameraCharacteristics) {
    }

    @Nullable
    @Override
    public CaptureProcessorImpl getCaptureProcessor() {
        return null;
    }

    @Nullable
    @Override
    public List<CaptureStageImpl> getCaptureStages() {
        return null;
    }

    @Override
    public int getMaxCaptureStage() {
        return 2;
    }

    @Override
    public void onInit(@NonNull String cameraId,
            @NonNull CameraCharacteristics cameraCharacteristics,
            @NonNull Context context) {
    }

    @Override
    public void onDeInit() {
    }

    @Nullable
    @Override
    public CaptureStageImpl onPresetSession() {
        return null;
    }

    @Nullable
    @Override
    public CaptureStageImpl onEnableSession() {
        return null;
    }

    @Nullable
    @Override
    public CaptureStageImpl onDisableSession() {
        return null;
    }

    @Nullable
    @Override
    public List<Pair<Integer, Size[]>> getSupportedResolutions() {
        return null;
    }

    @Nullable
    @Override
    public Range<Long> getEstimatedCaptureLatencyRange(@Nullable Size captureOutputSize) {
        return null;
    }

    @NonNull
    @Override
    public List<CaptureRequest.Key> getAvailableCaptureRequestKeys() {
        return null;
    }

    @NonNull
    @Override
    public List<CaptureResult.Key> getAvailableCaptureResultKeys() {
        return null;
    }

}
