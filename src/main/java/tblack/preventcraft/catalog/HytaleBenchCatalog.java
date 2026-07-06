package tblack.preventcraft.catalog;

import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.Bench;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

public final class HytaleBenchCatalog {
    private static final String DEFAULT_LOCALE = "en-US";
    private static final int MAX_QUERY_LENGTH = 80;

    private final ItemCatalog itemCatalog;
    private volatile RegistrySnapshot registry = RegistrySnapshot.empty();
    private final Map<String, LocalizedSnapshot> localizedSnapshots = new ConcurrentHashMap<>();

    public HytaleBenchCatalog(ItemCatalog itemCatalog) {
        this.itemCatalog = itemCatalog;
    }

    public List<BenchCatalogEntry> search(String rawQuery, String locale) {
        LocalizedSnapshot snapshot = localizedSnapshot(locale);
        String query = normalizeSearch(rawQuery);
        if (query.isEmpty()) return snapshot.entries();
        String[] tokens = query.split("\\s+");
        List<BenchCatalogEntry> matches = new ArrayList<>();
        for (BenchCatalogEntry entry : snapshot.entries()) {
            if (containsAllTokens(entry.searchText(), tokens)) matches.add(entry);
        }
        return List.copyOf(matches);
    }

    public BenchCatalogEntry describe(String benchId, String locale) {
        String normalized = normalize(benchId);
        if (normalized == null) return null;
        return localizedSnapshot(locale).byBenchId().get(normalized.toLowerCase(Locale.ROOT));
    }

    public boolean isValidBenchId(String benchId) {
        return describe(benchId, DEFAULT_LOCALE) != null;
    }

    public boolean matchesCraftedBenchTarget(String configuredTarget, String craftedItemId) {
        String target = normalize(configuredTarget);
        String item = normalize(craftedItemId);
        if (target == null || item == null) return false;
        if (target.equalsIgnoreCase(item)) return true;
        BenchCatalogEntry entry = describe(target, DEFAULT_LOCALE);
        if (entry == null) return false;
        if (entry.blockId() != null && entry.blockId().equalsIgnoreCase(item)) return true;
        if (entry.craftItemIds() != null) {
            for (String candidate : entry.craftItemIds()) {
                if (candidate != null && candidate.equalsIgnoreCase(item)) return true;
            }
        }
        return false;
    }

