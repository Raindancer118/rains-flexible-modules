package de.raindancer.modules.speedrun.manhunt.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.speedrun.manhunt.tracker.StructureChoices;
import de.raindancer.modules.speedrun.manhunt.tracker.StructureCompassService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** What a Runner's structure compass can find, in the dimension they stand in. One click, once. */
public final class StructureChoiceMenu extends PaginatedMenu<StructureChoices.Choice> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final StructureCompassService structures;

    public StructureChoiceMenu(StructureCompassService structures, Brand brand, Player viewer) {
        super(viewer, brand, null);
        this.structures = structures;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Find which structure?");
    }

    @Override
    public String breadcrumb() {
        return "Structure compass";
    }

    @Override
    protected List<StructureChoices.Choice> entries() {
        return StructureChoices.in(viewer.getWorld().getEnvironment());
    }

    @Override
    protected ItemStack icon(StructureChoices.Choice choice) {
        Material material = Material.matchMaterial(choice.icon());
        return Icons.of(material == null ? Material.COMPASS : material, "<gold>" + choice.label(),
                "<gray>Points your compass at the nearest one.", "<dark_gray>You only get to choose once.");
    }

    @Override
    protected void onClick(StructureChoices.Choice choice, InventoryClickEvent event) {
        viewer.closeInventory();
        structures.choose(viewer, choice);
    }
}
