package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.model.Hunt;
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
        } else if (services.config().sideSwitchingMidHunt()) {
            // A server that lets people change sides mid-hunt shows the same two buttons; they go
            // through ManhuntMode.changeSide, which moves the compass and the roster with them.
            band(MenuLayout.RULES, 4, Icons.of(Material.CLOCK, "<white>The hunt is on",
                    "<gray>" + hunt.eliminated().size() + " of " + hunt.runners().size()
                            + " Runner(s) caught.",
                    "<dark_gray>You may still change sides."));
            renderSwitchButtons();
        } else {
            band(MenuLayout.RULES, 4, Icons.of(Material.CLOCK, "<white>The hunt is on",
                    "<gray>" + hunt.eliminated().size() + " of " + hunt.runners().size()
                            + " Runner(s) caught.",
                    "<dark_gray>Sides are fixed until it ends."));
        }
    }

    /**
     * The two buttons again, mid-hunt, for a server that allows it. Each one asks the mode rather
     * than the teams: a side change during a hunt is a compass and a roster as well as a colour, and
     * {@code ManhuntMode.changeSide} is the only door that moves all three. Whatever it answers —
     * "somebody has to be running", say — is said by the mode itself.
     */
    private void renderSwitchButtons() {
        band(MenuLayout.RULES, 3, Icons.of(Material.FEATHER, "<white>Run instead",
                        "<gray>Give up the chase and race the goal.",
                        "<dark_gray>Your compass goes back."),
                click -> {
                    switchTo(de.raindancer.modules.manhunt.mode.ManhuntMode.Side.RUNNER);
                    refresh();
                });
        band(MenuLayout.RULES, 5, Icons.of(Material.IRON_SWORD, "<white>Hunt instead",
                        "<gray>Stop running and join the pack.",
                        "<dark_gray>You are handed a tracking compass."),
                click -> {
                    switchTo(de.raindancer.modules.manhunt.mode.ManhuntMode.Side.HUNTER);
                    refresh();
                });
    }

    private void switchTo(de.raindancer.modules.manhunt.mode.ManhuntMode.Side side) {
        var outcome = services.mode().changeSide(viewer.getUniqueId(), side, false);
        switch (outcome) {
            case CHANGED -> { }   // the mode already told them which side they are on
            case FROZEN -> services.messages().send(viewer, "manhunt.sides-frozen");
            case ALREADY -> services.messages().send(viewer, "manhunt.side.already",
                    "player", viewer.getName());
            case LAST_RUNNER -> services.messages().send(viewer, "manhunt.side.last-runner",
                    "player", viewer.getName());
            case NOT_IN_THE_HUNT -> services.messages().send(viewer, "manhunt.side.not-in-hunt",
                    "player", viewer.getName());
            case NO_HUNT -> services.messages().send(viewer, "manhunt.side.hunt-over");
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

    /**
     * The choice, before a hunt starts.
     *
     * <h2>Why the Run button is gone rather than greyed when the Runners are hand-picked</h2>
     * Because on that server there is no choice to offer <em>anybody</em>, an admin included: the
     * Runners are named with {@code /manhunt assign}, and everybody else — including the admin who
     * has not been named — is hunting. A greyed button says "you might be allowed to press this";
     * a locked one an admin can press anyway would be a second door to a decision that is supposed
     * to have one. So the page shows the one side there is to choose.
     */
    private void renderJoinButtons() {
        if (!services.config().runnerSelfJoin()) {
            band(MenuLayout.RULES, 4, Icons.of(Material.IRON_SWORD, "<white>Hunt",
                            "<gray>Everybody here hunts.",
                            "<dark_gray>An admin picks the Runners on this server."),
                    click -> {
                        services.teams().joinHunters(viewer.getUniqueId());
                        services.messages().send(viewer, "manhunt.join.hunter");
                        refresh();
                    });
            return;
        }
        band(MenuLayout.RULES, 3, Icons.of(Material.FEATHER, "<white>Run",
                        "<gray>Race the goal with the Hunters behind you.",
                        "<dark_gray>Click to join the Runners."),
                click -> {
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
