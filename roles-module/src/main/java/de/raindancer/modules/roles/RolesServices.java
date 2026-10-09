package de.raindancer.modules.roles;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.modules.roles.service.RoleService;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/** What this module built, handed to its command, listener and screens. */
public record RolesServices(
        Plugin plugin,
        Server server,
        RainsCore core,
        LogChannel log,
        Messages messages,
        Brand brand,
        Supplier<RolesSettings> settings,
        RoleService roles) {
}
