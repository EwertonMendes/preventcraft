package tblack.preventcraft.systems;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.Bench;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import tblack.preventcraft.PreventCraftPlugin;
import tblack.preventcraft.feedback.DenialKind;
import tblack.preventcraft.feedback.NotificationService;
import tblack.preventcraft.rule.RuleDecision;
import tblack.preventcraft.rule.RuleType;

public final class BenchAccessSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {
    private final PreventCraftPlugin plugin;

    public BenchAccessSystem(PreventCraftPlugin plugin) {
        super(UseBlockEvent.Pre.class);
        this.plugin = plugin;
    }

    @Override
    public void handle(int entityIndex, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer, UseBlockEvent.Pre event) {
        Ref<EntityStore> ref = chunk.getReferenceTo(entityIndex);
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null || event == null) return;
        BlockType blockType = event.getBlockType();
        if (blockType == null) return;
        Bench bench;
        try {
            bench = blockType.getBench();
        } catch (Throwable ignored) {
            return;
        }
        if (bench == null) return;
        String benchId = bench.getId();
        if (benchId == null || benchId.isBlank()) return;
        RuleDecision decision = plugin.getRuleService().decide(playerRef, RuleType.ACCESS_BENCH, benchId);
        if (!decision.allowed()) {
            event.setCancelled(true);
            NotificationService.sendDenied(playerRef, plugin.getPreventCraftConfig(), DenialKind.ACCESS_BENCH);
            if (plugin.getPreventCraftConfig().Debug) {
                PreventCraftPlugin.LOGGER.atInfo().log("Blocked bench access: player=%s bench=%s reason=%s", safeName(playerRef), benchId, decision.reason());
            }
        }
    }

    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    private String safeName(PlayerRef playerRef) {
        try {
            return playerRef.getUsername();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
