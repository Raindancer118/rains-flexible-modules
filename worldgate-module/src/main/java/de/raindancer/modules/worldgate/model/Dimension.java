package de.raindancer.modules.worldgate.model;

import java.util.Locale;
import java.util.Optional;

/**
 * The two dimensions this module manages. Never the overworld — there is nowhere to evacuate an
 * overworld to, and nothing else on this server is "entering the overworld" the way stepping through
 * a portal is entering the Nether or the End.
 */
public enum Dimension {
    NETHER("The Nether"),
    END("The End");

    private final String label;

    Dimension(String label) {
        this.label = label;
    }

    /** What a player is told this is called. */
    public String label() {
        return label;
    }

    /**
     * What somebody typed at a command, read as one of the two — {@code nether} or {@code end},
     * case-insensitively. Nothing else answers: a command that guessed at "nether_wastes" or similar
     * would be a second, looser copy of the same two words.
     */
    public static Optional<Dimension> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "nether" -> Optional.of(NETHER);
            case "end" -> Optional.of(END);
            default -> Optional.empty();
        };
    }
}
