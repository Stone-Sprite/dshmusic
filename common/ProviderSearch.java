package dev.dsh.music;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Queries music providers for tracks.
 *
 * <p>Currently implements Bilibili only. Its public search endpoint is used directly —
 * the older {@code search/type} path answers 412 to anything without a browser session,
 * while {@code wbi/search/type} serves the same data to a plain request, so that is the
 * one called here.
 *
 * <p>Results are metadata only: title, uploader, duration and the identifiers needed to
 * resolve a stream later. No audio is fetched during a search.
 *
 * <p>JSON is parsed with targeted regular expressions rather than a parser dependency.
 * The response shape is stable and only a handful of fields are needed, and the mod jar
 * already carries an MP3 decoder plus an AAC decoder; adding a JSON library for four
 * fields is not worth the size.
 */
public final class ProviderSearch {

    /** One search hit. */
    public static final class Result {
        public final String bvid;
        public final String title;
        public final String author;
        public final String duration;

        Result(String bvid, String title, String author, String duration) {
            this.bvid = bvid;
            this.title = title;
            this.author = author;
            this.duration = duration;
        }

        @Override
        public String toString() {
            return title + "  [" + duration + "]  " + author;
        }
    }

    private ProviderSearch() {
    }

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Pattern ITEM = Pattern.compile("\\{[^{}]*\"bvid\"\\s*:\\s*\"([^\"]+)\"[^{}]*\\}");

    /** Bilibili video search. */
    public static List<Result> bilibili(String keyword) throws IOException, InterruptedException {
        String url = "https://api.bilibili.com/x/web-interface/wbi/search/type"
                + "?search_type=video&page=1&keyword=" + enc(keyword);
        String body = get(url);
        List<Result> out = new ArrayList<>();
        Matcher m = ITEM.matcher(body);
        while (m.find() && out.size() < 40) {
            String item = m.group();
            String bvid = m.group(1);
            String title = str(item, "title");
            String author = str(item, "author");
            String duration = str(item, "duration");
            if (title.isEmpty()) {
                continue;
            }
            out.add(new Result(bvid, clean(title), author, duration));
        }
        return out;
    }

    /** Resolve the first page cid for a video, needed before a stream can be requested. */
    public static long cidOf(String bvid) throws IOException, InterruptedException {
        return meta(bvid).cid;
    }

    /** Title as the provider reports it; the bvid when the lookup fails. */
    public static String titleOf(String bvid) {
        try {
            return meta(bvid).title;
        } catch (Exception e) {
            return bvid;
        }
    }

    /** Total length in seconds, or 0 when unknown. */
    public static double durationOf(String bvid) {
        try {
            return meta(bvid).duration;
        } catch (Exception e) {
            return 0;
        }
    }

    /** cid, title and duration from the view endpoint, cached per bvid. */
    public static final class Meta {
        public final long cid;
        public final String title;
        public final double duration;

        Meta(long cid, String title, double duration) {
            this.cid = cid;
            this.title = title;
            this.duration = duration;
        }
    }

