package de.raindancer.modules.worldgate.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.worldgate.WorldGateSettings;
import de.raindancer.modules.worldgate.model.Dimension;
import de.raindancer.modules.worldgate.model.GateState;
import de.raindancer.modules.worldgate.model.GateStates;
import de.raindancer.modules.worldgate.store.GateStateStore;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether the Nether and the End are open, and pulling everybody out of one.
 *
 * <h2>Locking and evacuating are independent</h2>
 * Setting a state never moves a player, and evacuating never changes a state — an admin who wants
 * both closes the dimension first and evacuates second, or the other way round, as two separate
 * commands. Coupling them would mean a re-open silently teleporting nobody the first time it was
 * used for that, and an evacuate silently locking the door nobody asked to have shut — two surprises
 * for the price of one command.
 *
 * <h2>Why evacuation does not go through Core's {@code Travel}</h2>
 * {@code Travel} is a player's own trip: a warm-up they can walk out of, a cooldown, movement
 * cancelling. Being evacuated is done <em>to</em> a player by an admin, right now, with none of that —
 * so this teleports directly rather than dressing a forced move up as a voluntary one.
 */
public final class WorldGateService implements IWorldGateService {

    private final GateStateStore store;
    private final LogChannel log;
    private final Messages messages;

    private volatile GateStates states = GateStates.ALL_OPEN;
    private volatile WorldGateSettings settings;

    public WorldGateService(GateStateStore store, LogChannel log, Messages messages,
                            WorldGateSettings settings) {
        this.store = store;
        this.log = log;
        this.messages = messages;
        this.settings = settings;
    }

    /** Reads whatever was on disk last time the server stopped. Called once, from {@code enable}. */
    public void load() {
        states = store.load();
    }

    @Override
    public void settings(WorldGateSettings fresh) {
        this.settings = fresh;
    }

    public GateState state(Dimension dimension) {
        return states.of(dimension);
    }

    public GateStates states() {
        return states;
    }

    /**
     * Locks or unlocks one dimension.
     *
     * @return whether the change reached disk — the in-memory state is left as it was on a failed
     * write, so a command that could not save does not tell a player something is closed when the
     * server would forget that on the next restart
     */
    public boolean set(Dimension dimension, GateState state) {
        GateStates updated = states.with(dimension, state);
        boolean written = store.save(updated);
        if (written) {
            states = updated;
        } else {
            log.error("Could not write {}'s new state to disk, so it is left as {}.",
                    dimension.label(), states.of(dimension));
        }
        return written;
    }

    /** Which world this dimension currently is, from the live settings. */
    public String worldName(Dimension dimension) {
        WorldGateSettings now = settings;
        return dimension == Dimension.NETHER ? now.netherWorld() : now.endWorld();
    }

    /**
     * Everybody in a dimension's world, pulled back to the overworld.
     *
     * <p>Neither world being loaded is not an error — a dimension nobody has visited yet, or one
     * misconfigured to a name that does not exist, simply has nobody in it to move.
     *
     * @return how many players were moved
     */
    public int evacuate(Dimension dimension, Server server) {
        World world = server.getWorld(worldName(dimension));
        World overworld = server.getWorld(settings.overworldWorld());
        if (world == null || overworld == null) {
            return 0;
        }
        // Copied rather than iterated live: teleporting a player fires events other plugins may react
        // to, and this list must not change under us while that happens.
        List<Player> there = new ArrayList<>(world.getPlayers());
        for (Player player : there) {
            Location target = evacuationTarget(player.getRespawnLocation(), overworld);
            player.teleportAsync(target);
            messages.send(player, "worldgate.evacuate-notice", "dimension", dimension.label());
        }
        return there.size();
    }

    /**
     * Where an evacuated player goes: their own respawn point, when it is actually set somewhere in
     * the overworld — a bed or respawn anchor placed in the Nether must not send them right back into
     * the dimension they were just pulled out of. Otherwise the overworld's own spawn.
     *
     * <p>Pure and static on purpose, so it is testable with a fake {@code Location} and a mocked
     * {@code World} rather than a real player on a real server.
     */
    public static Location evacuationTarget(Location respawn, World overworld) {
        if (respawn != null && respawn.getWorld() != null && respawn.getWorld().equals(overworld)) {
            return respawn;
        }
        return overworld.getSpawnLocation();
    }

    @Override
    public String describe() {
        return "whether the Nether and the End are open, and pulling everybody out of one";
    }
}
