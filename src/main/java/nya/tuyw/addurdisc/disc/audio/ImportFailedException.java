package nya.tuyw.addurdisc.disc.audio;

import net.minecraft.network.chat.Component;

/**
 * Thrown when a picked file cannot become a playable disc.
 *
 * <p>{@link #key()} is a lang key so the player sees the failure in their own language, while
 * {@link #getMessage()} keeps the raw detail for the log.</p>
 */
public class ImportFailedException extends Exception {
    public static final String TOO_LARGE_KEY = "addurdisc.upload.too_large";

    /** Used when the failure has no dedicated message of its own. */
    public static final String GENERIC_KEY = "addurdisc.screen.failed";

    private final String key;

    public ImportFailedException(String detail) {
        this(GENERIC_KEY, detail);
    }

    public ImportFailedException(String key, String detail) {
        super(detail);
        this.key = key;
    }

    public String key() {
        return key;
    }

    public Component toComponent() {
        String detail = getMessage();
        return detail == null || detail.isEmpty()
                ? Component.translatable(key)
                : Component.translatable(key).append(detail);
    }
}
