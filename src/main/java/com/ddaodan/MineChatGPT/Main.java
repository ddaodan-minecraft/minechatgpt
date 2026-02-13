package com.ddaodan.MineChatGPT;

import org.bukkit.plugin.java.JavaPlugin;
import org.bstats.bukkit.Metrics;

import java.util.Objects;

public final class Main extends JavaPlugin {
    private ConfigManager configManager;
    private com.ddaodan.MineChatGPT.service.UserSessionManager sessionManager;
    private com.ddaodan.MineChatGPT.service.ApiService apiService;
    private com.ddaodan.MineChatGPT.service.RequestCoordinator requestCoordinator;
    private com.ddaodan.MineChatGPT.service.UpdateChecker updateChecker;
    private CommandHandler commandHandler;
    private MineChatGPTTabCompleter tabCompleter;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configManager = new ConfigManager(this);
        sessionManager = new com.ddaodan.MineChatGPT.service.UserSessionManager(configManager);
        apiService = new com.ddaodan.MineChatGPT.service.ApiService(this, configManager);
        requestCoordinator = new com.ddaodan.MineChatGPT.service.RequestCoordinator(this, configManager, apiService, sessionManager);
        requestCoordinator.start();
        updateChecker = new com.ddaodan.MineChatGPT.service.UpdateChecker(this, configManager);
        commandHandler = new CommandHandler(configManager, sessionManager, requestCoordinator, updateChecker);
        tabCompleter = new MineChatGPTTabCompleter(configManager);
        Objects.requireNonNull(getCommand("chatgpt")).setExecutor(commandHandler);
        Objects.requireNonNull(getCommand("chatgpt")).setTabCompleter(tabCompleter);
        getServer().getPluginManager().registerEvents(updateChecker, this);
        updateChecker.checkOnStartup();
        if (configManager.isDebugMode()) {
            getLogger().info( "DEBUG MODE IS TRUE!!!!!");
        }
        // Initialize bStats
        int pluginId = 22635;
        new Metrics(this, pluginId);
    }

    @Override
    public void onDisable() {
        if (requestCoordinator != null) {
            requestCoordinator.stop();
        }
        if (updateChecker != null) {
            updateChecker.shutdown();
        }
        if (apiService != null) {
            apiService.shutdown();
        }
        saveConfig();
    }
}
