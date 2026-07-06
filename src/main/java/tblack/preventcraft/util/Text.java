package tblack.preventcraft.util;

import java.util.Locale;

public final class Text {
    private Text() {
    }

    public static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static String lower(String value) {
        return safe(value).toLowerCase(Locale.ROOT);
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
