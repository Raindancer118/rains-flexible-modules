package de.raindancer.modules.hungergames.service;

import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.model.BorderSettings;
import de.raindancer.modules.hungergames.rules.BorderRules;
import de.raindancer.modules.hungergames.rules.BorderRules.ShrinkCommand;
import de.raindancer.modules.hungergames.rules.BorderRules.TickResult;
import de.raindancer.modules.hungergames.store.GameSession;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Turns {@link BorderRules}' decisions into an actual moving world border, and is the one place that
 * remembers which phase comes next.
 *
 * <h2>Why the phase index lives here and not in {@code BorderRules}</h2>
 * See {@code BorderRules}' own class note: the index travels as a value on purpose, so it can be read from
 * a restored snapshot, ticked, and written back without a live object anywhere disagreeing with the file.
 * This service is the caller that does exactly that — {@link #nextPhaseIndex()} is what a session snapshot
 * persists between ticks, and {@link #resumeAt} is how a restart hands it back in.
 *
 * <h2>Why this takes a {@link WorldBorderTarget} rather than a {@code World} directly</h2>
 * Moving an actual {@code WorldBorder} needs a running server, which is exactly what this module's tests
 * do not have. The decision of <em>what</em> to do — shrink to this size, over this long, and scale the
 * Nether 1:8 — is ordinary arithmetic and is tested directly; only the seam that turns that decision into
 * two {@code WorldBorder.changeSize} calls is Bukkit, and it is behind this one small interface so a test
 * can hand in a recording fake instead of a server.
 */
public final class BorderService implements IHungerGamesService {

    /** How Bukkit's Nether scale is applied to a border command — the one seam that needs a server. */
    public interface WorldBorderTarget {

        /** The Overworld border's current diameter. */
        double currentSize();

        /** Moves the Overworld border to {@code targetSize} over {@code ticks}. */
        void shrinkOverworld(double targetSize, long ticks);

        /** Moves the Nether border (already scaled 1:8) to {@code targetSize} over {@code ticks}. */
        void shrinkNether(double targetSize, long ticks);

        /** Sets both borders back to their round-start size, with no transition. */
        void resetTo(double overworldSize);
    }

    private final GameSession session;
    private final VirtualTime virtualTime;
    private final WorldBorderTarget target;
    private final BorderRules rules = new BorderRules();

    private HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;
    private int nextPhaseIndex;

    public BorderService(GameSession session, VirtualTime virtualTime, WorldBorderTarget target) {
        this.session = session;
        this.virtualTime = virtualTime;
        this.target = target;
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    /**
     * The round's border configuration, from the live settings.
     *
     * <p>The phase list itself is held by {@code store.BorderPhaseStore} (settings only carries the round's
     * fixed numbers), so it is read fresh from whatever is current rather than cached — an admin editing a
     * phase mid-lobby must see the edited phase fire, not the one on screen when the round was set up.
     */
    public BorderSettings currentSettings(List<de.raindancer.modules.hungergames.model.BorderPhaseConfig> phases) {
        return new BorderSettings(settings.borderInitialSize(), settings.borderFloor(),
                settings.borderEdgeSpeed(), phases);
    }

    /** Starts a fresh round's border at phase zero. */
    public void start() {
        nextPhaseIndex = 0;
    }

    /** Picks back up after a restart at the persisted phase index — earlier phases must not fire again. */
    public void resumeAt(int persistedPhaseIndex) {
        nextPhaseIndex = Math.max(0, persistedPhaseIndex);
    }

    public int nextPhaseIndex() {
        return nextPhaseIndex;
    }

    /**
     * One tick of the round's border. Asks {@link BorderRules} what should happen, applies it if anything
     * should, and always keeps {@link #nextPhaseIndex()} current — even on a tick that fires nothing, so a
     * caller persisting it every tick never writes a stale value.
     */
    public void tick(BorderSettings borderSettings) {
        TickResult result = rules.tick(borderSettings, nextPhaseIndex, virtualTime.elapsed(),
                session.participants().aliveCount(), target.currentSize());
        nextPhaseIndex = result.nextPhaseIndex();
        result.command().ifPresent(this::apply);
    }

    private void apply(ShrinkCommand command) {
        long ticks = Math.max(1, command.duration().toSeconds()) * 20L;
        target.shrinkOverworld(command.targetSize(), ticks);
        if (settings.borderScaleNether()) {
            target.shrinkNether(netherSize(command.targetSize()), ticks);
        }
    }

    /**
     * Takes the border over for the deathmatch: the planned phases stop firing this round, and both worlds
     * shrink straight to {@code targetSize} at whatever pace stays within the fairness ceiling.
     *
     * @return the shrink's duration in seconds, or {@code 0} when the border is already at or below the
     *         target — a deathmatch aimed at a border already smaller than it asks for shrinks nothing
     */
    public long overrideShrinkTo(double targetSize) {
        // Setting the index past the end of any phase list is what stops the regular phases from firing
        // again this round — BorderRules.isFinished is exactly this comparison.
        nextPhaseIndex = Integer.MAX_VALUE;
        double current = target.currentSize();
        long seconds = shrinkSeconds(current, targetSize, settings.borderEdgeSpeed());
        if (seconds <= 0) {
            return 0;
        }
        long ticks = seconds * 20L;
        target.shrinkOverworld(targetSize, ticks);
        if (settings.borderScaleNether()) {
            target.shrinkNether(netherSize(targetSize), ticks);
        }
        return seconds;
    }

    /** Both worlds back to the round's starting size — called once a round ends. */
    public void resetToInitial() {
        target.resetTo(settings.borderInitialSize());
        nextPhaseIndex = 0;
    }

    // ==================== pure arithmetic, tested without a server ====================

    /**
     * How long, in whole seconds, shrinking from {@code current} to {@code target} takes without exceeding
     * {@code maxEdgeSpeed}.
     *
     * @return {@code 0} when there is nothing to shrink — {@code target} at or above {@code current}
     */
    public static long shrinkSeconds(double current, double target, double maxEdgeSpeed) {
        if (target >= current || maxEdgeSpeed <= 0) {
            return 0;
        }
        return Math.max(1, (long) Math.ceil((current - target) / 2.0 / maxEdgeSpeed));
    }

    /**
     * The Nether's border for a given Overworld size, at the vanilla eighths scale — floored at 16, because
     * a Nether border narrower than that leaves no room to stand at all near the ceiling or the floor.
     */
    public static double netherSize(double overworldSize) {
        return Math.max(16.0, overworldSize / 8.0);
    }
}
