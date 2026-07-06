package tblack.preventcraft.packets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.window.OpenWindow;
import com.hypixel.hytale.protocol.packets.window.WindowType;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import tblack.preventcraft.PreventCraftPlugin;

public final class OpenWindowPacketInterceptor extends PacketInterceptor {
    public OpenWindowPacketInterceptor(PreventCraftPlugin plugin) {
        super(plugin);
    }

    @Override
    public void register() {
        packetFilter = PacketAdapters.registerOutbound(this::handle);
    }

    private boolean handle(PlayerRef playerRef, Packet packet) {
        if (!plugin.getPreventCraftConfig().HideBlockedRecipes) return false;
        if (!(packet instanceof OpenWindow openWindow)) return false;
        if (openWindow.windowData == null || openWindow.windowData.isBlank()) return false;
        if (openWindow.windowType != WindowType.BasicCrafting) return false;
        if (!openWindow.windowData.contains("categories")) return false;
        try {
            JsonObject root = JsonParser.parseString(openWindow.windowData).getAsJsonObject();
            if (!RecipePackets.hasArray(root, "categories")) return false;
            JsonArray categories = root.getAsJsonArray("categories");
            for (int i = 0; i < categories.size(); i++) {
                JsonObject category = categories.get(i).getAsJsonObject();
                if (!RecipePackets.hasArray(category, "craftableRecipes")) continue;
                category.add("craftableRecipes", RecipePackets.filteredRecipes(plugin, playerRef, category.getAsJsonArray("craftableRecipes")));
            }
            openWindow.windowData = root.toString();
        } catch (Exception exception) {
            if (plugin.getPreventCraftConfig().Debug) {
                PreventCraftPlugin.LOGGER.atWarning().withCause(exception).log("Could not filter BasicCrafting window recipes.");
            }
        }
        return false;
    }
}
