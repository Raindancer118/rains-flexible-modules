package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.StaffRank;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * One person's permissions, one at a time.
 *
 * <h2>What this is for</h2>
 * The exception. The presets are the rule — one helper who is trusted to ban should not require
 * inventing a fifth tier, and the alternative to a screen like this is a server owner editing a YAML
 * file and restarting.
 *
 * <h2>Why every node is drawn, held or not</h2>
 * Because the question this page answers is "what <em>could</em> she have?", not only "what does she
 * have?". A page listing only what somebody holds cannot be used to grant anything, and one that hid
 * the rest would make the page a different shape per person — so nobody could be told "the third one
 * along".
 *
 * <p>Green means held, grey means not, and the lore says which of those the rank asked for. That last
 * part is what makes drift visible: a node shown green and marked "not part of Mod" is one somebody
 * granted by hand three months ago.
 */
public final class PermissionsMenu extends ModerationList<String> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final UUID subject;
    private final String subjectName;

    public PermissionsMenu(ModerationServices services, Player viewer, Menu parent, UUID subject,
                           String subjectName) {
        super(services, viewer, parent);
        this.subject = subject;
        this.subjectName = subjectName;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Permissions — <white>" + subjectName);
    }

    @Override
    public String breadcrumb() {
        return "Permissions";
    }

    /**
     * Whether this viewer may change this person's permissions.
     *
     * <p>The demote rule, because handing somebody a node and taking one away is the same authority as
     * re-ranking them: only somebody below you, and only if the server allows it at all.
     */
    private boolean mayChange() {
        return services().promotionRule().mayDemote(viewer.getUniqueId(), subject).isAllowed();
    }

    @Override
    protected List<String> entries() {
        // Every node any rank can grant and this server can actually act on, so the page can give as
        // well as take without offering a toggle for a module that is not installed here — see
        // StaffRank.grantableNodesOn() for why that is a narrower list than everyGrantableNode(). Held
        // ones first, since "what has she got" is the more common question of the two.
        List<String> everything = new ArrayList<>(StaffRank.grantableNodesOn(services().server()));
        everything.sort((left, right) -> {
            boolean leftHeld = services().staff().has(subject, left);
            boolean rightHeld = services().staff().has(subject, right);
            if (leftHeld != rightHeld) {
                return leftHeld ? -1 : 1;
            }
            return left.compareTo(right);
        });
        return everything;
    }

    @Override
    protected ItemStack icon(String node) {
        boolean held = services().staff().has(subject, node);
        Optional<StaffRank> rank = services().roster().rankOf(subject);
        boolean partOfTheirRank = rank.isPresent() && rank.get().nodes().contains(node);

        List<String> lore = new ArrayList<>();
        lore.add(held ? "<green>Held." : "<gray>Not held.");
        lore.add("<dark_gray>" + describe(node));
        lore.add("");
        rank.ifPresent(theirs -> lore.add(partOfTheirRank
                ? "<dark_gray>Part of " + theirs.title() + "."
                : "<yellow>Not part of " + theirs.title() + "."));
        lore.add("<dark_gray>" + node);
        lore.add("");
        lore.add(mayChange()
                ? "<dark_gray>Click to " + (held ? "take it away." : "give it.")
                : "<dark_gray>Not yours to change.");

        ItemStack button = Icons.of(held ? Material.LIME_DYE : Material.GRAY_DYE,
                (held ? "<green>" : "<gray>") + readable(node), lore);
        return mayChange() ? button : Icons.locked(button, "Not yours to change");
    }

    @Override
    protected void onClick(String node, InventoryClickEvent event) {
        if (!mayChange()) {
            // Re-asked at the click rather than trusted from the render: this page can sit open for
            // minutes, and op can be taken away in between.
            tell("moderation.rank.not-yours");
            return;
        }
        boolean holdsItNow = services().staff().toggle(viewer, subject, subjectName, node);
        tell(holdsItNow ? "moderation.rank.granted" : "moderation.rank.revoked",
                "player", subjectName, "what", readable(node));
        refresh();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nothing to show",
                "<gray>No rank grants any permission, which should be impossible.");
    }

    /**
     * What a node is called on screen.
     *
     * <p>The moderation ones already describe themselves — {@link ModerationPermission} has a title and
     * a sentence for each. The claims ones are strings from another module, so they get a name here
     * rather than being shown as {@code rec.admin.nofee}, which reads as a typo.
     */
    private static String readable(String node) {
        for (ModerationPermission permission : ModerationPermission.values()) {
            if (permission.node().equals(node)) {
                return sentence(permission.name());
            }
        }
        return switch (node) {
            case StaffRank.CLAIM_ADMIN -> "Claim administration";
            case StaffRank.WARP_STAFF -> "Staff warps";
            case StaffRank.WARP_MANAGE -> "Make and move warps";
            case StaffRank.HOMES_UNLIMITED -> "Unlimited homes";
            case StaffRank.HUNGERGAMES_GAMEMASTER -> "Gamemaster";
            case StaffRank.HUNGERGAMES_ADMIN -> "Hunger Games admin";
            case "hungergames.protection.bypass" -> "Build in the arena";
            case "tpa.bypass.warmup" -> "No teleport warm-up";
            case "tpa.bypass.cooldown" -> "No teleport cooldown";
            case "tpa.back" -> "/back";
            case "homes.bypass.warmup" -> "No home warm-up";
            case "homes.bypass.cooldown" -> "No home cooldown";
            case "rec.admin.nofee" -> "No entry fees";
            case "rec.maxclaims.unlimited" -> "Unlimited claims";
            default -> node;
        };
    }

    /** What holding it lets somebody do. */
    private static String describe(String node) {
        for (ModerationPermission permission : ModerationPermission.values()) {
            if (permission.node().equals(node)) {
                return permission.describe();
            }
        }
        return switch (node) {
            case StaffRank.CLAIM_ADMIN -> "Act on any claim, without being exempt from anything";
            case StaffRank.WARP_STAFF -> "Reach every warp marked staff-only";
            case StaffRank.WARP_MANAGE -> "Create, move and delete warps for the whole server";
            case StaffRank.HOMES_UNLIMITED -> "Set as many homes as they like";
            case StaffRank.HUNGERGAMES_GAMEMASTER ->
                    "Run a round: the deathmatch, supply drops, revives and spectating";
            case StaffRank.HUNGERGAMES_ADMIN -> "Build the arena, edit the loot and the settings";
            case "hungergames.protection.bypass" ->
                    "Ignore the arena's protection — meant for minutes, not for a rank";
            case "tpa.bypass.warmup" -> "Teleport at once, without standing still first";
            case "tpa.bypass.cooldown" -> "Teleport again without waiting";
            case "tpa.back" -> "Return to where they last teleported from";
            case "homes.bypass.warmup" -> "Reach a home at once";
            case "homes.bypass.cooldown" -> "Reach another home without waiting";
            case "rec.admin.nofee" -> "Walk into a claim without paying its toll";
            case "rec.maxclaims.unlimited" -> "Hold as many claims as they like";
            default -> "A permission this server grants";
        };
    }

    /** {@code INVSEE_EDIT} to "Invsee edit" — for the enum names, which are shouted. */
    private static String sentence(String constant) {
        String words = constant.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Green is held, grey is not.",
                "<gray>A green one marked \"not part of\" their rank was granted by hand.",
                "<gray>Their rank is unchanged by anything on this page.");
    }

    @Override
    public String describe() {
        return "one person's permissions, one at a time — the exception to the presets";
    }
}
