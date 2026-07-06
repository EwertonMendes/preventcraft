package tblack.preventcraft.i18n;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import tblack.preventcraft.ModConstants;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class I18n {
    private static final String SERVER_TRANSLATION_PREFIX = "server." + ModConstants.MOD_ID + ".";
    private static final String LANGUAGE_KEY_PREFIX = ModConstants.MOD_ID + ".";
    private static final String LANGUAGE_PATH_PREFIX = "Server/Languages/";
    private static final Map<String, Properties> CACHE = new ConcurrentHashMap<>();

    private static final String[] LOCALE_METHODS = {
            "getLanguage",
            "language",
            "getLocale",
            "locale",
            "getLanguageCode",
            "getLanguageTag",
            "getClientLocale",
            "getClientLanguage"
    };

    private static final String[] LOCALE_CONTAINER_METHODS = {
            "getPlayerRef",
            "getClientInfo",
            "getClientInformation",
            "getClientSettings",
            "getSettings",
            "getPreferences",
            "getConnection",
            "getSession",
            "getProfile"
    };

    private I18n() {
    }

    public static String commandKey(String relativeKey) {
        return SERVER_TRANSLATION_PREFIX + stripKnownPrefix(relativeKey);
    }

    public static String locale(CommandContext context) {
        return localeFromContext(context);
    }

    public static String localeFromPlayer(Player player) {
        if (player == null) return ModConstants.DEFAULT_LOCALE;
        String locale = findLocale(player, 0, new HashSet<>());
        return locale == null ? ModConstants.DEFAULT_LOCALE : normalizeLocale(locale);
    }

    public static String localeFromPlayerRef(PlayerRef playerRef) {
        if (playerRef == null) return ModConstants.DEFAULT_LOCALE;
        try {
            String language = playerRef.getLanguage();
            if (language != null && !language.isBlank()) return normalizeLocale(language);
        } catch (Throwable ignored) {
        }
        String locale = findLocale(playerRef, 0, new HashSet<>());
        return locale == null ? ModConstants.DEFAULT_LOCALE : normalizeLocale(locale);
    }

    public static String translate(CommandContext context, String relativeKey, Object... args) {
        return translate(localeFromContext(context), relativeKey, args);
    }

    public static String translate(String locale, String relativeKey, Object... args) {
        String normalizedLocale = normalizeLocale(locale);
        String languageKey = languageKey(relativeKey);
        String translated = value(normalizedLocale, languageKey);
        if (translated == null && !Objects.equals(normalizedLocale, ModConstants.DEFAULT_LOCALE)) {
            translated = value(ModConstants.DEFAULT_LOCALE, languageKey);
        }
        if (translated == null) translated = languageKey;
        return format(translated, args);
    }

    private static String value(String locale, String key) {
        return properties(locale).getProperty(key);
    }

    private static Properties properties(String locale) {
        return CACHE.computeIfAbsent(locale, I18n::loadProperties);
    }

    private static Properties loadProperties(String locale) {
        Properties properties = new Properties();
        loadPropertiesFile(properties, LANGUAGE_PATH_PREFIX + locale + "/server.lang");
        return properties;
    }

    private static void loadPropertiesFile(Properties properties, String path) {
        try (InputStream input = openResource(path)) {
            if (input == null) return;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
                properties.load(reader);
            }
        } catch (IOException exception) {
            System.err.println("[" + ModConstants.MOD_NAME + "] Failed to load language file " + path + ": " + exception.getMessage());
        }
    }

    private static InputStream openResource(String path) {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null) {
            InputStream input = contextClassLoader.getResourceAsStream(path);
            if (input != null) return input;
        }
        ClassLoader ownClassLoader = I18n.class.getClassLoader();
        return ownClassLoader == null ? null : ownClassLoader.getResourceAsStream(path);
    }

    private static String format(String value, Object... args) {
        if (args == null || args.length == 0) return value;
        try {
            return MessageFormat.format(value.replace("'", "''"), args);
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }

    private static String languageKey(String relativeKey) {
        return LANGUAGE_KEY_PREFIX + stripKnownPrefix(relativeKey);
    }

    private static String stripKnownPrefix(String key) {
        if (key == null || key.isBlank()) return "missing";
        String trimmed = key.trim();
        if (trimmed.startsWith(SERVER_TRANSLATION_PREFIX)) return trimmed.substring(SERVER_TRANSLATION_PREFIX.length());
        if (trimmed.startsWith(LANGUAGE_KEY_PREFIX)) return trimmed.substring(LANGUAGE_KEY_PREFIX.length());
        if (trimmed.startsWith("server." + LANGUAGE_KEY_PREFIX)) return trimmed.substring(("server." + LANGUAGE_KEY_PREFIX).length());
        return trimmed;
    }

    private static String localeFromContext(CommandContext context) {
        if (context == null) return ModConstants.DEFAULT_LOCALE;
        String direct = findLocale(context, 0, new HashSet<>());
        if (direct != null) return normalizeLocale(direct);
        try {
            return normalizeLocale(findLocale(context.sender(), 0, new HashSet<>()));
        } catch (Throwable ignored) {
            return ModConstants.DEFAULT_LOCALE;
        }
    }

    private static String findLocale(Object target, int depth, Set<Object> visited) {
        if (target == null || depth > 4 || visited.contains(target)) return null;
        visited.add(target);
        for (String methodName : LOCALE_METHODS) {
            String locale = readLocaleMethod(target, methodName);
            if (locale != null) return locale;
        }
        for (String methodName : LOCALE_CONTAINER_METHODS) {
            Object nested = invokeNoArgs(target, methodName);
            String locale = findLocale(nested, depth + 1, visited);
            if (locale != null) return locale;
        }
        return null;
    }

    private static String readLocaleMethod(Object target, String methodName) {
        Object value = invokeNoArgs(target, methodName);
        return localeValue(value);
    }

    private static Object invokeNoArgs(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            if (method.getParameterCount() != 0) return null;
            return method.invoke(target);
        } catch (Throwable ignored) {
        }
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                Method method = type.getDeclaredMethod(methodName);
                if (method.getParameterCount() != 0) return null;
                method.setAccessible(true);
                return method.invoke(target);
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static String localeValue(Object value) {
        if (value == null) return null;
        if (value instanceof Locale locale) return locale.toLanguageTag();
        String text = value.toString();
        return text == null || text.isBlank() ? null : text;
    }

    private static String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) return ModConstants.DEFAULT_LOCALE;
        String normalized = locale.trim().replace('_', '-');
        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "en", "en-us" -> "en-US";
            case "pt", "pt-br", "ptbr" -> "pt-BR";
            case "ru", "ru-ru", "ruru" -> "ru-RU";
            case "uk", "uk-ua", "ukua", "ua" -> "uk-UA";
            case "zh", "zh-cn", "zhcn", "cn" -> "zh-CN";
            default -> ModConstants.DEFAULT_LOCALE;
        };
    }
}
