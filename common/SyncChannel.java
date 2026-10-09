package dev.dsh.music;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;

/**
 * Keeps playback in step across clients that have this mod.
 *
 * <p>What travels is a short encrypted blob, not audio. A client seals the identity of
 * what it is playing plus its position and a send timestamp; every other client opens
 * the blob and starts the same thing itself.
 *
 * <p>The identity is the fingerprint the player already tracks, which encodes both
 * cases in one string:
 *
 * <ul>
 *   <li>{@code net:BV1...} — a provider video. The receiver resolves and downloads it
 *       independently, so nothing is redistributed and the sender's stream URL (which
 *       is bound to the sender's address and expires) is never passed along.</li>
 *   <li>a 16-character hex digest — a local file. The receiver looks it up in its own
 *       index and plays its own copy.</li>
 * </ul>
 *
 * <p>The transport is the server's private-message command, configurable because it
 * differs per server ({@code /w}, {@code /msg}, {@code /tell}). Using chat means this
 * works on any server with no plugin and no relay, at the cost of one short message per
 * track change rather than per tick.
 */
public final class SyncChannel {

    /** Marker re-exported for callers that filter chat lines before decrypting. */
    public static final String PREFIX = SyncCrypto.MARK;

    private static final String NET_PREFIX = "net:";

    /** Players to keep in sync; empty means nobody. */
    private final Set<String> targets = new LinkedHashSet<>();

    private volatile boolean enabled;
    private volatile String command = "/w";