    private static final java.util.Map<String, Meta> META_CACHE =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Meta> e) {
                    return size() > 32;
                }
            });

    /**
     * One call serves cid, title and duration, and the result is cached.
     *
     * <p>A sync message carries only the video id — the title is deliberately left out
     * to keep the encrypted blob inside the server's chat length limit — so the
     * receiver asks the provider for the rest.
     */
    public static Meta meta(String bvid) throws IOException, InterruptedException {
        Meta hit = META_CACHE.get(bvid);
        if (hit != null) {
            return hit;
        }
        String body = get("https://api.bilibili.com/x/web-interface/view?bvid=" + enc(bvid));
        Matcher c = Pattern.compile("\"cid\"\\s*:\\s*(\\d+)").matcher(body);
        long cid = c.find() ? Long.parseLong(c.group(1)) : 0L;
        String title = str(body, "title");
        Matcher d = Pattern.compile("\"duration\"\\s*:\\s*(\\d+)").matcher(body);
        double dur = d.find() ? Double.parseDouble(d.group(1)) : 0;
        if (title.isEmpty()) {
            title = bvid;
        }
        Meta m = new Meta(cid, title, dur);
        META_CACHE.put(bvid, m);
        return m;
    }

    /**
     * Publicly served stream for a video.
     *
     * <p>Both of Bilibili's formats are usable, but only one of them is decodable by
     * the bundled tooling, so the choice is deliberate rather than incidental:
     *
     * <ul>
     *   <li>{@code fnval=1} returns a conventional progressive MP4. It is larger
     *       because the video track rides along, but its sample table is ordinary, so
     *       JCodec's demuxer reads it and the AAC audio track decodes.</li>
     *   <li>{@code fnval=16} returns DASH, which is smaller and audio-only, but it is
     *       a fragmented MP4 — the samples live in {@code moof}/{@code trun} boxes
     *       that JCodec 0.2.5 does not read, so the track reports zero frames and
     *       yields nothing to decode.</li>
     * </ul>
     *
     * <p>Only URLs handed out for anonymous playback are used. Nothing here attempts
     * to obtain member-only or otherwise restricted media; when the response offers no
     * playable stream the caller is told so rather than worked around.
     */
    public static String audioUrl(String bvid, long cid) throws IOException, InterruptedException {
        String body = get("https://api.bilibili.com/x/player/playurl?fnval=1&fourk=1"
                + "&bvid=" + enc(bvid) + "&cid=" + cid);

        String durl = arrayAfter(body, "\"durl\"");
        if (durl != null) {
            Matcher m = Pattern.compile("\"url\"\\s*:\\s*\"(https?://[^\"]+)\"").matcher(durl);
            if (m.find()) {
                return tidy(m.group(1));
            }
        }

        // older responses may still answer with dash; take an audio representation from
        // inside that array only, since video entries carry the same key names
        String dash = arrayAfter(body, "\"audio\"");
        if (dash != null) {
            Matcher m = Pattern.compile("\"baseUrl\"\\s*:\\s*\"(https?://[^\"]+)\"").matcher(dash);
            if (m.find()) {
                return tidy(m.group(1));
            }
        }
        return null;
    }

    private static String tidy(String url) {
        return unescape(url.replace("\\u0026", "&").replace("\\/", "/"));
    }

    /** The {@code [...]} slice that follows a {@code "key"} token, or null. */
    private static String arrayAfter(String body, String key) {
        int k = body.indexOf(key);
        if (k < 0) {
            return null;
        }
        int open = body.indexOf('[', k + key.length());
        if (open < 0) {
            return null;
        }
        int depth = 0;
        boolean inStr = false;
        for (int i = open; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return body.substring(open, i + 1);
                }
            }
        }
        return null;
    }

    private static String get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com/")
                .header("Accept", "application/json, text/plain, */*")
                .GET()
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return resp.body() == null ? "" : resp.body();
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Pull a string field out of one JSON object fragment. */
    private static String str(String obj, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(obj);
        return m.find() ? unescape(m.group(1)) : "";
    }

    /** Resolve the JSON escapes Bilibili uses, including \\uXXXX for HTML entities. */
    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': sb.append(' '); break;
                case 't': sb.append(' '); break;
                case 'r': break;
                case '"': sb.append('"'); break;
                case '/': sb.append('/'); break;
                case '\\': sb.append('\\'); break;
                case 'u': {
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            sb.append(n);
                        }
                    } else {
                        sb.append(n);
                    }
                    break;
                }
                default: sb.append(n); break;
            }
        }
        return sb.toString();
    }

    /** Search titles arrive with the keyword wrapped in <em> tags and HTML entities. */
    private static String clean(String s) {
        String t = s.replaceAll("<[^>]+>", "");
        t = t.replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<")
             .replace("&gt;", ">").replace("&#39;", "'").replace("&nbsp;", " ");
        return t.trim();
    }
}
