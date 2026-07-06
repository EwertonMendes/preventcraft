package tblack.preventcraft.catalog;

import java.util.Set;

public record BenchCatalogEntry(
        String benchId,
        String blockId,
        String displayName,
        String iconItemId,
        String searchText,
        Set<String> craftItemIds
) {
    public boolean hasDisplayName() {
        return displayName != null && !displayName.isBlank();
    }

    public boolean hasIcon() {
        return iconItemId != null && !iconItemId.isBlank();
    }
}
