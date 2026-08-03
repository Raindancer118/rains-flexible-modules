package de.raindancer.modules.claims.visual;

import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a potion effect into an icon a player recognises at a glance.
 * <p>
 * Where a real brewed potion grants the effect, that potion is shown: setting the base potion type makes
 * the client render the actual bottle in its proper colour, which is far more readable than a row of
 * identical pink flasks. Effects with no vanilla potion fall back to whatever they actually come from —
 * a nautilus shell for Conduit Power, a shulker shell for Levitation, and so on.
 */
public final class EffectIcons {

    /** Where an effect comes from when no potion brews it. */
    private static final Map<String, Material> SOURCES = new HashMap<>();

    static {
        SOURCES.put("conduit_power", Material.NAUTILUS_SHELL);
        SOURCES.put("dolphins_grace", Material.HEART_OF_THE_SEA);
        SOURCES.put("absorption", Material.GOLDEN_APPLE);
        SOURCES.put("health_boost", Material.ENCHANTED_GOLDEN_APPLE);
        SOURCES.put("resistance", Material.SHIELD);
        SOURCES.put("saturation", Material.COOKED_BEEF);
        SOURCES.put("glowing", Material.SPECTRAL_ARROW);
        SOURCES.put("levitation", Material.SHULKER_SHELL);
        SOURCES.put("hero_of_the_village", Material.EMERALD);
        SOURCES.put("haste", Material.BEACON);
        SOURCES.put("mining_fatigue", Material.PRISMARINE_SHARD);
        SOURCES.put("bad_omen", Material.OMINOUS_BOTTLE);
        SOURCES.put("trial_omen", Material.OMINOUS_BOTTLE);
        SOURCES.put("raid_omen", Material.OMINOUS_BOTTLE);
        SOURCES.put("nausea", Material.PUFFERFISH);
        SOURCES.put("blindness", Material.INK_SAC);
        SOURCES.put("darkness", Material.SCULK_SHRIEKER);
        SOURCES.put("hunger", Material.ROTTEN_FLESH);
        SOURCES.put("wither", Material.WITHER_SKELETON_SKULL);
        SOURCES.put("unluck", Material.FERMENTED_SPIDER_EYE);
        SOURCES.put("wind_charged", Material.WIND_CHARGE);
        SOURCES.put("weaving", Material.COBWEB);
        SOURCES.put("oozing", Material.SLIME_BLOCK);
        SOURCES.put("infested", Material.INFESTED_STONE);
    }

    private EffectIcons() {
    }

    /**
     * The plain potion granting this effect, if one exists.
     * <p>
     * Extended and strong variants are skipped so the icon is the everyday bottle rather than
     * "Potion of Long Swiftness".
     */
    public static Optional<PotionType> potionFor(PotionEffectType type) {
        PotionType best = null;
        for (PotionType candidate : Registry.POTION) {
            String key = candidate.getKey().getKey();
            if (key.startsWith("long_") || key.startsWith("strong_")) {
                continue;
            }
            for (PotionEffect effect : candidate.getPotionEffects()) {
                if (effect.getType().equals(type)) {
                    // Prefer the shortest name, which is the base potion for that effect.
                    if (best == null || key.length() < best.getKey().getKey().length()) {
                        best = candidate;
                    }
                    break;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** A recognisable icon for the effect: the real potion, or the thing it comes from. */
    public static ItemStack iconFor(PotionEffectType type) {
        Optional<PotionType> potion = potionFor(type);
        if (potion.isPresent()) {
            ItemStack stack = new ItemStack(Material.POTION);
            stack.editMeta(PotionMeta.class, meta -> meta.setBasePotionType(potion.get()));
            return stack;
        }
        Material source = SOURCES.get(type.getKey().getKey());
        return new ItemStack(source == null ? Material.GLASS_BOTTLE : source);
    }

    /** Whether the icon shows a real brewed potion, so the GUI can say where it comes from. */
    public static boolean hasRealPotion(PotionEffectType type) {
        return potionFor(type).isPresent();
    }

    /** Short note on the effect's origin, for the lore line. */
    public static String sourceLabel(PotionEffectType type) {
        Optional<PotionType> potion = potionFor(type);
        if (potion.isPresent()) {
            return "brewed as " + potion.get().getKey().getKey().replace('_', ' ');
        }
        Material source = SOURCES.get(type.getKey().getKey());
        return source == null
                ? "no potion brews this"
                : "comes from " + source.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
