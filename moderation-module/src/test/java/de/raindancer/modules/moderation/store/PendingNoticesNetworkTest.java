package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.Documents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class PendingNoticesNetworkTest {

    @TempDir
    Path plugins;

    private TwoServers servers;
    private final UUID ayla = UUID.randomUUID();

    private PendingNotices on(String id) {
        PendingNotices notices = new PendingNotices(servers.network(servers.server(id), "pending.yml"));
        notices.load();
        return notices;
    }

    @BeforeEach
    void start() {
        servers = new TwoServers(plugins);
    }

    @AfterEach
    void stop() {
        servers.close();
    }

    @Test
    @DisplayName("a notice kept on one server is told when the player logs in on the other, once")
    void toldWhereverTheyLogIn() {
        PendingNotices smp = on("smp");
        PendingNotices arena = on("arena");

        smp.keep(ayla, "moderation.mute", Map.of("reason", "spam"));
        smp.flush();
        servers.settle();

        assertThat(arena.forgetAndTake(ayla)).extracting(PendingNotices.Notice::key).containsExactly("moderation.mute");
        arena.flush();
        servers.settle();
        assertThat(smp.forgetAndTake(ayla)).as("already told on the other server").isEmpty();
    }

    @Test
    @DisplayName("delivering what one server held does not forget what another kept for the same player meanwhile")
    void deliveryForgetsOnlyWhatWasDelivered() {
        PendingNotices smp = on("smp");
        PendingNotices arena = on("arena");
        smp.keep(ayla, "first", Map.of());
        smp.flush();
        servers.settle();

        List<PendingNotices.Notice> told = arena.forgetAndTake(ayla);
        smp.keep(ayla, "second", Map.of());
        smp.flush();
        arena.flush();
        servers.settle();
        servers.settle();

        assertThat(told).extracting(PendingNotices.Notice::key).containsExactly("first");
        assertThat(smp.forgetAndTake(ayla)).extracting(PendingNotices.Notice::key).containsExactly("second");
    }

    @Test
    @DisplayName("kept but not yet written survives another server's change arriving first")
    void unwrittenSurvivesTheReload() {
        PendingNotices smp = on("smp");
        PendingNotices arena = on("arena");

        smp.keep(ayla, "mine", Map.of());
        arena.keep(UUID.randomUUID(), "theirs", Map.of());
        arena.flush();
        servers.settle();
        servers.settle();

        assertThat(smp.forgetAndTake(ayla)).extracting(PendingNotices.Notice::key).containsExactly("mine");
    }
}
