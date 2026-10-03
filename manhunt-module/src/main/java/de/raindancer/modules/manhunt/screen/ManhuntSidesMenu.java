package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.mode.ManhuntMode;
import de.raindancer.modules.manhunt.model.Hunt;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
                    switchTo(ManhuntMode.Side.RUNNER);
                    refresh();
                });
        band(MenuLayout.RULES, 5, Icons.of(Material.IRON_SWORD, "<white>Hunt instead",
                        "<gray>Stop running and join the pack.",
                        "<dark_gray>You are handed a tracking compass."),
                click -> {
                    switchTo(ManhuntMode.Side.HUNTER);
                    refresh();
                });
    }

    private void switchTo(ManhuntMode.Side side) {
        ManhuntMode.SideChange outcome = services.mode().changeSide(viewer.getUniqueId(), side, false);
        // A change itself is already said by the mode, in the words of the side they joined.
        if (outcome != ManhuntMode.SideChange.CHANGED) {
            services.messages().send(viewer, outcome.messageKey(), "player", viewer.getName(),
                    "side", side.name().toLowerCase(Locale.ROOT));
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
                    click -> join(false));
            return;
        }
        band(MenuLayout.RULES, 3, Icons.of(Material.FEATHER, "<white>Run",
                        "<gray>Race the goal with the Hunters behind you.",
                        "<dark_gray>Click to join the Runners."),
                click -> join(true));
        band(MenuLayout.RULES, 5, Icons.of(Material.IRON_SWORD, "<white>Hunt",
                        "<gray>Chase whoever is running.",
                        "<dark_gray>Click to leave the Runners."),
                click -> join(false));
    }

    /** A page drawn before a hunt began, or before the Runners were locked, can still be clicked after. */
    private void join(boolean runner) {
        if (runner && !services.config().runnerSelfJoin()) {
            services.messages().send(viewer, "manhunt.join.runners-locked");
        } else if (services.teams().join(viewer.getUniqueId(), runner)) {
            services.messages().send(viewer, runner ? "manhunt.join.runner" : "manhunt.join.hunter");
        } else {
            services.messages().send(viewer, "manhunt.sides-frozen");
        }
        refresh();
    }

    private static String count(int howMany) {
        return howMany == 0 ? "Nobody" : String.valueOf(howMany);
    }
}
