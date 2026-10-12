package de.raindancer.modules.arena.screen;

import de.raindancer.core.data.nbt.ItemText;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.arena.ArenaServices;
import de.raindancer.modules.arena.model.Kit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** A kit's contents, to look at: the hotbar and backpack as in an inventory, the armour and off hand below. */
public final class KitPreviewMenu extends Menu implements IArenaScreen {

    private final Kit kit;

    public KitPreviewMenu(ArenaServices services, Player viewer, Menu parent, Kit kit) {
        super(viewer, services.brand(), parent);
        this.kit = kit;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Kit: <white>" + MiniMessage.miniMessage().escapeTags(kit.name()));
    }

    @Override
    protected void render() {
        ItemText codec = ItemText.ofTheServer();
        for (int slot = 0; slot < Math.min(36, kit.items().size()); slot++) {
            String line = kit.items().get(slot);
            if (line == null || line.isEmpty()) {
                continue;
            }
            ItemStack item = codec.read(line);
            // Hotbar on the bottom row of the four, as a player sees their own inventory.
            int shown = slot < 9 ? 27 + slot : slot - 9;
            if (item != null && shown < 36) {
                set(shown, item);
            }
        }
    }

    @Override
    public String breadcrumb() {
        return kit.name();
    }

    @Override
    public String describe() {
        return "looking inside a kit";
    }
}
