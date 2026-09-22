package nya.tuyw.addurdisc;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import nya.tuyw.addurdisc.disc.net.DiscNetwork;
import nya.tuyw.addurdisc.init.ModInitialize;
import org.slf4j.Logger;

public class AddurDisc implements ModInitializer {
    public static final String MODID = "addurdisc";
    public static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        new ModInitialize();
        DiscNetwork.register();
    }
}
