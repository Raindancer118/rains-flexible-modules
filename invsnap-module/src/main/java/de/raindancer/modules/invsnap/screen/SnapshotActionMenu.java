package de.raindancer.modules.invsnap.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.invsnap.InvSnapServices;
import de.raindancer.modules.invsnap.model.Snapshot;
import de.raindancer.modules.invsnap.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * What to do with one snapshot, once picked from the history list — inspect it, compare it to what
 * {@code targetId} is carrying right now, or restore it. Three separate questions with three
 * separate permissions, so this is where they are actually asked rather than folding all of them
 * into a single click on the history page, which is what this used to be: a click straight into
 * "restore this?" with no way to look first.
 */
public final class SnapshotActionMenu extends Menu implements IInvSnapScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final InvSnapServices services;
    private final Snapshot snapshot;
    private final UUID targetId;
    private final String targetName;

    public SnapshotActionMenu(InvSnapServices services, Player viewer, Menu parent,
                              Snapshot snapshot, UUID targetId, String targetName) {
        super(viewer, services.brand(), parent, 3);
        this.services = services;
        this.snapshot = snapshot;
        this.targetId = targetId;
        this.targetName = targetName;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>" + STAMP.format(snapshot.takenAt()));
    }

    @Override
    public String breadcrumb() {
        return "This snapshot";
    }

    @Override
    protected void render() {
        band(MenuLayout.WHO, 2, Icons.of(Material.CHEST, "<white>Inspect",
                        "<gray>Look at every item in this",
                        "<gray>snapshot, exactly as it was."),
                event -> new SnapshotDetailScreen(services, viewer, this, snapshot, null, targetName)
                        .open());

        Player live = Bukkit.getPlayer(targetId);
        Snapshot liveSnapshot = live == null ? null : services.snapshots().liveSnapshotOf(live);
        band(MenuLayout.WHO, 4, live != null,
                Icons.of(Material.HOPPER, "<white>Compare to now",
                        "<gray>Slot by slot, against what",
                        "<gray>" + targetName + " is carrying right now."),
                targetName + " is not online",
                event -> new SnapshotDetailScreen(services, viewer, this, snapshot, liveSnapshot, targetName)
                        .open());

        band(MenuLayout.WHO, 6, viewer.hasPermission(PermissionNodes.RESTORE),
                Icons.of(Material.ANVIL, "<red>Restore",
                        "<gray>Overwrite " + targetName + "'s live",
                        "<gray>inventory with this snapshot."),
                "Restoring needs " + PermissionNodes.RESTORE,
                event -> restore());
    }

    private void restore() {
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            services.messages().send(viewer, "invsnap.restore.offline", "player", targetName);
            return;
        }
        new ConfirmScreen(services, viewer, this,
                "<red>Restore " + targetName + "'s inventory?",
                List.of("<gray>Snapshot from <white>" + STAMP.format(snapshot.takenAt()) + "</white>.",
                        "<red>Whatever " + targetName + " is carrying now is replaced, not merged."),
                () -> services.snapshots().restore(target, snapshot)).open();
    }

    @Override
    public String describe() {
        return "what to do with one snapshot: inspect it, compare it to the live inventory, or restore it";
    }
}
