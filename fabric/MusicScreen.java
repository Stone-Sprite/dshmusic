package dev.dsh.music;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The stick's right-click screen.
 *
 * <p>Every position is derived from one panel rectangle, which is itself derived from
 * the window, so controls cannot overlap at any GUI scale.
 *
 * <p>Ported from the NeoForge build against the 26.3 screen API: {@code render} became
 * {@code extractRenderState}, {@code renderBackground} became {@code extractBackground},
 * drawing goes through a {@link GuiGraphicsExtractor}, and screens are shown with
 * {@code setScreenAndShow}. The layout arithmetic is unchanged.
 */
final class MusicScreen extends Screen {

    private static final int PANEL = 0xF0100010;
    private static final int EDGE = 0xDD8AB6D6;
    private static final int DIVIDER = 0xFF9ABBD3;
    private static final int TITLE = 0xEEFFFFFF;
    private static final int BODY = 0xFFEEEEEE;
    private static final int DIM = 0xFFA3A3A3;
    private static final int ACCENT = 0xFF8AB6D6;
    private static final int GREEN = 0xCC20CC20;
    private static final int ROW_HOVER = 0x505000FF;

    private static final String[] PROVIDERS = {"本地库", "B站（音频区）", "网易云", "QQ音乐"};

    /** "local" plays the disk index; "search" queries the providers. */
    private static String mode = "local";
    private static int provider;
    private static String filter = "";

    // layout, recomputed in init()
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int dividerY;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int rightX;
    private int rightW;
    private int bodyY;
    private int bodyH;
    private int footerY;

    private EditBox searchBox;
    private final List<MusicLibrary.Track> shown = new ArrayList<>();
    private int scroll;

    /** Network hits, kept across screen opens like the mode and provider are. */
    private static final List<ProviderSearch.Result> NET = new ArrayList<>();
    private static volatile String netStatus = "";
    private static volatile boolean searching;

    MusicScreen() {
        super(Component.literal("DSH Music"));
    }

    @Override
    protected void init() {
        int pad = 6;
        int gap = 6;
        int btnH = 14;
        int rowStep = btnH + 3;

        panelW = Math.min(width - 12, 400);
        panelH = Math.min(height - 16, 220);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        dividerY = panelY + 16;
        footerY = panelY + panelH - 22;
        bodyY = dividerY + 3;
        bodyH = footerY - bodyY - 3;

        int colW = (panelW - pad * 2 - gap) / 2;
        listX = panelX + pad;
        listW = colW;
        rightX = listX + colW + gap;
        rightW = colW;

        searchBox = new EditBox(font, listX, bodyY, listW, 14, Component.literal("搜索"));
        searchBox.setValue(filter);
        searchBox.setHint(Component.literal("搜索 歌名 / 歌手 / 专辑"));
        searchBox.setResponder(s -> {
            filter = s;
            refresh();
        });
        addRenderableWidget(searchBox);

        listY = bodyY + 17;
        listH = Math.max(24, bodyY + bodyH - listY);

        int by = bodyY;
        addRenderableWidget(Button.builder(modeLabel(), b -> {
            mode = "local".equals(mode) ? "search" : "local";
            b.setMessage(modeLabel());
            refresh();
        }).bounds(rightX, by, rightW, btnH).build());
        by += rowStep;

        addRenderableWidget(Button.builder(
                Component.literal("搜索"), b -> runSearch())
                .bounds(rightX, by, Math.max(40, rightW / 3), btnH).build());
        addRenderableWidget(Button.builder(
                Component.literal("提供商： " + PROVIDERS[provider]), b -> {
                    provider = (provider + 1) % PROVIDERS.length;
                    b.setMessage(Component.literal("提供商： " + PROVIDERS[provider]));
                }).bounds(rightX + Math.max(40, rightW / 3) + 3, by,
                        rightW - Math.max(40, rightW / 3) - 3, btnH).build());
        by += rowStep;

        addRenderableWidget(Button.builder(shareLabel(), b -> {
            MusicMod.SYNC.setEnabled(!MusicMod.SYNC.enabled());
            if (MusicMod.SYNC.enabled()) {
                MusicMod.SYNC.announceNow();
            }
            b.setMessage(shareLabel());
        }).bounds(rightX, by, rightW, btnH).build());
        by += rowStep;

        addRenderableWidget(Button.builder(
                Component.literal("选择共享的玩家 (" + MusicMod.SYNC.targets().size() + ")"),
                b -> minecraft.setScreenAndShow(new PlayerPickerScreen(this)))
                .bounds(rightX, by, rightW, btnH).build());
        by += rowStep + 3;

        int qw = Math.max(20, (rightW - 12) / 5);
        addRenderableWidget(Button.builder(Component.literal("⏮"), b -> MusicMod.skip(-1))
                .bounds(rightX, by, qw, btnH).build());
        addRenderableWidget(Button.builder(
                Component.literal(MusicMod.PLAYER.isPlaying() ? "❚❚" : "▶"), b -> {
                    MusicMod.PLAYER.togglePause();
                    b.setMessage(Component.literal(MusicMod.PLAYER.isPlaying() ? "❚❚" : "▶"));
                }).bounds(rightX + qw + 3, by, qw, btnH).build());
        addRenderableWidget(Button.builder(Component.literal("■"), b -> MusicMod.PLAYER.stop())
                .bounds(rightX + (qw + 3) * 2, by, qw, btnH).build());
        addRenderableWidget(Button.builder(Component.literal("⏭"), b -> MusicMod.skip(1))
                .bounds(rightX + (qw + 3) * 3, by, qw, btnH).build());
        addRenderableWidget(Button.builder(loopLabel(), b -> {
            MusicMod.PLAYER.cycleLoopMode();
            b.setMessage(loopLabel());
        }).bounds(rightX + (qw + 3) * 4, by, qw, btnH).build());
        by += rowStep;

        int vw = Math.max(28, (rightW - 6) / 3);
        addRenderableWidget(Button.builder(Component.literal("音量 -"),
                b -> MusicMod.PLAYER.adjustVolume(-0.1f)).bounds(rightX, by, vw, btnH).build());
        addRenderableWidget(Button.builder(Component.literal("音量 +"),
                b -> MusicMod.PLAYER.adjustVolume(0.1f))
                .bounds(rightX + vw + 3, by, vw, btnH).build());
        addRenderableWidget(Button.builder(Component.literal("重新扫描"), b -> MusicMod.rescan())
                .bounds(rightX + (vw + 3) * 2, by, vw, btnH).build());

        int cw = Math.min(80, panelW / 3);
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(panelX + panelW / 2 - cw / 2, footerY, cw, btnH).build());

