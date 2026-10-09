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

    /** Music folder; override with -Ddshmusic.root=... */
    private static final Path ROOT = Path.of(
            System.getProperty("dshmusic.root", "D:\\音乐"));

    private static volatile boolean scanning;

    public MusicMod(IEventBus modBus) {
        modBus.addListener(MusicMod::onRegisterLayers);
        // game-bus hooks: right-click to open, chat to sync, tick to keep in step
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(MusicClient.class);
        Mp3Player.notice = SyncChannel::note;
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
}
