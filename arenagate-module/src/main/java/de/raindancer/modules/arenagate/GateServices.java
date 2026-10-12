package de.raindancer.modules.arenagate;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.arenagate.service.GateService;
import org.bukkit.plugin.Plugin;

/** Everything this module has built, in one place. */
public record GateServices(Plugin plugin, Messages messages, GateService gate) {
}
