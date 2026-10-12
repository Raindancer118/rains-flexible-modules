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
    WAVES("Mob waves", 1, 4),
    /** The pit is flooded; everybody in a boat, against everybody, the last one standing wins. */
    NAVAL("Naval battle", 2, 8),
    /** Your guild against another guild, a side each. */
    GUILD_WAR("Guild war", 4, 10),
    /** Your own guild split into two sides. */
    GUILD_TRAINING("Guild training", 4, 10);

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

    /** Whether the sides are made of guilds — the queue has to know who is in which. */
    public boolean isGuild() {
        return this == GUILD_WAR || this == GUILD_TRAINING;
    }

    /** Whether everybody fights everybody, the queue waiting a while for more before it starts. */
    public boolean isFreeForAll() {
        return this == FFA || this == NAVAL;
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
        if (name.equals("BOATS") || name.equals("SHIPS") || name.equals("NAVY") || name.equals("FLOOD")) {
            return Optional.of(NAVAL);
        }
        if (name.equals("GUILDWAR") || name.equals("GW") || name.equals("WAR")) {
            return Optional.of(GUILD_WAR);
        }
        if (name.equals("TRAINING") || name.equals("GUILDTRAINING") || name.equals("SPARRING")) {
            return Optional.of(GUILD_TRAINING);
        }
        for (Mode mode : values()) {
            if (mode.name().equals(name)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
