package de.raindancer.modules.roles.model;

import de.raindancer.core.ui.choose.ItemSelection;

import java.util.List;

/**
 * Something a role does in the game, at full strength; a new holder gets a share of it that grows the same
 * way the shop perks do.
 *
 * @param items for {@link AbilityKind#TOOLS}: which tools; null or empty is any pickaxe, shovel, axe or hoe
 */
public record Ability(AbilityKind kind, int percent, ItemSelection items) {

    private static final ItemSelection ANY_TOOL = new ItemSelection(List.of(),
            List.of("*_pickaxe", "*_shovel", "*_axe", "*_hoe", "shears", "fishing_rod"), List.of());

    public Ability {
        percent = Math.clamp(percent, 0, kind.most());
        items = items == null || items.items().isEmpty() && items.categories().isEmpty() ? ANY_TOOL : items;
    }

    public boolean covers(String material) {
        return items.covers(material);
    }

    public String says(int now) {
        return kind.says(now);
    }

    public String says() {
        return says(percent);
    }
}
