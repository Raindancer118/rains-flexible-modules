package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.util.Items;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.ClaimServices;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * One claim, everything about it, in four groups.
 *
 * <h2>What was wrong with the screen this replaces</h2>
 * It was a grid of eighteen buttons in no particular order — the pantry next to the flags next to the bans next
 * to the fence — and finding anything meant reading all eighteen names. Every new feature made it worse, because
 * a flat grid has nowhere to put a nineteenth thing except beside the eighteenth.
 *
 * <p>The fix is not fewer features. It is that the page has <b>rows that mean something</b>:
 *
 * <pre>
 *   who   ·  members    public grant    bans
 *   rules ·  flags      features
 *   land  ·  shape      height          fence      name and icon
 *   tools ·  info       bank            perks      entry fee
 *          [ delete ]                                          (behind a confirmation)
 * </pre>
 *
 * <p>Which means a player looking for "who may open my chests" reads one row of three rather than a grid of
 * eighteen, and a nineteenth feature has an obvious home rather than an empty slot.
 *
 * <h2>Buttons that are not yours are shown, not hidden</h2>
 * A trusted member with no management rights still sees the flags button, greyed, saying whose it is. Hiding it
 * makes the menu a different shape for every viewer, so nobody can be told "it is the third one along" — and it
 * makes "why can I not see it" a question with no answer on screen.
 */
public final class ClaimMenu extends ClaimScreen {

