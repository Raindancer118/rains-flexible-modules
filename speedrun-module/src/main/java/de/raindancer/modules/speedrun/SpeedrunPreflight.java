package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.checklist.Checklist;
import de.raindancer.core.world.manage.WorldRegenerator;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Everything that has to be true before a run can start — and, for each thing that is not, what
 * fixes it in one click — as Core's {@link Checklist}: what the pre-flight page draws, what
 * {@code /speedrun check} says, and what a refused start points at. The lobby's own checks come
 * first; the game mode adds its own under its heading ({@link SpeedrunMode#preflight}).
 *
 * <p>Built fresh on every look: it is a picture of the lobby now, not state to keep.
 */
public final class SpeedrunPreflight {

    public static final String TITLE = "Before the start";

    /** What the lobby itself can fix a failing check with. */
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

    /** Which of the lobby's fixes each of its checks offers, by the check's id. */
    private static final Map<String, Fix> FIXES = Map.of(
            "ready", Fix.RESET, "world", Fix.CREATE_WORLDS, "dimensions", Fix.CREATE_WORLDS,
            "mode", Fix.PLAIN_RACE, "goal", Fix.DRAGON_GOAL, "advancement", Fix.DRAGON_GOAL,
            "racers", Fix.BRING_EVERYBODY, "mode-ready", Fix.MODE_SETUP, "practice", Fix.NO_KIT,
            "seed", Fix.RANDOM_SEED);

    private SpeedrunPreflight() {
    }

    /** The lobby's own fix behind {@code check}, if it has one on offer. */
    public static Optional<Fix> fixOf(Checklist.Check check) {
        return check.fixIfAny().map(fix -> FIXES.get(check.id()));
    }

    /**
     * The lobby as it stands, with {@code racers} as whoever a start would sweep up.
     *
     * @param viewer who is reading it — somebody who may not start a run is told so as a red line,
     *               so the start button's grey has a reason; null for the console
     */
    public static Checklist of(SpeedrunLobby lobby, Set<UUID> racers, SpeedrunActions actions, Player viewer) {
        SpeedrunSettings config = lobby.config();
        Checklist list = Checklist.titled(TITLE);
        SpeedrunLobbyState state = lobby.state();
        boolean resettable = state != SpeedrunLobbyState.READY && state != SpeedrunLobbyState.COUNTDOWN;
        list = list.check(fixed(Checklist.Check.of("ready", "The lobby is ready", state == SpeedrunLobbyState.READY)
                .because(switch (state) {
                    case READY -> "Nothing is under way.";
                    case COUNTDOWN -> "A countdown is already running.";
                    case RUNNING, PAUSED -> "A run is under way. Reset it, or let it finish.";
                    case FINISHED -> "The last run is over; its world has to be remade first.";
                }), resettable ? Fix.RESET : Fix.NONE, actions));

        World world = Bukkit.getWorld(config.worldName());
        list = list.check(fixed(Checklist.Check.of("world", "The lobby world exists", world != null)
                .because(world != null ? "'" + config.worldName() + "' is loaded."
                        : "'" + config.worldName() + "' is not loaded."), Fix.CREATE_WORLDS, actions));
        SpeedrunWorlds worlds = SpeedrunWorlds.around(config.worldName());
        boolean dimensions = Bukkit.getWorld(worlds.nether()) != null && Bukkit.getWorld(worlds.theEnd()) != null;
        list = list.check(fixed(Checklist.Check.warning("dimensions", "Its nether and End exist", dimensions)
                .because(dimensions ? "Portals stay inside the run."
                        : "A portal would lead into the server's own dimensions."), Fix.CREATE_WORLDS, actions));
        if (world != null && WorldRegenerator.isPrimaryWorld(world)) {
            list = list.check(Checklist.Check.warning("primary", "A world of its own", false)
                    .because("The lobby is the server's main world, which can never be reset. Set world-name to "
                            + "a world of its own."));
        }

        SpeedrunMode mode = lobby.mode().orElse(null);
        boolean modeOk = !config.hasGameMode() || mode != null;
        list = list.check(fixed(Checklist.Check.of("mode", "The game is installed", modeOk)
                .because(modeOk ? (mode == null ? "A plain race." : "Playing " + mode.label() + ".")
                        : "'" + config.gameMode() + "' is not installed on this server."), Fix.PLAIN_RACE, actions));

        boolean endable = mode == null || mode.usesDeathPolicy()
                ? config.hasEndCondition()
                : config.hasAdvancementGoal() || mode.endsItself();
        list = list.check(fixed(Checklist.Check.of("goal", "Something ends the run", endable)
                .because(endable ? goalText(config) : "No goal and no death policy: a run could never end."),
                Fix.DRAGON_GOAL, actions));
        if (config.hasAdvancementGoal()) {
            NamespacedKey key = NamespacedKey.fromString(config.advancementKey());
            boolean exists = key != null && Bukkit.getAdvancement(key) != null;
            list = list.check(fixed(Checklist.Check.warning("advancement", "The goal exists", exists)
                    .because(exists ? "Players can earn it." : "'" + config.advancementKey()
                            + "' is no advancement on this server; that goal would never fire."), Fix.DRAGON_GOAL, actions));
        }

        list = list.check(fixed(Checklist.Check.of("racers", "Somebody to race", !racers.isEmpty())
                .because(racers.isEmpty() ? "Nobody racing is standing in the lobby world."
                        : racers.size() + " racing."), Fix.BRING_EVERYBODY, actions));
        Checklist fromMode = mode == null ? Checklist.titled(TITLE) : mode.preflight(config, racers);
        list = list.and(fromMode);
        if (mode != null && !racers.isEmpty() && fromMode.checks().isEmpty()) {
            String refusal = mode.refuseStart(config, racers).orElse(null);
            list = list.check(fixed(Checklist.Check.of("mode-ready", mode.label() + " is set up", refusal == null)
                    .because(refusal == null ? "Ready to play." : "Not yet — open its page to set it up."),
                    mode.setup().isPresent() ? Fix.MODE_SETUP : Fix.NONE, actions));
        }

        if (config.kit().isPractice()) {
            list = list.check(fixed(Checklist.Check.warning("practice", "A real run", false)
                    .because("Practice kit '" + config.kit().label() + "' is on: this run is ranked as practice."),
                    Fix.NO_KIT, actions));
        }
        if (config.seedMode() == SpeedrunSeedMode.FIXED && SpeedrunSeeds.fixed(config.seed()).isEmpty()
                || config.seedMode() == SpeedrunSeedMode.POOL && SpeedrunSeeds.pool(config.seedPool()).isEmpty()) {
            list = list.check(fixed(Checklist.Check.warning("seed", "The seed setting is complete", false)
                    .because("Seed mode is " + config.seedMode() + " but no seed is set, so worlds are random."),
                    Fix.RANDOM_SEED, actions));
        }
        if (viewer != null && !SpeedrunAccess.START.allows(lobby, viewer)) {
            list = list.check(Checklist.Check.of("may-start", "You may start a run", false)
                    .because("Only staff start runs here."));
        }
        return list;
    }

    /**
     * {@code check} with the lobby's {@code fix} — offered to somebody with its node, and asked again
     * when it is clicked, since a page or a chat line can outlive a permission.
     */
    private static Checklist.Check fixed(Checklist.Check check, Fix fix, SpeedrunActions actions) {
        if (fix == Fix.NONE) {
            return check;
        }
        SpeedrunAccess needed = fix == Fix.RESET ? SpeedrunAccess.RESET : SpeedrunAccess.FIX;
        return check.fixedBy(SpeedrunActions.fixLabel(fix), player -> actions.applyIfAllowed(fix, player, null),
                needed.node());
    }

    private static String goalText(SpeedrunSettings config) {
        if (config.hasAdvancementGoal()) {
            return "Racing for " + SpeedrunAdvancementChooser.friendlyName(config.advancementKey()) + ".";
        }
        return config.hasDeathCondition() ? "A death ends it." : "The game ends it.";
    }
}
