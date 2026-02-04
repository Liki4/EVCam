package com.kooo.evcam;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.Size;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;

/**
 * 独立补盲悬浮窗视图
 * 支持拖动和边缘缩放
 */
public class BlindSpotFloatingWindowView extends FrameLayout {
    private static final String TAG = "BlindSpotFloatingWindowView";
    private static final int RESIZE_THRESHOLD = 50;

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private AppConfig appConfig;
    private TextureView textureView;
    private Surface cachedSurface;
    private SingleCamera currentCamera;
    private String cameraPos = "right"; // 默认用右摄像头测试
    private boolean isSetupMode = false;
    private int currentRotation = 0;

    private float lastX, lastY;
    private float initialX, initialY;
    private int initialWidth, initialHeight;
    private boolean isResizing = false;
    private int resizeMode = 0;

    public BlindSpotFloatingWindowView(Context context, boolean isSetupMode) {
        super(context);
        this.isSetupMode = isSetupMode;
        appConfig = new AppConfig(context);
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        init();
    }

    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.view_blind_spot_floating, this);
        textureView = findViewById(R.id.blind_spot_texture_view);
        View saveLayout = findViewById(R.id.layout_save_config);
        View saveButton = findViewById(R.id.btn_save_blind_spot_config);
        View rotateButton = findViewById(R.id.btn_rotate_blind_spot);

        currentRotation = appConfig.getTurnSignalFloatingRotation();
        applyRotation(currentRotation);

        if (isSetupMode) {
            saveLayout.setVisibility(View.VISIBLE);
            saveButton.setOnClickListener(v -> {
                appConfig.setTurnSignalFloatingBounds(params.x, params.y, params.width, params.height);
                appConfig.setTurnSignalFloatingRotation(currentRotation);
                dismiss();
            });
            rotateButton.setOnClickListener(v -> {
                currentRotation = (currentRotation + 90) % 360;
                applyRotation(currentRotation);
            });
        }

        params = new WindowManager.LayoutParams(
                appConfig.getTurnSignalFloatingWidth(),
                appConfig.getTurnSignalFloatingHeight(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = appConfig.getTurnSignalFloatingX();
        params.y = appConfig.getTurnSignalFloatingY();

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width, int height) {
                MainActivity mainActivity = MainActivity.getInstance();
                if (mainActivity != null) {
                    MultiCameraManager cameraManager = mainActivity.getCameraManager();
                    if (cameraManager != null) {
                        SingleCamera camera = cameraManager.getCamera(cameraPos);
                        if (camera != null) {
                            Size previewSize = camera.getPreviewSize();
                            if (previewSize != null) {
                                surface.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                            }
                        }
                    }
                }
                if (cachedSurface != null) cachedSurface.release();
                cachedSurface = new Surface(surface);
                startCameraPreview(cachedSurface);
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
                stopCameraPreview();
                if (cachedSurface != null) {
                    cachedSurface.release();
                    cachedSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {}
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isSetupMode) return super.onTouchEvent(event);

        float x = event.getRawX();
        float y = event.getRawY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastX = x;
                lastY = y;
                initialX = params.x;
                initialY = params.y;
                initialWidth = params.width;
                initialHeight = params.height;

                float localX = event.getX();
                float localY = event.getY();
                resizeMode = 0;
                if (localX < RESIZE_THRESHOLD) resizeMode |= 1;
                if (localX > getWidth() - RESIZE_THRESHOLD) resizeMode |= 2;
                if (localY < RESIZE_THRESHOLD) resizeMode |= 4;
                if (localY > getHeight() - RESIZE_THRESHOLD) resizeMode |= 8;
                
                isResizing = resizeMode != 0;
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = x - lastX;
                float dy = y - lastY;

                if (isResizing) {
                    if ((resizeMode & 1) != 0) {
                        int newWidth = (int) (initialWidth - dx);
                        if (newWidth > 200) {
                            params.width = newWidth;
                            params.x = (int) (initialX + dx);
                        }
                    }
                    if ((resizeMode & 2) != 0) {
                        int newWidth = (int) (initialWidth + dx);
                        if (newWidth > 200) params.width = newWidth;
                    }
                    if ((resizeMode & 4) != 0) {
                        int newHeight = (int) (initialHeight - dy);
                        if (newHeight > 200) {
                            params.height = newHeight;
                            params.y = (int) (initialY + dy);
                        }
                    }
                    if ((resizeMode & 8) != 0) {
                        int newHeight = (int) (initialHeight + dy);
                        if (newHeight > 200) params.height = newHeight;
                    }
                } else {
                    params.x = (int) (initialX + dx);
                    params.y = (int) (initialY + dy);
                }
                
                windowManager.updateViewLayout(this, params);
                return true;

            case MotionEvent.ACTION_UP:
                return true;
        }
        return super.onTouchEvent(event);
    }

    public void setCamera(String cameraPos) {
        this.cameraPos = cameraPos;
        if (textureView.isAvailable()) {
            stopCameraPreview();
            startCameraPreview(cachedSurface);
        }
    }

    private void startCameraPreview(Surface surface) {
        MainActivity mainActivity = MainActivity.getInstance();
        if (mainActivity == null) return;
        MultiCameraManager cameraManager = mainActivity.getCameraManager();
        if (cameraManager == null) return;

        currentCamera = cameraManager.getCamera(cameraPos);
        if (currentCamera != null) {
            currentCamera.setMainFloatingSurface(surface); // 复用这个接口
            currentCamera.recreateSession();
        }
    }

    private void stopCameraPreview() {
        if (currentCamera != null) {
            currentCamera.setMainFloatingSurface(null);
            currentCamera.recreateSession();
            currentCamera = null;
        }
    }

    public void show() {
        try {
            if (this.getParent() == null) {
                windowManager.addView(this, params);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Error showing blind spot floating window: " + e.getMessage());
        }
    }

    private void applyRotation(int rotation) {
        textureView.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();
            if (viewWidth == 0 || viewHeight == 0) return;

            android.graphics.Matrix matrix = new android.graphics.Matrix();
            float centerX = viewWidth / 2f;
            float centerY = viewHeight / 2f;

            matrix.postRotate(rotation, centerX, centerY);

            if (rotation == 90 || rotation == 270) {
                float scale = (float) viewWidth / viewHeight;
                matrix.postScale(1 / scale, scale, centerX, centerY);
            }

            textureView.setTransform(matrix);
        });
    }

    public void dismiss() {
        stopCameraPreview();
        try {
            windowManager.removeView(this);
        } catch (Exception e) {
            // Ignore
        }
    }
}
