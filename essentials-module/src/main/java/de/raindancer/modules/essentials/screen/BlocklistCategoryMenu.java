package de.raindancer.modules.essentials.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.store.NicknameBlocklist;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One section's names — click one to remove it, or type a new one in with the toolbar button.
 */
public final class BlocklistCategoryMenu extends PaginatedMenu<String> {

    private static final Duration PROMPT_TIMEOUT = Duration.ofSeconds(30);

    private final EssentialsServices services;
    private final String categoryId;

    public BlocklistCategoryMenu(EssentialsServices services, Player viewer, String categoryId,
                                 Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.categoryId = categoryId;
    }

    @Override
    protected Component title() {
        return Component.text(categoryId);
    }

    @Override
    protected List<String> entries() {
        NicknameBlocklist.Category category = services.blocklist().categories().get(categoryId);
        return category == null ? List.of() : new ArrayList<>(category.names());
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.PAPER, "<gray>Nothing in this section",
                "<dark_gray>Add one with the button below.");
    }

    @Override
    protected ItemStack icon(String name) {
        return Icons.of(Material.NAME_TAG, "<white>" + name, "", "<dark_gray>click to remove");
    }

    @Override
    protected void onClick(String name, InventoryClickEvent event) {
        services.blocklist().removeName(categoryId, name);
        services.messages().send(viewer, "essentials.blocklist.removed",
                "name", name, "category", categoryId);
        refresh();
    }

    @Override
    protected void render() {
        super.render();
        toolbar(4, Icons.of(Material.WRITABLE_BOOK, "<green>Add a name",
                        "<dark_gray>click, then type it in chat"),
                click -> askAddName());
    }

    private void askAddName() {
        viewer.closeInventory();
        services.messages().send(viewer, "essentials.blocklist.type-a-name");
        boolean asked = services.core().prompts().ask(viewer.getUniqueId(), "Essentials",
                PROMPT_TIMEOUT,
                typed -> {
                    if (services.blocklist().addName(categoryId, typed)) {
                        services.messages().send(viewer, "essentials.blocklist.added",
                                "name", typed.trim(), "category", categoryId);
                    }
                    open();
                },
                this::open);
        if (!asked) {
            services.messages().send(viewer, "essentials.blocklist.already-asking");
        }
    }
}
