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

package androidx.camera.extensions.impl.advanced;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.util.Log;

import androidx.annotation.NonNull;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

// Jpeg compress input YUV and queue back in the client target surface.
public class JpegEncoder {
    private final static String TAG = "JpegEncoder";
    public final static int JPEG_DEFAULT_QUALITY = 100;
    public final static int JPEG_DEFAULT_ROTATION = 0;
    public static final int HAL_PIXEL_FORMAT_BLOB = 0x21;

    public static void encodeToJpeg(Image yuvInputImage, Image jpegImage,
            int jpegOrientation, int jpegQuality) {

        byte[] yuvBytes = yuv_420_888toNv21(yuvInputImage);
        YuvImage yuvImage = new YuvImage(yuvBytes, ImageFormat.NV21, yuvInputImage.getWidth(),
                yuvInputImage.getHeight(), null);
        File file = null;
        try {
            // Encode YUV to JPEG and save as a teamp file.
            file = File.createTempFile("ExtensionsTemp", ".jpg");
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            Rect imageRect = new Rect(0, 0, yuvInputImage.getWidth(), yuvInputImage.getHeight());
            yuvImage.compressToJpeg(imageRect, jpegQuality, fileOutputStream);
            fileOutputStream.close();

            // Update orientation EXIF on this file.
            writeOrientationExif(file, jpegOrientation);

            // Read the JPEG data into JPEG Image byte buffer.
            ByteBuffer jpegBuf = jpegImage.getPlanes()[0].getBuffer();
            readFileToByteBuffer(file, jpegBuf);

            // Set limits on jpeg buffer and rewind
            jpegBuf.limit(jpegBuf.position());
            jpegBuf.rewind();
        } catch (IOException e) {
            Log.e(TAG, "Failed to encode the YUV data into a JPEG image", e);
        } finally {
            if (file != null) {
                file.delete();
            }
        }
    }

    private static void writeOrientationExif(File file, int jpegOrientation) throws IOException {
        ExifInterface exifInterface = new ExifInterface(file);
        int orientationEnum = ExifInterface.ORIENTATION_NORMAL;

        switch (jpegOrientation) {
            case 0:
                orientationEnum = ExifInterface.ORIENTATION_NORMAL;
                break;
            case 90:
                orientationEnum = ExifInterface.ORIENTATION_ROTATE_90;
                break;
            case 180:
                orientationEnum = ExifInterface.ORIENTATION_ROTATE_180;
                break;
            case 270:
                orientationEnum = ExifInterface.ORIENTATION_ROTATE_270;
                break;
            default:
                Log.e(TAG, "Invalid jpeg orientation:" + jpegOrientation);
                break;
        }
        exifInterface.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(orientationEnum));
        exifInterface.saveAttributes();
    }

    private static void readFileToByteBuffer(File file, ByteBuffer byteBuffer) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                byteBuffer.put(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            Log.e(TAG, "failed to read the file into the byte buffer", e);
        }
    }
    public static int imageFormatToPublic(int format) {
        switch (format) {
            case HAL_PIXEL_FORMAT_BLOB:
                return ImageFormat.JPEG;
            default:
                return format;
        }
    }

    @NonNull
    private static byte[] yuv_420_888toNv21(@NonNull Image image) {
        Image.Plane yPlane = image.getPlanes()[0];
        Image.Plane uPlane = image.getPlanes()[1];
        Image.Plane vPlane = image.getPlanes()[2];

        ByteBuffer yBuffer = yPlane.getBuffer();
        ByteBuffer uBuffer = uPlane.getBuffer();
        ByteBuffer vBuffer = vPlane.getBuffer();
        yBuffer.rewind();
        uBuffer.rewind();
        vBuffer.rewind();

        int ySize = yBuffer.remaining();

        int position = 0;
        // TODO(b/115743986): Pull these bytes from a pool instead of allocating for every image.
        byte[] nv21 = new byte[ySize + (image.getWidth() * image.getHeight() / 2)];

        // Add the full y buffer to the array. If rowStride > 1, some padding may be skipped.
        for (int row = 0; row < image.getHeight(); row++) {
            yBuffer.get(nv21, position, image.getWidth());
            position += image.getWidth();
            yBuffer.position(
                    Math.min(ySize, yBuffer.position() - image.getWidth() + yPlane.getRowStride()));
        }

        int chromaHeight = image.getHeight() / 2;
        int chromaWidth = image.getWidth() / 2;
        int vRowStride = vPlane.getRowStride();
        int uRowStride = uPlane.getRowStride();
        int vPixelStride = vPlane.getPixelStride();
        int uPixelStride = uPlane.getPixelStride();

        // Interleave the u and v frames, filling up the rest of the buffer. Use two line buffers to
        // perform faster bulk gets from the byte buffers.
        byte[] vLineBuffer = new byte[vRowStride];
        byte[] uLineBuffer = new byte[uRowStride];
        for (int row = 0; row < chromaHeight; row++) {
            vBuffer.get(vLineBuffer, 0, Math.min(vRowStride, vBuffer.remaining()));
            uBuffer.get(uLineBuffer, 0, Math.min(uRowStride, uBuffer.remaining()));
            int vLineBufferPosition = 0;
            int uLineBufferPosition = 0;
            for (int col = 0; col < chromaWidth; col++) {
                nv21[position++] = vLineBuffer[vLineBufferPosition];
                nv21[position++] = uLineBuffer[uLineBufferPosition];
                vLineBufferPosition += vPixelStride;
                uLineBufferPosition += uPixelStride;
            }
        }

        return nv21;
    }
}
