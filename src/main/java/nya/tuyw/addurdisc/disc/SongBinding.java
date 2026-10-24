package nya.tuyw.addurdisc.disc;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import nya.tuyw.addurdisc.init.ModInitialize;
import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.config.ConfigUtil;
import nya.tuyw.addurdisc.init.DiscResources;
import nya.tuyw.addurdisc.util.Utf8;

/** A normalized per-disc song component, persisted and synchronized without registering each track. */
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

    public static final StreamCodec<RegistryFriendlyByteBuf, SongBinding> PACKET_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(DiscProtocol.NAME_CHARS), SongBinding::sound,
            ByteBufCodecs.FLOAT, SongBinding::lengthInSeconds,
            ByteBufCodecs.stringUtf8(DiscProtocol.TITLE_CHARS), SongBinding::title,
            SongBinding::new
    );

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

    public static SongBinding fromStack(ItemStack stack) { return stack.get(ModInitialize.DISC_SONG); }
    public void applyTo(ItemStack stack) { stack.set(ModInitialize.DISC_SONG,this); }
}
