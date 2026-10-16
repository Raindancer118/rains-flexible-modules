package de.raindancer.modules.ctf;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.hologram.FloatingBoards;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.ctf.service.AnnouncerService;
import de.raindancer.modules.ctf.service.ArenaWorldService;
import de.raindancer.modules.ctf.service.FlagService;
import de.raindancer.modules.ctf.service.LobbyService;
import de.raindancer.modules.ctf.service.MapSetupService;
import de.raindancer.modules.ctf.service.PartyService;
import de.raindancer.modules.ctf.service.ProgressService;
import de.raindancer.modules.ctf.service.RoleService;
import de.raindancer.modules.ctf.service.RoundService;
import de.raindancer.modules.ctf.service.SpectatorService;
import de.raindancer.modules.ctf.service.VoteService;
import de.raindancer.modules.ctf.store.MapStore;
import de.raindancer.modules.ctf.store.StatsStore;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/** The collaborators the commands, listeners and screens are handed. */
public record CtfServices(Plugin plugin, Server server, LogChannel log, Messages messages, Brand brand, RainsCore core,
                          Supplier<CtfSettings> config, MapStore maps, ArenaWorldService arena, MapSetupService setup,
                          LobbyService lobby, RoundService rounds, FlagService flags, RoleService roles,
                          AnnouncerService announcer, ProgressService progress, PartyService parties,
                          VoteService votes, SpectatorService spectators, StatsStore stats, FloatingBoards boards,
                          ICtfScreensOpener screens) {
}
