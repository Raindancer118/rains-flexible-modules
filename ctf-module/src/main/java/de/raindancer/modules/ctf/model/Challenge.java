package de.raindancer.modules.ctf.model;

import org.bukkit.Material;

/** A daily challenge: something to do a number of times today. */
public enum Challenge {
    CAPTURE("Capture %d flags", Material.RED_BANNER, 3, 150, null),
    RETURN("Return %d flags", Material.SHIELD, 4, 120, null),
    KILL("Take down %d players", Material.IRON_SWORD, 15, 120, null),
    CARRIER_KILL("Stop %d flag carriers", Material.CROSSBOW, 3, 150, null),
    WIN("Win %d rounds", Material.GOLDEN_HELMET, 2, 200, null),
    POWER_UP("Take %d power-ups", Material.BLAZE_POWDER, 6, 100, null),
    ESCORT("Make %d kills escorting a carrier", Material.IRON_CHESTPLATE, 4, 140, null),
    SCOUT_CAPTURE("Capture %d flags as a Scout", Material.FEATHER, 2, 200, Role.SCOUT),
    ARCHER_KILL("Take down %d players as an Archer", Material.BOW, 8, 160, Role.ARCHER),
    MEDIC_PLAY("Play %d rounds as a Medic", Material.GOLDEN_APPLE, 2, 140, Role.MEDIC);

    private final String text;
    private final Material icon;
    private final int goal;
    private final int xp;
    private final Role role;

    Challenge(String text, Material icon, int goal, int xp, Role role) {
        this.text = text;
        this.icon = icon;
        this.goal = goal;
        this.xp = xp;
        this.role = role;
    }

    public String describe() {
        return String.format(java.util.Locale.ROOT, text, goal);
    }

    public Material icon() {
        return icon;
    }

    public int goal() {
        return goal;
    }

    /** Experience it gives when done. */
    public int xp() {
        return xp;
    }

    /** The class it has to be done as, if any. */
    public java.util.Optional<Role> role() {
        return java.util.Optional.ofNullable(role);
    }
}
