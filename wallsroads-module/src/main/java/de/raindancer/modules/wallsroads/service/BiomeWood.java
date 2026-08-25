package de.raindancer.modules.wallsroads.service;

import java.util.Locale;
import java.util.Map;

/**
 * Which wood a place builds with: the wood growing beside it.
 *
 * <h2>Why this is worth having at all</h2>
 * A trestle bridge over a mangrove swamp built out of oak looks imported. The same bridge in mangrove
 * looks like something the people who live there put up, and that is the entire difference between a
 * structure that belongs in a landscape and one dropped into it.
 *
 * <p>Matched on the biome's own name, and by fragment rather than by an exhaustive list: Mojang adds
 * biomes and datapacks invent them, and a lookup table that has to name every one is a table that is
 * wrong by the next update. Anything unrecognised is oak, which is what a plain bridge has always
 * been.
 */
public final class BiomeWood {

    /** Fragment of a biome name → the wood that grows there. Order matters: first match wins. */
    private static final Map<String, String> BY_FRAGMENT = new java.util.LinkedHashMap<>();

    static {
        BY_FRAGMENT.put("mangrove", "MANGROVE");
        BY_FRAGMENT.put("cherry", "CHERRY");
        BY_FRAGMENT.put("bamboo", "BAMBOO");
        BY_FRAGMENT.put("dark_forest", "DARK_OAK");
        BY_FRAGMENT.put("birch", "BIRCH");
        BY_FRAGMENT.put("jungle", "JUNGLE");
        BY_FRAGMENT.put("savanna", "ACACIA");
        BY_FRAGMENT.put("desert", "ACACIA");
        BY_FRAGMENT.put("badlands", "ACACIA");
        BY_FRAGMENT.put("taiga", "SPRUCE");
        BY_FRAGMENT.put("snowy", "SPRUCE");
        BY_FRAGMENT.put("grove", "SPRUCE");
        BY_FRAGMENT.put("peaks", "SPRUCE");
        BY_FRAGMENT.put("windswept_hills", "SPRUCE");
        BY_FRAGMENT.put("pale_garden", "PALE_OAK");
        BY_FRAGMENT.put("crimson", "CRIMSON");
        BY_FRAGMENT.put("warped", "WARPED");
    }

    private BiomeWood() {
    }

    public static String logFor(String biome) {
        String family = familyOf(biome);
        // The nether woods are stems, not logs, and there is no such block as a CRIMSON_LOG.
        if (family.equals("CRIMSON") || family.equals("WARPED")) {
            return family + "_STEM";
        }
        if (family.equals("BAMBOO")) {
            return "BAMBOO_BLOCK";
        }
        return family + "_LOG";
    }

    public static String strippedFor(String biome) {
        return "STRIPPED_" + logFor(biome);
    }

    public static String planksFor(String biome) {
        return familyOf(biome) + "_PLANKS";
    }

    public static String fenceFor(String biome) {
        return familyOf(biome) + "_FENCE";
    }

    public static String slabFor(String biome) {
        return familyOf(biome) + "_SLAB";
    }

    private static String familyOf(String biome) {
        if (biome == null || biome.isBlank()) {
            return "OAK";
        }
        String name = biome.toLowerCase(Locale.ROOT);
        int namespace = name.indexOf(':');
        if (namespace >= 0) {
            name = name.substring(namespace + 1);
        }
        for (Map.Entry<String, String> entry : BY_FRAGMENT.entrySet()) {
            if (name.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "OAK";
    }
}
