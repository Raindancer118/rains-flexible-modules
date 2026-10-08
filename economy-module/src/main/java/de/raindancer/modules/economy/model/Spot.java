package de.raindancer.modules.economy.model;

import java.util.Locale;
import java.util.Optional;

/** Where a leaderboard floats: a world and a position, as written in the settings. */
public record Spot(String world, double x, double y, double z) {

    /** {@code "world 10.5 70 -3.5"}; empty for anything else. */
    public static Optional<Spot> parse(String line) {
        if (line == null) {
            return Optional.empty();
        }
        String[] words = line.trim().split("\\s+");
        if (words.length != 4) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Spot(words[0], Double.parseDouble(words[1]), Double.parseDouble(words[2]),
                    Double.parseDouble(words[3])));
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    public String written() {
        return String.format(Locale.ROOT, "%s %.2f %.2f %.2f", world, x, y, z);
    }

    public double distanceSquared(String otherWorld, double ox, double oy, double oz) {
        if (!world.equals(otherWorld)) {
            return Double.MAX_VALUE;
        }
        return (x - ox) * (x - ox) + (y - oy) * (y - oy) + (z - oz) * (z - oz);
    }
}
