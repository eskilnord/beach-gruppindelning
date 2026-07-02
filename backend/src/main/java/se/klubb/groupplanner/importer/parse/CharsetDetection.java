package se.klubb.groupplanner.importer.parse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * CSV charset detection per docs/plan.md's red-team correction: "CSV fallback charset: windows-1252
 * (not ISO-8859-1) after UTF-8 BOM sniff".
 *
 * <p>Order: (1) sniff a UTF-8 byte-order-mark and strip it if present; (2) attempt a strict UTF-8
 * decode (fails loudly on invalid byte sequences, so genuinely UTF-8 files - the common case for
 * modern exports - are never mis-decoded as windows-1252); (3) fall back to windows-1252, which
 * never fails to decode (every byte value is a valid code point in that single-byte charset) and
 * correctly renders Swedish å/ä/ö plus the smart-quote/en/em-dash characters (’ … –) old Excel
 * exports use.
 */
public final class CharsetDetection {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private CharsetDetection() {
    }

    public record Decoded(String text, Charset charsetUsed) {
    }

    public static Decoded decode(byte[] bytes) {
        byte[] withoutBom = stripBom(bytes);
        boolean hadBom = withoutBom.length != bytes.length;

        if (hadBom) {
            return new Decoded(new String(withoutBom, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }

        String strictUtf8 = tryStrictUtf8(withoutBom);
        if (strictUtf8 != null) {
            return new Decoded(strictUtf8, StandardCharsets.UTF_8);
        }

        Charset windows1252 = Charset.forName("windows-1252");
        return new Decoded(new String(withoutBom, windows1252), windows1252);
    }

    private static byte[] stripBom(byte[] bytes) {
        if (bytes.length >= 3
                && bytes[0] == UTF8_BOM[0] && bytes[1] == UTF8_BOM[1] && bytes[2] == UTF8_BOM[2]) {
            byte[] result = new byte[bytes.length - 3];
            System.arraycopy(bytes, 3, result, 0, result.length);
            return result;
        }
        return bytes;
    }

    private static String tryStrictUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            byte[] all = in.readAllBytes();
            return decoder.decode(java.nio.ByteBuffer.wrap(all)).toString();
        } catch (CharacterCodingException e) {
            return null;
        } catch (IOException e) {
            // Reading from a ByteArrayInputStream cannot actually throw, but keep the signature honest.
            return null;
        }
    }
}
