package tblack.preventcraft.importer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import tblack.preventcraft.config.ConfigManager;
import tblack.preventcraft.config.ConfigOperationResult;
import tblack.preventcraft.config.PreventCraftConfig;
import tblack.preventcraft.catalog.ItemCatalog;
import tblack.preventcraft.permissions.PermissionHolderSnapshot;
import tblack.preventcraft.permissions.PermissionService;
import tblack.preventcraft.rule.PreventRule;
import tblack.preventcraft.rule.RuleAction;
import tblack.preventcraft.rule.RuleScope;
import tblack.preventcraft.rule.RuleType;
import tblack.preventcraft.rule.RestrictionMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CraftRestrictImporter {
    private static final String RECIPE_PREFIX = "craftrestrict.recipe.";
    private static final String BENCH_PREFIX = "craftrestrict.bench.";
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ConfigManager configManager;
    private final PermissionService permissionService;
    private final ItemCatalog itemCatalog;

    public CraftRestrictImporter(ConfigManager configManager, PermissionService permissionService, ItemCatalog itemCatalog) {
        this.configManager = configManager;
        this.permissionService = permissionService;
        this.itemCatalog = itemCatalog;
    }

    public CraftRestrictImportResult importRules(CraftRestrictMode mode, boolean dryRun, boolean includeUsers) {
        if (!permissionService.isLuckPermsAvailable()) {
            return CraftRestrictImportResult.failure(dryRun, "LuckPerms was not detected. CraftRestrict migration needs LuckPerms to read old permission nodes.");
        }
        CraftRestrictMode effectiveMode = mode == null ? CraftRestrictMode.DENY : mode;
        List<PermissionHolderSnapshot> snapshots = permissionService.readLuckPermsSnapshots(includeUsers);
        PreventCraftConfig config = configManager.get();
        int scannedNodes = 0;
        int importedRules = 0;
        int skippedDuplicates = 0;
        int skippedWildcards = 0;
        int skippedUnknown = 0;
        JsonArray reportRules = new JsonArray();
        Set<String> unresolvedItemIds = new LinkedHashSet<>();
        int correctedItemTargets = canonicalizeExistingItemTargets(config);
        Set<String> existing = existingKeys(config);

        if (effectiveMode == CraftRestrictMode.ALLOW) config.Mode = RestrictionMode.WHITELIST;
        RuleAction importedAction = effectiveMode == CraftRestrictMode.ALLOW ? RuleAction.ALLOW : RuleAction.DENY;

        for (PermissionHolderSnapshot holder : snapshots) {
            for (String node : holder.nodes()) {
                if (node == null || !node.startsWith("craftrestrict.")) continue;
                scannedNodes++;
                String target;
                RuleType type;
                if (isWildcard(node)) {
                    skippedWildcards++;
                    continue;
                } else if (node.startsWith(RECIPE_PREFIX)) {
                    target = node.substring(RECIPE_PREFIX.length());
                    type = RuleType.CRAFT_ITEM;
                } else if (node.startsWith(BENCH_PREFIX)) {
                    target = node.substring(BENCH_PREFIX.length());
                    type = RuleType.ACCESS_BENCH;
                } else {
                    skippedUnknown++;
                    continue;
                }
                if (target.isBlank()) {
                    skippedUnknown++;
                    continue;
                }

                String originalTarget = target;
                boolean targetResolved = true;
                if (type == RuleType.CRAFT_ITEM) {
                    String resolvedTarget = itemCatalog.resolveItemId(target);
                    if (resolvedTarget != null) {
                        target = resolvedTarget;
                    } else {
                        target = target.toLowerCase(Locale.ROOT);
                        unresolvedItemIds.add(target);
                        targetResolved = false;
                    }
                }

                PreventRule rule = new PreventRule();
                rule.Enabled = true;
                rule.Type = type;
                rule.Target = target;
                rule.Action = importedAction;
                rule.Scope = holder.isGroup() ? RuleScope.GROUP : RuleScope.PLAYER;
                rule.Group = holder.isGroup() ? holder.name().toLowerCase(Locale.ROOT) : "";
                rule.Player = holder.isUser() ? (holder.uuid() == null ? holder.name() : holder.uuid().toString()) : "";
                rule.Id = importedId(rule);
                rule.Note = "Imported from CraftRestrict LuckPerms node: " + node;

                String key = key(rule);
                if (existing.contains(key)) {
                    skippedDuplicates++;
                    continue;
                }
                existing.add(key);
                importedRules++;
                config.Rules.add(rule);
                reportRules.add(reportRule(holder, node, originalTarget, targetResolved, rule));
            }
        }

        ConfigOperationResult save = dryRun
                ? ConfigOperationResult.success(config, "Dry run complete")
                : configManager.saveWithBackup(config, "before-craftrestrict-import");
        List<String> unresolved = List.copyOf(unresolvedItemIds);
        Path reportFile = writeReport(dryRun, effectiveMode, includeUsers, snapshots.size(), scannedNodes, importedRules, skippedDuplicates, skippedWildcards, skippedUnknown, unresolved, correctedItemTargets, reportRules);
        if (!save.success()) {
            return new CraftRestrictImportResult(false, dryRun, snapshots.size(), scannedNodes, importedRules, skippedDuplicates, skippedWildcards, skippedUnknown, unresolved, correctedItemTargets, save.message(), reportFile);
        }
        String message = dryRun
                ? "Dry run complete. No JSON changes were saved."
                : "CraftRestrict migration complete. A backup was created before saving.";
        return new CraftRestrictImportResult(true, dryRun, snapshots.size(), scannedNodes, importedRules, skippedDuplicates, skippedWildcards, skippedUnknown, unresolved, correctedItemTargets, message, reportFile);
    }

    private boolean isWildcard(String node) {
        return node.equals("*")
                || node.equals("craftrestrict.*")
                || node.equals("craftrestrict.recipe.*")
                || node.equals("craftrestrict.bench.*")
                || node.endsWith(".*");
    }

    private Set<String> existingKeys(PreventCraftConfig config) {
        Set<String> keys = new LinkedHashSet<>();
        if (config.Rules == null) return keys;
        for (PreventRule rule : config.Rules) {
            if (rule == null) continue;
            keys.add(key(rule));
        }
        return keys;
    }

    private int canonicalizeExistingItemTargets(PreventCraftConfig config) {
        if (config.Rules == null) return 0;
        int corrected = 0;
        for (PreventRule rule : config.Rules) {
            if (rule == null || rule.Type != RuleType.CRAFT_ITEM || rule.Target == null) continue;
            String resolved = itemCatalog.resolveItemId(rule.Target);
            if (resolved != null && !resolved.equals(rule.Target)) {
                rule.Target = resolved;
                corrected++;
            }
        }
        return corrected;
    }

    private String key(PreventRule rule) {
        return (rule.Type + "|" + rule.Target + "|" + rule.Scope + "|" + rule.Group + "|" + rule.Player).toLowerCase(Locale.ROOT);
    }

    private String importedId(PreventRule rule) {
        String owner = rule.Scope == RuleScope.GROUP ? rule.Group : rule.Player;
        String base = "craftrestrict-" + rule.Type.name().toLowerCase(Locale.ROOT).replace('_', '-') + "-" + owner + "-" + rule.Target;
        return base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
    }

    private JsonObject reportRule(PermissionHolderSnapshot holder, String node, String originalTarget, boolean targetResolved, PreventRule rule) {
        JsonObject json = new JsonObject();
        json.addProperty("holderKind", holder.kind());
        json.addProperty("holderName", holder.name());
        if (holder.uuid() != null) json.addProperty("holderUuid", holder.uuid().toString());
        json.addProperty("node", node);
        json.addProperty("originalTarget", originalTarget);
        json.addProperty("targetResolved", targetResolved);
        json.addProperty("ruleId", rule.Id);
        json.addProperty("type", rule.Type.name());
        json.addProperty("target", rule.Target);
        json.addProperty("action", rule.Action.name());
        json.addProperty("scope", rule.Scope.name());
        return json;
    }

    private Path writeReport(boolean dryRun, CraftRestrictMode mode, boolean includeUsers, int scannedHolders, int scannedNodes, int importedRules, int skippedDuplicates, int skippedWildcards, int skippedUnknown, List<String> unresolvedItemIds, int correctedItemTargets, JsonArray rules) {
        try {
            Files.createDirectories(configManager.reportsDirectory());
            JsonObject root = new JsonObject();
            root.addProperty("dryRun", dryRun);
            root.addProperty("mode", mode.name());
            root.addProperty("includeUsers", includeUsers);
            root.addProperty("scannedHolders", scannedHolders);
            root.addProperty("scannedNodes", scannedNodes);
            root.addProperty("importedRules", importedRules);
            root.addProperty("skippedDuplicates", skippedDuplicates);
            root.addProperty("skippedWildcards", skippedWildcards);
            root.addProperty("skippedUnknown", skippedUnknown);
            root.addProperty("unresolvedItemCount", unresolvedItemIds.size());
            root.addProperty("correctedExistingItemTargets", correctedItemTargets);
            JsonArray unresolved = new JsonArray();
            for (String itemId : unresolvedItemIds) unresolved.add(itemId);
            root.add("unresolvedItemIds", unresolved);
            root.add("rules", rules);
            Path report = configManager.reportsDirectory().resolve("craftrestrict-import-" + FORMAT.format(Instant.now()) + (dryRun ? "-dry-run" : "") + ".json");
            Gson gson = configManager.gson();
            Files.writeString(report, gson.toJson(root), StandardCharsets.UTF_8);
            return report;
        } catch (IOException exception) {
            return null;
        }
    }
}
