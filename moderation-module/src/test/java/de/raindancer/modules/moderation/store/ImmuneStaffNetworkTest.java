package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.Documents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class ImmuneStaffNetworkTest {

    @TempDir
    Path plugins;

    private TwoServers servers;
    private final UUID owner = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    private ImmuneStaff on(String id) {
        Documents documents = servers.server(id);
        ImmuneStaff immune = new ImmuneStaff(servers.network(documents, "immune.yml"));
        immune.load();
        return immune;
    }

    @org.junit.jupiter.api.BeforeEach
    void start() {
        servers = new TwoServers(plugins);
    }

    @AfterEach
    void stop() {
        servers.close();
    }

    @Test
    @DisplayName("an account protected on one server is protected on the other, and so is lifting it")
    void protectionIsTheNetworks() {
        ImmuneStaff smp = on("smp");
        ImmuneStaff arena = on("arena");

        smp.protect(owner);
        assertThat(smp.flush()).isTrue();
        servers.settle();
        assertThat(arena.isImmune(owner)).isTrue();

        arena.unprotect(owner);
        arena.flush();
        servers.settle();
        assertThat(smp.isImmune(owner)).isFalse();
    }

    @Test
    @DisplayName("what this server changed and has not written yet is not lost when another server's change arrives")
    void unwrittenChangeSurvivesTheReload() {
        ImmuneStaff smp = on("smp");
        ImmuneStaff arena = on("arena");

        smp.protect(owner);
        arena.protect(admin);
        arena.flush();
        servers.settle();
        servers.settle();

        assertThat(smp.isImmune(admin)).isTrue();
        assertThat(smp.isImmune(owner)).isTrue();
        assertThat(arena.isImmune(owner)).as("written by the reload on smp before it read").isTrue();
    }

    @Test
    @DisplayName("the 1.x list is read, and is turned into one entry per account on the next write")
    void readsTheOldList() {
        servers.network(servers.server("smp"), "immune.yml").write(yaml ->
                yaml.set("immune", java.util.List.of(owner.toString())));
        ImmuneStaff arena = on("arena");
        assertThat(arena.isImmune(owner)).isTrue();

        arena.protect(admin);
        arena.flush();

        assertThat(on("third").all()).containsExactlyInAnyOrder(owner, admin);
    }

    @Test
    @DisplayName("a list that cannot be read leaves the protected accounts protected rather than emptying it")
    void unreadableKeepsWhatIsHeld() throws Exception {
        ImmuneStaff immune = new ImmuneStaff(servers.network(servers.brokenServer("smp"), "immune.yml"));
        immune.protect(owner);

        immune.load();

        assertThat(immune.isImmune(owner)).isTrue();
        assertThat(immune.flush()).isFalse();
    }
}
