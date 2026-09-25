package nya.tuyw.addurdisc.disc.audio;

import nya.tuyw.addurdisc.disc.DiscProtocol;
import nya.tuyw.addurdisc.init.DiscResources;
import nya.tuyw.addurdisc.util.Utf8;

import java.text.Normalizer;
import java.util.Locale;

/** Names an imported source consistently on the client and server. */
public final class TrackMetadata {
    public static String suggestedName(String fileName) {
        String base = fileName;
        String extension = extensionOf(fileName);
        if (!extension.isEmpty()) {
            base = base.substring(0, base.length() - extension.length() - 1);
        }
        StringBuilder name = new StringBuilder(base.length());
        for (char c : Normalizer.normalize(base, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).toCharArray()) {
            boolean legal = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (legal) {
                name.append(c);
            } else if (name.length() > 0 && name.charAt(name.length() - 1) != '_') {
                name.append('_');
            }
        }
        trimSeparators(name);
        if (name.length() > 48) {
            name.setLength(48);
            trimSeparators(name);
        }
        if (name.length() == 0) {
            name.append("disc-").append(shortHash(fileName));
        }
        String candidate = name.toString();
        return DiscResources.isValidName(candidate) ? candidate : "disc-" + shortHash(fileName);
    }

    /** @return the extension-free title, bounded to the packet codec's UTF-8 byte limit */
    public static String title(String fileName) {
        String extension = extensionOf(fileName);
        String base = extension.isEmpty() ? fileName : fileName.substring(0, fileName.length() - extension.length() - 1);
        String flat = base.replaceAll("\\p{Cntrl}", " ").strip();
        String capped = flat.length() <= 64 ? flat : flat.substring(0, 64).strip();
        return Utf8.truncate(capped, DiscProtocol.TITLE_CHARS);
    }

    private static void trimSeparators(StringBuilder name) {
        int end = name.length();
        while (end > 0 && (name.charAt(end - 1) == '.' || name.charAt(end - 1) == '_' || name.charAt(end - 1) == '-')) {
            end--;
        }
        name.setLength(end);
        int start = 0;
        while (start < name.length() && (name.charAt(start) == '.' || name.charAt(start) == '_' || name.charAt(start) == '-')) {
            start++;
        }
        name.delete(0, start);
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extension.matches("[a-z0-9]{1,8}") ? extension : "";
    }

    private TrackMetadata() {
    }
}
