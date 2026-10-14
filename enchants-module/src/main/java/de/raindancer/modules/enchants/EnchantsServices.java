package de.raindancer.modules.enchants;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.enchants.service.ShardMobService;
import org.bukkit.Server;

/** The collaborators the commands and listeners are handed. */
public record EnchantsServices(Server server, Messages messages, ShardMobService shardMobs) {
}
