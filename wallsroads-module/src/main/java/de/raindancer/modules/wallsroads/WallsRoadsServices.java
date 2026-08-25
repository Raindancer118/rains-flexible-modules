package de.raindancer.modules.wallsroads;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.selection.MarkingTool;
import de.raindancer.core.world.visual.OutlineRenderer;
import de.raindancer.modules.wallsroads.claims.ClaimLink;
import de.raindancer.modules.wallsroads.map.MapLink;
import de.raindancer.modules.wallsroads.selection.WallsRoadsSelectionFlow;
import de.raindancer.modules.wallsroads.service.WallsRoadsService;
import de.raindancer.modules.wallsroads.store.WallsRoadsRegistry;
import de.raindancer.modules.wallsroads.store.WallsRoadsStorage;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Everything this module has built, in one place, so a listener, a screen or a command can be
 * handed what it needs — see {@code MannequinServices} for why this is a record rather than a god
 * object.
 */
public record WallsRoadsServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        Brand brand,
        RainsCore core,

        Supplier<WallsRoadsSettings> settings,
        SettingsStore<WallsRoadsSettings> store,

        WallsRoadsRegistry registry,
        WallsRoadsStorage storage,
        WallsRoadsService service,
        MarkingTool markingTool,
        OutlineRenderer outline,
        WallsRoadsSelectionFlow selectionFlow,
        IWallsRoadsScreensOpener screens,
        ClaimLink claimLink,
        MapLink mapLink) {

    public WallsRoadsSettings config() {
        return settings.get();
    }
}
