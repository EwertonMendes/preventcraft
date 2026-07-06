package tblack.preventcraft.packets;

import tblack.preventcraft.PreventCraftPlugin;

import java.util.ArrayList;
import java.util.List;

public final class PacketManager {
    private final List<PacketInterceptor> interceptors = new ArrayList<>();

    public PacketManager(PreventCraftPlugin plugin) {
        interceptors.add(new CraftPacketInterceptor(plugin));
        interceptors.add(new OpenWindowPacketInterceptor(plugin));
        interceptors.add(new UpdateWindowPacketInterceptor(plugin));
    }

    public void register() {
        for (PacketInterceptor interceptor : interceptors) {
            try {
                interceptor.register();
            } catch (Throwable throwable) {
                PreventCraftPlugin.LOGGER.atWarning().withCause(throwable).log("Packet interceptor %s could not be registered.", interceptor.getClass().getSimpleName());
            }
        }
    }

    public void unregister() {
        for (PacketInterceptor interceptor : interceptors) interceptor.unregister();
    }
}
