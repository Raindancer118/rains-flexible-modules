package de.raindancer.modules.speedrun.manhunt.setup;

import java.util.List;

/** Manhunt's lobby as the pre-flight check reads it — see {@link ManhuntChecks}. */
public final class Preflight {

    /**
     * @param present        how many are in the lobby a start would sweep up
     * @param runnersPresent how many of them are on the Runner side
     * @param runnersAway    Runners who are not here, by name
     * @param runnersExpected the Runners' chance by the ratings, 0…1
     */
    public record Situation(boolean lobbyRunning, boolean huntRunning, int present, int runnersPresent,
                            List<String> runnersAway, String goalKey, boolean goalKnown,
                            boolean closesWhitelist, boolean whitelistClosed, double runnersExpected) {
    }

    private Preflight() {
    }
}
