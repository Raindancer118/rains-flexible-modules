package de.raindancer.modules.arena;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.arena.rules.ProtectionRule;
import de.raindancer.modules.arena.service.ArenaService;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Everything this module has built, in one place, so a listener or command can be handed what it needs. */
public record ArenaServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        RainsCore core,
        Supplier<ArenaSettings> settings,
        ArenaService arena,
        ProtectionRule protection,
        de.raindancer.modules.arena.rules.ItemUseRule itemUse,
        /* Whether somebody is in a running fight — hurt, hunger and the like are theirs to have then. */
        Predicate<UUID> inFight) {

    public ArenaSettings config() {
        return settings.get();
    }

    public boolean fighting(UUID player) {
        return inFight.test(player);
    }
}
