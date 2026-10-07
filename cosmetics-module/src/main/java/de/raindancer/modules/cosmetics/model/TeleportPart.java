package de.raindancer.modules.cosmetics.model;

import java.util.Locale;
import java.util.Optional;

/** The three things about a teleport a player can choose for themselves. */
public enum TeleportPart {

    /** Heard where they stood, as they set off. */
    DEPART("depart"),
    /** Heard where they land. */
    ARRIVE("arrive"),
    /** Drawn around them while they stand still for it. */
    WAIT("wait"),
    /** Heard by them alone at each second of the countdown. */
    TICK("tick");

    private final String key;

    TeleportPart(String key) {
        this.key = key;
    }

    /** As typed and as written in messages.yml: depart, arrive, wait. */
    public String key() {
        return key;
    }

    public boolean isSound() {
        return this != WAIT;
    }

    public static Optional<TeleportPart> of(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String word = typed.trim().toLowerCase(Locale.ROOT);
        for (TeleportPart part : values()) {
            if (part.key.equals(word)) {
                return Optional.of(part);
            }
        }
        return switch (word) {
            case "leave", "departure", "start" -> Optional.of(DEPART);
            case "arrival", "land" -> Optional.of(ARRIVE);
            case "waiting", "particle", "particles" -> Optional.of(WAIT);
            case "countdown", "ding", "second", "seconds" -> Optional.of(TICK);
            default -> Optional.empty();
        };
    }
}
