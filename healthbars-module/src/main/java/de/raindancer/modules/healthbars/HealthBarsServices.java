package de.raindancer.modules.healthbars;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.healthbars.service.HealthBarService;
import org.bukkit.Server;

/** The collaborators the command and listener are handed. */
public record HealthBarsServices(Server server, Messages messages, HealthBarService bars) {
}
