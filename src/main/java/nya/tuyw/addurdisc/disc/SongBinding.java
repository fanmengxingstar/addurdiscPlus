package nya.tuyw.addurdisc.disc;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.JukeboxSong;
import nya.tuyw.addurdisc.AddurDisc;

/**
 * Binds an imported track to a {@link CustomDiscItem}.
 *
 * <p>Deliberately stores only the bare sound name instead of a {@code JukeboxSong} holder: the item
 * registry is frozen once bootstrapping finished, so an imported track can never become a newly
 * registered item, and a song built at runtime is not part of the {@code jukebox_song} datapack
 * registry that vanilla's own components and packets point at.</p>
 */
public record SongBinding(String sound, float lengthInSeconds, String title) {
    /** Comparator output a jukebox reports while one of these discs plays. */
    public static final int COMPARATOR_OUTPUT = 15;

    public static final Codec<SongBinding> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("sound").forGetter(SongBinding::sound),
            Codec.FLOAT.fieldOf("length_in_seconds").forGetter(SongBinding::lengthInSeconds),
            Codec.STRING.optionalFieldOf("title", "").forGetter(SongBinding::title)
    ).apply(instance, SongBinding::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, SongBinding> PACKET_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), SongBinding::sound,
            ByteBufCodecs.FLOAT, SongBinding::lengthInSeconds,
            ByteBufCodecs.stringUtf8(128), SongBinding::title,
            SongBinding::new
    );

    public Identifier soundId() {
        return Identifier.fromNamespaceAndPath(AddurDisc.MODID, sound);
    }

    /**
     * @return what the player called the track, falling back to the generated lang entry of the
     * sound name for a disc that was imported without a title.
     */
    public Component description() {
        return title == null || title.isBlank()
                ? Component.translatable("item." + AddurDisc.MODID + ".disc_" + sound + ".desc")
                : Component.literal(title);
    }

    /**
     * Builds the song a datapack would otherwise have loaded. Both holders are direct, which works
     * locally but leaves the song without the registry id vanilla uses to announce a playback.
     */
    public Holder<JukeboxSong> asSong() {
        SoundEvent event = SoundEvent.createVariableRangeEvent(soundId());
        return Holder.direct(new JukeboxSong(Holder.direct(event), description(), lengthInSeconds, COMPARATOR_OUTPUT));
    }
}
