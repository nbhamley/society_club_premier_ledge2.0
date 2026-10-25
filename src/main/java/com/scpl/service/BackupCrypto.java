package com.scpl.service;

import com.scpl.exception.BusinessRuleException;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * AES-256-GCM sealing of society backups (§10).
 *
 * <p>Key = SHA-256(masterKey || societyId || epochDay), with the two numbers as fixed-width 8-byte
 * values so different (society, day) pairs can never concatenate to the same bytes. As the spec
 * asks, this is a plain hash rather than a password KDF, so it relies on the master key being a
 * long random secret; the constructor refuses anything shorter than 32 characters.
 *
 * <p>File layout (53-byte header, then ciphertext):
 * magic "SCPLENC1" (8) | version (1) | societyId (8) | epochDay (8) | IV (12) | GCM tag (16) | ciphertext.
 * The first 25 header bytes are the GCM associated data, so editing the society id or date in the
 * header makes decryption fail instead of silently deriving a different key. A fresh random IV is
 * used for every file.
 */
@Component
public class BackupCrypto {

    public static final String ALGORITHM = "AES-256-GCM";
    private static final byte[] MAGIC = "SCPLENC1".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final int IV_LEN = 12;
    private static final int TAG_LEN = 16;
    private static final int AAD_LEN = 8 + 1 + 8 + 8;
    private static final int HEADER_LEN = AAD_LEN + IV_LEN + TAG_LEN;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Opened(long societyId, long epochDay, byte[] gzipped) {
    }

    private final byte[] masterKey;

    public BackupCrypto(@Value("${scpl.backup.master-key:}") String master) {
        if (master == null || master.length() < 32) {
            throw new IllegalStateException(
                    "scpl.backup.master-key (SCPL_BACKUP_MASTER_KEY) must be set to a secret of at least 32 characters");
        }
        this.masterKey = master.getBytes(StandardCharsets.UTF_8);
    }

    public byte[] seal(long societyId, long epochDay, byte[] plain) {
        byte[] aad = aad(societyId, epochDay);
        byte[] iv = new byte[IV_LEN];
        RANDOM.nextBytes(iv);
        byte[] ctAndTag = crypt(true, deriveKey(societyId, epochDay), iv, aad, plain);
        int ctLen = ctAndTag.length - TAG_LEN;
        return ByteBuffer.allocate(HEADER_LEN + ctLen).put(aad).put(iv).put(ctAndTag, ctLen, TAG_LEN)
                .put(ctAndTag, 0, ctLen).array();
    }

    /** Throws BusinessRuleException for anything that is not a valid, untampered file made with this master key. */
    public Opened open(byte[] file) {
        if (file == null || file.length <= HEADER_LEN) {
            throw new BusinessRuleException("This is not an SCPL backup file");
        }
        ByteBuffer buf = ByteBuffer.wrap(file);
        byte[] magic = new byte[MAGIC.length];
        buf.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new BusinessRuleException("This is not an SCPL backup file");
        }
        if (buf.get() != VERSION) {
            throw new BusinessRuleException("This backup was made by an incompatible version");
        }
        long societyId = buf.getLong();
        long epochDay = buf.getLong();
        byte[] iv = new byte[IV_LEN];
        buf.get(iv);
        byte[] tag = new byte[TAG_LEN];
        buf.get(tag);
        int ctLen = file.length - HEADER_LEN;
        byte[] ctAndTag = new byte[ctLen + TAG_LEN];
        buf.get(ctAndTag, 0, ctLen);
        System.arraycopy(tag, 0, ctAndTag, ctLen, TAG_LEN);
        byte[] plain = crypt(false, deriveKey(societyId, epochDay), iv, Arrays.copyOf(file, AAD_LEN), ctAndTag);
        return new Opened(societyId, epochDay, plain);
    }

    private static byte[] crypt(boolean encrypt, byte[] key, byte[] iv, byte[] aad, byte[] in) {
        GCMBlockCipher cipher = new GCMBlockCipher(AESEngine.newInstance());
        cipher.init(encrypt, new AEADParameters(new KeyParameter(key), TAG_LEN * 8, iv, aad));
        byte[] out = new byte[cipher.getOutputSize(in.length)];
        try {
            int n = cipher.processBytes(in, 0, in.length, out, 0);
            n += cipher.doFinal(out, n);
            return n == out.length ? out : Arrays.copyOf(out, n);
        } catch (InvalidCipherTextException e) {
            throw new BusinessRuleException("This backup cannot be decrypted. It may be damaged, edited, or made "
                    + "with a different master key.");
        }
    }

    private static byte[] aad(long societyId, long epochDay) {
        return ByteBuffer.allocate(AAD_LEN).put(MAGIC).put((byte) VERSION).putLong(societyId).putLong(epochDay).array();
    }

    private byte[] deriveKey(long societyId, long epochDay) {
        MessageDigest md = sha256();
        md.update(masterKey);
        md.update(ByteBuffer.allocate(16).putLong(societyId).putLong(epochDay).array());
        return md.digest();
    }

    // ------------------------------------------------------------------ helpers shared with backup, restore and import

    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(sha256().digest(data));
    }

    public static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 4));
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        } catch (IOException e) {
            throw new IllegalStateException("Compression failed", e);
        }
        return out.toByteArray();
    }

    /** Capped, so a small malicious file cannot inflate into gigabytes. */
    public static byte[] gunzip(byte[] data, long maxBytes) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            byte[] out = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
            if (out.length > maxBytes) {
                throw new BusinessRuleException("The backup unpacks to more data than is allowed");
            }
            return out;
        } catch (IOException e) {
            throw new BusinessRuleException("The backup contents are damaged");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}