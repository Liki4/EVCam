package com.kooo.evcam.playback;

import android.content.Context;
import android.graphics.Matrix;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.TextureView;
import android.view.Surface;

import com.kooo.evcam.AppConfig;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 多路视频同步播放管理器
 * 支持1-4路视频同时播放，并保持同步
 */
public class MultiVideoPlayerManager {
    private static final String TAG = "MultiVideoPlayerManager";

    /** 支持的倍速 */
    public static final float[] SPEED_OPTIONS = {0.5f, 1.0f, 1.5f, 2.0f};
    
    private final Context context;
    private final Handler handler;

    /** 各位置的TextureView */
    private TextureView videoFront;
    private TextureView videoBack;
    private TextureView videoLeft;
    private TextureView videoRight;
    private TextureView videoSingle;  // 单路模式用
    
    /** 各位置的MediaPlayer */
    private final Map<String, MediaPlayer> textureMediaPlayers = new HashMap<>();

    /** 各位置的MediaPlayer引用（用于倍速控制） */
    private final Map<String, MediaPlayer> mediaPlayers = new HashMap<>();

    /** 当前加载的视频组 */
    private VideoGroup currentGroup;

    /** 播放状态 */
    private boolean isPlaying = false;
    private boolean isPrepared = false;
    private int preparedCount = 0;
    private int totalVideos = 0;

    /** 当前倍速 */
    private float currentSpeed = 1.0f;
    private int currentSpeedIndex = 1;  // 默认1.0x

    /** 是否单路模式 */
    private boolean isSingleMode = false;
    private String singleModePosition = VideoGroup.POSITION_FULL;

    /** 视频时长（毫秒） */
    private int duration = 0;
    
    /** Seek同步相关 */
    private int pendingSeekCount = 0;  // 待完成的seek操作数
    private int completedSeekCount = 0;  // 已完成的seek操作数
    private Runnable pendingSeekCallback = null;  // seek完成后的回调

    /** 播放状态监听器 */
    private OnPlaybackListener playbackListener;

    public interface OnPlaybackListener {
        void onPrepared(int duration);
        void onProgressUpdate(int currentPosition);
        void onPlaybackStateChanged(boolean isPlaying);
        void onCompletion();
        void onError(String message);
        /** 单路视频准备好时回调（用于控制 UI 显示） */
        default void onSingleVideoPrepared() {}
    }

    public MultiVideoPlayerManager(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
    }

    /**
     * 设置TextureView引用
     */
    public void setVideoViews(TextureView front, TextureView back, TextureView left, TextureView right, TextureView single) {
        this.videoFront = front;
        this.videoBack = back;
        this.videoLeft = left;
        this.videoRight = right;
        this.videoSingle = single;
    }

    /**
     * 设置播放监听器
     */
    public void setPlaybackListener(OnPlaybackListener listener) {
        this.playbackListener = listener;
    }

