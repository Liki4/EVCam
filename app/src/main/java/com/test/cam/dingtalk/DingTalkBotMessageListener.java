package com.test.cam.dingtalk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.models.bot.MessageContent;

/**
 * 钉钉机器人消息回调监听器（使用官方 SDK）
 */
public class DingTalkBotMessageListener implements OpenDingTalkCallbackListener<ChatbotMessage, JSONObject> {
    private static final String TAG = "DingTalkBotListener";

    private final Context context;
    private final DingTalkApiClient apiClient;
    private final CommandCallback callback;
    private final Handler mainHandler;

    public interface CommandCallback {
        void onRecordCommand(String conversationId, String userId);
        void onConnectionStatusChanged(boolean connected);
    }

    public DingTalkBotMessageListener(Context context, DingTalkApiClient apiClient, CommandCallback callback) {
        this.context = context;
        this.apiClient = apiClient;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public JSONObject execute(ChatbotMessage message) {
        try {
            MessageContent text = message.getText();
            if (text != null) {
                String msg = text.getContent();
                String senderId = message.getSenderId();
                String conversationId = message.getConversationId();

                Log.d(TAG, "收到机器人消息 - senderId: " + senderId);
                Log.d(TAG, "收到机器人消息 - conversationId: " + conversationId);
                Log.d(TAG, "收到机器人消息 - text: " + msg);

                // 解析指令
                String command = parseCommand(msg);

                if ("录制".equals(command) || "record".equalsIgnoreCase(command)) {
                    Log.d(TAG, "收到录制指令");

                    // 发送确认消息
                    sendResponse(conversationId, "收到录制指令，开始录制 1 分钟视频...");

                    // 通知监听器执行录制，传递 senderId
                    mainHandler.post(() -> callback.onRecordCommand(conversationId, senderId));
                } else {
                    Log.d(TAG, "未识别的指令: " + command);
                    sendResponse(conversationId, "未识别的指令。请发送「录制」开始录制视频。");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "处理机器人消息失败", e);
        }

        return new JSONObject();
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
