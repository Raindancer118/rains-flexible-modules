package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.social.team.Teams;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import de.raindancer.modules.manhunt.service.ManhuntService;
import de.raindancer.modules.manhunt.util.PermissionNodes;
import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.SpeedrunState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Which side the viewer is on, the roster count for each, the clock if a hunt is going, and — for
 * whoever holds {@link PermissionNodes#ADMIN} — start, stop and a way into the chaos menu.
 */
public final class ManhuntLobbyMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final ManhuntServices services;

    public ManhuntLobbyMenu(ManhuntServices services, Player viewer, Menu parent) {
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
        ManhuntService manhunt = services.manhunt();
        ManhuntTeams teams = manhunt.teams();

        boolean isRunner = teams.isRunner(viewer.getUniqueId());
        boolean isHunter = teams.isHunter(viewer.getUniqueId());

        set(MenuLayout.HEADER_SUBJECT, headerFor(manhunt, teams));

        ManhuntSettings config = services.config();
        boolean runnersLocked = !config.runnerSelfJoinEnabled() && !viewer.hasPermission(PermissionNodes.ADMIN);

        if (runnersLocked) {
            band(MenuLayout.WHO, 1, Icons.locked(
                    Icons.of(Material.LIME_WOOL, isRunner ? "<green>You are a Runner" : "<white>Join the Runners",
                            "<gray>" + teams.runners().size() + " on this side"),
                    "Runners are assigned by an admin"), click -> { });
        } else {
            band(MenuLayout.WHO, 1, Icons.of(Material.LIME_WOOL,
                            isRunner ? "<green>You are a Runner" : "<white>Join the Runners",
                            "<gray>" + teams.runners().size() + " on this side",
                            "<dark_gray>Click to switch sides."),
                    click -> {
                        Teams.MembershipChange change = teams.joinRunners(viewer.getUniqueId());
                        if (change.status().isSuccess()) {
                            services.lobbyListener().relocateIfWaiting(viewer, manhunt.isRunning());
                        }
                        refresh();
                    });
        }

        band(MenuLayout.WHO, 7, Icons.of(Material.RED_WOOL,
                        isHunter ? "<red>You are a Hunter" : "<white>Join the Hunters",
                        "<gray>" + teams.hunters().size() + " on this side",
                        "<dark_gray>Click to switch sides."),
                click -> {
                    Teams.MembershipChange change = teams.joinHunters(viewer.getUniqueId());
                    if (change.status().isSuccess()) {
                        services.lobbyListener().relocateIfWaiting(viewer, manhunt.isRunning());
                    }
                    refresh();
                });

        if (isRunner || isHunter) {
            band(MenuLayout.WHO, 4, Icons.of(Material.BARRIER, "<gray>Leave",
                            "<dark_gray>Take yourself off whichever side you are on."),
                    click -> {
                        teams.leave(viewer.getUniqueId());
                        services.lobbyListener().releaseIfHeld(viewer);
                        refresh();
                    });
        }

        if (viewer.hasPermission(PermissionNodes.ADMIN)) {
            renderAdminRow(manhunt);
        }
        if (viewer.hasPermission(PermissionNodes.CHAOS)) {
            band(MenuLayout.TOOLBAR_ROW, 5, Icons.of(Material.BLAZE_POWDER, "<gold>Chaos",
                            "<dark_gray>Throw a chaos action at a running hunt."),
                    click -> new ManhuntChaosMenu(services, viewer, this).open());
        }
        band(MenuLayout.TOOLBAR_ROW, 3, Icons.of(Material.NETHER_STAR, "<gold>Achievements",
                        "<dark_gray>The curated set — earned or not."),
                click -> new ManhuntAchievementsMenu(services, viewer, this).open());
        if (viewer.hasPermission(PermissionNodes.ADMIN)) {
            band(MenuLayout.TOOLBAR_ROW, 1, Icons.of(Material.COMPASS, "<gold>Tracking compass",
                            "<dark_gray>Who the Hunters' compass follows, and what it gives away."),
                    click -> new ManhuntTrackerMenu(services, viewer, this).open());
            band(MenuLayout.TOOLBAR_ROW, 7, Icons.of(Material.COMMAND_BLOCK, "<gold>Options",
                            "<dark_gray>Five quick-access settings for this hunt."),
                    click -> new ManhuntOptionsMenu(services, viewer, this).open());
        }
    }

    private void renderAdminRow(ManhuntService manhunt) {
        boolean running = manhunt.isRunning();
        band(MenuLayout.RULES, 3, Icons.of(running ? Material.RED_DYE : Material.LIME_DYE,
                        running ? "<red>Stop the hunt" : "<green>Start the hunt",
                        "<dark_gray>" + (running
                                ? "Ends the run early."
                                : "Needs at least one Runner and one Hunter.")),
                click -> {
                    if (running) {
                        manhunt.stop();
                    } else {
                        manhunt.start();
                    }
                    refresh();
                });
    }

    private ItemStack headerFor(ManhuntService manhunt, ManhuntTeams teams) {
        Optional<SpeedrunSession> session = manhunt.session();
        if (session.isEmpty()) {
            return Icons.of(Material.COMPASS, "<gray>No hunt is going",
                    "<dark_gray>" + teams.runners().size() + " Runner(s), "
                            + teams.hunters().size() + " Hunter(s).");
        }
        SpeedrunSession run = session.get();
        boolean paused = run.state() == SpeedrunState.PAUSED;
        return Icons.of(paused ? Material.YELLOW_DYE : Material.LIME_DYE,
                paused ? "<yellow>Paused" : "<green>Running",
                "<gray>Elapsed: " + Times.brief(run.elapsed()));
    }

    public String describe() {
        return "the Manhunt lobby: sides, the clock, and — for admins — start/stop and chaos";
    }
}
