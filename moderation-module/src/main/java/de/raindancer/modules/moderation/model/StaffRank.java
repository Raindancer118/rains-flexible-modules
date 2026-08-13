package de.raindancer.modules.moderation.model;

import de.raindancer.modules.moderation.rules.StaffRule;
import org.bukkit.Material;
import org.bukkit.Server;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What ops can promote somebody to, and what it gets them.
 *
 * <h2>Why presets, when every node is toggleable anyway</h2>
 * Because "which of these nodes does a helper get?" is a question nobody should have to answer twice.
 * The server that answers it afresh each time is the server where one helper can ban and another cannot
 * for no reason either of them knows — and where nobody can say what "helper" means. The preset is the
 * answer written down once; the per-person toggles are for the exception, not the rule.
 *
 * <h2>Each tier contains the one below it</h2>
 * Not tidiness — it is what makes a promotion a promotion. If {@link #MOD} lacked something
 * {@link #TRIAL_MOD} had, promoting somebody would quietly take a power away, and they would report it as
 * a bug in whatever they happened to be doing at the time. {@code StaffRankTest} checks it.
 *
 * <h2>Where the lines fall, and why</h2>
 * <ul>
 *   <li><b>Trial</b> stops nobody doing anything: warn, read a record, talk to the staff. Enough to be
 *       useful and to be watched, and nothing that can go wrong in a way that needs undoing.</li>
 *   <li><b>Helper</b> can quiet somebody and look in a chest, but not remove them from the server and
 *       not take things out. That mute-without-ban split is the whole reason those are separate
 *       nodes.</li>
 *   <li><b>Moderator</b> is the full working set, including claim administration — a moderator dealing
 *       with a grief needs to act on the claim — but <em>not</em> the claim bypasses.</li>
 *   <li><b>Admin</b> adds the things that change the rules rather than apply them: the config, immunity,
 *       and the claim bypasses.</li>
 * </ul>
 */
public enum StaffRank {

    /** Learning the job. Can see everything and change almost nothing. */
    TRIAL_MOD("Trial Mod", 1, "gray", Material.STRING,
            "Can warn, read a record and handle reports. Nothing that stops a player doing anything."),

    /** The full working set. */
    MOD("Mod", 2, "aqua", Material.IRON_HELMET,
            "The full set: ban, mute, kick, freeze, notes, vanish, and claim administration."),

    /** Can change the rules, not only apply them. */
    ADMIN("Admin", 3, "gold", Material.DIAMOND_HELMET,
            "Everything a mod has, plus the settings, immunity and the claim bypasses."),

    /**
     * The person whose server it is.
     *
     * <h2>Why the owner is a rank rather than something outside the ladder</h2>
     * Because it is what people already mean by the top of it, and pretending otherwise led to the
     * arrangement where the highest rank this module knew about was weaker than a line somebody adds to
     * {@code ops.json} by hand. As a rank it is visible, it is granted and removed the same way as the
     * others, and it is on the record — which the hand-edited file never is.
     *
     * <p>It grants no nodes because it does not need to: an operator holds every permission of every
     * plugin on the server, present and future. That is exactly why the three ranks below it are not
     * op, and why this one is the only one that is.
     */
    OWNER("Owner", 4, "red", Material.NETHERITE_HELMET,
            "The server operator: every permission of every plugin, and the vanilla commands.");

    /**
     * Claim administration — what a moderator dealing with a grief needs.
     *
     * <p>Lets them act on somebody else's claim. Does <em>not</em> exempt them from anything.
     */
    public static final String CLAIM_ADMIN = "rec.admin";

    /**
     * The claim exemptions, which arrive with {@link #ADMIN} and not before.
     *
     * <p>Once three nodes and a count perk. The claim limit, the creation and fence costs, and drawing
     * inside a no-claim zone are not permission nodes any more — claims reads {@code /claimadmin bypass}'s
     * own toggle for all three now, which {@link #CLAIM_ADMIN} already reaches through {@code rec.admin}
     * implying {@code rec.bypass}. Granting them here would have been exactly the thing the toggle exists
     * to stop: a moderator with free unlimited claims is a moderator whose own building is invisible to
     * the rules everybody else plays under, handed out by a promotion rather than switched on for as long
     * as they are actually working. What is left is the one genuine rank perk, not a protection bypass:
     * holding more claims than anybody else is allowed.
     */
    public static final List<String> CLAIM_BYPASSES = List.of("rec.maxclaims.unlimited");
    /**
     * The staff warps, from {@link #MOD} upward.
     *
     * <p>One node opens every warp marked staff-only, which is how the staff room, the build world and
     * the event stage are reached. Not a trial's: a trial mod is somebody being watched while they
     * learn, and the staff warps lead to places where the things they have not been taught yet are
     * lying about.
     */
    public static final String WARP_STAFF = "rainswarps.warp.staff";

    /**
     * Making and moving warps, from {@link #ADMIN} upward.
     *
     * <p>A warp is a permanent, server-wide thing with a name everybody types. Reaching one is a
     * convenience; creating one is a decision about the shape of the server, and the difference is
     * the same one that puts a permanent ban an admin's side of the line.
     */
    public static final String WARP_MANAGE = "rainswarps.warp.manage";

    /**
     * The waiting and the cooling off, skipped — from {@link #MOD} upward.
     *
     * <p>These exist so a player cannot outrun a fight by teleporting, and a moderator walking to a
     * grief is the one person the delay is working against: by the time it is over, whoever they were
     * coming to see has finished and left. Not a trial's, for the same reason nothing else that
     * exempts somebody is: it is invisible to everybody else, and the first thing a player notices
     * when they find out.
     *
     * <p>{@code tpa.back} is here too — walking back out of somewhere is what makes going there
     * cheap, and a moderator who cannot get home again teleports twice.
     */
    public static final List<String> TELEPORT_BYPASSES = List.of(
            "tpa.bypass.warmup", "tpa.bypass.cooldown", "tpa.back",
            "homes.bypass.warmup", "homes.bypass.cooldown");

    /**
     * Unlimited homes, from {@link #ADMIN} upward.
     *
     * <p>With the claim exemptions, and for the same reason: a moderator with unlimited homes is one
     * whose own building plays by rules nobody else has. An admin is already trusted with that
     * distinction.
     */
    public static final String HOMES_UNLIMITED = "homes.unlimited";

    /**
     * Running a round of the Hunger Games, from {@link #MOD} upward.
     *
     * <p>The gamemaster node: call the deathmatch, drop supplies, revive somebody the plugin got wrong,
     * watch from spectator. Not a trial's — releasing forty people from their platforms is not something
     * to be learning on, and the mistakes cannot be taken back in front of an audience.
     *
     * <p>Deliberately not the admin node, which is the arena, the loot tables and the settings. The
     * hungergames module keeps those apart precisely so that a guest gamemaster brought in for one evening
     * cannot regenerate the arena mid-round, and a rank that granted both would collapse the distinction
     * for every moderator on the server.
     */
    public static final String HUNGERGAMES_GAMEMASTER = "hungergames.gamemaster";

    /**
     * The Hunger Games arena, loot and settings, from {@link #ADMIN} upward.
     *
     * <p>With the other things that change the rules rather than apply them. An admin here can rebuild the
     * arena from a schematic and rewrite what comes out of a chest, which is a decision about what the
     * tournament <em>is</em> — the same side of the line as the settings and the warp management.
     *
     * <p>{@code hungergames.protection.bypass} is deliberately absent, and its own class note says why: it
     * is meant for the ten minutes somebody is fixing something, not for a staff group. An admin who holds
     * it permanently is one who eventually mines the cornucopia by accident while everybody watches. Grant
     * it by hand, per person, on the permissions screen — the same treatment {@code /protect} gets.
     */
    public static final String HUNGERGAMES_ADMIN = "hungergames.admin";

    // rec.admin.nofee is deliberately absent. The claims plugin has always kept it off even for
    // operators, with the reasoning that an admin walking around the server should pay a claim's toll
    // like everybody else — and an owner whose own entry fees quietly do nothing stops noticing they
    // are configured. Grant it by hand, per person, on the permissions screen.

    private final String title;
    private final int weight;
    private final String colour;
    private final Material icon;
    private final String description;

    StaffRank(String title, int weight, String colour, Material icon, String description) {
        this.title = title;
        this.weight = weight;
        this.colour = colour;
        this.icon = icon;
        this.description = description;
    }

    /**
     * Every node this rank grants, the tiers below it included.
     *
     * <p><b>Derived, never listed.</b> Each {@link ModerationPermission} says which tier it arrives at,
     * so a permission added tomorrow lands in the right tiers the moment it exists — and cannot land in
     * none, because its constructor demands a tier. The version that listed each tier's nodes by hand
     * had one certain failure: somebody adds a permission, every tier still compiles, and the new power
     * belongs to nobody until a moderator asks why they cannot use it.
     */
    public Set<String> nodes() {
        Set<String> everything = new LinkedHashSet<>();
        for (ModerationPermission permission : ModerationPermission.values()) {
            if (permission.fromTier() <= weight) {
                everything.add(permission.node());
            }
        }
        // The claims half. Not derived, because these nodes belong to another module and cannot carry a
        // tier of their own — so they are the one place a tier is named, and the one place to look when
        // claims grows a permission worth granting.
        if (weight >= MOD.weight) {
            everything.add(CLAIM_ADMIN);
            // What a moderator on shift actually needs to get about: the staff warps, and not being
            // held by a warm-up while they walk to a grief.
            everything.add(WARP_STAFF);
            everything.addAll(TELEPORT_BYPASSES);
            // Running a round, not owning the tournament. See the constant.
            everything.add(HUNGERGAMES_GAMEMASTER);
        }
        if (weight >= ADMIN.weight) {
            // Making the server's furniture, rather than using it.
            everything.add(WARP_MANAGE);
            everything.add(HOMES_UNLIMITED);
            // The arena and the loot tables — building the tournament rather than running it.
            everything.add(HUNGERGAMES_ADMIN);
            // Protection is deliberately *not* here. It is not a power a rank confers: it is written
            // by the console with /protect and by nothing else, because a shield handed out by a
            // promotion is one the people it is aimed at can hand to each other.
            everything.addAll(CLAIM_BYPASSES);
        }
        // OP deliberately adds nothing. An operator already holds everything, so granting nodes on top
        // would be a list in a file that changes nothing — and the first person to read it would
        // reasonably conclude that op does *not* include them.
        return Set.copyOf(everything);
    }

    /** What to call it on screen. */
    public String title() {
        return title;
    }

    /** What holding it means, for the lore on the button that grants it. */
    public String describe() {
        return description;
    }

    /** Lowest first, so two ranks can be compared and a promotion can be told from a demotion. */
    public int weight() {
        return weight;
    }

    /**
     * Whether holding this rank means being a server operator.
     *
     * <p>Only the owner. The three ranks below hold nodes and nothing else — see the class note, and
     * {@code NobodyIsOppedTest}, which fails the build if that stops being true.
     */
    public boolean isOperator() {
        return this == OWNER;
    }

    /** The rung below this one, or empty at the bottom. */
    public Optional<StaffRank> below() {
        return ordinal() == 0 ? Optional.empty() : Optional.of(values()[ordinal() - 1]);
    }

    /** The rung above this one, or empty at the top. */
    public Optional<StaffRank> above() {
        return ordinal() + 1 >= values().length ? Optional.empty() : Optional.of(values()[ordinal() + 1]);
    }

    /** Whether this rank is the given one or above it. */
    public boolean isAtLeast(StaffRank other) {
        return other != null && weight >= other.weight;
    }

    public Material icon() {
        return icon;
    }

    /** The MiniMessage colour name, so every screen agrees what "Moderator" looks like. */
    public String colour() {
        return colour;
    }

    /** How it is typed and stored — lower case, so a file edited by hand still reads. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** One rank, however it was typed. */
    public static Optional<StaffRank> byName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (StaffRank rank : values()) {
            if (rank.key().equals(wanted)) {
                return Optional.of(rank);
            }
        }
        // The words people actually type. "op" for the owner because that is what it is called in
        // practice, and "trial"/"mod" because nobody types an underscore.
        return switch (wanted) {
            case "op", "operator" -> Optional.of(OWNER);
            case "trial", "trialmod" -> Optional.of(TRIAL_MOD);
            case "moderator" -> Optional.of(MOD);
            default -> Optional.empty();
        };
    }

    /** The words somebody types, in ladder order — for tab completion. */
    public static List<String> names() {
        List<String> names = new ArrayList<>();
        for (StaffRank rank : values()) {
            names.add(rank.key());
        }
        return names;
    }

    /**
     * Every node any rank can grant, which is also everything the per-person screen may toggle.
     *
     * <p>Derived from the ranks rather than listed again: a node this did not know about would be one
     * the toggle screen could not take away, and the person holding it would keep it for ever.
     */
    public static List<String> everyGrantableNode() {
        Set<String> everything = new LinkedHashSet<>(ADMIN.nodes());
        return List.copyOf(everything);
    }

    /**
     * {@link #everyGrantableNode()}, narrowed to what this server can actually act on.
     *
     * <h2>Why a second list, rather than filtering {@code everyGrantableNode()} itself</h2>
     * That one has to stay complete: a promotion sets every node a rank carries, on purpose, whether or
     * not the module that owns it happens to be installed today — {@code homes.unlimited} granted to a
     * moderator on a server without Homes yet is exactly what lets installing Homes tomorrow hand it to
     * them for free, rather than a second migration walking every existing moderator's grants. Filtering
     * that list would quietly take that property away, and every test that asserts a tier's nodes would
     * have to stand a fake server up to keep asserting them.
     *
     * <p>A screen offering to toggle a node answers a different question: "what could clicking here
     * actually change, right now?" This module does not depend on claims, warps, homes, tpa or the
     * Hunger Games — see the class note on why those nodes are string literals rather than references —
     * so it cannot ask a registry whether any of them is installed. What it can ask is the server: a
     * node one of those modules owns is only ever a real Bukkit permission once that module has
     * actually run {@code PermissionNodes.register()} during its own {@code enable()}, which happens
     * regardless of which plugin or classloader ends up hosting it. A node nothing has registered is a
     * string nobody reads, and offering to grant it is a button that does nothing while looking like it
     * does something.
     *
     * @param server null-safe: answers the unfiltered list when there is no server to ask, which is the
     *               right thing for a test building this list without one
     */
    public static List<String> grantableNodesOn(Server server) {
        List<String> everything = everyGrantableNode();
        if (server == null) {
            return everything;
        }
        List<String> live = new ArrayList<>();
        for (String node : everything) {
            if (server.getPluginManager().getPermission(node) != null) {
                live.add(node);
            }
        }
        return List.copyOf(live);
    }
}
