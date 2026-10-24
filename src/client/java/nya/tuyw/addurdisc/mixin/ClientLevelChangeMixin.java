package nya.tuyw.addurdisc.mixin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import nya.tuyw.addurdisc.disc.DiscClientSounds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Clear before the new dimension can receive playback packets, including mid-tick changes. */
@Mixin(Minecraft.class)
public class ClientLevelChangeMixin {
    @Inject(method="setLevel",at=@At("HEAD"))
    private void addurdisc$changeLevel(ClientLevel level,net.minecraft.client.gui.screens.ReceivingLevelScreen.Reason reason,CallbackInfo ci) { DiscClientSounds.clear(); }
}
