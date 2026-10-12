package de.raindancer.modules.enchants;

import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Server;

/** The collaborators the commands and listeners are handed. */
public record EnchantsServices(Server server, Messages messages) {
}
