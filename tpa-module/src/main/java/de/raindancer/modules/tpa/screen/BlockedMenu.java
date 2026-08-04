package de.raindancer.modules.tpa.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.tpa.TpaServices;
import de.raindancer.modules.tpa.model.TpaKind;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Who this player has blocked.
 *
 * <h2>Why there is no "add somebody" button here</h2>
 * Because adding needs a name, and a name typed in chat means exact spelling — a typo looks like
 * somebody who never joined, and a renamed player is untypeable. Blocking is done by shift-clicking a
 * face, on the page where the faces are. The button below opens that page rather than asking.
 */
public final class BlockedMenu extends PaginatedMenu<UUID> implements ITpaScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TpaServices services;

    public BlockedMenu(TpaServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Blocked");
    }

    @Override
    public String breadcrumb() {
        return "Blocked";
    }

    @Override
    protected List<UUID> entries() {
        List<UUID> blocked = new ArrayList<>(
                services.prefs().of(viewer.getUniqueId()).blocked());
        blocked.sort(Comparator.comparing(who -> services.prefs().nameOf(who),
                String.CASE_INSENSITIVE_ORDER));
        return blocked;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>You have blocked nobody",
                "<gray>Shift click somebody's face on the",
                "<gray>'who to ask' page to block them.",
                "",
                "<gray>Click here to open that page.");
    }

    @Override
    protected void emptyAction(InventoryClickEvent event) {
        // A list that names the way out has to be able to act on it, or the sentence is decoration.
        services.screens().whoToAsk(viewer, TpaKind.TO);
    }

    @Override
    protected ItemStack icon(UUID who) {
        return Icons.head(who, "<white>" + services.prefs().nameOf(who),
                "<gray>Cannot ask to teleport to you.",
                "<dark_gray>They are told the same thing as somebody",
                "<dark_gray>you have simply switched off — a block is",
                "<dark_gray>not something they can see.",
                "",
                "<gray>Click to unblock them.");
    }

    /**
     * Unblocking says so in chat, through the service.
     *
     * <p>The page redrawing without them is the visible half, and it is not enough on its own: a list
     * that is one shorter than it was is not obviously a thing that happened, especially on a page
     * where a misclick is one row away.
     */
    @Override
    protected void onClick(UUID who, InventoryClickEvent event) {
        // By uuid, never by name: getOfflinePlayer(String) blocks on a lookup against Mojang, from what
        // on Folia may be a region thread.
        services.prefs().unblock(viewer, services.server().getOfflinePlayer(who));
        refresh();
    }

    /** The way to add somebody, which is a page of faces rather than a question. */
    @Override
    protected void decorate() {
        super.decorate();
        toolbar(4, Icons.of(Material.IRON_DOOR, "<white>Block somebody",
                        "<gray>Opens the list of people who are online.",
                        "<gray>Shift click a face there to block them.",
                        "<dark_gray>Typing a name would mean exact spelling,",
                        "<dark_gray>and a renamed player would be untypeable."),
                click -> services.screens().whoToAsk(viewer, TpaKind.TO));
    }

    @Override
    public String describe() {
        return "who this player has blocked";
    }
}
