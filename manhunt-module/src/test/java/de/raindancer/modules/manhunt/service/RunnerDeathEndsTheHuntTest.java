package de.raindancer.modules.manhunt.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.ManhuntSettings.RunnerDeathRule;
import de.raindancer.modules.manhunt.conditions.AllRunnersDeadEndCondition;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import de.raindancer.modules.speedrun.SpeedrunOutcome;
import de.raindancer.modules.speedrun.SpeedrunReset;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A Runner dying ends the hunt when the Hunters' win is ALL_RUNNERS_DEAD — end to end, through the
 * real service, the real death listener and the real end condition, in the order Bukkit runs them.
 *
 * <p>Reported live: "I set the target to all runners dead and the runner died and it did not end".
 * The unit tests each piece had passed on its own; this is the one that wires them together.
 */
class RunnerDeathEndsTheHuntTest {

    private final UUID runner = UUID.randomUUID();
    private final UUID hunter = UUID.randomUUID();

    private Plugin plugin;
    private PluginManager pluginManager;
    private ManhuntTeams teams;
    private MockedStatic<Scheduling> scheduling;
    private MockedStatic<Bukkit> bukkit;
    private Messages deathMessages;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        pluginManager = mock(PluginManager.class);
        lenient().when(plugin.getServer()).thenReturn(server);
        lenient().when(server.getPluginManager()).thenReturn(pluginManager);
        lenient().when(server.getWorld(org.mockito.ArgumentMatchers.anyString())).thenReturn(mock(World.class));

        teams = new ManhuntTeams(() -> false);
        teams.joinRunners(runner);
        teams.joinHunters(hunter);

        // "A tick later" happens now, so the test sees exactly what the server sees one tick on.
        scheduling = mockStatic(Scheduling.class);
        scheduling.when(() -> Scheduling.globalLater(any(), anyLong(), any(Runnable.class)))
                .thenAnswer(call -> {
                    call.<Runnable>getArgument(2).run();
                    return null;
                });
        bukkit = mockStatic(Bukkit.class);
    }

    @AfterEach
    void tearDown() {
        scheduling.close();
        bukkit.close();
    }

    private static ManhuntSettings rules(RunnerDeathRule rule) {
        return ManhuntSettings.DEFAULTS
                .withResetOnStart(false)
                .withHunterReleaseDelaySeconds(0)
                .withCountdownSeconds(0)
                .withHunterWin(ManhuntSettings.HunterWinCondition.ALL_RUNNERS_DEAD)
                .withRunnerDeathRule(rule);
    }

    /** Starts a hunt, and hands back the death listener and the end condition Bukkit would call. */
    private record Hunt(ManhuntService service, ManhuntDeathListener deaths, AllRunnersDeadEndCondition condition) {
    }

    private Hunt start(ManhuntSettings settings) {
        ManhuntService service = new ManhuntService(plugin, teams, mock(Messages.class), mock(SpeedrunReset.class),
                ManhuntService.manual(), ManhuntService.immediate(), settings);
        deathMessages = mock(Messages.class);
        ManhuntDeathListener deaths = new ManhuntDeathListener(plugin, service, service.lives(),
                deathMessages, settings);
        assertThat(service.start()).isEqualTo(ManhuntService.StartOutcome.STARTED);

        ArgumentCaptor<Listener> registered = ArgumentCaptor.forClass(Listener.class);
        verify(pluginManager, atLeastOnce()).registerEvents(registered.capture(), any());
        AllRunnersDeadEndCondition condition = registered.getAllValues().stream()
                .filter(AllRunnersDeadEndCondition.class::isInstance)
                .map(AllRunnersDeadEndCondition.class::cast)
                .findFirst()
                .orElse(null);  // not armed at all under TIMEOUT with RESPAWN, where nothing can put a Runner out
        return new Hunt(service, deaths, condition);
    }

    /** One death, delivered in Bukkit's order: the death listener at HIGH, the condition at MONITOR. */
    private void die(Hunt hunt, UUID who) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(who);
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getEntity()).thenReturn(player);
        hunt.deaths().onDeath(event);
        if (hunt.condition() != null) {
            hunt.condition().onDeath(event);
        }
    }

    @Test
    @DisplayName("ELIMINATE: the only Runner dying ends the hunt for the Hunters")
    void eliminateEndsOnFirstDeath() {
        Hunt hunt = start(rules(RunnerDeathRule.ELIMINATE));

        die(hunt, runner);

        assertThat(hunt.service().isRunning()).isFalse();
    }

    @Test
    @DisplayName("LIVES: the hunt ends on the last life, not before")
    void livesEndsOnTheLastLife() {
        Hunt hunt = start(rules(RunnerDeathRule.LIVES).withRunnerLives(2));

        die(hunt, runner);
        assertThat(hunt.service().isRunning()).as("one life left").isTrue();

        die(hunt, runner);
        assertThat(hunt.service().isRunning()).isFalse();
    }

    @Test
    @DisplayName("RESPAWN with a timeout: a death says the Runner is back, and never counts lives")
    void respawnNeverMentionsLives() {
        Hunt hunt = start(rules(RunnerDeathRule.RESPAWN)
                .withHunterWin(ManhuntSettings.HunterWinCondition.TIMEOUT));

        die(hunt, runner);

        // The live report: "You died. 2147483646 live(s) left." — RESPAWN counts against
        // Integer.MAX_VALUE internally, and that number was printed.
        org.mockito.Mockito.verify(deathMessages).send(any(Player.class),
                org.mockito.ArgumentMatchers.eq("manhunt.death.respawned"), any(Object[].class));
        org.mockito.Mockito.verify(deathMessages, org.mockito.Mockito.never()).send(any(Player.class),
                org.mockito.ArgumentMatchers.eq("manhunt.death.lives-left"), any(Object[].class));
        assertThat(hunt.service().isRunning()).isTrue();
    }

    @Test
    @DisplayName("a Hunter dying never ends it")
    void hunterDeathDoesNotEndIt() {
        Hunt hunt = start(rules(RunnerDeathRule.ELIMINATE));

        die(hunt, hunter);

        assertThat(hunt.service().isRunning()).isTrue();
    }
}
