package de.raindancer.modules.essentials.model;

import de.raindancer.core.moderation.rules.RulePenalty;
import org.bukkit.Material;

import java.util.List;

/**
 * One of the server's rules, as players read them with {@code /rules}.
 *
 * @param id      stable through edits and moves; never shown, the number players see is its position
 * @param icon    what it is drawn as in the editor
 * @param enabled   off keeps it in the list for later without showing it to anybody
 * @param penalties what the first, second, … time breaking it costs; past the top it stays on the top
 */
public record HouseRule(String id, String title, String text, Material icon, boolean enabled,
                        List<RulePenalty> penalties) {

    public static final Material DEFAULT_ICON = Material.PAPER;

    public HouseRule {
        title = title == null ? "" : title.strip();
        text = text == null ? "" : text.strip();
        // Whether it can be held is asked where it is drawn: that needs the server's registries.
        icon = icon == null || icon.name().endsWith("AIR") ? DEFAULT_ICON : icon;
        penalties = penalties == null ? List.of() : List.copyOf(penalties);
    }

    public HouseRule(String id, String title, String text, Material icon, boolean enabled) {
        this(id, title, text, icon, enabled, List.of());
    }

    public HouseRule withPenalties(List<RulePenalty> value) {
        return new HouseRule(id, title, text, icon, enabled, value);
    }

    /** The icon, or the default for a material that is a block but no item (water, fire). */
    public Material drawnAs() {
        return icon.isItem() ? icon : DEFAULT_ICON;
    }

    public HouseRule withTitle(String value) {
        return new HouseRule(id, value, text, icon, enabled, penalties);
    }

    public HouseRule withText(String value) {
        return new HouseRule(id, title, value, icon, enabled, penalties);
    }

    public HouseRule withIcon(Material value) {
        return new HouseRule(id, title, text, value, enabled, penalties);
    }

    public HouseRule withEnabled(boolean value) {
        return new HouseRule(id, title, text, icon, value, penalties);
    }

    public HouseRule withId(String value) {
        return new HouseRule(value, title, text, icon, enabled, penalties);
    }
}
