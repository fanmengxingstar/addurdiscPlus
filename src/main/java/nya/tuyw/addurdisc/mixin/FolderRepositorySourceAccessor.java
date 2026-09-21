package nya.tuyw.addurdisc.mixin;

import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the pack type of vanilla's folder repository source so that AddurDisc can tell
 * the client's resource-pack repository apart from the server's data-pack repository.
 */
@Mixin(FolderRepositorySource.class)
public interface FolderRepositorySourceAccessor {
    @Accessor("packType")
    PackType addurdisc$getPackType();
}
