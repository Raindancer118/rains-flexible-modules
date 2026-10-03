package de.raindancer.modules.speedrun.manhunt.screen;

import de.raindancer.core.data.settings.SettingsMenu;
import de.raindancer.core.data.settings.SettingsNavigation;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.modules.speedrun.SpeedrunActions;
import de.raindancer.modules.speedrun.SpeedrunBoard;
import de.raindancer.modules.speedrun.SpeedrunGoalMenu;
import de.raindancer.modules.speedrun.SpeedrunHistoryMenu;
import de.raindancer.modules.speedrun.SpeedrunLobby;
import de.raindancer.modules.speedrun.SpeedrunPreflightMenu;
import de.raindancer.modules.speedrun.SpeedrunRunSummaryMenu;
import de.raindancer.modules.speedrun.SpeedrunSetupMenu;
import de.raindancer.modules.speedrun.SpeedrunStandingsMenu;
import de.raindancer.modules.speedrun.SpeedrunStatsMenu;
import de.raindancer.modules.speedrun.SpeedrunTimerDisplay;
import de.raindancer.modules.speedrun.SpeedrunToolkit;
import de.raindancer.modules.speedrun.manhunt.ManhuntServices;
import de.raindancer.modules.speedrun.manhunt.mode.ManhuntMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Every page of this module, opened from one place — for the commands ({@link ManhuntServices.Screens})
 * and for the pages themselves, which open each other with themselves as the parent so Back always
 * goes where the player came from.
 */
public final class Pages implements ManhuntServices.Screens {

    private final Plugin plugin;
    private final Supplier<ManhuntServices> services;
    private final Chat chat;
    private final Supplier<SettingsNavigation> navigation;
    private final ChatPrompts prompts;

    public Pages(Plugin plugin, Supplier<ManhuntServices> services, Chat chat, Supplier<SettingsNavigation> navigation,
                 ChatPrompts prompts) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.services = Objects.requireNonNull(services, "services");
        this.chat = chat;
        this.navigation = navigation;
        this.prompts = prompts;
    }

    public ManhuntServices services() {
        return services.get();
    }

    @Override
    public void open(Player viewer, ManhuntServices.Page page) {
        open(viewer, page, null);
    }

    public void open(Player viewer, ManhuntServices.Page page, Menu parent) {
        SpeedrunLobby lobby = services().lobby().get();
        if (lobby == null && page != ManhuntServices.Page.HUB && page != ManhuntServices.Page.SIDES) {
            services().messages().send(viewer, "manhunt.goal.no-lobby");
            return;
        }
        Menu menu = switch (page) {
            case HUB -> new ManhuntHubMenu(this, viewer, parent);
            case SIDES -> new SidesEditorMenu(this, viewer, parent);
            case PREFLIGHT -> new SpeedrunPreflightMenu(lobby, viewer, parent);
            case GOAL -> new SpeedrunGoalMenu(lobby, viewer, parent);
            case LEADERBOARD -> new SpeedrunStandingsMenu(lobby, viewer, parent, ManhuntMode.ID, SpeedrunBoard.RATING);
            case HISTORY -> new SpeedrunHistoryMenu(lobby, viewer, parent, null, "", ManhuntMode.ID);
            case SETUP -> new SpeedrunSetupMenu(lobby, viewer, parent, 0);
        };
        menu.open();
    }

    @Override
    public void stats(Player viewer, UUID whose) {
        stats(viewer, whose, null);
    }

    public void stats(Player viewer, UUID whose, Menu parent) {
        SpeedrunLobby lobby = services().lobby().get();
        if (lobby != null) {
            new SpeedrunStatsMenu(lobby, viewer, parent, whose, services().stats().get(whose).name()).open();
        }
    }

    @Override
    public void summary(Player viewer, int number) {
        SpeedrunLobby lobby = services().lobby().get();
        if (lobby == null) {
            return;
        }
        lobby.toolkit().map(SpeedrunToolkit::history).flatMap(history -> history.byNumber(number))
                .ifPresent(run -> new SpeedrunRunSummaryMenu(lobby, run, viewer, null).open());
    }

    @Override
    public void confirm(Player viewer, String question, List<String> consequences, Runnable onYes) {
        // Core's own page — see ConfirmMenu. The closing line is this module's: nothing is undone by no.
        new ConfirmMenu(viewer, services().brand(), null, question, consequences,
                "<dark_gray>The hunt carries on either way.", onYes).open();
    }

    /** Core's settings page at {@code path}, Back leading to {@code parent}. */
    public void settings(Player viewer, Menu parent, String path) {
        SettingsNavigation live = navigation == null ? null : navigation.get();
        if (live == null || chat == null) {
            services().messages().send(viewer, "manhunt.settings-unavailable");
            return;
        }
        new SettingsMenu(viewer, services().brand(), chat, live, path, parent).open();
    }

    /** Runs a {@code /manhunt} word as the viewer — the same door the chat uses. */
    public void run(Player viewer, String command) {
        viewer.performCommand(command);
    }

    /** Asks for the time a resumed hunt had reached, in chat, then resumes at it. */
    @Override
    public void askResumeTime(Player viewer) {
        SpeedrunLobby lobby = services().lobby().get();
        if (lobby == null) {
            services().messages().send(viewer, "manhunt.goal.no-lobby");
            return;
        }
        // The lobby's own question; the answer goes through /manhunt resume, so it is asked for the
        // same node again at the moment it is answered.
        new SpeedrunActions(lobby, services().messages()).askForTime(viewer, "speedrun.resume.ask",
                time -> run(viewer, "manhunt resume " + SpeedrunTimerDisplay.plain(time)));
    }
}
