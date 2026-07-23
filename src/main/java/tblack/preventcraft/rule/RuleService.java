package tblack.preventcraft.rule;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import tblack.preventcraft.catalog.BenchCatalogEntry;
import tblack.preventcraft.catalog.HytaleBenchCatalog;
import tblack.preventcraft.config.PreventCraftConfig;
import tblack.preventcraft.permissions.PermissionService;
import tblack.preventcraft.permissions.PlayerAuthorizationSnapshot;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAccumulator;

public final class RuleService {
    private final PermissionService permissionService;
    private final HytaleBenchCatalog benchCatalog;
    private final AtomicReference<RuntimePolicy> policy = new AtomicReference<>(RuntimePolicy.empty());
    private final AtomicReference<List<String>> adminSearchTexts = new AtomicReference<>(List.of());
    private final AtomicLong decisionCount = new AtomicLong();
    private final AtomicLong decisionNanos = new AtomicLong();
    private final LongAccumulator maxDecisionNanos = new LongAccumulator(Long::max, 0L);
    private volatile boolean metricsEnabled;

    public RuleService(PermissionService permissionService, HytaleBenchCatalog benchCatalog) {
        this.permissionService = permissionService;
        this.benchCatalog = benchCatalog;
    }

    public void rebuild(PreventCraftConfig config) {
        RuntimePolicy rebuilt = RuntimePolicy.compile(config, this::expandTargets);
        policy.set(rebuilt);
        adminSearchTexts.set(buildAdminSearchTexts(config));
        metricsEnabled = config != null && config.Debug;
    }

    public int activeRuleCount() {
        return policy.get().rules().size();
    }

    public List<String> adminSearchTexts() {
        return adminSearchTexts.get();
    }

    public PerformanceSnapshot performanceSnapshot() {
        long count = decisionCount.get();
        long total = decisionNanos.get();
        return new PerformanceSnapshot(count, count == 0 ? 0L : total / count, maxDecisionNanos.get());
    }

    public RuleDecision decideCraft(PlayerRef playerRef, String outputItemId) {
        if (!metricsEnabled) return decideCraftNow(playerRef, outputItemId);
        long started = System.nanoTime();
        try {
            return decideCraftNow(playerRef, outputItemId);
        } finally {
            recordDecision(System.nanoTime() - started);
        }
    }

    private RuleDecision decideCraftNow(PlayerRef playerRef, String outputItemId) {
        return decideCraft(permissionService.cachedAuthorization(playerRef), outputItemId);
    }

    /** Evaluates a compiled authorization snapshot without touching a permission provider. */
    public RuleDecision decideCraft(PlayerAuthorizationSnapshot authorization, String outputItemId) {
        String target = normalize(outputItemId);
        if (target.isBlank()) return RuleDecision.allowed("empty target");
        RuntimePolicy current = policy.get();
        if (!current.enabled()) return RuleDecision.allowed("mod disabled");
        if (current.mode() == RestrictionMode.BLACKLIST
                && !current.hasPotentialRule(RuleType.CRAFT_ITEM, target)
                && !current.hasPotentialRule(RuleType.CRAFT_BENCH, target)) {
            return RuleDecision.allowed("no configured rule");
        }
        RuleDecision itemDecision = current.decide(RuleType.CRAFT_ITEM, target, authorization);
        RuleDecision benchDecision = current.decide(RuleType.CRAFT_BENCH, target, authorization);
        return combine(itemDecision, benchDecision);
    }

    public RuleDecision decide(PlayerRef playerRef, RuleType type, String target) {
        if (!metricsEnabled) return decideNow(playerRef, type, target);
        long started = System.nanoTime();
        try {
            return decideNow(playerRef, type, target);
        } finally {
            recordDecision(System.nanoTime() - started);
        }
    }

    private RuleDecision decideNow(PlayerRef playerRef, RuleType type, String target) {
        RuntimePolicy current = policy.get();
        String normalizedTarget = normalize(target);
        if (!current.enabled()) return RuleDecision.allowed("mod disabled");
        if (normalizedTarget.isBlank()) return RuleDecision.allowed("empty target");
        if (current.mode() == RestrictionMode.BLACKLIST && !current.hasPotentialRule(type, normalizedTarget)) {
            return RuleDecision.allowed("no configured rule");
        }
        return current.decide(type, normalizedTarget, permissionService.cachedAuthorization(playerRef));
    }

    private RuleDecision combine(RuleDecision itemDecision, RuleDecision benchDecision) {
        if (!itemDecision.allowed() && itemDecision.rule() != null) return itemDecision;
        if (!benchDecision.allowed() && benchDecision.rule() != null) return benchDecision;
        if (itemDecision.rule() != null) return itemDecision;
        if (benchDecision.rule() != null) return benchDecision;
        if (!itemDecision.allowed()) return itemDecision;
        return !benchDecision.allowed() ? benchDecision : itemDecision;
    }

    private Set<String> expandTargets(RuleType type, String target) {
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        expanded.add(target);
        if (type == RuleType.CRAFT_BENCH) {
            BenchCatalogEntry entry = benchCatalog.describe(target, "en-US");
            if (entry != null) {
                expanded.add(entry.benchId());
                expanded.add(entry.blockId());
                expanded.addAll(entry.aliases());
                expanded.addAll(entry.craftItemIds());
                if (entry.iconItemId() != null) expanded.add(entry.iconItemId());
            }
        } else if (type == RuleType.ACCESS_BENCH) {
            BenchCatalogEntry entry = benchCatalog.describe(target, "en-US");
            if (entry != null) {
                expanded.add(entry.benchId());
                expanded.add(entry.blockId());
                expanded.addAll(entry.aliases());
            }
        }
        return Set.copyOf(expanded);
    }

    private void recordDecision(long nanos) {
        decisionCount.incrementAndGet();
        decisionNanos.addAndGet(nanos);
        maxDecisionNanos.accumulate(nanos);
    }

    private List<String> buildAdminSearchTexts(PreventCraftConfig config) {
        if (config == null || config.Rules == null || config.Rules.isEmpty()) return List.of();
        List<String> texts = new ArrayList<>(config.Rules.size());
        for (PreventRule rule : config.Rules) {
            if (rule == null) {
                texts.add("");
                continue;
            }
            String targetWords = rule.Target == null ? "" : rule.Target.replace('_', ' ').replace('-', ' ');
            String raw = String.join(" ",
                    safeSearchValue(rule.Id),
                    rule.Type == null ? "" : rule.Type.name(),
                    rule.Action == null ? "" : rule.Action.name(),
                    rule.Scope == null ? "" : rule.Scope.name(),
                    safeSearchValue(rule.Target), targetWords,
                    safeSearchValue(rule.Group), safeSearchValue(rule.Player), safeSearchValue(rule.Note));
            texts.add(normalizeSearch(raw));
        }
        return List.copyOf(texts);
    }

    private String safeSearchValue(String value) {
        return value == null ? "" : value;
    }

    private String normalizeSearch(String value) {
        String cleaned = value.replaceAll("\\p{Cntrl}", " ").trim();
        String decomposed = Normalizer.normalize(cleaned, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record PerformanceSnapshot(long decisions, long averageNanos, long maximumNanos) {
    }
}
