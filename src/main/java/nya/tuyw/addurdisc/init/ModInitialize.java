package nya.tuyw.addurdisc.init;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        ResourceLocation componentId = ResourceLocation.fromNamespaceAndPath(AddurDisc.MODID, "disc_song");
        DISC_SONG = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, componentId,
                DataComponentType.<SongBinding>builder()
                        .persistent(SongBinding.CODEC)
                        .networkSynchronized(SongBinding.PACKET_CODEC)
                        .cacheEncoding()
                        .build());

        // This version's EitherHolder requires a registry key and rejects direct holders.
        // Reuse a registered song only as the insertion marker: the hidden placeholder is
        // replaced by JukeboxSongMixin, and blank discs are rejected before insertion.
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath(AddurDisc.MODID, "custom_disc"));
        CUSTOM_DISC = Registry.register(BuiltInRegistries.ITEM, key, new CustomDiscItem(new Item.Properties()
                .setId(key)
                .stacksTo(1)
                .rarity(Rarity.RARE)
                .component(DataComponents.JUKEBOX_PLAYABLE, new ItemStack(Items.MUSIC_DISC_13).get(DataComponents.JUKEBOX_PLAYABLE).withTooltip(false))));

        CreativeModeTab tab = CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                .icon(() -> new ItemStack(Items.JUKEBOX))
                .title(Component.translatable("addurdisc_creativetab"))
                .displayItems((params, output) -> output.accept(CUSTOM_DISC))
                .build();
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
                ResourceLocation.fromNamespaceAndPath(AddurDisc.MODID, "addurdisc_creativetab"), tab);
    }

    /** Forces the registration above, which the mod entrypoint calls before anything else. */
    public static void init() {
    }

    private ModInitialize() {
    }
}
