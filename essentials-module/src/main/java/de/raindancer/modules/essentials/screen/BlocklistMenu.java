package de.raindancer.modules.essentials.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.rules.NicknameRule;
import de.raindancer.modules.essentials.store.NicknameBlocklist;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Every section of the nickname blocklist, one per button.
 *
 * <p>A click toggles the section on or off, in place — the whole point of moving this out of a
 * hand-edited file and into a menu, since that is the one thing an owner does to this list often
 * enough to want a button for. Shift-click opens the section's own page of names, for the rarer job
 * of adding or removing one.
 */
public final class BlocklistMenu extends PaginatedMenu<NicknameBlocklist.Category> {

    private final EssentialsServices services;

    public BlocklistMenu(EssentialsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return Component.text("Nickname blocklist");
    }

    @Override
    protected List<NicknameBlocklist.Category> entries() {
        return new ArrayList<>(services.blocklist().categories().values());
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>No sections yet",
                "<dark_gray>Edit blocklist.yml, or restart, to get the starting set back.");
    }

    @Override
    protected ItemStack icon(NicknameBlocklist.Category category) {
        boolean banned = category.action() == NicknameRule.BlockMatch.BANNED;
        Material material = !category.enabled() ? Material.GRAY_DYE
                : banned ? Material.RED_DYE : Material.YELLOW_DYE;
        String name = (category.enabled() ? "<white>" : "<strikethrough><gray>") + category.id();
        return Icons.of(material, name,
                "<gray>" + (category.enabled() ? "<green>enabled" : "<red>disabled"),
                "<gray>Action: " + (banned ? "<red>report + ban" : "<yellow>report only"),
                "<gray>" + category.names().size() + " name(s)",
                "",
                "<dark_gray>click to " + (category.enabled() ? "switch off" : "switch on"),
                "<dark_gray>shift-click to see its names");
    }

    @Override
    protected void onClick(NicknameBlocklist.Category category, InventoryClickEvent event) {
        if (event.isShiftClick()) {
            new BlocklistCategoryMenu(services, viewer, category.id(), this).open();
            return;
        }
        services.blocklist().setEnabled(category.id(), !category.enabled());
        refresh();
    }
}
