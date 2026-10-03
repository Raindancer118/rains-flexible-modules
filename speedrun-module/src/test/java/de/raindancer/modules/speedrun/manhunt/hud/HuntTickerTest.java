package de.raindancer.modules.speedrun.manhunt.hud;

import de.raindancer.core.ui.bossbar.BossBars;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.scoreboard.Scoreboards;
import de.raindancer.modules.speedrun.manhunt.ManhuntSettings;
import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import de.raindancer.modules.speedrun.manhunt.service.HunterHoldListener;
import de.raindancer.modules.speedrun.manhunt.stats.HuntLog;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("a hunt's heartbeat")
class HuntTickerTest {

    private static final UUID RUNNER = UUID.nameUUIDFromBytes("runner".getBytes());
    private static final UUID HUNTER = UUID.nameUUIDFromBytes("hunter".getBytes());

    private final Server server = mock(Server.class);
    private final Plugin plugin = mock(Plugin.class);
    private final World world = mock(World.class);
    private final Hunt hunt = Hunt.of(Set.of(RUNNER, HUNTER), Set.of(RUNNER));
    private final HuntLog log = new HuntLog(() -> 0L, Map.of(RUNNER, "Runner"), Map.of(HUNTER, "Hunter"));
    private final Messages messages = mock(Messages.class, invocation ->
            invocation.getMethod().getReturnType() == Component.class ? Component.text("x")
                    : Answers.RETURNS_DEFAULTS.answer(invocation));
    private final Scoreboards scoreboards = mock(Scoreboards.class);
    private final BossBars bars = mock(BossBars.class);
    private final AtomicReference<ManhuntSettings> settings = new AtomicReference<>(ManhuntSettings.DEFAULTS);
    private final AtomicReference<Duration> clock = new AtomicReference<>(Duration.ZERO);
    private final List<Player> glowed = new ArrayList<>();
    private final HunterHoldListener hold = new HunterHoldListener(hunt);
    private Player runner;
    private Player hunter;

    @BeforeEach
    void setUp() {
        when(plugin.getServer()).thenReturn(server);
        when(server.getOfflinePlayer(any(UUID.class))).thenReturn(mock(OfflinePlayer.class));
        when(world.getName()).thenReturn("speedrun");
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        runner = online(RUNNER, "Runner", 0);
        hunter = online(HUNTER, "Hunter", 50);
    }

    private Player online(UUID id, String name, double x) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.getLocation()).thenReturn(new Location(world, x, 64, 0));
        when(server.getPlayer(id)).thenReturn(player);
        return player;
    }

    private HuntTicker ticker(int headStart) {
        return new HuntTicker(plugin, hunt, log, clock::get, hold, headStart, 0, settings::get, messages,
                bars, glowed::add);
    }

    /**
     * The sidebar is the lobby's one — wherever each player chose to see it. What the ticker owns is
     * Manhunt's lines on it, as of the last beat.
     */
    @Test
    @DisplayName("Manhunt's lines for the lobby's sidebar: every Runner and the pack, as of the last beat")
    void sidebarLines() {
        HuntTicker ticker = ticker(0);
        ticker.linesFor(HUNTER);
        verify(messages, never()).get(eq("manhunt.hud.runner-near"), any(Object[].class));

        ticker.step(1000);

        assertThat(ticker.linesFor(HUNTER)).isNotEmpty();
        verify(messages, atLeastOnce()).get(eq("manhunt.hud.runner-near"), any(Object[].class));
        verify(scoreboards, never()).show(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("the head-start bar counts down while the Hunters are held, and goes the moment they are not")
    void headStartBar() {
        HuntTicker ticker = ticker(30);

        ticker.step(10_000);
        verify(bars).showShared(eq(HuntTicker.OWNER), eq(HuntTicker.BAR), any(), any(), any());

        hold.release();
        ticker.step(11_000);
        verify(bars).clearShared(HuntTicker.OWNER, HuntTicker.BAR);
    }

    @Test
    @DisplayName("the Runners glow on time, and only the ones still in it")
    void glow() {
        settings.set(ManhuntSettings.DEFAULTS.withGlowingRunnersEveryMinutes(1));
        HuntTicker ticker = ticker(0);

        clock.set(Duration.ofSeconds(50));
        ticker.step(0);
        verify(messages, atLeastOnce()).send(any(Player.class), eq("manhunt.glow.soon"));
        assertThat(glowed).isEmpty();

        clock.set(Duration.ofSeconds(61));
        ticker.step(0);
        assertThat(glowed).containsExactly(runner);

        hunt.eliminate(RUNNER);
        clock.set(Duration.ofSeconds(121));
        ticker.step(0);
        assertThat(glowed).as("a caught Runner glows no more").containsExactly(runner);
    }

    @Test
    @DisplayName("distance is added up step by step, and a teleport is not a journey")
    void distance() {
        HuntTicker ticker = ticker(0);
        ticker.step(0);
        when(hunter.getLocation()).thenReturn(new Location(world, 60, 64, 0));
        ticker.step(0);
        when(hunter.getLocation()).thenReturn(new Location(world, 5060, 64, 0));
        ticker.step(0);

        assertThat(log.distanceOf(HUNTER)).isEqualTo(10.0);
    }
}
