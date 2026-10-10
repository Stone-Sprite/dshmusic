package dev.dsh.music;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A user-editable playlist, kept as a plain text file beside the downloads.
 *
 * <p>A text file rather than an in-game editor: entries are often pasted in from a
 * browser or a file manager, a name may be anything at all, and a file can be edited
 * while the game is running and reloaded without a restart. The format is one entry per
 * line:
 *
 * <pre>
 * # 注释行以 # 开头
 * 夜色          | D:\音乐\某首歌.mp3
 * 特别的人      | https://www.bilibili.com/video/BV1xngHzyEAK
 * Max Verstappen | BV1tnHz6mErc
 * </pre>
 *
 * <p>The name is optional — with no {@code |} the whole line is the source and the name
 * is derived. The source may be a local file, a bilibili URL, or a bare {@code BV…} id;
 * anything that looks like the latter two is played through the provider path.
 */
public final class Playlist {

    /** One line of the playlist. */
    public static final class Entry {
        /** What the list and the HUD show. Never blank. */
        public final String name;
        /** A local path, a bilibili URL, or a bare BV id. */
        public final String source;
        /** True when {@link #source} has to be resolved and streamed. */
        public final boolean network;

        Entry(String name, String source, boolean network) {
            this.name = name;
            this.source = source;
            this.network = network;
        }

        /** Stable id, so "what is playing" lookups work for playlist entries too. */
        public String fingerprint() {
            if (network) {
                return "net:" + bvid();
            }
            try {
                return MusicLibrary.sha1(Path.of(source));
            } catch (Exception e) {
                // a path the platform cannot represent; keep it distinct and stable
                return "bad:" + Integer.toHexString(source.hashCode());
            }
        }

        /** The BV id inside the source, or the source itself when it is already one. */
        public String bvid() {
            Matcher m = BV.matcher(source);
            return m.find() ? m.group(1) : source.trim();
        }
    }

    private static final Pattern BV = Pattern.compile("(BV[0-9A-Za-z]{10})");
    /** A local path that exists is preferred over a provider match for the same text. */
    private static final Pattern LOOKS_NETWORK = Pattern.compile(
            "(?i)(BV[0-9A-Za-z]{10}|bilibili\\.com|b23\\.tv|^https?://)");

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private volatile Path file;
    private volatile long loadedAt;

    public List<Entry> entries() {
        return entries;
    }

    public Path file() {
        return file;
    }

    /** Where the list lives when nothing overrides it: beside the downloads. */
    public static Path defaultFile() {
        return Mp3Player.libraryDir().resolve("playlist.txt");
    }

    /**
     * Read the list, creating an empty file with a commented header when absent.
     *
     * <p>Re-read on every call; it is small and this is what makes edits show up without
     * a restart.
     */
    public void load(Path f) {
        this.file = f;
        List<Entry> parsed = new ArrayList<>();
        try {
            if (!Files.isRegularFile(f)) {
                Files.createDirectories(f.getParent());
                Files.writeString(f, HEADER, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            }
            for (String raw : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                Entry e = parse(raw);
                if (e != null) {
                    parsed.add(e);
                }
            }
            loadedAt = System.currentTimeMillis();
        } catch (Exception e) {
            System.err.println("[dshmusic] playlist load failed: " + e);
        }
        entries.clear();
        entries.addAll(parsed);
    }

    /** Re-read only when the file changed on disk; cheap enough to call from a tick. */
    public void reloadIfChanged() {
        Path f = file;
        if (f == null) {
            return;
        }
        try {
            if (!Files.isRegularFile(f) || Files.getLastModifiedTime(f).toMillis() > loadedAt) {
                load(f);
            }
        } catch (Exception ignored) {
            // a transient stat failure is not worth a reload
        }
    }

    /** @return the parsed entry, or null for a blank line or a comment. */
    static Entry parse(String raw) {
        if (raw == null) {
            return null;
        }
        String line = raw.strip();
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
            return null;
        }
        String name = "";
        String source = line;
        // Split on the LAST separator so a name may itself contain "|", which is far
        // more likely than a file path containing one.
        int bar = line.lastIndexOf('|');
        if (bar >= 0) {
            name = line.substring(0, bar).strip();
            source = line.substring(bar + 1).strip();
        }
        if (source.isEmpty()) {
            return null;
        }
        // A path that exists wins: a local file may legitimately be called "BV…".
        // Path.of throws on anything that is not a legal path — a URL, a stray colon —
        // so the check has to tolerate that rather than take the whole list down with it.
        boolean local = false;
        try {
            local = Files.isRegularFile(Path.of(source));
        } catch (Exception ignored) {
            // not a usable path; fall through to the network test
        }
        boolean network = !local && LOOKS_NETWORK.matcher(source).find();
        if (name.isEmpty()) {
            name = network ? source : Path.of(source).getFileName().toString();
        }
        return new Entry(name, source, network);
    }

    /** Append an entry and write it through, so the file and the screen agree. */
    public boolean add(String name, String source) {
        Path f = file;
        if (f == null || source == null || source.isBlank()) {
            return false;
        }
        String cleanName = name == null ? "" : name.replace("|", "/").replace("\n", " ").strip();
        String cleanSrc = source.replace("\n", " ").strip();
        try {
            Files.writeString(f, cleanName + " | " + cleanSrc + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("[dshmusic] playlist append failed: " + e);
            return false;
        }
        load(f);
        return true;
    }

    /** True when the playlist already contains this source. */
    public boolean contains(String source) {
        if (source == null) {
            return false;
        }
        String s = source.strip().toLowerCase(Locale.ROOT);
        for (Entry e : entries) {
            if (e.source.strip().toLowerCase(Locale.ROOT).equals(s)) {
                return true;
            }
        }
        return false;
    }

    /** The entry whose fingerprint matches, or null. */
    public Entry byFingerprint(String fp) {
        if (fp == null || fp.isBlank()) {
            return null;
        }
        for (Entry e : entries) {
            if (e.fingerprint().equalsIgnoreCase(fp)) {
                return e;
            }
        }
        return null;
    }

    private static final String HEADER = """
            # dshmusic 自定播放列表
            #
            # 每行一首，格式：名字 | 来源
            #   名字   显示用的名字，可以留空（留空则用文件名或 BV 号）
            #   来源   本地文件路径，或者 B站 链接 / BV 号
            #
            # 例子：
            #   夜色                | D:\\音乐\\某首歌.mp3
            #   特别的人            | https://www.bilibili.com/video/BV1xngHzyEAK
            #   Max Verstappen      | BV1tnHz6mErc
            #
            # 以 # 开头的行会被忽略。改完存盘，游戏里点「重载列表」即可生效。

            """;
}
