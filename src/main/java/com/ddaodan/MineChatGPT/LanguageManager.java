package com.ddaodan.MineChatGPT;

import com.ddaodan.MineChatGPT.util.ConfigFileUpdater;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class LanguageManager {
    private final Main plugin;
    private FileConfiguration langConfig;
    private String currentLanguage;
    private File langFile;

    public LanguageManager(Main plugin, String language) {
        this.plugin = plugin;
        this.currentLanguage = language;
        loadLanguage();
    }

    public void loadLanguage() {
        String resourcePath = "lang/" + currentLanguage + ".yml";
        langFile = new File(plugin.getDataFolder(), "lang" + File.separator + currentLanguage + ".yml");

        if (plugin.getResource(resourcePath) != null) {
            ConfigFileUpdater.UpdateResult updateResult = ConfigFileUpdater.updateIfMissingKeys(plugin, resourcePath);
            if (updateResult.updated) {
                plugin.getLogger().info("Language file updated (" + currentLanguage + "): inserted "
                        + updateResult.insertedPaths + " missing path(s). Backup: " + updateResult.backupFileName);
            }
        } else if (!langFile.exists()) {
            plugin.getLogger().warning("Language resource not found: " + resourcePath + ", file does not exist.");
        }

        langConfig = YamlConfiguration.loadConfiguration(langFile);

        InputStream defaultLangStream = plugin.getResource(resourcePath);
        if (defaultLangStream != null) {
            try (InputStreamReader reader = new InputStreamReader(defaultLangStream, StandardCharsets.UTF_8)) {
                YamlConfiguration defaultLang = YamlConfiguration.loadConfiguration(reader);
                langConfig.setDefaults(defaultLang);
            } catch (IOException ex) {
                plugin.getLogger().warning("Failed to load default language resource: " + ex.getMessage());
            }
        }
    }

    public void setLanguage(String language) {
        this.currentLanguage = language;
        loadLanguage();
    }

    public String getCurrentLanguage() {
        return currentLanguage;
    }

    private String translateColorCodes(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public String getMessage(String path) {
        return translateColorCodes(langConfig.getString("messages." + path, "Missing message: " + path));
    }

    public String getMessage(String path, String defaultValue) {
        return translateColorCodes(langConfig.getString("messages." + path, defaultValue));
    }
}
