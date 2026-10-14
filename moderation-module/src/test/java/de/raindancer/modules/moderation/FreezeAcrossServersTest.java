package de.raindancer.modules.moderation;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.moderation.punishment.PunishmentGuard;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.moderation.punishment.Punishments;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A frozen player stays frozen when they change server. The frozen set is Core's {@code Punishments} — the network's
 * one table, which {@code /freeze} and {@code /unfreeze} go through — and each server's guard asks it on every
 * build and on every join, so there is no second, module-side copy to keep in step.
 */
@Tag("database")
class FreezeAcrossServersTest {

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID MOD = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path folder;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<Database> databases = new ArrayList<>();
    private Punishments smp;
    private Punishments arena;
    private PunishmentGuard arenaGuard;

    private Punishments server(String id) {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        Punishments store = new Punishments(database, System::currentTimeMillis);
        store.load();
        store.connect(hub.bus(id));
        return store;
    }

    @BeforeEach
    void start() {
        smp = server("smp");
        arena = server("arena");
        arenaGuard = new PunishmentGuard(arena, System::currentTimeMillis);
        arenaGuard.enforce(PunishmentKind.FREEZE, true);
    }

    @AfterEach
    void stop() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("frozen on one server, a player cannot build on the next, and unfreezing anywhere lets them again")
    void followsThePlayer() {
        smp.punish(STEVE, PunishmentKind.FREEZE, MOD, "talk to a mod first", null);
        smp.flush();
        hub.drain();

        assertThat(arenaGuard.mayBuild(STEVE)).isFalse();

        assertThat(arena.lift(STEVE, PunishmentKind.FREEZE, MOD, "talked")).isTrue();
        arena.flush();
        hub.drain();

        assertThat(smp.isActive(STEVE, PunishmentKind.FREEZE)).isFalse();
        assertThat(arenaGuard.mayBuild(STEVE)).isTrue();
    }

    @Test
    @DisplayName("a player who arrives after the freeze was written is frozen on arrival, event or not")
    void appliedOnJoin() {
        smp.punish(STEVE, PunishmentKind.FREEZE, MOD, "x", null);
        smp.flush();

        arenaGuard.readFromDatabase(STEVE);

        assertThat(arenaGuard.mayBuild(STEVE)).isFalse();
    }
}
