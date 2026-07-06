package tblack.preventcraft.rule;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import tblack.preventcraft.ModConstants;
import tblack.preventcraft.catalog.HytaleBenchCatalog;
import tblack.preventcraft.config.PreventCraftConfig;
import tblack.preventcraft.permissions.PermissionService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class RuleService {
    private final PermissionService permissionService;
    private final HytaleBenchCatalog benchCatalog;
    private final AtomicReference<CompiledRules> compiled = new AtomicReference<>(CompiledRules.empty());

    public RuleService(PermissionService permissionService, HytaleBenchCatalog benchCatalog) {
        this.permissionService = permissionService;
        this.benchCatalog = benchCatalog;
    }

    public void rebuild(PreventCraftConfig config) {
        compiled.set(CompiledRules.from(config));
    }

    public int activeRuleCount() {
        return compiled.get().rules().size();
    }

    public RuleDecision decideCraft(PlayerRef playerRef, String outputItemId) {
        String target = normalize(outputItemId);
        if (target.isBlank()) return RuleDecision.allowed("empty target");

        RuleDecision itemDecision = decide(playerRef, RuleType.CRAFT_ITEM, target);
        RuleDecision benchDecision = decideBenchCraft(playerRef, target);

        if (itemDecision.rule() != null && !itemDecision.allowed()) return itemDecision;
        if (benchDecision.rule() != null && !benchDecision.allowed()) return benchDecision;
        if (itemDecision.rule() != null) return itemDecision;
        if (benchDecision.rule() != null) return benchDecision;
        if (!itemDecision.allowed()) return itemDecision;
        if (!benchDecision.allowed()) return benchDecision;
        return itemDecision;
    }

    public RuleDecision decide(PlayerRef playerRef, RuleType type, String target) {
        CompiledRules snapshot = compiled.get();
        if (!snapshot.enabled()) return RuleDecision.allowed("mod disabled");
        String normalizedTarget = normalize(target);
        if (normalizedTarget.isBlank()) return RuleDecision.allowed("empty target");
        if (hasBypass(playerRef, type)) return RuleDecision.allowed("bypass");

        PreventRule selected = type == RuleType.ACCESS_BENCH
                ? selectBenchRule(snapshot, playerRef, type, normalizedTarget)
                : selectRule(snapshot, playerRef, type, normalizedTarget);
        if (selected != null) {
            return selected.Action == RuleAction.DENY
                    ? RuleDecision.denied(selected, "matched rule " + selected.Id)
                    : RuleDecision.allowed("matched rule " + selected.Id);
        }
        boolean allowedByMode = snapshot.mode() == RestrictionMode.BLACKLIST;
        return allowedByMode ? RuleDecision.allowed("blacklist default") : RuleDecision.denied(null, "whitelist default");
    }

    public boolean shouldHideRecipe(PlayerRef playerRef, String recipeOutputItemId) {
        CompiledRules snapshot = compiled.get();
        return snapshot.hideBlockedRecipes() && !decideCraft(playerRef, recipeOutputItemId).allowed();
    }

    private RuleDecision decideBenchCraft(PlayerRef playerRef, String craftedItemId) {
        CompiledRules snapshot = compiled.get();
        if (!snapshot.enabled()) return RuleDecision.allowed("mod disabled");
        if (hasBypass(playerRef, RuleType.CRAFT_BENCH)) return RuleDecision.allowed("bypass");
        List<PreventRule> candidates = snapshot.rules().stream()
                .filter(rule -> rule.Type == RuleType.CRAFT_BENCH)
                .filter(rule -> benchCatalog.matchesCraftedBenchTarget(rule.Target, craftedItemId))
                .toList();
        PreventRule selected = selectRuleFromCandidates(snapshot, playerRef, candidates);
        if (selected != null) {
            return selected.Action == RuleAction.DENY
                    ? RuleDecision.denied(selected, "matched rule " + selected.Id)
                    : RuleDecision.allowed("matched rule " + selected.Id);
        }
        boolean allowedByMode = snapshot.mode() == RestrictionMode.BLACKLIST;
        return allowedByMode ? RuleDecision.allowed("blacklist default") : RuleDecision.denied(null, "whitelist default");
    }

    private PreventRule selectRule(CompiledRules snapshot, PlayerRef playerRef, RuleType type, String target) {
        List<PreventRule> candidates = snapshot.rules().stream()
                .filter(rule -> rule.Type == type)
                .filter(rule -> normalize(rule.Target).equalsIgnoreCase(target))
                .toList();
        return selectRuleFromCandidates(snapshot, playerRef, candidates);
    }

    private PreventRule selectBenchRule(CompiledRules snapshot, PlayerRef playerRef, RuleType type, String target) {
        List<PreventRule> candidates = snapshot.rules().stream()
                .filter(rule -> rule.Type == type)
                .filter(rule -> benchCatalog.matchesBenchTarget(rule.Target, target))
                .toList();
        return selectRuleFromCandidates(snapshot, playerRef, candidates);
    }

    private PreventRule selectRuleFromCandidates(CompiledRules snapshot, PlayerRef playerRef, List<PreventRule> candidates) {
        if (candidates.isEmpty()) return null;
        UUID uuid = null;
        String username = null;
        try {
            uuid = playerRef.getUuid();
            username = playerRef.getUsername();
        } catch (Throwable ignored) {
        }
        Set<String> groups = permissionService.getGroups(playerRef);
        List<ScoredRule> scored = new ArrayList<>();
        for (PreventRule rule : candidates) {
            int score = matchScore(rule, uuid, username, groups);
            if (score > 0) scored.add(new ScoredRule(rule, score));
        }
        return scored.stream()
                .max(Comparator
                        .comparingInt(ScoredRule::score)
                        .thenComparingInt(scoredRule -> scoredRule.rule().Action == RuleAction.DENY ? 1 : 0))
                .map(ScoredRule::rule)
                .orElse(null);
    }

    private int matchScore(PreventRule rule, UUID uuid, String username, Set<String> groups) {
        if (rule.Scope == RuleScope.EVERYONE) return 1;
        if (rule.Scope == RuleScope.GROUP) {
            String group = normalizeGroup(rule.Group);
            return !group.isBlank() && groups.contains(group) ? 2 : 0;
        }
        if (rule.Scope == RuleScope.PLAYER) {
            String player = normalizePlayer(rule.Player);
            if (player.isBlank()) return 0;
            if (uuid != null && player.equalsIgnoreCase(uuid.toString())) return 3;
            if (username != null && player.equalsIgnoreCase(username)) return 3;
        }
        return 0;
    }

    private boolean hasBypass(PlayerRef playerRef, RuleType type) {
        if (playerRef == null) return false;
        UUID uuid;
        try {
            uuid = playerRef.getUuid();
        } catch (Throwable ignored) {
            return false;
        }
        if (permissionService.hasPermission(uuid, ModConstants.BYPASS_PERMISSION)) return true;
        if (type == RuleType.ACCESS_BENCH) return permissionService.hasPermission(uuid, ModConstants.BYPASS_BENCH_PERMISSION);
        return permissionService.hasPermission(uuid, ModConstants.BYPASS_CRAFT_PERMISSION);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeGroup(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizePlayer(String value) {
        return value == null ? "" : value.trim();
    }

    private record ScoredRule(PreventRule rule, int score) {
    }

    private record CompiledRules(boolean enabled, RestrictionMode mode, boolean hideBlockedRecipes, List<PreventRule> rules) {
        private static CompiledRules empty() {
            return new CompiledRules(true, RestrictionMode.BLACKLIST, true, List.of());
        }

        private static CompiledRules from(PreventCraftConfig config) {
            if (config == null) return empty();
            List<PreventRule> active = new ArrayList<>();
            if (config.Rules != null) {
                for (PreventRule rule : config.Rules) {
                    if (rule == null || !rule.Enabled || rule.Target == null || rule.Target.isBlank()) continue;
                    active.add(rule.copy());
                }
            }
            return new CompiledRules(config.Enabled, config.Mode == null ? RestrictionMode.BLACKLIST : config.Mode, config.HideBlockedRecipes, List.copyOf(active));
        }
    }
}
