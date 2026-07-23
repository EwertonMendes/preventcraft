package tblack.preventcraft.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.event.events.ecs.CraftRecipeEvent;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import tblack.preventcraft.PreventCraftPlugin;
import tblack.preventcraft.feedback.DenialKind;
import tblack.preventcraft.feedback.NotificationService;
import tblack.preventcraft.rule.RuleDecision;
import tblack.preventcraft.rule.RuleType;

/** Authoritative craft guard. Runs before the server consumes inputs or queues a job. */
public final class CraftRestrictionSystem extends EntityEventSystem<EntityStore, CraftRecipeEvent.Pre> {
    private final PreventCraftPlugin plugin;

    public CraftRestrictionSystem(PreventCraftPlugin plugin) {
        super(CraftRecipeEvent.Pre.class);
        this.plugin = plugin;
    }

    @Override
    public void handle(
            int entityIndex,
            ArchetypeChunk<EntityStore> chunk,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer,
            CraftRecipeEvent.Pre event
    ) {
        if (event == null || event.isCancelled()) return;
        Ref<EntityStore> ref = chunk.getReferenceTo(entityIndex);
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) return;

        String outputItemId = primaryOutputItemId(event.getCraftedRecipe());
        if (outputItemId == null || outputItemId.isBlank()) return;
        RuleDecision decision = plugin.getRuleService().decideCraft(playerRef, outputItemId);
        if (decision.allowed()) return;

        event.setCancelled(true);
        DenialKind kind = decision.rule() != null && decision.rule().Type == RuleType.CRAFT_BENCH
                ? DenialKind.CRAFT_BENCH
                : DenialKind.CRAFT_ITEM;
        NotificationService.sendDenied(playerRef, plugin.getPreventCraftConfig(), kind);
        if (plugin.getPreventCraftConfig().Debug) {
            PreventCraftPlugin.LOGGER.atInfo().log(
                    "Blocked craft: player=%s recipe=%s output=%s reason=%s",
                    safeName(playerRef), safeRecipeId(event.getCraftedRecipe()), outputItemId, decision.reason());
        }
    }

    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    static String primaryOutputItemId(CraftingRecipe recipe) {
        if (recipe == null) return null;
        MaterialQuantity primaryOutput = recipe.getPrimaryOutput();
        return primaryOutput == null ? null : primaryOutput.getItemId();
    }

    private String safeRecipeId(CraftingRecipe recipe) {
        try {
            return recipe == null ? "unknown" : String.valueOf(recipe.getId());
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private String safeName(PlayerRef playerRef) {
        try {
            return playerRef.getUsername();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
