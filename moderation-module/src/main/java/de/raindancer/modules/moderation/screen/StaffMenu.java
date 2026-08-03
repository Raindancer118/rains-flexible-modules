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
public final class StaffMenu extends ModerationList<Player> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

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
    protected List<Player> entries() {
        List<Player> staff = new ArrayList<>();
        for (Player who : services().server().getOnlinePlayers()) {
            // "Staff" is whoever may read a record — the same permission the history command asks
            // for, rather than a second idea of who counts.
            if (who.hasPermission(ModerationPermission.HISTORY.node())) {
                staff.add(who);
            }
        }
        staff.sort((left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        return staff;
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nobody else is on",
                "<gray>You are the only one here who can see a record.");
    }

    @Override
    protected ItemStack icon(Player who) {
        List<String> lore = new ArrayList<>();
        lore.add(services().vanish().isVanished(who.getUniqueId())
                ? "<gray>Vanished." : "<green>Visible.");
        if (services().staffChat().isTalking(who.getUniqueId())) {
            lore.add("<dark_aqua>In staff chat.");
        }
        lore.add("");
        lore.add("<dark_gray>Click to open their page.");

        return Icons.head(who.getUniqueId(), "<yellow>" + who.getName(), lore);
    }

    @Override
    protected void onClick(Player who, InventoryClickEvent event) {
        new PlayerMenu(services(), viewer, this, who.getUniqueId(), who.getName()).open();
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
