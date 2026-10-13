package de.raindancer.modules.oneblock.model;

/** What an invite offers: a place in the team, or a look round as a guest. */
public enum InviteKind {
    TEAM,
    GUEST;

    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    public static InviteKind fromWire(String text) {
        return "guest".equalsIgnoreCase(text) ? GUEST : TEAM;
    }
}
