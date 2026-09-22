package nya.tuyw.addurdisc.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import nya.tuyw.addurdisc.init.GeneratedPackSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Adds the generated music pack to the client's own resource repository.
 *
 * <p>{@link PackRepository} serves both halves of the game and keeps no record of which one it
 * belongs to, so the instance is recognised by asking the client which repository it loads resource
 * packs from. Everything else — the datapack repositories of the integrated server included — is
 * left alone.</p>
 */
@Mixin(PackRepository.class)
public class MixinPackRepository {
    @Shadow
    @Final
    @Mutable
    private Set<RepositorySource> sources;

    /**
     * A head injection, because {@code reload} reads {@link #sources} right afterwards and the pack
     * has to be in there before it does.
     */
    @Inject(method = "reload", at = @At("HEAD"))
    private void addurdisc$addGeneratedPack(CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().getResourcePackRepository()
                || this.sources.contains(GeneratedPackSource.INSTANCE)) {
            return;
        }
        Set<RepositorySource> updated = new LinkedHashSet<>(this.sources);
        updated.add(GeneratedPackSource.INSTANCE);
        this.sources = updated;
    }
}
