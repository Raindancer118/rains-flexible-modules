package de.raindancer.modules.arenagate;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.arenagate.service.GateNetwork;
import de.raindancer.modules.arenagate.service.GateService;
import org.bukkit.plugin.Plugin;

/**
 * Everything this module has built, in one place.
 *
 * @param network  null without a server link — then only {@code /arena} works, and everything else says so
 * @param consents null without a server link, like the network
 */
public record GateServices(Plugin plugin, Messages messages, GateService gate, GateNetwork network,
                           de.raindancer.modules.arenagate.service.GateConsents consents,
                           java.util.function.Supplier<java.util.Set<String>> elsewhere) {
}
