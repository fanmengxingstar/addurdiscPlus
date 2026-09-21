package nya.tuyw.addurdisc;

import net.fabricmc.api.ModInitializer;
import nya.tuyw.addurdisc.Config.ConfigUtil;
import nya.tuyw.addurdisc.init.ModInitialize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AddurDisc implements ModInitializer {
	public static final String MODID = "addurdisc";
    public static final Logger LOGGER = LoggerFactory.getLogger("addurdisc");

	@Override
	public void onInitialize() {
		new ConfigUtil();
		new ModInitialize();
	}
}
