package de.raindancer.modules.arena.api;

import java.util.Locale;
import java.util.Optional;

/** What kind of fight. The numbers are how many people a fight of this kind takes. */
public enum Mode {
    /** One against one. */
    DUEL("Duel", 2, 2),
    /** Two teams of the spec's team size. */
    TEAMS("Teams", 4, 6),
    /** Everybody against everybody; the last one standing wins. */
    FFA("Free for all", 3, 8),
    /** One against one, no damage, pushed out of the ring is out. */
    SUMO("Sumo", 2, 2),
    /** Players together against waves of mobs. */
    WAVES("Mob waves", 1, 4);

    private final String title;
    private final int fewest;
    private final int most;

    Mode(String title, int fewest, int most) {
        this.title = title;
        this.fewest = fewest;
        this.most = most;
    }

    public String title() {
        return title;
    }

    public int fewest() {
        return fewest;
    }

    public int most() {
        return most;
    }

    /** Whether the players are against each other, rather than together against mobs. */
    public boolean isPvp() {
        return this != WAVES;
    }

    public static Optional<Mode> parse(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String name = typed.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (name.equals("WAVE") || name.equals("MOBS")) {
            return Optional.of(WAVES);
        }
        if (name.equals("TEAM")) {
            return Optional.of(TEAMS);
        }
        for (Mode mode : values()) {
            if (mode.name().equals(name)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
