package de.raindancer.modules.healthbars.rules;

import java.util.Locale;

/** How a player likes health drawn: a heart and the number, or a bar and the percent. */
public enum HealthStyle {
    HEARTS, BAR;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static HealthStyle of(String key) {
        if (key != null) {
            for (HealthStyle style : values()) {
                if (style.name().equalsIgnoreCase(key.strip())) {
                    return style;
                }
            }
        }
        return HEARTS;
    }
}
