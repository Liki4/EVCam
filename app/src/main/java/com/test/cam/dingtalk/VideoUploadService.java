package com.test.cam.dingtalk;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频上传服务
 * 负责将录制的视频上传到钉钉
 */
public class VideoUploadService {
    private static final String TAG = "VideoUploadService";

    private final Context context;
    private final DingTalkApiClient apiClient;

    public interface UploadCallback {
        void onProgress(String message);
        void onSuccess(String message);
        void onError(String error);
    }

    public VideoUploadService(Context context, DingTalkApiClient apiClient) {
        this.context = context;
        this.apiClient = apiClient;
    }

    /**
     * 上传视频文件到钉钉
     * @param videoFiles 视频文件列表
     * @param conversationId 钉钉会话 ID
     * @param userId 钉钉用户 ID（用于发送文件消息）
     * @param callback 上传回调
     */
    public void uploadVideos(List<File> videoFiles, String conversationId, String userId, UploadCallback callback) {
        new Thread(() -> {
            try {
                if (videoFiles == null || videoFiles.isEmpty()) {
                    callback.onError("没有视频文件可上传");
                    return;
                }

                callback.onProgress("开始上传 " + videoFiles.size() + " 个视频文件...");

                List<String> uploadedFiles = new ArrayList<>();

                for (int i = 0; i < videoFiles.size(); i++) {
                    File videoFile = videoFiles.get(i);

                    if (!videoFile.exists()) {
                        Log.w(TAG, "视频文件不存在: " + videoFile.getPath());
                        continue;
                    }

                    callback.onProgress("正在上传 (" + (i + 1) + "/" + videoFiles.size() + "): " + videoFile.getName());

                    try {
                        // 上传文件到钉钉
                        String mediaId = apiClient.uploadFile(videoFile);

                        // 发送文件消息到群聊，传递 userId
                        apiClient.sendFileMessage(conversationId, mediaId, videoFile.getName(), userId);

                        uploadedFiles.add(videoFile.getName());
                        Log.d(TAG, "视频上传成功: " + videoFile.getName());

                    } catch (Exception e) {
                        Log.e(TAG, "上传视频失败: " + videoFile.getName(), e);
                        callback.onError("上传失败: " + videoFile.getName() + " - " + e.getMessage());
                    }
                }

                if (uploadedFiles.isEmpty()) {
                    callback.onError("所有视频上传失败");
                } else {
                    String successMessage = "视频上传完成！共上传 " + uploadedFiles.size() + " 个文件";
                    callback.onSuccess(successMessage);

                    // 发送完成消息
                    apiClient.sendTextMessage(conversationId, successMessage);
                }

            } catch (Exception e) {
                Log.e(TAG, "上传过程出错", e);
                callback.onError("上传过程出错: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 上传单个视频文件
     */
    public void uploadVideo(File videoFile, String conversationId, String userId, UploadCallback callback) {
        List<File> files = new ArrayList<>();
        files.add(videoFile);
        uploadVideos(files, conversationId, userId, callback);
    }
}
