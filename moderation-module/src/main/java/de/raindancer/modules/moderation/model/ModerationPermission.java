package de.raindancer.modules.moderation.model;

import org.bukkit.Material;

/**
 * What a member of staff is allowed to do.
 *
 * <h2>Why an enum and not strings at the call sites</h2>
 * A stringly typed permission is a typo that compiles, has no find-usages, and fails open or closed
 * depending on which side of the check it happens to be on. The plugin this replaces wrote
 * {@code "rainsmoderation.ban"} out in four files, one of them as {@code "rainsmoderation.bans"} — a
 * command nobody could use, and nothing to find it by.
 *
 * <h2>Why they are split as finely as this</h2>
 * Because the split is the only thing that makes a trial helper possible. Somebody who can mute must
 * not thereby be able to ban; somebody who may look inside an inventory must not thereby be able to
 * take things out of it. Both of those were one node before, and both were granted to people who only
 * needed the smaller half.
 */
public enum ModerationPermission {

    // ── from a trial upward: useful, watched, and stops nobody doing anything ──────────────
    WARN("warn", "Put a warning on somebody's record", Material.YELLOW_BANNER, 1),
    HISTORY("history", "Read what has happened to somebody", Material.BOOK, 1),
    STAFF_CHAT("staffchat", "Talk in the staff channel", Material.OAK_SIGN, 1),

    // ── from a helper upward: can quiet somebody, and look ─────────────────────────────────
    MUTE("mute", "Stop somebody talking, and let them talk again", Material.PAPER, 2),
    KICK("kick", "Throw somebody off, once", Material.LEATHER_BOOTS, 2),
    REPORTS("reports", "Read the report queue and deal with what is in it", Material.BELL, 2),
    INVSEE("invsee", "Look inside somebody's inventory", Material.CHEST, 2),

    // ── from a moderator upward: the full working set ──────────────────────────────────────
    BAN("ban", "Ban somebody and lift a ban", Material.BARRIER, 3),
    FREEZE("freeze", "Stop somebody building while you talk to them", Material.PACKED_ICE, 3),
    NOTES("notes", "Read and write the staff notes about somebody", Material.WRITABLE_BOOK, 3),
    VANISH("vanish", "Go invisible, and see who else is", Material.GLASS, 3),
    INVSEE_EDIT("invsee.edit", "Change what is in it", Material.HOPPER, 3),

    // ── admin only: changes the rules rather than applying them ────────────────────────────
    CONFIG("config", "Change how moderation itself behaves", Material.COMPARATOR, 4);

    /**
     * The one prefix everything is under.
     *
     * <p>So that a wildcard grant an owner writes once actually grants everything, rather than
     * everything except the two nodes somebody put in a different family.
     */
    public static final String PREFIX = "rains.moderation.";

    private final String suffix;
    private final String description;
    private final Material icon;
    private final int fromTier;

    ModerationPermission(String suffix, String description, Material icon, int fromTier) {
        this.suffix = suffix;
        this.description = description;
        this.icon = icon;
        this.fromTier = fromTier;
    }

    /**
     * The lowest staff tier that gets this, by weight — 1 trial, 2 helper, 3 moderator, 4 admin.
     *
     * <h2>Why the tier lives here rather than in a list inside {@code StaffRank}</h2>
     * Because a list is a second place to remember. The version that listed each tier's nodes by hand
     * had exactly one failure mode, and it was certain: somebody adds a permission, every tier keeps
     * working, and the new power belongs to nobody — silently, until a moderator asks why they cannot
     * use it. Here the constructor <em>requires</em> a tier, so the question is asked at the moment the
     * permission is written and cannot be skipped.
     *
     * <p>An {@code int} rather than a {@code StaffRank} on purpose: two enums referring to each other
     * initialise in whichever order the classloader happens to reach them, and the loser sees nulls.
     */
    public int fromTier() {
        return fromTier;
    }

    /** The node as Bukkit matches it — literally, and case-sensitively. */
    public String node() {
        return PREFIX + suffix;
    }

    /** What holding it lets somebody do, for the page that lists them. */
    public String describe() {
        return description;
    }

    public Material icon() {
        return icon;
    }
}
