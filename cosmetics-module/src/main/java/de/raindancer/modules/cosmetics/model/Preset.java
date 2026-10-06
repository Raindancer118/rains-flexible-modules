package de.raindancer.modules.cosmetics.model;

import de.raindancer.core.ui.text.NameStyle;

import java.util.Locale;

/**
 * A named style an owner put in the config — "Sunset", "Rainbow".
 *
 * <p>A preset is its own permission: wearing it never needs the colour, gradient or decoration nodes,
 * so a server can hand out gradients only as presets. A restricted one needs
 * {@code rainscosmetics.preset.<id>}; the node is derived from the id rather than stored, so it cannot
 * drift from the preset it unlocks.
 */
public record Preset(String id, String title, NameStyle style, boolean restricted) {

    public static final String PERMISSION_PREFIX = "rainscosmetics.preset.";

    public Preset {
        id = id.toLowerCase(Locale.ROOT);
    }

    public String permission() {
        return PERMISSION_PREFIX + id;
    }
}
