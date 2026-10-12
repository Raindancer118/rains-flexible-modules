package de.raindancer.modules.ranks;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.ranks.service.RankService;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

/** What this module built, handed to its command and screens. */
public record RanksServices(
        Plugin plugin,
        Server server,
        RainsCore core,
        LogChannel log,
        Messages messages,
        Brand brand,
        RankService ranks) {
}
