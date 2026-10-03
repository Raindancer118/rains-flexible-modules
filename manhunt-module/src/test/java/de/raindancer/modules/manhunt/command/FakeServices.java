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
    /** The freeze the teams answer to — set by a test to stand in for a hunt that has just begun. */
    final java.util.concurrent.atomic.AtomicBoolean frozen = new java.util.concurrent.atomic.AtomicBoolean();
    final ManhuntMode mode = mock(ManhuntMode.class);
    final ManhuntWhitelistService whitelist = mock(ManhuntWhitelistService.class);
    final de.raindancer.modules.manhunt.service.PositionShare share =
            mock(de.raindancer.modules.manhunt.service.PositionShare.class);
    final SettingsStore<ManhuntSettings> settings;
    final List<Player> screensOpenedFor = new ArrayList<>();
    /** Every page a command opened, as {@code name:PAGE}. */
    final List<String> pages = new ArrayList<>();
    final List<String> statsPages = new ArrayList<>();
    final List<Integer> summaryPages = new ArrayList<>();
    /** Who a start would sweep up — the desk's lobby. */
    final java.util.Set<java.util.UUID> present = new java.util.LinkedHashSet<>();
    final java.util.Map<java.util.UUID, String> names = new java.util.HashMap<>();
    final de.raindancer.core.data.settings.SettingsRegistry registry = new de.raindancer.core.data.settings.SettingsRegistry();
    final de.raindancer.modules.manhunt.stats.StatsStore stats;
    final de.raindancer.modules.manhunt.stats.HistoryStore history;
    final de.raindancer.modules.manhunt.stats.HuntChronicle chronicle;
    final de.raindancer.modules.manhunt.setup.HuntDesk desk;
    final de.raindancer.modules.manhunt.setup.SetupState setup;
    final List<Player> confirmationsAskedOf = new ArrayList<>();
    /** Every /manhunt give that got through to the compasses: who, and which kind (null for all). */
    final List<Object[]> compassesGiven = new ArrayList<>();
    final List<Player> compassesTaken = new ArrayList<>();
    /** What the last confirmation page would do on "yes" — run by a test that wants the click. */
    Runnable lastConfirmation;
    final ManhuntServices services;

    FakeServices(Path directory) {
        this.teams = new ManhuntTeams(frozen::get);
        this.settings = new SettingsStore<>(
                SettingsSchema.of(ManhuntSettings.class, ManhuntSettings.DEFAULTS),
                directory.resolve("manhunt.yml"));
        this.settings.load();
        de.raindancer.core.data.settings.SettingsStore<de.raindancer.modules.speedrun.SpeedrunSettings> speedrun =
                new de.raindancer.core.data.settings.SettingsStore<>(SettingsSchema.of(
                        de.raindancer.modules.speedrun.SpeedrunSettings.class,
                        de.raindancer.modules.speedrun.SpeedrunSettings.DEFAULTS), directory.resolve("speedrun.yml"));
        speedrun.load();
        registry.add(settings);
        registry.add(speedrun);
        this.stats = new de.raindancer.modules.manhunt.stats.StatsStore(directory.resolve("stats.yml"));
        this.history = new de.raindancer.modules.manhunt.stats.HistoryStore(directory.resolve("hunts.yml"));
        org.bukkit.plugin.Plugin plugin = mock(org.bukkit.plugin.Plugin.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        this.chronicle = new de.raindancer.modules.manhunt.stats.HuntChronicle(plugin, settings::current, stats,
                history, messages, null, null, () -> 0L, null, null);
        this.desk = new de.raindancer.modules.manhunt.setup.HuntDesk(() -> present,
                id -> names.getOrDefault(id, "somebody"), settings, () -> registry, teams, mode::isRunning,
                whitelist::isClosed, stats, java.util.Set.of("minecraft:end/kill_dragon",
                        "minecraft:story/enter_the_nether")::contains, new java.util.Random(5));
        this.setup = new de.raindancer.modules.manhunt.setup.SetupState(directory.resolve("setup.yml"));
        this.services = new ManhuntServices(messages, mock(Brand.class), settings, teams, mode,
                whitelist, new ManhuntServices.Screens() {
                    @Override
                    public void open(Player viewer, ManhuntServices.Page page) {
                        screensOpenedFor.add(viewer);
                        pages.add(viewer.getName() + ":" + page);
                    }

                    @Override
                    public void stats(Player viewer, java.util.UUID whose) {
                        statsPages.add(viewer.getName() + ":" + whose);
                    }

                    @Override
                    public void summary(Player viewer, int number) {
                        summaryPages.add(number);
                    }

                    @Override
                    public void confirm(Player viewer, String question, List<String> consequences,
                                        Runnable onYes) {
                        // Recorded rather than opened, and deliberately not run: whether the
                        // confirmation is asked for at all is the thing a command test is about.
                        confirmationsAskedOf.add(viewer);
                        lastConfirmation = onYes;
                    }
                }, share, new ManhuntServices.Compasses() {
                    @Override
                    public void give(org.bukkit.command.CommandSender sender, Player target,
                                     java.util.Optional<de.raindancer.modules.manhunt.tracker.CompassHandout.Kind> kind) {
                        compassesGiven.add(new Object[]{target, kind.orElse(null)});
                    }

                    @Override
                    public void giveEverybody(org.bukkit.command.CommandSender sender,
                                              java.util.Optional<de.raindancer.modules.manhunt.tracker.CompassHandout.Kind> kind) {
                        compassesGiven.add(new Object[]{"all", kind.orElse(null)});
                    }

                    @Override
                    public void takeAll(Player player) {
                        compassesTaken.add(player);
                    }
                }, desk, chronicle, setup);
    }
}
