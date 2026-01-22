package com.test.cam.dingtalk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import com.dingtalk.open.app.stream.protocol.event.EventAckStatus;

import org.json.JSONObject;

/**
 * 钉钉 Stream 客户端管理器
 * 使用官方 app-stream-client SDK
 */
public class DingTalkStreamManager {
    private static final String TAG = "DingTalkStreamManager";

    // 钉钉官方事件主题
    private static final String BOT_MESSAGE_TOPIC = "/v1.0/im/bot/messages/get";

    private final Context context;
    private final DingTalkConfig config;
    private final DingTalkApiClient apiClient;
    private final ConnectionCallback callback;
    private final Handler mainHandler;

    private OpenDingTalkClient streamClient;
    private ChatbotMessageListener messageListener;
    private boolean isRunning = false;

    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    public interface CommandCallback {
        void onRecordCommand(String conversationId, String userId);
    }

    public DingTalkStreamManager(Context context, DingTalkConfig config,
                                  DingTalkApiClient apiClient, ConnectionCallback callback) {
        this.context = context;
        this.config = config;
        this.apiClient = apiClient;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 启动 Stream 连接
     */
    public void start(CommandCallback commandCallback) {
        if (isRunning) {
            Log.w(TAG, "Stream 客户端已在运行");
            return;
        }

        new Thread(() -> {
            try {
                Log.d(TAG, "正在初始化钉钉 Stream 客户端...");

                // 创建消息监听器
                messageListener = new ChatbotMessageListener(context, apiClient, commandCallback, mainHandler);

                // 使用官方 SDK 构建客户端
                streamClient = OpenDingTalkStreamClientBuilder.custom()
                        .credential(new AuthClientCredential(
                                config.getClientId(),
                                config.getClientSecret()
                        ))
                        .registerCallbackListener(BOT_MESSAGE_TOPIC, messageListener)
                        .build();

                Log.d(TAG, "Stream 客户端已创建，正在启动连接...");

                // 启动连接
                streamClient.start();

                isRunning = true;
                Log.d(TAG, "Stream 客户端已启动");

                // 通知连接成功
                mainHandler.post(() -> callback.onConnected());

            } catch (Exception e) {
                Log.e(TAG, "启动 Stream 客户端失败", e);
                mainHandler.post(() -> callback.onError("启动失败: " + e.getMessage()));
            }
        }).start();
    }

    /**
     * 停止 Stream 连接
     */
    public void stop() {
        if (!isRunning) {
            return;
        }

        new Thread(() -> {
            try {
                if (streamClient != null) {
                    Log.d(TAG, "正在停止 Stream 客户端...");
                    // OpenDingTalkClient doesn't have a close() method
                    // Just set to null to allow garbage collection
                    streamClient = null;
                }

                isRunning = false;
                Log.d(TAG, "Stream 客户端已停止");

                mainHandler.post(() -> callback.onDisconnected());

            } catch (Exception e) {
                Log.e(TAG, "停止 Stream 客户端失败", e);
            }
        }).start();
    }

    /**
     * 检查是否正在运行
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * 机器人消息监听器
     * 实现官方 SDK 的回调接口
     */
    private static class ChatbotMessageListener implements OpenDingTalkCallbackListener<String, EventAckStatus> {
        private static final String TAG = "ChatbotMessageListener";

        private final Context context;
        private final DingTalkApiClient apiClient;
        private final CommandCallback commandCallback;
        private final Handler mainHandler;

        public ChatbotMessageListener(Context context, DingTalkApiClient apiClient,
                                       CommandCallback commandCallback, Handler mainHandler) {
            this.context = context;
            this.apiClient = apiClient;
            this.commandCallback = commandCallback;
            this.mainHandler = mainHandler;
        }

        @Override
        public EventAckStatus execute(String messageJson) {
            try {
                // 记录原始消息用于调试
                Log.d(TAG, "收到原始消息JSON: " + messageJson);

                // 解析 JSON 字符串
                JSONObject message = new JSONObject(messageJson);
                Log.d(TAG, "解析后的消息对象: " + message.toString());

                String content = null;
                String conversationId = null;
                String senderId = null;
                String sessionWebhook = null;

                // 解析文本内容 - 钉钉机器人消息格式
                if (message.has("text")) {
                    JSONObject textObj = message.getJSONObject("text");
                    content = textObj.optString("content", "");
                } else if (message.has("content")) {
                    // 有些情况下可能直接是 content 字段
                    JSONObject contentObj = message.getJSONObject("content");
                    if (contentObj.has("text")) {
                        content = contentObj.optString("text", "");
                    }
                }

                // 解析会话ID和发送者ID
                conversationId = message.optString("conversationId", "");
                if (conversationId.isEmpty()) {
                    conversationId = message.optString("openConversationId", "");
                }

                senderId = message.optString("senderStaffId", "");
                if (senderId.isEmpty()) {
                    senderId = message.optString("senderId", "");
                }

                // 获取 sessionWebhook（用于回复消息）
                sessionWebhook = message.optString("sessionWebhook", "");

                // 如果消息为空，可能是其他类型的事件（如加入群聊等），直接返回成功
                if (content == null || content.isEmpty()) {
                    Log.d(TAG, "消息内容为空，可能是非文本消息或系统事件");
                    Log.d(TAG, "完整消息结构: " + message.toString(2));
                    return EventAckStatus.SUCCESS;
                }

                Log.d(TAG, "解析成功 - 内容: " + content);
                Log.d(TAG, "解析成功 - 会话ID: " + conversationId);
                Log.d(TAG, "解析成功 - 发送者ID: " + senderId);
                Log.d(TAG, "解析成功 - SessionWebhook: " + sessionWebhook);

                // 检查 sessionWebhook 是否有效
                if (sessionWebhook.isEmpty()) {
                    Log.w(TAG, "SessionWebhook 为空，无法回复");
                    return EventAckStatus.SUCCESS;
                }

                // 解析指令
                String command = parseCommand(content);
                Log.d(TAG, "解析的指令: " + command);

                if ("录制".equals(command) || "record".equalsIgnoreCase(command)) {
                    Log.d(TAG, "收到录制指令");

                    // 发送确认消息
                    sendResponse(sessionWebhook, "收到录制指令，开始录制 1 分钟视频...");

                    // 通知监听器执行录制，传递 senderId
                    String finalConversationId = conversationId;
                    String finalSenderId = senderId;
                    mainHandler.post(() -> commandCallback.onRecordCommand(finalConversationId, finalSenderId));

                } else if ("帮助".equals(command) || "help".equalsIgnoreCase(command)) {
                    sendResponse(sessionWebhook,
                        "可用指令：\n" +
                        "• 录制 - 开始录制 1 分钟视频\n" +
                        "• 帮助 - 显示此帮助信息");

                } else {
                    Log.d(TAG, "未识别的指令: " + command);
                    sendResponse(sessionWebhook,
                        "未识别的指令。发送「帮助」查看可用指令。");
                }

                return EventAckStatus.SUCCESS;

            } catch (Exception e) {
                Log.e(TAG, "处理机器人消息失败", e);
                return EventAckStatus.LATER;
            }
        }

        /**
         * 解析指令文本
         * 移除 @机器人 的部分，提取实际指令
         */
        private String parseCommand(String text) {
            if (text == null) {
                return "";
            }

            // 移除 @xxx 部分和多余空格
            String command = text.replaceAll("@\\S+\\s*", "").trim();
            return command;
        }

        /**
         * 发送响应消息到钉钉（使用 sessionWebhook）
         */
        private void sendResponse(String sessionWebhook, String message) {
            new Thread(() -> {
                try {
                    apiClient.sendMessageViaWebhook(sessionWebhook, message);
                    Log.d(TAG, "响应消息已发送: " + message);
                } catch (Exception e) {
                    Log.e(TAG, "发送响应消息失败", e);
                }
            }).start();
        }
    }
}
