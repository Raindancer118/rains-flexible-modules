package de.raindancer.modules.oneblock.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.oneblock.OneBlockServices;
import de.raindancer.modules.oneblock.model.EconomyMode;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.service.IslandServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The one choice an island never gets back: whether the SMP's money comes along. Both sides spelt out, then asked
 * again before it is made — for a new island, or for one that came over from BentoBox and has not chosen yet.
 */
public final class ChooseModeMenu extends Menu implements IOneBlockScreen {

    private final OneBlockServices services;
    private final IslandServer islands;
    /** True to make a new island with the choice; false to settle an existing island's. */
    private final boolean creating;

    public ChooseModeMenu(OneBlockServices services, IslandServer islands, Player viewer, Menu parent,
                          boolean creating) {
        super(viewer, islands.brand(), parent);
        this.services = services;
        this.islands = islands;
        this.creating = creating;
    }

    @Override
    protected Component title() {
        return Component.text(creating ? "A new island" : "Your island's money", NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return creating ? "New island" : "Money";
    }

    @Override
    protected void render() {
        set(MenuLayout.HEADER_SUBJECT, Icons.of(Material.GRASS_BLOCK,
                creating ? "<green>Start your OneBlock island" : "<yellow>Choose your island's money",
                "<gray>One block in the void. Break it,",
                "<gray>and it comes back as something else.",
                "",
                "<gray>First: does the SMP's money come along?",
                "<red>This is chosen once and never changes.",
                "<gray>Everybody else sees which you picked."));
        band(MenuLayout.WHO, 2, Icons.of(Material.STONE_PICKAXE, "<aqua>Pure OneBlock",
                "<gray>No SMP money on your island:",
                "<gray>no /shop, no /pay, nothing bought.",
                "<gray>Everything comes out of the block.",
                "",
                "<gray>Upgrades are paid with <light_purple>shards<gray>,",
                "<gray>earned by breaking.",
                "",
                "<yellow>Click to choose Pure."), click -> confirm(EconomyMode.PURE));
        band(MenuLayout.WHO, 6, Icons.of(Material.GOLD_INGOT, "<gold>With the SMP economy",
                "<gray>Your SMP money comes along:",
                "<gray>/shop, /sell, /pay and /balance",
                "<gray>work on your island.",
                "",
                "<gray>Upgrades are still paid with <light_purple>shards<gray>.",
                "",
                "<yellow>Click to choose the SMP economy."), click -> confirm(EconomyMode.SMP));
    }

    private void confirm(EconomyMode mode) {
        String name = mode == EconomyMode.PURE ? "Pure OneBlock" : "the SMP economy";
        new ConfirmScreen(islands.brand(), viewer, this, "Play with " + name + "?",
                List.of("<red>This can never be changed again.",
                        mode == EconomyMode.PURE ? "<gray>No SMP money will ever work on this island."
                                : "<gray>Your SMP money works on this island.",
                        "<gray>Everybody sees which your island plays with."),
                () -> choose(mode)).open();
    }

    private void choose(EconomyMode mode) {
        Reply reply = creating ? islands.islands().create(viewer.getUniqueId(), viewer.getName(), mode)
                : islands.islands().decide(viewer.getUniqueId(), mode);
        IOneBlockScreen.tell(services.messages(), viewer, reply);
        viewer.closeInventory();
        if (reply.ok() && creating) {
            islands.book().ofPlayer(viewer.getUniqueId())
                    .ifPresent(island -> islands.travel().go(viewer, island, IslandDimension.OVERWORLD));
        }
    }

    @Override
    public String describe() {
        return "choosing the money an island plays with, once";
    }
}
