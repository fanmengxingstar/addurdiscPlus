package nya.tuyw.addurdisc;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.util.FileUtil;
import net.minecraft.world.level.validation.DirectoryValidator;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Exposes the folder that AddurDisc generates (assets / data / pack.mcmeta) as a real
 * resource-pack repository entry, so the game picks it up without the user having to
 * copy anything into the "resourcepacks" folder.
 */
public class myRepositorySource implements RepositorySource {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The folder only ever contains files that AddurDisc itself generated, so symlink
     * validation is not needed here.
     */
    private static final DirectoryValidator VALIDATOR = new DirectoryValidator(path -> true);

    /** The pack is always enabled and takes priority over the vanilla resources. */
    private static final PackSelectionConfig SELECTION_CONFIG = new PackSelectionConfig(true, Pack.Position.TOP, false);

    protected final Path folder;
    protected final PackType packType;
    protected final PackSource packSource;

    public myRepositorySource(Path pFolder, PackType pPackType, PackSource pPackSource) {
        this.folder = pFolder;
        this.packType = pPackType;
        this.packSource = pPackSource;
    }

    protected static String nameFromPath(Path pPath) {
        return pPath.getFileName().toString();
    }

    @Override
    public void loadPacks(Consumer<Pack> pOnLoad) {
        try {
            FileUtil.createDirectoriesSafe(this.folder);
            // Vanilla's own discovery logic, so both plain folders and .zip packs keep working.
            FolderRepositorySource.discoverPacks(this.folder, VALIDATOR, (path, resources) -> {
                String name = nameFromPath(path);
                PackLocationInfo location = new PackLocationInfo("file/" + name, Component.literal(name), this.packSource, Optional.empty());
                Pack pack = Pack.readMetaAndCreate(location, resources, this.packType, SELECTION_CONFIG);
                if (pack != null) {
                    pOnLoad.accept(pack);
                }
            });
        } catch (IOException ioexception) {
            LOGGER.warn("Failed to list packs in {}", this.folder, ioexception);
        }
    }
}
