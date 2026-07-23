package tblack.preventcraft.rule;

import org.junit.jupiter.api.Test;
import tblack.preventcraft.config.PreventCraftConfig;
import tblack.preventcraft.permissions.PlayerAuthorizationSnapshot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePolicyTest {
    private static final UUID PLAYER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    void blacklistMatchesTargetsCaseInsensitively() {
        RuntimePolicy policy = compile(RestrictionMode.BLACKLIST,
                rule("deny-sword", RuleType.CRAFT_ITEM, "Sword_Of_Truth", RuleAction.DENY, RuleScope.EVERYONE, ""));

        assertFalse(policy.decide(RuleType.CRAFT_ITEM, "sword_of_truth", ready(Set.of())).allowed());
        assertTrue(policy.decide(RuleType.CRAFT_ITEM, "unlisted_item", ready(Set.of())).allowed());
    }

    @Test
    void importedAdventureRuleDeniesTheCanonicalVoidRequiemItem() {
        RuntimePolicy policy = compile(RestrictionMode.BLACKLIST,
                rule("void-requiem", RuleType.CRAFT_ITEM, "WanMine_Void_Requiem_Scythe",
                        RuleAction.DENY, RuleScope.GROUP, "adventure"));

        assertFalse(policy.decide(
                RuleType.CRAFT_ITEM,
                "wanmine_void_requiem_scythe",
                ready(Set.of("adventure"))
        ).allowed());
        assertTrue(policy.decide(
                RuleType.CRAFT_ITEM,
                "WanMine_Void_Requiem_Scythe",
                ready(Set.of("builder"))
        ).allowed());
    }

    @Test
    void playerThenGroupThenEveryonePrecedenceIsPreserved() {
        PreventRule everyone = rule("everyone", RuleType.CRAFT_ITEM, "Item_A", RuleAction.DENY, RuleScope.EVERYONE, "");
        PreventRule group = rule("group", RuleType.CRAFT_ITEM, "Item_A", RuleAction.ALLOW, RuleScope.GROUP, "adventure");
        PreventRule player = rule("player", RuleType.CRAFT_ITEM, "Item_A", RuleAction.DENY, RuleScope.PLAYER, PLAYER_ID.toString());
        RuntimePolicy policy = compile(RestrictionMode.BLACKLIST, everyone, group, player);

        RuleDecision decision = policy.decide(RuleType.CRAFT_ITEM, "item_a", ready(Set.of("adventure")));
        assertFalse(decision.allowed());
        assertEquals(player.Id, decision.rule().Id);
    }

    @Test
    void whitelistAllowsOnlyAnExplicitMatch() {
        PreventRule allowed = rule("allowed", RuleType.CRAFT_ITEM, "Item_A", RuleAction.ALLOW, RuleScope.GROUP, "builder");
        RuntimePolicy policy = compile(RestrictionMode.WHITELIST, allowed);

        assertTrue(policy.decide(RuleType.CRAFT_ITEM, "item_a", ready(Set.of("builder"))).allowed());
        assertFalse(policy.decide(RuleType.CRAFT_ITEM, "item_b", ready(Set.of("builder"))).allowed());
    }

    @Test
    void pendingGroupAuthorizationFailsClosed() {
        PreventRule group = rule("restricted", RuleType.ACCESS_BENCH, "Bench_Forge", RuleAction.DENY, RuleScope.GROUP, "adventure");
        PreventRule everyone = rule("public", RuleType.ACCESS_BENCH, "Bench_Forge", RuleAction.ALLOW, RuleScope.EVERYONE, "");
        RuntimePolicy policy = compile(RestrictionMode.BLACKLIST, everyone, group);
        PlayerAuthorizationSnapshot pending = PlayerAuthorizationSnapshot.pending(PLAYER_ID, "Player");

        assertFalse(policy.decide(RuleType.ACCESS_BENCH, "bench_forge", pending).allowed());
    }

    @Test
    void bypassIsReadFromThePreloadedSnapshot() {
        RuntimePolicy policy = compile(RestrictionMode.BLACKLIST,
                rule("deny", RuleType.CRAFT_ITEM, "Item_A", RuleAction.DENY, RuleScope.EVERYONE, ""));
        PlayerAuthorizationSnapshot bypass = new PlayerAuthorizationSnapshot(
                PLAYER_ID, "Player", Set.of(), false, true, false, true, 1L);

        assertTrue(policy.decide(RuleType.CRAFT_ITEM, "item_a", bypass).allowed());
    }

    @Test
    void tenThousandRulesCompileAndLookupsDoNotScanTheRuleList() {
        PreventCraftConfig config = new PreventCraftConfig();
        config.Rules = new ArrayList<>(10_000);
        for (int index = 0; index < 10_000; index++) {
            config.Rules.add(rule("rule-" + index, RuleType.CRAFT_ITEM, "Item_" + index,
                    RuleAction.DENY, RuleScope.EVERYONE, ""));
        }

        RuntimePolicy policy = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> RuntimePolicy.compile(config, RuntimePolicy.TargetExpander.IDENTITY));
        assertTimeoutPreemptively(Duration.ofMillis(250), () -> {
            PlayerAuthorizationSnapshot snapshot = ready(Set.of());
            for (int index = 0; index < 100_000; index++) {
                assertFalse(policy.decide(RuleType.CRAFT_ITEM, "item_9999", snapshot).allowed());
            }
        });
    }

    private RuntimePolicy compile(RestrictionMode mode, PreventRule... rules) {
        PreventCraftConfig config = new PreventCraftConfig();
        config.Mode = mode;
        config.Rules = new ArrayList<>(List.of(rules));
        return RuntimePolicy.compile(config, RuntimePolicy.TargetExpander.IDENTITY);
    }

    private PreventRule rule(String id, RuleType type, String target, RuleAction action, RuleScope scope, String subject) {
        PreventRule rule = new PreventRule();
        rule.Id = id;
        rule.Type = type;
        rule.Target = target;
        rule.Action = action;
        rule.Scope = scope;
        if (scope == RuleScope.GROUP) rule.Group = subject;
        if (scope == RuleScope.PLAYER) rule.Player = subject;
        return rule;
    }

    private PlayerAuthorizationSnapshot ready(Set<String> groups) {
        return new PlayerAuthorizationSnapshot(PLAYER_ID, "Player", groups, false, false, false, true, 1L);
    }
}
