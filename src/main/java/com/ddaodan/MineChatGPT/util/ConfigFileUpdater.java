package com.ddaodan.MineChatGPT.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class ConfigFileUpdater {
    private ConfigFileUpdater() {
    }

    public static UpdateResult updateIfMissingKeys(JavaPlugin plugin, String resourcePath) {
        File configFile = new File(plugin.getDataFolder(), resourcePath);
        if (!configFile.exists()) {
            plugin.saveResource(resourcePath, false);
            return UpdateResult.noChange();
        }

        try {
            YamlConfiguration currentYaml = YamlConfiguration.loadConfiguration(configFile);
            YamlConfiguration defaultYaml = loadYamlFromResource(plugin, resourcePath);
            if (defaultYaml == null) {
                return UpdateResult.noChange();
            }

            List<String> defaultLines = readResourceLines(plugin, resourcePath);
            if (defaultLines.isEmpty()) {
                return UpdateResult.noChange();
            }
            DocumentModel defaultModel = parseDocument(defaultLines);

            List<String> missingTopLevelPaths = collectMissingTopPaths(currentYaml, defaultYaml, defaultModel);
            if (missingTopLevelPaths.isEmpty()) {
                return UpdateResult.noChange();
            }

            List<String> currentLines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);
            int inserted = 0;
            for (String missingPath : missingTopLevelPaths) {
                Entry source = defaultModel.entriesByPath.get(missingPath);
                if (source == null) {
                    continue;
                }

                List<String> snippet = new ArrayList<>(
                        defaultLines.subList(source.startLine, source.endLine + 1)
                );
                if (snippet.isEmpty()) {
                    continue;
                }

                DocumentModel currentModel = parseDocument(currentLines);
                int insertAt = findInsertIndex(currentLines, currentModel, defaultModel, missingPath);
                if (insertAt < 0 || insertAt > currentLines.size()) {
                    insertAt = currentLines.size();
                }
                insertSnippetWithSpacing(currentLines, insertAt, snippet);
                inserted++;
            }

            if (inserted == 0) {
                return UpdateResult.noChange();
            }

            File backup = createBackup(configFile);
            Files.write(configFile.toPath(), currentLines, StandardCharsets.UTF_8);
            return UpdateResult.updated(inserted, backup.getName());
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to auto-update config.yml: " + ex.getMessage());
            return UpdateResult.noChange();
        }
    }

    private static YamlConfiguration loadYamlFromResource(JavaPlugin plugin, String resourcePath) {
        InputStream inputStream = plugin.getResource(resourcePath);
        if (inputStream == null) {
            return null;
        }
        try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to read default config resource: " + ex.getMessage());
            return null;
        }
    }

    private static List<String> collectMissingTopPaths(
            YamlConfiguration currentYaml,
            YamlConfiguration defaultYaml,
            DocumentModel defaultModel
    ) {
        Set<String> missing = new TreeSet<String>();
        for (String key : defaultYaml.getKeys(true)) {
            if (!currentYaml.contains(key)) {
                missing.add(key);
            }
        }
        if (missing.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> top = new ArrayList<String>();
        for (Entry entry : defaultModel.entriesInOrder) {
            String path = entry.path;
            if (!missing.contains(path)) {
                continue;
            }
            if (!hasAncestor(top, path)) {
                top.add(path);
            }
        }
        return top;
    }

    private static boolean hasAncestor(List<String> selected, String path) {
        int index = path.lastIndexOf('.');
        while (index > 0) {
            String parent = path.substring(0, index);
            if (selected.contains(parent)) {
                return true;
            }
            index = parent.lastIndexOf('.');
        }
        return false;
    }

    private static List<String> readResourceLines(JavaPlugin plugin, String resourcePath) throws IOException {
        InputStream inputStream = plugin.getResource(resourcePath);
        if (inputStream == null) {
            return Collections.emptyList();
        }
        List<String> lines = new ArrayList<String>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static int findInsertIndex(
            List<String> currentLines,
            DocumentModel currentModel,
            DocumentModel defaultModel,
            String path
    ) {
        String parentPath = getParentPath(path);
        int beforeSibling = findNextExistingSiblingStartLine(currentModel, defaultModel, path, parentPath);
        if (beforeSibling >= 0) {
            return beforeSibling;
        }

        if (parentPath == null) {
            Entry lastRoot = null;
            for (Entry entry : currentModel.entriesInOrder) {
                if (getParentPath(entry.path) == null) {
                    lastRoot = entry;
                }
            }
            return lastRoot == null ? currentLines.size() : lastRoot.endLine + 1;
        }

        Entry parent = currentModel.entriesByPath.get(parentPath);
        if (parent == null) {
            return currentLines.size();
        }
        return parent.endLine + 1;
    }

    private static int findNextExistingSiblingStartLine(
            DocumentModel currentModel,
            DocumentModel defaultModel,
            String path,
            String parentPath
    ) {
        boolean foundSelf = false;
        for (Entry defaultEntry : defaultModel.entriesInOrder) {
            if (!isDirectChildOf(defaultEntry.path, parentPath)) {
                continue;
            }

            if (!foundSelf) {
                if (defaultEntry.path.equals(path)) {
                    foundSelf = true;
                }
                continue;
            }

            Entry existingSibling = currentModel.entriesByPath.get(defaultEntry.path);
            if (existingSibling != null) {
                return existingSibling.startLine;
            }
        }
        return -1;
    }

    private static boolean isDirectChildOf(String path, String parentPath) {
        String actualParent = getParentPath(path);
        if (parentPath == null) {
            return actualParent == null;
        }
        return parentPath.equals(actualParent);
    }

    private static String getParentPath(String path) {
        int dot = path.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        return path.substring(0, dot);
    }

    private static void insertSnippetWithSpacing(List<String> lines, int insertAt, List<String> snippet) {
        if (snippet.isEmpty()) {
            return;
        }

        // Avoid carrying leading/trailing blank lines from the template snippet.
        int from = 0;
        int to = snippet.size() - 1;
        while (from <= to && snippet.get(from).trim().isEmpty()) {
            from++;
        }
        while (to >= from && snippet.get(to).trim().isEmpty()) {
            to--;
        }
        if (from > to) {
            return;
        }

        List<String> normalized = new ArrayList<String>(snippet.subList(from, to + 1));
        int index = insertAt;
        boolean isTopLevel = isTopLevelSnippet(normalized);
        if (isTopLevel
                && index > 0
                && !lines.get(index - 1).trim().isEmpty()
                && !normalized.get(0).trim().isEmpty()
                && (index >= lines.size() || !lines.get(index).trim().isEmpty())) {
            lines.add(index, "");
            index++;
        }
        lines.addAll(index, normalized);
    }

    private static boolean isTopLevelSnippet(List<String> snippet) {
        for (String line : snippet) {
            KeyLine keyLine = parseKeyLine(line);
            if (keyLine != null) {
                return keyLine.indent == 0;
            }
        }
        return false;
    }

    private static File createBackup(File configFile) throws IOException {
        String suffix = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File backup = new File(configFile.getParentFile(), configFile.getName() + ".bak." + suffix);
        Files.copy(configFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    private static DocumentModel parseDocument(List<String> lines) {
        List<Entry> ordered = new ArrayList<Entry>();
        Map<String, Entry> byPath = new LinkedHashMap<String, Entry>();
        ArrayDeque<Entry> stack = new ArrayDeque<Entry>();

        for (int i = 0; i < lines.size(); i++) {
            KeyLine keyLine = parseKeyLine(lines.get(i));
            if (keyLine == null) {
                continue;
            }

            while (!stack.isEmpty() && keyLine.indent <= stack.peek().indent) {
                stack.pop();
            }

            String path = stack.isEmpty() ? keyLine.key : stack.peek().path + "." + keyLine.key;
            Entry entry = new Entry(path, keyLine.indent, i);
            ordered.add(entry);
            byPath.put(path, entry);
            stack.push(entry);
        }

        for (Entry entry : ordered) {
            entry.startLine = findSnippetStartLine(lines, entry.line);
            entry.endLine = findValueEndLine(lines, entry.line, entry.indent);
        }

        return new DocumentModel(ordered, byPath);
    }

    private static int findSnippetStartLine(List<String> lines, int keyLineIndex) {
        int start = keyLineIndex;
        for (int i = keyLineIndex - 1; i >= 0; i--) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("#")) {
                start = i;
                continue;
            }
            break;
        }
        return start;
    }

    private static int findValueEndLine(List<String> lines, int keyLineIndex, int keyIndent) {
        int end = keyLineIndex;
        for (int i = keyLineIndex + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            KeyLine keyLine = parseKeyLine(line);
            if (keyLine != null && keyLine.indent <= keyIndent) {
                break;
            }

            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                end = i;
            }
        }
        return end;
    }

    private static KeyLine parseKeyLine(String line) {
        if (line == null) {
            return null;
        }

        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
            return null;
        }

        int colon = trimmed.indexOf(':');
        if (colon <= 0) {
            return null;
        }

        String key = trimmed.substring(0, colon).trim();
        if (key.isEmpty()) {
            return null;
        }

        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        return new KeyLine(key, indent);
    }

    public static final class UpdateResult {
        public final boolean updated;
        public final int insertedPaths;
        public final String backupFileName;

        private UpdateResult(boolean updated, int insertedPaths, String backupFileName) {
            this.updated = updated;
            this.insertedPaths = insertedPaths;
            this.backupFileName = backupFileName;
        }

        private static UpdateResult noChange() {
            return new UpdateResult(false, 0, "");
        }

        private static UpdateResult updated(int insertedPaths, String backupFileName) {
            return new UpdateResult(true, insertedPaths, backupFileName);
        }
    }

    private static final class DocumentModel {
        private final List<Entry> entriesInOrder;
        private final Map<String, Entry> entriesByPath;

        private DocumentModel(List<Entry> entriesInOrder, Map<String, Entry> entriesByPath) {
            this.entriesInOrder = entriesInOrder;
            this.entriesByPath = entriesByPath;
        }
    }

    private static final class Entry {
        private final String path;
        private final int indent;
        private final int line;
        private int startLine;
        private int endLine;

        private Entry(String path, int indent, int line) {
            this.path = path;
            this.indent = indent;
            this.line = line;
            this.startLine = line;
            this.endLine = line;
        }
    }

    private static final class KeyLine {
        private final String key;
        private final int indent;

        private KeyLine(String key, int indent) {
            this.key = key;
            this.indent = indent;
        }
    }
}
