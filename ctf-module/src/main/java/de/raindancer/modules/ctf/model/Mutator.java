package de.raindancer.modules.ctf.model;

import org.bukkit.Material;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Something odd about one round. Picked by vote or by chance. */
public enum Mutator {
    NONE("No twist", Material.BARRIER, "Plain Capture the Flag."),
    LOW_GRAVITY("Low gravity", Material.FEATHER, "Everybody jumps high and falls slowly."),
    TINY("Tiny", Material.RABBIT_FOOT, "Everybody is half their size —", "and so are the hitboxes."),
    GIANT("Giants", Material.TOTEM_OF_UNDYING, "Everybody is one and a half times as tall."),
    ONE_HIT("One hit", Material.NETHERITE_SWORD, "Every hit kills. Respawns are quick."),
    ELYTRA("Elytra", Material.ELYTRA, "Everybody flies: elytra and rockets for all.", "A flag carrier cannot use rockets."),
    KNOCKBACK("Knockback chaos", Material.STICK, "Every hit throws hard.", "The edge of the map is the danger."),
    SWAP_BASES("Swap time", Material.ENDER_PEARL, "Every two minutes the sides swap bases."),
    SPLEEF("Spleef", Material.DIAMOND_SHOVEL, "Building on, shovels only —", "and the floor breaks away under you.");

    private final String title;
    private final Material icon;
    private final String[] lines;

    Mutator(String title, Material icon, String... lines) {
        this.title = title;
        this.icon = icon;
        this.lines = lines;
    }

    public String title() {
        return title;
    }

    public Material icon() {
        return icon;
    }

    public List<String> lines() {
        return List.of(lines);
    }

    /** Whether the round needs blocks to be breakable whatever the building setting says. */
    public boolean needsBreaking() {
        return this == SPLEEF;
    }

    public static Optional<Mutator> parse(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String name = typed.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (Mutator mutator : values()) {
            if (mutator.name().equals(name)) {
                return Optional.of(mutator);
            }
        }
        return Optional.empty();
    }
}
