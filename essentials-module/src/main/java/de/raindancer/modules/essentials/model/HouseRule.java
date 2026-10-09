package de.raindancer.modules.essentials.model;

import org.bukkit.Material;

/**
 * One of the server's rules, as players read them with {@code /rules}.
 *
 * @param id      stable through edits and moves; never shown, the number players see is its position
 * @param icon    what it is drawn as in the editor
 * @param enabled off keeps it in the list for later without showing it to anybody
 */
public record HouseRule(String id, String title, String text, Material icon, boolean enabled) {

    public static final Material DEFAULT_ICON = Material.PAPER;

    public HouseRule {
        title = title == null ? "" : title.strip();
        text = text == null ? "" : text.strip();
        // Whether it can be held is asked where it is drawn: that needs the server's registries.
        icon = icon == null || icon.name().endsWith("AIR") ? DEFAULT_ICON : icon;
    }

    /** The icon, or the default for a material that is a block but no item (water, fire). */
    public Material drawnAs() {
        return icon.isItem() ? icon : DEFAULT_ICON;
    }

    public HouseRule withTitle(String value) {
        return new HouseRule(id, value, text, icon, enabled);
    }

    public HouseRule withText(String value) {
        return new HouseRule(id, title, value, icon, enabled);
    }

    public HouseRule withIcon(Material value) {
        return new HouseRule(id, title, text, value, enabled);
    }

    public HouseRule withEnabled(boolean value) {
        return new HouseRule(id, title, text, icon, value);
    }

    public HouseRule withId(String value) {
        return new HouseRule(value, title, text, icon, enabled);
    }
}
