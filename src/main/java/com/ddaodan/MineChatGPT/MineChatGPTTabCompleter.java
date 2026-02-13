package com.ddaodan.MineChatGPT;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
public class MineChatGPTTabCompleter implements TabCompleter {

    private final ConfigManager configManager;

    public MineChatGPTTabCompleter(ConfigManager configManager) {
        this.configManager = configManager;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // 补全子命令
            completions.add("reload");
            completions.add("model");
            completions.add("modellist");
            completions.add("context");
            completions.add("clear");
            completions.add("character");
            completions.add("stats");
            completions.add("checkupdate");
        } else if (args.length == 2) {
            String subCommand = args[0];
            if (subCommand.equalsIgnoreCase("model")) {
                completions.addAll(configManager.getModels());
            } else if (subCommand.equalsIgnoreCase("character")) {
                completions.addAll(configManager.getCharacters().keySet());
            } else if (subCommand.equalsIgnoreCase("stats")) {
                completions.add("reset");
            }
        }

        // 过滤补全列表以匹配输入
        completions.removeIf(completion -> !completion.toLowerCase().startsWith(args[args.length - 1].toLowerCase()));

        return completions;
    }
}
