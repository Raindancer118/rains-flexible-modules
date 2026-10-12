package de.raindancer.modules.arena.api;

import java.util.Locale;

/**
 * What somebody asked for: the kind of fight, the kit, how many rounds, the stake each side pays, the team size, and
 * for mob waves how they go. Two specs with the same {@link #queueKey} can be matched against each other.
 *
 * @param kit   a kit id, or {@link #OWN_GEAR} for fighting with a copy of what one owns on the survival server
 * @param stake what each player pays in, in the economy's smallest unit; 0 for a fight for nothing
 */
public record MatchSpec(Mode mode, String kit, int bestOf, long stake, int teamSize, WaveSpec waves) {

    public static final String OWN_GEAR = "own";
    public static final int MOST_ROUNDS = 7;
    /** No stake above this — far beyond any real balance, and far below where a pot of eight could overflow. */
    public static final long MOST_STAKE = 1_000_000_000_000L;

    public MatchSpec {
        mode = mode == null ? Mode.DUEL : mode;
        kit = kit == null || kit.isBlank() ? (mode == Mode.SUMO ? "sumo" : mode == Mode.NAVAL ? "naval" : "gladiator")
                : kit.toLowerCase(Locale.ROOT);
        bestOf = Math.max(1, Math.min(MOST_ROUNDS, bestOf | 1));
        stake = Math.max(0, Math.min(MOST_STAKE, stake));
        teamSize = mode == Mode.TEAMS ? Math.max(2, Math.min(3, teamSize))
                : mode.isGuild() ? Math.max(2, Math.min(5, teamSize)) : 1;
        if (mode == Mode.SUMO) {
            kit = "sumo";
        }
        if (mode != Mode.WAVES) {
            waves = null;
        } else if (waves == null) {
            waves = WaveSpec.PRESETS.get("warmup");
        }
        if (mode == Mode.WAVES || mode.isFreeForAll()) {
            bestOf = 1;
        }
    }

    public static MatchSpec duel(String kit) {
        return new MatchSpec(Mode.DUEL, kit, 1, 0, 1, null);
    }

    public static MatchSpec waves(WaveSpec waves, String kit) {
        return new MatchSpec(Mode.WAVES, kit, 1, 0, 1, waves);
    }

    public boolean isOwnGear() {
        return OWN_GEAR.equals(kit);
    }

    /** How many players the fight takes before it starts. */
    public int players() {
        return switch (mode) {
            case DUEL, SUMO -> 2;
            case TEAMS, GUILD_WAR, GUILD_TRAINING -> teamSize * 2;
            case FFA, NAVAL -> mode.fewest();
            case WAVES -> 1;
        };
    }

    /** Fights that may be put together from the queue are fights with the same key. */
    public String queueKey() {
        return mode.name() + "|" + kit + "|" + bestOf + "|" + stake + "|" + teamSize
                + (waves == null ? "" : "|" + waves.preset());
    }

    public MatchSpec withKit(String value) {
        return new MatchSpec(mode, value, bestOf, stake, teamSize, waves);
    }

    public MatchSpec withBestOf(int value) {
        return new MatchSpec(mode, kit, value, stake, teamSize, waves);
    }

    public MatchSpec withStake(long value) {
        return new MatchSpec(mode, kit, bestOf, value, teamSize, waves);
    }

    public MatchSpec withTeamSize(int value) {
        return new MatchSpec(mode, kit, bestOf, stake, value, waves);
    }

    public MatchSpec withWaves(WaveSpec value) {
        return new MatchSpec(mode, kit, bestOf, stake, teamSize, value);
    }
}
