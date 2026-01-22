package com.test.cam.dingtalk;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 钉钉 API 客户端
 * 负责与钉钉服务器进行 HTTP 通信
 */
public class DingTalkApiClient {
    private static final String TAG = "DingTalkApiClient";
    private static final String BASE_URL = "https://api.dingtalk.com";
    private static final String OAPI_URL = "https://oapi.dingtalk.com";

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final DingTalkConfig config;

    public DingTalkApiClient(DingTalkConfig config) {
        this.config = config;
        this.gson = new Gson();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 获取 Access Token (使用旧版 API)
     */
    public String getAccessToken() throws IOException {
        // 检查缓存的 token 是否有效
        if (config.isTokenValid()) {
            String cachedToken = config.getAccessToken();
            Log.d(TAG, "使用缓存的 Access Token");
            return cachedToken;
        }

        // 获取新的 token - 使用旧版 API
        String url = OAPI_URL + "/gettoken?appkey=" + config.getClientId() +
                     "&appsecret=" + config.getClientSecret();

        Log.d(TAG, "正在获取新的 Access Token...");

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            Log.d(TAG, "Access Token 响应: " + responseBody);

            if (!response.isSuccessful()) {
                throw new IOException("获取 Access Token 失败: " + response.code() + " - " + responseBody);
            }

            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

            // 检查错误码
            if (jsonResponse.has("errcode")) {
                int errcode = jsonResponse.get("errcode").getAsInt();
                if (errcode != 0) {
                    String errmsg = jsonResponse.has("errmsg") ? jsonResponse.get("errmsg").getAsString() : "Unknown error";
                    throw new IOException("获取 Access Token 失败: errcode=" + errcode + ", errmsg=" + errmsg);
                }
            }

            if (jsonResponse.has("access_token")) {
                String accessToken = jsonResponse.get("access_token").getAsString();
                long expireIn = jsonResponse.get("expires_in").getAsLong();

                // 提前 5 分钟过期
                long expireTime = System.currentTimeMillis() + (expireIn - 300) * 1000;
                config.saveAccessToken(accessToken, expireTime);

                Log.d(TAG, "Access Token 获取成功");
                return accessToken;
            } else {
                throw new IOException("响应中没有 access_token: " + responseBody);
            }
        }
    }

    /**
     * 通过 sessionWebhook 发送文本消息（推荐方式）
     */
    public void sendMessageViaWebhook(String webhookUrl, String text) throws IOException {
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            throw new IOException("Webhook URL 为空");
        }

        // 构建消息体 - 按照自定义机器人的格式
        JsonObject textObj = new JsonObject();
        textObj.addProperty("content", text);

        JsonObject body = new JsonObject();
        body.addProperty("msgtype", "text");
        body.add("text", textObj);

        String requestJson = gson.toJson(body);
        Log.d(TAG, "通过 Webhook 发送消息: " + requestJson);

