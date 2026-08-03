package de.raindancer.modules.moderation.model;

import de.raindancer.modules.moderation.rules.StaffRule;
import org.bukkit.Material;

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
     * <p>A moderator with free unlimited claims is a moderator whose own building is invisible to the
     * rules everybody else plays under — and the first thing a player notices when they find out.
     */
    public static final List<String> CLAIM_BYPASSES = List.of(
            "rec.admin.nocost", "rec.admin.nolimit", "rec.admin.zonebypass",
            "rec.maxclaims.unlimited");
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
        }
        if (weight >= ADMIN.weight) {
            everything.add(StaffRule.IMMUNE);
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
}
