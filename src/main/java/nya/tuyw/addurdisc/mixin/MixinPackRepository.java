package nya.tuyw.addurdisc.mixin;

import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import nya.tuyw.addurdisc.Config.ConfigUtil;
import nya.tuyw.addurdisc.myRepositorySource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashSet;
import java.util.Set;

@Mixin(PackRepository.class)
public class MixinPackRepository {
    @Shadow
    @Final
    @Mutable
    private Set<RepositorySource> sources;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void addurdisc$addPackSource(CallbackInfo ci) {
        // The generated folder serves both halves of the pack: "assets" for the client resource
        // repository and "data" (the music disc tags) for the server data pack repository, so it
        // is registered with whichever pack type this repository belongs to.
        PackType packType = addurdisc$currentPackType();
        if (packType == null) {
            return;
        }

        new ConfigUtil();
        RepositorySource mySource = new myRepositorySource(ConfigUtil.getNormalPath(), packType, PackSource.BUILT_IN);
        Set<RepositorySource> updated = new LinkedHashSet<>(this.sources);
        updated.add(mySource);
        this.sources = updated;
    }

    /**
     * Vanilla always builds a FolderRepositorySource for the folder it scans, and that source
     * carries the pack type of the repository it belongs to.
     */
    private PackType addurdisc$currentPackType() {
        for (RepositorySource source : this.sources) {
            if (source instanceof FolderRepositorySourceAccessor accessor) {
                return accessor.addurdisc$getPackType();
            }
        }
        // No folder source found: be conservative and leave this repository untouched.
        return null;
    }
}
