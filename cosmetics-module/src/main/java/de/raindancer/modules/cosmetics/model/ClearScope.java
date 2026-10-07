package de.raindancer.modules.cosmetics.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** What {@code /cosmetics clear} takes off. */
public enum ClearScope {
    NAME("name"), PARTICLES("particles"), ALL("all");

    private final String key;

    ClearScope(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public boolean includesName() {
        return this != PARTICLES;
    }

    public boolean includesParticles() {
        return this != NAME;
    }

    /** The word somebody typed; "particle" is accepted as well, because everybody types it. */
    public static Optional<ClearScope> of(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String word = typed.strip().toLowerCase(Locale.ROOT);
        if (word.equals("particle")) {
            return Optional.of(PARTICLES);
        }
        return Arrays.stream(values()).filter(scope -> scope.key.equals(word)).findFirst();
    }

    public static List<String> keys() {
        return Arrays.stream(values()).map(ClearScope::key).toList();
    }
}
