package de.raindancer.modules.oneblock.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.oneblock.OneBlockSettings;
import de.raindancer.modules.oneblock.model.Hold;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.rules.UnloadRule;
import de.raindancer.modules.oneblock.store.IslandBook;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Keeps each island's worlds up exactly while they should be: the {@link UnloadRule} decides, this loads, starts the
 * clock, and unloads — sending anybody still there to the waiting world first, which only ever happens when an admin
 * takes an island down. An admin hold that ran out is cleared here too. Server thread, every few seconds.
 */
public final class IslandLifecycle implements IOneBlockService {

    private final Server server;
    private final LogChannel log;
    private final IslandBook book;
    private final IslandWorlds worlds;
    private final IslandTravel travel;
    private final Messages messages;
    private final Predicate<Island> working;
    private final LongSupplier clock;
    private final UnloadRule rule = new UnloadRule();
    private final Map<UUID, Long> emptySince = new HashMap<>();
    private volatile OneBlockSettings settings;

    public IslandLifecycle(Server server, LogChannel log, IslandBook book, IslandWorlds worlds, IslandTravel travel,
                           Messages messages, Predicate<Island> working, LongSupplier clock, OneBlockSettings settings) {
        this.server = server;
        this.log = log;
        this.book = book;
        this.worlds = worlds;
        this.travel = travel;
        this.messages = messages;
        this.working = working;
        this.clock = clock;
        settings(settings);
    }

    @Override
    public void settings(OneBlockSettings fresh) {
        this.settings = fresh;
    }

    public void tick() {
        long now = clock.getAsLong();
        for (Island island : book.all()) {
            if (island.hold().filter(hold -> !hold.activeAt(now)).isPresent()) {
                book.change(island.id(), it -> Optional.of(it.withHold(Optional.empty())));
                log.info("The admin hold on {}'s island ran out.", island.ownerName());
            }
            tick(book.get(island.id()).orElse(island), now);
        }
        unloadOrphans();
    }

    /** One island, now — also right after an admin changed it, so a hold acts at once. */
    public void tick(Island island, long now) {
        boolean loaded = worlds.anyLoaded(island);
        List<Player> on = worlds.playersOn(island);
        UnloadRule.Decision decision = rule.decide(new UnloadRule.Facts(loaded, on.size(),
                emptySince.getOrDefault(island.id(), -1L), island.autoUnload(), island.hold(),
                loaded && working.test(island), now, settings.unloadMillis()));
        switch (decision) {
            case STAY -> {
                if (!on.isEmpty()) {
                    emptySince.remove(island.id());
                }
            }
            case START_COUNTING -> emptySince.put(island.id(), now);
            case LOAD -> {
                for (IslandDimension dimension : island.unlockedDimensions()) {
                    worlds.load(island, dimension);
                }
                emptySince.remove(island.id());
                log.info("Loaded {}'s island{}.", island.ownerName(),
                        island.hold().isPresent() ? " (held up by " + island.hold().get().by() + ")" : "");
            }
            case UNLOAD -> {
                boolean heldDown = island.hold().filter(hold -> hold.keepsDown(now)).isPresent();
                for (Player player : on) {
                    messages.send(player, heldDown ? "oneblock.lifecycle.taken-down" : "oneblock.lifecycle.unloaded");
                }
                if (worlds.unload(island, travel.waiting())) {
                    emptySince.remove(island.id());
                }
            }
            case NOTHING -> emptySince.remove(island.id());
        }
    }

    /** Island worlds nobody plays any more — an archived island — go once they are empty. */
    private void unloadOrphans() {
        for (World world : List.copyOf(server.getWorlds())) {
            if (Island.isIslandWorld(world.getName()) && book.ofWorld(world.getName()).isEmpty()
                    && world.getPlayers().isEmpty()) {
                server.unloadWorld(world, true);
            }
        }
    }

    public void forget(UUID island) {
        emptySince.remove(island);
    }

    /** For the admin pages: since when an island has sat empty; empty while it is not counting. */
    public Optional<Long> emptySince(UUID island) {
        return Optional.ofNullable(emptySince.get(island));
    }

    public boolean held(Island island, Hold.Kind kind) {
        return island.hold().filter(hold -> hold.kind() == kind && hold.activeAt(clock.getAsLong())).isPresent();
    }
}
