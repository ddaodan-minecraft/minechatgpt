package com.ddaodan.MineChatGPT.service;

import com.ddaodan.MineChatGPT.ConfigManager;
import com.ddaodan.MineChatGPT.Main;
import jodd.http.HttpRequest;
import jodd.http.HttpResponse;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.json.JSONArray;
import org.json.JSONObject;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;

public class UpdateChecker implements Listener {
    private static final String GITHUB_API = "https://api.github.com/repos/ddaodan-minecraft/minechatgpt/releases/latest";
    private static final String MODRINTH_API = "https://api.modrinth.com/v2/project/minechatgpt/version?limit=1";
    private static final String GITHUB_URL = "https://github.com/ddaodan-minecraft/minechatgpt/releases/latest";
    private static final String MODRINTH_URL = "https://modrinth.com/plugin/minechatgpt";

    private final Main plugin;
    private final ConfigManager configManager;
    private final ExecutorService executor;
    private volatile String latestVersion;

    public UpdateChecker(Main plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "minechatgpt-update-checker");
            t.setDaemon(true);
            return t;
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    public void checkOnStartup() {
        if (!configManager.isUpdateCheckerEnabled()) {
            return;
        }
        checkAsync().thenAccept(result -> {
            if (result.error) {
                plugin.getLogger().warning("Failed to check for updates.");
            } else if (result.hasUpdate) {
                plugin.getLogger().info("A new version is available: " + result.latestVersion
                        + " (current: " + result.currentVersion + ")");
                plugin.getLogger().info("Download: " + result.downloadUrl);
            } else {
                plugin.getLogger().info("You are running the latest version (" + result.currentVersion + ").");
            }
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!configManager.isUpdateCheckerEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("minechatgpt.checkupdate")) {
            return;
        }
        if (latestVersion == null) {
            return;
        }
        String currentVersion = plugin.getDescription().getVersion();
        if (isNewerVersion(latestVersion, currentVersion)) {
            String downloadUrl = getDownloadUrl();
            runOnMainThreadDelayed(() -> {
                if (player.isOnline()) {
                    player.sendMessage(configManager.getUpdateAvailableMessage()
                            .replaceFirst("%s", latestVersion)
                            .replaceFirst("%s", currentVersion));
                    player.sendMessage(configManager.getUpdateDownloadMessage()
                            .replace("%s", downloadUrl));
                }
            }, 40L);
        }
    }

    public void checkAndNotify(CommandSender sender) {
        sender.sendMessage(configManager.getUpdateCheckingMessage());
        checkAsync().thenAccept(result -> {
            runOnMainThread(() -> {
                if (sender instanceof Player && !((Player) sender).isOnline()) {
                    return;
                }
                if (result.error) {
                    sender.sendMessage(configManager.getUpdateErrorMessage());
                    return;
                }
                if (result.hasUpdate) {
                    sender.sendMessage(configManager.getUpdateAvailableMessage()
                            .replaceFirst("%s", result.latestVersion)
                            .replaceFirst("%s", result.currentVersion));
                    sender.sendMessage(configManager.getUpdateDownloadMessage()
                            .replace("%s", result.downloadUrl));
                } else {
                    sender.sendMessage(configManager.getUpdateLatestMessage());
                }
            });
        });
    }

    private CompletableFuture<UpdateResult> checkAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String source = configManager.getUpdateCheckerSource();
                String currentVersion = plugin.getDescription().getVersion();
                String remote;

                if ("modrinth".equalsIgnoreCase(source)) {
                    remote = fetchModrinthVersion();
                } else {
                    remote = fetchGitHubVersion();
                }

                if (remote == null) {
                    return UpdateResult.errorResult();
                }

                latestVersion = remote;
                boolean hasUpdate = isNewerVersion(remote, currentVersion);
                return new UpdateResult(false, hasUpdate, remote, currentVersion, getDownloadUrl());
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to check for updates", e);
                return UpdateResult.errorResult();
            }
        }, executor);
    }

    private String fetchGitHubVersion() {
        HttpResponse response = HttpRequest.get(GITHUB_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "MineChatGPT/" + plugin.getDescription().getVersion())
                .connectionTimeout(10000)
                .timeout(15000)
                .send();
        if (response.statusCode() != 200) {
            return null;
        }
        JSONObject json = new JSONObject(response.bodyText());
        String tagName = json.getString("tag_name");
        return tagName.startsWith("v") ? tagName.substring(1) : tagName;
    }

    private String fetchModrinthVersion() {
        HttpResponse response = HttpRequest.get(MODRINTH_API)
                .header("User-Agent", "MineChatGPT/" + plugin.getDescription().getVersion())
                .connectionTimeout(10000)
                .timeout(15000)
                .send();
        if (response.statusCode() != 200) {
            return null;
        }
        JSONArray arr = new JSONArray(response.bodyText());
        if (arr.length() == 0) {
            return null;
        }
        return arr.getJSONObject(0).getString("version_number");
    }

    private void runOnMainThread(Runnable runnable) {
        if (isFoliaSchedulerAvailable()) {
            try {
                Object scheduler = getGlobalRegionScheduler();
                Method method = scheduler.getClass().getMethod("execute", Plugin.class, Runnable.class);
                method.invoke(scheduler, plugin, runnable);
                return;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to execute on Folia scheduler, fallback to Bukkit.", e);
            }
        }
        Bukkit.getScheduler().runTask(plugin, runnable);
    }

    private void runOnMainThreadDelayed(Runnable runnable, long delayTicks) {
        if (isFoliaSchedulerAvailable()) {
            try {
                Object scheduler = getGlobalRegionScheduler();
                Consumer<Object> consumer = task -> runnable.run();
                Method method = scheduler.getClass().getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
                method.invoke(scheduler, plugin, consumer, delayTicks);
                return;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to schedule delayed Folia task, fallback to Bukkit.", e);
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
    }

    private boolean isFoliaSchedulerAvailable() {
        try {
            Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private Object getGlobalRegionScheduler() throws Exception {
        Method method = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler");
        return method.invoke(Bukkit.getServer());
    }

    private String getDownloadUrl() {
        String source = configManager.getUpdateCheckerSource();
        return "modrinth".equalsIgnoreCase(source) ? MODRINTH_URL : GITHUB_URL;
    }

    static boolean isNewerVersion(String remote, String current) {
        String[] remoteParts = remote.split("\\.");
        String[] currentParts = current.split("\\.");
        int length = Math.max(remoteParts.length, currentParts.length);
        for (int i = 0; i < length; i++) {
            int r = i < remoteParts.length ? parseIntSafe(remoteParts[i]) : 0;
            int c = i < currentParts.length ? parseIntSafe(currentParts[i]) : 0;
            if (r > c) return true;
            if (r < c) return false;
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final class UpdateResult {
        final boolean error;
        final boolean hasUpdate;
        final String latestVersion;
        final String currentVersion;
        final String downloadUrl;

        UpdateResult(boolean error, boolean hasUpdate, String latestVersion, String currentVersion, String downloadUrl) {
            this.error = error;
            this.hasUpdate = hasUpdate;
            this.latestVersion = latestVersion;
            this.currentVersion = currentVersion;
            this.downloadUrl = downloadUrl;
        }

        static UpdateResult errorResult() {
            return new UpdateResult(true, false, null, null, null);
        }
    }
}
