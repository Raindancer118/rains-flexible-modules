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

    BAN("ban", "Ban somebody and lift a ban", Material.BARRIER),
    MUTE("mute", "Stop somebody talking, and let them talk again", Material.PAPER),
    KICK("kick", "Throw somebody off, once", Material.LEATHER_BOOTS),
    WARN("warn", "Put a warning on somebody's record", Material.YELLOW_BANNER),
    FREEZE("freeze", "Stop somebody building while you talk to them", Material.PACKED_ICE),
    HISTORY("history", "Read what has happened to somebody", Material.BOOK),
    NOTES("notes", "Read and write the staff notes about somebody", Material.WRITABLE_BOOK),
    REPORTS("reports", "Read the report queue and deal with what is in it", Material.BELL),
    VANISH("vanish", "Go invisible, and see who else is", Material.GLASS),
    INVSEE("invsee", "Look inside somebody's inventory", Material.CHEST),
    INVSEE_EDIT("invsee.edit", "Change what is in it", Material.HOPPER),
    STAFF_CHAT("staffchat", "Talk in the staff channel", Material.OAK_SIGN),
    CONFIG("config", "Change how moderation itself behaves", Material.COMPARATOR);

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

    ModerationPermission(String suffix, String description, Material icon) {
        this.suffix = suffix;
        this.description = description;
        this.icon = icon;
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
