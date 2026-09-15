package de.raindancer.modules.speedrun;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import de.raindancer.core.ui.menu.Menu;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A different game played in the speedrun lobby — Manhunt is the first.
 *
 * <h2>Why a mode, and not a second lobby beside this one</h2>
 * Manhunt used to be exactly that: its own world, its own waiting area, its own countdown, its own
 * start teleport and its own reset, all next to this module's. Every one of those was a second copy
 * of something that already worked here, and the two copies disagreed with each other in front of
 * players — the speedrun lobby's compass and start block were still in everybody's hands when a
 * hunt began, both modules declared a {@code world-name}, and the hunt's own start teleport was
 * narrated as a Runner "reaching the Overworld". So a mode owns nothing a race already has. The
 * world, the lobby items, the freeze, the countdown, the clock, the pause while everybody is
 * offline and the reset are this module's, and a mode only adds what makes its game a different one.
 *
 * <h2>Why the registry points this way round</h2>
 * The dependency runs from the mode's module to this one and never back, so the lobby cannot name a
 * mode — it can only ask {@link SpeedrunModes} what has been offered. See that class.
 *
 * <h2>The order a mode is asked in</h2>
 * {@link #refuseStart} when the start block is pressed and again when the countdown reaches zero,
 * before anybody is frozen or anything is armed; then {@link #onStart} with the fresh session, after
 * the lobby has armed the goal and prepared everybody, before the clock starts; then
 * {@link #announceFinish} once; and finally whatever the mode handed to {@link SpeedrunRun#onDisarm},
 * when the lobby forgets the run.
 */
public interface SpeedrunMode {

    /** The id stored in {@code game-mode}, and the key the mode is withdrawn by. Lower case. */
    String id();

    /** The name on the lobby menu's button, without markup. */
    String label();

    /** What that button is made of. */
    Material icon();

    /** A line or two under the button saying what the game is, MiniMessage. */
    default List<String> description() {
        return List.of();
    }

    /**
     * Why a run with these participants may not start, as a {@code messages.yml} key the lobby sends
     * to whoever pressed the block — or empty when it may.
     *
     * <p>Asked before anybody is frozen, so a hopeless press is refused at once rather than after the
     * countdown; and asked again at zero, since the roster or the settings can have changed. Must not
     * change anything: it is a question.
     */
    Optional<String> refuseStart(SpeedrunSettings config, Set<UUID> participants);

    /**
     * Whether the lobby's own death policy applies. A mode with rules of its own about dying — Manhunt
     * eliminates Runners and lets Hunters respawn — answers false, and then the policy is neither armed
     * nor offered in the menu.
     */
    default boolean usesDeathPolicy() {
        return true;
    }

    /**
     * Whether reaching the goal as {@code participant} ends the run. Every participant by default; in
     * Manhunt only a Runner, because a Hunter who kills the dragon has not won anything for the Runners.
     *
     * <p>Asked at the moment the goal is reached, so the answer may depend on the run in progress.
     */
    default boolean countsForGoal(UUID participant) {
        return true;
    }

    /**
     * The run has been built: its goal is armed, everybody has been prepared, and the clock is about
     * to start. Add end conditions to {@link SpeedrunRun#session()}, and register listeners through
     * {@link SpeedrunRun#listen} so they go when the run does.
     *
     * <p>A mode that throws here does not get a plain race run in its place: the lobby forgets the run
     * and goes back to ready.
     */
    void onStart(SpeedrunRun run);

    /**
     * Tells the participants how the run ended. Return true when this mode has said it, and the
     * lobby's plain "run finished" line is not sent; false to leave that to the lobby.
     */
    default boolean announceFinish(SpeedrunSession session, SpeedrunOutcome outcome) {
        return false;
    }

    /**
     * A page of the mode's own, reached from a button on the lobby menu — Manhunt's sides. Empty for
     * a mode with nothing to set up.
     */
    default Optional<Setup> setup() {
        return Optional.empty();
    }

    /** Opening a mode's own page, with the lobby menu as the one its Back button returns to. */
    @FunctionalInterface
    interface Setup {

        void open(Player viewer, Menu parent);
    }
}
