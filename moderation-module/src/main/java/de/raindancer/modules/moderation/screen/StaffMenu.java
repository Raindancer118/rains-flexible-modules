package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Who is on shift.
 *
 * <h2>Why vanished staff are on the list</h2>
 * Because this page is for the staff, and the question it answers is "is anybody else about". A page
 * that hid vanished moderators from other moderators would answer it wrongly exactly when it matters —
 * three people invisible and everybody thinking they are on their own.
 *
 * <p>Whether somebody is vanished is shown, not hidden, for the same reason.
 */
public final class StaffMenu extends ModerationList<StaffMenu.OnShift> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Somebody on shift: here ({@code server} is null) or on another server of the network. */
    public record OnShift(UUID id, String name, String server, boolean vanished, boolean talking) {
    }

    public StaffMenu(ModerationServices services, Player viewer, Menu parent) {
        super(services, viewer, parent);
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Staff on now");
    }

    @Override
    public String breadcrumb() {
        return "Staff";
    }

    @Override
    protected List<OnShift> entries() {
        List<OnShift> staff = new ArrayList<>();
        for (Player who : services().server().getOnlinePlayers()) {
            // "Staff" is whoever may read a record — the same permission the history command asks
            // for, rather than a second idea of who counts.
            if (who.hasPermission(ModerationPermission.HISTORY.node())) {
                staff.add(new OnShift(who.getUniqueId(), who.getName(), null,
                        services().vanish().isVanished(who.getUniqueId()),
                        services().staffChat().isTalking(who.getUniqueId())));
            }
        }
        staff.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
        // Other servers' staff are the roster's: a permission cannot be asked about somebody who is not here.
        boolean mayKnowVanished = services().vanish().maySeeVanished(viewer.getUniqueId());
        services().network().staffElsewhere(mayKnowVanished, services().roster()::isStaff).forEach(there ->
                staff.add(new OnShift(there.id(), there.name(), there.server(), there.vanished(), false)));
        return staff;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nobody else is on",
                "<gray>You are the only one here who can see a record.");
    }

    @Override
    protected ItemStack icon(OnShift who) {
        List<String> lore = new ArrayList<>();
        services().roster().rankOf(who.id()).ifPresentOrElse(
                rank -> lore.add("<" + rank.colour() + ">" + rank.title()),
                () -> lore.add("<dark_gray>No rank — permissions from somewhere else."));
        if (who.server() != null) {
            lore.add("<gray>On <white>" + MINI.escapeTags(who.server()) + "</white>.");
        }
        lore.add(who.vanished() ? "<gray>Vanished." : "<green>Visible.");
        if (who.talking()) {
            lore.add("<dark_aqua>In staff chat.");
        }
        lore.add("");
        lore.add("<dark_gray>Click to open their page.");

        return Icons.head(who.id(), "<yellow>" + MINI.escapeTags(who.name()), lore);
    }

    @Override
    protected void onClick(OnShift who, InventoryClickEvent event) {
        new PlayerMenu(services(), viewer, this, who.id(), who.name()).open();
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Everybody here can see a punishment record.",
                "<gray>Vanished staff are shown — this page is for you, not for players.");
    }

    @Override
    public String describe() {
        return "who is on shift, vanished or not";
    }
}
