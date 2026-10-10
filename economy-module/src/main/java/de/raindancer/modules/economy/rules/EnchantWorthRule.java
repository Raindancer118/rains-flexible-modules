package de.raindancer.modules.economy.rules;

import de.raindancer.modules.economy.model.EnchantLevel;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How useful each enchantment is, as what it is worth at its highest level — counted in levels of an
 * ordinary enchantment, so 10 means ten times the shop's price per level. A level below the highest is
 * its share of that: Protection I is a quarter of Protection IV.
 *
 * <p>An enchantment not ranked here (a datapack's, a future version's) is worth a level per level,
 * treasure double. Curses are worth their number taken away.
 */
public final class EnchantWorthRule implements IEconomyRule {

    /** Survival usefulness, best first within each band. Owners override any of it ({@code shop.enchant-worth}). */
    static final Map<String, Double> RANKED = Map.ofEntries(
            // Wanted on everything that can carry them.
            Map.entry("mending", 12.0), Map.entry("protection", 10.0), Map.entry("efficiency", 10.0),
            Map.entry("unbreaking", 9.0), Map.entry("sharpness", 9.0), Map.entry("fortune", 9.0),
            // The best at what they do.
            Map.entry("silk_touch", 7.0), Map.entry("feather_falling", 7.0), Map.entry("looting", 7.0),
            Map.entry("power", 7.0), Map.entry("wind_burst", 7.0), Map.entry("infinity", 6.0),
            Map.entry("swift_sneak", 6.0), Map.entry("breach", 6.0),
            // Good, for some gear or some players.
            Map.entry("blast_protection", 5.0), Map.entry("fire_aspect", 5.0), Map.entry("quick_charge", 5.0),
            Map.entry("depth_strider", 5.0), Map.entry("respiration", 4.0), Map.entry("sweeping_edge", 4.0),
            Map.entry("thorns", 4.0), Map.entry("loyalty", 4.0), Map.entry("riptide", 4.0), Map.entry("smite", 4.0),
            Map.entry("luck_of_the_sea", 4.0), Map.entry("flame", 4.0), Map.entry("multishot", 4.0),
            Map.entry("projectile_protection", 4.0), Map.entry("fire_protection", 4.0), Map.entry("density", 4.0),
            Map.entry("lunge", 4.0),
            // Niche.
            Map.entry("aqua_affinity", 3.0), Map.entry("soul_speed", 3.0), Map.entry("frost_walker", 3.0),
            Map.entry("lure", 3.0), Map.entry("channeling", 3.0), Map.entry("impaling", 3.0), Map.entry("piercing", 3.0),
            Map.entry("knockback", 2.0), Map.entry("punch", 2.0), Map.entry("bane_of_arthropods", 2.0),
            // Curses: what they take away.
            Map.entry("binding_curse", 3.0), Map.entry("vanishing_curse", 4.0));

    private final Map<String, Double> ranked;

    /** @param overrides lines like {@code mending 20} or {@code mending=20}; anything else is skipped */
    public EnchantWorthRule(List<String> overrides) {
        Map<String, Double> all = new HashMap<>(RANKED);
        if (overrides != null) {
            for (String line : overrides) {
                if (line == null) {
                    continue;
                }
                String[] parts = line.strip().split("[\\s=:]+");
                if (parts.length != 2) {
                    continue;
                }
                try {
                    double value = Double.parseDouble(parts[1]);
                    if (value >= 0 && Double.isFinite(value)) {
                        all.put(parts[0].toLowerCase(Locale.ROOT), value);
                    }
                } catch (NumberFormatException notANumber) {
                    // Skipped, like any other line that makes no sense.
                }
            }
        }
        this.ranked = Map.copyOf(all);
    }

    /** What one enchantment at its level is worth, in ordinary levels; negative for a curse. */
    public double worth(EnchantLevel enchantment) {
        int max = Math.max(1, enchantment.maxLevel());
        int level = Math.max(0, enchantment.level());
        return best(enchantment.key(), max, enchantment.treasure(), enchantment.curse()) * level / max;
    }

    /** What an enchantment is worth at its highest level — what ranks it. */
    public double best(String key, int maxLevel, boolean treasure, boolean curse) {
        Double known = ranked.get(key.toLowerCase(Locale.ROOT));
        double value = known != null ? known : Math.max(1, maxLevel) * (treasure ? 2.0 : 1.0);
        return curse ? -value : value;
    }

    @Override
    public String describe() {
        return "how useful each enchantment is, and so what it is worth";
    }
}
