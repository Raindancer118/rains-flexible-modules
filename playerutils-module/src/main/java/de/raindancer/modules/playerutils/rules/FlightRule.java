package de.raindancer.modules.playerutils.rules;

import java.util.Locale;

/**
 * When granted flight has to be put back. The game takes {@code allowFlight} away on a gamemode change,
 * a death and a world change — a player given flight should not have to ask again each time.
 */
public final class FlightRule implements IPlayerUtilsRule {

    public boolean shouldRestore(boolean granted, String gamemode, boolean canFlyNow) {
        if (!granted || canFlyNow) {
            return false;
        }
        String mode = gamemode == null ? "" : gamemode.toUpperCase(Locale.ROOT);
        return mode.equals("SURVIVAL") || mode.equals("ADVENTURE");
    }

    /** Whether flight should be on after {@code state} ("toggle", "on", "off") given it is {@code now}. */
    public boolean wanted(String state, boolean now) {
        return switch (state == null ? "toggle" : state.toLowerCase(Locale.ROOT)) {
            case "on" -> true;
            case "off" -> false;
            default -> !now;
        };
    }

    @Override
    public String describe() {
        return "when flight a player was given has to be put back";
    }
}
