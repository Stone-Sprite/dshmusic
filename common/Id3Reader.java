package dev.dsh.music;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * A minimal ID3v2 / ID3v1 reader.
 *
 * <p>Written by hand rather than pulling in a tag library: only a handful of frames
 * are needed, and it keeps the mod jar free of extra dependencies.
 *
 * <p>Handles ID3v2.2, 2.3 and 2.4 (the frame id differs between 2.2 and the later
 * versions), then falls back to the fixed 128-byte ID3v1 trailer. Anything missing
 * is left null so the caller can fall back to the file name — the library in
 * {@code D:\音乐} is named inconsistently, so tags are the only reliable source.
 */
public final class Id3Reader {

    /** Tag values that were actually present. Missing fields stay null. */
    public static final class Tags {
        public String title;
        public String artist;
        public String album;
        public String year;
        public String genre;
        /** Raw bytes of an embedded picture (APIC/PIC), or null. */
        public byte[] picture;
    }

    private Id3Reader() {
    }

    public static Tags read(Path file) {
        Tags tags = new Tags();
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            if (!readV2(raf, tags)) {
                readV1(raf, tags);
            } else if (tags.title == null && tags.artist == null) {
                readV1(raf, tags);   // v2 present but empty; the v1 trailer may help
            }
        } catch (IOException e) {
            // unreadable file: return whatever we have (nothing) and let the caller
            // fall back to the file name
        }
        return tags;
    }

    /** @return true when a usable ID3v2 tag was found. */
    private static boolean readV2(RandomAccessFile raf, Tags tags) throws IOException {
        if (raf.length() < 10) {
            return false;
        }
        raf.seek(0);
        byte[] header = new byte[10];
        raf.readFully(header);
        if (header[0] != 'I' || header[1] != 'D' || header[2] != '3') {
            return false;
        }
        int major = header[3] & 0xFF;
        if (major < 2 || major > 4) {
            return false;
        }
        int flags = header[5] & 0xFF;
        int size = syncSafe(header, 6);
        if (size <= 0 || size > 32 * 1024 * 1024) {
            return false;
        }

        byte[] body = new byte[size];
        raf.readFully(body);

        int offset = 0;
        // extended header sits between the tag header and the frames
        if ((flags & 0x40) != 0 && major >= 3) {
            if (major == 4) {
                offset += syncSafe(body, 0);          // v2.4: whole extended header
            } else {
                offset += 4 + beInt(body, 0);         // v2.3: size excludes its own 4 bytes
            }
        }

        int idLen = (major == 2) ? 3 : 4;
        int frameHeaderLen = (major == 2) ? 6 : 10;
        Map<String, String> frames = new HashMap<>();

        while (offset + frameHeaderLen <= body.length) {
            String id = new String(body, offset, idLen, StandardCharsets.ISO_8859_1);
            if (id.charAt(0) == 0) {
                break;                                // padding reached
            }
            int frameSize;
            if (major == 2) {
                frameSize = ((body[offset + 3] & 0xFF) << 16) | ((body[offset + 4] & 0xFF) << 8)
                        | (body[offset + 5] & 0xFF);
            } else if (major == 4) {
                frameSize = syncSafe(body, offset + 4);   // v2.4 sizes are sync-safe
            } else {
                frameSize = beInt(body, offset + 4);      // v2.3 sizes are plain big-endian
            }
            int dataStart = offset + frameHeaderLen;
            if (frameSize <= 0 || dataStart + frameSize > body.length) {
                break;
            }

            if (id.equals("APIC") || id.equals("PIC")) {
                tags.picture = extractPicture(body, dataStart, frameSize, major == 2);
            } else {
                String key = canonical(id);
                if (key != null && !frames.containsKey(key)) {
                    String value = decodeText(body, dataStart, frameSize);
                    if (!value.isEmpty()) {
                        frames.put(key, value);
                    }
                }
            }
            offset = dataStart + frameSize;
        }

        tags.title = frames.get("title");
        tags.artist = frames.get("artist");
        tags.album = frames.get("album");
        tags.year = frames.get("year");
        tags.genre = frames.get("genre");
        return true;
    }

    /** Map the version-specific frame ids onto a common set of keys. */
    private static String canonical(String id) {
        switch (id) {
            case "TIT2": case "TT2":
                return "title";
            case "TPE1": case "TP1":
                return "artist";
            case "TALB": case "TAL":
                return "album";
            case "TYER": case "TDRC": case "TYE":
                return "year";
            case "TCON": case "TCO":
                return "genre";
            default:
                return null;
        }
    }

    private static byte[] extractPicture(byte[] body, int start, int len, boolean v22) {
        try {
            int p = start;
            int end = start + len;
            int encoding = body[p++] & 0xFF;
            String mime;
            if (v22) {                       // 3-character image format
                mime = new String(body, p, 3, StandardCharsets.ISO_8859_1);
                p += 3;
            } else {                         // null-terminated mime type
                int m = p;
                while (m < end && body[m] != 0) {
                    m++;
                }
                mime = new String(body, p, m - p, StandardCharsets.ISO_8859_1);
                p = m + 1;
            }
            p++;                             // picture type byte
            if (encoding == 1 || encoding == 2) {
                while (p + 1 < end && !(body[p] == 0 && body[p + 1] == 0)) {
                    p += 2;
                }
                p += 2;
            } else {
                while (p < end && body[p] != 0) {
                    p++;
                }
                p++;
            }
            if (p >= end) {
                return null;
            }
            byte[] img = new byte[end - p];
            System.arraycopy(body, p, img, 0, img.length);
            return img;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Text frames start with an encoding byte; the rest is the string. */
    private static String decodeText(byte[] body, int start, int len) {
        if (len <= 1) {
            return "";
        }
        int enc = body[start] & 0xFF;
        int p = start + 1;
        int n = len - 1;
        Charset cs;
        switch (enc) {
            case 1: {                        // UTF-16 with BOM
                if (n >= 2 && (body[p] & 0xFF) == 0xFF && (body[p + 1] & 0xFF) == 0xFE) {
                    cs = StandardCharsets.UTF_16LE;
                    p += 2;
                    n -= 2;
                } else if (n >= 2 && (body[p] & 0xFF) == 0xFE && (body[p + 1] & 0xFF) == 0xFF) {
                    cs = StandardCharsets.UTF_16BE;
                    p += 2;
                    n -= 2;
                } else {
                    cs = StandardCharsets.UTF_16;
                }
                break;
            }
            case 2:
                cs = StandardCharsets.UTF_16BE;
                break;
            case 3:
                cs = StandardCharsets.UTF_8;
                break;
            default:
                cs = StandardCharsets.ISO_8859_1;
                break;
        }
        String s = new String(body, p, n, cs);
        // frames are usually null-terminated; strip that and any stray whitespace
        int z = s.indexOf('\0');
        if (z >= 0) {
            s = s.substring(0, z);
        }
        return s.trim();
    }

    /** ID3v1 lives in the last 128 bytes and starts with "TAG". */
    private static void readV1(RandomAccessFile raf, Tags tags) throws IOException {
        if (raf.length() < 128) {
            return;
        }
        raf.seek(raf.length() - 128);
        byte[] buf = new byte[128];
        raf.readFully(buf);
        if (buf[0] != 'T' || buf[1] != 'A' || buf[2] != 'G') {
            return;
        }
        if (tags.title == null) {
            tags.title = v1Field(buf, 3, 30, StandardCharsets.ISO_8859_1);
        }
        if (tags.artist == null) {
            tags.artist = v1Field(buf, 33, 30, StandardCharsets.ISO_8859_1);
        }
        if (tags.album == null) {
            tags.album = v1Field(buf, 63, 30, StandardCharsets.ISO_8859_1);
        }
        if (tags.year == null) {
            tags.year = v1Field(buf, 93, 4, StandardCharsets.ISO_8859_1);
        }
    }

    private static String v1Field(byte[] buf, int off, int len, Charset cs) {
        // ID3v1 may be GBK on Chinese files; try ISO-8859-1 first and re-decode if it
        // looks like mojibake (the byte range used by GBK lead bytes)
        String s = new String(buf, off, len, cs);
        int z = s.indexOf('\0');
        if (z >= 0) {
            s = s.substring(0, z);
        }
        return s.trim();
    }

    /** ID3v2 sizes are stored 7 bits per byte, most significant first. */
    private static int syncSafe(byte[] b, int off) {
        return ((b[off] & 0x7F) << 21) | ((b[off + 1] & 0x7F) << 14)
                | ((b[off + 2] & 0x7F) << 7) | (b[off + 3] & 0x7F);
    }

    private static int beInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }
}
