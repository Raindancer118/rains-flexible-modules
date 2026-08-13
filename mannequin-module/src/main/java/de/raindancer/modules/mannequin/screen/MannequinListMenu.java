package de.raindancer.modules.mannequin.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.mannequin.MannequinServices;
import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.rules.CreateMannequinRule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Every mannequin this player owns: what bare {@code /mannequin} opens, the same way {@code
 * /home} opens the list of homes rather than guessing which one somebody meant.
 */
public final class MannequinListMenu extends PaginatedMenu<Mannequin> implements IMannequinScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final CreateMannequinRule CREATE_RULE = new CreateMannequinRule();

    private final MannequinServices services;

    public MannequinListMenu(MannequinServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Your mannequins");
    }

    @Override
    public String breadcrumb() {
        return "Your mannequins";
    }

    @Override
    protected List<Mannequin> entries() {
        return services.registry().ownedBy(viewer.getUniqueId());
    }

    /**
     * Nobody has one yet, so this is the first thing a new player sees — it says what to do and
     * the click does it, rather than leaving "you have none" as the whole sentence.
     */
    @Override
    protected ItemStack emptyIcon() {
        boolean mayCreate = CREATE_RULE.mayCreate(services.config().openCreation(), viewer);
        return mayCreate
                ? Icons.of(Material.ARMOR_STAND, "<gray>You have no mannequins yet",
                        "<gray>Click to create one right where you stand.")
                : Icons.of(Material.BARRIER, "<gray>You have no mannequins",
                        "<red>You do not have permission to create one.");
    }

    @Override
    protected void emptyAction(InventoryClickEvent event) {
        if (!CREATE_RULE.mayCreate(services.config().openCreation(), viewer)) {
            return;
        }
        new CreateMannequinScreen(services, viewer, this).open();
    }

    @Override
    protected ItemStack icon(Mannequin mannequin) {
        List<String> lore = List.of(
                "<dark_gray>" + mannequin.world() + " " + mannequin.x() + " "
                        + mannequin.y() + " " + mannequin.z(),
                "",
                "<gray>Click to open.");
        if (mannequin.skinSource() != null) {
            return Icons.head(mannequin.skinSource(), "<white>" + mannequin.displayName(), lore);
        }
        return Icons.of(Material.ARMOR_STAND, "<white>" + mannequin.displayName(), lore);
    }

    @Override
    protected void onClick(Mannequin mannequin, InventoryClickEvent event) {
        // Looked up fresh rather than trusting the clicked value: a second window, or the combat
        // listener recording a hit between opening this list and clicking an entry, may have
        // changed what is actually stored.
        services.registry().get(mannequin.id())
                .ifPresentOrElse(
                        current -> new MannequinEditMenu(services, viewer, current, this).open(),
                        () -> {
                            services.messages().send(viewer, "mannequin.unknown-id", "id", mannequin.id());
                            refresh();
                        });
    }

    @Override
    protected void decorate() {
        super.decorate();
        int have = services.registry().ownedBy(viewer.getUniqueId()).size();
        boolean mayCreate = CREATE_RULE.mayCreate(services.config().openCreation(), viewer);
        // Always present, not only when the list is empty — creating a second or third mannequin
        // used to mean removing every existing one first, since emptyAction() was the only door in.
        toolbar(4, mayCreate
                        ? Icons.of(Material.ARMOR_STAND, "<white>Your mannequins",
                                "<gray>" + have + " right now.",
                                "",
                                "<gray>Click to create another.")
                        : Icons.of(Material.ARMOR_STAND, "<white>Your mannequins",
                                "<gray>" + have + " right now.",
                                "<dark_gray>/mannequin create makes another where you stand."),
                click -> {
                    if (mayCreate) {
                        new CreateMannequinScreen(services, viewer, this).open();
                    }
                });
    }

    @Override
    public String describe() {
        return "every mannequin this player owns";
    }
}