    /**
     * 加载视频组
     */
    public void loadVideoGroup(VideoGroup group) {
        // 停止当前播放
        stopAll();

        this.currentGroup = group;
        this.isPrepared = false;
        this.preparedCount = 0;
        this.totalVideos = 0;
        this.duration = 0;
        this.mediaPlayers.clear();

        if (group == null) {
            return;
        }

        // 检查是否是领克07模式且有full视频
        AppConfig appConfig = new AppConfig(context);
        boolean isLynkco07 = AppConfig.CAR_MODEL_LYNKCO_07.equals(appConfig.getCarModel());
        boolean hasFull = group.hasVideo(VideoGroup.POSITION_FULL);

        if (isLynkco07 && hasFull) {
            // 领克07模式：从full视频裁切显示
            File fullVideo = group.getFullVideo();
            totalVideos = 4; // 四个方向都从full视频裁切
            
            loadCroppedVideo(VideoGroup.POSITION_FRONT, fullVideo, videoFront);
            loadCroppedVideo(VideoGroup.POSITION_BACK, fullVideo, videoBack);
            loadCroppedVideo(VideoGroup.POSITION_LEFT, fullVideo, videoLeft);
            loadCroppedVideo(VideoGroup.POSITION_RIGHT, fullVideo, videoRight);
        } else {
            // 非领克07模式或没有full视频：使用原有逻辑
            // 统计要加载的视频数量
            if (group.hasVideo(VideoGroup.POSITION_FRONT)) totalVideos++;
            if (group.hasVideo(VideoGroup.POSITION_BACK)) totalVideos++;
            if (group.hasVideo(VideoGroup.POSITION_LEFT)) totalVideos++;
            if (group.hasVideo(VideoGroup.POSITION_RIGHT)) totalVideos++;

            if (totalVideos == 0) {
                if (playbackListener != null) {
                    playbackListener.onError("No video files in this group");
                }
                return;
            }

            // 加载各位置视频
            loadVideoIfExists(VideoGroup.POSITION_FRONT, group.getFrontVideo(), videoFront);
            loadVideoIfExists(VideoGroup.POSITION_BACK, group.getBackVideo(), videoBack);
            loadVideoIfExists(VideoGroup.POSITION_LEFT, group.getLeftVideo(), videoLeft);
            loadVideoIfExists(VideoGroup.POSITION_RIGHT, group.getRightVideo(), videoRight);
        }
        
        // 如果是单路模式，也加载单路视频
        if (isSingleMode && videoSingle != null) {
            loadSingleModeVideo(0, false);
        }
    }

