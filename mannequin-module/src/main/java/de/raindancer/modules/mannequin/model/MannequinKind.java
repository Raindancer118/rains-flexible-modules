package de.raindancer.modules.mannequin.model;

import org.bukkit.entity.EntityType;

/**
 * The curated list of mob types a training dummy may be spawned as — deliberately not "any {@link
 * EntityType}", the same way the loadout screen offers a curated shortlist of materials rather than
 * the whole registry.
 *
 * <h2>Why a curated enum rather than an open {@code EntityType}</h2>
 * Every kind here needs its own AI-suppression recipe ({@code
 * de.raindancer.modules.mannequin.service.MannequinService#spawn}), and two of them — {@link
 * #WITHER} and {@link #IRON_GOLEM} — have no equipment slots at all, which every screen that offers
 * a loadout button has to know. An open list would mean the loadout screen, the skin screen and the
 * spawn dispatch would each need their own guess at whether an arbitrary {@code EntityType} supports
 * either; five known kinds, five known answers, kept here once.
 */
public enum MannequinKind {

    /** The original mannequin: Paper's own humanoid {@code org.bukkit.entity.Mannequin}, skinned. */
    PLAYER(EntityType.PLAYER, true, true, false, "Player", 20.0),
    ZOMBIE(EntityType.ZOMBIE, true, false, true, "Zombie", 20.0),
    SKELETON(EntityType.SKELETON, true, false, true, "Skeleton", 20.0),
    /** No armor or weapon slots in vanilla — never offered a loadout screen. */
    WITHER(EntityType.WITHER, false, false, false, "Wither", 300.0),
    /** No armor or weapon slots in vanilla either — never offered a loadout screen. */
    IRON_GOLEM(EntityType.IRON_GOLEM, false, false, false, "Iron Golem", 100.0);

    private final EntityType bukkitType;
    private final boolean supportsLoadout;
    private final boolean supportsSkin;
    private final boolean burnsInDaylight;
    private final String displayName;
    private final double defaultMaxHealth;

    MannequinKind(EntityType bukkitType, boolean supportsLoadout, boolean supportsSkin,
                 boolean burnsInDaylight, String displayName, double defaultMaxHealth) {
        this.bukkitType = bukkitType;
        this.supportsLoadout = supportsLoadout;
        this.supportsSkin = supportsSkin;
        this.burnsInDaylight = burnsInDaylight;
        this.displayName = displayName;
        this.defaultMaxHealth = defaultMaxHealth;
    }

    /** The real Bukkit entity type this kind is spawned as. */
    public EntityType bukkitType() {
        return bukkitType;
    }

    /** Whether this kind has real equipment slots and is offered the loadout screen. */
    public boolean supportsLoadout() {
        return supportsLoadout;
    }

    /** Whether this kind wears a player skin — only ever true for {@link #PLAYER}. */
    public boolean supportsSkin() {
        return supportsSkin;
    }

    /** Whether vanilla would otherwise set this kind alight in daylight without a helmet. */
    public boolean burnsInDaylight() {
        return burnsInDaylight;
    }

    /** A human-readable label for a button or a chat line. */
    public String displayName() {
        return displayName;
    }

    /**
     * This kind's own vanilla-realistic max health — a Wither dummy starts at 300, not a bare
     * player's 20, so its native boss bar reads as a real Wither's would from the first hit rather
     * than needing a trip through the Health screen's presets first. {@link
     * de.raindancer.modules.mannequin.model.Mannequin#resolvedMaxHealth} only falls back to this
     * when the owner has not set an explicit override.
     */
    public double defaultMaxHealth() {
        return defaultMaxHealth;
    }

    /**
     * Case-insensitive lookup by name, for the command and the store. {@code null} or unmatched
     * both come back empty rather than throwing — a typo'd kind is something a caller reports, not
     * something this has an opinion about.
     */
    public static java.util.Optional<MannequinKind> byName(String name) {
        if (name == null || name.isBlank()) {
            return java.util.Optional.empty();
        }
        for (MannequinKind kind : values()) {
            if (kind.name().equalsIgnoreCase(name.trim())) {
                return java.util.Optional.of(kind);
            }
        }
        return java.util.Optional.empty();
    }
}
