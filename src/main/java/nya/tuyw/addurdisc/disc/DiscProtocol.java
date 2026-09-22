package nya.tuyw.addurdisc.disc;

/**
 * The few numbers the disc protocol, the generated pack and the item all have to agree on. They
 * live here because a change that lands on one side only shows up as a corrupt transfer or a silent
 * jukebox in game, which is not where anyone wants to discover it.
 */
public final class DiscProtocol {
    /**
     * Chunk payload size going up. A serverbound custom payload rides an unknown-channel packet, and
     * vanilla caps those at 32767 bytes, so anything larger is dropped or read back wrong. A single
     * player never sees this because the integrated server is talked to in memory, which is exactly
     * what makes the mistake easy to ship.
     */
    public static final int UPLOAD_CHUNK_BYTES = 16 * 1024;

    /**
     * Chunk payload size coming down. The cap above only binds going up, and a whole song is pushed
     * out in one server tick, so a download chunk stays as wide as it always was to keep the packet
     * count down.
     */
    public static final int DOWNLOAD_CHUNK_BYTES = 128 * 1024;

    /**
     * Tracks per {@code SongList}. The library goes out as several of these rather than one message,
     * because a collection codec has to declare its ceiling and a ceiling the sender can exceed
     * means a player who can never join the server again.
     */
    public static final int SONG_PAGE_SIZE = 64;

    /** Longest track name; matches {@code DiscResources.SOUND_NAME}. */
    public static final int NAME_CHARS = 64;
    /** Longest player-visible track title. */
    public static final int TITLE_CHARS = 128;
    /** Length of a lowercase hex sha1. */
    public static final int SHA1_CHARS = 40;

    /**
     * Blocks a track carries from its jukebox. Pinned into the generated {@code sounds.json} so the
     * sound engine and {@link #ANNOUNCE_BLOCKS} cannot drift apart.
     */
    public static final int AUDIBLE_BLOCKS = 16;

    /**
     * Blocks around a jukebox whose players are told to play. Wider than {@link #AUDIBLE_BLOCKS} so
     * someone walking towards a playing jukebox already has the sound and simply fades in.
     */
    public static final int ANNOUNCE_BLOCKS = 64;

    private DiscProtocol() {
    }
}
