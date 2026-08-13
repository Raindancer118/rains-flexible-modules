package de.raindancer.modules.mannequin.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.mannequin.MannequinServices;
import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.model.MannequinKind;
import de.raindancer.modules.mannequin.rules.CreateMannequinRule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Picking which of the five kinds to create — the GUI's own door to what {@code /mannequin
 * create [kind]} already does from a command. Reached from {@link MannequinListMenu}, whether that
 * is the empty-list click or its always-present toolbar button, and nowhere else: this only ever
 * needs a top-level entry point in {@code IMannequinScreensOpener} if a command or another module
 * ever needs to open it directly, which none currently does — see that interface's own javadoc,
 * "open child screens directly with {@code this} as parent … the screens() opener is for entry
 * points from commands."
 *
 * <h2>The same permission check as the command, not a second one</h2>
 * {@link CreateMannequinRule} is the one rule that decides this, asked here exactly the way {@code
 * command.MannequinCommand#create} asks it — a screen that worked out its own answer to "may this
 * player create one" would be the second rule this project's own grammar exists to prevent.
 */
public final class CreateMannequinScreen extends Menu implements IMannequinScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final CreateMannequinRule CREATE_RULE = new CreateMannequinRule();

    private final MannequinServices services;

    public CreateMannequinScreen(MannequinServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent, 3);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Create a mannequin");
    }

    @Override
    public String breadcrumb() {
        return "Create";
    }

    @Override
    protected void render() {
        boolean mayCreate = CREATE_RULE.mayCreate(services.config().openCreation(), viewer);

        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.ARMOR_STAND, "<white>Which kind?",
                mayCreate ? "<gray>Spawns right where you stand."
                        : "<red>You do not have permission to create one."));

        band(MenuLayout.WHO, 1, kindIcon(MannequinKind.PLAYER, Material.PLAYER_HEAD, mayCreate),
                click -> create(MannequinKind.PLAYER, mayCreate));
        band(MenuLayout.WHO, 2, kindIcon(MannequinKind.ZOMBIE, Material.ZOMBIE_HEAD, mayCreate),
                click -> create(MannequinKind.ZOMBIE, mayCreate));
        band(MenuLayout.WHO, 3, kindIcon(MannequinKind.SKELETON, Material.SKELETON_SKULL, mayCreate),
                click -> create(MannequinKind.SKELETON, mayCreate));
        // Vanilla has no "wither head" item at all — the closest sensible icon is the wither
        // skeleton's own skull, which at least shares the name.
        band(MenuLayout.WHO, 5, kindIcon(MannequinKind.WITHER, Material.WITHER_SKELETON_SKULL, mayCreate),
                click -> create(MannequinKind.WITHER, mayCreate));
        // No golem head item exists either; an iron block is what an iron golem is built from.
        band(MenuLayout.WHO, 7, kindIcon(MannequinKind.IRON_GOLEM, Material.IRON_BLOCK, mayCreate),
                click -> create(MannequinKind.IRON_GOLEM, mayCreate));
    }

    private org.bukkit.inventory.ItemStack kindIcon(MannequinKind kind, Material icon, boolean mayCreate) {
        if (!mayCreate) {
            return Icons.of(Material.BARRIER, "<gray>" + kind.displayName(),
                    "<red>You do not have permission to create one.");
        }
        java.util.List<String> lore = kind.supportsLoadout()
                ? java.util.List.of("<gray>Real health, a loadout screen,", "<gray>and everything else a dummy has.",
                        "", "<gray>Click to create one.")
                : java.util.List.of("<gray>Real health and everything else a", "<gray>dummy has — no loadout for this one.",
                        "", "<gray>Click to create one.");
        return Icons.of(icon, "<white>" + kind.displayName(), lore.toArray(new String[0]));
    }

    private void create(MannequinKind kind, boolean mayCreate) {
        if (!mayCreate) {
            return;
        }
        // The full location, not block-snapped: MannequinService#create reads its yaw before
        // deriving the block coordinates, so the dummy faces the way the player was looking.
        Mannequin created = services.mannequins().create(viewer.getUniqueId(), kind, viewer.getLocation());
        services.messages().send(viewer, "mannequin.create.done", "id", created.id());
        new MannequinEditMenu(services, viewer, created, parent()).open();
    }

    @Override
    public String describe() {
        return "picking which kind of mannequin to create";
    }
}
