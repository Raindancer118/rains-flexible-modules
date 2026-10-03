package de.raindancer.modules.speedrun.manhunt.setup;

import de.raindancer.core.data.settings.SettingsRegistry;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.modules.speedrun.manhunt.ManhuntSettings;
import de.raindancer.modules.speedrun.manhunt.model.ManhuntTeams;
import de.raindancer.modules.speedrun.manhunt.stats.BalancePlanner;
import de.raindancer.modules.speedrun.manhunt.stats.Rating;
import de.raindancer.modules.speedrun.manhunt.stats.StatsStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * What the hub, the pre-flight page and the commands all ask of the lobby before a hunt — who is
 * here, how the sides stand, what the goal is — and the few things they change: the sides by rating
 * or by lot, the goal, the door.
 *
 * <p>The lobby's goal is speedrun-module's own setting, reached through Core's settings registry
 * rather than through the lobby, because a game mode is never handed the lobby — and a goal changed
 * here is then exactly the goal the lobby's own screen shows.
 */
public final class HuntDesk {

    /** Speedrun-module's settings, by the name Core's registry knows them under. */
    static final String GOAL = "speedrun:advancement-key";
    static final String WORLD = "speedrun:world-name";

    /** What a balance or a draw did: whether anything moved, the Runners by name, and their chance. */
    public record Result(boolean done, String refusal, List<String> runners, int hunters, double runnersExpected) {

        static Result refused(String key) {
            return new Result(false, key, List.of(), 0, 0);
        }
    }

    private final Supplier<Set<UUID>> present;
    private final Function<UUID, String> names;
    private final SettingsStore<ManhuntSettings> settings;
    private final Supplier<SettingsRegistry> registry;
    private final ManhuntTeams teams;
    private final BooleanSupplier hunting;
    private final BooleanSupplier whitelistClosed;
    private final StatsStore stats;
    private final Predicate<String> advancementExists;
    private final Random random;

    /**
     * @param present           everybody a start would sweep up
     * @param registry          Core's settings registry; null where it is not up
     * @param advancementExists whether this server has an advancement by that key
     */
    public HuntDesk(Supplier<Set<UUID>> present, Function<UUID, String> names, SettingsStore<ManhuntSettings> settings,
                    Supplier<SettingsRegistry> registry, ManhuntTeams teams, BooleanSupplier hunting,
                    BooleanSupplier whitelistClosed, StatsStore stats, Predicate<String> advancementExists,
                    Random random) {
        this.present = Objects.requireNonNull(present, "present");
        this.names = Objects.requireNonNull(names, "names");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.teams = Objects.requireNonNull(teams, "teams");
        this.hunting = Objects.requireNonNull(hunting, "hunting");
        this.whitelistClosed = Objects.requireNonNull(whitelistClosed, "whitelistClosed");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.advancementExists = Objects.requireNonNull(advancementExists, "advancementExists");
        this.random = Objects.requireNonNull(random, "random");
    }

    public Set<UUID> present() {
        return Set.copyOf(present.get());
    }

    public String nameOf(UUID id) {
        return names.apply(id);
    }

    public StatsStore stats() {
        return stats;
    }

    /** Whether speedrun-module's lobby is up at all — its settings are what say so. */
    public boolean lobbyRunning() {
        SettingsRegistry live = registry.get();
        return live != null && live.storeOf(WORLD).isPresent();
    }

    /** The lobby's goal, or empty for none. */
    public String goal() {
        SettingsRegistry live = registry.get();
        return live == null ? "" : live.display(GOAL).trim();
    }

    public boolean goalKnown(String key) {
        return key != null && !key.isBlank() && advancementExists.test(key);
    }

    /** Sets the lobby's goal — only to an advancement this server has. */
    public boolean setGoal(String key) {
        SettingsRegistry live = registry.get();
        return live != null && goalKnown(key) && live.set(GOAL, key);
    }

    public void keepDoorOpen() {
        settings.set("close-whitelist-on-start", "false");
    }

    public void closeDoorOnStart() {
        settings.set("close-whitelist-on-start", "true");
    }

    public Preflight.Situation situation() {
        Set<UUID> here = present();
        Set<UUID> runners = teams.runners();
        List<String> away = new ArrayList<>();
        for (UUID runner : runners) {
            if (!here.contains(runner)) {
                away.add(names.apply(runner));
            }
        }
        List<Double> runnerRatings = new ArrayList<>();
        List<Double> hunterRatings = new ArrayList<>();
        for (UUID id : here) {
            (runners.contains(id) ? runnerRatings : hunterRatings).add(stats.rating(id));
        }
        double chance = runnerRatings.isEmpty() || hunterRatings.isEmpty()
                ? 0.5 : Rating.runnersExpected(runnerRatings, hunterRatings);
        String goal = goal();
        return new Preflight.Situation(lobbyRunning(), hunting.getAsBoolean(), here.size(), runnerRatings.size(),
                List.copyOf(away), goal, goalKnown(goal), settings.current().closeWhitelistOnStart(),
                whitelistClosed.getAsBoolean(), chance);
    }


    /** Splits everybody here by rating — see {@link BalancePlanner}. Refused during a hunt. */
    public Result balance() {
        Set<UUID> here = present();
        Result refusal = refusal(here);
        if (refusal != null) {
            return refusal;
        }
        Map<UUID, Double> ratings = new LinkedHashMap<>();
        for (UUID id : here) {
            ratings.put(id, stats.rating(id));
        }
        BalancePlanner.Plan plan = BalancePlanner.balance(ratings, settings.current().balanceMaxRunners());
        return apply(here, plan.runners(), plan.runnersExpected());
    }

    /** {@code count} Runners drawn at random from everybody here, the rest hunting. Refused during a hunt. */
    public Result randomRunners(int count) {
        Set<UUID> here = present();
        Result refusal = refusal(here);
        if (refusal != null) {
            return refusal;
        }
        Set<UUID> runners = BalancePlanner.random(List.copyOf(here), Math.max(1, count), random);
        List<Double> r = runners.stream().map(stats::rating).toList();
        List<Double> h = here.stream().filter(id -> !runners.contains(id)).map(stats::rating).toList();
        return apply(here, runners, Rating.runnersExpected(r, h));
    }

    private Result refusal(Set<UUID> here) {
        if (hunting.getAsBoolean()) {
            return Result.refused("manhunt.sides-frozen");
        }
        if (here.size() < 2) {
            return Result.refused("manhunt.balance.too-few");
        }
        return null;
    }

    private Result apply(Set<UUID> here, Set<UUID> runners, double chance) {
        // Runners who are not here are not in the next hunt either; leaving them on the side would
        // make the pre-flight page complain about them straight after a balance.
        for (UUID member : teams.runners()) {
            if (!here.contains(member)) {
                teams.leave(member);
            }
        }
        List<String> runnerNames = new ArrayList<>();
        for (UUID id : here) {
            boolean runner = runners.contains(id);
            teams.join(id, runner);
            if (runner) {
                runnerNames.add(names.apply(id));
            }
        }
        return new Result(true, null, List.copyOf(runnerNames), here.size() - runners.size(), chance);
    }
}
