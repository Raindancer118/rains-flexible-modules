package de.raindancer.modules.hungergames.service;

import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.store.GameSession;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * The deathmatch: a gamemaster-triggered (or, unmanually, automatic) finale that hands the border over to
 * {@link BorderService#overrideShrinkTo} and, optionally, pulls every surviving tribute to the centre.
 *
 * <h2>Why the state machine and the countdown are two different things</h2>
 * {@link State} — {@code IDLE}, {@code WARNING}, {@code ACTIVE} — is what a restart has to be able to put
 * back the way it found it, and it is plain enough to test without a clock: given a phase, the settings and
 * the current state, is starting or cancelling allowed right now, and does two tributes remaining call for
 * an automatic start? All of that is {@link #checkStart}, {@link #checkCancel} and
 * {@link #autoTriggerReason}, none of which touch a scheduler. The actual warning countdown — counting
 * seconds down and firing {@link #execute} at zero — is real time passing and belongs to whoever wires this
 * service onto {@code Scheduling.globalTimer}; this class only has to be told when that has happened.
 *
 * <h2>Restart safety</h2>
 * An {@code ACTIVE} deathmatch is not a warning ticking down, it is a border already committed to a target
 * size — and Vanilla itself carries a border transition across a restart in {@code level.dat}, so nothing
 * here has to replay the shrink. What has to survive is only "do not let the ordinary border phases fire
 * again this round", which {@link #restoreActive} re-asserts by calling
 * {@link BorderService#overrideShrinkTo} again — a no-op on the actual border since it is already there, but
 * exactly what {@link BorderService#nextPhaseIndex()} needs pushed past the end of the phase list. A
 * {@code WARNING} does not survive a restart at all: a countdown with no clock running is not a countdown,
 * and restarting one from whatever second it stopped at would either replay lost seconds or skip them.
 */
public final class DeathmatchService implements IHungerGamesService {

    public enum State {
        IDLE,
        WARNING,
        ACTIVE
    }

    private final GameSession session;
    private final BorderService border;
    private final Consumer<HungerGamesSettings> teleportTributesToCentre;

    private HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;
    private State state = State.IDLE;

    /**
     * @param teleportTributesToCentre puts every living tribute at the arena's centre, honouring the
     *                                 configured height offset and post-teleport grace — Bukkit's job, so it
     *                                 arrives as a collaborator rather than being ported into this class
     */
    public DeathmatchService(GameSession session, BorderService border,
                              Consumer<HungerGamesSettings> teleportTributesToCentre) {
        this.session = session;
        this.border = border;
        this.teleportTributesToCentre = teleportTributesToCentre;
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    public State state() {
        return state;
    }

    // ==================== pure preconditions ====================

    /** Whether {@link #start} would be allowed right now; the reason it would not, otherwise. */
    public Optional<String> checkStart() {
        if (!settings.deathmatchEnabled()) {
            return Optional.of("the deathmatch is disabled (deathmatch.enabled)");
        }
        if (state != State.IDLE) {
            return Optional.of("the deathmatch is already " + state);
        }
        boolean phaseAllowed = settings.deathmatchAllowedPhases().stream()
                .anyMatch(phase -> phase.equalsIgnoreCase(session.phase().name()));
        if (!phaseAllowed) {
            return Optional.of("the deathmatch is not allowed in phase " + session.phase());
        }
        return Optional.empty();
    }

    /** Whether {@link #cancel} would be allowed right now; the reason it would not, otherwise. */
    public Optional<String> checkCancel() {
        return switch (state) {
            case WARNING -> Optional.empty();
            case ACTIVE -> Optional.of("the deathmatch is already running — it cannot be cancelled now");
            case IDLE -> Optional.of("there is no deathmatch warning running");
        };
    }

    /**
     * Whether the automatic trigger ({@code deathmatch.manual-only: false}) should fire now that
     * {@code remainingAlive} tributes are left.
     */
    public boolean autoTriggerReason(int remainingAlive) {
        return state == State.IDLE && !settings.deathmatchManualOnly() && settings.deathmatchEnabled()
                && remainingAlive == 2 && checkStart().isEmpty();
    }

    // ==================== state transitions ====================

    /**
     * Enters the warning state. The caller is responsible for the countdown itself and must call
     * {@link #execute()} when it reaches zero, or {@link #cancel()} if a gamemaster calls it off first.
     */
    public Optional<String> start() {
        Optional<String> refusal = checkStart();
        if (refusal.isPresent()) {
            return refusal;
        }
        state = State.WARNING;
        return Optional.empty();
    }

    /** Calls off a warning before it fires. */
    public Optional<String> cancel() {
        Optional<String> refusal = checkCancel();
        if (refusal.isPresent()) {
            return refusal;
        }
        state = State.IDLE;
        return Optional.empty();
    }

    /** Commits the deathmatch: takes the border, optionally teleports, and moves to {@code ACTIVE}. */
    public void execute() {
        if (session.phase() != GamePhase.RUNNING) {
            state = State.IDLE;
            return;
        }
        state = State.ACTIVE;
        border.overrideShrinkTo(settings.deathmatchTargetBorderSize());
        if (settings.deathmatchTeleportToCenter()) {
            teleportTributesToCentre.accept(settings);
        }
    }

    /**
     * Puts an {@code ACTIVE} deathmatch back after a restart — see the class note on why {@code WARNING}
     * does not get the same treatment.
     */
    public void restoreActive() {
        if (session.phase() != GamePhase.RUNNING) {
            return;
        }
        state = State.ACTIVE;
        border.overrideShrinkTo(settings.deathmatchTargetBorderSize());
    }

    /** Round over — whatever state the deathmatch was in, it is gone with the round. */
    public void resetForNewRound() {
        state = State.IDLE;
    }
}
