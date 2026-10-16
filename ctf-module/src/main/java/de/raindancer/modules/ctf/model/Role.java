package de.raindancer.modules.ctf.model;

import org.bukkit.Material;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** A class: what somebody fights with, and what they can do besides. Picked at every respawn. */
public enum Role {
    SOLDIER("Soldier", Material.IRON_SWORD, true, false, "Sword, bow, iron armour.", "Good at everything, best at nothing."),
    SCOUT("Scout", Material.FEATHER, true, false, "Fast, light armour, wind charges.", "Takes a flag at once — no waiting."),
    TANK("Tank", Material.SHIELD, false, false, "Heavy armour and a shield, slow.", "Cannot carry a flag — escorts it."),
    ARCHER("Archer", Material.BOW, true, false, "A strong bow and flame arrows.", "An arrow makes a carrier glow."),
    MEDIC("Medic", Material.GOLDEN_APPLE, true, false, "Heals teammates near them.", "Once a life: right click a teammate to mend them."),
    ENGINEER("Engineer", Material.PISTON, true, true, "Barricades, jump pads and a turret.", "Only when building is on."),
    NINJA("Ninja", Material.ENDER_EYE, true, false, "Sneak to vanish for a moment.", "Hitting or carrying ends it."),
    TRAPPER("Trapper", Material.COBWEB, true, false, "Cobweb traps and tripwire alarms.", "Best near your own flag.");

    private final String title;
    private final Material icon;
    private final boolean mayCarry;
    private final boolean needsBuilding;
    private final String[] lines;

    Role(String title, Material icon, boolean mayCarry, boolean needsBuilding, String... lines) {
        this.title = title;
        this.icon = icon;
        this.mayCarry = mayCarry;
        this.needsBuilding = needsBuilding;
        this.lines = lines;
    }

    public String title() {
        return title;
    }

    public Material icon() {
        return icon;
    }

    public boolean mayCarry() {
        return mayCarry;
    }

    public boolean needsBuilding() {
        return needsBuilding;
    }

    public List<String> lines() {
        return List.of(lines);
    }

    public static Optional<Role> parse(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String name = typed.trim().toUpperCase(Locale.ROOT);
        for (Role role : values()) {
            if (role.name().equals(name)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
