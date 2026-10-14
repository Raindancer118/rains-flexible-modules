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

import de.raindancer.core.platform.permission.Grants;
import de.raindancer.modules.moderation.model.StaffRank;

@Tag("database")
class StaffRosterNetworkTest {

    @TempDir
    Path plugins;

    private TwoServers servers;
    private final UUID ayla = UUID.randomUUID();
    private final UUID bram = UUID.randomUUID();

    private StaffRoster on(String id) {
        Documents documents = servers.server(id);
        StaffRoster roster = new StaffRoster(servers.network(documents, "staff.yml"), new Grants(plugins.resolve("grants-" + id)));
        roster.load();
        return roster;
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
    @DisplayName("a promotion on one server is the rank on the other, a demotion too")
    void ranksAreTheNetworks() {
        StaffRoster smp = on("smp");
        StaffRoster arena = on("arena");

        smp.promote(ayla, StaffRank.MOD);
        smp.flush();
        servers.settle();
        assertThat(arena.rankOf(ayla)).contains(StaffRank.MOD);

        arena.demote(ayla);
        arena.flush();
        servers.settle();
        assertThat(smp.rankOf(ayla)).isEmpty();
    }

    @Test
    @DisplayName("two servers promoting different people at once: both are staff everywhere")
    void concurrentPromotions() {
        StaffRoster smp = on("smp");
        StaffRoster arena = on("arena");

        smp.promote(ayla, StaffRank.MOD);
        arena.promote(bram, StaffRank.ADMIN);
        smp.flush();
        arena.flush();
        servers.settle();
        servers.settle();

        assertThat(smp.rankOf(bram)).contains(StaffRank.ADMIN);
        assertThat(arena.rankOf(ayla)).contains(StaffRank.MOD);
        assertThat(smp.rankOf(ayla)).contains(StaffRank.MOD);
    }

    @Test
    @DisplayName("a roster that cannot be read leaves the staff as they are rather than emptying it")
    void unreadableKeepsWhatIsHeld() throws Exception {
        StaffRoster roster = new StaffRoster(servers.network(servers.brokenServer("smp"), "staff.yml"),
                new Grants(plugins.resolve("grants")));
        roster.promote(ayla, StaffRank.MOD);

        roster.load();

        assertThat(roster.rankOf(ayla)).contains(StaffRank.MOD);
        assertThat(roster.flush()).as("not written, and kept for the next try").isFalse();
    }
}
