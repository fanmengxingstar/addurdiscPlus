package nya.tuyw.addurdisc.disc;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;

import java.util.function.BiConsumer;

/**
 * Indirection so the common {@link CustomDiscItem} can open a client screen without a dedicated
 * server ever loading a client class. The client entrypoint replaces {@link #open}.
 */
public final class DiscPickers {
    public static BiConsumer<Player, InteractionHand> open = (player, hand) -> {
    };

    private DiscPickers() {
    }
}