    /**
     * 加载单个视频到TextureView
     */
    private void loadVideoIfExists(String position, File videoFile, TextureView textureView) {
        if (videoFile == null || !videoFile.exists() || textureView == null) {
            return;
        }

        try {
            // 创建MediaPlayer
            MediaPlayer mediaPlayer = new MediaPlayer();
            Uri uri = Uri.fromFile(videoFile);
            mediaPlayer.setDataSource(context, uri);
            
            // 设置Surface
            TextureView.SurfaceTextureListener listener = new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width, int height) {
                    try {
                        mediaPlayer.setSurface(new Surface(surface));
                        mediaPlayer.prepareAsync();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to set surface for " + position, e);
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width, int height) {
                    handler.postDelayed(() -> {
                        applyCropTransformIfNeeded(position, textureView, mediaPlayer);
                    }, 100);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
                    return false;
                }

                @Override
                public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {
                }
            };
            textureView.setSurfaceTextureListener(listener);

            // 若 Surface 已可用（如切换视频时复用 View），立即绑定，否则不会再次回调 onSurfaceTextureAvailable
            if (textureView.isAvailable()) {
                android.graphics.SurfaceTexture st = textureView.getSurfaceTexture();
                if (st != null) {
                    try {
                        mediaPlayer.setSurface(new Surface(st));
                        mediaPlayer.prepareAsync();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to set surface (already available) for " + position, e);
                    }
                }
            }

            mediaPlayer.setOnPreparedListener(mp -> {
                Log.d(TAG, "Video prepared: " + position);
                mediaPlayers.put(position, mp);
                textureMediaPlayers.put(position, mp);

                // 行车记录仪视频没有声音，设置静音
                mp.setVolume(0f, 0f);

                // 记录最长时长
                int videoDuration = mp.getDuration();
                if (videoDuration > duration) {
                    duration = videoDuration;
                }

                // 设置倍速
                setMediaPlayerSpeed(mp, currentSpeed);
                
                // 应用裁切变换（如果是领克07模式）
                // 延迟应用，确保视频尺寸已获取
                handler.postDelayed(() -> {
                    applyCropTransformIfNeeded(position, textureView, mp);
                }, 100);

                preparedCount++;
                checkAllPrepared();
            });

            mediaPlayer.setOnCompletionListener(mp -> {
                // 所有视频播放完成
                isPlaying = false;
                if (playbackListener != null) {
                    playbackListener.onPlaybackStateChanged(false);
                    playbackListener.onCompletion();
                }
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Video error: " + position + ", what=" + what + ", extra=" + extra);
                return true;
            });

            // 视频尺寸已知时设置 Surface 缓冲大小并应用裁切（部分设备 onPrepared 时 getVideoWidth/Height 仍为 0）
            mediaPlayer.setOnVideoSizeChangedListener((mp, width, height) -> {
                if (width <= 0 || height <= 0) return;
                android.graphics.SurfaceTexture st = textureView.getSurfaceTexture();
                if (st != null) {
                    try {
                        st.setDefaultBufferSize(width, height);
                    } catch (Exception e) {
                        Log.w(TAG, "setDefaultBufferSize failed for " + position, e);
                    }
                }
                handler.postDelayed(() -> {
                    applyCropTransformIfNeeded(position, textureView, mp);
                }, 50);
            });

        } catch (Exception e) {
            Log.e(TAG, "Failed to load video: " + position, e);
        }
    }
    
    /**
     * 如果需要，应用裁切变换（领克07模式）
     * 带重试机制，确保裁切变换正确应用
     */
    private void applyCropTransformIfNeeded(String position, TextureView textureView, MediaPlayer mediaPlayer) {
        applyCropTransformIfNeeded(position, textureView, mediaPlayer, 0);
    }
    
    /**
     * 如果需要，应用裁切变换（领克07模式）
     * @param position 摄像头位置
     * @param textureView TextureView
     * @param mediaPlayer MediaPlayer
     * @param retryCount 重试次数（最多重试3次）
     */
    private void applyCropTransformIfNeeded(String position, TextureView textureView, MediaPlayer mediaPlayer, int retryCount) {
        AppConfig appConfig = new AppConfig(context);
        boolean isLynkco07 = AppConfig.CAR_MODEL_LYNKCO_07.equals(appConfig.getCarModel());
        
        if (!isLynkco07 || currentGroup == null || !currentGroup.hasVideo(VideoGroup.POSITION_FULL)) {
            return;
        }
        
        // 获取视频尺寸
        int videoWidth = mediaPlayer.getVideoWidth();
        int videoHeight = mediaPlayer.getVideoHeight();
        
        // 如果视频尺寸未获取到，延迟重试（最多3次）
        if (videoWidth <= 0 || videoHeight <= 0) {
            if (retryCount < 3) {
                handler.postDelayed(() -> {
                    applyCropTransformIfNeeded(position, textureView, mediaPlayer, retryCount + 1);
                }, 200);
            }
            return;
        }
        
        // 获取裁切区域
        float[] cropRegion = AppConfig.getPanoramicCropRegion(position);
        if (cropRegion == null || cropRegion.length < 4) {
            return;
        }
        
        // 应用变换：TextureView.setTransform 的 Matrix 作用在纹理的归一化坐标系 (0,0)-(1,1)
        // 将裁切区域 [cropX, cropY, cropW, cropH] 映射为满屏，即 scale=1/cropW, 1/cropH，再平移使 (cropX,cropY)->(0,0)
        textureView.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();
            if (viewWidth == 0 || viewHeight == 0) {
                if (retryCount < 3) {
                    handler.postDelayed(() -> {
                        applyCropTransformIfNeeded(position, textureView, mediaPlayer, retryCount + 1);
                    }, 200);
                }
                return;
            }

            float cropX = cropRegion[0];
            float cropY = cropRegion[1];
            float cropW = cropRegion[2];
            float cropH = cropRegion[3];
            if (cropW <= 0 || cropH <= 0) {
                return;
            }
            float scaleX = 1f / cropW;
            float scaleY = 1f / cropH;
            float translateX = -cropX * scaleX * viewWidth;
            float translateY = -cropY * scaleY * viewHeight;

            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.setScale(scaleX, scaleY);
            matrix.postTranslate(translateX, translateY);
            textureView.setTransform(matrix);

            Log.d(TAG, position + " applied crop transform (normalized): view=" + viewWidth + "x" + viewHeight +
                    ", cropRegion=[" + cropRegion[0] + "," + cropRegion[1] + "," + cropRegion[2] + "," + cropRegion[3] + "]" +
                    ", scale=" + scaleX + "," + scaleY + ", mediaPlayer=" + mediaPlayer.hashCode() + ", textureView=" + textureView.hashCode());
        });
    }

    /**
     * 从full视频裁切并加载到TextureView
     * 使用TextureView + MediaPlayer，通过Matrix变换实现裁切
     */
    private void loadCroppedVideo(String position, File fullVideoFile, TextureView textureView) {
        if (fullVideoFile == null || !fullVideoFile.exists() || textureView == null) {
            return;
        }

        // 使用和loadVideoIfExists相同的逻辑
        // 裁切效果通过applyCropTransformIfNeeded方法实现
        loadVideoIfExists(position, fullVideoFile, textureView);
    }

    /**
     * 检查是否所有视频都准备好了
     */
    private void checkAllPrepared() {
        if (preparedCount >= totalVideos) {
            isPrepared = true;
            Log.d(TAG, "All videos prepared, duration=" + duration);
            
            // 放弃音频焦点，让其他应用（如音乐播放器）继续播放
            abandonAudioFocus();
            
            if (playbackListener != null) {
                playbackListener.onPrepared(duration);
            }
            // 自动开始播放
            play();
        }
    }

    /**
     * 开始播放
     */
    public void play() {
        if (!isPrepared) {
            return;
        }

        isPlaying = true;

        if (isSingleMode) {
            // 单路模式播放
            MediaPlayer singleMp = textureMediaPlayers.get("single");
            if (singleMp != null) {
                singleMp.start();
            }
        } else {
            // 多路模式播放所有
            MediaPlayer frontMp = textureMediaPlayers.get(VideoGroup.POSITION_FRONT);
            MediaPlayer backMp = textureMediaPlayers.get(VideoGroup.POSITION_BACK);
            MediaPlayer leftMp = textureMediaPlayers.get(VideoGroup.POSITION_LEFT);
            MediaPlayer rightMp = textureMediaPlayers.get(VideoGroup.POSITION_RIGHT);
            
            if (frontMp != null) frontMp.start();
            if (backMp != null) backMp.start();
            if (leftMp != null) leftMp.start();
            if (rightMp != null) rightMp.start();
        }

        if (playbackListener != null) {
            playbackListener.onPlaybackStateChanged(true);
        }

        // 开始更新进度
        startProgressUpdate();
    }

    /**
     * 暂停播放
     */
    public void pause() {
        isPlaying = false;

        for (MediaPlayer mp : textureMediaPlayers.values()) {
            if (mp != null && mp.isPlaying()) {
                mp.pause();
            }
        }

        if (playbackListener != null) {
            playbackListener.onPlaybackStateChanged(false);
        }
    }

    /**
     * 切换播放/暂停
     */
    public void togglePlayPause() {
        if (isPlaying) {
            pause();
        } else {
            play();
        }
    }

    /**
     * 停止所有播放
     */
    public void stopAll() {
        isPlaying = false;
        isPrepared = false;
        handler.removeCallbacksAndMessages(null);

        try {
            for (MediaPlayer mp : textureMediaPlayers.values()) {
                if (mp != null) {
                    mp.stop();
                    mp.release();
                }
            }
            textureMediaPlayers.clear();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping playback", e);
        }

        mediaPlayers.clear();
    }

    /**
     * 跳转到指定位置（同步所有视频）
     */
    public void seekTo(int position) {
        if (!isPrepared) return;

        if (isSingleMode) {
            // 单路模式
            MediaPlayer singleMp = textureMediaPlayers.get("single");
            if (singleMp != null) {
                singleMp.seekTo(position);
            }
        } else {
            // 多路模式：同步seek所有视频
            seekToSync(position, null);
        }
    }
    
    /**
     * 同步seek到指定位置（等待所有MediaPlayer完成seek）
     * @param position 目标位置（毫秒）
     * @param callback seek完成后的回调（可为null）
     */
    private void seekToSync(int position, Runnable callback) {
        // 收集所有需要seek的MediaPlayer
        java.util.List<MediaPlayer> playersToSeek = new java.util.ArrayList<>();
        MediaPlayer frontMp = textureMediaPlayers.get(VideoGroup.POSITION_FRONT);
        MediaPlayer backMp = textureMediaPlayers.get(VideoGroup.POSITION_BACK);
        MediaPlayer leftMp = textureMediaPlayers.get(VideoGroup.POSITION_LEFT);
        MediaPlayer rightMp = textureMediaPlayers.get(VideoGroup.POSITION_RIGHT);
        
        if (frontMp != null) playersToSeek.add(frontMp);
        if (backMp != null) playersToSeek.add(backMp);
        if (leftMp != null) playersToSeek.add(leftMp);
        if (rightMp != null) playersToSeek.add(rightMp);
        
        if (playersToSeek.isEmpty()) {
            if (callback != null) callback.run();
            return;
        }
        
        // 重置计数器
        pendingSeekCount = playersToSeek.size();
        completedSeekCount = 0;
        pendingSeekCallback = callback;
        
        // 为每个MediaPlayer设置OnSeekCompleteListener
        MediaPlayer.OnSeekCompleteListener seekListener = (mp) -> {
            completedSeekCount++;
            // 所有seek完成后执行回调
            if (completedSeekCount >= pendingSeekCount) {
                if (pendingSeekCallback != null) {
                    handler.post(pendingSeekCallback);
                    pendingSeekCallback = null;
                }
            }
        };
        
        // 对所有MediaPlayer执行seek
        for (MediaPlayer mp : playersToSeek) {
            mp.setOnSeekCompleteListener(seekListener);
            mp.seekTo(position);
        }
    }

    /**
     * 获取当前播放位置
     * 多路模式下返回所有视频位置的最小值，确保所有视频都已播放到该位置
     */
    public int getCurrentPosition() {
        // 返回当前播放视频的位置
        if (isSingleMode) {
            // 单路模式下优先从 single MediaPlayer 获取位置
            MediaPlayer singleMp = textureMediaPlayers.get("single");
            if (singleMp != null) {
                try {
                    int pos = singleMp.getCurrentPosition();
                    if (pos > 0) {
                        return pos;
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
            // 后备：从四宫格中对应位置的MediaPlayer获取
            MediaPlayer sourceMp = getSingleModeMediaPlayer();
            if (sourceMp != null) {
                try {
                    return sourceMp.getCurrentPosition();
                } catch (Exception e) {
                    // ignore
                }
            }
        }
        
        // 多路模式：返回所有视频位置的最小值（确保所有视频都已播放到该位置）
        MediaPlayer frontMp = textureMediaPlayers.get(VideoGroup.POSITION_FRONT);
        MediaPlayer backMp = textureMediaPlayers.get(VideoGroup.POSITION_BACK);
        MediaPlayer leftMp = textureMediaPlayers.get(VideoGroup.POSITION_LEFT);
        MediaPlayer rightMp = textureMediaPlayers.get(VideoGroup.POSITION_RIGHT);
        
        int minPosition = Integer.MAX_VALUE;
        boolean hasValidPosition = false;
        
        if (frontMp != null) {
            try {
                int pos = frontMp.getCurrentPosition();
                if (pos > 0) {
                    minPosition = Math.min(minPosition, pos);
                    hasValidPosition = true;
                }
            } catch (Exception e) { /* ignore */ }
        }
        if (backMp != null) {
            try {
                int pos = backMp.getCurrentPosition();
                if (pos > 0) {
                    minPosition = Math.min(minPosition, pos);
                    hasValidPosition = true;
                }
            } catch (Exception e) { /* ignore */ }
        }
        if (leftMp != null) {
            try {
                int pos = leftMp.getCurrentPosition();
                if (pos > 0) {
                    minPosition = Math.min(minPosition, pos);
                    hasValidPosition = true;
                }
            } catch (Exception e) { /* ignore */ }
        }
        if (rightMp != null) {
            try {
                int pos = rightMp.getCurrentPosition();
                if (pos > 0) {
                    minPosition = Math.min(minPosition, pos);
                    hasValidPosition = true;
                }
            } catch (Exception e) { /* ignore */ }
        }
        
        return hasValidPosition ? minPosition : 0;
    }

    /**
     * 获取视频总时长
     */
    public int getDuration() {
        return duration;
    }

    /**
     * 是否正在播放
     */
    public boolean isPlaying() {
        return isPlaying;
    }

    /**
     * 循环切换倍速
     */
    public float cycleSpeed() {
        currentSpeedIndex = (currentSpeedIndex + 1) % SPEED_OPTIONS.length;
        currentSpeed = SPEED_OPTIONS[currentSpeedIndex];
        
        // 应用新倍速到所有播放器
        for (MediaPlayer mp : mediaPlayers.values()) {
            setMediaPlayerSpeed(mp, currentSpeed);
        }
        
        return currentSpeed;
    }

    /**
     * 设置倍速
     */
    public void setSpeed(float speed) {
        currentSpeed = speed;
        for (int i = 0; i < SPEED_OPTIONS.length; i++) {
            if (Math.abs(SPEED_OPTIONS[i] - speed) < 0.01) {
                currentSpeedIndex = i;
                break;
            }
        }
        
        for (MediaPlayer mp : mediaPlayers.values()) {
            setMediaPlayerSpeed(mp, currentSpeed);
        }
    }

    /**
     * 获取当前倍速
     */
    public float getCurrentSpeed() {
        return currentSpeed;
    }

    /**
     * 设置MediaPlayer的播放速度
     */
    private void setMediaPlayerSpeed(MediaPlayer mp, float speed) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(speed));
            } catch (Exception e) {
                Log.e(TAG, "Failed to set playback speed", e);
            }
        }
    }

    /**
     * 放弃音频焦点，让其他应用（如音乐播放器）继续播放
     * 行车记录仪视频没有声音，不需要抢占音频焦点
     */
    private void abandonAudioFocus() {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // API 26+ 使用新的 AudioFocusRequest API
                AudioFocusRequest focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                .build())
                        .build();
                audioManager.abandonAudioFocusRequest(focusRequest);
            } else {
                // 旧版本 API
                audioManager.abandonAudioFocus(null);
            }
        }
    }

    /**
     * 设置单路/多路模式
     */
    public void setSingleMode(boolean singleMode, String position) {
        // 先保存当前播放位置和状态
        int savedPosition = 0;
        boolean wasPlaying = isPlaying;
        
        if (isPrepared && currentGroup != null) {
            savedPosition = getCurrentPosition();
        }
        
        this.isSingleMode = singleMode;
        if (position != null) {
            this.singleModePosition = position;
        }

        // 如果已准备好，需要重新同步
        if (isPrepared && currentGroup != null) {
            if (singleMode) {
                // 切换到单路：先暂停多路视频
                for (MediaPlayer mp : textureMediaPlayers.values()) {
                    if (mp != null && !mp.equals(textureMediaPlayers.get("single"))) {
                        mp.pause();
                    }
                }
                // 将源视频的内容显示到单路TextureView
                loadSingleModeVideo(savedPosition, wasPlaying);
            } else {
                // 切换回多路：暂停单路视频
                MediaPlayer singleMp = textureMediaPlayers.get("single");
                if (singleMp != null) {
                    singleMp.pause();
                }
                // 同步seek到保存的位置，完成后恢复播放状态
                seekToSync(savedPosition, () -> {
                    if (wasPlaying) {
                        play();
                    } else {
                        // 确保所有视频都暂停
                        pause();
                    }
                });
            }
        }
    }

    /**
     * 加载单路模式视频
     */
    private void loadSingleModeVideo(int seekPosition, boolean autoPlay) {
        if (currentGroup == null || videoSingle == null) return;

        // 检查是否是领克07模式且有full视频
        AppConfig appConfig = new AppConfig(context);
        boolean isLynkco07 = AppConfig.CAR_MODEL_LYNKCO_07.equals(appConfig.getCarModel());
        boolean hasFull = currentGroup.hasVideo(VideoGroup.POSITION_FULL);
        
        File videoFile;
        if (isLynkco07 && hasFull) {
            // 领克07模式：使用full视频
            videoFile = currentGroup.getFullVideo();
        } else {
            // 非领克07模式：使用对应位置的视频
            videoFile = currentGroup.getVideoFile(singleModePosition);
        }
        
        if (videoFile != null && videoFile.exists()) {
            try {
                // 创建MediaPlayer
                MediaPlayer mediaPlayer = new MediaPlayer();
                Uri uri = Uri.fromFile(videoFile);
                mediaPlayer.setDataSource(context, uri);
                
                // 设置Surface
                videoSingle.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                    @Override
                    public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width, int height) {
                        try {
                            mediaPlayer.setSurface(new Surface(surface));
                            mediaPlayer.prepareAsync();
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to set surface for single mode", e);
                        }
                    }

                    @Override
                    public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width, int height) {
                        // 应用裁切变换（如果是领克07模式）
                        // 延迟应用，确保MediaPlayer已准备好
                        if (isLynkco07 && hasFull) {
                            handler.postDelayed(() -> {
                                applyCropTransformIfNeeded(singleModePosition, videoSingle, mediaPlayer);
                            }, 100);
                        }
                    }

                    @Override
                    public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
                        return false;
                    }

                    @Override
                    public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {
                    }
                });

                mediaPlayer.setOnPreparedListener(mp -> {
                    mediaPlayers.put("single", mp);
                    textureMediaPlayers.put("single", mp);
                    // 行车记录仪视频没有声音，设置静音
                    mp.setVolume(0f, 0f);
                    setMediaPlayerSpeed(mp, currentSpeed);
                    // 放弃音频焦点
                    abandonAudioFocus();
                    
                    // 应用裁切变换（如果是领克07模式）
                    // 延迟应用，确保视频尺寸已获取
                    if (isLynkco07 && hasFull) {
                        handler.postDelayed(() -> {
                            applyCropTransformIfNeeded(singleModePosition, videoSingle, mp);
                        }, 100);
                    }
                    
                    // 视频准备好后再 seek 和播放
                    mp.seekTo(seekPosition);
                    
                    // 通知 UI 单路视频已准备好（可以显示画面了）
                    if (playbackListener != null) {
                        playbackListener.onSingleVideoPrepared();
                    }
                    
                    if (autoPlay) {
                        mp.start();
                        isPlaying = true;
                        startProgressUpdate();
                        // 通知 UI 更新按钮状态
                        if (playbackListener != null) {
                            playbackListener.onPlaybackStateChanged(true);
                        }
                    } else {
                        // 确保视频暂停（某些设备 seek 后会自动播放）
                        mp.pause();
                        isPlaying = false;
                        // 通知 UI 更新按钮状态
                        if (playbackListener != null) {
                            playbackListener.onPlaybackStateChanged(false);
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load single mode video", e);
            }
        }
    }

    /**
     * 获取单路模式对应的MediaPlayer
     */
    private MediaPlayer getSingleModeMediaPlayer() {
        return textureMediaPlayers.get(singleModePosition);
    }

    /**
     * 是否是单路模式
     */
    public boolean isSingleMode() {
        return isSingleMode;
    }

    /**
     * 获取单路模式的位置
     */
    public String getSingleModePosition() {
        return singleModePosition;
    }

    /**
     * 更新单路模式的位置（不触发视频加载，仅更新状态）
     */
    public void updateSingleModePosition(boolean singleMode, String position) {
        this.isSingleMode = singleMode;
        if (position != null) {
            this.singleModePosition = position;
        }
    }

    /**
     * 开始进度更新
     */
    private void startProgressUpdate() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isPlaying && playbackListener != null) {
                    int position = getCurrentPosition();
                    playbackListener.onProgressUpdate(position);
                }
                if (isPlaying) {
                    handler.postDelayed(this, 200);
                }
            }
        }, 200);
    }

    /**
     * 释放资源
     */
    public void release() {
        stopAll();
        mediaPlayers.clear();
        playbackListener = null;
    }

    /**
     * 检查指定位置是否有视频
     */
    public boolean hasVideo(String position) {
        return currentGroup != null && currentGroup.hasVideo(position);
    }

    /**
     * 获取当前视频组
     */
    public VideoGroup getCurrentGroup() {
        return currentGroup;
    }
}
