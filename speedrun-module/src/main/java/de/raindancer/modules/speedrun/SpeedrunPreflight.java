package de.raindancer.modules.speedrun;

import de.raindancer.core.world.manage.WorldRegenerator;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Everything that has to be true before a run can start — and, for each thing that is not, what
 * fixes it in one click. What the pre-flight page draws, what {@code /speedrun check} says, and what
 * a refused start points at.
 *
 * <p>Built fresh on every look: it is a picture of the lobby now, not state to keep.
 */
public final class SpeedrunPreflight {

    /** What a failing check can be fixed by, if anything. */
    public enum Fix {
        /** Nothing here can fix it; the description says what to do. */
        NONE,
        /** Reset the run's worlds (asks first — the world is deleted). */
        RESET,
        /** Create whichever of the run's three worlds is missing. */
        CREATE_WORLDS,
        /** Play a plain race instead of a mode that is not installed. */
        PLAIN_RACE,
        /** Race for the dragon. */
        DRAGON_GOAL,
        /** Teleport everybody online who is racing into the lobby world. */
        BRING_EVERYBODY,
        /** Open the game mode's own page. */
        MODE_SETUP,
        /** Switch the practice kit off. */
        NO_KIT,
        /** Switch seed-mode back to RANDOM. */
        RANDOM_SEED
    }

    /**
     * @param ok       whether it is fine
     * @param blocking whether a start is refused while it is not — a warning only advises
     * @param label    what is checked, a few words
     * @param detail   what is wrong and what to do about it, or what is right
     */
    public record Check(String id, boolean ok, boolean blocking, String label, String detail, Fix fix) {

        public boolean stopsTheStart() {
            return !ok && blocking;
        }
    }

    private final List<Check> checks;

    private SpeedrunPreflight(List<Check> checks) {
        this.checks = List.copyOf(checks);
    }

    public List<Check> checks() {
        return checks;
    }

    /** Whether a start would go through as things stand. */
    public boolean clear() {
        return checks.stream().noneMatch(Check::stopsTheStart);
    }

    public List<Check> problems() {
        return checks.stream().filter(check -> !check.ok()).toList();
    }

    /** The lobby as it stands, with {@code racers} as whoever a start would sweep up. */
    public static SpeedrunPreflight of(SpeedrunLobby lobby, Set<UUID> racers) {
        SpeedrunSettings config = lobby.config();
        List<Check> checks = new ArrayList<>();
        SpeedrunLobbyState state = lobby.state();
        checks.add(new Check("ready", state == SpeedrunLobbyState.READY, true, "The lobby is ready",
                switch (state) {
                    case READY -> "Nothing is under way.";
                    case COUNTDOWN -> "A countdown is already running.";
                    case RUNNING, PAUSED -> "A run is under way. Reset it, or let it finish.";
                    case FINISHED -> "The last run is over; its world has to be remade first.";
                },
                state == SpeedrunLobbyState.READY || state == SpeedrunLobbyState.COUNTDOWN ? Fix.NONE : Fix.RESET));

        World world = Bukkit.getWorld(config.worldName());
        checks.add(new Check("world", world != null, true, "The lobby world exists",
                world != null ? "'" + config.worldName() + "' is loaded."
                        : "'" + config.worldName() + "' is not loaded.", Fix.CREATE_WORLDS));
        SpeedrunWorlds worlds = SpeedrunWorlds.around(config.worldName());
        boolean dimensions = Bukkit.getWorld(worlds.nether()) != null && Bukkit.getWorld(worlds.theEnd()) != null;
        checks.add(new Check("dimensions", dimensions, false, "Its nether and End exist",
                dimensions ? "Portals stay inside the run."
                        : "A portal would lead into the server's own dimensions.", Fix.CREATE_WORLDS));
        if (world != null && WorldRegenerator.isPrimaryWorld(world)) {
            checks.add(new Check("primary", false, false, "A world of its own",
                    "The lobby is the server's main world, which can never be reset. Set world-name to "
                            + "a world of its own.", Fix.NONE));
        }

        SpeedrunMode mode = lobby.mode().orElse(null);
        boolean modeOk = !config.hasGameMode() || mode != null;
        checks.add(new Check("mode", modeOk, true, "The game is installed",
                modeOk ? (mode == null ? "A plain race." : "Playing " + mode.label() + ".")
                        : "'" + config.gameMode() + "' is not installed on this server.", Fix.PLAIN_RACE));

        boolean endable = mode == null || mode.usesDeathPolicy()
                ? config.hasEndCondition()
                : config.hasAdvancementGoal() || mode.endsItself();
        checks.add(new Check("goal", endable, true, "Something ends the run",
                endable ? goalText(config) : "No goal and no death policy: a run could never end.",
                Fix.DRAGON_GOAL));
        if (config.hasAdvancementGoal()) {
            NamespacedKey key = NamespacedKey.fromString(config.advancementKey());
            boolean exists = key != null && Bukkit.getAdvancement(key) != null;
            checks.add(new Check("advancement", exists, false, "The goal exists",
                    exists ? "Players can earn it." : "'" + config.advancementKey()
                            + "' is no advancement on this server; that goal would never fire.", Fix.DRAGON_GOAL));
        }

        checks.add(new Check("racers", !racers.isEmpty(), true, "Somebody to race",
                racers.isEmpty() ? "Nobody racing is standing in the lobby world."
                        : racers.size() + " racing.", Fix.BRING_EVERYBODY));
        if (mode != null && !racers.isEmpty()) {
            String refusal = mode.refuseStart(config, racers).orElse(null);
            checks.add(new Check("mode-ready", refusal == null, true, mode.label() + " is set up",
                    refusal == null ? "Ready to play." : "Not yet — open its page to set it up.",
                    mode.setup().isPresent() ? Fix.MODE_SETUP : Fix.NONE));
        }

        if (config.kit().isPractice()) {
            checks.add(new Check("practice", false, false, "A real run",
                    "Practice kit '" + config.kit().label() + "' is on: this run is ranked as practice.",
                    Fix.NO_KIT));
        }
        if (config.seedMode() == SpeedrunSeedMode.FIXED && SpeedrunSeeds.fixed(config.seed()).isEmpty()
                || config.seedMode() == SpeedrunSeedMode.POOL && SpeedrunSeeds.pool(config.seedPool()).isEmpty()) {
            checks.add(new Check("seed", false, false, "The seed setting is complete",
                    "Seed mode is " + config.seedMode() + " but no seed is set, so worlds are random.",
                    Fix.RANDOM_SEED));
        }
        return new SpeedrunPreflight(checks);
    }

    private static String goalText(SpeedrunSettings config) {
        if (config.hasAdvancementGoal()) {
            return "Racing for " + SpeedrunAdvancementChooser.friendlyName(config.advancementKey()) + ".";
        }
        return config.hasDeathCondition() ? "A death ends it." : "The game ends it.";
    }
}