    public void invalidate() {
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
        Map<String, String> fallbackMessages = locale.equals(DEFAULT_LOCALE) ? localizedMessages : getMessages(DEFAULT_LOCALE);
        List<BenchCatalogEntry> entries = new ArrayList<>(currentRegistry.entries().size());
        Map<String, BenchCatalogEntry> byBenchId = new LinkedHashMap<>();
        for (BaseEntry base : currentRegistry.entries()) {
            String displayName = resolveName(localizedMessages, fallbackMessages, locale, base.translationKey());
            if (displayName == null && base.iconItemId() != null) {
                ItemCatalogEntry item = itemCatalog.describe(base.iconItemId(), locale);
                if (item != null && item.hasDisplayName()) displayName = item.displayName();
            }
            String searchText = buildSearchText(base.benchId(), base.blockId(), displayName, base.craftItemIds());
            BenchCatalogEntry entry = new BenchCatalogEntry(base.benchId(), base.blockId(), displayName, base.iconItemId(), searchText, base.craftItemIds());
            entries.add(entry);
            byBenchId.put(entry.benchId().toLowerCase(Locale.ROOT), entry);
        }
        entries.sort(Comparator
                .comparing(BenchCatalogEntry::hasDisplayName).reversed()
                .thenComparing(entry -> entry.displayName() == null ? "" : entry.displayName(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(BenchCatalogEntry::benchId, String.CASE_INSENSITIVE_ORDER));
        return new LocalizedSnapshot(Collections.unmodifiableList(entries), Collections.unmodifiableMap(byBenchId), currentRegistry.registrySize(), currentRegistry.mapIdentity());
    }

    private void refreshRegistryIfNeeded() {
        try {
            DefaultAssetMap<String, BlockType> blockMap = BlockType.getAssetMap();
            Map<String, BlockType> assets = blockMap == null ? null : blockMap.getAssetMap();
            int registrySize = assets == null ? 0 : assets.size();
            int mapIdentity = assets == null ? 0 : System.identityHashCode(assets);
            RegistrySnapshot current = registry;
            if (current.matches(registrySize, mapIdentity)) return;
            refreshRegistry(assets, registrySize, mapIdentity);
        } catch (RuntimeException exception) {
            refreshRegistry(null, 0, 0);
        }
    }

    private synchronized void refreshRegistry(Map<String, BlockType> assets, int registrySize, int mapIdentity) {
        RegistrySnapshot current = registry;
        if (current.matches(registrySize, mapIdentity)) return;
        localizedSnapshots.clear();
        if (assets == null || assets.isEmpty()) {
            registry = new RegistrySnapshot(List.of(), registrySize, mapIdentity);
            return;
        }
        List<BaseEntry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, BlockType> assetEntry : assets.entrySet()) {
            BlockType blockType = assetEntry.getValue();
            if (blockType == null) continue;
            Bench bench;
            try {
                bench = blockType.getBench();
            } catch (Throwable ignored) {
                continue;
            }
            if (bench == null) continue;
            String benchId = normalize(callString(bench, "getId"));
            if (benchId == null) benchId = normalize(String.valueOf(readField(bench, "id")));
            String blockId = normalize(callString(blockType, "getId"));
            if (blockId == null) blockId = normalize(String.valueOf(assetEntry.getKey()));
            if (benchId == null) benchId = blockId;
            if (benchId == null || !seen.add(benchId.toLowerCase(Locale.ROOT))) continue;
            String translationKey = stripTranslationPrefix(firstNonBlank(
                    callString(bench, "getTranslationKey"),
                    callString(blockType, "getTranslationKey"),
                    callString(bench, "getNameKey"),
                    callString(blockType, "getNameKey")
            ));
            Set<String> craftItemIds = candidateCraftItemIds(bench, blockType, benchId, blockId);
            String iconItemId = firstValidItem(craftItemIds);
            entries.add(new BaseEntry(benchId, blockId, translationKey, iconItemId, craftItemIds));
        }
        entries.sort(Comparator.comparing(BaseEntry::benchId, String.CASE_INSENSITIVE_ORDER));
        registry = new RegistrySnapshot(Collections.unmodifiableList(entries), registrySize, mapIdentity);
    }

    private Set<String> candidateCraftItemIds(Bench bench, BlockType blockType, String benchId, String blockId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        add(ids, benchId);
        add(ids, blockId);
        String[] names = {"getItemId", "getBlockItemId", "getCraftingItemId", "getPlacementItemId", "getDisplayItemId", "getIconItemId", "getInventoryItemId", "getPlacedItemId"};
        for (String name : names) {
            add(ids, callString(bench, name));
            add(ids, callString(blockType, name));
        }
        for (String name : new String[]{"itemId", "blockItemId", "craftingItemId", "placementItemId", "displayItemId", "iconItemId", "inventoryItemId"}) {
            Object benchValue = readField(bench, name);
            Object blockValue = readField(blockType, name);
            add(ids, benchValue == null ? null : String.valueOf(benchValue));
            add(ids, blockValue == null ? null : String.valueOf(blockValue));
        }
        return Collections.unmodifiableSet(ids);
    }

    private String firstValidItem(Set<String> ids) {
        for (String id : ids) {
            if (id != null && itemCatalog.isValidItemId(id)) return id;
        }
        return null;
    }

    private void add(Set<String> ids, String value) {
        String normalized = normalize(value);
        if (normalized != null) ids.add(normalized);
    }

    private String callString(Object target, String methodName) {
        Object value = call(target, methodName);
        return value == null ? null : String.valueOf(value);
    }

    private Object call(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            if (method.getParameterCount() != 0) return null;
            return method.invoke(target);
        } catch (Throwable ignored) {
        }
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            if (method.getParameterCount() != 0) return null;
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object readField(Object target, String fieldName) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
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

    private String resolveName(Map<String, String> primary, Map<String, String> fallback, String locale, String translationKey) {
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

    private String buildSearchText(String benchId, String blockId, String displayName, Set<String> craftItemIds) {
        Set<String> values = new LinkedHashSet<>();
        values.add(normalizeSearch(benchId));
        values.add(normalizeSearch(blockId));
        values.add(normalizeSearch(benchId == null ? null : benchId.replace('_', ' ').replace('-', ' ')));
        if (displayName != null) values.add(normalizeSearch(displayName));
        if (craftItemIds != null) {
            for (String id : craftItemIds) values.add(normalizeSearch(id));
        }
        values.remove("");
        return String.join(" ", values);
    }

    private String normalize(String value) {
        if (value == null) return null;
        String normalized = value.replaceAll("\\p{Cntrl}", "").trim();
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
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private String stripTranslationPrefix(String key) {
        if (key == null) return null;
        String normalized = key.trim();
        while (normalized.startsWith("%")) normalized = normalized.substring(1);
        return normalized.isEmpty() ? null : normalized;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private boolean containsAllTokens(String searchText, String[] tokens) {
        if (searchText == null || searchText.isBlank()) return false;
        for (String token : tokens) {
            if (!token.isEmpty() && !searchText.contains(token)) return false;
        }
        return true;
    }

    private record BaseEntry(String benchId, String blockId, String translationKey, String iconItemId, Set<String> craftItemIds) {
    }

    private record RegistrySnapshot(List<BaseEntry> entries, int registrySize, int mapIdentity) {
        private static RegistrySnapshot empty() {
            return new RegistrySnapshot(List.of(), -1, -1);
        }

        private boolean matches(int currentRegistrySize, int currentMapIdentity) {
            return registrySize == currentRegistrySize && mapIdentity == currentMapIdentity;
        }
    }

    private record LocalizedSnapshot(List<BenchCatalogEntry> entries, Map<String, BenchCatalogEntry> byBenchId, int registrySize, int mapIdentity) {
        private boolean matches(RegistrySnapshot registry) {
            return registrySize == registry.registrySize() && mapIdentity == registry.mapIdentity();
        }
    }
}
