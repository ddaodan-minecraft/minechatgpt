package com.ddaodan.MineChatGPT.service;

import com.ddaodan.MineChatGPT.ConfigManager;
import com.ddaodan.MineChatGPT.ConversationContext;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Map;

/**
 * 命令处理服务类，负责处理各种命令的业务逻辑
 */
public class CommandService {
    private final ConfigManager configManager;
    private final RequestCoordinator requestCoordinator;
    private final UserSessionManager sessionManager;

    public CommandService(ConfigManager configManager, RequestCoordinator requestCoordinator, UserSessionManager sessionManager) {
        this.configManager = configManager;
        this.requestCoordinator = requestCoordinator;
        this.sessionManager = sessionManager;
    }

    /**
     * 处理重载配置命令
     *
     * @param sender 命令发送者
     * @return 是否成功处理
     */
    public boolean handleReloadCommand(CommandSender sender) {
        if (!sender.hasPermission("minechatgpt.reload")) {
            sender.sendMessage(configManager.getNoPermissionMessage().replace("%s", "minechatgpt.reload"));
            return true;
        }
        configManager.reloadConfig();
        requestCoordinator.reloadFromConfig();
        sender.sendMessage(configManager.getReloadMessage());
        return true;
    }

    /**
     * 处理模型切换命令
     *
     * @param sender 命令发送者
     * @param args 命令参数
     * @return 是否成功处理
     */
    public boolean handleModelCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("minechatgpt.model")) {
            sender.sendMessage(configManager.getNoPermissionMessage().replace("%s", "minechatgpt.model"));
            return true;
        }
        if (args.length < 2) {
            String currentModel = configManager.getCurrentModel();
            sender.sendMessage(configManager.getCurrentModelInfoMessage().replace("%s", currentModel));
            return true;
        }
        String model = args[1];
        List<String> models = configManager.getModels();
        if (models.contains(model)) {
            configManager.setCurrentModel(model);
            sender.sendMessage(configManager.getModelSwitchMessage().replace("%s", model));
        } else {
            sender.sendMessage(configManager.getInvalidModelMessage());
        }
        return true;
    }

    /**
     * 处理模型列表命令
     *
     * @param sender 命令发送者
     * @return 是否成功处理
     */
    public boolean handleModelListCommand(CommandSender sender) {
        if (!sender.hasPermission("minechatgpt.modellist")) {
            sender.sendMessage(configManager.getNoPermissionMessage().replace("%s", "minechatgpt.modellist"));
            return true;
        }
        List<String> models = configManager.getModels();
        sender.sendMessage(configManager.getAvailableModelsMessage());
        for (String model : models) {
            sender.sendMessage("- " + model);
        }
        return true;
    }

    /**
     * 处理上下文切换命令
     *
     * @param sender 命令发送者
     * @param userId 用户ID
     * @return 是否成功处理
     */
    public boolean handleContextCommand(CommandSender sender, String userId) {
        boolean contextEnabled = !sessionManager.isContextEnabled(userId);
        sessionManager.setContextEnabled(userId, contextEnabled);
        String status = contextEnabled ? configManager.getContextToggleEnabledMessage() : configManager.getContextToggleDisabledMessage();
        sender.sendMessage(configManager.getContextToggleMessage().replace("%s", status));
        return true;
    }

    /**
     * 处理清除历史记录命令
     *
     * @param sender 命令发送者
     * @param userId 用户ID
     * @return 是否成功处理
     */
    public boolean handleClearCommand(CommandSender sender, String userId) {
        if (!sender.hasPermission("minechatgpt.clear")) {
            sender.sendMessage(configManager.getNoPermissionMessage().replace("%s", "minechatgpt.clear"));
            return true;
        }
        sessionManager.clearConversationHistory(userId);
        sender.sendMessage(configManager.getClearMessage());
        return true;
    }

    /**
     * 处理角色切换命令
     *
     * @param sender 命令发送者
     * @param args 命令参数
     * @param userId 用户ID
     * @return 是否成功处理
     */
    public boolean handleCharacterCommand(CommandSender sender, String[] args, String userId) {
        if (!sender.hasPermission("minechatgpt.character")) {
            sender.sendMessage(configManager.getNoPermissionMessage().replace("%s", "minechatgpt.character"));
            return true;
        }
        Map<String, String> characters = configManager.getCharacters();
        if (args.length < 2) {
            sender.sendMessage(configManager.getAvailableCharactersMessage());
            for (String character : characters.keySet()) {
                sender.sendMessage("- " + character);
            }
            return true;
        }
        String character = args[1];
        if (characters.containsKey(character)) {
            sessionManager.setCurrentCharacter(userId, character);
            sender.sendMessage(configManager.getCharacterSwitchedMessage().replace("%s", character));
        } else {
            sender.sendMessage(configManager.getInvalidCharacterMessage());
        }
        return true;
    }

    /**
     * 处理向ChatGPT提问的命令
     *
     * @param sender 命令发送者
     * @param args 命令参数
     * @param userId 用户ID
     * @return 是否成功处理
     */
    public boolean handleAskCommand(CommandSender sender, String[] args, String userId) {
        if (!sender.hasPermission("minechatgpt.use")) {
            sender.sendMessage(configManager.getNoPermissionMessage().replace("%s", "minechatgpt.use"));
            return true;
        }
        String question = String.join(" ", args);
        
        sender.sendMessage(configManager.getQuestionMessage().replace("%s", question));
        requestCoordinator.submitAsk(sender, question, userId);
        return true;
    }

    public boolean handleStatsCommand(CommandSender sender, String[] args, String userId) {
        if (!sender.hasPermission("minechatgpt.stats")) {
            sender.sendMessage(configManager.getNoPermissionMessage().replace("%s", "minechatgpt.stats"));
            return true;
        }

        if (args.length >= 2 && "reset".equalsIgnoreCase(args[1])) {
            requestCoordinator.resetUsageTracker();
            sender.sendMessage(configManager.getStatsResetMessage());
            return true;
        }

        UsageTracker.Snapshot global = requestCoordinator.getUsageTracker().getGlobal();
        UsageTracker.Snapshot user = requestCoordinator.getUsageTracker().getUser(userId);

        sender.sendMessage(configManager.getStatsHeaderMessage());
        sender.sendMessage(configManager.getStatsGlobalMessage()
                .replaceFirst("%s", String.valueOf(global.totalTokens))
                .replaceFirst("%s", String.valueOf(global.promptTokens))
                .replaceFirst("%s", String.valueOf(global.completionTokens))
                .replaceFirst("%s", String.valueOf(global.requests)));

        sender.sendMessage(configManager.getStatsUserMessage()
                .replaceFirst("%s", String.valueOf(user.totalTokens))
                .replaceFirst("%s", String.valueOf(user.promptTokens))
                .replaceFirst("%s", String.valueOf(user.completionTokens))
                .replaceFirst("%s", String.valueOf(user.requests)));

        return true;
    }

    /**
     * 发送帮助信息
     *
     * @param sender 命令发送者
     */
    public void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(configManager.getHelpMessage());
        sender.sendMessage(configManager.getHelpAskMessage());
        sender.sendMessage(configManager.getHelpReloadMessage());
        sender.sendMessage(configManager.getHelpModelMessage());
        sender.sendMessage(configManager.getHelpModelListMessage());
        sender.sendMessage(configManager.getHelpContextMessage());
        sender.sendMessage(configManager.getHelpClearMessage());
        sender.sendMessage(configManager.getHelpCharacterMessage());
        sender.sendMessage(configManager.getHelpStatsMessage());
    }
}
