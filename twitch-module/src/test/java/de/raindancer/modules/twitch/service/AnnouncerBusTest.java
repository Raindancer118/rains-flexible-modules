package de.raindancer.modules.twitch.service;

import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.modules.twitch.TwitchSettings;
import de.raindancer.modules.twitch.model.StreamEvent;
import de.raindancer.modules.twitch.model.StreamInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** On a network the announcement crosses the bus: the watching server shows it, every other server shows it too. */
class AnnouncerBusTest {

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Map<String, String>> smpShown = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> arenaShown = new CopyOnWriteArrayList<>();
    private final Announcer smp = new Announcer(null, null, null, null, hub.bus("smp"), List::of,
            () -> TwitchSettings.DEFAULTS, smpShown::add);
    private final Announcer arena = new Announcer(null, null, null, null, hub.bus("arena"), List::of,
            () -> TwitchSettings.DEFAULTS, arenaShown::add);
    private final StreamInfo stream = new StreamInfo("1", "Lillyyxoxo_", "Building <the> castle: now", "Minecraft",
            Instant.now(), 5);

    @Test
    @DisplayName("live and ended are shown once here and once on the other server, whole")
    void reachesTheOtherServer() {
        arena.listen();
        smp.announce(new StreamEvent.WentLive(stream));
        smp.announce(new StreamEvent.Ended(stream, Duration.ofMinutes(125)));
        hub.drain();

        assertThat(smpShown).hasSize(2);
        assertThat(arenaShown).hasSize(2);
        assertThat(arenaShown.get(0)).containsEntry("kind", "live").containsEntry("name", "Lillyyxoxo_")
                .containsEntry("title", "Building <the> castle: now").containsEntry("game", "Minecraft");
        assertThat(arenaShown.get(1)).containsEntry("kind", "ended").containsEntry("lasted", "2 h 5 min");
    }

    @Test
    @DisplayName("the server that watches does not show it twice, even when it listens too")
    void notTwiceOnTheSender() {
        smp.listen();
        arena.listen();
        smp.announce(new StreamEvent.WentLive(stream));
        hub.drain();

        assertThat(smpShown).hasSize(1);
    }

    @Test
    @DisplayName("stopping stops listening, and a mangled payload is ignored")
    void stoppedAndMangled() {
        arena.listen();
        arena.stop();
        smp.announce(new StreamEvent.WentLive(stream));
        hub.drain();
        assertThat(arenaShown).isEmpty();
        assertThat(Announcer.decode("not: [valid")).isEmpty();
        assertThat(Announcer.decode("title: no kind")).isEmpty();
    }
}
