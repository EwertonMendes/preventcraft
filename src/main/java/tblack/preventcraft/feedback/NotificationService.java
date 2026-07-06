package tblack.preventcraft.feedback;

import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import tblack.preventcraft.config.PreventCraftConfig;
import tblack.preventcraft.i18n.I18n;

public final class NotificationService {
    private NotificationService() {
    }

    public static void sendDenied(PlayerRef playerRef, PreventCraftConfig config, DenialKind kind) {
        if (playerRef == null || config == null || config.Feedback == null) return;
        if (shouldSendMessage(config, kind)) {
            String message = message(playerRef, config, kind);
            if (message != null && !message.isBlank()) playerRef.sendMessage(Message.raw(message).color("#FF6B81"));
        }
        if (config.Feedback.SendDeniedSound) playDeniedSound(playerRef, config.Feedback.DeniedSound);
    }

    private static boolean shouldSendMessage(PreventCraftConfig config, DenialKind kind) {
        return switch (kind) {
            case CRAFT_ITEM, CRAFT_BENCH -> config.Feedback.SendCraftDeniedMessage;
            case ACCESS_BENCH -> config.Feedback.SendBenchDeniedMessage;
        };
    }

    private static String message(PlayerRef playerRef, PreventCraftConfig config, DenialKind kind) {
        String configured = switch (kind) {
            case CRAFT_ITEM -> config.Feedback.CraftDeniedMessage;
            case CRAFT_BENCH -> config.Feedback.BenchCraftDeniedMessage;
            case ACCESS_BENCH -> config.Feedback.BenchAccessDeniedMessage;
        };
        String defaultMessage = switch (kind) {
            case CRAFT_ITEM -> "You cannot craft this item.";
            case CRAFT_BENCH -> "You cannot craft this bench.";
            case ACCESS_BENCH -> "You cannot use this bench.";
        };
        if (configured != null && !configured.isBlank() && !configured.equals(defaultMessage)) return configured;
        String key = switch (kind) {
            case CRAFT_ITEM -> "messages.craft_denied";
            case CRAFT_BENCH -> "messages.bench_craft_denied";
            case ACCESS_BENCH -> "messages.bench_access_denied";
        };
        return I18n.translate(I18n.localeFromPlayerRef(playerRef), key);
    }

    private static void playDeniedSound(PlayerRef playerRef, String soundId) {
        try {
            int index;
            try {
                index = SoundEvent.getAssetMap().getIndex(soundId);
            } catch (Exception exception) {
                index = SoundEvent.getAssetMap().getIndex("SFX_Antelope_Alerted");
            }
            SoundUtil.playSoundEvent2dToPlayer(playerRef, index, SoundCategory.UI);
        } catch (Throwable ignored) {
        }
    }
}
