package dev.dsh.music;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;

/**
 * The stick read-out: what is playing, how far in, and how loud.
 *
 * <p>Appears only while either hand holds a stick, so it never clutters the HUD during
 * normal play.
 *
 * <p>Ported from the NeoForge build. 26.3 renamed the HUD plumbing: the layer interface
 * is {@link HudElement} and its method is {@code extractRenderState}, which receives a
 * {@link GuiGraphicsExtractor} rather than a {@code GuiGraphics}. Text drawing is
 * {@code text(...)} rather than {@code drawString(...)}, and the surface reports its own
 * size through {@code guiWidth()}/{@code guiHeight()} instead of the window. The sizing
 * itself is unchanged: capped to a third of the width so it stays out of the way.
 */
final class MusicHud implements HudElement {

    private static final int PANEL = 0xF0251A0E;
    private static final int BODY = 0xFFEEEEEE;
    private static final int DIM = 0xFFA3A3A3;
    private static final int ACCENT = 0xFF8AB6D6;
    private static final int GREEN = 0xCC20CC20;
    private static final int BAR_BG = 0xFF2A1030;
    private static final int BAR_FG = 0xFF5FF0FF;

    /**
     * The bezel, in Create's brass.
     *
     * <p>Four tones rather than one: a flat gold rectangle reads as a yellow box, while
     * an outline, a body, a highlight on the top/left edge and a shade on the bottom/
     * right edge read as a machined brass frame.
     */
    private static final int BRASS_DARK = 0xFF301E0E;
    private static final int BRASS = 0xFFD7AA5E;
    private static final int BRASS_HI = 0xFFFFEB8C;
    private static final int BRASS_SHADOW = 0xFF724731;
    private static final int BEZEL = 2;

    /** Never wider than this fraction of the GUI, so it cannot dominate the view. */
    private static final double MAX_WIDTH_FRACTION = 0.34;
    private static final int MIN_WIDTH = 104;
    private static final int MAX_WIDTH = 168;
    private static final int HEIGHT = 27;
    /** Distance from the bottom of the GUI to the bottom of the panel. */
    private static final int BOTTOM_GAP = 42;

    static boolean holdingStick(LocalPlayer p) {
        // ItemStack.is() now takes a Predicate<Holder<Item>>, so compare the item itself
        return p.getMainHandItem().getItem() == Items.STICK
                || p.getOffhandItem().getItem() == Items.STICK;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        // 26.3 moved the F1 "hide GUI" state off Options.hideGui; the layer is small
        // enough to leave the check out rather than guess at a replacement.
        if (p == null || mc.level == null || !holdingStick(p)) {
            return;
        }

        Mp3Player player = MusicMod.PLAYER;

        int guiW = g.guiWidth();
        int guiH = g.guiHeight();

        int w = Mth.clamp((int) (guiW * MAX_WIDTH_FRACTION), MIN_WIDTH, MAX_WIDTH);
        w = Math.min(w, guiW - 8);
        int x = guiW / 2 - w / 2;
        int y = guiH - BOTTOM_GAP - HEIGHT;

        // Brass bezel, drawn outside the panel rect: outline, body, then the bevel
        // highlights. Order matters — the highlight has to land on top of the body.
        int b = BEZEL;
        g.fill(x - b - 1, y - b - 1, x + w + b + 1, y + HEIGHT + b + 1, BRASS_DARK);
        g.fill(x - b, y - b, x + w + b, y + HEIGHT + b, BRASS);
        g.fill(x, y, x + w, y + HEIGHT, PANEL);
        // lit from the top-left
        g.fill(x - b, y - b, x + w + b, y - b + 1, BRASS_HI);
        g.fill(x - b, y - b, x - b + 1, y + HEIGHT + b, BRASS_HI);
        // shaded on the bottom-right
        g.fill(x - b, y + HEIGHT + b - 1, x + w + b, y + HEIGHT + b, BRASS_SHADOW);
        g.fill(x + w + b - 1, y - b, x + w + b, y + HEIGHT + b, BRASS_SHADOW);
        // corner rivets, the detail that makes it read as Create's brasswork
        for (int cx : new int[]{x - b, x + w + b - 2}) {
            for (int cy : new int[]{y - b, y + HEIGHT + b - 2}) {
                g.fill(cx, cy, cx + 2, cy + 2, BRASS_HI);
            }
        }

        int pad = 4;
        int inner = w - pad * 2;

        // row 1: track name on the left, transport state on the right
        if (!player.isNetwork() && player.file() == null) {
            String msg = MusicMod.isScanning() ? "扫描中…"
                    : ("♪ 已索引 " + MusicMod.LIBRARY.tracks().size() + " 首");
            g.text(mc.font, fit(mc, msg, inner), x + pad, y + 4, ACCENT);
            return;
        }

        String name;
        if (player.isNetwork()) {
            name = player.netStatus().isEmpty() ? player.displayName() : player.netStatus();
        } else {
            var t = MusicMod.LIBRARY.byFingerprint(player.fingerprint());
            name = t != null ? t.display() : player.file().getFileName().toString();
        }
        String state = player.isPlaying() ? "▶" : (player.isPaused() ? "❚❚" : "■");
        int stateW = mc.font.width(state);
        g.text(mc.font, fit(mc, name, inner - stateW - 4), x + pad, y + 4,
                player.isNetwork() ? ACCENT : BODY);
        g.text(mc.font, state, x + w - pad - stateW, y + 4,
                player.isPlaying() ? GREEN : DIM);

        // row 2: elapsed, bar, total
        int row2 = y + 15;
        String left = clock(player.position());
        String right = player.duration() > 0 ? clock(player.duration()) : "--:--";
        int lw = mc.font.width(left);
        int rw = mc.font.width(right);
        g.text(mc.font, left, x + pad, row2, DIM);
        g.text(mc.font, right, x + w - pad - rw, row2, DIM);

        int barX = x + pad + lw + 3;
        int barW = w - pad * 2 - lw - rw - 6;
        if (barW > 6) {
            g.fill(barX, row2 + 2, barX + barW, row2 + 5, BAR_BG);
            double dur = player.duration();
            if (dur > 0) {
                int filled = (int) Math.round(barW * Mth.clamp(player.position() / dur, 0, 1));
                g.fill(barX, row2 + 2, barX + filled, row2 + 5, BAR_FG);
            }
        }
    }

    private static String clock(double seconds) {
        int t = (int) Math.max(0, Math.round(seconds));
        return String.format("%d:%02d", t / 60, t % 60);
    }

    /** Trim to width, appending an ellipsis when something had to go. */
    private static String fit(Minecraft mc, String s, int width) {
        if (width <= 0) {
            return "";
        }
        if (mc.font.width(s) <= width) {
            return s;
        }
        String out = s;
        while (out.length() > 1 && mc.font.width(out + "…") > width) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }
}
