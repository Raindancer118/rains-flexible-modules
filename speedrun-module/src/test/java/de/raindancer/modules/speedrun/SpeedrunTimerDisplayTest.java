package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.actionbar.ActionBarSink;
import de.raindancer.core.ui.actionbar.ActionBars;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run clock on the action bar: counts up once a second while a run is going, and disappears the
 * moment it finishes — without a real {@link ActionBars} tick or a live Paper scheduler, the same
 * trick {@code SpeedrunCountdownTest} uses for the boss bar.
 */
class SpeedrunTimerDisplayTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private Map<UUID, Component> shown;
    private ActionBars actionBars;
    private Consumer<Runnable> capturedTick;

    @BeforeEach
    void setUp() {
        shown = new HashMap<>();
        ActionBarSink sink = (player, message) -> {
            if (message.equals(Component.empty())) {
                shown.remove(player);
            } else {
                shown.put(player, message);
            }
        };
        actionBars = new ActionBars(sink, new AtomicLong(0)::get);
    }

    private String textFor(UUID player) {
        Component component = shown.get(player);
        return component == null ? null : PlainTextComponentSerializer.plainText().serialize(component);
    }

    private SpeedrunTimerDisplay.Ticker manualTicker() {
        return task -> {
            capturedTick = ignored -> task.run();
            return () -> capturedTick = null;
        };
    }

    private void tick() {
        capturedTick.accept(null);
    }

    @Test
    @DisplayName("shows 0:00 to every participant the instant the run starts")
    void showsImmediately() {
        SpeedrunTimerDisplay display = new SpeedrunTimerDisplay(actionBars, manualTicker());
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE, BOB));
        session.start();

        display.start(session);

        assertThat(textFor(ALICE)).isEqualTo("0:00");
        assertThat(textFor(BOB)).isEqualTo("0:00");
    }

    @Test
    @DisplayName("formats as m:ss, zero-padded, for any elapsed duration")
    void formatsAsMinutesAndSeconds() {
        assertThat(plain(SpeedrunTimerDisplay.format(Duration.ZERO))).isEqualTo("0:00");
        assertThat(plain(SpeedrunTimerDisplay.format(Duration.ofSeconds(5)))).isEqualTo("0:05");
        assertThat(plain(SpeedrunTimerDisplay.format(Duration.ofSeconds(65)))).isEqualTo("1:05");
        assertThat(plain(SpeedrunTimerDisplay.format(Duration.ofMinutes(12).plusSeconds(3))))
                .isEqualTo("12:03");
    }

    @Test
    @DisplayName("re-shows the session's current elapsed time on every tick")
    void reShowsOnEveryTick() {
        SpeedrunTimerDisplay display = new SpeedrunTimerDisplay(actionBars, manualTicker());
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        display.start(session);

        tick();
        tick();

        assertThat(textFor(ALICE)).startsWith("0:0");
    }

    @Test
    @DisplayName("clears every participant's bar and stops ticking the instant the run finishes")
    void clearsOnFinish() {
        SpeedrunTimerDisplay display = new SpeedrunTimerDisplay(actionBars, manualTicker());
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE, BOB));
        session.start();
        display.start(session);
        assertThat(textFor(ALICE)).isNotNull();

        session.finish("advancement:minecraft:end/kill_dragon");

        assertThat(textFor(ALICE)).isNull();
        assertThat(textFor(BOB)).isNull();
        assertThat(capturedTick).isNull();
    }

    @Test
    @DisplayName("starting a second time cancels the first run's tick instead of stacking it")
    void restartingCancelsThePrevious() {
        SpeedrunTimerDisplay display = new SpeedrunTimerDisplay(actionBars, manualTicker());
        SpeedrunSession first = new SpeedrunSession(Set.of(ALICE));
        first.start();
        display.start(first);
        Consumer<Runnable> firstTick = capturedTick;

        SpeedrunSession second = new SpeedrunSession(Set.of(BOB));
        second.start();
        display.start(second);

        assertThat(capturedTick).isNotSameAs(firstTick);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