        refresh();
    }

    private static Component modeLabel() {
        return Component.literal("local".equals(mode) ? "本地播放（点击切网络搜索）"
                : "网络搜索（点击切本地播放）");
    }

    private static String modeLabelText() {
        return "local".equals(mode) ? "本地播放" : "网络搜索";
    }

    /** Transport button text: the loop mode is the button's whole label. */
    private static Component loopLabel() {
        return Component.literal(MusicMod.PLAYER.loopMode().label);
    }

    private static Component shareLabel() {
        return Component.literal("共享音乐： " + (MusicMod.SYNC.enabled() ? "开" : "关"));
    }

    /**
     * Run the provider query.
     *
     * <p>In local mode there is nothing to query, so the button only focuses the filter
     * box. In search mode it performs the request on a worker thread and posts the
     * result back to the client thread, because the screen redraws on the render thread
     * and must not block on a socket.
     */
    private void runSearch() {
        if ("local".equals(mode)) {
            searchBox.setFocused(true);
            netStatus = "";
            return;
        }
        String q = filter;
        if (q == null || q.isBlank()) {
            netStatus = "请先在搜索框输入关键词";
            return;
        }
        if (searching) {
            return;
        }
        searching = true;
        netStatus = "正在搜索 " + PROVIDERS[provider] + " …";
        Thread t = new Thread(() -> {
            String status;
            List<ProviderSearch.Result> got = List.of();
            try {
                got = ProviderSearch.bilibili(q);
                status = got.isEmpty() ? "没有结果" : ("找到 " + got.size() + " 条");
            } catch (Throwable e) {
                status = "搜索失败： " + e.getClass().getSimpleName() + " " + e.getMessage();
            }
            final List<ProviderSearch.Result> res = got;
            final String st = status;
            Minecraft.getInstance().execute(() -> {
                NET.clear();
                NET.addAll(res);
                netStatus = st;
                scroll = 0;
            });
            searching = false;
        }, "dshmusic-search");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Turn a search hit into playback.
     *
     * <p>Runs off-thread: cid lookup, stream resolution and the download are all
     * network work. The stream URL is bound to the requesting address and expires, so
     * it is resolved at click time and never cached or handed to other clients.
     */
    private void resolveAndPlay(ProviderSearch.Result r) {
        netStatus = "解析 " + r.bvid + " …";
        Thread t = new Thread(() -> {
            try {
                long cid = ProviderSearch.cidOf(r.bvid);
                if (cid == 0) {
                    netStatus = "取不到 cid";
                    return;
                }
                String url = ProviderSearch.audioUrl(r.bvid, cid);
                if (url == null) {
                    netStatus = "该视频没有公开音频流（可能需要会员）";
                    return;
                }
                Minecraft.getInstance().execute(() -> {
                    MusicMod.PLAYER.loadUrl(url, "https://www.bilibili.com/",
                            r.title, "net:" + r.bvid, durationSeconds(r.duration));
                    MusicMod.PLAYER.play();
                    netStatus = "正在加载：" + r.title;
                });
            } catch (Throwable e) {
                netStatus = "解析失败： " + e.getClass().getSimpleName();
            }
        }, "dshmusic-resolve");
        t.setDaemon(true);
        t.start();
    }

    /** "12:34" / "1:02:03" -> seconds. */
    static double durationSeconds(String d) {
        if (d == null || d.isBlank()) {
            return 0;
        }
        String[] p = d.split(":");
        try {
            if (p.length == 2) {
                return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
            }
            if (p.length == 3) {
                return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60
                        + Integer.parseInt(p[2]);
            }
            return Integer.parseInt(d);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void refresh() {
        shown.clear();
        List<MusicLibrary.Track> all = MusicMod.LIBRARY.tracks();
        if (filter == null || filter.isBlank()) {
            shown.addAll(all);
        } else {
            String q = filter.toLowerCase(Locale.ROOT);
            for (MusicLibrary.Track t : all) {
                if (t.matches(q)) {
                    shown.add(t);
                }
            }
        }
        scroll = Mth.clamp(scroll, 0, Math.max(0, shown.size() - rows()));
    }

    private int rows() {
        return Math.max(1, listH / 11);
    }

    /**
     * The panel is drawn from extractBackground, which the framework calls once per
     * frame before the widgets. Drawing it from extractRenderState instead would put it
     * on top of the buttons.
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractBackground(g, mouseX, mouseY, partial);

        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, PANEL);
        g.fill(panelX - 1, panelY - 1, panelX + panelW + 1, panelY, EDGE);
        g.fill(panelX - 1, panelY + panelH, panelX + panelW + 1, panelY + panelH + 1, EDGE);
        g.fill(panelX - 1, panelY, panelX, panelY + panelH, EDGE);
        g.fill(panelX + panelW, panelY, panelX + panelW + 1, panelY + panelH, EDGE);

        // header
        g.text(font, "♪ DSH Music", listX, panelY + 4, TITLE);
        String m = "模式：" + modeLabelText() + "   提供商：" + PROVIDERS[provider];
        g.text(font, m, listX + font.width("♪ DSH Music") + 10, panelY + 4, ACCENT);
        g.fill(panelX + 4, dividerY, panelX + panelW - 4, dividerY + 1, DIVIDER);

        // track list: the local index in local mode, provider hits in search mode
        g.fill(listX - 1, listY - 1, listX + listW + 1, listY + listH + 1, 0x60000000);
        int n = rows();
        if ("local".equals(mode)) {
            for (int i = 0; i < n; i++) {
                int idx = scroll + i;
                if (idx >= shown.size()) {
                    break;
                }
                MusicLibrary.Track t = shown.get(idx);
                int ry = listY + i * 11;
                boolean hover = mouseX >= listX && mouseX < listX + listW
                        && mouseY >= ry && mouseY < ry + 11;
                boolean current = t.fingerprint.equalsIgnoreCase(MusicMod.PLAYER.fingerprint());
                if (hover) {
                    g.fill(listX, ry, listX + listW, ry + 11, ROW_HOVER);
                }
                int color = current ? GREEN : (hover ? TITLE : BODY);
                String d = t.duration();
                g.text(font, fit(t.display(), listW - font.width(d) - 9), listX + 3, ry + 2, color);
                g.text(font, d, listX + listW - font.width(d) - 3, ry + 2, DIM);
            }
            if (shown.isEmpty()) {
                g.text(font, MusicMod.isScanning() ? "正在扫描…" : "没有匹配的本地文件",
                        listX + 3, listY + 3, DIM);
            }
        } else {
            for (int i = 0; i < n; i++) {
                int idx = scroll + i;
                if (idx >= NET.size()) {
                    break;
                }
                ProviderSearch.Result r = NET.get(idx);
                int ry = listY + i * 11;
                boolean hover = mouseX >= listX && mouseX < listX + listW
                        && mouseY >= ry && mouseY < ry + 11;
                if (hover) {
                    g.fill(listX, ry, listX + listW, ry + 11, ROW_HOVER);
                }
                String d = r.duration;
                g.text(font, fit(r.title, listW - font.width(d) - 9), listX + 3, ry + 2,
                        hover ? TITLE : BODY);
                g.text(font, d, listX + listW - font.width(d) - 3, ry + 2, DIM);
            }
            if (NET.isEmpty()) {
                g.text(font, netStatus.isEmpty() ? "输入关键词后点「搜索」" : netStatus,
                        listX + 3, listY + 3, searching ? ACCENT : DIM);
            } else if (!netStatus.isEmpty()) {
                g.text(font, netStatus, listX + 3, listY + listH - 11, DIM);
            }
        }

        // now playing, under the list inside the body area
        Mp3Player player = MusicMod.PLAYER;
        String now;
        if (player.isNetwork()) {
            now = player.netStatus().isEmpty() ? player.displayName() : player.netStatus();
        } else {
            var track = player.file() == null ? null
                    : MusicMod.LIBRARY.byFingerprint(player.fingerprint());
            now = track != null ? track.display()
                    : (player.file() == null ? "未在播放" : player.file().getFileName().toString());
        }
        int ny = listY + listH + 3;
        if (ny + 22 < footerY) {
            g.text(font, fit(now, panelW - 24), listX, ny, BODY);
            String pos = String.format("%.0f/%.0f 秒  音量 %d%%  循环 %s   %s",
                    player.position(), player.duration(), Math.round(player.volume() * 100),
                    player.loopMode().label,
                    MusicMod.SYNC.enabled()
                            ? ("共享→" + String.join(",", MusicMod.SYNC.targets()))
                            : "未共享");
            g.text(font, fit(pos, panelW - 24), listX, ny + 11, DIM);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);
    }

    private String fit(String s, int w) {
        if (w <= 0) {
            return "";
        }
        if (font.width(s) <= w) {
            return s;
        }
        String out = s;
        while (out.length() > 1 && font.width(out + "…") > w) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
            int i = (int) ((my - listY) / 11);
            int idx = scroll + i;
            if ("local".equals(mode)) {
                if (idx >= 0 && idx < shown.size()) {
                    MusicMod.play(shown.get(idx));
                    MusicMod.SYNC.announceNow();
                    return true;
                }
            } else if (idx >= 0 && idx < NET.size()) {
                resolveAndPlay(NET.get(idx));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int size = "local".equals(mode) ? shown.size() : NET.size();
        int max = Math.max(0, size - rows());
        scroll = Mth.clamp(scroll - (int) Math.signum(dy), 0, max);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Sub-screen for picking which players receive the sync messages. */
    static final class PlayerPickerScreen extends Screen {

        private final Screen parent;

        PlayerPickerScreen(Screen parent) {
            super(Component.literal("选择共享的玩家"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            List<String> online = new ArrayList<>();
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() != null) {
                for (var info : mc.getConnection().getOnlinePlayers()) {
                    // GameProfile is a record in authlib 10, so the accessor is name()
                    online.add(info.getProfile().name());
                }
            }
            int btnH = 14;
            int y = 42;
            for (String name : online) {
                boolean on = MusicMod.SYNC.targets().contains(name);
                addRenderableWidget(Button.builder(
                        Component.literal((on ? "✔ " : "  ") + name), b -> {
                            MusicMod.SYNC.toggleTarget(name);
                            b.setMessage(Component.literal(
                                    (MusicMod.SYNC.targets().contains(name) ? "✔ " : "  ") + name));
                        }).bounds(width / 2 - 90, y, 180, btnH).build());
                y += btnH + 3;
                if (y > height - 46) {
                    break;
                }
            }
            addRenderableWidget(Button.builder(Component.literal("全选"), b -> {
                for (String s : online) {
                    MusicMod.SYNC.addTarget(s);
                }
                rebuildWidgets();
            }).bounds(width / 2 - 90, height - 38, 86, btnH).build());
            addRenderableWidget(Button.builder(Component.literal("清空"), b -> {
                MusicMod.SYNC.clearTargets();
                rebuildWidgets();
            }).bounds(width / 2 + 4, height - 38, 86, btnH).build());
            addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                    .bounds(width / 2 - 45, height - 20, 90, btnH).build());
        }

        @Override
        public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
            super.extractBackground(g, mouseX, mouseY, partial);
            g.centeredText(font, "选择共享的玩家", width / 2, 14, 0xFFFFFFFF);
            g.centeredText(font,
                    "已选 " + MusicMod.SYNC.targets().size() + " 人 · 指令 " + MusicMod.SYNC.command(),
                    width / 2, 26, 0xFFA3A3A3);
            var conn = Minecraft.getInstance().getConnection();
            if (conn == null || conn.getOnlinePlayers().isEmpty()) {
                g.centeredText(font, "当前没有其他在线玩家", width / 2, 48, 0xFFA3A3A3);
            }
        }

        @Override
        public void onClose() {
            Minecraft.getInstance().setScreenAndShow(parent);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
