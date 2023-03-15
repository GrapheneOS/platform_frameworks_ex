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
import android.util.Pair;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Stub implementation for bokeh preview use case.
 *
 * <p>This class should be implemented by OEM and deployed to the target devices.
 *
 * @since 1.0
 */
public final class BokehPreviewExtenderImpl implements PreviewExtenderImpl {
    public BokehPreviewExtenderImpl() {}

    @Override
    public boolean isExtensionAvailable(@NonNull String cameraId,
            @Nullable CameraCharacteristics cameraCharacteristics) {
        return true;
    }

    @Override
    public void init(@NonNull String cameraId,
            @NonNull CameraCharacteristics cameraCharacteristics) {
    }

    @NonNull
    @Override
    public CaptureStageImpl getCaptureStage() {
        return null;
    }

    @NonNull
    @Override
    public ProcessorType getProcessorType() {
        return ProcessorType.PROCESSOR_TYPE_NONE;
    }

    @Nullable
    @Override
    public ProcessorImpl getProcessor() {
        return null;
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
}
