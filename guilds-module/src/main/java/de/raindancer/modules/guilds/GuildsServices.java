package de.raindancer.modules.guilds;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.guilds.service.BankService;
import de.raindancer.modules.guilds.service.GuildChatService;
import de.raindancer.modules.guilds.service.GuildNotifier;
import de.raindancer.modules.guilds.service.GuildService;
import de.raindancer.modules.guilds.service.HomeService;
import de.raindancer.modules.guilds.service.PositionService;
import de.raindancer.modules.guilds.service.PerkService;
import de.raindancer.modules.guilds.service.RelationService;
import de.raindancer.modules.guilds.service.XpService;
import de.raindancer.modules.guilds.store.GuildBook;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/** What this module built, handed to its commands, listeners and screens. */
public record GuildsServices(
        Plugin plugin,
        Server server,
        RainsCore core,
        LogChannel log,
        Messages messages,
        Brand brand,
        Supplier<GuildsSettings> settings,
        GuildBook book,
        GuildNotifier notifier,
        GuildService guilds,
        BankService bank,
        RelationService relations,
        XpService xp,
        HomeService homes,
        GuildChatService chat,
        PositionService positions,
        PerkService perks) {
}
