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
import nya.tuyw.addurdisc.Config.ConfigUtil;
import nya.tuyw.addurdisc.disc.CustomDiscItem;
import nya.tuyw.addurdisc.disc.SongBinding;
import nya.tuyw.addurdisc.util.OggVorbis;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;

public class ModInitialize {
    public static final Path path;
    public static final Path sounds;
    public static final Path models;
    public static final Path itemDefinitions;
    public static final Path textures;
    public static final Path lang;
    public static final Path data;

    static {
        Path basePath = ConfigUtil.getResourcePath();

        path = basePath.resolve("assets/addurdisc");
        sounds = path.resolve("sounds");
        // Since 1.21.4 the "models/item" folder holds the actual model, while "items" holds the
        // item model definition that points at it.
        models = path.resolve("models/item");
        itemDefinitions = path.resolve("items");
        textures = path.resolve("textures/item");
        lang = path.resolve("lang");
        // Item tags live in "tags/item" (singular) since 1.21.
        data = basePath.resolve("data/minecraft/tags/item");

        initializeResourcesPath();
        initializePackmcmeta();
    }

    public static ArrayList<String> loadedSoundsNames;
    public static ArrayList<Item> addedDiscs;
    public static ArrayList<SoundEvent> loadedSounds;
    public static InputStream Stream;
    /** Binding component every imported track is stored on. */
    public static DataComponentType<SongBinding> DISC_SONG;
    /** The single disc item shared by all imported tracks. */
    public static Item CUSTOM_DISC;

    private static void initializePackmcmeta() {
        Generater.GenPackmcmeta(ConfigUtil.getResourcePath());
        AddurDisc.LOGGER.debug("pack.mcmeta initialization is complete");
    }

    private static void initializeResourcesPath() {
        checkResourcesPath(path);
        checkResourcesPath(sounds);
        checkResourcesPath(models);
        checkResourcesPath(itemDefinitions);
        checkResourcesPath(textures);
        checkResourcesPath(lang);
        checkResourcesPath(data);
        AddurDisc.LOGGER.debug("resources path all ready");
    }

    private static void initializeSounds() {
        loadedSoundsNames = DiscResources.scanSoundNames();
        DiscResources.writePackFiles(loadedSoundsNames);
        AddurDisc.LOGGER.debug("Loaded files:" + loadedSoundsNames);
        AddurDisc.LOGGER.debug("Sounds are all ready");
        RegisterSounds();
        RegisterItems();
        RegisterTabs();
    }

    /**
     * Registers the one disc item that every imported track shares. The item registry is frozen
     * once bootstrapping is over, which is why an import can only rebind this item instead of
     * registering a disc of its own.
     */
    private static void registerCustomDisc() {
        Identifier componentId = Identifier.fromNamespaceAndPath(AddurDisc.MODID, "disc_song");
        DISC_SONG = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, componentId,
                DataComponentType.<SongBinding>builder()
                        .persistent(SongBinding.CODEC)
                        .networkSynchronized(SongBinding.PACKET_CODEC)
                        .cacheEncoding()
                        .build());

        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM,
                Identifier.fromNamespaceAndPath(AddurDisc.MODID, "custom_disc"));

        // Vanilla gates jukebox insertion on the mere presence of this component, so the item
        // carries a placeholder song and JukeboxSongMixin swaps in the imported track. A blank disc
        // shows the placeholder's description, which is the "right click to add a song" hint.
        SoundEvent silence = SoundEvent.createVariableRangeEvent(
                Identifier.fromNamespaceAndPath(AddurDisc.MODID, "custom_disc_placeholder"));
        JukeboxSong placeholder = new JukeboxSong(Holder.direct(silence),
                Component.translatable("item." + AddurDisc.MODID + ".custom_disc.desc"),
                0.0F, SongBinding.COMPARATOR_OUTPUT);

        CUSTOM_DISC = Registry.register(BuiltInRegistries.ITEM, key, new CustomDiscItem(new Item.Properties()
                .setId(key)
                .stacksTo(1)
                .rarity(Rarity.RARE)
                .component(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(Holder.direct(placeholder)))));
    }

    public ModInitialize() {
        Stream = getClass().getClassLoader().getResourceAsStream("custom.png");
        registerCustomDisc();
        initializeSounds();
    }

    private static void RegisterSounds() {
        loadedSounds = new ArrayList<>();
        for (String name : loadedSoundsNames) {
            Identifier id = Identifier.fromNamespaceAndPath(AddurDisc.MODID, name);
            SoundEvent soundEvent = SoundEvent.createVariableRangeEvent(id);
            Registry.register(BuiltInRegistries.SOUND_EVENT, id, soundEvent);
            loadedSounds.add(soundEvent);
        }
    }

    private static void RegisterItems() {
        addedDiscs = new ArrayList<>();
        for (SoundEvent soundEvent : loadedSounds) {
            Identifier soundLocation = soundEvent.location();
            String name = soundLocation.getPath();
            Identifier itemId = Identifier.fromNamespaceAndPath(AddurDisc.MODID, "disc_" + name);
            // Since 26.2 item ids are stored explicitly, separately from the Item instance, and
            // Item.Properties refuses to build an item without one.
            ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, itemId);

            // A Holder.Reference keeps the built-in sound registry entry intact, while the
            // JukeboxSong itself stays outside the (datapack driven) jukebox_song registry.
            Holder<SoundEvent> soundHolder = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(soundEvent);

            File ogg = sounds.resolve(name + ".ogg").toFile();
            float lengthInSeconds = OggVorbis.readDurationSeconds(ogg, ConfigUtil.getDefaultSongLengthSeconds());

            JukeboxSong song = new JukeboxSong(
                    soundHolder,
                    Component.translatable("item." + AddurDisc.MODID + ".disc_" + name + ".desc"),
                    lengthInSeconds,
                    SongBinding.COMPARATOR_OUTPUT
            );
            Holder<JukeboxSong> songHolder = Holder.direct(song);

            Item discItem = new Item(new Item.Properties()
                    .setId(itemKey)
                    .stacksTo(1)
                    .rarity(Rarity.RARE)
                    .component(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(songHolder)));

            Registry.register(BuiltInRegistries.ITEM, itemKey, discItem);
            addedDiscs.add(discItem);
        }
    }

    private static void RegisterTabs() {
        CreativeModeTab creativeModeTab = CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                .icon(() -> new ItemStack(Items.JUKEBOX))
                .title(Component.translatable("addurdisc_creativetab"))
                .displayItems((params, output) -> {
                    output.accept(CUSTOM_DISC);
                    for (Item item : addedDiscs) {
                        output.accept(item);
                    }
                })
                .build();
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(AddurDisc.MODID, "addurdisc_creativetab"), creativeModeTab);
    }

    private static void checkResourcesPath(Path p) {
        if (!p.toFile().exists()) {
            p.toFile().mkdirs();
        }
    }
}
