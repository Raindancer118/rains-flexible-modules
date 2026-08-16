package de.raindancer.modules.invsnap.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.invsnap.InvSnapServices;
import de.raindancer.modules.invsnap.model.Snapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * A player's whole snapshot history, newest first — what {@code /invsnap <player>} opens for
 * an admin, and what {@code /invsnap}'s own root menu leads into once a player is picked.
 *
 * <p>A click here does not restore anything by itself any more — it opens {@link
 * SnapshotActionMenu}, which is where inspecting, comparing and restoring are each their own
 * button. Restoring is gated on {@code PermissionNodes.RESTORE} there, greyed rather than hidden;
 * this page only ever needed {@code PermissionNodes.BROWSE} to be opened at all.
 */
public final class SnapshotHistoryMenu extends PaginatedMenu<Snapshot> implements IInvSnapScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final InvSnapServices services;
    private final UUID targetId;
    private final String targetName;

    public SnapshotHistoryMenu(InvSnapServices services, Player viewer, UUID targetId,
                               String targetName, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.targetId = targetId;
        this.targetName = targetName == null ? targetId.toString() : targetName;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Snapshots — " + targetName);
    }

    @Override
    public String breadcrumb() {
        return "Snapshots";
    }

    @Override
    protected List<Snapshot> entries() {
        return services.snapshots().historyOf(targetId);
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>No snapshots yet",
                "<gray>One is taken automatically every "
                        + services.config().snapshotInterval().toSeconds() + " seconds "
                        + "while " + targetName + " is online.");
    }

    @Override
    protected ItemStack icon(Snapshot entry) {
        String when = STAMP.format(entry.takenAt());
        return Icons.of(Material.CHEST, "<white>" + when,
                "<gray>Click to inspect, compare or restore",
                "<gray>this snapshot.");
    }

    @Override
    protected void onClick(Snapshot entry, InventoryClickEvent event) {
        new SnapshotActionMenu(services, viewer, this, entry, targetId, targetName).open();
    }

    @Override
    public String describe() {
        return "one player's snapshot history, with restore behind a confirmation";
    }
}
