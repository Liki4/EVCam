package com.test.cam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.navigation.NavigationView;
import com.test.cam.camera.MultiCameraManager;
import com.test.cam.dingtalk.DingTalkApiClient;
import com.test.cam.dingtalk.VideoUploadService;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSIONS = 100;

    // 根据Android版本动态获取需要的权限
    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            };
        } else {
            // Android 12及以下
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }
    }

    private AutoFitTextureView textureFront, textureBack, textureLeft, textureRight;
    private Button btnStartRecord, btnStopRecord;
    private MultiCameraManager cameraManager;
    private int textureReadyCount = 0;  // 记录准备好的TextureView数量

    // 导航相关
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private View recordingLayout;  // 录制界面布局
    private View fragmentContainer;  // Fragment容器

    // 日志相关
    private TextView logText;
    private ScrollView logScroll;
    private TextView logToggle;
    private boolean logExpanded = false;
    private StringBuilder logBuffer = new StringBuilder();
    private Process logcatProcess;
    private Thread logcatThread;
    private volatile boolean logcatRunning = false;
    private boolean logcatStarted = false;

    // 远程录制相关
    private String remoteConversationId;  // 钉钉会话 ID
    private String remoteUserId;  // 钉钉用户 ID
    private android.os.Handler autoStopHandler;  // 自动停止录制的 Handler
    private Runnable autoStopRunnable;  // 自动停止录制的 Runnable

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupNavigationDrawer();

        // 初始化自动停止 Handler
        autoStopHandler = new android.os.Handler(android.os.Looper.getMainLooper());

        // 权限检查，但不立即初始化摄像头
        // 等待TextureView准备好后再初始化
        if (!checkPermissions()) {
            requestPermissions();
        }
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);
        recordingLayout = findViewById(R.id.main);
        fragmentContainer = findViewById(R.id.fragment_container);

        textureFront = findViewById(R.id.texture_front);
        textureBack = findViewById(R.id.texture_back);
        textureLeft = findViewById(R.id.texture_left);
        textureRight = findViewById(R.id.texture_right);
        btnStartRecord = findViewById(R.id.btn_start_record);
        btnStopRecord = findViewById(R.id.btn_stop_record);

        // 初始化日志视图
        logText = findViewById(R.id.log_text);
        logScroll = findViewById(R.id.log_scroll);
        logToggle = findViewById(R.id.log_toggle);

        // 日志面板点击事件
        findViewById(R.id.log_header).setOnClickListener(v -> toggleLogPanel());

        // 菜单按钮点击事件
        findViewById(R.id.btn_menu).setOnClickListener(v -> {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });

        btnStartRecord.setOnClickListener(v -> startRecording());
        btnStopRecord.setOnClickListener(v -> stopRecording());

        // 为每个TextureView添加Surface监听器
        TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {
                textureReadyCount++;
                Log.d(TAG, "TextureView ready: " + textureReadyCount + "/4");
                appendLog("TextureView就绪: " + textureReadyCount + "/4 (尺寸: " + width + "x" + height + ")");

                // 当所有TextureView都准备好后，初始化摄像头
                if (textureReadyCount == 4 && checkPermissions()) {
                    appendLog("所有TextureView已就绪,开始初始化摄像头");
                    initCamera();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "TextureView size changed: " + width + "x" + height);
                appendLog("TextureView尺寸变化: " + width + "x" + height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull android.graphics.SurfaceTexture surface) {
                textureReadyCount--;
                Log.d(TAG, "TextureView destroyed, remaining: " + textureReadyCount);
                appendLog("TextureView销毁,剩余: " + textureReadyCount);
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull android.graphics.SurfaceTexture surface) {
                // 不需要处理每帧更新
            }
        };

        textureFront.setSurfaceTextureListener(surfaceTextureListener);
        textureBack.setSurfaceTextureListener(surfaceTextureListener);
        textureLeft.setSurfaceTextureListener(surfaceTextureListener);
        textureRight.setSurfaceTextureListener(surfaceTextureListener);

        // 初始化日志
        appendLog("应用启动");
    }

    /**
     * 设置导航抽屉
     */
    private void setupNavigationDrawer() {
        // 设置导航菜单点击监听
        navigationView.setNavigationItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_recording) {
                // 显示录制界面
                showRecordingInterface();
            } else if (itemId == R.id.nav_playback) {
                // 显示回看界面
                showPlaybackInterface();
            } else if (itemId == R.id.nav_remote_view) {
                // 显示远程查看界面
                showRemoteViewInterface();
            }
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        // 默认选中录制界面
        navigationView.setCheckedItem(R.id.nav_recording);
    }

    /**
     * 显示录制界面
     */
    private void showRecordingInterface() {
        // 清除所有Fragment
        FragmentManager fragmentManager = getSupportFragmentManager();
        for (Fragment fragment : fragmentManager.getFragments()) {
            fragmentManager.beginTransaction().remove(fragment).commit();
        }

        // 显示录制布局，隐藏Fragment容器
        recordingLayout.setVisibility(View.VISIBLE);
        fragmentContainer.setVisibility(View.GONE);
    }

    /**
     * 显示回看界面
     */
    private void showPlaybackInterface() {
        // 隐藏录制布局，显示Fragment容器
        recordingLayout.setVisibility(View.GONE);
        fragmentContainer.setVisibility(View.VISIBLE);

        // 显示PlaybackFragment
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragment_container, new PlaybackFragment());
        transaction.commit();
    }

    /**
     * 显示远程查看界面
     */
    private void showRemoteViewInterface() {
        // 隐藏录制布局，显示Fragment容器
        recordingLayout.setVisibility(View.GONE);
        fragmentContainer.setVisibility(View.VISIBLE);

        // 显示RemoteViewFragment
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragment_container, new RemoteViewFragment());
        transaction.commit();
    }

    /**
     * 切换日志面板显示/隐藏
     */
    private void toggleLogPanel() {
        logExpanded = !logExpanded;
        if (logExpanded) {
            logScroll.setVisibility(android.view.View.VISIBLE);
            logToggle.setText("▲");
        } else {
            logScroll.setVisibility(android.view.View.GONE);
            logToggle.setText("▼");
        }
    }

    /**
     * 添加日志信息
     */
    private void appendLog(String message) {
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        String logLine = timestamp + " - " + message + "\n";

        runOnUiThread(() -> {
            logBuffer.append(logLine);
            // 限制日志缓冲区大小
            if (logBuffer.length() > 20000) {
                logBuffer.delete(0, logBuffer.length() - 20000);
            }
            logText.setText(logBuffer.toString());
            // 自动滚动到底部
            logScroll.post(() -> logScroll.fullScroll(android.view.View.FOCUS_DOWN));
        });

        // 同时输出到 Logcat
        Log.d(TAG, message);
    }

    private void appendLogcatLine(String line) {
        if (logText == null || logScroll == null) {
            return;
        }
        runOnUiThread(() -> {
            logBuffer.append(line).append('\n');
            if (logBuffer.length() > 20000) {
                logBuffer.delete(0, logBuffer.length() - 20000);
            }
            logText.setText(logBuffer.toString());
            logScroll.post(() -> logScroll.fullScroll(android.view.View.FOCUS_DOWN));
        });
    }

    private void startLogcatReader() {
        if (logcatThread != null) {
            return;
        }
        logcatRunning = true;
        logcatThread = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "logcat", "-v", "time",
                        "-s", "CameraService:V", "Camera3-Device:V", "Camera3-Stream:V", "Camera3-Output:V", "camera3:V"
                );
                pb.redirectErrorStream(true);
                logcatProcess = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(logcatProcess.getInputStream()))) {
                    String line;
                    while (logcatRunning && (line = reader.readLine()) != null) {
                        appendLogcatLine(line);
                    }
                }
            } catch (IOException e) {
                appendLog("logcat启动失败: " + e.getMessage());
            }
        }, "LogcatReader");
        logcatThread.start();
    }

    private void stopLogcatReader() {
        logcatRunning = false;
        if (logcatProcess != null) {
            logcatProcess.destroy();
            logcatProcess = null;
        }
        if (logcatThread != null) {
            logcatThread.interrupt();
            logcatThread = null;
        }
    }

    private boolean checkPermissions() {
        for (String permission : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Missing permission: " + permission);
                appendLog("缺少权限: " + permission);
                return false;
            }
        }
        appendLog("权限检查通过");
        return true;
    }

    private void requestPermissions() {
        Log.d(TAG, "Requesting permissions...");
        appendLog("请求权限...");
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (checkPermissions()) {
                appendLog("权限已授予");
                // 权限已授予，但需要等待TextureView准备好
                // 如果TextureView已经准备好，立即初始化摄像头
                if (textureReadyCount == 4) {
                    initCamera();
                }
            } else {
                appendLog("权限被拒绝,应用退出");
                Toast.makeText(this, "需要相机和存储权限", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void initCamera() {
        // 确保所有TextureView都准备好
        if (textureReadyCount < 4) {
            Log.w(TAG, "Not all TextureViews are ready yet: " + textureReadyCount + "/4");
            appendLog("TextureView未就绪: " + textureReadyCount + "/4");
            return;
        }

        appendLog("开始初始化摄像头...");
        if (!logcatStarted) {
            logcatStarted = true;
            startLogcatReader();
            appendLog("logcat reader started");
        }
        cameraManager = new MultiCameraManager(this);
        cameraManager.setMaxOpenCameras(4);
        appendLog("open all 4 cameras");

        // 设置摄像头状态回调
        cameraManager.setStatusCallback((cameraId, status) -> {
            appendLog("摄像头 " + cameraId + ": " + status);

            // 如果摄像头断开或被占用，提示用户
            if (status.contains("错误") || status.contains("断开")) {
                runOnUiThread(() -> {
                    if (status.contains("ERROR_CAMERA_IN_USE") || status.contains("DISCONNECTED")) {
                        Toast.makeText(MainActivity.this,
                            "摄像头 " + cameraId + " 被占用，正在自动重连...",
                            Toast.LENGTH_SHORT).show();
                    } else if (status.contains("max reconnect attempts")) {
                        Toast.makeText(MainActivity.this,
                            "摄像头 " + cameraId + " 重连失败，请手动重启应用",
                            Toast.LENGTH_LONG).show();
                    }
                });
            } else if (status.contains("已打开") || status.contains("预览已启动")) {
                // 摄像头恢复正常
                runOnUiThread(() -> {
                    // 可以在这里添加恢复提示，但为了避免过多提示，暂时注释
                    // Toast.makeText(MainActivity.this, "摄像头 " + cameraId + " 已恢复", Toast.LENGTH_SHORT).show();
                });
            }
        });

        // 设置预览尺寸回调
        cameraManager.setPreviewSizeCallback((cameraKey, cameraId, previewSize) -> {
            appendLog("摄像头 " + cameraId + " 预览尺寸: " + previewSize.getWidth() + "x" + previewSize.getHeight());
            // 根据 camera key 设置对应 TextureView 的宽高比
            runOnUiThread(() -> {
                final AutoFitTextureView textureView;
                switch (cameraKey) {
                    case "front":
                        textureView = textureFront;
                        break;
                    case "back":
                        textureView = textureBack;
                        break;
                    case "left":
                        textureView = textureLeft;
                        break;
                    case "right":
                        textureView = textureRight;
                        break;
                    default:
                        textureView = null;
                        break;
                }
                if (textureView != null) {
                    // 判断是否需要旋转
                    boolean needRotation = "left".equals(cameraKey) || "right".equals(cameraKey);

                    if (needRotation) {
                        // 左右摄像头：容器使用旋转后的宽高比（800x1280，竖向）
                        textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
                        appendLog("设置 " + cameraKey + " 宽高比(旋转后): " + previewSize.getHeight() + ":" + previewSize.getWidth());

                        // 应用旋转变换
                        int rotation = "left".equals(cameraKey) ? 270 : 90;  // 左逆时针90度(270)，右顺时针90度(90)
                        applyRotationTransform(textureView, previewSize, rotation, cameraKey);
                    } else {
                        // 前后摄像头：使用原始宽高比（1280x800，横向）
                        textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
                        appendLog("设置 " + cameraKey + " 宽高比: " + previewSize.getWidth() + ":" + previewSize.getHeight());
                    }
                }
            });
        });

        // 等待TextureView准备好
        textureFront.post(() -> {
            try {
                // 检测可用的摄像头
                CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                String[] cameraIds = cm.getCameraIdList();

                Log.d(TAG, "Available cameras: " + cameraIds.length);
                appendLog("检测到 " + cameraIds.length + " 个摄像头");

                for (String id : cameraIds) {
                    Log.d(TAG, "Camera ID: " + id);
                    appendLog("摄像头ID: " + id);
                }

                // 根据可用摄像头数量初始化
                if (cameraIds.length >= 4) {
                    // 有4个或更多摄像头
                    // 修正摄像头位置映射：前=cameraIds[2], 后=cameraIds[1], 左=cameraIds[0], 右=cameraIds[3]
                    appendLog("使用4路摄像头模式");
                    cameraManager.initCameras(
                            cameraIds[2], textureFront,  // 前摄像头使用 cameraIds[2]
                            cameraIds[1], textureBack,   // 后摄像头使用 cameraIds[1]
                            cameraIds[0], textureLeft,   // 左摄像头使用 cameraIds[0]
                            cameraIds[3], textureRight   // 右摄像头使用 cameraIds[3]
                    );
                } else if (cameraIds.length >= 2) {
                    // 只有2个摄像头，使用前两个位置
                    appendLog("使用2路摄像头模式(复用显示)");
                    cameraManager.initCameras(
                            cameraIds[0], textureFront,
                            cameraIds[1], textureBack,
                            cameraIds[0], textureLeft,  // 复用第一个
                            cameraIds[1], textureRight  // 复用第二个


                    );
                } else if (cameraIds.length == 1) {
                    // 只有1个摄像头，所有位置使用同一个
                    appendLog("使用1路摄像头模式(复用显示)");
                    cameraManager.initCameras(
                            cameraIds[0], textureFront,
                            cameraIds[0], textureBack,
                            cameraIds[0], textureLeft,
                            cameraIds[0], textureRight
                    );
                } else {
                    appendLog("错误: 没有可用的摄像头");
                    Toast.makeText(this, "没有可用的摄像头", Toast.LENGTH_SHORT).show();
                    return;
                }

                // 打开所有摄像头
                appendLog("正在打开摄像头...");
                cameraManager.openAllCameras();

                Log.d(TAG, "Camera initialized with " + cameraIds.length + " cameras");
                appendLog("摄像头初始化完成");
                Toast.makeText(this, "已打开 " + cameraIds.length + " 个摄像头", Toast.LENGTH_SHORT).show();

            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed to access camera", e);
                appendLog("摄像头访问失败: " + e.getMessage());
                Toast.makeText(this, "摄像头访问失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 对 TextureView 应用旋转变换 (修正版 - 解决变形问题)
     * @param textureView 要旋转的 TextureView
     * @param previewSize 预览尺寸（原始的 1280x800）
     * @param rotation 旋转角度（90 或 270）
     * @param cameraKey 摄像头标识
     */
    private void applyRotationTransform(AutoFitTextureView textureView, android.util.Size previewSize,
                                        int rotation, String cameraKey) {
        // 延迟执行，确保 TextureView 已经完成布局
        textureView.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();

            if (viewWidth == 0 || viewHeight == 0) {
                appendLog(cameraKey + " TextureView 尺寸为0，延迟应用旋转");
                // 如果视图还没有尺寸，再次延迟
                textureView.postDelayed(() -> applyRotationTransform(textureView, previewSize, rotation, cameraKey), 100);
                return;
            }

            android.graphics.Matrix matrix = new android.graphics.Matrix();
            android.graphics.RectF viewRect = new android.graphics.RectF(0, 0, viewWidth, viewHeight);
            
            // 缓冲区矩形，使用 float 精度
            android.graphics.RectF bufferRect = new android.graphics.RectF(0, 0, previewSize.getWidth(), previewSize.getHeight());

            float centerX = viewRect.centerX();
            float centerY = viewRect.centerY();

            if (rotation == 90 || rotation == 270) {
                // 1. 将 bufferRect 中心移动到 viewRect 中心
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
                
                // 2. 将 buffer 映射到 view，这一步会处理拉伸校正
                matrix.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.FILL);
                
                // 3. 计算缩放比例以填满屏幕 (Center Crop)
                // 因为旋转了 90 度，所以 viewHeight 对应 previewWidth，viewWidth 对应 previewHeight
                float scale = Math.max(
                        (float) viewHeight / previewSize.getWidth(),
                        (float) viewWidth / previewSize.getHeight());
                
                // 4. 应用缩放
                matrix.postScale(scale, scale, centerX, centerY);
                
                // 5. 应用旋转
                matrix.postRotate(rotation, centerX, centerY);
            } else if (android.view.Surface.ROTATION_180 == rotation) {
                // 如果需要处理 180 度翻转
                matrix.postRotate(180, centerX, centerY);
            }

            textureView.setTransform(matrix);
            appendLog(cameraKey + " 应用修正旋转: " + rotation + "度");
        });
    }

    private void startRecording() {
        if (cameraManager != null && !cameraManager.isRecording()) {
            appendLog("开始录制...");
            appendLog("提示: 视频将自动分段，每段1分钟");
            boolean success = cameraManager.startRecording();
            if (success) {
                btnStartRecord.setEnabled(false);
                btnStopRecord.setEnabled(true);
                appendLog("录制已开始（自动分段模式）");
                Toast.makeText(this, "开始录制（每1分钟自动分段）", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Recording started");
            } else {
                appendLog("录制失败");
                Toast.makeText(this, "录制失败", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void stopRecording() {
        if (cameraManager != null) {
            appendLog("停止录制...");
            cameraManager.stopRecording();
            btnStartRecord.setEnabled(true);
            btnStopRecord.setEnabled(false);
            appendLog("录制已停止");
            Toast.makeText(this, "录制已停止", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Recording stopped");
        }
    }

    /**
     * 远程录制（由钉钉指令触发）
     * 自动录制 1 分钟视频并上传到钉钉
     */
    public void startRemoteRecording(String conversationId, String userId) {
        this.remoteConversationId = conversationId;
        this.remoteUserId = userId;

        appendLog("收到远程录制指令，开始录制 1 分钟视频...");

        // 如果正在录制，先停止
        if (cameraManager != null && cameraManager.isRecording()) {
            cameraManager.stopRecording();
            try {
                Thread.sleep(500);  // 等待停止完成
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // 开始录制
        if (cameraManager != null) {
            boolean success = cameraManager.startRecording();
            if (success) {
                appendLog("远程录制已开始");

                // 设置 1 分钟后自动停止
                autoStopRunnable = () -> {
                    appendLog("1 分钟录制完成，正在停止...");
                    cameraManager.stopRecording();

                    // 等待录制完全停止
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        uploadRecordedVideos();
                    }, 1000);
                };

                autoStopHandler.postDelayed(autoStopRunnable, 60 * 1000);  // 60 秒
            } else {
                appendLog("远程录制启动失败");
                sendErrorToRemote("录制启动失败");
            }
        } else {
            appendLog("摄像头未初始化");
            sendErrorToRemote("摄像头未初始化");
        }
    }

    /**
     * 上传录制的视频到钉钉
     */
    private void uploadRecordedVideos() {
        appendLog("开始上传视频到钉钉...");

        // 获取录制的视频文件
        File videoDir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM), "MultiCam");

        if (!videoDir.exists() || !videoDir.isDirectory()) {
            appendLog("视频目录不存在");
            sendErrorToRemote("视频目录不存在");
            return;
        }

        // 获取最新的视频文件（最近 1 分钟内创建的）
        File[] files = videoDir.listFiles((dir, name) -> name.endsWith(".mp4"));
        if (files == null || files.length == 0) {
            appendLog("没有找到视频文件");
            sendErrorToRemote("没有找到视频文件");
            return;
        }

        // 筛选最近 1 分钟内的文件
        long currentTime = System.currentTimeMillis();
        List<File> recentFiles = new ArrayList<>();
        for (File file : files) {
            if (currentTime - file.lastModified() < 90 * 1000) {  // 90 秒内
                recentFiles.add(file);
            }
        }

        if (recentFiles.isEmpty()) {
            appendLog("没有找到最近录制的视频");
            sendErrorToRemote("没有找到最近录制的视频");
            return;
        }

        appendLog("找到 " + recentFiles.size() + " 个视频文件");

        // 获取 RemoteViewFragment 中的 API 客户端
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment fragment = fragmentManager.findFragmentById(R.id.fragment_container);

        if (fragment instanceof RemoteViewFragment) {
            RemoteViewFragment remoteFragment = (RemoteViewFragment) fragment;
            DingTalkApiClient apiClient = remoteFragment.getApiClient();

            if (apiClient != null && remoteConversationId != null) {
                VideoUploadService uploadService = new VideoUploadService(this, apiClient);
                uploadService.uploadVideos(recentFiles, remoteConversationId, remoteUserId, new VideoUploadService.UploadCallback() {
                    @Override
                    public void onProgress(String message) {
                        appendLog(message);
                    }

                    @Override
                    public void onSuccess(String message) {
                        appendLog(message);
                        Toast.makeText(MainActivity.this, "视频上传成功", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String error) {
                        appendLog("上传失败: " + error);
                        sendErrorToRemote("上传失败: " + error);
                    }
                });
            } else {
                appendLog("API 客户端未初始化");
                sendErrorToRemote("API 客户端未初始化");
            }
        } else {
            appendLog("RemoteViewFragment 未找到");
            sendErrorToRemote("RemoteViewFragment 未找到");
        }
    }

    /**
     * 发送错误消息到钉钉
     */
    private void sendErrorToRemote(String error) {
        if (remoteConversationId == null) {
            return;
        }

        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment fragment = fragmentManager.findFragmentById(R.id.fragment_container);

        if (fragment instanceof RemoteViewFragment) {
            RemoteViewFragment remoteFragment = (RemoteViewFragment) fragment;
            DingTalkApiClient apiClient = remoteFragment.getApiClient();

            if (apiClient != null) {
                new Thread(() -> {
                    try {
                        apiClient.sendTextMessage(remoteConversationId, "录制失败: " + error);
                        Log.d(TAG, "错误消息已发送到钉钉");
                    } catch (Exception e) {
                        Log.e(TAG, "发送错误消息失败", e);
                    }
                }).start();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopLogcatReader();

        // 取消自动停止录制的任务
        if (autoStopHandler != null && autoStopRunnable != null) {
            autoStopHandler.removeCallbacks(autoStopRunnable);
        }

        if (cameraManager != null) {
            cameraManager.release();
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }
}