package tblack.preventcraft.catalog;

import java.util.Set;

public record BenchCatalogEntry(
        String benchId,
        String blockId,
        String displayName,
        String iconItemId,
        String searchText,
        Set<String> craftItemIds,
        Set<String> aliases
) {
    public boolean hasDisplayName() {
        return displayName != null && !displayName.isBlank();
    }

    public boolean hasIcon() {
        return iconItemId != null && !iconItemId.isBlank();
    }

    public boolean matches(String value) {
        if (value == null || value.isBlank()) return false;
        if (benchId != null && benchId.equalsIgnoreCase(value)) return true;
        if (blockId != null && blockId.equalsIgnoreCase(value)) return true;
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null && alias.equalsIgnoreCase(value)) return true;
            }
        }
        return false;
    }
}
