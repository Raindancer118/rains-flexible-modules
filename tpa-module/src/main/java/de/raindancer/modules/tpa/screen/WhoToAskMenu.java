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

/**
 * Somebody to ask.
 *
 * <h2>Why the faces are real faces</h2>
 * A head wearing the player's own skin is the thing people actually recognise — a page of identical
 * Steves is a page you have to read rather than look at. {@code Icons.head(uuid, …)} does that;
 * {@code Icons.of(Material.PLAYER_HEAD, …)} is Steve on every server.
 *
 * <h2>What a click means</h2>
 * Left asks in the direction this page was opened for, right asks the other way, and shift blocks. Three
 * meanings on one button, and every one of them is written in that button's lore — an unadvertised
 * modifier is one nobody finds.
 */
public final class WhoToAskMenu extends PaginatedMenu<Player> implements ITpaScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final TpaServices services;
    private final TpaKind kind;

    public WhoToAskMenu(TpaServices services, Player viewer, Menu parent, TpaKind kind) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.kind = kind;
    }

    @Override
    protected Component title() {
        return MINI.deserialize(kind == TpaKind.TO
                ? "<dark_gray>Who to go to"
                : "<dark_gray>Who to ask over");
    }

    @Override
    public String breadcrumb() {
        return kind == TpaKind.TO ? "Who to go to" : "Who to ask over";
    }

    /**
     * Everybody else who is online.
     *
     * <p>Including people who are not accepting: their button says so and refuses politely. Leaving
     * them out would make the page a different shape depending on other people's settings, and would
     * quietly tell you which of your friends has blocked you.
     */
    @Override
    protected List<Player> entries() {
        List<Player> others = new ArrayList<>(services.server().getOnlinePlayers());
        others.remove(viewer);
        others.removeIf(other -> !services.core().vanish().canSee(viewer.getUniqueId(), other.getUniqueId()));
        others.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return others;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nobody else is here",
                "<gray>There is no one online to ask.");
    }

    @Override
    protected void emptyAction(InventoryClickEvent event) {
        leave();
    }

    @Override
    protected ItemStack icon(Player who) {
        boolean mayAsk = services.prefs().mayBeAskedBy(who.getUniqueId(), viewer.getUniqueId());
        boolean blockedByMe = services.prefs().of(viewer.getUniqueId())
                .hasBlocked(who.getUniqueId());

        List<String> lore = new ArrayList<>();
        if (!mayAsk) {
            // The same sentence whether they switched requests off or blocked this player. Telling
            // somebody they have been blocked turns a quiet decision into a confrontation.
            lore.add("<red>Not accepting requests right now.");
            lore.add("");
        }
        lore.add("<gray>Click to ask " + (kind == TpaKind.TO
                ? "to go to them." : "them to come to you."));
        lore.add("<gray>Right click to ask the other way round.");
        lore.add(blockedByMe
                ? "<gray>Shift click to unblock them."
                : "<gray>Shift click to block them.");

        return Icons.head(who.getUniqueId(), "<white>" + who.getName(), lore);
    }

    @Override
    protected void onClick(Player who, InventoryClickEvent event) {
        if (event.isShiftClick()) {
            if (services.prefs().of(viewer.getUniqueId()).hasBlocked(who.getUniqueId())) {
                services.prefs().unblock(viewer, who);
            } else {
                services.prefs().block(viewer, who);
            }
            refresh();
            return;
        }
        TpaKind asking = event.isRightClick()
                ? (kind == TpaKind.TO ? TpaKind.HERE : TpaKind.TO)
                : kind;
        // Closed first: what follows is a message, and a window over it is a window they have to shut
        // before they can read what happened.
        viewer.closeInventory();
        services.asking().ask(viewer, who, asking);
    }

    @Override
    public String describe() {
        return "somebody to ask";
    }
}
