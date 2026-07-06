package tblack.preventcraft.packets;

import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import tblack.preventcraft.PreventCraftPlugin;

public abstract class PacketInterceptor {
    protected final PreventCraftPlugin plugin;
    protected PacketFilter packetFilter;

    protected PacketInterceptor(PreventCraftPlugin plugin) {
        this.plugin = plugin;
    }

    public abstract void register();

    public void unregister() {
        if (packetFilter == null) return;
        try {
            PacketAdapters.deregisterInbound(packetFilter);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            PacketAdapters.deregisterOutbound(packetFilter);
        } catch (IllegalArgumentException ignored) {
        }
        packetFilter = null;
    }
}