        Request request = new Request.Builder()
                .url(webhookUrl)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                Log.e(TAG, "Webhook 发送消息失败，响应: " + responseBody);
                throw new IOException("Webhook 发送消息失败: " + response.code() + ", " + responseBody);
            }
            Log.d(TAG, "Webhook 消息发送成功，响应: " + responseBody);
        }
    }

    /**
     * 发送文本消息到群聊
     * 优先使用 Webhook 方式，如果没有 Webhook 则使用 API 方式
     */
    public void sendTextMessage(String conversationId, String text) throws IOException {
        // 优先使用 Webhook 方式（不需要 userIds）
        String webhookUrl = config.getWebhookUrl();
        if (webhookUrl != null && !webhookUrl.isEmpty()) {
            sendMessageViaWebhook(webhookUrl, text);
            return;
        }

        // 如果没有 Webhook，使用 API 方式（需要 userIds）
        // 注意：这种方式需要至少一个 userId，否则会失败
        Log.w(TAG, "未配置 Webhook URL，无法发送文本消息（API 方式需要 userIds）");
        throw new IOException("未配置 Webhook URL，无法发送文本消息");
    }

    /**
     * 上传文件到钉钉
     */
    public String uploadFile(File file) throws IOException {
        String accessToken = getAccessToken();
        String url = OAPI_URL + "/media/upload?access_token=" + accessToken + "&type=file";

        RequestBody fileBody = RequestBody.create(
                MediaType.parse("application/octet-stream"),
                file
        );

        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("media", file.getName(), fileBody)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("上传文件失败: " + response.code());
            }

            String responseBody = response.body().string();
            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

            if (jsonResponse.has("media_id")) {
                String mediaId = jsonResponse.get("media_id").getAsString();
                Log.d(TAG, "文件上传成功，media_id: " + mediaId);
                return mediaId;
            } else {
                throw new IOException("响应中没有 media_id: " + responseBody);
            }
        }
    }

    /**
     * 发送文件消息到群聊
     * 注意：文件消息必须使用 API 方式，需要提供 userIds
     */
    public void sendFileMessage(String conversationId, String mediaId, String fileName, String userId) throws IOException {
        String accessToken = getAccessToken();
        String url = BASE_URL + "/v1.0/robot/oToMessages/batchSend";

        // 构建消息参数
        JsonObject msgParam = new JsonObject();
        msgParam.addProperty("mediaId", mediaId);
        msgParam.addProperty("fileName", fileName);

        // 构建 userIds 数组 - 必须至少包含一个 userId
        com.google.gson.JsonArray userIds = new com.google.gson.JsonArray();
        if (userId != null && !userId.isEmpty()) {
            userIds.add(userId);
        } else {
            throw new IOException("发送文件消息需要提供 userId");
        }

        JsonObject body = new JsonObject();
        body.addProperty("robotCode", config.getClientId());
        body.add("userIds", userIds);
        body.addProperty("msgKey", "sampleFile");
        body.addProperty("msgParam", gson.toJson(msgParam));

        // 如果有会话ID，添加到请求中
        if (conversationId != null && !conversationId.isEmpty()) {
            body.addProperty("openConversationId", conversationId);
        }

        String requestJson = gson.toJson(body);
        Log.d(TAG, "发送文件消息请求: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .header("x-acs-dingtalk-access-token", accessToken)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                Log.e(TAG, "发送文件消息失败，响应: " + responseBody);
                throw new IOException("发送文件消息失败: " + response.code() + ", " + responseBody);
            }
            Log.d(TAG, "文件消息发送成功，响应: " + responseBody);
        }
    }

    /**
     * Stream 连接信息
     */
    public static class StreamConnection {
        public final String endpoint;
        public final String ticket;

        public StreamConnection(String endpoint, String ticket) {
            this.endpoint = endpoint;
            this.ticket = ticket;
        }
    }

    /**
     * 获取 Stream 连接信息
     */
    public StreamConnection getStreamConnection() throws IOException {
        String url = BASE_URL + "/v1.0/gateway/connections/open";

        // 构建 subscriptions 数组
        // 订阅机器人消息事件
        com.google.gson.JsonArray subscriptions = new com.google.gson.JsonArray();

        // 订阅所有事件（如果开放平台已配置具体事件）
        JsonObject subscription1 = new JsonObject();
        subscription1.addProperty("type", "CALLBACK");
        subscription1.addProperty("topic", "/v1.0/im/bot/messages/get");
        subscriptions.add(subscription1);

        // 也订阅通用回调
        JsonObject subscription2 = new JsonObject();
        subscription2.addProperty("type", "CALLBACK");
        subscription2.addProperty("topic", "*");
        subscriptions.add(subscription2);

        JsonObject body = new JsonObject();
        body.addProperty("clientId", config.getClientId());
        body.addProperty("clientSecret", config.getClientSecret());
        body.add("subscriptions", subscriptions);

        String requestJson = gson.toJson(body);
        Log.d(TAG, "Stream 请求: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            Log.d(TAG, "Stream 响应: " + responseBody);

            if (!response.isSuccessful()) {
                throw new IOException("获取 Stream 连接信息失败: " + response.code() + " - " + responseBody);
            }

            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

            if (jsonResponse.has("endpoint") && jsonResponse.has("ticket")) {
                String endpoint = jsonResponse.get("endpoint").getAsString();
                String ticket = jsonResponse.get("ticket").getAsString();
                Log.d(TAG, "Stream 连接信息获取成功: " + endpoint);
                return new StreamConnection(endpoint, ticket);
            } else {
                throw new IOException("响应中缺少 endpoint 或 ticket: " + responseBody);
            }
        }
    }
}
