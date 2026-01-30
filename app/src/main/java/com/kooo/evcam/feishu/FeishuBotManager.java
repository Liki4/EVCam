package com.kooo.evcam.feishu;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.WakeUpHelper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.enums.MsgTypeEnum;
import com.lark.oapi.service.im.v1.enums.ReceiveIdTypeEnum;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.ReplyMessageReq;
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody;
import com.lark.oapi.service.im.v1.model.ReplyMessageResp;

import java.util.Map;
import java.util.HashMap;
import com.google.gson.reflect.TypeToken;

/**
 * 飞书 Bot 管理器
 * 使用飞书官方 SDK 通过 WebSocket 长连接接收消息
 */
public class FeishuBotManager {
    private static final String TAG = "FeishuBotManager";

    private final Context context;
    private final FeishuConfig config;
    private final FeishuApiClient apiClient;
    private final ConnectionCallback connectionCallback;
    private final Handler mainHandler;
    private final Gson gson;

    private Client larkClient;
    private com.lark.oapi.ws.Client wsClient;
    private volatile boolean isRunning = false;
    private CommandCallback currentCommandCallback;

    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    public interface CommandCallback {
        void onRecordCommand(String chatId, String messageId, int durationSeconds);
        void onPhotoCommand(String chatId, String messageId);
        String getStatusInfo();
        String onStartRecordingCommand();
        String onStopRecordingCommand();
        String onExitCommand(boolean confirmed);
    }

    public FeishuBotManager(Context context, FeishuConfig config,
                            FeishuApiClient apiClient, ConnectionCallback callback) {
        this.context = context;
        this.config = config;
        this.apiClient = apiClient;
        this.connectionCallback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.gson = new Gson();
    }

