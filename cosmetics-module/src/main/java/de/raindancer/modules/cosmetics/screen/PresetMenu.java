package de.raindancer.modules.cosmetics.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.cosmetics.CosmeticsServices;
import de.raindancer.modules.cosmetics.model.Preset;
import de.raindancer.modules.cosmetics.service.NameStyleService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * The owner's presets, each painted in itself and on the viewer's own name. A restricted one the
 * viewer lacks is greyed with the node it needs — greyed, not hidden, so a perk is something to want.
 */
public final class PresetMenu extends PaginatedMenu<Preset> implements ICosmeticsScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final CosmeticsServices services;

    public PresetMenu(CosmeticsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Presets");
    }

    @Override
    public String breadcrumb() {
        return "Presets";
    }

    @Override
    protected List<Preset> entries() {
        return services.offered().presets();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>No presets on this server",
                "<gray>An owner adds them under presets:",
                "<gray>in the cosmetics config.yml.");
    }

    @Override
    protected ItemStack icon(Preset preset) {
        NameStyle worn = services.names().current(viewer.getUniqueId());
        boolean wearing = worn.equals(preset.style());
        ItemStack icon = Icons.of(wearing ? Material.GLOW_ITEM_FRAME : Material.NAME_TAG,
                MINI.serialize(NameStyleService.painted(preset.title(), preset.style())),
                "<gray>Your name:",
                MINI.serialize(NameStyleService.painted(viewer.getName(), preset.style())),
                "",
                wearing ? "<green>You are wearing this." : "<dark_gray>Click to wear it.");
        return services.names().mayUse(viewer, preset)
                ? icon
                : Icons.locked(icon, "Needs " + preset.permission());
    }

    @Override
    protected void onClick(Preset preset, InventoryClickEvent event) {
        // A locked preset answers too: wear() says which node it needs.
        services.names().wear(viewer, preset);
        refresh();
    }

    @Override
    public String describe() {
        return "the presets, painted in themselves";
    }
}
