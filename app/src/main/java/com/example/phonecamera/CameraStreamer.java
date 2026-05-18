package com.example.phonecamera;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.view.TextureView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@SuppressWarnings("deprecation")
public class CameraStreamer {
    private static final int TARGET_WIDTH = 640;
    private static final int TARGET_HEIGHT = 480;
    private static final int JPEG_QUALITY = 70;

    private final Activity activity;
    private final TextureView preview;
    private final Object frameLock = new Object();

    private Camera camera;
    private volatile H264Encoder h264Encoder;
    private volatile byte[] latestJpeg;
    private int previewWidth = TARGET_WIDTH;
    private int previewHeight = TARGET_HEIGHT;
    private int naturalOutputRotation = 90;
    private boolean outputPortrait = true;
    private int currentCameraId = -1;
    private int currentZoom = 0;

    public CameraStreamer(Activity activity, TextureView preview) {
        this.activity = activity;
        this.preview = preview;
    }

    public void start() {
        if (preview.isAvailable()) {
            openCamera();
        } else {
            preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    openCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    stop();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                }
            });
        }
    }

    public synchronized void stop() {
        if (camera == null) {
            if (h264Encoder != null) {
                h264Encoder.stop();
                h264Encoder = null;
            }
            return;
        }
        try {
            camera.setPreviewCallback(null);
            camera.stopPreview();
        } catch (RuntimeException ignored) {
        }
        camera.release();
        camera = null;
        if (h264Encoder != null) {
            h264Encoder.stop();
            h264Encoder = null;
        }
    }

    public boolean hasMultipleCameras() {
        return Camera.getNumberOfCameras() > 1;
    }

    public synchronized boolean isZoomSupported() {
        if (camera == null) {
            return false;
        }
        try {
            return camera.getParameters().isZoomSupported();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public synchronized int getMaxZoom() {
        if (camera == null) {
            return 0;
        }
        try {
            Camera.Parameters parameters = camera.getParameters();
            return parameters.isZoomSupported() ? parameters.getMaxZoom() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public synchronized int getZoom() {
        return currentZoom;
    }

    public synchronized void setZoom(int zoom) {
        if (camera == null) {
            currentZoom = zoom;
            return;
        }
        try {
            Camera.Parameters parameters = camera.getParameters();
            if (!parameters.isZoomSupported()) {
                currentZoom = 0;
                return;
            }
            int maxZoom = parameters.getMaxZoom();
            currentZoom = Math.max(0, Math.min(zoom, maxZoom));
            parameters.setZoom(currentZoom);
            camera.setParameters(parameters);
        } catch (RuntimeException ignored) {
        }
    }

    public synchronized boolean switchCamera() {
        int count = Camera.getNumberOfCameras();
        if (count <= 1) {
            return false;
        }
        int nextCameraId = currentCameraId < 0 ? chooseBackCamera() : (currentCameraId + 1) % count;
        stop();
        currentCameraId = nextCameraId;
        currentZoom = 0;
        openCamera();
        return true;
    }

    public synchronized boolean isFrontCamera() {
        if (currentCameraId < 0) {
            return false;
        }
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(currentCameraId, info);
        return info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
    }

    public byte[] getLatestJpeg() {
        synchronized (frameLock) {
            return latestJpeg;
        }
    }

    public synchronized int getFrameWidth() {
        return previewWidth;
    }

    public synchronized int getFrameHeight() {
        return previewHeight;
    }

    public H264Encoder.H264Frame getLatestH264Frame() {
        H264Encoder encoder = h264Encoder;
        return encoder == null ? null : encoder.getLatestFrame();
    }

    public byte[] getH264Sps() {
        H264Encoder encoder = h264Encoder;
        return encoder == null ? null : encoder.getSps();
    }

    public byte[] getH264Pps() {
        H264Encoder encoder = h264Encoder;
        return encoder == null ? null : encoder.getPps();
    }

    public synchronized boolean isOutputPortrait() {
        return outputPortrait;
    }

    public synchronized void setOutputPortrait(boolean outputPortrait) {
        if (this.outputPortrait == outputPortrait) {
            return;
        }
        this.outputPortrait = outputPortrait;
        restartH264Encoder();
    }

    public synchronized void refreshOrientation() {
        if (camera == null || currentCameraId < 0) {
            return;
        }
        try {
            int displayOrientation = calculateDisplayOrientation(currentCameraId);
            camera.setDisplayOrientation(displayOrientation);
            updatePreviewAspectRatio(displayOrientation);
        } catch (RuntimeException ignored) {
        }
    }

    private synchronized void openCamera() {
        if (camera != null) {
            return;
        }
        try {
            if (currentCameraId < 0) {
                currentCameraId = chooseBackCamera();
            }
            camera = Camera.open(currentCameraId);
            configureCamera();
            int displayOrientation = calculateDisplayOrientation(currentCameraId);
            naturalOutputRotation = calculateNaturalOutputRotation(currentCameraId);
            restartH264Encoder();
            camera.setDisplayOrientation(displayOrientation);
            updatePreviewAspectRatio(displayOrientation);
            camera.setPreviewTexture(preview.getSurfaceTexture());
            camera.setPreviewCallback(new Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(byte[] data, Camera camera) {
                    updateJpegFrame(data);
                }
            });
            camera.startPreview();
            notifyControlsChanged();
        } catch (IOException | RuntimeException e) {
            stop();
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (activity instanceof MainActivity) {
                        ((MainActivity) activity).showStatus("\u76f8\u673a\u542f\u52a8\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u6743\u9650\u6216\u5176\u4ed6\u5e94\u7528\u662f\u5426\u5360\u7528\u76f8\u673a");
                    }
                }
            });
        }
    }

    private int chooseBackCamera() {
        int count = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < count; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                return i;
            }
        }
        return 0;
    }

    private void configureCamera() {
        Camera.Parameters parameters = camera.getParameters();
        Camera.Size size = choosePreviewSize(parameters.getSupportedPreviewSizes());
        previewWidth = size.width;
        previewHeight = size.height;
        parameters.setPreviewSize(previewWidth, previewHeight);
        parameters.setPreviewFormat(ImageFormat.NV21);
        List<String> focusModes = parameters.getSupportedFocusModes();
        if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
        } else if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
        }
        if (parameters.isZoomSupported()) {
            currentZoom = Math.max(0, Math.min(currentZoom, parameters.getMaxZoom()));
            parameters.setZoom(currentZoom);
        } else {
            currentZoom = 0;
        }
        camera.setParameters(parameters);
    }

    private Camera.Size choosePreviewSize(List<Camera.Size> sizes) {
        Camera.Size best = sizes.get(0);
        int bestScore = Integer.MAX_VALUE;
        for (Camera.Size size : sizes) {
            int score = Math.abs(size.width - TARGET_WIDTH) + Math.abs(size.height - TARGET_HEIGHT);
            if (score < bestScore) {
                best = size;
                bestScore = score;
            }
        }
        return best;
    }

    private void updateJpegFrame(byte[] data) {
        try {
            int outputRotation = outputPortrait ? naturalOutputRotation : 0;
            byte[] outputData = data;
            int outputWidth = previewWidth;
            int outputHeight = previewHeight;
            if (outputRotation == 90 || outputRotation == 270) {
                outputData = rotateNv21(data, previewWidth, previewHeight, outputRotation);
                outputWidth = previewHeight;
                outputHeight = previewWidth;
            } else if (outputRotation == 180) {
                outputData = rotateNv21(data, previewWidth, previewHeight, outputRotation);
            }
            H264Encoder encoder = h264Encoder;
            if (encoder != null) {
                encoder.encodeNv21(outputData);
            }
            YuvImage image = new YuvImage(outputData, ImageFormat.NV21, outputWidth, outputHeight, null);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            image.compressToJpeg(new Rect(0, 0, outputWidth, outputHeight), JPEG_QUALITY, output);
            synchronized (frameLock) {
                latestJpeg = output.toByteArray();
            }
        } catch (RuntimeException ignored) {
        }
    }

    private byte[] rotateNv21(byte[] input, int width, int height, int rotation) {
        if (rotation == 90) {
            return rotateNv21Right(input, width, height);
        }
        if (rotation == 180) {
            return rotateNv21HalfTurn(input, width, height);
        }
        if (rotation == 270) {
            return rotateNv21Left(input, width, height);
        }
        return input;
    }

    private byte[] rotateNv21Right(byte[] input, int width, int height) {
        byte[] output = new byte[input.length];
        int frameSize = width * height;
        int index = 0;
        for (int x = 0; x < width; x++) {
            for (int y = height - 1; y >= 0; y--) {
                output[index++] = input[y * width + x];
            }
        }
        index = frameSize;
        for (int x = 0; x < width; x += 2) {
            for (int y = height / 2 - 1; y >= 0; y--) {
                int source = frameSize + y * width + x;
                output[index++] = input[source];
                output[index++] = input[source + 1];
            }
        }
        return output;
    }

    private byte[] rotateNv21Left(byte[] input, int width, int height) {
        byte[] output = new byte[input.length];
        int frameSize = width * height;
        int index = 0;
        for (int x = width - 1; x >= 0; x--) {
            for (int y = 0; y < height; y++) {
                output[index++] = input[y * width + x];
            }
        }
        index = frameSize;
        for (int x = width - 2; x >= 0; x -= 2) {
            for (int y = 0; y < height / 2; y++) {
                int source = frameSize + y * width + x;
                output[index++] = input[source];
                output[index++] = input[source + 1];
            }
        }
        return output;
    }

    private byte[] rotateNv21HalfTurn(byte[] input, int width, int height) {
        byte[] output = new byte[input.length];
        int frameSize = width * height;
        int index = 0;
        for (int i = frameSize - 1; i >= 0; i--) {
            output[index++] = input[i];
        }
        for (int i = input.length - 2; i >= frameSize; i -= 2) {
            output[index++] = input[i];
            output[index++] = input[i + 1];
        }
        return output;
    }

    private int calculateDisplayOrientation(int cameraId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;
        if (rotation == android.view.Surface.ROTATION_90) {
            degrees = 90;
        } else if (rotation == android.view.Surface.ROTATION_180) {
            degrees = 180;
        } else if (rotation == android.view.Surface.ROTATION_270) {
            degrees = 270;
        }
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (360 - ((info.orientation + degrees) % 360)) % 360;
        }
        return (info.orientation - degrees + 360) % 360;
    }

    private int calculateNaturalOutputRotation(int cameraId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (360 - info.orientation) % 360;
        }
        return info.orientation;
    }

    private void restartH264Encoder() {
        if (h264Encoder != null) {
            h264Encoder.stop();
        }
        h264Encoder = new H264Encoder();
        int outputRotation = outputPortrait ? naturalOutputRotation : 0;
        int outputWidth = previewWidth;
        int outputHeight = previewHeight;
        if (outputRotation == 90 || outputRotation == 270) {
            outputWidth = previewHeight;
            outputHeight = previewWidth;
        }
        h264Encoder.start(outputWidth, outputHeight);
    }

    private void notifyControlsChanged() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).updateCameraControls();
                }
            }
        });
    }

    private void updatePreviewAspectRatio(final int displayOrientation) {
        if (!(preview instanceof AutoFitTextureView)) {
            return;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                AutoFitTextureView fitPreview = (AutoFitTextureView) preview;
                if (displayOrientation == 90 || displayOrientation == 270) {
                    fitPreview.setAspectRatio(previewHeight, previewWidth);
                } else {
                    fitPreview.setAspectRatio(previewWidth, previewHeight);
                }
            }
        });
    }
}
