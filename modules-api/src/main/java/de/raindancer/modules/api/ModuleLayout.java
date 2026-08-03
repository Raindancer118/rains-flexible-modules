package de.raindancer.modules.api;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Where a module's files live.
 *
 * <p>Two answers, and which one applies is the whole point of this project:
 *
 * <ul>
 *   <li><b>Its own plugin.</b> {@code plugins/RainsModeration/config.yml} — where somebody who used the
 *       standalone plugin already keeps it, so nothing has to move when they switch.</li>
 *   <li><b>Hosted.</b> {@code plugins/RainsSMPCore/modules/moderation/config.yml} — because the host's own
 *       {@code config.yml} is already in that folder, and two files with one name is not a layout.</li>
 * </ul>
 *
 * <p>The path is built from the module id, which is why {@link ModuleInfo} is strict about ids: an id of
 * {@code ../../..} would resolve outside the plugins directory entirely, and this class would obey.
 */
public final class ModuleLayout {

    /** The folder every hosted module's own folder goes under. */
    private static final String HOSTED_UNDER = "modules";

    private final Path dataRoot;
    private final boolean shared;

    private ModuleLayout(Path dataRoot, boolean shared) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "a layout needs a root to put things under");
        this.shared = shared;
    }

    /** For a module that is the plugin: the data folder is its own. */
    public static ModuleLayout owningFolder(Path dataRoot) {
        return new ModuleLayout(dataRoot, false);
    }

    /** For a module hosted by a plugin, or one of several in a jar: a subfolder of its own. */
    public static ModuleLayout sharedFolder(Path dataRoot) {
        return new ModuleLayout(dataRoot, true);
    }

    /** Whether modules here have to share the folder with a host or with each other. */
    public boolean isShared() {
        return shared;
    }

    /** The root everything is under — the hosting plugin's data folder. */
    public Path dataRoot() {
        return dataRoot;
    }

    public Path folderFor(ModuleInfo info) {
        return folderFor(info.id());
    }

    public Path folderFor(String moduleId) {
        Ids.checkModuleId(moduleId);
        return shared ? dataRoot.resolve(HOSTED_UNDER).resolve(moduleId) : dataRoot;
    }

    public Path configFor(ModuleInfo info) {
        return configFor(info.id());
    }

    public Path configFor(String moduleId) {
        return folderFor(moduleId).resolve("config.yml");
    }
}
