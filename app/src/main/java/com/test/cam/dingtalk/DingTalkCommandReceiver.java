package com.test.cam.dingtalk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * 钉钉指令接收器
 * 负责解析和处理从钉钉接收到的指令
 */
public class DingTalkCommandReceiver implements DingTalkStreamClient.MessageCallback {
    private static final String TAG = "DingTalkCommandReceiver";

    private final Context context;
    private final DingTalkApiClient apiClient;
    private final CommandListener listener;
    private final Handler mainHandler;

    public interface CommandListener {
        void onRecordCommand(String conversationId, String userId);
        void onConnectionStatusChanged(boolean connected);
    }

    public DingTalkCommandReceiver(Context context, DingTalkApiClient apiClient, CommandListener listener) {
        this.context = context;
        this.apiClient = apiClient;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onConnected() {
        Log.d(TAG, "Stream 连接已建立");
        mainHandler.post(() -> listener.onConnectionStatusChanged(true));
    }

    @Override
    public void onDisconnected() {
        Log.d(TAG, "Stream 连接已断开");
        mainHandler.post(() -> listener.onConnectionStatusChanged(false));
    }

    @Override
    public void onMessageReceived(String conversationId, String senderUserId, String text) {
        Log.d(TAG, "收到消息: " + text + " from " + senderUserId);

        // 解析指令
        String command = parseCommand(text);

        if ("录制".equals(command) || "record".equalsIgnoreCase(command)) {
            Log.d(TAG, "收到录制指令");

            // 发送确认消息
            sendResponse(conversationId, "收到录制指令，开始录制 1 分钟视频...");

            // 通知监听器执行录制，传递 senderUserId
            mainHandler.post(() -> listener.onRecordCommand(conversationId, senderUserId));
        } else {
            Log.d(TAG, "未识别的指令: " + command);
            sendResponse(conversationId, "未识别的指令。请发送「录制」开始录制视频。");
        }
    }

    @Override
    public void onError(String error) {
        Log.e(TAG, "错误: " + error);
    }

    /**
     * 解析指令文本
     * 移除 @机器人 的部分，提取实际指令
     */
    private String parseCommand(String text) {
        if (text == null) {
            return "";
        }

        // 移除 @xxx 部分
        String command = text.replaceAll("@\\S+\\s*", "").trim();
        return command;
    }

    /**
     * 发送响应消息到钉钉
     */
    public void sendResponse(String conversationId, String message) {
        new Thread(() -> {
            try {
                apiClient.sendTextMessage(conversationId, message);
                Log.d(TAG, "响应消息已发送: " + message);
            } catch (Exception e) {
                Log.e(TAG, "发送响应消息失败", e);
            }
        }).start();
    }
}