    /** Fingerprint we last announced, so we only send on an actual change. */
    private String lastAnnounced = "";
    private double lastPosition;

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            targets.clear();
            lastAnnounced = "";
        }
    }

    public String command() {
        return command;
    }

    public void setCommand(String cmd) {
        this.command = cmd == null || cmd.isBlank() ? "/w" : cmd.trim();
    }

    public List<String> targets() {
        return new ArrayList<>(targets);
    }

    public void addTarget(String name) {
        if (name != null && !name.isBlank()) {
            targets.add(name.trim());
        }
    }

    public void removeTarget(String name) {
        targets.remove(name);
    }

    public void clearTargets() {
        targets.clear();
    }

    public void toggleTarget(String name) {
        if (targets.contains(name)) {
            targets.remove(name);
        } else {
            addTarget(name);
        }
    }

    /** Force an announcement on the next tick even if the track did not change. */
    public void announceNow() {
        lastAnnounced = "";
    }

    /**
     * Announce the current track to every selected player when it changes or when a
     * seek moves playback far enough to matter.
     */
    public void tick() {
        if (!enabled || targets.isEmpty() || !MusicMod.PLAYER.isActive()) {
            return;
        }
        String fp = MusicMod.PLAYER.fingerprint();
        if (fp == null || fp.isEmpty()) {
            return;
        }
        double pos = MusicMod.PLAYER.position();
        boolean trackChanged = !fp.equals(lastAnnounced);
        boolean jumped = Math.abs(pos - lastPosition) > 1.5;
        if (!trackChanged && !jumped) {
            lastPosition = pos;
            return;
        }
        lastAnnounced = fp;
        lastPosition = pos;
        send(fp, pos);
    }

    private void send(String fingerprint, double position) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            return;
        }
        long ts = System.currentTimeMillis() / 1000L;
        long pos = Math.round(position * 10);
        String json;
        if (fingerprint.startsWith(NET_PREFIX)) {
            // A provider track: the id is all the receiver needs, it resolves the rest.
            // Kept minimal because the whole command has to survive the chat limit.
            json = "{\"v\":1,\"i\":\"" + fingerprint + "\",\"s\":" + pos
                    + ",\"t\":" + ts + "}";
        } else {
            // A local file the receiver may not own. The audio cannot travel through
            // chat and a disk file has no URL to hand over, so along with the digest
            // this carries a title to search with and a length to match against — that
            // is what lets the receiver find a stand-in on the provider instead of
            // just failing.
            MusicLibrary.Track t = MusicMod.LIBRARY.byFingerprint(fingerprint);
            String title = t == null ? "" : escape(truncate(t.title, 20));
            long dur = t == null ? 0 : Math.round(t.seconds);
            json = "{\"v\":1,\"i\":\"" + fingerprint + "\",\"n\":\"" + title
                    + "\",\"d\":" + dur + ",\"s\":" + pos + ",\"t\":" + ts + "}";
        }
        String token = SyncCrypto.seal(json);
        if (token == null) {
            return;
        }
        // ClientPacketListener.sendCommand() prepends the slash itself, so passing the
        // configured "/w" through unchanged sends "//w" and the server rejects it.
        String bare = command.startsWith("/") ? command.substring(1) : command;
        for (String who : targets) {
            // sent as a command so the server does the routing; nothing is broadcast
            mc.getConnection().sendCommand(bare + " " + who + " " + token);
        }
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Sanitise a title for the payload.
     *
     * <p>Quotes and backslashes are removed rather than escaped. The payload is built
     * and read only by this class, and it is sealed with an authenticated cipher, so no
     * third party can inject a field; stripping is enough to guarantee the quote we
     * emit is always the closing one, and it cannot be defeated by a title that tries
     * to look like JSON.
     */
    static String escape(String s) {
        return s.replace("\\", "").replace("\"", "").replace("\n", " ").replace("\r", " ");
    }

    /**
     * Handle a chat line. Returns a short description when the line was a sync message
     * this client consumed, or null when it was ordinary chat.
     *
     * @param raw the message text, already stripped of formatting
     * @param from the sender's name as it appears on the line, or null when unknown
     */
    public String handleIncoming(String raw, String from) {
        if (raw == null || !raw.contains(SyncCrypto.MARK)) {
            return null;
        }
        String json = SyncCrypto.open(raw);
        if (json == null) {
            return null;   // no marker, wrong key, or a damaged line
        }
        String id = field(json, "i");
        String ps = field(json, "s");
        String ts = field(json, "t");
        if (id == null || ps == null) {
            return null;
        }
        String title = field(json, "n");
        double localDur = parseInt(field(json, "d"), 0);

        double position = parseInt(ps, 0) / 10.0;
        // compensate for the message's transit time so we do not start behind
        if (ts != null) {
            long sentAt = parseInt(ts, 0);
            long now = System.currentTimeMillis() / 1000L;
            position += Math.max(0, Math.min(30, now - sentAt));
        }
        // the sender must not echo the announcement straight back
        lastAnnounced = id;

        if (id.startsWith(NET_PREFIX)) {
            return fetchAndPlay(id.substring(NET_PREFIX.length()), position, from);
        }
        return playLocal(id, title, localDur, position, from);
    }

    /**
     * Local file: play our own copy, or find a stand-in when we do not have it.
     *
     * <p>This is the case where the two peers genuinely differ. Sharing a file needs
     * both to own it; if the receiver does not, the audio cannot be pushed through
     * chat and there is no URL to fetch, so the title carried in the payload is used to
     * search the provider for the same song and play that instead.
     */
    private String playLocal(String fingerprint, String title, double duration,
                             double position, String from) {
        MusicLibrary.Track track = MusicMod.LIBRARY.byFingerprint(fingerprint);
        if (track != null) {
            if (position >= track.seconds && track.seconds > 0) {
                position = 0;   // they finished it; start ours over rather than at the end
            }
            MusicMod.PLAYER.stop();
            MusicMod.PLAYER.load(track.path, track.fingerprint, track.seconds);
            MusicMod.PLAYER.seek(position);
            MusicMod.PLAYER.play();
            lastPosition = position;
            return "同步播放：" + track.display();
        }

        if (title == null || title.isBlank()) {
            return "缺少文件：" + (from == null ? "对方" : from) + " 的曲目（指纹 "
                    + fingerprint + "）";
        }
        return substitute(title, duration, position, from);
    }

    /**
     * Search the provider for the track the other side is playing and play the closest
     * match, so a shared local file still results in the same song when the receiver
     * does not own it.
     */
    private String substitute(String title, double duration, double position, String from) {
        final double pos = position;
        final double want = duration;
        Thread t = new Thread(() -> {
            try {
                List<ProviderSearch.Result> hits = ProviderSearch.bilibili(title);
                if (hits.isEmpty()) {
                    note("没有该文件，B站 上也搜不到「" + title + "」");
                    return;
                }
                ProviderSearch.Result best = pickClosest(hits, want);
                long cid = ProviderSearch.cidOf(best.bvid);
                if (cid == 0) {
                    note("替代版本取不到 cid：" + best.bvid);
                    return;
                }
                String url = ProviderSearch.audioUrl(best.bvid, cid);
                if (url == null) {
                    note("替代版本没有公开音频流：" + best.title);
                    return;
                }
                Minecraft.getInstance().execute(() -> {
                    MusicMod.PLAYER.loadUrl(url, "https://www.bilibili.com/",
                            best.title, NET_PREFIX + best.bvid, seconds(best.duration));
                    MusicMod.PLAYER.seek(pos);
                    MusicMod.PLAYER.play();
                    lastPosition = pos;
                });
                note("没有该文件，改放 B站 上找到的：「" + best.title + "」");
            } catch (Throwable e) {
                note("找替代版本失败： " + e.getClass().getSimpleName());
            }
        }, "dshmusic-substitute");
        t.setDaemon(true);
        t.start();
        return "没有该文件，正在找替代版本：「" + title + "」…";
    }

    /** Prefer the hit whose length is nearest, so a 3-minute song does not match a 30-minute one. */
    private static ProviderSearch.Result pickClosest(List<ProviderSearch.Result> hits,
                                                     double want) {
        if (want <= 0) {
            return hits.get(0);
        }
        ProviderSearch.Result best = hits.get(0);
        double bestDiff = Double.MAX_VALUE;
        for (ProviderSearch.Result r : hits) {
            double diff = Math.abs(seconds(r.duration) - want);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = r;
            }
        }
        return best;
    }

    /** "12:34" / "1:02:03" -> seconds. */
    static double seconds(String d) {
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

    /**
     * Provider track: resolve and download it here.
     *
     * <p>Runs on a worker because it is network work; the returned text is shown
     * immediately while the download proceeds.
     */
    private String fetchAndPlay(String bvid, double position, String from) {
        final double pos = position;
        Thread t = new Thread(() -> {
            try {
                long cid = ProviderSearch.cidOf(bvid);
                if (cid == 0) {
                    note("取不到 cid：" + bvid);
                    return;
                }
                String url = ProviderSearch.audioUrl(bvid, cid);
                if (url == null) {
                    note("该视频没有公开音频流：" + bvid);
                    return;
                }
                String title = ProviderSearch.titleOf(bvid);
                double dur = ProviderSearch.durationOf(bvid);
                Minecraft.getInstance().execute(() -> {
                    MusicMod.PLAYER.loadUrl(url, "https://www.bilibili.com/",
                            title == null ? bvid : title, NET_PREFIX + bvid,
                            dur > 0 ? dur : 0);
                    MusicMod.PLAYER.seek(pos);
                    MusicMod.PLAYER.play();
                    lastPosition = pos;
                });
                note("正在下载同步曲目：" + (title == null ? bvid : title));
            } catch (Throwable e) {
                note("同步失败： " + e.getClass().getSimpleName());
            }
        }, "dshmusic-sync-fetch");
        t.setDaemon(true);
        t.start();
        return "正在下载 " + (from == null ? "对方" : from) + " 的曲目…";
    }

    /**
     * Show a line to the local player.
     *
     * <p>26.3 renamed {@code displayClientMessage(Component, boolean)} to
     * {@code sendSystemMessage(Component)}, and this file is shared verbatim by the
     * NeoForge and Fabric builds, so the call is made reflectively and the first form
     * the running version has is used.
     */
    public static void note(String msg) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.player.LocalPlayer p = mc.player;
        if (p == null) {
            return;
        }
        Object c = net.minecraft.network.chat.Component.literal("§b[音乐] §f" + msg);
        try {
            p.getClass().getMethod("sendSystemMessage",
                    net.minecraft.network.chat.Component.class).invoke(p, c);
            return;
        } catch (Throwable ignored) {
            // older signature below
        }
        try {
            p.getClass().getMethod("displayClientMessage",
                    net.minecraft.network.chat.Component.class, boolean.class)
                    .invoke(p, c, false);
        } catch (Throwable ignored) {
            // neither form available; the message is simply not shown
        }
    }

    static String field(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int i = json.indexOf(needle);
        if (i >= 0) {
            int start = i + needle.length();
            int stop = json.indexOf('"', start);
            return stop < 0 ? null : json.substring(start, stop);
        }
        needle = "\"" + key + "\":";
        i = json.indexOf(needle);
        if (i < 0) {
            return null;
        }
        int start = i + needle.length();
        int stop = start;
        while (stop < json.length() && (Character.isDigit(json.charAt(stop))
                || json.charAt(stop) == '-')) {
            stop++;
        }
        return stop == start ? null : json.substring(start, stop);
    }

    private static long parseInt(String s, long fallback) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
