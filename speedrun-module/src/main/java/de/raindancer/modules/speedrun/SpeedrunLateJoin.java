package de.raindancer.modules.speedrun;

/**
 * What happens to somebody who joins while a run is under way — {@code late-join}.
 *
 * <p>Only newcomers: somebody who was racing in this run and comes back is never a latecomer — they
 * keep everything they had. And only those who can get in at all: a hunt that closed the server's
 * whitelist keeps everybody not on it out, whatever this says.
 */
public enum SpeedrunLateJoin {
    /** Nothing happens to them: they land in the run's world as an onlooker, as they always did. */
    OFF,
    /** They watch: spectator mode in the run's world, back to normal once the run is over. */
    SPECTATE,
    /**
     * They race: everything a racer got at the start — a clean slate, the kit, the game's own items,
     * a safe spot near the start — and a place in the run from the moment they joined. Their own
     * result never ranks: a run they did not run from the start is no personal best of theirs.
     */
    RACE;

    public String label() {
        return switch (this) {
            case OFF -> "Nothing — they look on";
            case SPECTATE -> "They watch in spectator mode";
            case RACE -> "They join the race";
        };
    }
}
