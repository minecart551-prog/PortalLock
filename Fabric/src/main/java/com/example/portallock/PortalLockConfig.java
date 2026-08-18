package com.example.portallock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PortalLockConfig {

    private static final File DIR = new File("config/portal-lock");
    private static final File FILE = new File(DIR, "config.yml");
    private static final File LEGACY_YAML_FILE = new File(DIR, "portal-lock.yml");
    private static final File LEGACY_JSON_IN_DIR = new File(DIR, "portal-lock.json");
    private static final File LEGACY_JSON_ROOT = new File("config/portal-lock.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Data DATA = new Data();

    public static class Data {
        public boolean nether_enabled = true;
        public boolean end_enabled = true;

        public List<String> blocked_items = new ArrayList<>();
        public String blocked_message = "";
        public boolean blocked_overlay = true;
        public String blocked_fail_sound = "minecraft:block.anvil.place";

        public float volume = 1.0f;
        public float pitch = 1.2f;

        public boolean admin_activation = false;
        public int activation_radius = 16;
        public String portal_denied_message = "";

        public String language_mode = "auto";
        public String fixed_language = "en_us";
        public String fallback_language = "en_us";
    }

    public static void load() {
        migrateLegacyIfNeeded();
        backupLegacyJsonFiles();

        try {
            if (!FILE.exists()) {
                DATA = new Data();
                save();
                return;
            }

            DATA = readYaml(FILE);
            if (DATA == null) {
                DATA = new Data();
                save();
                return;
            }

            if (applyDefaultsAndNormalize()) {
                save();
            }
        } catch (Exception e) {
            e.printStackTrace();
            DATA = new Data();
            save();
        }
    }

    private static boolean applyDefaultsAndNormalize() {
        boolean changed = false;

        if (DATA.blocked_items == null) {
            DATA.blocked_items = new ArrayList<>();
            changed = true;
        }
        if (DATA.blocked_message == null) {
            DATA.blocked_message = "";
            changed = true;
        }
        if (DATA.blocked_fail_sound == null || DATA.blocked_fail_sound.isBlank()) {
            DATA.blocked_fail_sound = "minecraft:block.anvil.place";
            changed = true;
        }
        if (DATA.language_mode == null || DATA.language_mode.isBlank()) {
            DATA.language_mode = "auto";
            changed = true;
        }
        if (DATA.fixed_language == null || DATA.fixed_language.isBlank()) {
            DATA.fixed_language = "en_us";
            changed = true;
        }
        if (DATA.fallback_language == null || DATA.fallback_language.isBlank()) {
            DATA.fallback_language = "en_us";
            changed = true;
        }

        String normalizedMode = DATA.language_mode.trim().toLowerCase(Locale.ROOT);
        if (!normalizedMode.equals(DATA.language_mode)) {
            DATA.language_mode = normalizedMode;
            changed = true;
        }

        String normalizedFixed = normalizeLocale(DATA.fixed_language);
        if (!normalizedFixed.equals(DATA.fixed_language)) {
            DATA.fixed_language = normalizedFixed;
            changed = true;
        }

        String normalizedFallback = normalizeLocale(DATA.fallback_language);
        if (!normalizedFallback.equals(DATA.fallback_language)) {
            DATA.fallback_language = normalizedFallback;
            changed = true;
        }

        return changed;
    }

    private static void migrateLegacyIfNeeded() {
        if (FILE.exists()) {
            return;
        }

        try {
            DIR.mkdirs();

            if (LEGACY_YAML_FILE.exists()) {
                DATA = readYaml(LEGACY_YAML_FILE);
                if (DATA == null) {
                    DATA = new Data();
                }
                applyDefaultsAndNormalize();
                save();
                System.out.println("[PortalLock] Migrated config from config/portal-lock/portal-lock.yml to config/portal-lock/config.yml");
                return;
            }

            if (LEGACY_JSON_IN_DIR.exists()) {
                DATA = readLegacyJson(LEGACY_JSON_IN_DIR);
                applyDefaultsAndNormalize();
                save();
                System.out.println("[PortalLock] Migrated config from config/portal-lock/portal-lock.json to config/portal-lock/config.yml");
                return;
            }

            if (LEGACY_JSON_ROOT.exists()) {
                DATA = readLegacyJson(LEGACY_JSON_ROOT);
                applyDefaultsAndNormalize();
                save();
                System.out.println("[PortalLock] Migrated config from config/portal-lock.json to config/portal-lock/config.yml");
            }
        } catch (Exception e) {
            System.out.println("[PortalLock] Failed to migrate legacy config; using defaults.");
            DATA = new Data();
            save();
        }
    }

    private static void backupLegacyJsonFiles() {
        backupLegacyJsonFile(LEGACY_JSON_IN_DIR, "config/portal-lock/portal-lock.json");
        backupLegacyJsonFile(LEGACY_JSON_ROOT, "config/portal-lock.json");
    }

    private static void backupLegacyJsonFile(File legacyFile, String label) {
        if (!legacyFile.exists()) {
            return;
        }
        File backup = new File(legacyFile.getParentFile(), legacyFile.getName() + ".bak");
        if (backup.exists()) {
            if (legacyFile.delete()) {
                System.out.println("[PortalLock] Removed legacy config after migration because backup already exists: " + label);
            }
            return;
        }
        try {
            Files.move(legacyFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[PortalLock] Backed up legacy JSON config to " + backup.getPath().replace('\\', '/'));
        } catch (IOException e) {
            System.out.println("[PortalLock] Failed to back up legacy config: " + label);
        }
    }

    private static Data readLegacyJson(File file) {
        try (FileReader reader = new FileReader(file)) {
            Data migrated = GSON.fromJson(reader, Data.class);
            return migrated != null ? migrated : new Data();
        } catch (Exception e) {
            return new Data();
        }
    }

    private static Data readYaml(File file) throws IOException {
        Data data = new Data();
        Map<String, String> values = new LinkedHashMap<>();

        try (BufferedReader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                int colon = line.indexOf(':');
                if (colon < 0) {
                    continue;
                }

                String key = line.substring(0, colon).trim();
                if (key.isEmpty()) {
                    continue;
                }

                String rawValue = line.substring(colon + 1).trim();
                if (rawValue.equals("") || rawValue.equals("[]")) {
                    // Check if next lines are list items
                    values.put(key, "[]");
                } else {
                    values.put(key, parseYamlScalar(rawValue));
                }
            }
        }

        data.nether_enabled = parseBoolean(values.get("nether_enabled"), data.nether_enabled);
        data.end_enabled = parseBoolean(values.get("end_enabled"), data.end_enabled);

        data.blocked_items = parseInlineList(values.getOrDefault("blocked_items", "[]"));
        data.blocked_message = values.getOrDefault("blocked_message", data.blocked_message);
        data.blocked_overlay = parseBoolean(values.get("blocked_overlay"), data.blocked_overlay);
        data.blocked_fail_sound = values.getOrDefault("blocked_fail_sound", data.blocked_fail_sound);

        data.volume = parseFloat(values.get("volume"), data.volume);
        data.pitch = parseFloat(values.get("pitch"), data.pitch);

        data.admin_activation = parseBoolean(values.get("admin_activation"), data.admin_activation);
        data.activation_radius = parseInt(values.get("activation_radius"), data.activation_radius);
        data.portal_denied_message = values.getOrDefault("portal_denied_message", data.portal_denied_message);

        data.language_mode = values.getOrDefault("language_mode", data.language_mode);
        data.fixed_language = values.getOrDefault("fixed_language", data.fixed_language);
        data.fallback_language = values.getOrDefault("fallback_language", data.fallback_language);

        return data;
    }

    private static List<String> parseInlineList(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) return result;
        String trimmed = raw.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return result;
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) return result;

        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < inner.length(); i++) {
            char ch = inner.charAt(i);
            if (inQuote) {
                if (ch == '\\') {
                    if (i + 1 < inner.length()) {
                        current.append(inner.charAt(i + 1));
                        i++;
                    }
                } else if (ch == quoteChar) {
                    inQuote = false;
                } else {
                    current.append(ch);
                }
            } else {
                if (ch == '"' || ch == '\'') {
                    inQuote = true;
                    quoteChar = ch;
                } else if (ch == ',') {
                    String item = current.toString().trim();
                    if (!item.isEmpty()) result.add(item);
                    current.setLength(0);
                } else {
                    current.append(ch);
                }
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) result.add(last);
        return result;
    }

    private static String parseYamlScalar(String rawValue) {
        if (rawValue == null) {
            return "";
        }
        if ((rawValue.startsWith("\"") && rawValue.endsWith("\"")) || (rawValue.startsWith("'") && rawValue.endsWith("'"))) {
            String inner = rawValue.substring(1, rawValue.length() - 1);
            return unescapeYaml(inner);
        }
        return rawValue;
    }

    private static String unescapeYaml(String value) {
        return value
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        return fallback;
    }

    private static float parseFloat(String value, float fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Float.parseFloat(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return "en_us";
        }
        return locale.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    public static void save() {
        try {
            DIR.mkdirs();
            try (FileWriter writer = new FileWriter(FILE, StandardCharsets.UTF_8)) {
                writer.write(buildYaml());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String buildYaml() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ==============================\n");
        sb.append("# Portal Lock Configuration\n");
        sb.append("# ==============================\n\n");

        sb.append("# Enable or disable the Nether portal lock.\n");
        sb.append(writeBoolean("nether_enabled", DATA.nether_enabled));
        sb.append("\n");

        sb.append("# Enable or disable the End portal lock.\n");
        sb.append(writeBoolean("end_enabled", DATA.end_enabled));
        sb.append("\n");

        sb.append("# List of item IDs that block portal entry.\n");
        sb.append("# Players carrying any of these items cannot enter portals.\n");
        sb.append("# Leave empty for no blocked items.\n");
        sb.append("#\n");
        sb.append("# Example:\n");
        sb.append("# blocked_items:\n");
        sb.append("#   - \"minecraft:elytra\"\n");
        sb.append("#   - \"minecraft:totem_of_undying\"\n");
        sb.append("#   - \"minecraft:netherite_sword\"\n");
        sb.append(writeList("blocked_items", DATA.blocked_items));
        sb.append("\n");

        sb.append("# Optional custom blocked message.\n");
        sb.append("# Leave blank to use language files automatically.\n");
        sb.append("#\n");
        sb.append("# By default, edit messages in config/portal-lock/lang/<language>.json\n");
        sb.append("# If set here, this message overrides all language files.\n");
        sb.append("#\n");
        sb.append("# Supports color codes:\n");
        sb.append("# &0-&9, &a-&f, &r\n");
        sb.append("#\n");
        sb.append("# Supports placeholders:\n");
        sb.append("# %item%\n");
        sb.append("# %item_id%\n");
        sb.append(writeString("blocked_message", DATA.blocked_message));
        sb.append("\n");

        sb.append("# Show the blocked message in the action bar overlay.\n");
        sb.append("# If false, the message will be shown in the chat.\n");
        sb.append(writeBoolean("blocked_overlay", DATA.blocked_overlay));
        sb.append("\n");

        sb.append("# Sound played when portal entry is blocked.\n");
        sb.append(writeString("blocked_fail_sound", DATA.blocked_fail_sound));
        sb.append("\n");

        sb.append("# Volume for the blocked sound.\n");
        sb.append(writeFloat("volume", DATA.volume));
        sb.append("\n");

        sb.append("# Pitch for the blocked sound.\n");
        sb.append(writeFloat("pitch", DATA.pitch));
        sb.append("\n");

        sb.append("# Require admin activation for portals to work.\n");
        sb.append("# When enabled, portals show visuals but block teleportation until an admin activates them.\n");
        sb.append("# Use /pl activate and /pl deactivate commands.\n");
        sb.append(writeBoolean("admin_activation", DATA.admin_activation));
        sb.append("\n");

        sb.append("# Radius (in blocks) to check for activated portals.\n");
        sb.append(writeInt("activation_radius", DATA.activation_radius));
        sb.append("\n");

        sb.append("# Message shown when a portal is not activated.\n");
        sb.append("# Leave blank to use language files automatically.\n");
        sb.append(writeString("portal_denied_message", DATA.portal_denied_message));
        sb.append("\n");

        sb.append("# Language mode: auto or fixed.\n");
        sb.append(writeString("language_mode", DATA.language_mode));
        sb.append("\n");

        sb.append("# Used only when language_mode is fixed. Example: en_us, ja_jp\n");
        sb.append(writeString("fixed_language", DATA.fixed_language));
        sb.append("\n");

        sb.append("# Fallback language when the client locale is unsupported.\n");
        sb.append(writeString("fallback_language", DATA.fallback_language));

        return sb.toString();
    }

    private static String writeString(String key, String value) {
        return key + ": \"" + escapeYaml(value == null ? "" : value) + "\"\n";
    }

    private static String writeBoolean(String key, boolean value) {
        return key + ": " + value + "\n";
    }

    private static String writeList(String key, List<String> items) {
        if (items == null || items.isEmpty()) {
            return key + ": []\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(key).append(": [");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(escapeYaml(items.get(i))).append("\"");
        }
        sb.append("]\n");
        return sb.toString();
    }

    private static String writeFloat(String key, float value) {
        if (value == (long) value) {
            return key + ": " + (long) value + ".0\n";
        }
        return key + ": " + value + "\n";
    }

    private static String writeInt(String key, int value) {
        return key + ": " + value + "\n";
    }

    private static String escapeYaml(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
