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
    WARN("warn", "Put a warning on somebody's record", Material.YELLOW_BANNER, 1, false),
    HISTORY("history", "Read what has happened to somebody", Material.BOOK, 1, true),
    STAFF_CHAT("staffchat", "Talk in the staff channel", Material.OAK_SIGN, 1, true),
    REPORTS("reports", "Read the report queue and deal with what is in it", Material.BELL, 1, true),
    INVSEE("invsee", "Look inside somebody's inventory", Material.CHEST, 1, true),

    // ── from a mod upward: the full working set ────────────────────────────────────────────
    MUTE("mute", "Stop somebody talking, and let them talk again", Material.PAPER, 2, false),
    KICK("kick", "Throw somebody off, once", Material.LEATHER_BOOTS, 2, false),
    TEMPBAN("tempban", "Ban somebody for a limited time, up to the configured maximum",
            Material.IRON_DOOR, 2, false),
    FREEZE("freeze", "Stop somebody building while you talk to them", Material.PACKED_ICE, 2, false),
    NOTES("notes", "Read and write the staff notes about somebody", Material.WRITABLE_BOOK, 2, true),
    VANISH("vanish", "Go invisible, and see who else is", Material.GLASS, 2, true),
    INVSEE_EDIT("invsee.edit", "Change what is in it", Material.HOPPER, 2, true),
    // Reaching /promote and /demote at all. *What* somebody may hand out is PromotionRule's answer —
    // never their own rank or above — so this is the door and the rule is the lock. Distinct from
    // PromoteCommand.USE, which is the owner's and is in no preset.
    APPOINT("appoint", "Appoint and remove staff below your own rank", Material.NAME_TAG, 2, false),
    FLY("fly", "Fly, and let somebody else fly", Material.FEATHER, 2, true),
    GOD("god", "Be invulnerable, and make somebody else invulnerable", Material.TOTEM_OF_UNDYING, 2, true),
    HEAL("heal", "Restore somebody to full health", Material.GOLDEN_APPLE, 2, true),
    FEED("feed", "Fill somebody's hunger bar", Material.COOKED_BEEF, 2, true),

    // ── admin upward: changes the rules rather than applying them ──────────────────────────
    // A permanent ban is here rather than with the mods deliberately. Stopping somebody *now* is a
    // mod's job — TEMPBAN, above — but ending their time on the server for good, with nobody else
    // having looked, is not.
    BAN("ban", "Ban somebody for any length, permanently included, and lift any ban",
            Material.BARRIER, 3, false),
    CONFIG("config", "Change how moderation itself behaves", Material.COMPARATOR, 3, true),
    // Admin, not mod. One hit kills anything — including, in the wrong hands, every animal on a farm
    // somebody spent a fortnight breeding, in about eleven seconds.
    INSTAKILL("instakill", "Kill anything in one hit", Material.NETHERITE_SWORD, 3, true),
    // The mirror of HEAL and FEED, one rank higher. Restoring somebody is unremarkable and undoes
    // itself; taking half their health from a menu, silently, kills a player in a fight they were
    // winning. A server that wants a particular mod to have it can toggle it on for that one person.
    HURT("hurt", "Take half of somebody's health", Material.IRON_SWORD, 3, true),
    STARVE("starve", "Empty most of somebody's hunger bar", Material.ROTTEN_FLESH, 3, true),

    // ── the world tools ───────────────────────────────────────────────────────────────────
    // A vein is a mod's, and the two mob ones are an admin's, which is the split the server owner
    // asked for and it is the right one for a reason worth writing down.
    //
    // Burying ore is generous and local: it costs nothing anybody had, it touches only ground the
    // world generated, and the worst version of it is a mod being over-friendly to one player. That
    // is a conversation, not an incident.
    //
    // A wave is neither. It arrives around somebody who did not ask for it, it can kill them and
    // everything they were carrying, and forty creatures let loose next to a farm is damage nobody
    // can put back. It is also the one tool here whose effect outlives the click.
    SPAWN_ORE("spawn.ore", "Bury a vein of ore in the ground you are looking at",
            Material.IRON_ORE, 2, true),
    SPAWN_MOBS("spawn.mobs", "Call up a pack of creatures, or a wave of them over time",
            Material.ZOMBIE_HEAD, 3, true);

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
    private final boolean aimableAtSelf;

    ModerationPermission(String suffix, String description, Material icon, int fromTier,
                         boolean aimableAtSelf) {
        this.suffix = suffix;
        this.description = description;
        this.icon = icon;
        this.fromTier = fromTier;
        this.aimableAtSelf = aimableAtSelf;
    }

    /**
     * Whether this may be pointed at the person doing it.
     *
     * <h2>Why this is a property of the permission</h2>
     * Because the answer differs per action and the question is asked from two places — the command and
     * the screen — which is exactly how they came to disagree. {@code /heal} worked and
     * {@code /heal <your own name>} was refused, because omitting the name took a different branch from
     * naming yourself: the same request, from the same person, answered two ways.
     *
     * <p>False for punishments. A moderator who bans themselves cannot come back and lift it, which has
     * happened on a real server and needed a database edit to undo. False for {@link #APPOINT} too:
     * promotion is somebody else's to give.
     *
     * <p>Required by the constructor for the same reason {@link #fromTier} is — so a permission added
     * later has to answer, rather than defaulting into whichever answer is more surprising.
     */
    public boolean aimableAtSelf() {
        return aimableAtSelf;
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
