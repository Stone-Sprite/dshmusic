package dev.dsh.music;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * Index of the local music folder.
 *
 * <p>Each track carries a content fingerprint so the sync channel can identify a
 * song without transferring any audio: clients exchange the fingerprint, and each
 * one plays its own local copy of the matching file.
 */
public final class MusicLibrary {

    /** One indexed audio file. */
    public static final class Track {
        public final Path path;
        public final String title;
        public final String artist;
        public final String album;
        public final double seconds;
        /** SHA-1 of the file contents, first 16 hex characters. */
        public final String fingerprint;

        Track(Path path, String title, String artist, String album, double seconds,
              String fingerprint) {
            this.path = path;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.seconds = seconds;
            this.fingerprint = fingerprint;
        }

        /** "Artist — Title", or just the title when no artist is known. */
        public String display() {
            if (artist == null || artist.isEmpty()) {
                return title;
            }
            return artist + " — " + title;
        }

        public String duration() {
            int total = (int) Math.round(seconds);
            return String.format("%d:%02d", total / 60, total % 60);
        }

        /** Case-insensitive match against title, artist, album and file name. */
        public boolean matches(String query) {
            String q = query.toLowerCase(Locale.ROOT);
            return title.toLowerCase(Locale.ROOT).contains(q)
                    || (artist != null && artist.toLowerCase(Locale.ROOT).contains(q))
                    || (album != null && album.toLowerCase(Locale.ROOT).contains(q))
                    || path.getFileName().toString().toLowerCase(Locale.ROOT).contains(q);
        }

        @Override
        public String toString() {
            return display() + "  [" + duration() + "]  " + fingerprint;
        }
    }

    /**
     * Only formats this mod can actually play.
     *
     * <p>Ogg and M4A are deliberately absent. Java Sound cannot decode either without
     * another codec, and a whole-drive scan otherwise fills the list with Minecraft
     * mod sound effects and voice memos that would fail the moment they were chosen.
     * Provider downloads land here as WAV, so they are still picked up.
     */
    private static final List<String> EXTENSIONS = List.of(".mp3", ".wav", ".flac");

    private final List<Track> tracks = new ArrayList<>();

    public List<Track> tracks() {
        return tracks;
    }

    public Track byFingerprint(String fingerprint) {
        for (Track t : tracks) {
            if (t.fingerprint.equalsIgnoreCase(fingerprint)) {
                return t;
            }
        }
        return null;
    }

    public List<Track> search(String query) {
        List<Track> hits = new ArrayList<>();
        for (Track t : tracks) {
            if (t.matches(query)) {
                hits.add(t);
            }
        }
        return hits;
    }

    /** Walk {@code root} recursively and index every supported audio file. */
    public int scan(Path root) {
        return scanRoots(List.of(root));
    }

    /**
     * Directories that are never worth walking.
     *
     * <p>Scanning whole drives is what makes a freshly downloaded file show up without
     * any manual step, but walking Windows and the program directories would spend
     * minutes for no music and bury the index in system sounds.
     */
    private static final Set<String> SKIP_DIRS = Set.of(
            "windows", "program files", "program files (x86)", "programdata",
            "$recycle.bin", "system volume information", "recovery", "$windows.~bt",
            "$windows.~ws", "perflogs", "msocache", "config.msi", "intel",
            "node_modules", ".git", ".gradle", ".cache", "cache",
            "temp", "tmp", "appdata");

    /** Files smaller than this are system sounds, not music. */
    private static final long MIN_AUDIO_BYTES = 128 * 1024;
    /**
     * Anything shorter than this is a sample, a loop or a sound effect.
     *
     * <p>A whole-drive scan otherwise picks up every drum hit and orchestral stab in
     * an FL Studio sample pack — hundreds of entries measured in tenths of a second —
     * and buries the actual music. Music is minutes long, so a length floor separates
     * the two far more reliably than a byte count does.
     */
    private static final double MIN_SECONDS = 20.0;
    /** Upper bound so a runaway scan cannot exhaust memory. */
    private static final int MAX_TRACKS = 6000;

    /** Set while a scan is running, for the UI. */
    private volatile int scanned;
    private volatile String scanningRoot = "";

    public int scannedCount() {
        return scanned;
    }

    public String scanningRoot() {
        return scanningRoot;
    }

    /**
     * Walk every root and rebuild the index.
     *
     * <p>Uses {@link Files#walkFileTree} rather than {@code Files.walk}: a whole-drive
     * scan always meets directories it cannot read ({@code C:\Documents and Settings},
     * other users' profiles), and {@code Files.walk} throws an unchecked
     * {@code UncheckedIOException} out of the middle of the iteration for those. A
     * visitor's {@code visitFileFailed} turns that into "skip this one and carry on",
     * which is the behaviour a scan of the entire machine needs.
     *
     * <p>Drives are scanned rather than one music folder so that anything downloaded
     * since the last scan — including this mod's own provider downloads — becomes
     * playable and shareable without the user moving it first.
     */
    public int scanRoots(List<Path> roots) {
        tracks.clear();
        scanned = 0;
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            scanningRoot = root.toString();
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (!dir.equals(root) && skipDir(dir)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (tracks.size() >= MAX_TRACKS) {
                            return FileVisitResult.TERMINATE;
                        }
                        scanningRoot = dir.toString();
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (tracks.size() >= MAX_TRACKS) {
                            return FileVisitResult.TERMINATE;
                        }
                        if (!attrs.isRegularFile() || !isAudio(file)
                                || attrs.size() < MIN_AUDIO_BYTES) {
                            return FileVisitResult.CONTINUE;
                        }
                        Track t = index(file);
                        // Every accepted format has a readable length either from its
                        // header or from the bundled MP3 SPI, so an unknown one means
                        // something is wrong with the file rather than that it is long.
                        if (t != null && t.seconds >= MIN_SECONDS) {
                            tracks.add(t);
                            scanned++;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        // unreadable directory or file: skip it, keep scanning
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                // a drive that fails outright still leaves the rest of the index usable
            } catch (RuntimeException e) {
                // a security manager or a vanished drive mid-walk
            }
        }
        scanningRoot = "";
        tracks.sort(Comparator.comparing(t -> t.path.toString()));
        return tracks.size();
    }

