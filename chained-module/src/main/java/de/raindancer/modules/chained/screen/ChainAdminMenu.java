package de.raindancer.modules.chained.screen;

import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.choose.PlayerChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.chained.ChainedServices;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * The admin's own page: pairing two players, starting and stopping a run, and the one irreversible
 * action this module has — resetting the configured map.
 */
public final class ChainAdminMenu extends Menu implements IChainedScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final ChainedServices services;

    public ChainAdminMenu(ChainedServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Chained — admin");
    }

    @Override
    public String breadcrumb() {
        return "Chained admin";
    }

    @Override
    protected void render() {
        int pairs = services.pairs().count();

        band(MenuLayout.WHO, 1, Icons.of(Material.IRON_CHAIN, "<white>Pair two players",
                        "<gray>Choose two players and how far apart",
                        "<gray>they may go.",
                        "",
                        "<dark_gray>" + pairs + " pair(s) chained right now."),
                click -> pickFirst());

        band(MenuLayout.RULES, 1, Icons.of(Material.LIME_DYE, "<white>Start a run",
                        "<gray>Starts the speedrun clock for your",
                        "<gray>own pair. Pair yourself first."),
                click -> {
                    if (services.chain().start(viewer.getUniqueId()).isPresent()) {
                        services.messages().send(viewer, "chained.started");
                    } else {
                        services.messages().send(viewer, "chained.start-refused");
                    }
                    refresh();
                });

        band(MenuLayout.RULES, 3, Icons.of(Material.RED_DYE, "<white>Stop a run",
                        "<gray>Ends your own pair's run early."),
                click -> {
                    if (services.chain().stop(viewer.getUniqueId())) {
                        services.messages().send(viewer, "chained.stopped");
                    } else {
                        services.messages().send(viewer, "chained.stop-refused");
                    }
                    refresh();
                });

        danger(Icons.of(Material.TNT, "<red>Reset the map",
                        "<gray>Throws the configured world away and",
                        "<gray>makes it again, with the seed policy",
                        "<gray>from the settings.",
                        "",
                        "<dark_gray>This is the button the confirmation exists for."),
                click -> confirmReset());
    }

    /** Opens the confirmation before resetting the map — the one irreversible action here. */
    private void confirmReset() {
        new ConfirmScreen(services, viewer, this, "Reset the map?",
                List.of("The configured world is deleted and made again.",
                        "Anybody currently paired is moved out first.",
                        "This cannot be undone."),
                () -> services.chain().resetWorld(null, done ->
                        services.messages().send(viewer, done ? "chained.reset-done" : "chained.reset-refused"))
                ).open();
    }

    private void pickFirst() {
        new PlayerChooser(viewer, services.brand(), this, "Chain: pick the first player",
                List.of(), entry -> pickSecond(entry.id())).open();
    }

    private void pickSecond(UUID first) {
        new PlayerChooser(viewer, services.brand(), this, "Chain: pick the second player",
                List.of(first), entry -> pickDistance(first, entry.id())).open();
    }

    private void pickDistance(UUID first, UUID second) {
        int start = services.config().maxDistance();
        new AmountChooser(viewer, services.brand(), this, "Max distance (blocks)",
                start, 1, 10_000,
                amount -> {
                    services.chain().pair(first, second, amount);
                    services.messages().send(viewer, "chained.paired",
                            "first", nameOf(first), "second", nameOf(second), "distance", amount);
                }).open();
    }

    private static String nameOf(UUID id) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name == null ? "somebody" : name;
    }

    @Override
    public String describe() {
        return "pairing players, starting and stopping a run, and resetting the map";
    }
}
