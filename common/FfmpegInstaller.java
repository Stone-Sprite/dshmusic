package dev.dsh.music;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Fetches a working ffmpeg when the machine has none.
 *
 * <p>Provider playback needs an ffmpeg that can open MP4/M4A, and a fresh machine
 * usually has neither. Rather than making that the user's problem, the build is
 * downloaded once, the single executable is extracted, and the archive is deleted —
 * only the ~80 MB binary is kept, not the ~110 MB zip.
 *
 * <p>Kept out of {@link Mp3Player} because it is a separate concern: that class plays
 * audio, this one provisions a tool.
 */
public final class FfmpegInstaller {

    /**
     * Sources to try, smallest first.
     *
     * <p>The GitHub one is a gzipped single executable (~28 MB) — four times less to
     * transfer than a whole build, and nothing to unpack beyond one gunzip. It is not
     * reachable from every network, and neither is the fallback from every network, so
     * both are tried and the first usable result wins.
     *
     * <p>Overridable with {@code -Ddshmusic.ffmpegUrl=...}; a {@code .zip} URL works too.
     */
    private static final String[] DEFAULT_URLS = {
            "https://github.com/eugeneware/ffmpeg-static/releases/latest/download/ffmpeg-win32-x64.gz",
            "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip",
    };

    /**
     * How long one source gets before the next is tried.
     *
     * <p>Not a nicety: a connection that accepts the request and then delivers nothing
     * would otherwise hold the installer forever, which is exactly what happens to
     * GitHub from some Chinese networks.
     */
    private static final long PER_SOURCE_MS = 120_000;

    private FfmpegInstaller() {
    }

    /**
     * Download and extract ffmpeg into the cache directory.
     *
     * <p>Blocking, and only called when nothing usable was found; the caller is expected
     * to report progress through {@code progress} because this can take minutes.
     *
     * @return the executable's path, or null when the download or extraction failed.
     */
    public static String install(Consumer<String> progress) {
        Path dir = Mp3Player.downloadDir().resolve("ffmpeg");
        Path exe = dir.resolve("ffmpeg.exe");
        try {
            Files.createDirectories(dir);
            if (Files.isRegularFile(exe) && Files.size(exe) > 1_000_000) {
                return exe.toAbsolutePath().toString();
            }

            String override = System.getProperty("dshmusic.ffmpegUrl");
            String[] urls = override != null && !override.isBlank()
                    ? new String[]{override}
                    : DEFAULT_URLS;

            for (String url : urls) {
                Path dl = dir.resolve(url.endsWith(".gz") ? "ffmpeg.gz" : "ffmpeg.zip");
                try {
                    Files.deleteIfExists(dl);
                    long total = head(url);
                    progress.accept("正在下载 ffmpeg"
                            + (total > 0 ? "（约 " + (total / 1024 / 1024) + " MB）" : "") + "…");
                    if (!download(url, dl, total, progress, PER_SOURCE_MS)) {
                        progress.accept("该源太慢，换下一个…");
                        Files.deleteIfExists(dl);
                        continue;
                    }

                    progress.accept("正在解压 ffmpeg…");
                    boolean ok = url.endsWith(".gz") ? gunzip(dl, exe) : extractExe(dl, exe);
                    Files.deleteIfExists(dl);
                    if (!ok) {
                        progress.accept("压缩包格式异常，换下一个源…");
                        continue;
                    }
                    if (!Files.isRegularFile(exe) || Files.size(exe) < 1_000_000) {
                        continue;
                    }
                    progress.accept("ffmpeg 安装完成：" + exe);
                    return exe.toAbsolutePath().toString();
                } catch (Throwable e) {
                    progress.accept("该源失败（" + e.getClass().getSimpleName() + "），换下一个…");
                    System.err.println("[dshmusic] ffmpeg source failed: " + url + " -> " + e);
                    try {
                        Files.deleteIfExists(dl);
                    } catch (Exception ignored) {
                        // nothing to clean
                    }
                }
            }
            progress.accept("所有下载源都失败了。可手动放置 ffmpeg 并用 -Ddshmusic.ffmpeg=<路径> 指定");
            return null;
        } catch (Throwable e) {
            progress.accept("ffmpeg 自动安装失败： " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : (" " + e.getMessage()))
                    + "。可手动放置并用 -Ddshmusic.ffmpeg=<路径> 指定");
            System.err.println("[dshmusic] ffmpeg install failed: " + e);
            return null;
        }
    }

    private static long head(String url) {
        try {
            HttpClient c = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            return c.send(req, HttpResponse.BodyHandlers.discarding())
                    .headers().firstValueAsLong("content-length").orElse(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    /** @return true when the whole file arrived inside {@code budgetMs}. */
    private static boolean download(String url, Path dest, long total, Consumer<String> progress,
                                    long budgetMs) throws Exception {
        long deadline = System.currentTimeMillis() + budgetMs;
        HttpClient c = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();
        HttpResponse<InputStream> resp = c.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            throw new java.io.IOException("HTTP " + resp.statusCode());
        }
        long got = 0;
        long lastReport = 0;
        try (InputStream in = resp.body();
             OutputStream os = Files.newOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                got += n;
                if (total > 0 && got - lastReport > (total / 20)) {
                    lastReport = got;
                    progress.accept("正在下载 ffmpeg " + (got * 100 / total) + "%");
                }
                // A connection that trickles cannot be allowed to hold this open: the
                // observed failure mode is a source that accepts the request and then
                // delivers a fraction of a MB per minute.
                if (System.currentTimeMillis() > deadline) {
                    return false;
                }
            }
        }
        if (total > 0 && got < total) {
            return false;
        }
        if (got < 1_000_000) {
            throw new java.io.IOException("下载内容不完整（" + got + " 字节）");
        }
        return true;
    }

    /** The single-executable form: one gzip stream, written straight out. */
    private static boolean gunzip(Path gz, Path out) throws Exception {
        try (java.util.zip.GZIPInputStream in =
                     new java.util.zip.GZIPInputStream(Files.newInputStream(gz))) {
            Path tmp = out.resolveSibling("ffmpeg.exe.part");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            return Files.size(out) > 1_000_000;
        }
    }

    /**
     * Pull just {@code bin/ffmpeg.exe} out of a full build zip.
     *
     * <p>Streamed rather than unpacked wholesale: the archive also carries ffplay,
     * ffprobe, documentation and licences, and only the one binary is wanted.
     */
    private static boolean extractExe(Path zip, Path out) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
                if (e.isDirectory() || !name.endsWith("/bin/ffmpeg.exe")) {
                    continue;
                }
                Path tmp = out.resolveSibling("ffmpeg.exe.part");
                Files.copy(zis, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
        }
        return false;
    }
}
