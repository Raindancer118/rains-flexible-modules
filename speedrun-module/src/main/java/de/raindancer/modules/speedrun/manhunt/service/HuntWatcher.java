package de.raindancer.modules.speedrun.manhunt.service;

import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import de.raindancer.modules.speedrun.SpeedrunMode;
import de.raindancer.modules.speedrun.SpeedrunOutcome;
import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.SpeedrunRun;

import java.util.Optional;
import java.util.UUID;

/**
 * Everything that happens to a hunt, told to whoever keeps its record and draws its HUD — so the
 * mode and its listeners decide, and this only listens. Every method is optional.
 */
public interface HuntWatcher {

    /** What a death in a hunt cost. */
    enum Death { CAUGHT, LIFE_LOST, HUNTER_DIED }

    HuntWatcher NONE = new HuntWatcher() { };

    /** @param headStartSeconds how long {@code hold} keeps the Hunters still, 0 for none */
    default void started(Hunt hunt, SpeedrunRun run, HunterHoldListener hold, int headStartSeconds) {
    }

    /** @param by whoever killed them, or null; {@code livesLeft} only means something for LIFE_LOST */
    default void died(Hunt hunt, UUID who, String name, UUID by, String byName, Death death, int livesLeft) {
    }

    default void caughtAway(Hunt hunt, UUID who, String name) {
    }

    default void left(Hunt hunt, UUID who) {
    }

    default void sideChanged(Hunt hunt, UUID who, boolean nowRunner, boolean latecomer) {
    }

    /** The hunt is over; {@code outcome} is empty where it was abandoned or the plugin stopped. */
    /** Everybody's results as the run ends, for the lobby's history — see {@code SpeedrunMode.results}. */
    default Optional<SpeedrunMode.Results> results(Hunt hunt, SpeedrunSession session, SpeedrunOutcome outcome) {
        return Optional.empty();
    }

    default void ended(Hunt hunt, Optional<SpeedrunOutcome> outcome) {
    }
}
