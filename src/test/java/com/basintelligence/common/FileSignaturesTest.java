package com.basintelligence.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.basintelligence.common.FileSignatures.Type;

class FileSignaturesTest {

    @Test
    void identifiesFilesByContentNotByName() {
        assertThat(FileSignatures.detect(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0})).isEqualTo(Type.PNG);
        assertThat(FileSignatures.detect(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0})).isEqualTo(Type.JPEG);
        assertThat(FileSignatures.detect("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII))).isEqualTo(Type.PDF);
        assertThat(FileSignatures.detect(new byte[] {'P', 'K', 3, 4, 0})).isEqualTo(Type.ZIP);
        assertThat(FileSignatures.detect("# Pump manual\n\nCheck the seal.\n".getBytes(StandardCharsets.UTF_8))).isEqualTo(Type.TEXT);
        assertThat(FileSignatures.detect("Lagerprüfung – °C".getBytes(StandardCharsets.UTF_8))).isEqualTo(Type.TEXT);
        // a GIF, a Windows executable and binary noise are not on any allowlist
        assertThat(FileSignatures.detect(Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")))
                .isEqualTo(Type.UNKNOWN);
        assertThat(FileSignatures.detect(new byte[] {'M', 'Z', (byte) 0x90, 0, 3, 0})).isEqualTo(Type.UNKNOWN);
        assertThat(FileSignatures.detect(new byte[0])).isEqualTo(Type.UNKNOWN);
        // "BM" followed by prose is text, not a bitmap
        assertThat(FileSignatures.detect("BMW service notes".getBytes(StandardCharsets.US_ASCII))).isEqualTo(Type.TEXT);
    }

    @Test
    void sanitisesClientFileNames() {
        assertThat(Text.safeFilename("../../etc/passwd")).isEqualTo("passwd");
        assertThat(Text.safeFilename("C:\\Users\\x\\manual <v2>.pdf")).isEqualTo("manual _v2_.pdf");
        assertThat(Text.safeFilename(".htaccess")).isEqualTo("_htaccess");
        assertThat(Text.safeFilename("  ")).isNull();
    }
}
