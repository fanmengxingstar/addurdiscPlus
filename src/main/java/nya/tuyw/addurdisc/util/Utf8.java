package nya.tuyw.addurdisc.util;

import java.nio.charset.StandardCharsets;

/**
 * Length helpers for the strings that travel over the network.
 *
 * <p>Minecraft limits every string it writes to a packet by its <em>UTF-8 byte</em> length, while
 * Java counts characters. The two only agree for ASCII: one Chinese character is three bytes, so a
 * title cut to a character limit can be twice the size the codec accepts, and encoding it throws
 * instead of sending. Everything that is bounded has to be cut to bytes here.</p>
 */
public final class Utf8 {

    /**
     * @return {@code value} shortened to at most {@code maxBytes} UTF-8 bytes, never splitting a
     * multi byte character or a surrogate pair, so the result is always encodable within that
     * limit; an empty string when there is no room for anything
     */
    public static String truncate(String value, int maxBytes) {
        if (value == null || value.isEmpty() || maxBytes <= 0) {
            return "";
        }
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }
        int bytes = 0;
        int end = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int codePointBytes = byteLength(codePoint);
            if (bytes + codePointBytes > maxBytes) {
                break;
            }
            bytes += codePointBytes;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }

    private static int byteLength(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }

    private Utf8() {
    }
}