    /**
     * 启动 WebSocket 连接
     */
    public void start(CommandCallback commandCallback) {
        if (isRunning) {
            AppLog.w(TAG, "Bot 已在运行");
            return;
        }

        this.currentCommandCallback = commandCallback;

        String appId = config.getAppId();
        String appSecret = config.getAppSecret();

        if (appId == null || appId.isEmpty() || appSecret == null || appSecret.isEmpty()) {
            AppLog.e(TAG, "App ID 或 App Secret 未配置");
            mainHandler.post(() -> connectionCallback.onError("App ID 或 App Secret 未配置"));
            return;
        }

        AppLog.d(TAG, "正在初始化飞书 SDK...");

        // 在后台线程中初始化和启动，避免阻塞主线程
        new Thread(() -> {
            try {
                // 创建 LarkClient 用于调用 API
                larkClient = new Client.Builder(appId, appSecret).build();

                // 注册事件处理器
                EventDispatcher eventHandler = EventDispatcher.newBuilder("", "")
                        .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                            @Override
                            public void handle(P2MessageReceiveV1 event) throws Exception {
                                processMessageEvent(event);
                            }
                        })
                        .build();

                // 创建 WebSocket 客户端并启动
                wsClient = new com.lark.oapi.ws.Client.Builder(appId, appSecret)
                        .eventHandler(eventHandler)
                        .build();

                // 启动 WebSocket 客户端
                AppLog.d(TAG, "启动 WebSocket 连接...");
                AppLog.d(TAG, "App ID: " + appId.substring(0, Math.min(8, appId.length())) + "...");
                
                wsClient.start();
                // start() 成功调用后，SDK 会在后台维护连接
                isRunning = true;
                AppLog.d(TAG, "飞书 SDK 已启动，连接在后台运行");
                mainHandler.post(() -> connectionCallback.onConnected());

            } catch (Exception e) {
                isRunning = false;
                AppLog.e(TAG, "初始化或启动失败: " + e.getMessage(), e);
                mainHandler.post(() -> connectionCallback.onError("启动失败: " + e.getMessage()));
            }
        }).start();
    }

    /**
     * 处理消息事件
     */
    private void processMessageEvent(P2MessageReceiveV1 event) {
        try {
            AppLog.d(TAG, "收到消息事件: " + Jsons.DEFAULT.toJson(event.getEvent()));

            // 获取消息信息
            String messageType = event.getEvent().getMessage().getMessageType();
            String chatId = event.getEvent().getMessage().getChatId();
            String messageId = event.getEvent().getMessage().getMessageId();
            String chatType = event.getEvent().getMessage().getChatType();

            // 获取发送者信息
            String senderId = "";
            if (event.getEvent().getSender() != null && 
                event.getEvent().getSender().getSenderId() != null) {
                senderId = event.getEvent().getSender().getSenderId().getOpenId();
            }

            AppLog.d(TAG, "消息类型: " + messageType + ", chatId: " + chatId + ", senderId: " + senderId);

            // 检查用户是否被允许
            if (!config.isUserIdAllowed(senderId)) {
                AppLog.d(TAG, "用户不在白名单中: " + senderId);
                return;
            }

            // 只处理文本消息
            if (!"text".equals(messageType)) {
                AppLog.d(TAG, "非文本消息，忽略: " + messageType);
                return;
            }

            // 解析消息内容
            String content = event.getEvent().getMessage().getContent();
            Map<String, String> contentMap = new HashMap<>();
            try {
                contentMap = new Gson().fromJson(content, new TypeToken<Map<String, String>>() {}.getType());
            } catch (Exception e) {
                AppLog.e(TAG, "解析消息内容失败", e);
                return;
            }

            String text = contentMap.get("text");
            if (text == null || text.isEmpty()) {
                AppLog.d(TAG, "消息内容为空");
                return;
            }

            AppLog.d(TAG, "收到文本消息: " + text);

            // 处理指令
            handleCommand(chatId, messageId, chatType, text);

        } catch (Exception e) {
            AppLog.e(TAG, "处理消息事件失败", e);
        }
    }

    /**
     * 处理指令
     */
    private void handleCommand(String chatId, String messageId, String chatType, String content) {
        // 移除 @机器人 部分
        String command = content.replaceAll("@\\S+\\s*", "").trim();
        AppLog.d(TAG, "解析指令: " + command);

        try {
            if (command.startsWith("录制") || command.toLowerCase().startsWith("record")) {
                int durationSeconds = parseRecordDuration(command);
                AppLog.d(TAG, "收到录制指令，时长: " + durationSeconds + " 秒");

                String confirmMsg = String.format("收到录制指令，开始录制 %d 秒视频...", durationSeconds);
                sendReplyAndThen(chatId, messageId, chatType, confirmMsg, () -> {
                    WakeUpHelper.launchForRecordingFeishu(context, chatId, messageId, durationSeconds);
                });

            } else if ("拍照".equals(command) || "photo".equalsIgnoreCase(command)) {
                AppLog.d(TAG, "收到拍照指令");

                sendReplyAndThen(chatId, messageId, chatType, "收到拍照指令，正在拍照...", () -> {
                    WakeUpHelper.launchForPhotoFeishu(context, chatId, messageId);
                });

            } else if ("状态".equals(command) || "status".equalsIgnoreCase(command)) {
                AppLog.d(TAG, "收到状态指令");
                String statusInfo = currentCommandCallback != null ?
                        currentCommandCallback.getStatusInfo() : "✅ Bot 正在运行中";
                sendReply(chatId, messageId, chatType, statusInfo);

            } else if ("启动录制".equals(command) || "开始录制".equals(command) ||
                       "start".equalsIgnoreCase(command)) {
                AppLog.d(TAG, "收到启动录制指令");
                if (currentCommandCallback != null) {
                    String result = currentCommandCallback.onStartRecordingCommand();
                    sendReply(chatId, messageId, chatType, result);
                } else {
                    sendReply(chatId, messageId, chatType, "❌ 功能不可用");
                }

            } else if ("结束录制".equals(command) || "停止录制".equals(command) ||
                       "stop".equalsIgnoreCase(command)) {
                AppLog.d(TAG, "收到结束录制指令");
                if (currentCommandCallback != null) {
                    String result = currentCommandCallback.onStopRecordingCommand();
                    sendReply(chatId, messageId, chatType, result);
                } else {
                    sendReply(chatId, messageId, chatType, "❌ 功能不可用");
                }

            } else if ("退出".equals(command) || "exit".equalsIgnoreCase(command)) {
                AppLog.d(TAG, "收到退出指令（需二次确认）");
                sendReply(chatId, messageId, chatType,
                    "⚠️ 确认要退出 EVCam 吗？\n\n" +
                    "退出后将停止所有录制和远程服务。\n" +
                    "发送「确认退出」执行退出操作。");

            } else if ("确认退出".equals(command)) {
                AppLog.d(TAG, "收到确认退出指令");
                if (currentCommandCallback != null) {
                    String result = currentCommandCallback.onExitCommand(true);
                    sendReply(chatId, messageId, chatType, result);
                } else {
                    sendReply(chatId, messageId, chatType, "❌ 功能不可用");
                }

            } else if ("帮助".equals(command) || "help".equalsIgnoreCase(command)) {
                sendReply(chatId, messageId, chatType,
                    "📋 EVCam 远程控制\n" +
                    "━━━━━━━━━━━━━━\n\n" +
                    "📹 远程录制\n" +
                    "• 录制 - 录制60秒视频\n" +
                    "• 录制30 - 录制30秒视频\n\n" +
                    "▶️ 持续录制\n" +
                    "• 启动录制 - 开始持续录制\n" +
                    "• 结束录制 - 停止录制\n\n" +
                    "📷 拍照\n" +
                    "• 拍照 - 拍摄照片\n\n" +
                    "ℹ️ 其他\n" +
                    "• 状态 - 查看应用状态\n" +
                    "• 退出 - 退出应用\n" +
                    "• 帮助 - 显示此帮助");

            } else {
                AppLog.d(TAG, "未识别的指令: " + command);
                sendReply(chatId, messageId, chatType, "未识别的指令。发送「帮助」查看可用指令。");
            }

        } catch (Exception e) {
            AppLog.e(TAG, "处理指令失败", e);
        }
    }

    /**
     * 解析录制时长
     */
    private int parseRecordDuration(String command) {
        String durationStr = command.replaceAll("(?i)(录制|record)", "").trim();

        if (durationStr.isEmpty()) {
            return 60; // 默认 1 分钟
        }

        try {
            int duration = Integer.parseInt(durationStr);
            if (duration < 5) return 5;
            if (duration > 600) return 600;
            return duration;
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    /**
     * 构建文本消息的 content JSON 字符串
     * 飞书 API 要求 content 必须是 JSON 序列化后的字符串，如 "{\"text\":\"Hello\"}"
     */
    private String buildTextContent(String text) {
        JsonObject content = new JsonObject();
        content.addProperty("text", text);
        return gson.toJson(content);
    }

    /**
     * 发送回复消息
     */
    private void sendReply(String chatId, String messageId, String chatType, String text) {
        new Thread(() -> {
            try {
                // 优先使用 SDK 客户端，如果不可用则使用 HTTP API
                if (larkClient != null) {
                    sendReplyViaSdk(chatId, messageId, chatType, text);
                } else {
                    // SDK 客户端不可用，使用 HTTP API 发送
                    AppLog.w(TAG, "SDK 客户端不可用，使用 HTTP API 发送消息");
                    sendReplyViaHttp(chatId, messageId, chatType, text);
                }
            } catch (Exception e) {
                AppLog.e(TAG, "发送消息失败", e);
            }
        }).start();
    }

    /**
     * 通过 SDK 发送消息
     */
    private void sendReplyViaSdk(String chatId, String messageId, String chatType, String text) throws Exception {
        String replyContent = buildTextContent(text);
        AppLog.d(TAG, "通过 SDK 发送消息 content: " + replyContent);

        if ("p2p".equals(chatType)) {
            // 私聊：使用 create 发送
            CreateMessageReq req = CreateMessageReq.newBuilder()
                    .receiveIdType(ReceiveIdTypeEnum.CHAT_ID.getValue())
                    .createMessageReqBody(CreateMessageReqBody.newBuilder()
                            .receiveId(chatId)
                            .msgType(MsgTypeEnum.MSG_TYPE_TEXT.getValue())
                            .content(replyContent)
                            .build())
                    .build();

            CreateMessageResp resp = larkClient.im().message().create(req);
            if (resp.getCode() != 0) {
                AppLog.e(TAG, "发送消息失败: " + Jsons.DEFAULT.toJson(resp.getError()));
            } else {
                AppLog.d(TAG, "消息发送成功");
            }
        } else {
            // 群聊：使用 reply 回复
            ReplyMessageReq req = ReplyMessageReq.newBuilder()
                    .messageId(messageId)
                    .replyMessageReqBody(ReplyMessageReqBody.newBuilder()
                            .content(replyContent)
                            .msgType("text")
                            .build())
                    .build();

            ReplyMessageResp resp = larkClient.im().message().reply(req);
            if (resp.getCode() != 0) {
                AppLog.e(TAG, "回复消息失败: " + Jsons.DEFAULT.toJson(resp.getError()));
            } else {
                AppLog.d(TAG, "回复消息成功");
            }
        }
    }

    /**
     * 通过 HTTP API 发送消息（备选方案）
     */
    private void sendReplyViaHttp(String chatId, String messageId, String chatType, String text) {
        try {
            if ("p2p".equals(chatType)) {
                apiClient.sendTextMessage("chat_id", chatId, text);
                AppLog.d(TAG, "HTTP API 消息发送成功");
            } else {
                apiClient.replyMessage(messageId, text);
                AppLog.d(TAG, "HTTP API 回复消息成功");
            }
        } catch (Exception e) {
            AppLog.e(TAG, "HTTP API 发送消息失败", e);
        }
    }

    /**
     * 发送回复消息并执行回调
     */
    private void sendReplyAndThen(String chatId, String messageId, String chatType, String text, Runnable callback) {
        new Thread(() -> {
            try {
                // 优先使用 SDK 客户端，如果不可用则使用 HTTP API
                if (larkClient != null) {
                    sendReplyViaSdk(chatId, messageId, chatType, text);
                } else {
                    AppLog.w(TAG, "SDK 客户端不可用，使用 HTTP API 发送消息");
                    sendReplyViaHttp(chatId, messageId, chatType, text);
                }

                AppLog.d(TAG, "回复消息已发送");

                if (callback != null) {
                    callback.run();
                }
            } catch (Exception e) {
                AppLog.e(TAG, "发送回复失败", e);
                if (callback != null) {
                    callback.run();
                }
            }
        }).start();
    }

    /**
     * 获取 LarkClient（供上传服务使用）
     */
    public Client getLarkClient() {
        return larkClient;
    }

    /**
     * 停止 Bot
     */
    public void stop() {
        AppLog.d(TAG, "正在停止 Bot...");

        if (wsClient != null) {
            try {
                // SDK 没有提供 stop 方法，设置标志位让线程退出
                // wsClient 的 start() 是阻塞的，需要通过其他方式中断
            } catch (Exception e) {
                AppLog.e(TAG, "停止 wsClient 失败", e);
            }
            wsClient = null;
        }

        larkClient = null;
        isRunning = false;
        AppLog.d(TAG, "Bot 已停止");
    }

    /**
     * 检查是否正在运行
     */
    public boolean isRunning() {
        return isRunning;
    }
}
