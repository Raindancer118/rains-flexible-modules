package de.raindancer.modules.speedrun.manhunt.tracker;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.speedrun.manhunt.ManhuntServices;
import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import de.raindancer.modules.speedrun.manhunt.util.Threads;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /manhunt give} — a compass for somebody who lost theirs, through the same three services that
 * hand them out at the start.
 *
 * <p>It never makes room: no item is replaced, nothing is dropped at their feet, and a compass they
 * still carry is left alone. A full inventory is refused and said so, because a compass lying on the
 * ground is a compass in the other side's hands.
 */
public final class CompassHandout implements ManhuntServices.Compasses {

    public enum Kind {
        TRACKER, TEAM, STRUCTURE;

        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Kind> parse(String typed) {
            for (Kind kind : values()) {
                if (kind.word().equalsIgnoreCase(typed)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    public enum Outcome { GIVEN, NO_HUNT, SWITCHED_OFF, NOT_THEIRS, ALREADY_HAS, NO_ROOM }

    private final Plugin plugin;
    private final Supplier<Optional<Hunt>> liveHunt;
    private final HuntCompasses compasses;
    private final Messages messages;

    public CompassHandout(Plugin plugin, Supplier<Optional<Hunt>> liveHunt, HuntCompasses compasses,
                          Messages messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.liveHunt = Objects.requireNonNull(liveHunt, "liveHunt");
        this.compasses = Objects.requireNonNull(compasses, "compasses");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    static Outcome decide(boolean hunting, boolean switchedOn, boolean theirs, boolean carries, boolean room) {
        if (!hunting) {
            return Outcome.NO_HUNT;
        }
        if (!switchedOn) {
            return Outcome.SWITCHED_OFF;
        }
        if (!theirs) {
            return Outcome.NOT_THEIRS;
        }
        if (carries) {
            return Outcome.ALREADY_HAS;
        }
        return room ? Outcome.GIVEN : Outcome.NO_ROOM;
    }

    /**
     * With a kind, exactly that compass or the reason why not. Without one, every compass
     * {@code target} is owed and missing — the others are not worth a line each.
     */
    @Override
    public void give(CommandSender sender, Player target, Optional<Kind> kind) {
        give(sender, target, kind, kind.isPresent());
    }

    @Override
    public void giveEverybody(CommandSender sender, Optional<Kind> kind) {
        Hunt hunt = liveHunt.get().orElse(null);
        if (hunt == null) {
            messages.send(sender, "manhunt.give.no-hunt");
            return;
        }
        int online = 0;
        for (UUID id : hunt.everybody()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null && !hunt.isEliminated(id)) {
                give(sender, player, kind, false);
                online++;
            }
        }
        messages.send(sender, "manhunt.give.everybody", "players", String.valueOf(online));
    }

    /** @param sayEverything every outcome, not only a hand-out or a full inventory */
    private void give(CommandSender sender, Player target, Optional<Kind> kind, boolean sayEverything) {
        // The inventory belongs to the target's region, which need not be the sender's.
        Threads.entity(plugin, target, () -> {
            List<Kind> kinds = kind.map(List::of).orElse(List.of(Kind.values()));
            boolean handed = false;
            for (Kind each : kinds) {
                Outcome outcome = handOut(target, each);
                if (outcome == Outcome.NO_HUNT) {
                    messages.send(sender, "manhunt.give.no-hunt");
                    return;
                }
                if (outcome == Outcome.GIVEN) {
                    handed = true;
                }
                if (sayEverything || outcome == Outcome.GIVEN || outcome == Outcome.NO_ROOM) {
                    messages.send(sender, "manhunt.give." + outcome.name().toLowerCase(Locale.ROOT)
                                    .replace('_', '-'),
                            "player", target.getName(), "compass", each.word());
                }
            }
            if (!sayEverything && kind.isEmpty() && !handed) {
                messages.send(sender, "manhunt.give.nothing-missing", "player", target.getName());
            }
        });
    }

    @Override
    public void takeAll(Player player) {
        compasses.takeAll(player);
    }

    private Outcome handOut(Player target, Kind kind) {
        TrackerCompassService tracker = compasses.tracker();
        TeamCompassService team = compasses.team();
        StructureCompassService structures = compasses.structures();
        Hunt hunt = liveHunt.get().orElse(null);
        UUID id = target.getUniqueId();
        boolean inIt = hunt != null && hunt.everybody().contains(id) && !hunt.isEliminated(id);
        boolean room = target.getInventory().firstEmpty() != -1;
        Outcome outcome = switch (kind) {
            case TRACKER -> decide(hunt != null, true, hunt != null && tracker.isHolder(hunt, id),
                    tracker.carries(target), room);
            case TEAM -> decide(hunt != null, team.enabledNow(), inIt, team.carries(target), room);
            case STRUCTURE -> decide(hunt != null, structures.enabledNow(), inIt && hunt.isRunner(id),
                    structures.carries(target), room);
        };
        if (outcome == Outcome.GIVEN) {
            switch (kind) {
                case TRACKER -> tracker.give(target);
                case TEAM -> team.give(target);
                case STRUCTURE -> structures.giveAgain(target);
            }
        }
        return outcome;
    }
}
