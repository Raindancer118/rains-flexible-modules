package de.raindancer.modules.worldutils.model;

import org.bukkit.World;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The three kinds of world {@code /dim} moves between, and what people type for each.
 */
public enum Dimension {
    OVERWORLD(World.Environment.NORMAL, "the overworld"),
    NETHER(World.Environment.NETHER, "the Nether"),
    END(World.Environment.THE_END, "the End");

    private final World.Environment environment;
    private final String label;

    Dimension(World.Environment environment, String label) {
        this.environment = environment;
        this.label = label;
    }

    public World.Environment environment() {
        return environment;
    }

    /** What a player is told this is called. */
    public String label() {
        return label;
    }

    /** The words offered while typing, one per dimension. */
    public static List<String> words() {
        return List.of("overworld", "nether", "end");
    }

    /**
     * What somebody typed. The vanilla names and the short forms people actually use both answer —
     * {@code /dim the_end} from somebody who knows the id, {@code /dim e} from somebody in a hurry.
     */
    public static Optional<Dimension> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "overworld", "normal", "world", "o", "ow" -> Optional.of(OVERWORLD);
            case "nether", "the_nether", "n", "hell" -> Optional.of(NETHER);
            case "end", "the_end", "e" -> Optional.of(END);
            default -> Optional.empty();
        };
    }

    /** Which of the three a world is; empty for a datapack dimension. */
    public static Optional<Dimension> of(World.Environment environment) {
        if (environment == null) {
            return Optional.empty();
        }
        for (Dimension dimension : values()) {
            if (dimension.environment == environment) {
                return Optional.of(dimension);
            }
        }
        return Optional.empty();
    }
}
