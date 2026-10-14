package de.raindancer.modules.healthbars.rules;

import java.util.Locale;

/** What a health line says in each {@link HealthStyle}, and when a mob's is shown at all. Decides only. */
public final class HealthTextRule {

    private static final double HALF = 0.5;
    private static final int SEGMENTS = 10;
    private static final String SEGMENT = "█";

    /** MiniMessage, coloured for how much is left: green above half, yellow above a quarter, red below. */
    public String text(HealthStyle style, double health, double most) {
        double left = most <= 0 ? 0 : Math.max(0, Math.min(1, health / most));
        String colour = left > 0.5 ? "green" : left > 0.25 ? "yellow" : "red";
        if (style == HealthStyle.BAR) {
            int filled = Math.max(1, (int) Math.ceil(left * SEGMENTS));
            int percent = Math.max(1, (int) Math.ceil(left * 100));
            return "<" + colour + ">" + SEGMENT.repeat(filled) + "</" + colour + ">"
                    + (filled < SEGMENTS ? "<dark_gray>" + SEGMENT.repeat(SEGMENTS - filled) + "</dark_gray>" : "")
                    + " <" + colour + ">" + percent + "%</" + colour + ">";
        }
        double shown = Math.max(HALF, Math.ceil(health / HALF) * HALF);
        return "<red>❤</red> <" + colour + ">" + number(shown) + "</" + colour + ">";
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    /** A mob's line shows while it is hurt, and for a while after its last hit even when healed. */
    public boolean mobShown(double health, double most, long lastHitMillis, long now, int seconds) {
        return health < most || (lastHitMillis > 0 && now - lastHitMillis < seconds * 1000L);
    }
}
