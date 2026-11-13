package com.basintelligence.common;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Identifies uploaded files by their leading bytes ("magic numbers") instead of trusting the file name or the
 * Content-Type header, which the client controls.
 */
public final class FileSignatures {

    public enum Type {
        PNG, JPEG, BMP, PDF, ZIP, OLE2, TEXT, UNKNOWN
    }

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] BMP = {'B', 'M'};
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] ZIP = {'P', 'K', 0x03, 0x04};
    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private FileSignatures() {
    }

    public static Type detect(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Type.UNKNOWN;
        }
        if (startsWith(bytes, PNG)) {
            return Type.PNG;
        }
        if (startsWith(bytes, JPEG)) {
            return Type.JPEG;
        }
        if (startsWith(bytes, PDF)) {
            return Type.PDF;
        }
        if (startsWith(bytes, ZIP)) {
            return Type.ZIP;
        }
        if (startsWith(bytes, OLE2)) {
            return Type.OLE2;
        }
        // "BM" is also plain text, so BMP additionally needs a plausible header (file size field and 14-byte header).
        if (startsWith(bytes, BMP) && bytes.length >= 26 && littleEndianInt(bytes, 10) >= 26) {
            return Type.BMP;
        }
        return isText(bytes) ? Type.TEXT : Type.UNKNOWN;
    }

    /** Valid UTF-8 without NUL bytes or control characters other than tab, line feed, form feed and carriage return. */
    public static boolean isText(byte[] bytes) {
        byte[] body = startsWith(bytes, UTF8_BOM) ? Arrays.copyOfRange(bytes, UTF8_BOM.length, bytes.length) : bytes;
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            return false;
        }
        return text.chars().noneMatch(c -> c < 0x20 && c != '\t' && c != '\n' && c != '\r' && c != '\f' || c == 0x7F);
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static int littleEndianInt(byte[] b, int offset) {
        return (b[offset] & 0xFF) | (b[offset + 1] & 0xFF) << 8 | (b[offset + 2] & 0xFF) << 16 | (b[offset + 3] & 0xFF) << 24;
    }
}
