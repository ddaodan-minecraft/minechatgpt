package com.ddaodan.MineChatGPT;

import com.ddaodan.MineChatGPT.util.ConfigFileUpdater;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class ConfigManager {
    private final Main plugin;
    private FileConfiguration config;
    private String currentModel;
    private LanguageManager languageManager;
    private volatile Map<String, String> charactersCache;

    public ConfigManager(Main plugin) {
        this.plugin = plugin;
        reloadConfig();
        // 获取语言设置
        String language = config.getString("language", "en");
        this.languageManager = new LanguageManager(plugin, language);
        this.charactersCache = null;
    }
    public boolean isDebugMode() {
        return config.getBoolean("debug", false);
    }
    public void reloadConfig() {
        ConfigFileUpdater.UpdateResult updateResult = ConfigFileUpdater.updateIfMissingKeys(plugin, "config.yml");
        if (updateResult.updated) {
            plugin.getLogger().info("Config updated: inserted " + updateResult.insertedPaths
                    + " missing path(s). Backup: " + updateResult.backupFileName);
        }
        plugin.reloadConfig();
        config = plugin.getConfig();
        currentModel = config.getString("default_model");
        charactersCache = null;
        
        // 重新加载语言文件
        if (languageManager != null) {
            String language = config.getString("language", "en");
            languageManager.setLanguage(language);
        }
    }

    private String translateColorCodes(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
    public String getCurrentModel() {
        return currentModel;
    }
    public void setCurrentModel(String model) {
        currentModel = model;
    }
    private final AtomicInteger currentKeyIndex = new AtomicInteger(0);
    
    public String getApiKey() {
        List<String> keys = new ArrayList<>();
        for (String key : config.getStringList("api.keys")) {
            if (isUsableApiKey(key)) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            String legacyKey = config.getString("api.key");
            if (isUsableApiKey(legacyKey)) {
                return legacyKey;
            }
            return "";
        }

        String selectionMethod = config.getString("api.selection_method", "round_robin");

        if ("random".equalsIgnoreCase(selectionMethod)) {
            int randomIndex = (int) (Math.random() * keys.size());
            return keys.get(randomIndex);
        } else {
            int index = Math.floorMod(currentKeyIndex.getAndIncrement(), keys.size());
            return keys.get(index);
        }
    }

    private boolean isUsableApiKey(String key) {
        if (key == null) {
            return false;
        }
        String trimmed = key.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        return !trimmed.startsWith("sk-your_openai_api_key_");
    }

    public String getBaseUrl() {
        return config.getString("api.base_url", "https://api.openai.com/v1");
    }
    public int getApiConnectTimeoutMs() {
        return config.getInt("api.connect_timeout_ms", 10000);
    }
    public int getApiTimeoutMs() {
        return config.getInt("api.timeout_ms", 30000);
    }
    public int getApiThreadPoolSize() {
        return Math.max(1, config.getInt("api.thread_pool_size", 4));
    }
    public String getDefaultModel() {
        return config.getString("default_model");
    }
    public String getReloadMessage() {
        return languageManager.getMessage("reload");
    }
    public List<String> getModels() {
        return config.getStringList("models");
    }
    public String getHelpMessage() {
        return languageManager.getMessage("help");
    }
    public String getHelpAskMessage() {
        return languageManager.getMessage("help_ask");
    }
    public String getHelpReloadMessage() {
        return languageManager.getMessage("help_reload");
    }
    public String getHelpModelMessage() {
        return languageManager.getMessage("help_model");
    }
    public String getHelpModelListMessage() {
        return languageManager.getMessage("help_modellist");
    }
    public String getHelpContextMessage() {
        return languageManager.getMessage("help_context");
    }
    public String getHelpClearMessage() {
        return languageManager.getMessage("help_clear");
    }
    public String getHelpCharacterMessage() {
        return languageManager.getMessage("help_character");
    }
    public String getHelpStatsMessage() {
        return languageManager.getMessage("help_stats");
    }
    public String getModelSwitchMessage() {
        return languageManager.getMessage("model_switch");
    }
    public String getChatGPTErrorMessage() {
        return languageManager.getMessage("chatgpt_error");
    }
    public String getNoApiKeyMessage() {
        return languageManager.getMessage("no_api_key", "&cNo API key configured. Please set api.keys in config.yml.");
    }
    public String getChatGPTResponseMessage() {
        return languageManager.getMessage("chatgpt_response", "&b%s: %s");
    }
    public String getQuestionMessage() {
        return languageManager.getMessage("question");
    }
    public String getQueuedMessage() {
        return languageManager.getMessage("queued", "&eYour request has been queued. Position: %s");
    }
    public String getQueueFullMessage() {
        return languageManager.getMessage("queue_full", "&cQueue is full. Please try again later.");
    }
    public String getQueueFullUserMessage() {
        return languageManager.getMessage("queue_full_user", "&cYou have too many pending requests. Please wait.");
    }
    public String getCooldownMessage() {
        return languageManager.getMessage("cooldown", "&cYou're sending requests too fast. Please wait %s ms.");
    }
    public String getRateLimitedMessage() {
        return languageManager.getMessage("rate_limited", "&cRate limited. Please try again later.");
    }
    public String getStatsHeaderMessage() {
        return languageManager.getMessage("stats_header", "&e===== MineChatGPT Stats =====");
    }
    public String getStatsGlobalMessage() {
        return languageManager.getMessage("stats_global", "&eGlobal tokens: %s (prompt=%s, completion=%s), requests=%s");
    }
    public String getStatsUserMessage() {
        return languageManager.getMessage("stats_user", "&eYour tokens: %s (prompt=%s, completion=%s), requests=%s");
    }
    public String getStatsResetMessage() {
        return languageManager.getMessage("stats_reset", "&aStats reset.");
    }
    public String getInvalidModelMessage() {
        return languageManager.getMessage("invalid_model");
    }
    public String getAvailableModelsMessage() {
        return languageManager.getMessage("available_models");
    }
    public String getNoPermissionMessage() {
        return languageManager.getMessage("no_permission");
    }
    public String getCurrentModelInfoMessage() {
        return languageManager.getMessage("current_model_info");
    }
    public int getMaxHistorySize() {
        return config.getInt("conversation.max_history_size", 10);
    }
    public int getMaxContextTokens() {
        return config.getInt("conversation.max_context_tokens", 2000);
    }
    public int getReserveCompletionTokens() {
        return config.getInt("conversation.reserve_completion_tokens", 400);
    }
    public boolean isSummarizationEnabled() {
        return config.getBoolean("conversation.summarization.enabled", true);
    }
    public String getSummarizationModel() {
        return config.getString("conversation.summarization.model", getCurrentModel());
    }
    public int getSummarizationTriggerTokens() {
        return config.getInt("conversation.summarization.trigger_tokens", 1800);
    }
    public int getSummarizationKeepLastMessages() {
        return Math.max(0, config.getInt("conversation.summarization.keep_last_messages", 6));
    }
    public boolean isContextEnabled() {
        return config.getBoolean("conversation.context_enabled", false);
    }
    public String getContextToggleMessage() {
        return languageManager.getMessage("context_toggle", "&eContext is now %s.");
    }
    public String getContextToggleEnabledMessage() {
        return languageManager.getMessage("context_toggle_enabled", "&aenabled");
    }
    public String getContextToggleDisabledMessage() {
        return languageManager.getMessage("context_toggle_disabled", "&edisabled");
    }
    public String getClearMessage() {
        return languageManager.getMessage("clear", "&aConversation history has been cleared.");
    }
    public String getCharacterSwitchedMessage() {
        return languageManager.getMessage("character_switched", "&aSwitched to character: %s");
    }
    public String getAvailableCharactersMessage() {
        return languageManager.getMessage("available_characters", "&eAvailable characters:");
    }
    public String getInvalidCharacterMessage() {
        return languageManager.getMessage("invalid_character", "&cInvalid character. Use /chatgpt character to list available characters.");
    }
    public Map<String, String> getCharacters() {
        Map<String, String> cached = charactersCache;
        if (cached != null) {
            return cached;
        }

        ConfigurationSection section = config.getConfigurationSection("characters");
        if (section == null) {
            charactersCache = Collections.emptyMap();
            return charactersCache;
        }

        Map<String, String> characters = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            String prompt = section.getString(key);
            if (prompt != null) {
                characters.put(key, prompt);
            }
        }

        charactersCache = Collections.unmodifiableMap(characters);
        return charactersCache;
    }
    public String getCurrentCharacter(String userId) {
        return config.getString("users." + userId + ".character", "ChatGPT");
    }
    public void setCurrentCharacter(String userId, String character) {
        config.set("users." + userId + ".character", character);
        plugin.saveConfig();
    }

    public boolean isRateLimitEnabled() {
        return config.getBoolean("rate_limit.enabled", true);
    }
    public String getRateLimitMode() {
        return config.getString("rate_limit.mode", "both");
    }
    public long getRateLimitCooldownMs() {
        return Math.max(0L, config.getLong("rate_limit.cooldown_ms", 1000L));
    }
    public String getRateLimitTokenEstimator() {
        return config.getString("rate_limit.token_estimator", "approx_chars");
    }
    public int getRateLimitAssumedCompletionTokens() {
        return Math.max(0, config.getInt("rate_limit.assumed_completion_tokens", 300));
    }

    public int getPerUserRequestsPerMinute() {
        return Math.max(0, config.getInt("rate_limit.per_user.requests_per_minute", 6));
    }
    public int getPerUserBurstRequests() {
        return Math.max(0, config.getInt("rate_limit.per_user.burst_requests", 3));
    }
    public int getPerUserTokensPerMinute() {
        return Math.max(0, config.getInt("rate_limit.per_user.tokens_per_minute", 4000));
    }
    public int getPerUserBurstTokens() {
        return Math.max(0, config.getInt("rate_limit.per_user.burst_tokens", 2000));
    }

    public int getGlobalRequestsPerMinute() {
        return Math.max(0, config.getInt("rate_limit.global.requests_per_minute", 60));
    }
    public int getGlobalBurstRequests() {
        return Math.max(0, config.getInt("rate_limit.global.burst_requests", 20));
    }
    public int getGlobalTokensPerMinute() {
        return Math.max(0, config.getInt("rate_limit.global.tokens_per_minute", 100000));
    }
    public int getGlobalBurstTokens() {
        return Math.max(0, config.getInt("rate_limit.global.burst_tokens", 20000));
    }

    public boolean isQueueEnabled() {
        return config.getBoolean("queue.enabled", true);
    }
    public int getQueueMaxSize() {
        return Math.max(0, config.getInt("queue.max_size", 100));
    }
    public int getQueueMaxPerUser() {
        return Math.max(0, config.getInt("queue.max_per_user", 3));
    }
    public int getQueueMaxInFlight() {
        return Math.max(1, config.getInt("queue.max_in_flight", getApiThreadPoolSize()));
    }
    public int getQueueDispatchPerTick() {
        return Math.max(1, config.getInt("queue.dispatch_per_tick", 2));
    }
}
