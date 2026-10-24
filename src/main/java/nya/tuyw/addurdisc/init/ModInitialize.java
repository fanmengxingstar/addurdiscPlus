package nya.tuyw.addurdisc.init;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.Rarity;
import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.disc.CustomDiscItem;
import nya.tuyw.addurdisc.disc.SongBinding;

/**
 * Registry side of the mod: one data component, one disc item, one creative tab. Tracks themselves
 * are never registered — an imported song is only a file in the generated pack plus a component
 * value on a disc, which is what lets an import work long after the item registry has frozen.
 *
 * <p>Registration lives in the class initialiser, which is where Fabric mods conventionally do it:
 * the fields are final, the work happens the first time anything touches them, and
 * {@link #init()} exists so the mod entrypoint can state that ordering out loud instead of
 * disguising it as a constructor call.</p>
 */
public final class ModInitialize {
    /** Binding component every imported track is stored on. */
    public static final DataComponentType<SongBinding> DISC_SONG;
    /** The single disc item shared by all imported tracks. */
    public static final Item CUSTOM_DISC;

    static {
        DiscResources.bootstrap();
        Identifier componentId = Identifier.fromNamespaceAndPath(AddurDisc.MODID, "disc_song");
        DISC_SONG = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, componentId,
                DataComponentType.<SongBinding>builder()
                        .persistent(SongBinding.CODEC)
                        .networkSynchronized(SongBinding.PACKET_CODEC)
                        .cacheEncoding()
                        .build());

        // Vanilla gates jukebox insertion on the mere presence of DataComponents.JUKEBOX_PLAYABLE
        // and the item registry is frozen once bootstrapping is over, so the item carries a
        // placeholder song that JukeboxSongMixin swaps for the imported track. A blank disc shows
        // the placeholder's description, which is the "right click to add a song" hint. The
        // placeholder's SoundEvent is never registered and never heard: a blank disc cannot be put
        // in a jukebox at all, and a bound one resolves through the mixin instead. There is no
        // silent vanilla sound event to point it at, so it is an unregistered name of our own.
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM,
                Identifier.fromNamespaceAndPath(AddurDisc.MODID, "custom_disc"));
        SoundEvent placeholderSound = SoundEvent.createVariableRangeEvent(
                Identifier.fromNamespaceAndPath(AddurDisc.MODID, "custom_disc_placeholder"));
        JukeboxSong placeholder = new JukeboxSong(Holder.direct(placeholderSound),
                Component.translatable("item." + AddurDisc.MODID + ".custom_disc.desc"),
                0.0F, SongBinding.COMPARATOR_OUTPUT);
        CUSTOM_DISC = Registry.register(BuiltInRegistries.ITEM, key, new CustomDiscItem(new Item.Properties()
                .setId(key)
                .stacksTo(1)
                .rarity(Rarity.RARE)
                .component(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(new net.minecraft.world.item.EitherHolder<>(Holder.direct(placeholder))))));

        CreativeModeTab tab = CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                .icon(() -> new ItemStack(Items.JUKEBOX))
                .title(Component.translatable("addurdisc_creativetab"))
                .displayItems((params, output) -> output.accept(CUSTOM_DISC))
                .build();
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
                Identifier.fromNamespaceAndPath(AddurDisc.MODID, "addurdisc_creativetab"), tab);
    }

    /** Forces the registration above, which the mod entrypoint calls before anything else. */
    public static void init() {
    }

    private ModInitialize() {
    }
}
