package dev.dsh.music;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;

/**
 * Music player driven from a stick in hand.
 *
 * <p>Fabric entrypoint. Holds the one library index and the one audio player for the
 * whole client. The library scan and the audio decode both run off-thread; the client
 * tick only reads their state, so neither the scan nor playback can stutter a frame.
 *
 * <p>Ported from the NeoForge build. The audio half of this mod is plain Java and was
 * carried over unchanged; what differs here is the loader: a {@code ClientModInitializer}
 * instead of {@code @Mod}, {@code HudElementRegistry} instead of
 * {@code RegisterGuiLayersEvent}, and Fabric's callback events instead of the NeoForge
 * event bus.
 */
public final class MusicMod implements ClientModInitializer {

    public static final String MODID = "dshmusic";

    public static final MusicLibrary LIBRARY = new MusicLibrary();
    public static final Mp3Player PLAYER = new Mp3Player();
    public static final SyncChannel SYNC = new SyncChannel();

    /** Music folder; override with -Ddshmusic.root=... */
    private static final Path ROOT = Path.of(
            System.getProperty("dshmusic.root", "D:\\音乐"));

    private static volatile boolean scanning;

    @Override
    public void onInitializeClient() {
        // addLast puts the panel at the end of the root HUD layer. attachElementAfter
        // was tried first and the element was never invoked at all — no error, it just
        // never appeared — so this is the form the registry actually drives.
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath(MODID, "music_hud"), new MusicHud());

        ClientTickEvents.END_CLIENT_TICK.register(MusicClient::onEndTick);

        // private messages arrive as ordinary chat lines, so sync payloads are picked
        // up here; the extra arguments are not needed
        ClientReceiveMessageEvents.CHAT.register(
                (message, signed, sender, bound, timestamp) -> MusicClient.onChat(message));

        // a playback failure is also worth a chat line: the HUD status needs a stick in
        // hand and cannot be copied out, a chat line can
        Mp3Player.notice = SyncChannel::note;

        // the player handles ONE-loop itself; ALL needs the library to say what is next
        Mp3Player.onTrackEnd = MusicMod::onTrackEnded;

        rescan();
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
     * shareable — without being moved first. It runs on every launch and can be
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
