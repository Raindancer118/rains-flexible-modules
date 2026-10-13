package de.raindancer.modules.arena.model;

import de.raindancer.modules.arena.api.MatchSpec;
import java.util.List;
import java.util.Locale;

/**
 * What a fighter is given: every inventory slot, items as Core's item text (so a kit saved under one version reads back
 * under the next), and potion effects as {@code type ticks amplifier}.
 *
 * @param items   41 slots in {@code PlayerInventory#getContents()} order, empty slots as {@code ""}
 * @param harmless sumo's: hits push and never hurt
 * @param building explosives and the like: fighters place blocks and blow up their pit, which is put back after each round
 */
public record Kit(String id, String name, String icon, List<String> items, List<String> effects, boolean harmless,
                  boolean building) {

    public static final int SLOTS = 41;

    public Kit(String id, String name, String icon, List<String> items, List<String> effects, boolean harmless) {
        this(id, name, icon, items, effects, harmless, false);
    }

    public Kit {
        if (id == null || id.isBlank() || id.equals(MatchSpec.OWN_GEAR)) {
            throw new IllegalArgumentException("a kit needs a name that is not '" + MatchSpec.OWN_GEAR + "'");
        }
        id = id.toLowerCase(Locale.ROOT);
        name = name == null || name.isBlank() ? id : name;
        icon = icon == null || icon.isBlank() ? "IRON_SWORD" : icon.toUpperCase(Locale.ROOT);
        items = items == null ? List.of() : List.copyOf(items);
        effects = effects == null ? List.of() : List.copyOf(effects);
    }

    public boolean hasItems() {
        return items.stream().anyMatch(slot -> slot != null && !slot.isEmpty());
    }
}
