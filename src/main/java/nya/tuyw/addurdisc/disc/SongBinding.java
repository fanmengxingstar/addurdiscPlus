package nya.tuyw.addurdisc.disc;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.config.ConfigUtil;
import nya.tuyw.addurdisc.init.DiscResources;
import nya.tuyw.addurdisc.util.Utf8;

/** A normalized runtime song stored in legacy item NBT and synchronized by vanilla. */
public record SongBinding(String sound, float lengthInSeconds, String title) {
    /** Comparator output a jukebox reports while one of these discs plays. */
    public static final int COMPARATOR_OUTPUT = 15;
    /** Longest song the jukebox will count down for; anything past it is a broken file. */
    private static final float MAX_LENGTH_SECONDS = 7200.0F;

    public static final Codec<SongBinding> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("sound").forGetter(SongBinding::sound),
            Codec.FLOAT.fieldOf("length_in_seconds").forGetter(SongBinding::lengthInSeconds),
            Codec.STRING.optionalFieldOf("title", "").forGetter(SongBinding::title)
    ).apply(instance, SongBinding::new));


    public SongBinding {
        // Both fields are capped in UTF-8 bytes rather than characters: the lengths are the same for
        // an ASCII track name, but a Chinese title costs three bytes per character and a
        // character count would let it through at up to three times the codec's limit.
        sound = Utf8.truncate(sound, DiscProtocol.NAME_CHARS);
        String plain = title == null ? "" : ChatFormatting.stripFormatting(title);
        title = Utf8.truncate(plain, DiscProtocol.TITLE_CHARS);
        if (!Float.isFinite(lengthInSeconds) || lengthInSeconds <= 0.0F) {
            lengthInSeconds = ConfigUtil.getDefaultSongLengthSeconds();
        } else if (lengthInSeconds > MAX_LENGTH_SECONDS) {
            lengthInSeconds = MAX_LENGTH_SECONDS;
        }
    }

    /** @return whether this names a track at all, which is what makes the disc playable */
    public boolean isBound() {
        return DiscResources.isValidName(sound);
    }

    public ResourceLocation soundId() {
        return new ResourceLocation(AddurDisc.MODID, sound);
    }

    /**
     * @return what the player called the track, or the bare file name for a disc that was imported
     * without a title
     */
    public Component description() {
        return title.isBlank() ? Component.literal(sound) : Component.literal(title);
    }

    /** Vanilla persists and synchronizes this NBT with the ItemStack. */
    public static SongBinding fromStack(ItemStack stack) {
        CompoundTag root = stack.getTag();
        if (root == null || !root.contains("addurdisc_song", 10)) return null;
        CompoundTag tag = root.getCompound("addurdisc_song");
        return new SongBinding(tag.getString("sound"), tag.getFloat("length_in_seconds"), tag.getString("title"));
    }
    public void applyTo(ItemStack stack) {
        CompoundTag tag = new CompoundTag();
        tag.putString("sound", sound);
        tag.putFloat("length_in_seconds", lengthInSeconds);
        tag.putString("title", title);
        stack.getOrCreateTag().put("addurdisc_song", tag);
    }
}
