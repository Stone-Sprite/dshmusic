package dev.dsh.music;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;


/**
 * Plays an audio file through Java Sound.
 *
 * <p>Deliberately separate from Minecraft's OpenAL sound engine: the mod owns its own
 * output line, so volume, pause and seeking are independent of the in-game audio
 * settings and music never competes with game sounds for a channel.
 *
 * <p>MP3 support comes from the bundled JLayer/mp3spi service provider, which extends
 * {@link AudioSystem} — so the same code path also plays WAV and anything else the SPI
 * offers, with no format branching here.
 *
 * <p>Decoding runs on its own daemon thread that feeds a {@link SourceDataLine}. All
 * public methods are safe to call from the client thread.
 */
public final class Mp3Player {

    /**
     * Output line buffer.
     *
     * <p>32 KB is only ~0.19 s at 44.1 kHz stereo, which is far too little slack when
     * the decode thread shares the CPU with a running game: any pause longer than that
     * empties the line and is audible as a drop-out. 256 KB is ~1.5 s.
     */
    private static final int BUFFER_BYTES = 1 << 18;
    /** Decoded-but-unwritten PCM chunks held ahead of the line (~3 MB, ~17 s). */

    private final Object lock = new Object();

    private volatile Path file;
    private volatile double duration;
    private volatile String fingerprint = "";

    // ---- network source ----
    /**
     * When set, playback streams from a provider rather than a local library file.
     *
     * <p>Providers hand out AAC in an MP4 container, which neither Java Sound nor the
     * bundled mp3spi can read, so this path decodes with the bundled JCodec decoder.
     * The URL is bound to the requesting address and expires, so the bytes are pulled
     * at play time and cached only in the system temp directory for this session.
     */
    private volatile String url;
    private volatile String referer = "https://www.bilibili.com/";
    private volatile String netStatus = "";
    private volatile Path cache;
    private volatile double netProgress;

    private Thread worker;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile float volume = 0.8f;

    /** Frames written to the line since the current stream started, for position. */
    private volatile long framesPlayed;
    private volatile float frameRate = 44100f;
    /** Frames to skip on the next stream open (a pending seek). */
    private volatile long pendingSeek;

    private volatile SourceDataLine line;

    /** Load a file without starting playback. */
    public void load(Path path, String fingerprint, double seconds) {
        stop();
        this.file = path;
        this.fingerprint = fingerprint == null ? "" : fingerprint;
        this.duration = seconds;
        this.framesPlayed = 0;
        this.pendingSeek = 0;
        this.url = null;
        this.cache = null;
        // Must be cleared together with the download. Leaving the previous conversion
        // in place makes prepareNetworkFile() hand back the last track's WAV, so every
        // provider track after the first plays that same first track.
        this.converted = null;
        this.netStatus = "";
        this.netProgress = 0;
    }

    /**
     * Load a provider stream. {@code label} is what the UI shows; {@code fingerprint}
     * is a synthetic id so the current-track lookups keep working.
     */
    public void loadUrl(String url, String referer, String label, String fingerprint,
                        double seconds) {
        load(null, fingerprint, seconds);
        this.url = url;
        this.referer = referer == null || referer.isBlank() ? "https://www.bilibili.com/" : referer;
        this.file = null;
        this.netLabel = label == null ? "网络音频" : label;
    }

    private volatile String netLabel = "";

    public boolean isNetwork() {
        return url != null;
    }

    public String networkLabel() {
        return netLabel;
    }

    /** Human-readable progress while downloading / decoding a stream. */
    public String netStatus() {
        return netStatus;
    }

    public double netProgress() {
        return netProgress;
    }

    public void play() {
        if (file == null && url == null) {
            return;
        }
        if (paused) {
            paused = false;
            synchronized (lock) {
                lock.notifyAll();
            }
            return;
        }
        if (running) {
            return;
        }
        running = true;
        paused = false;
        worker = new Thread(this::decodeLoop, "dshmusic-player");
        worker.setDaemon(true);
        // audio underruns are audible, so the decode thread outranks ordinary work
        worker.setPriority(Thread.MAX_PRIORITY);
        worker.start();
    }

