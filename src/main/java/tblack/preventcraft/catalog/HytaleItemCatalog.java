package tblack.preventcraft.catalog;

import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class HytaleItemCatalog implements ItemCatalog {
    private static final String DEFAULT_LOCALE = "en-US";
    private static final int MAX_QUERY_LENGTH = 80;

    private volatile RegistrySnapshot registry = RegistrySnapshot.empty();
    private final Map<String, LocalizedSnapshot> localizedSnapshots = new ConcurrentHashMap<>();

    @Override
    public boolean isValidItemId(String itemId) {
        String normalized = normalizeItemId(itemId);
        if (normalized == null) return false;
        try {
            DefaultAssetMap<String, Item> itemMap = Item.getAssetMap();
            if (itemMap == null) return false;
            Item item = itemMap.getAsset(normalized);
            return item != null && item != Item.UNKNOWN;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public List<ItemCatalogEntry> search(String rawQuery, String locale) {
        LocalizedSnapshot snapshot = localizedSnapshot(locale);
        String query = normalizeSearch(rawQuery);
        if (query.isEmpty()) return snapshot.entries();

        String[] tokens = query.split("\\s+");
        List<ItemCatalogEntry> matches = new ArrayList<>();
        for (ItemCatalogEntry entry : snapshot.entries()) {
            if (containsAllTokens(entry.searchText(), tokens)) matches.add(entry);
        }
        return List.copyOf(matches);
    }

    @Override
    public ItemCatalogEntry describe(String itemId, String locale) {
        String normalized = normalizeItemId(itemId);
        if (normalized == null || !isValidItemId(normalized)) return null;
        return localizedSnapshot(locale).byItemId().get(normalized);
    }

    @Override
    public synchronized void invalidate() {
        registry = RegistrySnapshot.empty();
        localizedSnapshots.clear();
    }

    private LocalizedSnapshot localizedSnapshot(String rawLocale) {
        refreshRegistryIfNeeded();
        String locale = normalizeLocale(rawLocale);
        RegistrySnapshot currentRegistry = registry;
        LocalizedSnapshot cached = localizedSnapshots.get(locale);
        if (cached != null && cached.matches(currentRegistry)) return cached;

        LocalizedSnapshot rebuilt = buildLocalizedSnapshot(locale, currentRegistry);
        localizedSnapshots.put(locale, rebuilt);
        return rebuilt;
    }

    private LocalizedSnapshot buildLocalizedSnapshot(String locale, RegistrySnapshot currentRegistry) {
        Map<String, String> localizedMessages = getMessages(locale);
        Map<String, String> fallbackMessages = locale.equals(DEFAULT_LOCALE)
                ? localizedMessages
                : getMessages(DEFAULT_LOCALE);

        List<ItemCatalogEntry> entries = new ArrayList<>(currentRegistry.entries().size());
        Map<String, ItemCatalogEntry> byItemId = new LinkedHashMap<>(currentRegistry.entries().size());
        for (BaseEntry base : currentRegistry.entries()) {
            String localizedName = resolveName(localizedMessages, fallbackMessages, locale, base.translationKey());
            String fallbackName = resolveName(fallbackMessages, Map.of(), DEFAULT_LOCALE, base.translationKey());
            String searchText = buildSearchText(base.itemId(), localizedName, fallbackName);
            ItemCatalogEntry entry = new ItemCatalogEntry(
                    base.itemId(),
                    base.translationKey(),
                    localizedName,
                    searchText
            );
            entries.add(entry);
            byItemId.put(entry.itemId(), entry);
        }

        entries.sort(Comparator
                .comparing(ItemCatalogEntry::hasDisplayName).reversed()
                .thenComparing(entry -> entry.displayName() == null ? "" : entry.displayName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ItemCatalogEntry::itemId, String.CASE_INSENSITIVE_ORDER));

        return new LocalizedSnapshot(
                Collections.unmodifiableList(entries),
                Collections.unmodifiableMap(byItemId),
                currentRegistry.registrySize(),
                currentRegistry.mapIdentity()
        );
    }

    private Map<String, String> getMessages(String locale) {
        try {
            I18nModule module = I18nModule.get();
            if (module == null) return Map.of();
            Map<String, String> messages = module.getMessages(locale);
            return messages == null ? Map.of() : messages;
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    private String resolveName(
            Map<String, String> primary,
            Map<String, String> fallback,
            String locale,
            String translationKey
    ) {
        if (translationKey == null || translationKey.isBlank()) return null;
        String key = stripTranslationPrefix(translationKey);
        String value = cleanTranslationValue(primary.get(key));
        if (value != null) return value;

        value = cleanTranslationValue(fallback.get(key));
        if (value != null) return value;

        try {
            I18nModule module = I18nModule.get();
            if (module == null) return null;
            value = cleanTranslationValue(module.getMessage(locale, key));
            if (value == null || value.equals(key) || value.equals(translationKey)) return null;
            return value;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String cleanTranslationValue(String value) {
        if (value == null) return null;
        String cleaned = value.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String buildSearchText(String itemId, String localizedName, String fallbackName) {
        Set<String> values = new LinkedHashSet<>();
        values.add(normalizeSearch(itemId));
        values.add(normalizeSearch(itemId.replace('_', ' ').replace('-', ' ')));
        if (localizedName != null) values.add(normalizeSearch(localizedName));
        if (fallbackName != null) values.add(normalizeSearch(fallbackName));
        values.remove("");
        return String.join(" ", values);
    }

    private void refreshRegistryIfNeeded() {
        try {
            DefaultAssetMap<String, Item> itemMap = Item.getAssetMap();
            Map<String, Item> assets = itemMap == null ? null : itemMap.getAssetMap();
            int registrySize = assets == null ? 0 : assets.size();
            int mapIdentity = assets == null ? 0 : System.identityHashCode(assets);
            RegistrySnapshot current = registry;
            if (current.matches(registrySize, mapIdentity)) return;
            refreshRegistry(assets, registrySize, mapIdentity);
        } catch (RuntimeException exception) {
            refreshRegistry(null, 0, 0);
        }
    }

    private synchronized void refreshRegistry(Map<String, Item> assets, int registrySize, int mapIdentity) {
        RegistrySnapshot current = registry;
        if (current.matches(registrySize, mapIdentity)) return;

        localizedSnapshots.clear();
        if (assets == null || assets.isEmpty()) {
            registry = new RegistrySnapshot(List.of(), registrySize, mapIdentity);
            return;
        }

        List<BaseEntry> entries = new ArrayList<>(assets.size());
        Set<String> seenIds = new LinkedHashSet<>(assets.size());
        for (Map.Entry<String, Item> assetEntry : assets.entrySet()) {
            Item item = assetEntry.getValue();
            if (item == null || item == Item.UNKNOWN) continue;

            String itemId = normalizeItemId(item.getId());
            if (itemId == null) itemId = normalizeItemId(String.valueOf(assetEntry.getKey()));
            if (itemId == null || !seenIds.add(itemId)) continue;

            String translationKey = stripTranslationPrefix(item.getTranslationKey());
            entries.add(new BaseEntry(itemId, translationKey));
        }

        entries.sort(Comparator.comparing(BaseEntry::itemId, String.CASE_INSENSITIVE_ORDER));
        registry = new RegistrySnapshot(Collections.unmodifiableList(entries), registrySize, mapIdentity);
    }

    private String normalizeItemId(String rawItemId) {
        if (rawItemId == null) return null;
        String normalized = rawItemId.replaceAll("\\p{Cntrl}", "").trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeLocale(String rawLocale) {
        if (rawLocale == null || rawLocale.isBlank()) return DEFAULT_LOCALE;
        String normalized = rawLocale.replace('_', '-').trim();
        Locale locale = Locale.forLanguageTag(normalized);
        if (locale.getLanguage().isBlank()) return DEFAULT_LOCALE;
        return locale.toLanguageTag();
    }

    private String normalizeSearch(String rawQuery) {
        if (rawQuery == null) return "";
        String cleaned = rawQuery.replaceAll("\\p{Cntrl}", " ").trim();
        if (cleaned.length() > MAX_QUERY_LENGTH) cleaned = cleaned.substring(0, MAX_QUERY_LENGTH);
        String decomposed = Normalizer.normalize(cleaned, Normalizer.Form.NFD);
        return decomposed
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String stripTranslationPrefix(String key) {
        if (key == null) return null;
        String normalized = key.trim();
        while (normalized.startsWith("%")) normalized = normalized.substring(1);
        return normalized.isEmpty() ? null : normalized;
    }

    private boolean containsAllTokens(String searchText, String[] tokens) {
        if (searchText == null || searchText.isBlank()) return false;
        for (String token : tokens) {
            if (!token.isEmpty() && !searchText.contains(token)) return false;
        }
        return true;
    }

    private record BaseEntry(String itemId, String translationKey) {
    }

    private record RegistrySnapshot(List<BaseEntry> entries, int registrySize, int mapIdentity) {
        private static RegistrySnapshot empty() {
            return new RegistrySnapshot(List.of(), -1, -1);
        }

        private boolean matches(int currentRegistrySize, int currentMapIdentity) {
            return registrySize == currentRegistrySize && mapIdentity == currentMapIdentity;
        }
    }

    private record LocalizedSnapshot(
            List<ItemCatalogEntry> entries,
            Map<String, ItemCatalogEntry> byItemId,
            int registrySize,
            int mapIdentity
    ) {
        private boolean matches(RegistrySnapshot snapshot) {
            return registrySize == snapshot.registrySize() && mapIdentity == snapshot.mapIdentity();
        }
    }
}
