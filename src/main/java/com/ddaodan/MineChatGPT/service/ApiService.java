package com.ddaodan.MineChatGPT.service;

import com.ddaodan.MineChatGPT.ConfigManager;
import com.ddaodan.MineChatGPT.Main;
import jodd.http.HttpRequest;
import jodd.http.HttpResponse;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * API服务类：负责与 OpenAI 兼容接口通信（/chat/completions）
 */
public class ApiService {
    private static final Logger logger = Logger.getLogger(ApiService.class.getName());

    private final Main plugin;
    private final ConfigManager configManager;
    private final ExecutorService executor;

    public ApiService(Main plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.executor = Executors.newFixedThreadPool(
                configManager.getApiThreadPoolSize(),
                newNamedThreadFactory("minechatgpt-api-")
        );
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    public CompletableFuture<ChatCompletionResult> createChatCompletion(String model, JSONArray messages) {
        if (model == null || model.trim().isEmpty()) {
            return CompletableFuture.completedFuture(ChatCompletionResult.error("Missing model", null));
        }
        if (messages == null) {
            messages = new JSONArray();
        }

        String apiKey = configManager.getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return CompletableFuture.completedFuture(ChatCompletionResult.error(configManager.getNoApiKeyMessage(), null));
        }

        JSONObject payload = new JSONObject();
        payload.put("model", model);
        payload.put("messages", messages);

        if (configManager.isDebugMode()) {
            logger.info("Built request: " + payload);
        }

        String baseUrl = normalizeBaseUrl(configManager.getBaseUrl());
        HttpRequest request = HttpRequest
                .post(baseUrl + "/chat/completions")
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("User-Agent", "MineChatGPT/" + plugin.getDescription().getVersion())
                .connectionTimeout(configManager.getApiConnectTimeoutMs())
                .timeout(configManager.getApiTimeoutMs())
                .bodyText(payload.toString());

        if (configManager.isDebugMode()) {
            logger.info("Sending request: " + request);
        }

        return CompletableFuture
                .supplyAsync(request::send, executor)
                .thenApply(response -> parseResponse(response))
                .exceptionally(e -> {
                    logger.log(Level.SEVERE, "Exception occurred while processing request: " + e.getMessage(), e);
                    return ChatCompletionResult.error(configManager.getChatGPTErrorMessage(), null);
                });
    }

    private ChatCompletionResult parseResponse(HttpResponse response) {
        if (configManager.isDebugMode()) {
            logger.info("Received response: " + response);
        }

        int status = response.statusCode();
        String body = response.bodyText();

        if (status == 200) {
            try {
                JSONObject json = new JSONObject(body);
                String answer = json
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content");

                Usage usage = null;
                if (json.has("usage")) {
                    JSONObject usageJson = json.getJSONObject("usage");
                    long prompt = usageJson.optLong("prompt_tokens", -1);
                    long completion = usageJson.optLong("completion_tokens", -1);
                    long total = usageJson.optLong("total_tokens", -1);
                    if (prompt >= 0 && completion >= 0 && total >= 0) {
                        usage = new Usage(prompt, completion, total);
                    }
                }

                return ChatCompletionResult.success(answer, usage);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to parse response: " + e.getMessage(), e);
                return ChatCompletionResult.error(configManager.getChatGPTErrorMessage(), body);
            }
        }

        String debugDetails = tryExtractOpenAiErrorDetails(status, body);
        logger.log(Level.SEVERE, "Failed to get response (HTTP " + status + "): " + body);
        return ChatCompletionResult.error(configManager.getChatGPTErrorMessage(), debugDetails);
    }

    private static String tryExtractOpenAiErrorDetails(int status, String responseBody) {
        try {
            JSONObject errorJson = new JSONObject(responseBody);
            if (!errorJson.has("error")) {
                return null;
            }
            JSONObject err = errorJson.getJSONObject("error");
            String message = err.optString("message", "");
            String code = err.optString("code", "");
            if (message.isEmpty()) {
                return null;
            }
            return "[MineChatGPT] HTTP " + status + (code.isEmpty() ? "" : " (" + code + ")") + ": " + message;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "https://api.openai.com/v1";
        }
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static ThreadFactory newNamedThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    public static final class Usage {
        public final long promptTokens;
        public final long completionTokens;
        public final long totalTokens;

        public Usage(long promptTokens, long completionTokens, long totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }
    }

    public static final class ChatCompletionResult {
        public final String answer;
        public final Usage usage;
        public final String errorMessage;
        public final String debugDetails;

        private ChatCompletionResult(String answer, Usage usage, String errorMessage, String debugDetails) {
            this.answer = answer;
            this.usage = usage;
            this.errorMessage = errorMessage;
            this.debugDetails = debugDetails;
        }

        public static ChatCompletionResult success(String answer, Usage usage) {
            return new ChatCompletionResult(answer, usage, null, null);
        }

        public static ChatCompletionResult error(String errorMessage, String debugDetails) {
            return new ChatCompletionResult(null, null, errorMessage, debugDetails);
        }

        public boolean isSuccess() {
            return errorMessage == null;
        }
    }
}
