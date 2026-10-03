package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.data.settings.SettingsMenu;
import de.raindancer.core.data.settings.SettingsNavigation;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.menu.ConfirmMenu;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.modules.manhunt.ManhuntServices;
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
        Menu menu = switch (page) {
            case HUB -> new ManhuntHubMenu(this, viewer, parent);
            case SIDES -> new SidesEditorMenu(this, viewer, parent);
            case PREFLIGHT -> new PreflightMenu(this, viewer, parent);
            case GOAL -> new GoalMenu(this, viewer, parent);
            case RESUME -> new ResumeMenu(this, viewer, parent);
            case LEADERBOARD -> new LeaderboardMenu(this, viewer, parent);
            case HISTORY -> new HistoryMenu(this, viewer, parent);
            case SETUP -> new SetupWizardMenu(this, viewer, parent);
        };
        menu.open();
    }

    @Override
    public void stats(Player viewer, UUID whose) {
        new StatsMenu(this, viewer, null, whose).open();
    }

    public void stats(Player viewer, UUID whose, Menu parent) {
        new StatsMenu(this, viewer, parent, whose).open();
    }

    @Override
    public void summary(Player viewer, int number) {
        services().chronicle().history().find(number)
                .ifPresent(record -> new SummaryMenu(this, viewer, null, record).open());
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
    public void askResumeTime(Player viewer) {
        viewer.closeInventory();
        if (prompts == null || !prompts.ask(viewer.getUniqueId(), "manhunt", Duration.ofMinutes(1),
                answer -> Scheduling.entity(plugin, viewer, () -> run(viewer, "manhunt resume " + answer.trim())),
                () -> services().messages().send(viewer, "manhunt.resume.cancelled"))) {
            services().messages().send(viewer, "manhunt.resume.busy");
            return;
        }
        services().messages().send(viewer, "manhunt.resume.ask");
    }
}
