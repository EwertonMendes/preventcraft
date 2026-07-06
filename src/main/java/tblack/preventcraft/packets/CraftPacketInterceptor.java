package tblack.preventcraft.packets;

import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.window.CraftRecipeAction;
import com.hypixel.hytale.protocol.packets.window.SendWindowAction;
import com.hypixel.hytale.protocol.packets.window.WindowAction;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import tblack.preventcraft.PreventCraftPlugin;
import tblack.preventcraft.feedback.DenialKind;
import tblack.preventcraft.feedback.NotificationService;
import tblack.preventcraft.rule.RuleDecision;

public final class CraftPacketInterceptor extends PacketInterceptor {
    public CraftPacketInterceptor(PreventCraftPlugin plugin) {
        super(plugin);
    }

    @Override
    public void register() {
        packetFilter = PacketAdapters.registerInbound(this::handle);
    }

    private boolean handle(PlayerRef playerRef, Packet packet) {
        if (!(packet instanceof SendWindowAction windowActionPacket)) return false;
        WindowAction action = windowActionPacket.action;
        if (!(action instanceof CraftRecipeAction craftAction)) return false;
        String recipeId = craftAction.recipeId;
        if (recipeId == null || recipeId.isBlank()) return false;
        String outputItemId = RecipePackets.outputItemId(recipeId);
        if (outputItemId == null || outputItemId.isBlank()) return false;
        RuleDecision decision = plugin.getRuleService().decideCraft(playerRef, outputItemId);
        if (decision.allowed()) return false;
        DenialKind kind = decision.rule() != null && decision.rule().Type == tblack.preventcraft.rule.RuleType.CRAFT_BENCH
                ? DenialKind.CRAFT_BENCH
                : DenialKind.CRAFT_ITEM;
        NotificationService.sendDenied(playerRef, plugin.getPreventCraftConfig(), kind);
        if (plugin.getPreventCraftConfig().Debug) {
            PreventCraftPlugin.LOGGER.atInfo().log("Blocked craft: player=%s recipe=%s output=%s reason=%s", safeName(playerRef), recipeId, outputItemId, decision.reason());
        }
        return true;
    }

    private String safeName(PlayerRef playerRef) {
        try {
            return playerRef.getUsername();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
