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

        // Six doors, not twelve buttons. The previous version put everything on this one page in adjacent
        // columns, which read as a wall: nothing was grouped, the eye had nowhere to rest, and finding the
        // fence meant reading all twelve. Each of these opens a page about one subject, and they sit two
        // columns apart so the grid has air in it.
        band(MenuLayout.WHO, 2,
                Icons.of(Material.PLAYER_HEAD, "<aqua>People",
                        "<gray>Who is trusted, what strangers may do,",
                        "<gray>and who is kept out.",
                        "<dark_gray>" + claim.members().size() + " trusted · "
                                + claim.bans().size() + " barred"),
                click -> new PeopleMenu(services(), viewer, claim, this).open());

        band(MenuLayout.WHO, 4,
                Icons.of(Material.REDSTONE_TORCH, "<gold>Protection",
                        "<gray>Fire, mobs, explosions, PvP —",
                        "<gray>what the world may do in here.",
                        "<dark_gray>" + changedFlags() + " changed from the server default"),
                click -> new ProtectionMenu(services(), viewer, claim, this,
                        this::openTheRules, changedFlags()).open());

        band(MenuLayout.WHO, 6,
                Icons.of(Material.GRASS_BLOCK, "<green>The land",
                        "<gray>Its outline, its depth, its fence,",
                        "<gray>its name and icon.",
                        "<dark_gray>" + claim.shape().areaBlocks() + " blocks"),
                click -> new LandMenu(services(), viewer, claim, this).open());

        band(MenuLayout.LAND, 2,
                Icons.of(Material.BREWING_STAND, "<light_purple>Perks",
                        "<gray>Effects, the pantry, auto-equip, the weather.",
                        "<dark_gray>" + livePerks() + " running"),
                click -> new PerksMenu(services(), viewer, claim, this).open());

        if (services().features().isOffered(ClaimFeature.ENTRY_FEE)
                || services().features().isOffered(ClaimFeature.BANK)) {
            band(MenuLayout.LAND, 4,
                    Icons.of(Material.GOLD_NUGGET, "<yellow>Toll and bank",
                            "<gray>What a visitor pays, and where it ends up.",
                            "<dark_gray>" + (claim.entryFee().enabled() ? "charging" : "free")
                                    + " · " + claim.bank().items().size() + " item(s) banked"),
                    click -> new MoneyMenu(services(), viewer, claim, this).open());
        }

        band(MenuLayout.LAND, 6,
                Icons.of(Material.BOOK, "<white>What this claim is",
                        "<gray>Its size, its owners, what was paid for it."),
                click -> new ClaimInfoMenu(services(), viewer, claim, this).open());

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
