package de.raindancer.modules.manhunt.stats;

import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.chat.ClickActions;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.hud.Announcer;
import de.raindancer.modules.manhunt.hud.HuntTicker;
import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.service.HuntDeathListener;
import de.raindancer.modules.manhunt.service.HuntWatcher;
import de.raindancer.modules.speedrun.SpeedrunOutcome;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("a hunt's record, kept as it is played")
class HuntChronicleTest {

    private static final UUID RUNNER = UUID.nameUUIDFromBytes("runner".getBytes());
    private static final UUID HUNTER = UUID.nameUUIDFromBytes("hunter".getBytes());

    @TempDir
    Path directory;

    private final Server server = mock(Server.class);
    private final Plugin plugin = mock(Plugin.class);
    private final Messages messages = mock(Messages.class, invocation ->
            invocation.getMethod().getReturnType() == Component.class ? Component.text("x")
                    : invocation.getMethod().getReturnType() == String.class ? "x"
                    : Answers.RETURNS_DEFAULTS.answer(invocation));
    private final Announcer announcer = mock(Announcer.class);
    private final HuntTicker ticker = mock(HuntTicker.class);
    private final AtomicLong now = new AtomicLong(0);
    private final AtomicReference<ManhuntSettings> settings = new AtomicReference<>(ManhuntSettings.DEFAULTS);
    private final Hunt hunt = Hunt.of(Set.of(RUNNER, HUNTER), Set.of(RUNNER));
    private HuntChronicle chronicle;
    private Player runner;
    private Player hunter;
    private int recordersListened;

    @BeforeEach
    void setUp() {
        when(plugin.getServer()).thenReturn(server);
        when(server.getOfflinePlayer(any(UUID.class))).thenReturn(mock(OfflinePlayer.class));
        runner = online(RUNNER, "Runner");
        hunter = online(HUNTER, "Hunter");
        chronicle = new HuntChronicle(plugin, settings::get, new StatsStore(directory.resolve("stats.yml")),
                new HistoryStore(directory.resolve("hunts.yml")), messages,
                new ChatButtons(new ClickActions(System::currentTimeMillis), ""), announcer, now::get,
                (h, log, run, hold, headStart) -> ticker, (h, run, c) -> recordersListened++);
    }

    private Player online(UUID id, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(server.getPlayer(id)).thenReturn(player);
        return player;
    }

    private static Optional<SpeedrunOutcome> outcome(String reason) {
        return Optional.of(new SpeedrunOutcome(reason, Duration.ofMinutes(9), Instant.now()));
    }

    @Test
    @DisplayName("a hunt starting gets its own record, its listeners and its HUD")
    void starts() {
        chronicle.started(hunt, null, null, 0);

        assertThat(chronicle.currentLog()).isPresent();
        assertThat(recordersListened).isEqualTo(1);
        verify(ticker).start();
    }

    @Test
    @DisplayName("at the end: the HUD goes, the stats and the history keep it, and everybody gets the summary")
    void ends() {
        chronicle.started(hunt, null, null, 0);
        now.set(9 * 60_000);
        chronicle.died(hunt, RUNNER, "Runner", HUNTER, "Hunter", HuntWatcher.Death.CAUGHT, 0);

        chronicle.ended(hunt, outcome(HuntDeathListener.HUNTERS_WIN));

        verify(ticker).stop();
        assertThat(chronicle.stats().get(HUNTER).hunterWins()).isEqualTo(1);
        assertThat(chronicle.stats().get(HUNTER).catches()).isEqualTo(1);
        HuntRecord kept = chronicle.history().latest().orElseThrow();
        assertThat(kept.winner()).isEqualTo(HuntRecord.Winner.HUNTERS);
        assertThat(kept.durationMillis()).isEqualTo(9 * 60_000L);
        verify(runner, atLeastOnce()).sendMessage(any(Component.class));
        verify(messages).prefixed(eq("manhunt.summary.header-hunters"), any(Object[].class));
        assertThat(chronicle.currentLog()).isEmpty();
    }

    @Test
    @DisplayName("an abandoned hunt is kept as nobody's win, moves no rating, and is not announced")
    void abandoned() {
        chronicle.started(hunt, null, null, 0);

        chronicle.ended(hunt, Optional.empty());

        assertThat(chronicle.history().latest().orElseThrow().winner()).isEqualTo(HuntRecord.Winner.NOBODY);
        assertThat(chronicle.stats().get(RUNNER).rating()).isEqualTo(Rating.START);
        verify(runner, never()).sendMessage(any(Component.class));
    }

    @Test
    @DisplayName("with stats off nothing is added to anybody, and the history still keeps the hunt")
    void statsOff() {
        settings.set(ManhuntSettings.DEFAULTS.withStatsEnabled(false));
        chronicle.started(hunt, null, null, 0);

        chronicle.ended(hunt, outcome(HuntDeathListener.HUNTERS_WIN));

        assertThat(chronicle.stats().has(HUNTER)).isFalse();
        assertThat(chronicle.history().all()).hasSize(1);
    }

    @Test
    @DisplayName("a milestone is announced the first time only")
    void milestoneOnce() {
        chronicle.started(hunt, null, null, 0);

        chronicle.reached(Milestone.NETHER, runner);
        chronicle.reached(Milestone.NETHER, runner);

        verify(announcer, times(1)).milestone(eq(hunt), eq(Milestone.NETHER), eq("Runner"), any(Long.class));
    }

    @Test
    @DisplayName("nothing it does wrong reaches the hunt")
    void neverThrows() {
        when(plugin.getServer()).thenThrow(new IllegalStateException("broken"));

        assertThatCode(() -> {
            chronicle.started(hunt, null, null, 0);
            chronicle.ended(hunt, outcome("x"));
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("who won is read off what ended it")
    void winners() {
        assertThat(HuntChronicle.winnerOf(HuntDeathListener.HUNTERS_WIN)).isEqualTo(HuntRecord.Winner.HUNTERS);
        assertThat(HuntChronicle.winnerOf("advancement:minecraft:end/kill_dragon")).isEqualTo(HuntRecord.Winner.RUNNERS);
        assertThat(HuntChronicle.winnerOf("admin-reset")).isEqualTo(HuntRecord.Winner.NOBODY);
        assertThat(HuntChronicle.winnerOf("manhunt:runners-left")).isEqualTo(HuntRecord.Winner.NOBODY);
    }
}
