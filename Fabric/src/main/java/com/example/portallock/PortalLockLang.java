package com.example.portallock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class PortalLockLang {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
    private static final File LANG_DIR = new File("config/portal-lock/lang");
    private static final Pattern LOCALE_PATTERN = Pattern.compile("^[a-z]{2}_[a-z]{2}$");
    private static final String[] SUPPORTED = {"en_us", "ja_jp", "ko_kr", "zh_cn", "zh_tw", "es_es", "it_it", "de_de", "pt_br", "fr_fr", "ru_ru"};

    private static final Map<String, Map<String, String>> DEFAULT_LANGS = loadBundledDefaults();
    private static final Map<String, Map<String, String>> LOADED_LANGS = new LinkedHashMap<>();

    private PortalLockLang() {}

    public static void load() {
        LANG_DIR.mkdirs();
        LOADED_LANGS.clear();

        for (String locale : SUPPORTED) {
            Map<String, String> defaults = DEFAULT_LANGS.getOrDefault(locale, fallbackDefaults(locale));
            File file = new File(LANG_DIR, locale + ".json");
            if (!file.exists()) {
                writeLangFile(file, defaults);
            }

            Map<String, String> merged = new LinkedHashMap<>(defaults);
            Map<String, String> user = readLangFile(file);
            boolean changed = false;
            for (Map.Entry<String, String> e : user.entrySet()) {
                String upgraded = upgradeLegacyValue(e.getKey(), e.getValue(), defaults.get(e.getKey()));
                if (!upgraded.equals(e.getValue())) changed = true;
                merged.put(e.getKey(), upgraded);
            }
            if (changed) writeLangFile(file, merged);
            LOADED_LANGS.put(locale, merged);
        }
        if (!LOADED_LANGS.containsKey("en_us")) {
            LOADED_LANGS.put("en_us", fallbackDefaults("en_us"));
        }
    }

    public static void setPlayerLocale(Object player, String locale) {
        // 26.x uses Mojang names and reads clientInformation() lazily.
    }

    public static String getMessageTemplate(Object player, String key, String overrideMessage, String codeFallback) {
        if (overrideMessage != null && !overrideMessage.isBlank()) return overrideMessage;

        String locale = resolveEffectiveLocale(player);
        String message = lookup(locale, key);
        if (message != null && !message.isBlank()) return message;

        String fallback = normalizeLocale(PortalLockConfig.DATA.fallback_language);
        message = lookup(fallback, key);
        if (message != null && !message.isBlank()) return message;

        message = lookup("en_us", key);
        return message != null && !message.isBlank() ? message : (codeFallback == null ? "" : codeFallback);
    }

    public static String resolveEffectiveLocale(Object player) {
        String fallback = normalizeLocale(PortalLockConfig.DATA.fallback_language);
        if (!LOADED_LANGS.containsKey(fallback)) fallback = "en_us";

        String mode = PortalLockConfig.DATA.language_mode == null ? "auto" : PortalLockConfig.DATA.language_mode.trim().toLowerCase(Locale.ROOT);
        if (mode.equals("fixed")) {
            String fixed = normalizeLocale(PortalLockConfig.DATA.fixed_language);
            return LOADED_LANGS.containsKey(fixed) ? fixed : fallback;
        }

        String detected = resolvePlayerLocale(player);
        if (detected != null && LOADED_LANGS.containsKey(detected)) return detected;
        return fallback;
    }

    private static String resolvePlayerLocale(Object player) {
        if (player == null) return null;
        try {
            Object info = callNoArg(player, "clientInformation", "getClientInformation", "clientOptions", "getClientOptions");
            String lang = stringFromInfo(info, "language", "getLanguage", "lang", "locale");
            if (lang != null) return normalizeLocale(lang);
        } catch (Throwable ignored) {}
        try {
            String lang = stringFromInfo(player, "language", "getLanguage", "locale", "getLocale");
            if (lang != null) return normalizeLocale(lang);
        } catch (Throwable ignored) {}
        return null;
    }

    private static String stringFromInfo(Object target, String... names) throws Exception {
        if (target == null) return null;
        Object value = callNoArg(target, names);
        return value instanceof String s ? s : null;
    }

    private static Object callNoArg(Object target, String... names) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            for (String name : names) {
                try {
                    Method m = c.getDeclaredMethod(name);
                    m.setAccessible(true);
                    return m.invoke(target);
                } catch (NoSuchMethodException ignored) {}
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(target);
                } catch (NoSuchFieldException ignored) {}
            }
            c = c.getSuperclass();
        }
        throw new NoSuchMethodException();
    }

    private static String lookup(String locale, String key) {
        Map<String, String> map = LOADED_LANGS.get(normalizeLocale(locale));
        return map == null ? null : map.get(key);
    }

    private static Map<String, Map<String, String>> loadBundledDefaults() {
        Map<String, Map<String, String>> defaults = new LinkedHashMap<>();
        for (String locale : SUPPORTED) {
            Map<String, String> fromAsset = readBundledLangFile(locale);
            defaults.put(locale, fromAsset.isEmpty() ? fallbackDefaults(locale) : fromAsset);
        }
        return defaults;
    }

    private static Map<String, String> readBundledLangFile(String locale) {
        String path = "/assets/portal-lock/lang/" + locale + ".json";
        try (InputStream stream = PortalLockLang.class.getResourceAsStream(path)) {
            if (stream == null) return Collections.emptyMap();
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                Map<String, String> loaded = GSON.fromJson(reader, MAP_TYPE);
                return loaded == null ? Collections.emptyMap() : loaded;
            }
        } catch (Exception e) {
            System.out.println("[PortalLock] Failed to read bundled lang file: " + path);
            return Collections.emptyMap();
        }
    }

    private static Map<String, String> readLangFile(File file) {
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            Map<String, String> loaded = GSON.fromJson(reader, MAP_TYPE);
            return loaded == null ? Collections.emptyMap() : loaded;
        } catch (Exception e) {
            System.out.println("[PortalLock] Failed to read lang file: " + file.getName());
            return Collections.emptyMap();
        }
    }

    private static void writeLangFile(File file, Map<String, String> values) {
        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(values, MAP_TYPE, writer);
        } catch (Exception e) {
            System.out.println("[PortalLock] Failed to write lang file: " + file.getName());
        }
    }

    private static String upgradeLegacyValue(String key, String value, String defaultValue) {
        if (value == null) return defaultValue == null ? "" : defaultValue;
        if ("blocked-denied".equals(key) && defaultValue != null) {
            String plain = value.replace("&c", "").replace("&e", "").replace("§c", "").replace("§e", "").trim();
            if (!value.contains("&") && plain.contains("%item%")) return defaultValue;
        }
        return value;
    }

    private static Map<String, String> fallbackDefaults(String locale) {
        Map<String, String> map = new LinkedHashMap<>();
        switch (normalizeLocale(locale)) {
            case "ja_jp" -> {
                map.put("blocked-denied", "&c&c%item%&cを持っている間は入場できません！");
            }
            default -> {
                map.put("blocked-denied", "&cYou cannot enter while carrying &e%item%&c!");
            }
        }
        return map;
    }

    private static String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) return "en_us";
        String normalized = locale.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return LOCALE_PATTERN.matcher(normalized).matches() ? normalized : "en_us";
    }
}
