package dev.dsh.music;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client-side hooks: the right-click that opens the screen, the chat listener that
 * feeds the sync channel, and the tick that keeps sync housekeeping moving.
 *
 * <p>Fabric has no equivalent of NeoForge's {@code InteractionKeyMappingTriggered}, so
 * the use key is polled on the client tick with edge detection instead. That avoids a
 * mixin for what is ultimately one keypress, and it cannot fire twice for one press.
 */
public final class MusicClient {

    private MusicClient() {
    }

    /** Set while the use key is held, so one press opens the screen exactly once. */
    private static boolean useWasDown;

    public static void onEndTick(Minecraft mc) {
        MusicMod.tick();

        // 26.3 tracks the open screen on Gui rather than on Minecraft itself.
        // When a screen is open the game releases the movement/use keys, so isDown()
        // is already false and no extra guard is needed for that case.
        if (mc.player == null || mc.gui.screen() != null) {
            useWasDown = false;
            return;
        }
        boolean down = mc.options.keyUse.isDown();
        if (down && !useWasDown && MusicHud.holdingStick(mc.player)) {
            mc.setScreenAndShow(new MusicScreen());
        }
        useWasDown = down;
    }

    /**
     * Watch chat for sync payloads. Private messages arrive as chat lines, so this is
     * where an incoming {@code [DSHM]{...}} announcement is picked up.
     */
    public static void onChat(Component message) {
        String raw = message.getString();
        if (!raw.contains(SyncChannel.PREFIX)) {
            return;
        }
        String note = MusicMod.SYNC.handleIncoming(raw, null);
        if (note != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                // 26.3 replaced displayClientMessage with sendSystemMessage
                mc.player.sendSystemMessage(Component.literal("§b[音乐] §f" + note));
            }
        }
    }
}
