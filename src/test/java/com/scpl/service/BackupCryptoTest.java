package com.scpl.service;

import com.scpl.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupCryptoTest {

    private static final String KEY = "unit-test-master-key-0123456789-abcdef";
    private static final int HEADER = 53;   // magic 8 + version 1 + society 8 + day 8 + IV 12 + tag 16

    private final BackupCrypto crypto = new BackupCrypto(KEY);
    private final byte[] plain = "the quick brown fox".getBytes(StandardCharsets.UTF_8);

    @Test
    void aSealedFileOpensAgainToTheSameBytesSocietyAndDay() {
        byte[] sealed = crypto.seal(7, 20_000, plain);
        BackupCrypto.Opened opened = crypto.open(sealed);
        assertThat(opened.gzipped()).isEqualTo(plain);
        assertThat(opened.societyId()).isEqualTo(7);
        assertThat(opened.epochDay()).isEqualTo(20_000);
        assertThat(sealed.length).isEqualTo(HEADER + plain.length);
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("quick brown fox");
    }

    @Test
    void everyFileGetsAFreshIv() {
        assertThat(crypto.seal(7, 20_000, plain)).isNotEqualTo(crypto.seal(7, 20_000, plain));
    }

    @Test
    void editingTheCiphertextOrTagFailsDecryption() {
        byte[] sealed = crypto.seal(7, 20_000, plain);
        sealed[sealed.length - 1] ^= 1;
        assertThatThrownBy(() -> crypto.open(sealed)).isInstanceOf(BusinessRuleException.class);

        byte[] tagEdited = crypto.seal(7, 20_000, plain);
        tagEdited[HEADER - 1] ^= 1;     // last byte of the GCM tag
        assertThatThrownBy(() -> crypto.open(tagEdited)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void editingTheSocietyIdOrDateInTheHeaderFailsBecauseTheHeaderIsAuthenticated() {
        byte[] society = crypto.seal(7, 20_000, plain);
        society[16] ^= 1;      // low byte of the society id (bytes 9..16)
        assertThatThrownBy(() -> crypto.open(society)).isInstanceOf(BusinessRuleException.class);

        byte[] day = crypto.seal(7, 20_000, plain);
        day[24] ^= 1;          // low byte of the epoch day (bytes 17..24)
        assertThatThrownBy(() -> crypto.open(day)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void aDifferentMasterKeyCannotOpenTheFile() {
        byte[] sealed = crypto.seal(7, 20_000, plain);
        BackupCrypto other = new BackupCrypto("another-master-key-0123456789-abcdefgh");
        assertThatThrownBy(() -> other.open(sealed)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void garbageAndTruncatedFilesAreRejected() {
        assertThatThrownBy(() -> crypto.open(null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> crypto.open(new byte[10])).isInstanceOf(BusinessRuleException.class);
        byte[] notOurs = new byte[200];
        Arrays.fill(notOurs, (byte) 7);
        assertThatThrownBy(() -> crypto.open(notOurs)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("not an SCPL backup");
        byte[] sealed = crypto.seal(7, 20_000, plain);
        assertThatThrownBy(() -> crypto.open(Arrays.copyOf(sealed, sealed.length - 3))).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void aWeakMasterKeyStopsTheApplicationFromStarting() {
        assertThatThrownBy(() -> new BackupCrypto("short")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BackupCrypto("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BackupCrypto(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void helpers() {
        assertThat(BackupCrypto.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        byte[] data = new byte[5000];
        assertThat(BackupCrypto.gunzip(BackupCrypto.gzip(data), 10_000)).isEqualTo(data);
        assertThatThrownBy(() -> BackupCrypto.gunzip(BackupCrypto.gzip(data), 100)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> BackupCrypto.gunzip(new byte[]{1, 2, 3}, 100)).isInstanceOf(BusinessRuleException.class);
    }
}