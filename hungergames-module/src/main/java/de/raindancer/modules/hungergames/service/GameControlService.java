package de.raindancer.modules.hungergames.service;

import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.store.GameSession;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The single door a command or a GUI button goes through to move a round from one stage to the next.
 *
 * <h2>Why the actual stage work arrives as a collaborator rather than being called directly</h2>
 * The source engine this is ported from wired {@code /init}, {@code /startup} and {@code /start} straight
 * into its own {@code PreflightRunner}, {@code StartupRunner} and {@code StartRunner} — the classes that
 * paste the arena schematic, hold tributes in the launch tubes and run the countdown. None of that exists
 * in this module yet: the arena wave (schematics, WorldEdit, the platforms) has not landed. Wiring this
 * service to concrete runners that are not there would mean either inventing a second, throwaway version of
 * them here — exactly the duplication {@code ReuseTest} exists to catch once the real ones do land — or
 * leaving the method bodies empty, which is the "looks finished, does nothing" failure this module's whole
 * porting effort exists to avoid.
 *
 * <p>So the actions are {@link Stage} collaborators, handed in by whoever wires the module once those
 * runners exist. What belongs to <em>this</em> class, and is real today, is the part that has nothing to do
 * with schematics: which phase a round is allowed to move through which stage from, and the round-length
 * sanity checks a stage must pass before it is even attempted. That half is what
 * {@code GameControlServiceTest} exercises — every precondition, with no server and no arena, against a real
 * {@link GameSession}.
 */
public final class GameControlService implements IHungerGamesService {

    /** The fewest tributes a round can sensibly run with — one tribute has nobody to win against. */
    public static final int MIN_PLAYERS = 2;

    /** A ceiling rather than a real limit: past this a stage is almost certainly a typo, not a tournament. */
    public static final int MAX_PLAYERS = 100;

    /**
     * One stage of the start-up sequence: holding tributes in the tubes, or running the countdown.
     *
     * @return whether the stage was actually carried out
     */
    @FunctionalInterface
    public interface Stage {
        boolean run(UUID actor);
    }

    /**
     * Building the arena, which is the one stage that needs to be told how big.
     *
     * <h2>Why this is its own type</h2>
     * Because {@link Stage} was used for it, and {@code Stage} carries only the actor. {@link #init} validated
     * the count against {@link #MIN_PLAYERS} and {@link #MAX_PLAYERS} and then called {@code run(actor)} —
     * the number was checked and dropped one line later, and the builder re-derived it from the tribute
     * register. On a live server a gamemaster chose 42 and got two platforms, because the register was empty.
     *
     * <p>A validated argument that reaches nothing is worse than no validation at all: the refusal quotes a
     * range the value never had to be inside. So the count is part of the type now, and the compiler is what
     * keeps it that way.
     *
     * @return whether the arena was actually built
     */
    @FunctionalInterface
    public interface BuildStage {
        boolean run(UUID actor, int playerCount);
    }

    private final GameSession session;
    private final Predicate<UUID> isCountdownActive;
    private final BuildStage initStage;
    private final Stage startupStage;
    private final Stage startStage;

    private HungerGamesSettings settings;

    /**
     * @param isCountdownActive whether a countdown is already running for the given actor's round — a
     *                          gamemaster wiring this without a real countdown runner yet can pass
     *                          {@code actor -> false}, the safe default: nothing here is otherwise mistaken
     *                          for a countdown in progress
     */
    public GameControlService(GameSession session, Predicate<UUID> isCountdownActive,
                               BuildStage initStage, Stage startupStage, Stage startStage) {
        this.session = session;
        this.isCountdownActive = isCountdownActive;
        this.initStage = initStage;
        this.startupStage = startupStage;
        this.startStage = startStage;
    }

    /**
     * Held for {@link #init}'s player-count bounds, which are round setup rather than a settings-screen
     * value today — kept here so a later settings key can widen them without touching every call site.
     */
    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    // ==================== preconditions ====================

    /** Whether {@code /init} may run: no arena yet, or the previous round is over. */
    public boolean canInit() {
        GamePhase phase = session.phase();
        return phase == GamePhase.NOT_INITIALIZED || phase == GamePhase.FINISHED;
    }

    /** Whether the start-up sequence (tubes, platforms) may run: tributes are gathered in the lobby. */
    public boolean canStartup() {
        return session.phase() == GamePhase.LOBBY;
    }

    /** Whether {@code /start} may run: everybody is on a platform, and no countdown is already ticking. */
    public boolean canStart(UUID actor) {
        return session.phase() == GamePhase.READY && !isCountdownActive.test(actor);
    }

    /** Whether the round may be ended early, on a time-out verdict. */
    public boolean canEndRound() {
        return session.phase() == GamePhase.RUNNING;
    }

    // ==================== actions ====================

    /**
     * Initialises the arena for a fresh round.
     *
     * @return empty on success, or the reason it was refused
     */
    public Optional<String> init(UUID actor, int playerCount) {
        if (!canInit()) {
            return Optional.of("the arena is already initialised — end the round first");
        }
        if (playerCount < MIN_PLAYERS || playerCount > MAX_PLAYERS) {
            return Optional.of("the player count must be between " + MIN_PLAYERS
                    + " and " + MAX_PLAYERS);
        }
        // The count goes through. It used to stop here — see BuildStage.
        return initStage.run(actor, playerCount) ? Optional.empty()
                : Optional.of("the arena could not be built — see the console");
    }

    /** Runs the start-up sequence: tributes down the tubes, up to their platforms. */
    public Optional<String> startup(UUID actor) {
        if (!canStartup()) {
            return Optional.of("the start-up sequence only runs from the lobby (currently "
                    + session.phase() + ")");
        }
        return startupStage.run(actor) ? Optional.empty()
                : Optional.of("the start-up sequence could not be run — see the console");
    }

    /** Runs the countdown and, at its end, moves the round to {@code RUNNING}. */
    public Optional<String> start(UUID actor) {
        if (isCountdownActive.test(actor)) {
            return Optional.of("the countdown is already running");
        }
        if (!canStart(actor)) {
            return Optional.of("starting only works from READY (currently " + session.phase() + ")");
        }
        return startStage.run(actor) ? Optional.empty()
                : Optional.of("the countdown could not be started — see the console");
    }

    /** Ends the round early, always producing a winner verdict — see {@link GameSession#declareTimeout()}. */
    public boolean endRound() {
        if (!canEndRound()) {
            return false;
        }
        session.declareTimeout();
        return true;
    }

    /** Resets round state for a rematch. Tributes and teams stay registered; the arena is left to its owner. */
    public void prepareNextRound() {
        session.resetForNextRound();
    }

    public GameSession session() {
        return session;
    }

    /** A stage collaborator that never runs — for wiring a slot the module does not use yet. */
    public static Stage notYetAvailable() {
        return actor -> false;
    }

    /** The same for the build stage. */
    public static BuildStage noArenaBuilderYet() {
        return (actor, playerCount) -> false;
    }
}
