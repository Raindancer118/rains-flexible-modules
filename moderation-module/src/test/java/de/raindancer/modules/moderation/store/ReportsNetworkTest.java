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

import de.raindancer.modules.moderation.model.Report;
import de.raindancer.modules.moderation.model.StaffNote;

import java.time.Instant;

@Tag("database")
class ReportsNetworkTest {

    @TempDir
    Path plugins;

    private TwoServers servers;
    private final UUID ayla = UUID.randomUUID();
    private final UUID bram = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-08-03T12:00:00Z");

    private record Side(ReportStorage storage, ReportRegistry registry) {
    }

    private Side reports(String id) {
        ReportStorage storage = new ReportStorage(servers.network(servers.server(id), "reports.yml"));
        ReportRegistry registry = new ReportRegistry();
        storage.bind(registry);
        storage.refresh();
        return new Side(storage, registry);
    }

    private record NoteSide(NoteStorage storage, NoteRegistry registry) {
    }

    private NoteSide notes(String id) {
        NoteStorage storage = new NoteStorage(servers.network(servers.server(id), "notes.yml"));
        NoteRegistry registry = new NoteRegistry();
        storage.bind(registry);
        storage.refresh();
        return new NoteSide(storage, registry);
    }

    private Report file(Side side, String text) {
        return side.storage().admit(id -> Report.filed(id, ayla, "Ayla", bram, "Bram", text, now));
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
    @DisplayName("a report filed on one server is in the other's queue")
    void filedEverywhere() {
        Side smp = reports("smp");
        Side arena = reports("arena");

        Report filed = file(smp, "griefing");
        servers.settle();

        assertThat(arena.registry().byId(filed.id())).contains(filed);
    }

    @Test
    @DisplayName("two servers filing at once never share an id")
    void idsAreUniqueAcrossServers() {
        Side smp = reports("smp");
        Side arena = reports("arena");

        Report first = file(smp, "one");
        Report second = file(arena, "two");

        assertThat(second.id()).isNotEqualTo(first.id());
        servers.settle();
        assertThat(smp.registry().size()).isEqualTo(2);
        assertThat(arena.registry().size()).isEqualTo(2);
    }

    @Test
    @DisplayName("closing a report on one server shows on the other; the other's own change is not undone")
    void changesMergeByReport() {
        Side smp = reports("smp");
        Side arena = reports("arena");
        Report one = file(smp, "one");
        Report two = file(arena, "two");
        servers.settle();

        smp.storage().put(smp.registry().byId(one.id()).orElseThrow().resolved(ayla, "Ayla", "done", now));
        arena.storage().put(arena.registry().byId(two.id()).orElseThrow().claimedBy(bram, "Bram"));
        smp.storage().flush();
        arena.storage().flush();
        servers.settle();
        servers.settle();

        for (Side side : List.of(smp, arena)) {
            assertThat(side.registry().byId(one.id()).orElseThrow().isClosed()).isTrue();
            assertThat(side.registry().byId(two.id()).orElseThrow().handler()).isEqualTo(bram);
        }
    }

    @Test
    @DisplayName("a change not yet written is kept when the other server's change arrives")
    void unwrittenChangeIsKept() {
        Side smp = reports("smp");
        Side arena = reports("arena");
        Report one = file(smp, "one");
        servers.settle();

        smp.storage().put(smp.registry().byId(one.id()).orElseThrow().claimedBy(ayla, "Ayla"));
        file(arena, "two");
        servers.settle();
        servers.settle();

        assertThat(arena.registry().byId(one.id()).orElseThrow().handler()).isEqualTo(ayla);
        assertThat(smp.registry().size()).isEqualTo(2);
    }

    @Test
    @DisplayName("a note written on one server is on the other, a removed one is gone there")
    void notesAreTheNetworks() {
        NoteSide smp = notes("smp");
        NoteSide arena = notes("arena");

        StaffNote written = smp.storage().admit(id -> new StaffNote(id, bram, ayla, "Ayla", "watch him", now));
        servers.settle();
        assertThat(arena.registry().byId(written.id())).contains(written);

        arena.storage().drop(written.id());
        arena.storage().flush();
        servers.settle();
        assertThat(smp.registry().byId(written.id())).isEmpty();
    }

    @Test
    @DisplayName("two servers writing a note at once never share an id")
    void noteIdsAreUnique() {
        NoteSide smp = notes("smp");
        NoteSide arena = notes("arena");

        StaffNote first = smp.storage().admit(id -> new StaffNote(id, bram, ayla, "Ayla", "one", now));
        StaffNote second = arena.storage().admit(id -> new StaffNote(id, bram, ayla, "Ayla", "two", now));

        assertThat(second.id()).isNotEqualTo(first.id());
    }
}
