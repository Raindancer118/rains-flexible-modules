package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.model.ChaosAction;
import de.raindancer.modules.manhunt.model.ManhuntTeams;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChaosServiceTest {

    private Plugin plugin;
    private Server server;
    private ManhuntService manhunt;
    private ManhuntTeams teams;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        server = mock(Server.class);
        lenient().when(plugin.getServer()).thenReturn(server);

        teams = new ManhuntTeams(() -> false);
        manhunt = mock(ManhuntService.class);
        lenient().when(manhunt.teams()).thenReturn(teams);
        lenient().when(manhunt.isRunning()).thenReturn(true);
        lenient().when(manhunt.config()).thenReturn(ManhuntSettings.DEFAULTS.withChaosCooldownSeconds(0));
    }

    private ChaosService service(Clock clock) {
        return new ChaosService(plugin, manhunt, clock, new Random(1));
    }

    @Test
    @DisplayName("nothing is thrown at a hunt that is not running")
    void refusesWhenNotRunning() {
        when(manhunt.isRunning()).thenReturn(false);
        ChaosService service = service(Clock.systemUTC());

        assertThat(service.apply(ChaosAction.LIGHTNING_ON_A_RUNNER)).isEqualTo(ChaosService.Result.NOT_RUNNING);
    }

    @Test
    @DisplayName("an action with nobody to act on answers NO_TARGETS rather than throwing")
    void noTargetsIsAnAnswerNotAnException() {
        // No Runner ever joined `teams`, so LIGHTNING_ON_A_RUNNER has nobody to strike near.
        ChaosService service = service(Clock.systemUTC());

        assertThat(service.apply(ChaosAction.LIGHTNING_ON_A_RUNNER)).isEqualTo(ChaosService.Result.NO_TARGETS);
    }

    @Test
    @DisplayName("an online Runner is struck near by LIGHTNING_ON_A_RUNNER")
    void appliesToAnOnlineRunner() {
        UUID runnerId = UUID.randomUUID();
        teams.joinRunners(runnerId);
        Player runner = mock(Player.class);
        when(runner.isOnline()).thenReturn(true);
        when(runner.isDead()).thenReturn(false);
        when(runner.getWorld()).thenReturn(mock(org.bukkit.World.class));
        when(server.getPlayer(runnerId)).thenReturn(runner);

        ChaosService service = service(Clock.systemUTC());

        assertThat(service.apply(ChaosAction.LIGHTNING_ON_A_RUNNER)).isEqualTo(ChaosService.Result.APPLIED);
    }

    @Nested
    @DisplayName("cooldown gate")
    class CooldownGate {

        @Test
        @DisplayName("zero cooldown is always ready")
        void zeroCooldownAlwaysReady() {
            assertThat(ChaosService.cooldownElapsed(Instant.EPOCH, Instant.EPOCH.plusSeconds(1_000_000), 0))
                    .isTrue();
        }

        @Test
        @DisplayName("not enough time has passed yet")
        void notReadyYet() {
            Instant last = Instant.parse("2026-01-01T00:00:00Z");
            Instant now = last.plusSeconds(5);
            assertThat(ChaosService.cooldownElapsed(last, now, 10)).isFalse();
        }

        @Test
        @DisplayName("exactly the cooldown has passed")
        void readyAtExactlyTheBoundary() {
            Instant last = Instant.parse("2026-01-01T00:00:00Z");
            Instant now = last.plusSeconds(10);
            assertThat(ChaosService.cooldownElapsed(last, now, 10)).isTrue();
        }
    }

    @Test
    @DisplayName("a second action inside the cooldown window is refused")
    void secondActionInsideCooldownIsRefused() {
        UUID runnerId = UUID.randomUUID();
        teams.joinRunners(runnerId);
        Player runner = mock(Player.class);
        when(runner.isOnline()).thenReturn(true);
        when(runner.isDead()).thenReturn(false);
        when(runner.getWorld()).thenReturn(mock(org.bukkit.World.class));
        when(server.getPlayer(runnerId)).thenReturn(runner);
        when(manhunt.config()).thenReturn(ManhuntSettings.DEFAULTS.withChaosCooldownSeconds(30));

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(t0);
        Clock movable = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        ChaosService service = service(movable);

        assertThat(service.apply(ChaosAction.LIGHTNING_ON_A_RUNNER)).isEqualTo(ChaosService.Result.APPLIED);
        now.set(t0.plusSeconds(5));
        assertThat(service.apply(ChaosAction.LIGHTNING_ON_A_RUNNER)).isEqualTo(ChaosService.Result.ON_COOLDOWN);
        now.set(t0.plusSeconds(31));
        assertThat(service.apply(ChaosAction.LIGHTNING_ON_A_RUNNER)).isEqualTo(ChaosService.Result.APPLIED);
    }
}
