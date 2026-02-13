package com.ddaodan.MineChatGPT;

import com.ddaodan.MineChatGPT.service.ApiService;
import com.ddaodan.MineChatGPT.service.CommandService;
import com.ddaodan.MineChatGPT.service.RequestCoordinator;
import com.ddaodan.MineChatGPT.service.UserSessionManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
public class CommandHandler implements CommandExecutor {
    private final ConfigManager configManager;
    private final CommandService commandService;
    private final UserSessionManager sessionManager;

    public CommandHandler(ConfigManager configManager, UserSessionManager sessionManager, RequestCoordinator requestCoordinator) {
        this.configManager = configManager;
        this.sessionManager = sessionManager;
        this.commandService = new CommandService(configManager, requestCoordinator, sessionManager);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String userId = sender.getName();

        if (command.getName().equalsIgnoreCase("chatgpt")) {
            if (args.length == 0) {
                commandService.sendHelpMessage(sender);
                return true;
            }
            
            String subCommand = args[0];
            if (subCommand.equalsIgnoreCase("reload")) {
                return commandService.handleReloadCommand(sender);
            } else if (subCommand.equalsIgnoreCase("model")) {
                return commandService.handleModelCommand(sender, args);
            } else if (subCommand.equalsIgnoreCase("modellist")) {
                return commandService.handleModelListCommand(sender);
            } else if (subCommand.equalsIgnoreCase("context")) {
                return commandService.handleContextCommand(sender, userId);
            } else if (subCommand.equalsIgnoreCase("clear")) {
                return commandService.handleClearCommand(sender, userId);
            } else if (subCommand.equalsIgnoreCase("character")) {
                return commandService.handleCharacterCommand(sender, args, userId);
            } else if (subCommand.equalsIgnoreCase("stats")) {
                return commandService.handleStatsCommand(sender, args, userId);
            } else {
                return commandService.handleAskCommand(sender, args, userId);
            }
        }
        return false;
    }
}
