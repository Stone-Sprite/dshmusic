package dev.dsh.music;

import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * Client-side hooks: the right-click that opens the screen, the chat listener that
 * feeds the sync channel, and the tick that keeps sync housekeeping moving.
 *
 * <p>Registered on the game event bus from the mod constructor.
 */
public final class MusicClient {

    private MusicClient() {
    }

    /** Right-click while holding a stick opens the player instead of using the item. */
    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (!event.isUseItem() || mc.player == null || mc.screen != null) {
            return;
        }
        if (!MusicHud.holdingStick(mc.player)) {
            return;
        }
        event.setCanceled(true);
        mc.setScreen(new MusicScreen());
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        MusicMod.tick();
    }

    /**
     * Watch chat for sync payloads. Private messages arrive as chat lines, so this is
     * where an incoming {@code [DSHM]{...}} announcement is picked up.
     */
    @SubscribeEvent
    public static void onChat(ClientChatReceivedEvent event) {
        String raw = event.getMessage().getString();
        if (!raw.contains(SyncChannel.PREFIX)) {
            return;
        }
        String note = MusicMod.SYNC.handleIncoming(raw, null);
        if (note != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("§b[音乐] §f" + note), false);
            }
        }
    }
}
