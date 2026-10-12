package de.raindancer.modules.arena.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.arena.model.Fight;
import de.raindancer.modules.arena.rules.PairingRule;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * "[Rematch]" after a fight: once everybody in it has asked within a minute, the same fight goes ahead of the queue
 * like an accepted invite — same sides, kit, rounds and stake, every check an invite gets.
 */
public final class RematchService implements IArenaService {

    private final Rematches book = new Rematches();
    private final Server server;
    private final Messages messages;
    private final QueueService queue;
    private final LongSupplier clock;

    public RematchService(Server server, Messages messages, QueueService queue, LongSupplier clock) {
        this.server = server;
        this.messages = messages;
        this.queue = queue;
        this.clock = clock;
    }

    @Override
    public void settings(de.raindancer.modules.arena.ArenaSettings settings) {
        // Nothing read from the settings: a rematch is the fight that was, as it was.
    }

    /** A fight ended with a result. */
    public void finished(Fight fight) {
        if (!Rematches.offered(fight.spec(), fight.players().size())) {
            return;
        }
        book.offer(fight.sides(), fight.spec(), clock.getAsLong());
        for (UUID member : fight.players()) {
            Player player = server.getPlayer(member);
            if (player != null) {
                messages.send(player, "arena.rematch.offer", "others", names(fight.players(), member));
            }
        }
    }

    /** {@code /pvp rematch}, and the button. */
    public void ask(Player player) {
        UUID id = player.getUniqueId();
        Rematches.Answer answer = book.ask(id, clock.getAsLong());
        switch (answer.kind()) {
            case NONE -> messages.send(player, "arena.rematch.none");
            case EXPIRED -> messages.send(player, "arena.rematch.expired");
            case WAITING -> {
                PairingRule.Group group = answer.group();
                if (anybodyGone(group)) {
                    book.forget(id);
                    messages.send(player, "arena.rematch.gone");
                    return;
                }
                queue.agreed(id, group.spec());
                messages.send(player, "arena.rematch.waiting", "others", names(answer.missing(), id));
                answer.missing().stream().map(server::getPlayer).filter(Objects::nonNull).forEach(other ->
                        messages.send(other, "arena.rematch.asked", "player", player.getName()));
            }
            case READY -> {
                PairingRule.Group group = answer.group();
                if (anybodyGone(group)) {
                    messages.send(player, "arena.rematch.gone");
                    return;
                }
                queue.agreed(id, group.spec());
                group.players().stream().map(server::getPlayer).filter(Objects::nonNull)
                        .forEach(member -> messages.send(member, "arena.rematch.starting"));
                queue.reserve(group);
            }
        }
    }

    public void forget(UUID player) {
        Set<UUID> others = book.forget(player);
        others.stream().map(server::getPlayer).filter(Objects::nonNull)
                .forEach(other -> messages.send(other, "arena.rematch.left"));
    }

    private boolean anybodyGone(PairingRule.Group group) {
        return group.players().stream().anyMatch(member -> server.getPlayer(member) == null);
    }

    private String names(Set<UUID> players, UUID except) {
        return players.stream().filter(member -> !member.equals(except)).map(member -> {
            Player online = server.getPlayer(member);
            return online == null ? "somebody who left" : online.getName();
        }).sorted().collect(Collectors.joining(", "));
    }
}
