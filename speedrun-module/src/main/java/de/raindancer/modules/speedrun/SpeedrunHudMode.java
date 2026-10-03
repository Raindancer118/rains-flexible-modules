package de.raindancer.modules.speedrun;

/** Where a player sees the run's splits — each player's own choice, {@code hud-default} until they make one. */
public enum SpeedrunHudMode {
    /** The splits as a list on the right of the screen. */
    SIDEBAR,
    /** The last split and the next one in a bar at the top. */
    BOSSBAR,
    /** The last split beside the clock, above the hotbar. */
    ACTIONBAR,
    /** Only the clock. */
    OFF;

    public String label() {
        return switch (this) {
            case SIDEBAR -> "Sidebar";
            case BOSSBAR -> "Boss bar";
            case ACTIONBAR -> "Action bar";
            case OFF -> "Off";
        };
    }

    /** The next one round, for a toggle button. */
    public SpeedrunHudMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
