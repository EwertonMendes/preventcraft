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

    public int size() {
        refreshRegistryIfNeeded();
        return registry.entries().size();
    }

    public String resolveIconItemId(String benchId) {
        String normalized = normalize(benchId);
        if (normalized == null) return null;
        refreshRegistryIfNeeded();
        BaseEntry entry = registry.byLookupId().get(normalized.toLowerCase(Locale.ROOT));
        return entry == null ? null : entry.iconItemId();
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
        return findBaseEntry(benchId) != null;
    }

    public boolean matchesCraftedBenchTarget(String configuredTarget, String craftedItemId) {
        String target = normalize(configuredTarget);
        String item = normalize(craftedItemId);
        if (target == null || item == null) return false;
        if (target.equalsIgnoreCase(item)) return true;
        BaseEntry entry = findBaseEntry(target);
        if (entry == null) return false;
        if (baseMatches(entry, item)) return true;
        for (String candidate : entry.craftItemIds()) {
            if (candidate != null && candidate.equalsIgnoreCase(item)) return true;
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
            if (displayName == null) displayName = prettifyBenchName(base.benchId());
            String searchText = buildSearchText(base.benchId(), base.blockId(), displayName, base.craftItemIds(), base.aliases());
            BenchCatalogEntry entry = new BenchCatalogEntry(base.benchId(), base.blockId(), displayName, base.iconItemId(), searchText, base.craftItemIds(), base.aliases());
            entries.add(entry);
            putAlias(byBenchId, entry.benchId(), entry);
            putAlias(byBenchId, entry.blockId(), entry);
            for (String alias : entry.aliases()) putAlias(byBenchId, alias, entry);
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
            registry = new RegistrySnapshot(List.of(), Map.of(), registrySize, mapIdentity);
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
            String rawBenchId = normalize(callString(bench, "getId"));
            if (rawBenchId == null) rawBenchId = normalize(objectToString(readField(bench, "id")));
            String blockId = normalize(callString(blockType, "getId"));
            if (blockId == null) blockId = normalize(String.valueOf(assetEntry.getKey()));
            if (rawBenchId == null) rawBenchId = blockId;
            String publicBenchId = publicBenchId(rawBenchId, blockId);
            if (publicBenchId == null || !seen.add(publicBenchId.toLowerCase(Locale.ROOT))) continue;
            String translationKey = stripTranslationPrefix(firstNonBlank(
                    callString(bench, "getTranslationKey"),
                    callString(blockType, "getTranslationKey"),
                    callString(bench, "getNameKey"),
                    callString(blockType, "getNameKey")
            ));
            Set<String> craftItemIds = candidateCraftItemIds(bench, blockType, rawBenchId, blockId);
            Set<String> aliases = benchAliases(publicBenchId, rawBenchId, blockId);
            String iconItemId = firstValidItem(candidateIconItemIds(rawBenchId, blockId, craftItemIds, aliases));
            entries.add(new BaseEntry(publicBenchId, blockId, translationKey, iconItemId, craftItemIds, aliases));
        }
        entries.sort(Comparator.comparing(BaseEntry::benchId, String.CASE_INSENSITIVE_ORDER));
        Map<String, BaseEntry> byLookupId = new LinkedHashMap<>();
        for (BaseEntry entry : entries) {
            putBaseAlias(byLookupId, entry.benchId(), entry);
            putBaseAlias(byLookupId, entry.blockId(), entry);
            for (String alias : entry.aliases()) putBaseAlias(byLookupId, alias, entry);
        }
        registry = new RegistrySnapshot(
                Collections.unmodifiableList(entries),
                Collections.unmodifiableMap(byLookupId),
                registrySize,
                mapIdentity
        );
    }

    private void putBaseAlias(Map<String, BaseEntry> byLookupId, String alias, BaseEntry entry) {
        String normalized = normalize(alias);
        if (normalized != null) byLookupId.putIfAbsent(normalized.toLowerCase(Locale.ROOT), entry);
    }


    public boolean matchesBenchTarget(String configuredTarget, String runtimeBenchIdOrBlockId) {
        String target = normalize(configuredTarget);
        String runtime = normalize(runtimeBenchIdOrBlockId);
        if (target == null || runtime == null) return false;
        if (target.equalsIgnoreCase(runtime)) return true;
        BaseEntry entry = findBaseEntry(target);
        if (entry != null && baseMatches(entry, runtime)) return true;
        BaseEntry runtimeEntry = findBaseEntry(runtime);
        return runtimeEntry != null && baseMatches(runtimeEntry, target);
    }

    private BaseEntry findBaseEntry(String value) {
        String normalized = normalize(value);
        if (normalized == null) return null;
        refreshRegistryIfNeeded();
        return registry.byLookupId().get(normalized.toLowerCase(Locale.ROOT));
    }

    private boolean baseMatches(BaseEntry entry, String value) {
        if (entry.benchId() != null && entry.benchId().equalsIgnoreCase(value)) return true;
        if (entry.blockId() != null && entry.blockId().equalsIgnoreCase(value)) return true;
        for (String alias : entry.aliases()) {
            if (alias != null && alias.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    private void putAlias(Map<String, BenchCatalogEntry> byBenchId, String alias, BenchCatalogEntry entry) {
        String normalized = normalize(alias);
        if (normalized != null) byBenchId.put(normalized.toLowerCase(Locale.ROOT), entry);
    }

    private String publicBenchId(String rawBenchId, String blockId) {
        String block = normalize(blockId);
        String raw = normalize(rawBenchId);
        if (block != null && block.toLowerCase(Locale.ROOT).startsWith("bench_")) return canonicalBenchId(block);
        if (raw == null) return block == null ? null : canonicalBenchId(block);
        if (raw.toLowerCase(Locale.ROOT).startsWith("bench_")) return canonicalBenchId(raw);
        String vanilla = vanillaBenchIcon(raw);
        if (vanilla != null && vanilla.toLowerCase(Locale.ROOT).startsWith("bench_")) return canonicalBenchId(vanilla);
        return canonicalBenchId(raw);
    }

    private Set<String> benchAliases(String publicBenchId, String rawBenchId, String blockId) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        add(aliases, publicBenchId);
        add(aliases, rawBenchId);
        add(aliases, blockId);
        add(aliases, stripBenchPrefix(publicBenchId));
        add(aliases, stripBenchPrefix(blockId));
        return Collections.unmodifiableSet(aliases);
    }

    private Set<String> candidateIconItemIds(String rawBenchId, String blockId, Set<String> craftItemIds, Set<String> aliases) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        add(ids, vanillaBenchIcon(rawBenchId));
        add(ids, vanillaBenchIcon(blockId));
        if (aliases != null) for (String alias : aliases) add(ids, vanillaBenchIcon(alias));
        add(ids, blockId);
        add(ids, rawBenchId);
        add(ids, publicBenchId(rawBenchId, blockId));
        if (aliases != null) {
            for (String alias : aliases) {
                add(ids, alias);
                String stripped = stripBenchPrefix(alias);
                add(ids, stripped);
                add(ids, stripped == null ? null : "Bench_" + stripped);
            }
        }
        if (craftItemIds != null) for (String id : craftItemIds) add(ids, id);
        return Collections.unmodifiableSet(ids);
    }

    private String vanillaBenchIcon(String value) {
        String normalized = normalize(value);
        if (normalized == null) return null;
        String key = normalized.toLowerCase(Locale.ROOT).replace("-", "_");
        if (key.startsWith("bench_")) key = key.substring("bench_".length());
        return switch (key) {
            case "workbench", "work_bench" -> "Bench_WorkBench";
            case "builders", "builder", "builders_workbench", "builder_workbench", "builder's_workbench" -> "Bench_Builders";
            case "campfire" -> "Bench_Campfire";
            case "furnace" -> "Bench_Furnace";
            case "anvil" -> "Bench_Anvil";
            case "forge" -> "Bench_Forge";
            case "loom" -> "Bench_Loom";
            case "salvagebench", "salvage_bench" -> "Salvagebench";
            default -> null;
        };
    }

    private String canonicalBenchId(String value) {
        String normalized = normalize(value);
        if (normalized == null) return null;
        String key = normalized.toLowerCase(Locale.ROOT);
        if (key.equals("bench_workbench") || key.equals("workbench")) return "Bench_WorkBench";
        if (key.equals("bench_builders") || key.equals("builders")) return "Bench_Builders";
        if (key.startsWith("bench_")) return "Bench_" + normalized.substring("Bench_".length());
        return normalized;
    }

    private String stripBenchPrefix(String value) {
        String normalized = normalize(value);
        if (normalized == null) return null;
        return normalized.toLowerCase(Locale.ROOT).startsWith("bench_") ? normalized.substring("Bench_".length()) : normalized;
    }

    private String prettifyBenchName(String value) {
        String normalized = stripBenchPrefix(value);
        if (normalized == null) return null;
        String spaced = normalized.replace('_', ' ').replace('-', ' ').trim();
        if (spaced.isBlank()) return normalized;
        StringBuilder result = new StringBuilder(spaced.length());
        boolean upper = true;
        for (char c : spaced.toCharArray()) {
            if (Character.isWhitespace(c)) {
                result.append(c);
                upper = true;
            } else if (upper) {
                result.append(Character.toUpperCase(c));
                upper = false;
            } else {
                result.append(c);
            }
        }
        return result.toString();
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
        return objectToString(value);
    }

    private String objectToString(Object value) {
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

    private String buildSearchText(String benchId, String blockId, String displayName, Set<String> craftItemIds, Set<String> aliases) {
        Set<String> values = new LinkedHashSet<>();
        values.add(normalizeSearch(benchId));
        values.add(normalizeSearch(blockId));
        values.add(normalizeSearch(benchId == null ? null : benchId.replace('_', ' ').replace('-', ' ')));
        values.add(normalizeSearch(stripBenchPrefix(benchId)));
        if (displayName != null) values.add(normalizeSearch(displayName));
        if (aliases != null) for (String alias : aliases) values.add(normalizeSearch(alias));
        if (craftItemIds != null) for (String id : craftItemIds) values.add(normalizeSearch(id));
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

    private record BaseEntry(String benchId, String blockId, String translationKey, String iconItemId, Set<String> craftItemIds, Set<String> aliases) {
    }

    private record RegistrySnapshot(
            List<BaseEntry> entries,
            Map<String, BaseEntry> byLookupId,
            int registrySize,
            int mapIdentity
    ) {
        private static RegistrySnapshot empty() {
            return new RegistrySnapshot(List.of(), Map.of(), -1, -1);
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
