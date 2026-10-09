package dev.dsh.music;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Wraps a sync payload so it travels through chat as an opaque blob.
 *
 * <p>AES-256-GCM with a random nonce per message, base64url-encoded and prefixed with a
 * marker so a receiving client can recognise its own traffic.
 *
 * <p>Be clear about what this is and is not: the key is derived from a constant that
 * ships inside the mod, so anyone holding the jar can decrypt the payload. It hides the
 * contents from other players reading the chat and gives the message a compact, fixed
 * shape — it is obfuscation, not a security boundary, and nothing here should be relied
 * on to keep a secret from someone who has the mod.
 *
 * <p>The authenticated tag is the part that does real work: a truncated or corrupted
 * chat line fails to authenticate and is dropped, rather than being parsed as garbage.
 */
public final class SyncCrypto {

    /** Marker so a chat line can be identified before attempting to decrypt. */
    public static final String MARK = "[DSHM]";

    private static final String KEY_MATERIAL = "dshmusic/shared-payload/v1";
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final SecretKeySpec KEY = new SecretKeySpec(sha256(KEY_MATERIAL), "AES");

    private SyncCrypto() {
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** @return the marker plus base64url(nonce || ciphertext || tag), or null on failure. */
    public static String seal(String plain) {
        try {
            byte[] nonce = new byte[NONCE_LEN];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, KEY, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ct, 0, out, nonce.length, ct.length);
            return MARK + Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            System.err.println("[dshmusic] seal failed: " + e);
            return null;
        }
    }

    /**
     * @param text any chat line; the marker may appear anywhere in it
     * @return the plaintext, or null when the line carries no marker or fails to
     *         authenticate (a wrong key, a truncated line, or another mod's message).
     */
    public static String open(String text) {
        if (text == null) {
            return null;
        }
        int at = text.indexOf(MARK);
        if (at < 0) {
            return null;
        }
        String body = text.substring(at + MARK.length()).trim();
        // stop at the first character that cannot be part of base64url
        int end = 0;
        while (end < body.length()) {
            char ch = body.charAt(end);
            boolean ok = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_';
            if (!ok) {
                break;
            }
            end++;
        }
        if (end < NONCE_LEN + 1) {
            return null;
        }
        try {
            byte[] all = Base64.getUrlDecoder().decode(body.substring(0, end));
            if (all.length <= NONCE_LEN) {
                return null;
            }
            byte[] nonce = new byte[NONCE_LEN];
            System.arraycopy(all, 0, nonce, 0, NONCE_LEN);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, KEY, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] pt = c.doFinal(all, NONCE_LEN, all.length - NONCE_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // wrong key, tampered payload, or not our message at all
            return null;
        }
    }
}