    private static boolean skipDir(Path p) {
        Path name = p.getFileName();
        if (name == null) {
            return false;
        }
        String n = name.toString().toLowerCase(Locale.ROOT);
        return SKIP_DIRS.contains(n) || n.startsWith("$");
    }

    /** Every fixed drive root that exists, for a whole-machine scan. */
    public static List<Path> fixedDriveRoots() {
        List<Path> out = new ArrayList<>();
        // an explicit override wins, so a user can pin the scan to one folder
        String prop = System.getProperty("dshmusic.roots");
        if (prop != null && !prop.isBlank()) {
            for (String s : prop.split(java.io.File.pathSeparator)) {
                if (!s.isBlank()) {
                    out.add(Path.of(s.trim()));
                }
            }
            return out;
        }
        for (java.io.File f : java.io.File.listRoots()) {
            try {
                Path root = f.toPath();
                if (Files.isDirectory(root)) {
                    out.add(root);
                }
            } catch (Exception ignored) {
                // a drive that vanished mid-enumeration
            }
        }
        return out;
    }

    private static boolean isAudio(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) {
            if (n.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    public static Track index(Path file) {
        Id3Reader.Tags tags = Id3Reader.read(file);

        // The folder names files inconsistently, so tags win; the file name is only a
        // fallback. "Artist - Title" and "Title - Artist" both occur, so a bare
        // filename cannot be split reliably and is kept whole.
        String title = clean(tags.title);
        String artist = clean(tags.artist);
        if (title == null) {
            String base = file.getFileName().toString();
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                base = base.substring(0, dot);
            }
            title = base.trim();
        }

        return new Track(file, title, artist, clean(tags.album), duration(file), sha1(file));
    }

    private static String clean(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Duration in seconds, or 0 when it cannot be determined.
     *
     * <p>The container header is read first for WAV and FLAC. Java Sound refuses or
     * mis-reports some of those (24-bit WAV, unusual FLAC blocks), and the scan uses
     * the length to tell music from single-hit samples, so a wrong zero would put
     * hundreds of drum one-shots back into the library.
     */
    public static double duration(Path file) {
        double d = headerDuration(file);
        if (d > 0) {
            return d;
        }
        try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat fmt = in.getFormat();
            long frames = in.getFrameLength();
            if (frames > 0 && fmt.getFrameRate() > 0) {
                return frames / fmt.getFrameRate();
            }
            AudioFileFormat aff = AudioSystem.getAudioFileFormat(file.toFile());
            Object prop = aff.properties().get("duration");
            if (prop instanceof Long micros) {
                return micros / 1_000_000.0;
            }
        } catch (Exception e) {
            // unsupported by the SPI: fall back to an unknown length
        }
        return 0.0;
    }

    /** RIFF/WAVE and native FLAC headers carry everything needed, in a few bytes. */
    private static double headerDuration(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] b = in.readNBytes(4096);
            if (b.length >= 44 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') {
                return wavDuration(b);
            }
            if (b.length >= 42 && b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C') {
                return flacDuration(b);
            }
        } catch (Exception e) {
            // not a container we can read cheaply
        }
        return 0.0;
    }

    private static double wavDuration(byte[] b) {
        int byteRate = 0;
        long dataSize = 0;
        int p = 12;
        while (p + 8 <= b.length) {
            int size = le32(b, p + 4);
            if (size < 0) {
                break;
            }
            boolean fmt = b[p] == 'f' && b[p + 1] == 'm' && b[p + 2] == 't';
            boolean dat = b[p] == 'd' && b[p + 1] == 'a' && b[p + 2] == 't' && b[p + 3] == 'a';
            if (fmt && p + 8 + 12 <= b.length) {
                byteRate = le32(b, p + 8 + 8);
            } else if (dat) {
                dataSize = size;
            }
            p += 8 + size + (size & 1);
        }
        if (byteRate <= 0 || dataSize <= 0) {
            return 0;
        }
        return dataSize / (double) byteRate;
    }

    private static double flacDuration(byte[] b) {
        // STREAMINFO payload starts at 8; sample rate and total samples share bytes 18-25
        int sr = ((b[18] & 0xFF) << 12) | ((b[19] & 0xFF) << 4) | ((b[20] & 0xFF) >> 4);
        long total = ((long) (b[21] & 0x0F) << 32) | ((long) (b[22] & 0xFF) << 24)
                | ((long) (b[23] & 0xFF) << 16) | ((long) (b[24] & 0xFF) << 8)
                | (b[25] & 0xFF);
        if (sr <= 0 || total <= 0) {
            return 0;
        }
        return total / (double) sr;
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    /** SHA-1 of the whole file, truncated to 16 hex characters. */
    public static String sha1(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "0000000000000000";
        }
    }
}
