package nya.tuyw.addurdisc;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import nya.tuyw.addurdisc.disc.DiscPickers;
import nya.tuyw.addurdisc.disc.DiscClientSounds;
import nya.tuyw.addurdisc.disc.net.DiscClientNetwork;
import nya.tuyw.addurdisc.disc.ui.MusicPickerScreen;

public class AddurDiscClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        DiscClientNetwork.register();
        DiscClientSounds.register();
        // Opening a screen straight from Item#use can collide with the input that triggered it, so
        // the screen is handed to the next tick instead.
        DiscPickers.open = (player, hand) -> {
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> client.setScreen(new MusicPickerScreen(hand)));
        };
    }
}
