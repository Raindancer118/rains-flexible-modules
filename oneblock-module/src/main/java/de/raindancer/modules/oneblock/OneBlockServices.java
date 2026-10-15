package de.raindancer.modules.oneblock;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.oneblock.service.InviteService;
import de.raindancer.modules.oneblock.service.IslandServer;
import de.raindancer.modules.oneblock.service.IslandsGateway;
import de.raindancer.modules.oneblock.service.MigrationService;
import de.raindancer.modules.oneblock.service.SidebarService;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Everything this module has built, in one place.
 *
 * @param invites      present on the island server only
 * @param sidebar      present on the island server only
 * @param islandServer the islands themselves — present on the island server only
 * @param migration    moving BentoBox's islands over — present on the island server only
 */
public record OneBlockServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        RainsCore core,
        Supplier<OneBlockSettings> settings,
        IslandsGateway gateway,
        Optional<InviteService> invites,
        Optional<SidebarService> sidebar,
        Optional<IslandServer> islandServer,
        Optional<MigrationService> migration) {

    public OneBlockSettings config() {
        return settings.get();
    }
}
