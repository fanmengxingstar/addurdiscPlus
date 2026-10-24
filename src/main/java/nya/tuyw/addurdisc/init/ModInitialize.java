package nya.tuyw.addurdisc.init;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.*;
import nya.tuyw.addurdisc.AddurDisc;
import nya.tuyw.addurdisc.disc.CustomDiscItem;
public final class ModInitialize {
    public static final SoundEvent PLACEHOLDER = Registry.register(BuiltInRegistries.SOUND_EVENT,
            new ResourceLocation(AddurDisc.MODID, "custom_disc_placeholder"),
            SoundEvent.createVariableRangeEvent(new ResourceLocation(AddurDisc.MODID, "custom_disc_placeholder")));
    public static final Item CUSTOM_DISC;
    static {
        DiscResources.bootstrap();
        CUSTOM_DISC = Registry.register(BuiltInRegistries.ITEM, new ResourceLocation(AddurDisc.MODID, "custom_disc"),
                new CustomDiscItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB,
                new ResourceLocation(AddurDisc.MODID, "addurdisc_creativetab"), FabricItemGroup.builder()
                .icon(() -> new ItemStack(Items.JUKEBOX)).title(Component.translatable("addurdisc_creativetab"))
                .displayItems((params, output) -> output.accept(CUSTOM_DISC)).build());
    }
    public static void init() { }
    private ModInitialize() { }
}
