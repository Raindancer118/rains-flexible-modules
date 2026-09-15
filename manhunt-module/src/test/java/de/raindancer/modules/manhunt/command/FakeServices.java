package de.raindancer.modules.manhunt.command;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.mode.ManhuntMode;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import de.raindancer.modules.manhunt.service.ManhuntWhitelistService;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;

/**
 * The services a command is handed, with real teams and settings and mocks for the rest — the same
 * shape {@code chained-module}'s own {@code FakeServices} has, and for the same reason: a command test
 * is about what the command does, not about how many collaborators it takes.
 */
final class FakeServices {

    final Messages messages = mock(Messages.class);
    final ManhuntTeams teams;
    final ManhuntMode mode = mock(ManhuntMode.class);
    final ManhuntWhitelistService whitelist = mock(ManhuntWhitelistService.class);
    final SettingsStore<ManhuntSettings> settings;
    final List<Player> screensOpenedFor = new ArrayList<>();
    final List<Player> confirmationsAskedOf = new ArrayList<>();
    /** What the last confirmation page would do on "yes" — run by a test that wants the click. */
    Runnable lastConfirmation;
    final ManhuntServices services;

    FakeServices(Path directory) {
        this.teams = new ManhuntTeams(() -> false);
        this.settings = new SettingsStore<>(
                SettingsSchema.of(ManhuntSettings.class, ManhuntSettings.DEFAULTS),
                directory.resolve("manhunt.yml"));
        this.settings.load();
        this.services = new ManhuntServices(messages, mock(Brand.class), settings, teams, mode,
                whitelist, new ManhuntServices.Screens() {
                    @Override
                    public void sides(Player viewer) {
                        screensOpenedFor.add(viewer);
                    }

                    @Override
                    public void confirm(Player viewer, String question, List<String> consequences,
                                        Runnable onYes) {
                        // Recorded rather than opened, and deliberately not run: whether the
                        // confirmation is asked for at all is the thing a command test is about.
                        confirmationsAskedOf.add(viewer);
                        lastConfirmation = onYes;
                    }
                });
    }
}
