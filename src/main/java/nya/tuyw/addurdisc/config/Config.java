package nya.tuyw.addurdisc.config;

/**
 * Plain data mirror of {@code config/AddurDisc-common.json}. {@link ConfigHandler} clamps every
 * field on the way out, so a hand-edited value can never switch off a safety limit.
 */
public class Config {
    public String customPath = "";
    public String customPathName = ".addurdisc";
    /**
     * Fallback song length (in seconds) used when the length of an .ogg file
     * cannot be read from its Vorbis headers.
     */
    public float defaultSongLengthSeconds = 180.0F;
    /**
     * Executable used to convert imported music to mono OGG. Leave it empty to
     * look "ffmpeg" up on PATH, or give the full path, e.g. "C:/ffmpeg/bin/ffmpeg.exe".
     * This setting is read by the server when a player imports a track.
     */
    public String ffmpegPath = "";
    /** Largest single track the server stores, in megabytes. */
    public int maxSongMegabytes = 32;
    /** Largest total size of the music folder, in megabytes. */
    public int maxStoreMegabytes = 512;
    /** Largest number of tracks the server stores. */
    public int maxTracks = 512;
}
