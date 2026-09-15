package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The sides: who is running, who is chasing, and the two buttons that change that.
 *
 * <h2>Reached from the speedrun compass, not from a second item</h2>
 * This is the page behind the Manhunt button on {@code SpeedrunLobbyMenu} — the lobby's own compass is
 * the one item in the world, and this hangs off it like the goal and the hazard do.
 *
 * <h2>Greyed, never hidden, while a hunt is under way</h2>
 * The opposite of a staff warp: there is no secret here, and somebody who opens this mid-hunt wants to
 * see the rosters. So the buttons stay, with the reason on them, and the page doubles as the hunt's
 * own scoreboard once one is running.
 */
public final class ManhuntSidesMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final ManhuntServices services;

    public ManhuntSidesMenu(ManhuntServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Manhunt");
    }

    @Override
    public String breadcrumb() {
        return "Manhunt";
    }

    @Override
    protected void render() {
        Hunt hunt = services.mode().current().orElse(null);
        renderRosters(hunt);
        if (hunt == null) {
            renderJoinButtons();
        } else {
            band(MenuLayout.RULES, 4, Icons.of(Material.CLOCK, "<white>The hunt is on",
                    "<gray>" + hunt.eliminated().size() + " of " + hunt.runners().size()
                            + " Runner(s) caught.",
                    "<dark_gray>Sides are fixed until it ends."));
        }
    }

    private void renderRosters(Hunt hunt) {
        List<String> runnerLore = new ArrayList<>();
        List<String> hunterLore = new ArrayList<>();
        if (hunt == null) {
            runnerLore.add("<gray>" + count(services.teams().runners().size()) + " running.");
            runnerLore.add("<dark_gray>Everybody else in the lobby hunts.");
            hunterLore.add("<gray>Everybody racing who is not a Runner.");
            hunterLore.add("<dark_gray>" + count(services.teams().hunters().size()) + " on the side so far.");
        } else {
            runnerLore.add("<gray>" + count(hunt.livingRunners().size()) + " still running.");
            runnerLore.add("<dark_gray>" + count(hunt.eliminated().size()) + " caught.");
            hunterLore.add("<gray>" + count(hunt.hunters().size()) + " chasing.");
        }
        band(MenuLayout.WHO, 3, Icons.of(Material.LIME_BANNER, "<green>Runners", runnerLore));
        band(MenuLayout.WHO, 5, Icons.of(Material.RED_BANNER, "<red>Hunters", hunterLore));
    }

    private void renderJoinButtons() {
        boolean mayRun = services.config().runnerSelfJoin()
                || viewer.hasPermission(PermissionNodes.ADMIN);
        var runButton = Icons.of(Material.FEATHER, "<white>Run",
                "<gray>Race the goal with the Hunters behind you.",
                "<dark_gray>Click to join the Runners.");
        band(MenuLayout.RULES, 3,
                mayRun ? runButton : Icons.locked(runButton, "Only an admin picks the Runners here."),
                click -> {
                    if (!mayRun) {
                        return;
                    }
                    services.teams().joinRunners(viewer.getUniqueId());
                    services.messages().send(viewer, "manhunt.join.runner");
                    refresh();
                });
        band(MenuLayout.RULES, 5, Icons.of(Material.IRON_SWORD, "<white>Hunt",
                        "<gray>Chase whoever is running.",
                        "<dark_gray>Click to leave the Runners."),
                click -> {
                    services.teams().joinHunters(viewer.getUniqueId());
                    services.messages().send(viewer, "manhunt.join.hunter");
                    refresh();
                });
    }

    private static String count(int howMany) {
        return howMany == 0 ? "Nobody" : String.valueOf(howMany);
    }
}
