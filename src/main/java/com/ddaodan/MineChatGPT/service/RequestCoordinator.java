package com.ddaodan.MineChatGPT.service;

import com.ddaodan.MineChatGPT.ConfigManager;
import com.ddaodan.MineChatGPT.ConversationContext;
import com.ddaodan.MineChatGPT.Main;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class RequestCoordinator {
    private static final String SUMMARY_SYSTEM_PROMPT =
            "You are a summarization assistant. Summarize the conversation for future context. " +
            "Keep it concise, preserve important facts, user preferences, goals, and constraints. " +
            "Return ONLY the updated summary text.";

    private final Main plugin;
    private final ConfigManager configManager;
    private final ApiService apiService;
    private final UserSessionManager sessionManager;
    private volatile RateLimiter rateLimiter;
    private final AtomicInteger inFlight = new AtomicInteger(0);

    private volatile UsageTracker usageTracker;
    private volatile TokenEstimator tokenEstimator;
    private volatile RequestQueue<RequestJob> queue;
    private volatile int dispatchTaskId = -1;

    public RequestCoordinator(Main plugin, ConfigManager configManager, ApiService apiService, UserSessionManager sessionManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.apiService = apiService;
        this.sessionManager = sessionManager;
        this.usageTracker = new UsageTracker();
        reloadFromConfig();
    }

    public void reloadFromConfig() {
        this.tokenEstimator = createTokenEstimator(configManager.getRateLimitTokenEstimator());
        this.queue = new RequestQueue<>(configManager.getQueueMaxSize(), configManager.getQueueMaxPerUser());
        this.rateLimiter = new RateLimiter(configManager);
    }

    public UsageTracker getUsageTracker() {
        return usageTracker;
    }

    public void resetUsageTracker() {
        this.usageTracker = new UsageTracker();
    }

    public void start() {
        if (dispatchTaskId != -1) {
            return;
        }
        dispatchTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!configManager.isQueueEnabled()) {
                return;
            }
            int maxInFlight = configManager.getQueueMaxInFlight();
            int perTick = configManager.getQueueDispatchPerTick();
            for (int i = 0; i < perTick; i++) {
                if (inFlight.get() >= maxInFlight) {
                    return;
                }
                RequestJob job = queue.poll();
                if (job == null) {
                    return;
                }
                dispatch(job);
            }
        }, 1L, 1L).getTaskId();
    }

    public void stop() {
        if (dispatchTaskId != -1) {
            Bukkit.getScheduler().cancelTask(dispatchTaskId);
            dispatchTaskId = -1;
        }
    }

    public void submitAsk(CommandSender sender, String question, String userId) {
        boolean contextEnabled = sessionManager.isContextEnabled(userId);
        ConversationContext context = sessionManager.getConversationContext(userId);

        int estimatedTokensForLimit = estimateTokensForLimit(userId, question, contextEnabled, context);
        long now = System.currentTimeMillis();
        RateLimiter.Decision decision = rateLimiter.tryAcquire(userId, estimatedTokensForLimit, now);
        if (!decision.allowed) {
            if ("cooldown".equals(decision.reason)) {
                sender.sendMessage(configManager.getCooldownMessage().replace("%s", String.valueOf(decision.retryAfterMs)));
            } else {
                sender.sendMessage(configManager.getRateLimitedMessage());
            }
            return;
        }

        RequestJob job = new RequestJob(sender, question, userId, contextEnabled, estimatedTokensForLimit);

        if (!configManager.isQueueEnabled()) {
            int maxInFlight = configManager.getQueueMaxInFlight();
            if (inFlight.get() >= maxInFlight) {
                sender.sendMessage(configManager.getQueueFullMessage());
                return;
            }
            dispatch(job);
            return;
        }

        RequestQueue.EnqueueResult result = queue.tryEnqueue(userId, job);
        if (!result.accepted) {
            if ("queue_full_user".equals(result.reason)) {
                sender.sendMessage(configManager.getQueueFullUserMessage());
            } else {
                sender.sendMessage(configManager.getQueueFullMessage());
            }
            return;
        }

        sender.sendMessage(configManager.getQueuedMessage().replace("%s", String.valueOf(result.position)));
    }

    private void dispatch(RequestJob job) {
        inFlight.incrementAndGet();
        process(job)
                .whenComplete((ignored, e) -> {
                    if (e != null) {
                        plugin.getLogger().log(Level.SEVERE, "Job failed: " + e.getMessage(), e);
                    }
                    inFlight.decrementAndGet();
                });
    }

    private CompletableFuture<Void> process(RequestJob job) {
        if (job.sender instanceof Player) {
            Player player = (Player) job.sender;
            if (!player.isOnline()) {
                return CompletableFuture.completedFuture(null);
            }
        }

        ConversationContext context = sessionManager.getConversationContext(job.userId);
        String characterName = sessionManager.getCurrentCharacter(job.userId);
        String characterPrompt = configManager.getCharacters().get(characterName);
        String summary = sessionManager.getSummary(job.userId);

        CompletableFuture<Void> maybeSummarize = ensureContextWithinBudget(job.userId, job.contextEnabled, characterPrompt, summary, context);

        return maybeSummarize.thenCompose(v -> {
            String updatedSummary = sessionManager.getSummary(job.userId);
            JSONArray messages = buildMessages(job.contextEnabled, characterPrompt, updatedSummary, context, job.question);
            String model = configManager.getCurrentModel();
            return apiService.createChatCompletion(model, messages)
                    .thenAccept(result -> handleCompletion(job, characterName, result));
        });
    }

    private void handleCompletion(RequestJob job, String characterName, ApiService.ChatCompletionResult result) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled()) {
                return;
            }
            if (job.sender instanceof Player) {
                Player player = (Player) job.sender;
                if (!player.isOnline()) {
                    return;
                }
            }

            if (!result.isSuccess()) {
                job.sender.sendMessage(result.errorMessage);
                if (configManager.isDebugMode() && result.debugDetails != null && !result.debugDetails.isEmpty()) {
                    job.sender.sendMessage(result.debugDetails);
                }
                return;
            }

            String formatted = formatTwoPlaceholders(configManager.getChatGPTResponseMessage(), characterName, result.answer);
            sendMultiline(job.sender, formatted);

            if (job.contextEnabled) {
                ConversationContext ctx = sessionManager.getConversationContext(job.userId);
                ctx.addUserMessage(job.question);
                ctx.addAssistantMessage(result.answer);
            }

            long promptTokens = 0;
            long completionTokens = 0;
            long totalTokens = 0;
            if (result.usage != null) {
                promptTokens = result.usage.promptTokens;
                completionTokens = result.usage.completionTokens;
                totalTokens = result.usage.totalTokens;
            } else {
                // fallback estimation
                int estimatedPrompt = estimatePromptTokens(job.userId, job.question, job.contextEnabled);
                int estimatedCompletion = tokenEstimator.estimateTextTokens(result.answer);
                promptTokens = Math.max(0, estimatedPrompt);
                completionTokens = Math.max(0, estimatedCompletion);
                totalTokens = promptTokens + completionTokens;
            }
            usageTracker.record(job.userId, promptTokens, completionTokens, totalTokens);
        });
    }

    private CompletableFuture<Void> ensureContextWithinBudget(String userId, boolean contextEnabled, String characterPrompt, String summary, ConversationContext context) {
        if (!contextEnabled) {
            return CompletableFuture.completedFuture(null);
        }

        if (!configManager.isSummarizationEnabled()) {
            trimContextToBudget(characterPrompt, summary, context);
            return CompletableFuture.completedFuture(null);
        }

        int trigger = configManager.getSummarizationTriggerTokens();
        int estimated = estimateContextTokens(characterPrompt, summary, context);
        if (estimated <= trigger) {
            trimContextToBudget(characterPrompt, summary, context);
            return CompletableFuture.completedFuture(null);
        }

        int keepLast = configManager.getSummarizationKeepLastMessages();
        List<ConversationContext.Message> history = context.getMessages();
        if (history.size() <= keepLast) {
            trimContextToBudget(characterPrompt, summary, context);
            return CompletableFuture.completedFuture(null);
        }

        List<ConversationContext.Message> toSummarize = history.subList(0, history.size() - keepLast);
        List<ConversationContext.Message> toKeep = history.subList(history.size() - keepLast, history.size());

        JSONArray summarizeMessages = new JSONArray();
        summarizeMessages.put(new JSONObject().put("role", "system").put("content", SUMMARY_SYSTEM_PROMPT));

        StringBuilder sb = new StringBuilder();
        String existing = sessionManager.getSummary(userId);
        if (existing != null && !existing.trim().isEmpty()) {
            sb.append("Current summary:\n").append(existing.trim()).append("\n\n");
        }
        sb.append("Conversation to summarize:\n");
        for (ConversationContext.Message msg : toSummarize) {
            sb.append(msg.getRole()).append(": ").append(msg.getContent()).append("\n");
        }
        summarizeMessages.put(new JSONObject().put("role", "user").put("content", sb.toString()));

        String model = configManager.getSummarizationModel();
        return apiService.createChatCompletion(model, summarizeMessages)
                .thenCompose(result -> runSync(() -> {
                    if (!result.isSuccess()) {
                        return;
                    }
                    String newSummary = result.answer == null ? "" : result.answer.trim();
                    sessionManager.setSummary(userId, newSummary);
                    context.setMessages(new ArrayList<>(toKeep));
                    trimContextToBudget(characterPrompt, newSummary, context);

                    if (result.usage != null) {
                        usageTracker.record(userId, result.usage.promptTokens, result.usage.completionTokens, result.usage.totalTokens);
                    }
                }));
    }

    private CompletableFuture<Void> runSync(Runnable runnable) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private void trimContextToBudget(String characterPrompt, String summary, ConversationContext context) {
        int budget = configManager.getMaxContextTokens();
        if (budget <= 0) {
            return;
        }
        while (estimateContextTokens(characterPrompt, summary, context) > budget) {
            List<ConversationContext.Message> history = context.getMessages();
            if (history.isEmpty()) {
                return;
            }
            context.removeOldestMessage();
        }
    }

    private int estimateContextTokens(String characterPrompt, String summary, ConversationContext context) {
        int total = 0;
        if (characterPrompt != null && !characterPrompt.isEmpty()) {
            total += tokenEstimator.estimateMessageTokens("system", characterPrompt);
        }
        if (summary != null && !summary.trim().isEmpty()) {
            total += tokenEstimator.estimateMessageTokens("system", "Conversation summary:\n" + summary.trim());
        }
        for (ConversationContext.Message msg : context.getMessages()) {
            total += tokenEstimator.estimateMessageTokens(msg.getRole(), msg.getContent());
        }
        return total;
    }

    private int estimatePromptTokens(String userId, String question, boolean contextEnabled) {
        ConversationContext context = sessionManager.getConversationContext(userId);
        String characterName = sessionManager.getCurrentCharacter(userId);
        String characterPrompt = configManager.getCharacters().get(characterName);
        String summary = sessionManager.getSummary(userId);

        int total = 0;
        if (characterPrompt != null && !characterPrompt.isEmpty()) {
            total += tokenEstimator.estimateMessageTokens("system", characterPrompt);
        }
        if (contextEnabled) {
            if (summary != null && !summary.trim().isEmpty()) {
                total += tokenEstimator.estimateMessageTokens("system", "Conversation summary:\n" + summary.trim());
            }
            for (ConversationContext.Message msg : context.getMessages()) {
                total += tokenEstimator.estimateMessageTokens(msg.getRole(), msg.getContent());
            }
        }
        total += tokenEstimator.estimateMessageTokens("user", question);
        return total;
    }

    private int estimateTokensForLimit(String userId, String question, boolean contextEnabled, ConversationContext context) {
        int assumedCompletion = configManager.getRateLimitAssumedCompletionTokens();
        int reserve = configManager.getReserveCompletionTokens();
        int completionBudget = Math.max(assumedCompletion, reserve);
        int prompt = estimatePromptTokens(userId, question, contextEnabled);
        return prompt + completionBudget;
    }

    private JSONArray buildMessages(boolean contextEnabled, String characterPrompt, String summary, ConversationContext context, String question) {
        JSONArray messages = new JSONArray();
        if (characterPrompt != null && !characterPrompt.isEmpty()) {
            messages.put(new JSONObject().put("role", "system").put("content", characterPrompt));
        }
        if (contextEnabled) {
            if (summary != null && !summary.trim().isEmpty()) {
                messages.put(new JSONObject().put("role", "system").put("content", "Conversation summary:\n" + summary.trim()));
            }
            for (ConversationContext.Message msg : context.getMessages()) {
                messages.put(new JSONObject().put("role", msg.getRole()).put("content", msg.getContent()));
            }
        }
        messages.put(new JSONObject().put("role", "user").put("content", question));
        return messages;
    }

    private static void sendMultiline(CommandSender sender, String message) {
        String[] lines = message.split("\\R", -1);
        for (String line : lines) {
            if (!line.isEmpty()) {
                sender.sendMessage(line);
            }
        }
    }

    private static String formatTwoPlaceholders(String template, String first, String second) {
        String withFirst = replaceFirstLiteral(template, "%s", first);
        return replaceFirstLiteral(withFirst, "%s", second);
    }

    private static String replaceFirstLiteral(String template, String token, String replacement) {
        int index = template.indexOf(token);
        if (index < 0) {
            return template;
        }
        return template.substring(0, index) + replacement + template.substring(index + token.length());
    }

    private static TokenEstimator createTokenEstimator(String type) {
        if ("approx_chars".equalsIgnoreCase(type)) {
            return new ApproxCharTokenEstimator();
        }
        return new ApproxCharTokenEstimator();
    }

    private static final class RequestJob {
        private final CommandSender sender;
        private final String question;
        private final String userId;
        private final boolean contextEnabled;
        @SuppressWarnings("unused")
        private final int estimatedTokensForLimit;

        private RequestJob(CommandSender sender, String question, String userId, boolean contextEnabled, int estimatedTokensForLimit) {
            this.sender = sender;
            this.question = question;
            this.userId = userId;
            this.contextEnabled = contextEnabled;
            this.estimatedTokensForLimit = estimatedTokensForLimit;
        }
    }
}
