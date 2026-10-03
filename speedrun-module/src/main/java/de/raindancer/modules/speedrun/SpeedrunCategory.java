package de.raindancer.modules.speedrun;

import java.util.Locale;
import java.util.Optional;

/**
 * Which runs may be compared with which: the same goal, the same kind of seed, the same game, and
 * practice never beside the real thing. Personal bests, records and leaderboards are all kept per
 * category — a set-seed dragon kill and a random-seed one are different races.
 *
 * @param goal     the advancement key the run was for, or empty for a mode's own ending
 * @param seeds    random or set seed
 * @param mode     the game mode's id, or empty for a plain race
 * @param practice the practice kit's name, or empty for a real run
 */
public record SpeedrunCategory(String goal, SpeedrunSeedType seeds, String mode, String practice) {

    public SpeedrunCategory {
        goal = goal == null ? "" : goal.trim();
        seeds = seeds == null ? SpeedrunSeedType.RANDOM : seeds;
        mode = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        practice = practice == null ? "" : practice.trim();
    }

    /** One string, stable across restarts — what history is filed under. */
    public String key() {
        return goal + "|" + seeds.name() + "|" + mode + "|" + practice;
    }

    public static Optional<SpeedrunCategory> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String[] parts = key.split("\\|", -1);
        if (parts.length != 4) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SpeedrunCategory(parts[0], SpeedrunSeedType.valueOf(parts[1]), parts[2], parts[3]));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    public boolean isPractice() {
        return !practice.isEmpty();
    }

    /** What a player reads: {@code "Dragon killed · Random seed"}, and so on. */
    public String label() {
        StringBuilder text = new StringBuilder(goal.isEmpty() ? "No goal"
                : SpeedrunAdvancementChooser.friendlyName(goal));
        text.append(" · ").append(seeds.label());
        if (!mode.isEmpty()) {
            text.append(" · ").append(mode.substring(0, 1).toUpperCase(Locale.ROOT)).append(mode.substring(1));
        }
        if (isPractice()) {
            text.append(" · Practice");
        }
        return text.toString();
    }
}
