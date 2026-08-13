package de.raindancer.modules.mannequin.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Named starting points for {@code model.Mannequin#maxHealthOverride}, so the loadout/skin screens
 * can offer "make this one as tough as an iron golem" without a player typing a raw number.
 *
 * <p>Deliberately a plain lookup table rather than an enum a mannequin's health field is typed as.
 * {@code model.Mannequin#maxHealthOverride} is a bare {@code Double}, so any number works — a
 * server wanting a preset this table does not have simply sets the number it wants, rather than
 * this module needing a new release to add a case for it. What lives here is only a curated set of
 * conveniences, and it is exactly as easy to extend as adding one line below.
 */
public final class HealthPresets {

    private static final Map<String, Double> PRESETS = build();

    private HealthPresets() {
    }

    private static Map<String, Double> build() {
        Map<String, Double> presets = new LinkedHashMap<>();
        presets.put("player", 20.0);
        presets.put("zombie", 20.0);
        presets.put("skeleton", 20.0);
        presets.put("husk", 20.0);
        presets.put("spider", 16.0);
        presets.put("cave_spider", 12.0);
        presets.put("witch", 26.0);
        presets.put("pillager", 24.0);
        presets.put("vindicator", 24.0);
        presets.put("evoker", 24.0);
        presets.put("piglin_brute", 50.0);
        presets.put("iron_golem", 100.0);
        presets.put("ravager", 100.0);
        presets.put("wither", 300.0);
        presets.put("ender_dragon", 200.0);
        presets.put("warden", 500.0);
        return Map.copyOf(presets);
    }

    /** Every preset name, lower case, for a menu to list. */
    public static Map<String, Double> all() {
        return PRESETS;
    }

    public static Optional<Double> get(String name) {
        return name == null ? Optional.empty()
                : Optional.ofNullable(PRESETS.get(name.toLowerCase(Locale.ROOT)));
    }
}
