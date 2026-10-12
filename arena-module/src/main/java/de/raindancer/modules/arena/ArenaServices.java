package de.raindancer.modules.arena;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.arena.service.ArenaLink;
import de.raindancer.modules.arena.service.ArenaService;
import de.raindancer.modules.arena.service.IntentBook;
import de.raindancer.modules.arena.service.InviteService;
import de.raindancer.modules.arena.service.KitService;
import de.raindancer.modules.arena.service.MatchService;
import de.raindancer.modules.arena.service.NpcService;
import de.raindancer.modules.arena.service.QueueService;
import de.raindancer.modules.arena.service.WaveService;
import de.raindancer.modules.arena.store.StatsStore;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.function.Supplier;

/** Everything this module has built, in one place, so a listener, screen or command can be handed what it needs. */
public record ArenaServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        Brand brand,
        RainsCore core,
        Supplier<ArenaSettings> settings,
        ArenaService arena,
        MatchService matches,
        QueueService queue,
        InviteService invites,
        KitService kits,
        WaveService waves,
        NpcService npcs,
        StatsStore stats,
        ArenaLink link,
        IntentBook intents,
        IArenaScreensOpener screens,
        de.raindancer.modules.arena.service.BoardService boards) {

    public ArenaSettings config() {
        return settings.get();
    }

    /** Whether somebody is in a running fight — hurt, hunger and the like are theirs to have then. */
    public boolean fighting(UUID player) {
        return matches.isFighting(player);
    }
}
