package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.command.PromoteCommand;
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
 * part is what makes drift visible: a node shown green and marked "not part of Helper" is one somebody
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

    /** Op, or the node. Never a moderation preset — see {@code PromoteCommand}. */
    private boolean mayChange() {
        return viewer.isOp() || viewer.hasPermission(PromoteCommand.USE);
    }

    @Override
    protected List<String> entries() {
        // Every node any rank can grant, so the page can give as well as take. Held ones first, since
        // "what has she got" is the more common question of the two.
        List<String> everything = new ArrayList<>(StaffRank.everyGrantableNode());
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
                : "<dark_gray>Only the server owner may change these.");

        ItemStack button = Icons.of(held ? Material.LIME_DYE : Material.GRAY_DYE,
                (held ? "<green>" : "<gray>") + readable(node), lore);
        return mayChange() ? button : Icons.locked(button, "Only the server owner may change these");
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
            case "rec.admin.nocost" -> "Claims cost nothing";
            case "rec.admin.nofee" -> "No entry fees";
            case "rec.admin.nolimit" -> "No size limit";
            case "rec.admin.zonebypass" -> "Claim anywhere";
            case "rec.maxclaims.unlimited" -> "Unlimited claims";
            case de.raindancer.modules.moderation.rules.StaffRule.IMMUNE -> "Immune to moderators";
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
            case "rec.admin.nocost" -> "Make a claim without paying for it";
            case "rec.admin.nofee" -> "Walk into a claim without paying its toll";
            case "rec.admin.nolimit" -> "Ignore the largest and smallest claim size";
            case "rec.admin.zonebypass" -> "Claim inside a no-claim zone";
            case "rec.maxclaims.unlimited" -> "Hold as many claims as they like";
            case de.raindancer.modules.moderation.rules.StaffRule.IMMUNE ->
                    "Cannot be banned, muted or kicked by a moderator — only from the console";
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