    public void pause() {
        if (running) {
            paused = true;
        }
    }

    public void togglePause() {
        if (paused) {
            play();
        } else {
            pause();
        }
    }

    public void stop() {
        running = false;
        paused = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        SourceDataLine l = line;
        if (l != null) {
            try {
                l.stop();
                l.flush();
                l.close();
            } catch (Exception ignored) {
                // line already gone
            }
        }
        line = null;
        Thread w = worker;
        if (w != null && w.isAlive() && w != Thread.currentThread()) {
            try {
                w.join(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
    }

    /** Jump to a position; takes effect on the next decode iteration. */
    public void seek(double seconds) {
        long target = (long) Math.max(0, seconds * frameRate);
        framesPlayed = target;
        pendingSeek = target;
        // drop whatever is buffered so the jump is audible immediately
        SourceDataLine l = line;
        if (l != null) {
            try {
                l.flush();
            } catch (Exception ignored) {
                // nothing buffered
            }
        }
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    public void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        applyVolume();
    }

    public float volume() {
        return volume;
    }

    public void adjustVolume(float delta) {
        setVolume(volume + delta);
    }

    public boolean isPlaying() {
        return running && !paused;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isActive() {
        return running;
    }

    public Path file() {
        return file;
    }

    /** What the UI should show for the current track. */
    public String displayName() {
        if (isNetwork()) {
            return netLabel;
        }
        return file == null ? "" : file.getFileName().toString();
    }

    public String fingerprint() {
        return fingerprint;
    }

    public double duration() {
        return duration;
    }

    /** Current playback position in seconds. */
    public double position() {
        return frameRate <= 0 ? 0 : framesPlayed / (double) frameRate;
    }

    /**
     * Frames the line itself reports as played.
     *
     * <p>{@link #position()} counts frames <em>handed</em> to the line and so runs
     * ahead by the buffer; this is what has actually reached the speakers, which is the
     * only way to see a real drop-out rather than a full buffer.
     */
    public long lineFramePosition() {
        SourceDataLine l = line;
        return l == null ? -1 : l.getLongFramePosition();
    }

    /** Free space in the line, in bytes; 0 means the line is full and we are ahead. */
    public int lineAvailable() {
        SourceDataLine l = line;
        return l == null ? -1 : l.available();
    }

    private void applyVolume() {
        SourceDataLine l = line;
        if (l == null || !l.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }
        try {
            FloatControl gain = (FloatControl) l.getControl(FloatControl.Type.MASTER_GAIN);
            // straight amplitude -> dB, with a floor so 0 is真 silent rather than -Inf
            float db = volume <= 0.0001f
                    ? gain.getMinimum()
                    : (float) (20.0 * Math.log10(volume));
            gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
        } catch (Exception ignored) {
            // control not usable on this mixer
        }
    }

    private void decodeLoop() {
        if (isNetwork()) {
            Path playable = prepareNetworkFile();
            if (playable == null) {
                running = false;
                paused = false;
                line = null;
                return;
            }
            // from here the stream is an ordinary local file, so it plays through the
            // same code path as a library track - one implementation, no second decoder
            this.file = playable;
            this.duration = MusicLibrary.duration(playable);
        }
        // A seek needs the stream reopened at the new frame offset, so the whole
        // decode runs in a restartable loop rather than trying to skip in place.
        while (running) {
            boolean restart = decodeOnce();
            if (!restart) {
                if (!running) {
                    break;                       // stop() was called mid-track
                }
                // The stream ran out on its own. running is still true, which is the
                // only thing that distinguishes this from a stop.
                if (loopMode == LoopMode.ONE) {
                    framesPlayed = 0;
                    pendingSeek = 0;
                    restart = true;              // decodeOnce reopens from the top
                } else {
                    break;
                }
            }
        }
        boolean endedNaturally = running;
        running = false;
        paused = false;
        line = null;
        if (endedNaturally && loopMode != LoopMode.ONE) {
            Runnable r = onTrackEnd;
            if (r != null) {
                // off the decode thread: the handler may start another player thread
                Thread t = new Thread(r, "dshmusic-advance");
                t.setDaemon(true);
                t.start();
            }
        }
    }

    private Path prepareNetworkFile() {
        Path src = ensureDownloaded();
        if (src == null) {
            return null;
        }
        // already converted in this load?
        if (converted != null && Files.isRegularFile(converted)) {
            return converted;
        }
        String ff = ffmpeg();
        if (ff == null) {
            netStatus = "找不到 ffmpeg。试过并拒绝：" + FFMPEG_TRIED
                    + "。可用 -Ddshmusic.ffmpeg=<路径> 指定";
            System.err.println("[dshmusic] " + netStatus);
            tell(netStatus);
            return null;
        }
        // The cache path must be ASCII: it is passed to ffmpeg as an argument, and a
        // non-ASCII path arrives at the child process mangled.
        if (!isAscii(src.toAbsolutePath().toString())) {
            netStatus = "缓存目录含非 ASCII 字符，ffmpeg 无法处理：" + src.getParent();
            return null;
        }
        try {
            Path dir = src.getParent();
            Path wav = dir.resolve(stripExt(src.getFileName().toString()) + ".wav");
            if (!Files.isRegularFile(wav) || Files.size(wav) < 8192) {
                netStatus = "解析中…";
                ProcessBuilder pb = new ProcessBuilder(ff,
                        "-hide_banner", "-loglevel", "error", "-y",
                        "-i", src.toAbsolutePath().toString(),
                        "-vn",                   // audio only; the video track is not wanted
                        "-acodec", "pcm_s16le",
                        "-ar", "44100",
                        "-ac", "2",
                        wav.toAbsolutePath().toString());
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String out;
                try (InputStream is = proc.getInputStream()) {
                    out = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                int code = proc.waitFor();
                if (code != 0 || !Files.isRegularFile(wav) || Files.size(wav) < 8192) {
                    // show ffmpeg's own words, not just the exit code — the code alone
                    // cannot distinguish a bad path from a bad stream
                    String detail = out.isBlank() ? "(没有输出)"
                            : String.join(" | ", out.lines().limit(3).toList());
                    netStatus = "解析失败（ffmpeg 退出码 " + code + "）：" + detail;
                    System.err.println("[dshmusic] ffmpeg exit " + code + ": " + out);
                    tell(netStatus + "  [ffmpeg=" + ff + "]");
                    return null;
                }
            }

            // Move the finished file somewhere the library scan can see it. The copy is
            // done by Java, which handles non-ASCII paths fine, so the destination is
            // free to be the user's own folder.
            Path into = libraryDir();
            Path dest = into.resolve(stripExt(src.getFileName().toString()) + ".wav");
            try {
                Files.copy(wav, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                converted = dest;
            } catch (Exception e) {
                // a non-writable library folder is not fatal; play from the cache
                converted = wav;
            }
            netStatus = "解析完成";
            // let the library pick the new file up
            MusicMod.rescan();
            return converted;
        } catch (Throwable e) {
            netStatus = "解析失败：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : (" " + e.getMessage()));
            System.err.println("[dshmusic] ffmpeg failed: " + e);
            tell(netStatus + "  [ffmpeg=" + ff + "]");
            return null;
        }
    }

    private static String stripExt(String n) {
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n;
    }

    /**
     * Locate ffmpeg.
     *
     * <p>Ordered from most explicit to most opportunistic: the system property, then
     * PATH, then the copies that ship inside other applications on this machine.
     *
     * <p>When nothing is found the message names the places that were tried, because
     * "not found" on its own gives no way to tell a missing binary from a path the
     * process cannot read.
     */
    public static String ffmpeg() {
        if (FFMPEG_CACHE != null) {
            return FFMPEG_CACHE.isEmpty() ? null : FFMPEG_CACHE;
        }
        java.util.List<String> candidates = new java.util.ArrayList<>();
        String prop = System.getProperty("dshmusic.ffmpeg");
        if (prop != null && !prop.isBlank()) {
            // the property may name the executable or the folder holding it
            Path pp = Path.of(prop);
            if (Files.isDirectory(pp)) {
                candidates.add(pp.resolve("ffmpeg.exe").toString());
                candidates.add(pp.resolve("bin").resolve("ffmpeg.exe").toString());
            } else {
                candidates.add(prop);
            }
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                if (!dir.isBlank()) {
                    candidates.add(dir + java.io.File.separator + "ffmpeg.exe");
                    candidates.add(dir + java.io.File.separator + "ffmpeg");
                }
            }
        }
        // builds that ship alongside other software on this machine
        for (String p : new String[]{
                "D:\\hiyori\\resources\\tools\\ffmpeg.exe",
                "D:\\ZW3D\\ffmpeg.exe",
                "D:\\Minecraft Note Block Studio\\ffmpeg.exe",
                "C:\\ffmpeg\\bin\\ffmpeg.exe",
                "D:\\ffmpeg\\bin\\ffmpeg.exe",
                "C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe",
                "C:\\Program Files (x86)\\ffmpeg\\bin\\ffmpeg.exe",
                "C:\\Program Files\\VideoLAN\\VLC\\ffmpeg.exe"}) {
            candidates.add(p);
        }

        java.util.List<String> tried = new java.util.ArrayList<>();
        for (String c : candidates) {
            String hit = accept(c, tried);
            if (hit != null) {
                FFMPEG_CACHE = hit;
                System.out.println("[dshmusic] ffmpeg = " + hit);
                return hit;
            }
        }

        // The hardcoded list only covers the machine this was written on, so fall back
        // to actually looking for a build. The search runs in the background so it
        // cannot stall playback; wait for it to finish before concluding there is none.
        startFfmpegSearch();
        String found = awaitFfmpegSearch(3000);
        if (found == null && !ffmpegSearchDone) {
            tell("正在搜索 ffmpeg…");
            found = awaitFfmpegSearch(30000);
        }
        if (found != null) {
            FFMPEG_CACHE = found;
            System.out.println("[dshmusic] ffmpeg found by search = " + found);
            return found;
        }

        FFMPEG_TRIED = tried.isEmpty() ? "(常见位置都不存在)" : String.join(", ", tried);

        // Nothing usable on this machine. Provider playback is dead without it, and a
        // fresh install never has one, so fetch it here instead of leaving the user to
        // work out what ffmpeg is and where it goes.
        String installed = FfmpegInstaller.install(Mp3Player::tell);
        if (installed != null) {
            FFMPEG_CACHE = installed;
            return installed;
        }

        System.err.println("[dshmusic] ffmpeg not found; rejected: " + FFMPEG_TRIED);
        FFMPEG_CACHE = "";
        return null;
    }

    /** @return the accepted absolute path, or null; records why in {@code tried}. */
    private static String accept(String c, java.util.List<String> tried) {
        try {
            Path p = Path.of(c);
            if (!Files.isRegularFile(p)) {
                return null;
            }
            boolean exec = Files.isExecutable(p);
            long size = Files.size(p);
            if (!exec || size <= 1_000_000) {
                tried.add(p.getFileName() + "(" + (size / 1024) + "KB,exec=" + exec + ")");
                return null;
            }
            // Existence is not enough. A stripped build runs fine and still cannot open
            // the stream: the one shipped with Note Block Studio has no MP4 demuxer, so
            // a breadth-first search finds it first and every conversion then fails with
            // "Invalid data found". Probe for the capability that is actually needed.
            if (!canReadMp4(p.toString())) {
                tried.add(p.getFileName() + "(无 MP4 解复用器)");
                System.err.println("[dshmusic] rejected ffmpeg without mp4 support: " + p);
                return null;
            }
            return p.toAbsolutePath().toString();
        } catch (Exception e) {
            tried.add(c + "(" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    /**
     * Does this build list the MP4/M4A demuxer?
     *
     * <p>One cheap {@code -demuxers} run, done once per candidate and cached with the
     * result, which is the difference between "an ffmpeg exists" and "this ffmpeg can
     * open what Bilibili serves".
     */
    private static boolean canReadMp4(String ff) {
        try {
            ProcessBuilder pb = new ProcessBuilder(ff, "-hide_banner", "-demuxers");
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String out;
            try (InputStream is = proc.getInputStream()) {
                out = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return false;
            }
            String s = out.toLowerCase(java.util.Locale.ROOT);
            return s.contains("mov,mp4,m4a") || s.contains("mp4");
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Find ffmpeg without blocking playback.
     *
     * <p>A depth-first walk drowns in {@code C:\Windows} and never reaches the other
     * drive, so the search is breadth-first and each drive gets an equal slice of the
     * budget — a shallow install such as {@code D:\hiyori\resources\tools} is then found
     * in the first few levels instead of never.
     *
     * <p>It also runs on its own thread: the caller waits only a short while, and the
     * result lands in the cache for the next attempt.
     */
    private static volatile boolean ffmpegSearching;
    private static volatile String ffmpegSearchResult;
    private static volatile boolean ffmpegSearchDone;

    private static void startFfmpegSearch() {
        if (ffmpegSearching || ffmpegSearchDone) {
            return;
        }
        ffmpegSearching = true;
        Thread t = new Thread(() -> {
            try {
                ffmpegSearchResult = bfsSearch(25000);
                System.out.println("[dshmusic] ffmpeg background search -> "
                        + (ffmpegSearchResult == null ? "nothing" : ffmpegSearchResult));
            } finally {
                ffmpegSearchDone = true;
                ffmpegSearching = false;
            }
        }, "dshmusic-ffmpeg-find");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);   // must not compete with audio
        t.start();
    }

    /** @return the found path, or null if the search has not produced one yet. */
    private static String awaitFfmpegSearch(long waitMs) {
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline && !ffmpegSearchDone) {
            if (ffmpegSearchResult != null) {
                return ffmpegSearchResult;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ffmpegSearchResult;
    }

    /** Breadth-first, depth-capped, with a per-drive share of the time budget. */
    private static String bfsSearch(long budgetMs) {
        java.io.File[] roots = java.io.File.listRoots();
        if (roots.length == 0) {
            return null;
        }
        long perDrive = Math.max(1500, budgetMs / roots.length);
        for (java.io.File root : roots) {
            long deadline = System.currentTimeMillis() + perDrive;
            java.util.ArrayDeque<Path> queue = new java.util.ArrayDeque<>();
            java.util.ArrayDeque<Integer> depths = new java.util.ArrayDeque<>();
            queue.add(root.toPath());
            depths.add(0);
            int visited = 0;
            while (!queue.isEmpty() && System.currentTimeMillis() < deadline && visited < 40000) {
                Path dir = queue.poll();
                int depth = depths.poll();
                visited++;
                if (skipTree(dir, depth)) {
                    continue;
                }
                try (java.util.stream.Stream<Path> children = Files.list(dir)) {
                    for (Path p : children.toList()) {
                        String fn = p.getFileName().toString();
                        if (fn.equalsIgnoreCase("ffmpeg.exe") || fn.equalsIgnoreCase("ffmpeg")) {
                            String ok = accept(p.toString(), new java.util.ArrayList<>());
                            if (ok != null) {
                                return ok;
                            }
                        }
                        if (depth < 4 && Files.isDirectory(p)) {
                            queue.add(p);
                            depths.add(depth + 1);
                        }
                    }
                } catch (Exception ignored) {
                    // unreadable directory: skip it
                }
            }
        }
        return null;
    }

    private static boolean skipTree(Path dir, int depth) {
        if (depth == 0) {
            return false;
        }
        Path name = dir.getFileName();
        if (name == null) {
            return false;
        }
        String n = name.toString().toLowerCase(java.util.Locale.ROOT);
        return n.equals("windows") || n.equals("$recycle.bin") || n.equals("node_modules")
                || n.equals("system volume information") || n.equals("temp")
                || n.equals("winsxs") || n.startsWith("$");
    }

    private static volatile String FFMPEG_CACHE;
    /** What was rejected, for the "not found" message. */
    public static volatile String FFMPEG_TRIED = "";
    private volatile Path converted;

    /**
     * Set by the loader entrypoint so a playback failure also reaches chat.
     *
     * <p>The HUD status is only visible while a stick is held and cannot be copied out;
     * a chat line can, which is what makes a failure reportable.
     */
    public static volatile java.util.function.Consumer<String> notice;

    private static void tell(String msg) {
        java.util.function.Consumer<String> n = notice;
        if (n != null) {
            try {
                n.accept(msg);
            } catch (Throwable ignored) {
                // chat is unavailable; the HUD status still carries the message
            }
        }
    }

    /**
     * What to do when a track reaches its end by itself.
     *
     * <p>{@code ONE} is handled here, by replaying: the file is already in hand and the
     * decode thread is still alive, so a restart costs nothing and needs no help from
     * the UI. {@code ALL} needs to know what comes next, which only the library knows,
     * so that case is handed back through {@link #onTrackEnd}.
     */
    public enum LoopMode {
        OFF("关闭"),
        ALL("列表"),
        ONE("单曲");

        public final String label;

        LoopMode(String label) {
            this.label = label;
        }
    }

    private volatile LoopMode loopMode = LoopMode.OFF;

    public LoopMode loopMode() {
        return loopMode;
    }

    public void setLoopMode(LoopMode m) {
        this.loopMode = m == null ? LoopMode.OFF : m;
    }

    /** OFF -> ALL -> ONE -> OFF. */
    public LoopMode cycleLoopMode() {
        switch (loopMode) {
            case OFF:
                loopMode = LoopMode.ALL;
                break;
            case ALL:
                loopMode = LoopMode.ONE;
                break;
            default:
                loopMode = LoopMode.OFF;
                break;
        }
        return loopMode;
    }

    /**
     * Run when a track ends on its own and the mode says to move on.
     *
     * <p>Set by the loader entrypoint. Not called for a user-requested stop, and not
     * called in {@code ONE} mode.
     */
    public static volatile Runnable onTrackEnd;

    /**
     * Working directory for downloads and conversion.
     *
     * <p>Deliberately ASCII-only. Java hands a child process's arguments to Windows in
     * the system code page, so a path like {@code D:\音乐\_网络下载} reaches ffmpeg as
     * mojibake, ffmpeg reports "No such file or directory", and the conversion fails
     * with an exit code that says nothing about the real cause. Converting in an ASCII
     * folder and copying the finished file out avoids the whole class of problem.
     *
     * <p>Not the system temp directory either, because the library scan skips it — the
     * finished WAV is copied to {@link #libraryDir()} so it still becomes a normal,
     * shareable library track.
     */
    public static Path downloadDir() {
        String prop = System.getProperty("dshmusic.cache");
        Path dir = prop != null && !prop.isBlank()
                ? Path.of(prop)
                : Path.of("D:\\dshmusic-cache");
        if (!isAscii(dir.toString())) {
            dir = Path.of(System.getProperty("java.io.tmpdir"), "dshmusic");
        }
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            dir = Path.of(System.getProperty("java.io.tmpdir"), "dshmusic");
            try {
                Files.createDirectories(dir);
            } catch (Exception ignored) {
                // nothing else to try
            }
        }
        return dir;
    }

    /** Where converted tracks are placed so the library scan picks them up. */
    public static Path libraryDir() {
        Path dir = Path.of(System.getProperty("dshmusic.downloads", "D:\\音乐\\_网络下载"));
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (Exception e) {
            return downloadDir();
        }
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    /** Download the stream once per load, reporting progress as it goes. */
    private Path ensureDownloaded() {
        if (cache != null && Files.isRegularFile(cache)) {
            return cache;
        }
        try {
            Path dir = downloadDir();
            Path dest = dir.resolve(Integer.toHexString(url.hashCode()) + ".m4a");
            if (Files.isRegularFile(dest) && Files.size(dest) > 4096) {
                cache = dest;
                return dest;
            }
            netStatus = "连接中…";
            netProgress = 0;
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", referer)
                    .GET()
                    .build();
            HttpResponse<InputStream> resp =
                    client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            long total = resp.headers().firstValueAsLong("content-length").orElse(-1);
            try (InputStream in = resp.body();
                 OutputStream os = Files.newOutputStream(dest,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buf = new byte[1 << 16];
                long got = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    got += n;
                    if (total > 0) {
                        netProgress = got / (double) total;
                        netStatus = String.format("下载中 %d%%", (int) (netProgress * 100));
                    }
                }
            }
            cache = dest;
            netStatus = "解码中…";
            return dest;
        } catch (Throwable e) {
            netStatus = "下载失败：" + e.getClass().getSimpleName();
            return null;
        }
    }

    /** @return true when the caller should reopen the stream (a seek was requested). */
    private boolean decodeOnce() {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat base = source.getFormat();
            AudioFormat pcm = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    base.getSampleRate(),
                    16,
                    base.getChannels(),
                    base.getChannels() * 2,
                    base.getSampleRate(),
                    false);
            frameRate = pcm.getFrameRate();
            if (duration <= 0 && source.getFrameLength() > 0) {
                duration = source.getFrameLength() / frameRate;
            }

            try (AudioInputStream in = AudioSystem.getAudioInputStream(pcm, source)) {
                long startAt = pendingSeek;
                pendingSeek = 0;
                if (startAt > 0) {
                    skipFrames(in, pcm, startAt);
                }
                framesPlayed = startAt;

                DataLine.Info info = new DataLine.Info(SourceDataLine.class, pcm, BUFFER_BYTES);
                SourceDataLine l = (SourceDataLine) AudioSystem.getLine(info);
                l.open(pcm, BUFFER_BYTES);
                l.start();
                line = l;
                applyVolume();

                byte[] buf = new byte[BUFFER_BYTES];
                while (running) {
                    if (paused) {
                        l.stop();
                        synchronized (lock) {
                            while (paused && running && pendingSeek == 0) {
                                lock.wait(200);
                            }
                        }
                        if (!running) {
                            break;
                        }
                        l.start();
                        applyVolume();
                    }
                    if (pendingSeek > 0) {
                        l.stop();
                        l.flush();
                        return true;             // reopen at the new offset
                    }
                    int n = in.read(buf, 0, buf.length);
                    if (n <= 0) {
                        l.drain();
                        return false;            // track finished
                    }
                    l.write(buf, 0, n);
                    framesPlayed += n / (long) pcm.getFrameSize();
                }
                l.stop();
                return false;
            }
        } catch (Exception e) {
            // unsupported file, no audio device, or a closed device mid-track
            System.err.println("[dshmusic] playback failed: " + e);
            return false;
        }
    }

    /** Seek by reopening: the caller breaks out of the loop and restarts the stream. */
    private void skipFrames(AudioInputStream in, AudioFormat fmt, long frames) throws IOException {
        long bytes = frames * fmt.getFrameSize();
        long skipped = 0;
        while (skipped < bytes) {
            long s = in.skip(bytes - skipped);
            if (s <= 0) {
                break;
            }
            skipped += s;
        }
    }
}
