package dev.dsh.music;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Music player driven from a stick in hand.
 *
 * <p>Holds the one library index and the one audio player for the whole client. The
 * library scan and the audio decode both run off-thread; the client tick only reads
 * their state, so neither the scan nor playback can stutter a frame.
 */
@Mod(value = MusicMod.MODID, dist = Dist.CLIENT)
public final class MusicMod {

    public static final String MODID = "dshmusic";

    public static final MusicLibrary LIBRARY = new MusicLibrary();
    public static final Mp3Player PLAYER = new Mp3Player();
    public static final SyncChannel SYNC = new SyncChannel();
    public static final Playlist PLAYLIST = new Playlist();

    /** Music folder; override with -Ddshmusic.root=... */
    private static final Path ROOT = Path.of(
            System.getProperty("dshmusic.root", "D:\\音乐"));

    private static volatile boolean scanning;

    public MusicMod(IEventBus modBus) {
        modBus.addListener(MusicMod::onRegisterLayers);
        // game-bus hooks: right-click to open, chat to sync, tick to keep in step
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(MusicClient.class);
        Mp3Player.notice = SyncChannel::note;
        Mp3Player.onTrackEnd = MusicMod::onTrackEnded;
        PLAYLIST.load(Playlist.defaultFile());
        rescan();
    }

    private static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.EXPERIENCE_LEVEL,
                Identifier.fromNamespaceAndPath(MODID, "music_hud"),
                new MusicHud());
    }

    public Path root() {
        return ROOT;
    }

    public static Path musicRoot() {
        return ROOT;
    }

    public static boolean isScanning() {
        return scanning;
    }

    /**
     * Re-index the drives on a background thread.
     *
     * <p>The scan covers whole drives rather than one music folder, so a file
     * downloaded by the provider path becomes part of the library — and therefore
     * shareable — without being moved first. It runs on every world join and can be
     * triggered from the screen.
     */
    public static void rescan() {
        if (scanning) {
            return;
        }
        scanning = true;
        Thread t = new Thread(() -> {
            try {
                List<Path> roots = new ArrayList<>();
                Path pinned = Path.of(System.getProperty("dshmusic.root", "D:\\音乐"));
                if (Files.isDirectory(pinned)) {
                    roots.add(pinned);
                }
                for (Path r : MusicLibrary.fixedDriveRoots()) {
                    if (!roots.contains(r)) {
                        roots.add(r);
                    }
                }
                LIBRARY.scanRoots(roots);
            } finally {
                scanning = false;
            }
        }, "dshmusic-scan");
        t.setDaemon(true);
        t.start();
    }

    /** Play a track from the start, replacing whatever is playing. */
    public static void play(MusicLibrary.Track track) {
        PLAYER.stop();
        PLAYER.load(track.path, track.fingerprint, track.seconds);
        PLAYER.play();
    }

    /** Play one entry of the custom playlist, local or streamed. */
    public static void playlistPlay(Playlist.Entry e) {
        if (e.network) {
            playNetwork(e.bvid(), e.name);
            return;
        }
        Path p = Path.of(e.source);
        if (!Files.isRegularFile(p)) {
            Mp3Player.say("列表里的文件不存在：" + e.source);
            return;
        }
        PLAYER.stop();
        PLAYER.load(p, MusicLibrary.sha1(p), MusicLibrary.duration(p));
        PLAYER.play();
    }

    /**
     * Append whatever is playing to the custom playlist.
     *
     * <p>The name is the track's own title rather than a file name, so the list stays
     * readable; the user can rewrite it in the file at any time.
     *
     * @return true when a line was written.
     */
    public static boolean addCurrentToPlaylist() {
        String fp = PLAYER.fingerprint();
        if (fp == null || fp.isBlank()) {
            return false;
        }
        if (PLAYER.isNetwork()) {
            String bvid = fp.startsWith("net:") ? fp.substring(4) : fp;
            return PLAYLIST.add(PLAYER.displayName(),
                    "https://www.bilibili.com/video/" + bvid);
        }
        Path f = PLAYER.file();
        if (f == null) {
            return false;
        }
        MusicLibrary.Track t = LIBRARY.byFingerprint(fp);
        String name = t == null ? f.getFileName().toString()
                : (t.artist == null || t.artist.isBlank() ? t.title : t.artist + " - " + t.title);
        return PLAYLIST.add(name, f.toAbsolutePath().toString());
    }

    /**
     * Play the next track in the index, wrapping at the end.
     *
     * @param step +1 for next, -1 for previous.
     */
    public static void skip(int step) {
        List<MusicLibrary.Track> all = LIBRARY.tracks();
        if (all.isEmpty()) {
            return;
        }
        String current = PLAYER.fingerprint();
        int at = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).fingerprint.equalsIgnoreCase(current)) {
                at = i;
                break;
            }
        }
        int next = at < 0 ? 0 : Math.floorMod(at + step, all.size());
        play(all.get(next));
    }

    /** Called from the client tick; advances sync housekeeping. */
    public static void tick() {
        SYNC.tick();
    }

    /**
     * Resolve a bilibili id and start streaming it.
     *
     * <p>Shared by the search list and the custom playlist so both go through exactly
     * one resolve path.
     *
     * <p>Off-thread: the cid lookup, the stream resolution and the download are all
     * network work. The stream URL is bound to the requesting address and expires, so
     * it is resolved when asked for and is never cached or handed to another client.
     *
     * @param bvid  the video to play
     * @param label what to show while it loads and once it plays
     */
    public static void playNetwork(String bvid, String label) {
        String shown = label == null || label.isBlank() ? bvid : label;
        Thread t = new Thread(() -> {
            try {
                long cid = ProviderSearch.cidOf(bvid);
                if (cid == 0) {
                    Mp3Player.say("取不到 cid：" + bvid);
                    return;
                }
                String url = ProviderSearch.audioUrl(bvid, cid);
                if (url == null) {
                    Mp3Player.say("该视频没有公开音频流（可能需要会员）：" + shown);
                    return;
                }
                double seconds = ProviderSearch.durationOf(bvid);
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    PLAYER.loadUrl(url, "https://www.bilibili.com/", shown, "net:" + bvid, seconds);
                    PLAYER.play();
                });
            } catch (Throwable e) {
                Mp3Player.say("解析失败：" + e.getClass().getSimpleName() + "  " + shown);
            }
        }, "dshmusic-resolve");
        t.setDaemon(true);
        t.start();
    }

    /**
     * A track reached its end on its own.
     *
     * <p>Only {@code ALL} mode advances; {@code OFF} simply stops, and {@code ONE} was
     * already handled by the player replaying in place.
     *
     * <p>Sequencing covers the local library only. A provider track has a {@code net:}
     * fingerprint that is not in the index, so there is no meaningful "next" to step to
     * and it is left stopped rather than jumping to an unrelated local file.
     */
    private static void onTrackEnded() {
        if (PLAYER.loopMode() != Mp3Player.LoopMode.ALL) {
            return;
        }
        List<MusicLibrary.Track> all = LIBRARY.tracks();
        if (all.isEmpty()) {
            return;
        }
        String current = PLAYER.fingerprint();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).fingerprint.equalsIgnoreCase(current)) {
                play(all.get((i + 1) % all.size()));
                return;
            }
        }
    }
}
