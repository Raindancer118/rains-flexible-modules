package de.raindancer.modules.warp.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.world.warp.Warp;
import de.raindancer.modules.warp.WarpServices;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Every warp there is: what {@code /warp admin} opens.
 *
 * <h2>Why this is a different page from the player's list</h2>
 * Because the two answer different questions. A player's list answers "where can I go", and clicking
 * one goes there. This answers "what is on this server", and clicking one opens what can be changed
 * about it — including the warps this admin cannot personally use, and the ones whose world is
 * currently unloaded, neither of which belong on the other page.
 *
 * <p>Mixing them was the first attempt, with a shift click to edit. It meant an admin who wanted to
 * change a warp had to go to it first, and one misjudged click sent them across the world.
 */
public final class AdminWarpMenu extends PaginatedMenu<Warp> implements IWarpScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** How long an admin has to type a name before the question is dropped. */
    private static final Duration TO_ANSWER = Duration.ofSeconds(60);

    private final WarpServices services;

    public AdminWarpMenu(WarpServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Every warp");
    }

    @Override
    public String breadcrumb() {
        return "Every warp";
    }

    /** All of them, not only the ones this admin may use — that is the point of the page. */
    @Override
    protected List<Warp> entries() {
        List<Warp> all = new ArrayList<>(services.catalogue().all());
        all.sort(Comparator.comparing(Warp::name, String.CASE_INSENSITIVE_ORDER));
        return all;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>There are no warps yet",
                "<gray>Stand where you want the first one",
                "<gray>and click here to name it.");
    }

    @Override
    protected void emptyAction(InventoryClickEvent event) {
        askForANameAndMakeItHere();
    }

    @Override
    protected ItemStack icon(Warp warp) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>" + warp.world() + " " + warp.coordinates());
        lore.add("<gray>" + services.catalogue().accessOf(warp).describe());
        warp.category().ifPresent(filed -> lore.add("<dark_gray>Filed under " + filed));
        if (!warp.label().equals(warp.name())) {
            lore.add("<dark_gray>Typed as " + warp.name());
        }
        if (!warp.isReachable()) {
            // Not an error and not a reason to delete it: a multiverse server unloads worlds for
            // maintenance, and the warp works again when the world comes back.
            lore.add("<red>Its world is not loaded right now.");
        }
        lore.add("");
        lore.add("<gray>Click to change it.");

        Material material = warp.poi().icon();
        if (material == null || !material.isItem()) {
            material = Material.LODESTONE;
        }
        return Icons.of(material, "<white>" + warp.label(), lore);
    }

    @Override
    protected void onClick(Warp warp, InventoryClickEvent event) {
        new WarpEditMenu(services, viewer, this, warp.name()).open();
    }

    /**
     * Making one here.
     *
     * <p>In the toolbar rather than the danger slot: making a warp is not irreversible, and the
     * danger slot is for the thing that is.
     */
    @Override
    protected void decorate() {
        super.decorate();
        toolbar(2, Icons.of(Material.LODESTONE, "<white>Make a warp here",
                        "<gray>Where you are standing, facing the way you are.",
                        "<dark_gray>You will be asked what to call it."),
                click -> askForANameAndMakeItHere());

        // Two columns along, so a pane falls between them. A wall of adjacent buttons is unreadable.
        toolbar(6, Icons.of(Material.COMPARATOR, "<white>How warps work here",
                        "<gray>What a warp costs to use, what travels with",
                        "<gray>somebody, and how many there may be.",
                        "<dark_gray>Every click there is written to disk at once."),
                click -> new WarpConfigMenu(services, viewer, this).open());
    }

    /**
     * Asks in chat, which is the one thing a menu genuinely cannot ask.
     *
     * <p>A name has nothing to enumerate, so there is no chooser to open. Everything with a set of
     * answers — the icon, who it is for — is a chooser, per the module's own grammar.
     */
    private void askForANameAndMakeItHere() {
        viewer.closeInventory();
        boolean asking = services.core().prompts().ask(viewer.getUniqueId(), "warps", TO_ANSWER,
                answer -> {
                    services.admin().create(viewer, answer);
                    // Reopened so the new warp can be given an icon and an access without typing
                    // anything else. Rebuilt rather than refreshed: the page behind it is gone.
                    new AdminWarpMenu(services, viewer, null).open();
                },
                // Cancelled or timed out. The line says a warp needs a name, so there is nothing
                // to fill in — see WarpAdminService.create for why that matters.
                () -> services.messages().send(viewer, "warps.name.empty"));
        if (!asking) {
            // Somebody else is already asking them something. Saying so beats overwriting it,
            // which would leave the other plugin waiting for an answer it never gets.
            services.messages().send(viewer, "warps.busy");
            return;
        }
        services.messages().send(viewer, "warps.ask-name");
    }

    @Override
    public String describe() {
        return "every warp on the server, and what can be changed about each";
    }
}
