package de.raindancer.modules.invsnap.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.invsnap.InvSnapServices;
import de.raindancer.modules.invsnap.model.TrackedPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Every player this server has ever taken a snapshot of, newest activity first — what bare
 * {@code /invsnap} opens. {@code /invsnap <player>} skips straight past this to that player's own
 * history; this page exists for the times the admin does not already know who they are looking for.
 */
public final class InvSnapRootMenu extends PaginatedMenu<TrackedPlayer> implements IInvSnapScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final InvSnapServices services;

    public InvSnapRootMenu(InvSnapServices services, Player viewer) {
        super(viewer, services.brand(), null);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Inventory snapshots");
    }

    @Override
    public String breadcrumb() {
        return "Every player's snapshots";
    }

    @Override
    protected List<TrackedPlayer> entries() {
        return services.snapshots().tracked();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nobody has a snapshot yet",
                "<gray>One is taken automatically every "
                        + services.config().snapshotInterval().toSeconds() + " seconds "
                        + "while a player is online.");
    }

    @Override
    protected ItemStack icon(TrackedPlayer entry) {
        return Icons.head(entry.id(), "<white>" + entry.name(),
                "<gray>" + entry.count() + " snapshot(s)",
                "<gray>Newest: <white>" + STAMP.format(entry.newest()));
    }

    @Override
    protected void onClick(TrackedPlayer entry, InventoryClickEvent event) {
        new SnapshotHistoryMenu(services, viewer, entry.id(), entry.name(), this).open();
    }

    @Override
    public String describe() {
        return "every player this server has a snapshot of, for picking one without already knowing their name";
    }
}
