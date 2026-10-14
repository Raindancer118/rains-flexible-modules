package de.raindancer.modules.twitch.service;

import de.raindancer.core.platform.link.LinkGuest;
import de.raindancer.core.platform.link.LinkHost;
import de.raindancer.core.platform.link.ServerLink;
import de.raindancer.modules.twitch.TwitchSettings;
import de.raindancer.modules.twitch.model.StreamEvent;
import de.raindancer.modules.twitch.model.StreamInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The announcement really crosses a link: the watching server tells itself and every other server it is linked to. */
class AnnouncerLinkTest {

    private static final String SECRET = "a-shared-secret-of-reasonable-length";
    private final List<ServerLink> open = new ArrayList<>();

    @AfterEach
    void closeAll() {
        open.forEach(ServerLink::close);
    }

    private static Announcer announcer(ServerLink link, List<Map<String, String>> shown) {
        return new Announcer(null, null, null, null, null, () -> List.of(link),
                () -> TwitchSettings.DEFAULTS, shown::add);
    }

    private static void eventually(Runnable check) throws InterruptedException {
        AssertionError last = null;
        for (int tries = 0; tries < 60; tries++) {
            try {
                check.run();
                return;
            } catch (AssertionError notYet) {
                last = notYet;
                Thread.sleep(50);
            }
        }
        throw last;
    }

    @Test
    @DisplayName("going live and the end are shown here and arrive on the linked server, with what the banner needs")
    void crossesTheLink() throws Exception {
        LinkHost host = LinkHost.listen("127.0.0.1", 0, SECRET, Duration.ofSeconds(2));
        open.add(host);
        LinkGuest guest = LinkGuest.connect(URI.create("http://127.0.0.1:" + host.port()), SECRET,
                Duration.ofSeconds(2), Duration.ofMillis(50));
        open.add(guest);
        List<Map<String, String>> here = new CopyOnWriteArrayList<>();
        List<Map<String, String>> there = new CopyOnWriteArrayList<>();
        Announcer watching = announcer(guest, here);
        Announcer listening = announcer(host, there);
        listening.listen();
        StreamInfo stream = new StreamInfo("1", "Lillyyxoxo_", "Building the castle", "Minecraft", Instant.now(), 5);

        watching.announce(new StreamEvent.WentLive(stream));
        watching.announce(new StreamEvent.Ended(stream, Duration.ofMinutes(125)));

        assertThat(here).hasSize(2);
        eventually(() -> assertThat(there).hasSize(2));
        // Two messages sent back to back may overtake each other; announcements are minutes apart, so order is not asked.
        Map<String, String> live = there.stream().filter(each -> "live".equals(each.get("kind"))).findFirst().orElseThrow();
        Map<String, String> ended = there.stream().filter(each -> "ended".equals(each.get("kind"))).findFirst().orElseThrow();
        assertThat(live).containsEntry("title", "Building the castle").containsEntry("game", "Minecraft")
                .containsEntry("name", "Lillyyxoxo_");
        assertThat(ended).containsEntry("lasted", "2 h 5 min");
    }

    @Test
    @DisplayName("stopping stops listening")
    void stops() throws Exception {
        LinkHost host = LinkHost.listen("127.0.0.1", 0, SECRET, Duration.ofSeconds(2));
        open.add(host);
        LinkGuest guest = LinkGuest.connect(URI.create("http://127.0.0.1:" + host.port()), SECRET,
                Duration.ofSeconds(2), Duration.ofMillis(50));
        open.add(guest);
        List<Map<String, String>> there = new CopyOnWriteArrayList<>();
        Announcer listening = announcer(host, there);
        listening.listen();
        listening.stop();

        announcer(guest, new CopyOnWriteArrayList<>()).announce(new StreamEvent.WentLive(
                new StreamInfo("1", "x", "t", "g", Instant.now(), 1)));
        Thread.sleep(400);

        assertThat(there).isEmpty();
    }
}
