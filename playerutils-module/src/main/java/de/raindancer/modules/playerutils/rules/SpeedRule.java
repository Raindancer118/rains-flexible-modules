package de.raindancer.modules.playerutils.rules;

import de.raindancer.core.moderation.players.PlayerBody;

import java.util.Locale;

/** Which speed a {@code /speed} means, and the level a raw game speed came from. */
public final class SpeedRule implements IPlayerUtilsRule {

    public enum Kind { WALK, FLY, BOTH }

    public Kind kind(String word, boolean flying) {
        return switch (word == null ? "auto" : word.toLowerCase(Locale.ROOT)) {
            case "walk" -> Kind.WALK;
            case "fly" -> Kind.FLY;
            case "both" -> Kind.BOTH;
            default -> flying ? Kind.FLY : Kind.WALK;
        };
    }

    public int walkLevel(float raw) {
        return level(raw, PlayerBody.walkSpeedFor(1));
    }

    public int flyLevel(float raw) {
        return level(raw, PlayerBody.flySpeedFor(1));
    }

    private static int level(float raw, float one) {
        if (raw >= 1f) {
            return PlayerBody.MAX_SPEED_LEVEL;
        }
        return Math.clamp(Math.round(raw / one), 0, PlayerBody.MAX_SPEED_LEVEL);
    }

    @Override
    public String describe() {
        return "which speed a /speed means";
    }
}
