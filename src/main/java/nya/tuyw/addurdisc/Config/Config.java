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
}
