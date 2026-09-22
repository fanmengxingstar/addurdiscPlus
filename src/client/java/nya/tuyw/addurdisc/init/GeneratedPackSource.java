package nya.tuyw.addurdisc.init;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import nya.tuyw.addurdisc.AddurDisc;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Exposes the folder this mod generates as one resource pack.
 *
 * <p>The folder is named by the config and holds nothing but what {@link DiscResources} writes, so
 * the pack is handed over directly instead of letting vanilla scan a directory for it. A scan would
 * read every pack the player ever installed, and it has to be pointed at the <em>parent</em> of the
 * pack to find it, which for a folder in the game directory means the whole game directory.</p>
 */
public final class GeneratedPackSource implements RepositorySource {
    /** One instance for the whole session, so a repository can tell it already has it. */
    public static final GeneratedPackSource INSTANCE = new GeneratedPackSource();

    private static final PackLocationInfo LOCATION = new PackLocationInfo("file/addurdisc",
            Component.translatable("addurdisc.pack.generated"), PackSource.BUILT_IN, Optional.empty());

    /** Always on, and on top of the vanilla resources so a track of the same name wins. */
    private static final PackSelectionConfig SELECTION =
            new PackSelectionConfig(true, Pack.Position.TOP, false);

    @Override
    public void loadPacks(Consumer<Pack> onLoad) {
        DiscResources.ensurePackMetadata();
        Pack pack = Pack.readMetaAndCreate(LOCATION,
                new PathPackResources.PathResourcesSupplier(DiscResources.root()), PackType.CLIENT_RESOURCES, SELECTION);
        if (pack == null) {
            AddurDisc.LOGGER.warn("The generated music pack at {} could not be read.", DiscResources.root());
            return;
        }
        onLoad.accept(pack);
    }

    private GeneratedPackSource() {
    }
}
