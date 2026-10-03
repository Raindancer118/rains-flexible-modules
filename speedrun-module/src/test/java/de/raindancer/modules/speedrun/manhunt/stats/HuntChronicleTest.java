package de.raindancer.modules.speedrun.manhunt.stats;

import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.chat.ClickActions;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.speedrun.SpeedrunCategory;
import de.raindancer.modules.speedrun.SpeedrunHistory;
import de.raindancer.modules.speedrun.SpeedrunMode;
import de.raindancer.modules.speedrun.SpeedrunOutcome;
import de.raindancer.modules.speedrun.SpeedrunRun;
import de.raindancer.modules.speedrun.SpeedrunRunRecord;
import de.raindancer.modules.speedrun.SpeedrunSeedType;
import de.raindancer.modules.speedrun.SpeedrunSession;
import de.raindancer.modules.speedrun.SpeedrunTimeline;
import de.raindancer.modules.speedrun.SpeedrunWorlds;
import de.raindancer.modules.speedrun.manhunt.ManhuntSettings;
import de.raindancer.modules.speedrun.manhunt.hud.HuntTicker;
import de.raindancer.modules.speedrun.manhunt.mode.ManhuntMode;
import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import de.raindancer.modules.speedrun.manhunt.service.HuntDeathListener;
import de.raindancer.modules.speedrun.manhunt.service.HuntWatcher;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("a hunt's record, kept as it is played")
class HuntChronicleTest {

    private static final UUID RUNNER = UUID.nameUUIDFromBytes("runner".getBytes());
    private static final UUID HUNTER = UUID.nameUUIDFromBytes("hunter".getBytes());

    private final Server server = mock(Server.class);
    private final Plugin plugin = mock(Plugin.class);
    private final Messages messages = mock(Messages.class, invocation ->
            invocation.getMethod().getReturnType() == Component.class ? Component.text("x")
                    : invocation.getMethod().getReturnType() == String.class ? "x"
                    : Answers.RETURNS_DEFAULTS.answer(invocation));
    private final HuntTicker ticker = mock(HuntTicker.class);
    private final AtomicLong now = new AtomicLong(0);
    private final AtomicReference<ManhuntSettings> settings = new AtomicReference<>(ManhuntSettings.DEFAULTS);
    private final AtomicReference<SpeedrunRunRecord> lastRun = new AtomicReference<>();
    private final Hunt hunt = Hunt.of(Set.of(RUNNER, HUNTER), Set.of(RUNNER));
    private final SpeedrunSession session = new SpeedrunSession(Set.of(RUNNER, HUNTER));
    private SpeedrunRun run;
    private HuntChronicle chronicle;
    private Player runner;
    private int recordersListened;

    @BeforeEach
    void setUp() {
        when(plugin.getServer()).thenReturn(server);
        when(server.getOfflinePlayer(any(UUID.class))).thenReturn(mock(OfflinePlayer.class));
        runner = online(RUNNER, "Runner");
        online(HUNTER, "Hunter");
        session.start();
        run = new SpeedrunRun(plugin, session, SpeedrunWorlds.around("speedrun"));
        chronicle = new HuntChronicle(plugin, settings::get, messages,
                new ChatButtons(new ClickActions(System::currentTimeMillis), ""), now::get,
                (h, log, r, hold, headStart) -> ticker, (h, r, c) -> recordersListened++,
                () -> Optional.ofNullable(lastRun.get()), kept -> 3);
    }

    private Player online(UUID id, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(server.getPlayer(id)).thenReturn(player);
        return player;
    }

    private static SpeedrunOutcome outcome(String reason) {
        return new SpeedrunOutcome(reason, Duration.ofMinutes(9), Instant.now());
    }

    /** What the lobby keeps of the run, built from the mode's results the way its recorder does. */
    private SpeedrunRunRecord kept(SpeedrunOutcome outcome, SpeedrunMode.Results results) {
        return new SpeedrunRunRecord("r", new SpeedrunCategory("", SpeedrunSeedType.RANDOM, ManhuntMode.ID, ""), 0,
                outcome.elapsed(), outcome.reason(), false, 1, Map.of(RUNNER, "Runner", HUNTER, "Hunter"),
                session.timeline().entries(), Map.of(), results.players(), results.winner());
    }

    @Test
    @DisplayName("a hunt starting gets its own record, its listeners and its HUD")
    void starts() {
        chronicle.started(hunt, run, null, 0);

        assertThat(chronicle.currentLog()).isPresent();
        assertThat(recordersListened).isEqualTo(1);
        verify(ticker).start();
    }

    @Test
    @DisplayName("at the end: everybody's results and the winner go to the lobby's history, the HUD goes, and everybody gets the summary")
    void ends() {
        chronicle.started(hunt, run, null, 0);
        now.set(9 * 60_000);
        chronicle.died(hunt, RUNNER, "Runner", HUNTER, "Hunter", HuntWatcher.Death.CAUGHT, 0);
        SpeedrunOutcome over = outcome(HuntDeathListener.HUNTERS_WIN);

        SpeedrunMode.Results results = chronicle.results(hunt, session, over).orElseThrow();
        assertThat(results.winner()).isEqualTo(SpeedrunHistory.HUNTERS);
        assertThat(results.rated()).isTrue();
        assertThat(results.players()).filteredOn(p -> p.id().equals(HUNTER)).singleElement()
                .satisfies(p -> assertThat(p.catches()).isEqualTo(1));
        assertThat(session.timeline().of(SpeedrunTimeline.Kind.CAUGHT)).hasSize(1);
        lastRun.set(kept(over, results));

        chronicle.ended(hunt, Optional.of(over));

        verify(ticker).stop();
        verify(runner, atLeastOnce()).sendMessage(any(Component.class));
        verify(messages).prefixed(eq("manhunt.summary.header-hunters"), eq("number"), eq("3"), any(), any());
        assertThat(chronicle.currentLog()).isEmpty();
    }

    @Test
    @DisplayName("an abandoned hunt is kept as nobody's win, and is not announced")
    void abandoned() {
        chronicle.started(hunt, run, null, 0);

        assertThat(chronicle.results(hunt, session, outcome("admin-reset")).orElseThrow().winner()).isEmpty();
        chronicle.ended(hunt, Optional.empty());

        verify(runner, never()).sendMessage(any(Component.class));
    }

    @Test
    @DisplayName("with stats off the hunt is kept but rates nobody")
    void statsOff() {
        settings.set(ManhuntSettings.DEFAULTS.withStatsEnabled(false));
        chronicle.started(hunt, run, null, 0);

        assertThat(chronicle.results(hunt, session, outcome(HuntDeathListener.HUNTERS_WIN)).orElseThrow().rated())
                .isFalse();
    }

    @Test
    @DisplayName("nothing it does wrong reaches the hunt")
    void neverThrows() {
        when(plugin.getServer()).thenThrow(new IllegalStateException("broken"));

        assertThatCode(() -> {
            chronicle.started(hunt, run, null, 0);
            chronicle.ended(hunt, Optional.of(outcome("x")));
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("who won is read off what ended it")
    void winners() {
        assertThat(HuntChronicle.winnerOf(HuntDeathListener.HUNTERS_WIN)).isEqualTo(SpeedrunHistory.HUNTERS);
        assertThat(HuntChronicle.winnerOf("advancement:minecraft:end/kill_dragon")).isEqualTo(SpeedrunHistory.RUNNERS);
        assertThat(HuntChronicle.winnerOf("admin-reset")).isEmpty();
        assertThat(HuntChronicle.winnerOf("manhunt:runners-left")).isEmpty();
    }
}
