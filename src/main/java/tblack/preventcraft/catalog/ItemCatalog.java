package tblack.preventcraft.catalog;

import java.util.List;

public interface ItemCatalog {
    boolean isValidItemId(String itemId);
    List<ItemCatalogEntry> search(String rawQuery, String locale);
    ItemCatalogEntry describe(String itemId, String locale);
    void invalidate();
}
