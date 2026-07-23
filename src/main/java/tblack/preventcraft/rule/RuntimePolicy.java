package tblack.preventcraft.rule;

import tblack.preventcraft.config.PreventCraftConfig;
import tblack.preventcraft.permissions.PlayerAuthorizationSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable, pre-indexed representation of the JSON rules used by gameplay. */
public record RuntimePolicy(
        boolean enabled,
        RestrictionMode mode,
        List<PreventRule> rules,
        Map<RuleType, Map<String, ScopedRules>> byTypeAndTarget,
        boolean hasGroupRules,
        boolean hasPlayerRules
) {
    public static RuntimePolicy empty() {
        return new RuntimePolicy(true, RestrictionMode.BLACKLIST, List.of(), Map.of(), false, false);
    }

    public static RuntimePolicy compile(PreventCraftConfig config, TargetExpander targetExpander) {
        if (config == null) return empty();
        TargetExpander expander = targetExpander == null ? TargetExpander.IDENTITY : targetExpander;
        List<PreventRule> active = new ArrayList<>();
        Map<RuleType, Map<String, MutableScopedRules>> mutable = new LinkedHashMap<>();
        boolean groupRules = false;
        boolean playerRules = false;

        if (config.Rules != null) {
            for (PreventRule configured : config.Rules) {
                if (configured == null || !configured.Enabled || configured.Type == null
                        || configured.Target == null || configured.Target.isBlank()) continue;
                PreventRule rule = configured.copy();
                active.add(rule);
                groupRules |= rule.Scope == RuleScope.GROUP;
                playerRules |= rule.Scope == RuleScope.PLAYER;

                Set<String> expanded = expander.expand(rule.Type, rule.Target);
                if (expanded == null || expanded.isEmpty()) expanded = Set.of(rule.Target);
                LinkedHashSet<String> keys = new LinkedHashSet<>();
                keys.add(normalize(rule.Target));
                for (String value : expanded) keys.add(normalize(value));
                for (String key : keys) {
                    if (key.isBlank()) continue;
                    mutable.computeIfAbsent(rule.Type, ignored -> new LinkedHashMap<>())
                            .computeIfAbsent(key, ignored -> new MutableScopedRules())
                            .add(rule);
                }
            }
        }

        Map<RuleType, Map<String, ScopedRules>> compiled = new LinkedHashMap<>();
        for (Map.Entry<RuleType, Map<String, MutableScopedRules>> typeEntry : mutable.entrySet()) {
            Map<String, ScopedRules> targets = new LinkedHashMap<>();
            for (Map.Entry<String, MutableScopedRules> targetEntry : typeEntry.getValue().entrySet()) {
                targets.put(targetEntry.getKey(), targetEntry.getValue().freeze());
            }
            compiled.put(typeEntry.getKey(), Map.copyOf(targets));
        }
        return new RuntimePolicy(
                config.Enabled,
                config.Mode == null ? RestrictionMode.BLACKLIST : config.Mode,
                List.copyOf(active),
                Map.copyOf(compiled),
                groupRules,
                playerRules
        );
    }

    public ScopedRules candidates(RuleType type, String target) {
        if (type == null) return null;
        Map<String, ScopedRules> targets = byTypeAndTarget.get(type);
        return targets == null ? null : targets.get(normalize(target));
    }

    public boolean hasPotentialRule(RuleType type, String target) {
        return candidates(type, target) != null;
    }

    public RuleDecision decide(RuleType type, String target, PlayerAuthorizationSnapshot authorization) {
        if (!enabled) return RuleDecision.allowed("mod disabled");
        String normalizedTarget = normalize(target);
        if (normalizedTarget.isBlank()) return RuleDecision.allowed("empty target");
        PlayerAuthorizationSnapshot auth = authorization == null
                ? PlayerAuthorizationSnapshot.pending(null, "")
                : authorization;
        boolean bypass = type == RuleType.ACCESS_BENCH ? auth.bypassBench() : auth.bypassCraft();
        if (bypass) return RuleDecision.allowed("bypass");

        ScopedRules scoped = candidates(type, normalizedTarget);
        PreventRule selected = selectPlayer(scoped, auth);
        if (selected == null && !auth.ready() && scoped != null && scoped.hasGroupRules()) {
            return RuleDecision.denied(null, "authorization pending");
        }
        if (selected == null) selected = selectGroup(scoped, auth.groups());
        if (selected == null && scoped != null) selected = scoped.everyone();
        if (selected != null) {
            return selected.Action == RuleAction.DENY
                    ? RuleDecision.denied(selected, "matched rule " + selected.Id)
                    : new RuleDecision(true, selected, "matched rule " + selected.Id);
        }
        return mode == RestrictionMode.BLACKLIST
                ? RuleDecision.allowed("blacklist default")
                : RuleDecision.denied(null, "whitelist default");
    }

    private PreventRule selectPlayer(ScopedRules scoped, PlayerAuthorizationSnapshot auth) {
        if (scoped == null || scoped.players().isEmpty()) return null;
        PreventRule byUuid = auth.uuid() == null ? null : scoped.players().get(normalize(auth.uuid().toString()));
        PreventRule byName = scoped.players().get(normalize(auth.username()));
        return prefer(byUuid, byName);
    }

    private PreventRule selectGroup(ScopedRules scoped, Set<String> groups) {
        if (scoped == null || scoped.groups().isEmpty() || groups == null || groups.isEmpty()) return null;
        PreventRule selected = null;
        for (String group : groups) selected = prefer(selected, scoped.groups().get(normalize(group)));
        return selected;
    }

    public record ScopedRules(
            PreventRule everyone,
            Map<String, PreventRule> groups,
            Map<String, PreventRule> players
    ) {
        public boolean hasGroupRules() {
            return !groups.isEmpty();
        }
    }

    @FunctionalInterface
    public interface TargetExpander {
        TargetExpander IDENTITY = (type, target) -> Set.of(target);

        Set<String> expand(RuleType type, String target);
    }

    private static final class MutableScopedRules {
        private PreventRule everyone;
        private final Map<String, PreventRule> groups = new LinkedHashMap<>();
        private final Map<String, PreventRule> players = new LinkedHashMap<>();

        private void add(PreventRule rule) {
            if (rule.Scope == RuleScope.GROUP) {
                merge(groups, normalize(rule.Group), rule);
            } else if (rule.Scope == RuleScope.PLAYER) {
                merge(players, normalize(rule.Player), rule);
            } else {
                everyone = prefer(everyone, rule);
            }
        }

        private void merge(Map<String, PreventRule> map, String key, PreventRule rule) {
            if (!key.isBlank()) map.put(key, prefer(map.get(key), rule));
        }

        private ScopedRules freeze() {
            return new ScopedRules(everyone, Map.copyOf(groups), Map.copyOf(players));
        }
    }

    private static PreventRule prefer(PreventRule current, PreventRule candidate) {
        if (current == null) return candidate;
        if (current.Action != RuleAction.DENY && candidate.Action == RuleAction.DENY) return candidate;
        return current;
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
