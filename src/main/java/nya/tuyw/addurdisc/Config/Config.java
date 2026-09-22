package nya.tuyw.addurdisc.Config;

public class Config {
    public String customPath = "";
    public String customPathName = ".addurdisc";
    public boolean canDropByCreeper = true;
    public boolean refreshLang = true;
    /**
     * Fallback song length (in seconds) used when the length of an .ogg file
     * cannot be read from its Vorbis headers.
     */
    public float defaultSongLengthSeconds = 180.0F;
    /**
     * Executable used to convert imported music to mono OGG. Leave it empty to
     * look "ffmpeg" up on PATH, or give the full path, e.g. "C:/ffmpeg/bin/ffmpeg.exe".
     */
    public String ffmpegPath = "";
}
