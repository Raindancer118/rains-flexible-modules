package de.raindancer.modules.guilds.model;

import java.util.Locale;

/** How one guild stands to another. Neutral is the absence of either, and is never stored. */
public enum Relation {
    /** Both agreed. Shared ally chat; friendly fire as each guild's own setting says. */
    ALLY,
    /** Declared by one side alone. Shown on both guilds' pages; nothing else changes. */
    RIVAL,
    NEUTRAL;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