    public ClaimMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(services, viewer, claim, parent);
    }

    @Override
    protected Component title() {
        return Component.text(services().names().display(claim(), viewer.getUniqueId()));
    }

    @Override
    protected List<String> helpLines() {
        return List.of(
                "<gray>Everything about this claim, in four rows.",
                "",
                "<white>Who</white> <dark_gray>·</dark_gray> <gray>the people who may be here",
                "<white>Rules</white> <dark_gray>·</dark_gray> <gray>what may happen here",
                "<white>Land</white> <dark_gray>·</dark_gray> <gray>the ground itself",
                "<white>Tools</white> <dark_gray>·</dark_gray> <gray>what this claim keeps for you",
                "",
                "<dark_gray>A greyed button belongs to somebody else — its lore says who.");
    }

    @Override
    protected void render() {
        Claim claim = claim();

        // ── who ──────────────────────────────────────────────────────────────────────────── row 1
        // Three columns apart rather than shoulder to shoulder. The first version of this page put twelve
        // buttons in adjacent columns with the rest of the grid empty, which read as a wall — nothing was
        // grouped and finding the fence meant reading all twelve labels. The row IS the grouping; the gaps
        // are what let you see it.
        //
        // The alternative was a page of six category buttons, each opening a page of its own. That looked
        // tidier and cost a click everywhere: every list was then two levels down from the front door. One
        // submenu per button is the rule here, so the row does the grouping instead of a hub page.
        band(MenuLayout.WHO, 1, may(ClaimAdminPermission.MANAGE_MEMBERS),
                Icons.head(viewer.getUniqueId(), "<aqua>Trusted people",
                        "<gray>Who may do what here.",
                        "<dark_gray>" + claim.members().size() + " trusted"),
                "The owner's to change",
                click -> new MembersMenu(services(), viewer, claim, this).open());

        band(MenuLayout.WHO, 3, may(ClaimAdminPermission.MANAGE_PERMISSIONS),
                Icons.of(Material.OAK_DOOR, "<aqua>Everybody else",
                        "<gray>What a visitor may do without being trusted.",
                        "<dark_gray>" + claim.publicPermissions().size() + " allowed"),
                "The owner's to change",
                click -> new PublicPermissionsMenu(services(), viewer, claim, this).open());

        band(MenuLayout.WHO, 5, may(ClaimAdminPermission.MANAGE_BANS),
                Icons.of(Material.IRON_BARS, "<aqua>Kept out",
                        "<gray>Bans and timeouts.",
                        "<dark_gray>" + claim.bans().size() + " on the list"),
                "The owner's to change",
                click -> new BansMenu(services(), viewer, claim, this).open());

        band(MenuLayout.WHO, 7, may(ClaimAdminPermission.MANAGE_TITLES),
                Icons.of(Material.NAME_TAG, "<aqua>Greetings",
                        "<gray>What people see coming in and going out.",
                        "<dark_gray>" + (claim.titles().enterTitle().raw().isBlank()
                                ? "nothing set" : "set")),
                "The owner's to change",
                click -> new TitlesMenu(services(), viewer, claim, this).open());

        // ── rules ────────────────────────────────────────────────────────────────────────── row 2
        band(MenuLayout.RULES, 1, may(ClaimAdminPermission.MANAGE_FLAGS),
                Icons.of(Material.REDSTONE_TORCH, "<gold>Rules",
                        "<gray>Fire, mobs, explosions, PvP —",
                        "<gray>what the world does inside your border.",
                        "<dark_gray>" + changedFlags() + " changed from the server default"),
                "The owner's to change",
                click -> openTheRules(claim));

        band(MenuLayout.RULES, 3, true,
                Icons.of(Material.COMPARATOR, "<gold>What this claim can do",
                        "<gray>Which perks this claim is allowed at all.",
                        "<dark_gray>set by the server, shown here so you know"),
                "",
                click -> new FeaturesMenu(services(), viewer, claim, this).open());

        band(MenuLayout.RULES, 5, Icons.of(Material.BREWING_STAND, "<gold>Perks",
                        "<gray>Effects, the pantry, auto-equip, the weather.",
                        "<dark_gray>" + livePerks() + " running"),
                click -> new PerksMenu(services(), viewer, claim, this).open());

        // Only for an owner, and only on their own claim: this is not the server-wide bypass, and somebody
        // who is merely trusted has no rules of their own here to be excused from.
        if (claim.isOwner(viewer.getUniqueId())) {
            boolean ignoring = claim.isIgnoringOwnRules(viewer.getUniqueId());
            band(MenuLayout.RULES, 7,
                    Icons.of(ignoring ? Material.ENDER_EYE : Material.ENDER_PEARL,
                            ignoring ? "<green>Ignoring your own rules" : "<gray>Following your own rules",
                            "<gray>Excuses you from this claim's flags while you work on it.",
                            "<dark_gray>this claim only, and only until the server restarts",
                            "",
                            "<dark_gray>click to " + (ignoring ? "follow them again" : "ignore them")),
                    click -> {
                        claim.toggleIgnoringOwnRules(viewer.getUniqueId());
                        refresh();
                    });
        }

        // ── the land ─────────────────────────────────────────────────────────────────────── row 3
        band(MenuLayout.LAND, 1, may(ClaimAdminPermission.MANAGE_SHAPE),
                Icons.of(Material.STICK, "<green>Redraw the border",
                        "<gray>Mark a new outline out with the tool.",
                        "<dark_gray>" + claim.shape().areaBlocks() + " blocks"),
                "The owner's to change",
                click -> {
                    viewer.closeInventory();
                    services().selectionFlow().begin(viewer,
                            de.raindancer.modules.claims.selection.Selection.Mode.RECTANGLE,
                            de.raindancer.modules.claims.selection.Selection.Purpose.RESIZE_CLAIM,
                            null, claim, null);
                });

        band(MenuLayout.LAND, 3, may(ClaimAdminPermission.MANAGE_SHAPE),
                Icons.of(Material.LADDER, "<green>How deep and how high",
                        "<gray>Change the height without redrawing.",
                        "<dark_gray>y " + claim.shape().minY() + " to " + claim.shape().maxY()),
                "The owner's to change",
                click -> new ClaimHeightMenu(services(), viewer, claim, this).open());

        if (services().features().isOffered(ClaimFeature.FENCE)) {
            band(MenuLayout.LAND, 5, may(ClaimAdminPermission.MANAGE_SHAPE),
                    Icons.of(claim.fence().material(), "<green>Fence",
                            "<gray>A real fence along the border.",
                            "<dark_gray>" + (claim.fence().enabled() ? "standing" : "not built")),
                    "The owner's to change",
                    click -> new FenceMenu(services(), viewer, claim, this).open());
        }

        band(MenuLayout.LAND, 7, may(ClaimAdminPermission.MANAGE_TITLES),
                Icons.of(claim.iconMaterial(claim.isOwner(viewer.getUniqueId())),
                        "<green>Name and icon",
                        "<gray>What this claim is called, and what it looks like in a list."),
                "The owner's to change",
                click -> new ClaimIdentityMenu(services(), viewer, claim, this).open());

        // ── what it keeps for you ────────────────────────────────────────────────────────── row 4
        toolbar(7, Icons.of(Material.WRITTEN_BOOK, "<white>The manual",
                        "<gray>How all of this works, as a book.",
                        "<dark_gray>also /claim manual"),
                click -> {
                    viewer.closeInventory();
                    services().screens().manual(viewer);
                });

        toolbar(1, Icons.of(Material.BOOK, "<white>What this claim is",
                        "<gray>Its size, its owners, what was paid for it."),
                click -> new ClaimInfoMenu(services(), viewer, claim, this).open());

        if (services().features().isOffered(ClaimFeature.ENTRY_FEE)) {
            toolbar(3, Icons.of(Material.GOLD_NUGGET, "<white>Entry fee",
                            "<gray>What a visitor pays at the border.",
                            "<dark_gray>" + (claim.entryFee().enabled() ? "charging" : "free")),
                    click -> new EntryFeeMenu(services(), viewer, claim, this).open());
        }

        if (services().features().isOffered(ClaimFeature.BANK)) {
            toolbar(5, Icons.of(Material.ENDER_CHEST, "<white>Bank",
                            "<gray>Items and experience the claim holds.",
                            "<dark_gray>" + claim.bank().items().size() + " item(s)"),
                    click -> new BankMenu(services(), viewer, claim, this).open());
        }

        // ── the one irreversible thing ────────────────────────────────────────────────────────
        if (services().rights().isOwnerOrServerAdmin(claim, viewer)) {
            danger(Icons.of(Material.TNT, "<red>Give this claim up",
                            "<gray>The land stops being protected.",
                            "<dark_gray>asks first"),
                    click -> new ConfirmScreen(services(), viewer, claim, this,
                            "<red>Give up " + claim.name() + "?",
                            List.of("<gray>The border comes down and anybody may build here.",
                                    "<gray>Whatever is in the bank comes back to you first."),
                            () -> {
                                services().claimService().delete(claim, viewer);
                                viewer.closeInventory();
                            }).open());
        }
    }

    /**
     * The rules, drawn by Core.
     *
     * <p>Core's chooser rather than three screens of our own: the flags are Core's, and a page per plugin is the
     * same page three times with three different arrangements — which is how a server comes to look like a pile
     * of plugins rather than one thing.
     */
    private void openTheRules(Claim claim) {
        new de.raindancer.core.ui.choose.FlagChooser(viewer, services().brand(), this,
                claim.area(), services().flags(),
                may(ClaimAdminPermission.MANAGE_FLAGS), "The owner's to change",
                (flag, audience, value) -> {
                    claim.setFlagOverride(flag, audience, value);
                    services().claimService().saveAsync(claim);
                }).open();
    }

    /** How many flags the owner has actually decided, so the button can say whether it is worth opening. */
    private int changedFlags() {
        return claim().flagOverrides().size();
    }

    /** How many perks are running right now, for the same reason. */
    private int livePerks() {
        List<ClaimFeature> perks = new ArrayList<>(List.of(
                ClaimFeature.EFFECTS, ClaimFeature.PANTRY, ClaimFeature.AUTO_EQUIP,
                ClaimFeature.CLAIM_WEATHER, ClaimFeature.CLAIM_TIME));
        int running = 0;
        for (ClaimFeature perk : perks) {
            if (services().features().isEnabled(claim(), perk)) {
                running++;
            }
        }
        return running;
    }
}
