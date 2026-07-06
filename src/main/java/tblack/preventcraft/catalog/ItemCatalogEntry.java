package tblack.preventcraft.catalog;

public record ItemCatalogEntry(String itemId, String translationKey, String displayName, String searchText) {
    public boolean hasDisplayName() {
        return displayName != null && !displayName.isBlank();
    }
}
