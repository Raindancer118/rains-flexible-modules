package de.raindancer.modules.worldutils;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.worldutils.service.WorldAdminService;
import de.raindancer.modules.worldutils.service.WorldTravelService;
import org.bukkit.Server;

import java.util.function.Supplier;

/**
 * Everything this module has built, in one place, so a command can be handed what it needs.
 *
 * @param settings behind a supplier rather than captured — a reload replaces the snapshot wholesale
 */
public record WorldUtilsServices(
        Server server,
        Messages messages,
        Brand brand,
        Supplier<WorldUtilsSettings> settings,
        WorldTravelService travel,
        WorldAdminService admin) {

    public WorldUtilsSettings config() {
        return settings.get();
    }
}
