package tblack.preventcraft.packets;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.window.UpdateWindow;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import tblack.preventcraft.PreventCraftPlugin;

public final class UpdateWindowPacketInterceptor extends PacketInterceptor {
    public UpdateWindowPacketInterceptor(PreventCraftPlugin plugin) {
        super(plugin);
    }

    @Override
    public void register() {
        packetFilter = PacketAdapters.registerOutbound(this::handle);
    }

    private boolean handle(PlayerRef playerRef, Packet packet) {
        if (!plugin.getPreventCraftConfig().HideBlockedRecipes) return false;
        if (!(packet instanceof UpdateWindow updateWindow)) return false;
        if (updateWindow.windowData == null || updateWindow.windowData.isBlank()) return false;
        if (!updateWindow.windowData.contains("optionSlotRecipes")) return false;
        try {
            JsonObject root = JsonParser.parseString(updateWindow.windowData).getAsJsonObject();
            if (!RecipePackets.hasArray(root, "optionSlotRecipes")) return false;
            root.add("optionSlotRecipes", RecipePackets.filteredRecipes(plugin, playerRef, root.getAsJsonArray("optionSlotRecipes")));
            updateWindow.windowData = root.toString();
        } catch (Exception exception) {
            if (plugin.getPreventCraftConfig().Debug) {
                PreventCraftPlugin.LOGGER.atWarning().withCause(exception).log("Could not filter option slot recipes.");
            }
        }
        return false;
    }
}
