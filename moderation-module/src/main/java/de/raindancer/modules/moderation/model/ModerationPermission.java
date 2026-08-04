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

    // ── from a trial mod upward: can see everything, and change almost nothing ─────────────
    // Deliberately generous about *reading*. A trial who cannot see a record or a report cannot learn
    // the job, and the whole point of the rank is that they are useful while being watched.
    WARN("warn", "Put a warning on somebody's record", Material.YELLOW_BANNER, 1),
    HISTORY("history", "Read what has happened to somebody", Material.BOOK, 1),
    STAFF_CHAT("staffchat", "Talk in the staff channel", Material.OAK_SIGN, 1),
    REPORTS("reports", "Read the report queue and deal with what is in it", Material.BELL, 1),
    INVSEE("invsee", "Look inside somebody's inventory", Material.CHEST, 1),

    // ── from a mod upward: the full working set ────────────────────────────────────────────
    MUTE("mute", "Stop somebody talking, and let them talk again", Material.PAPER, 2),
    KICK("kick", "Throw somebody off, once", Material.LEATHER_BOOTS, 2),
    TEMPBAN("tempban", "Ban somebody for a limited time, up to the configured maximum",
            Material.IRON_DOOR, 2),
    FREEZE("freeze", "Stop somebody building while you talk to them", Material.PACKED_ICE, 2),
    NOTES("notes", "Read and write the staff notes about somebody", Material.WRITABLE_BOOK, 2),
    VANISH("vanish", "Go invisible, and see who else is", Material.GLASS, 2),
    INVSEE_EDIT("invsee.edit", "Change what is in it", Material.HOPPER, 2),
    // Reaching /promote and /demote at all. *What* somebody may hand out is PromotionRule's answer —
    // never their own rank or above — so this is the door and the rule is the lock. Distinct from
    // PromoteCommand.USE, which is the owner's and is in no preset.
    APPOINT("appoint", "Appoint and remove staff below your own rank", Material.NAME_TAG, 2),
    FLY("fly", "Fly, and let somebody else fly", Material.FEATHER, 2),
    GOD("god", "Be invulnerable, and make somebody else invulnerable", Material.TOTEM_OF_UNDYING, 2),
    HEAL("heal", "Restore somebody to full health", Material.GOLDEN_APPLE, 2),
    FEED("feed", "Fill somebody's hunger bar", Material.COOKED_BEEF, 2),

    // ── admin upward: changes the rules rather than applying them ──────────────────────────
    // A permanent ban is here rather than with the mods deliberately. Stopping somebody *now* is a
    // mod's job — TEMPBAN, above — but ending their time on the server for good, with nobody else
    // having looked, is not.
    BAN("ban", "Ban somebody for any length, permanently included, and lift any ban",
            Material.BARRIER, 3),
    CONFIG("config", "Change how moderation itself behaves", Material.COMPARATOR, 3),
    // Admin, not mod. One hit kills anything — including, in the wrong hands, every animal on a farm
    // somebody spent a fortnight breeding, in about eleven seconds.
    INSTAKILL("instakill", "Kill anything in one hit", Material.NETHERITE_SWORD, 3),
    // The mirror of HEAL and FEED, one rank higher. Restoring somebody is unremarkable and undoes
    // itself; taking half their health from a menu, silently, kills a player in a fight they were
    // winning. A server that wants a particular mod to have it can toggle it on for that one person.
    HURT("hurt", "Take half of somebody's health", Material.IRON_SWORD, 3),
    STARVE("starve", "Empty most of somebody's hunger bar", Material.ROTTEN_FLESH, 3);

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
     * The lowest staff tier that gets this, by weight — 1 trial mod, 2 mod, 3 admin, 4 owner.
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
