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
class RuleOffencesNetworkTest {

    @TempDir
    Path plugins;

    private TwoServers servers;
    private final UUID bo = UUID.randomUUID();

    private RuleOffences on(String id) {
        RuleOffences offences = new RuleOffences(servers.network(servers.server(id), "rule-offences.yml"));
        offences.load();
        return offences;
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
    @DisplayName("an offence counted on one server is counted on the other")
    void countedEverywhere() {
        RuleOffences smp = on("smp");
        RuleOffences arena = on("arena");

        smp.add(bo, "no-griefing");
        servers.settle();

        assertThat(arena.count(bo, "no-griefing")).isEqualTo(1);
    }

    @Test
    @DisplayName("two servers counting the same offence at once: both land, so the ladder climbs by two")
    void concurrentCounts() {
        RuleOffences smp = on("smp");
        RuleOffences arena = on("arena");

        smp.add(bo, "no-griefing");
        arena.add(bo, "no-griefing");
        servers.settle();
        servers.settle();

        assertThat(smp.count(bo, "no-griefing")).isEqualTo(2);
        assertThat(arena.count(bo, "no-griefing")).isEqualTo(2);
    }
}
