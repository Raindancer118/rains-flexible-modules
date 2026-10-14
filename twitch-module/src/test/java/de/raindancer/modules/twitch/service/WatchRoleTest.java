package de.raindancer.modules.twitch.service;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.platform.network.NetworkRoles;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Two servers over one database: only one asks Twitch, and the other takes over when the lease lapses. */
@Tag("database")
class WatchRoleTest {

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<Database> databases = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final AtomicBoolean smpCan = new AtomicBoolean(true);

    private NetworkRoles roles(String id) {
        Database database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
        databases.add(database);
        return new NetworkRoles(database, id, clock::get);
    }

    private WatchRole watch(String id, AtomicBoolean can) {
        return new WatchRole(roles(id), can::get, () -> events.add(id + "+"), () -> events.add(id + "-"));
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("only one server watches; the other takes over once the holder's lease lapses, and the holder learns it lost")
    void takeover() {
        WatchRole smp = watch("smp", smpCan);
        WatchRole arena = watch("arena", new AtomicBoolean(true));
        smp.tick();
        arena.tick();
        assertThat(smp.holds()).isTrue();
        assertThat(arena.holds()).isFalse();
        assertThat(arena.holder()).contains("smp");

        clock.addAndGet(NetworkRoles.LEASE_MILLIS / 3);
        smp.tick();
        clock.addAndGet(NetworkRoles.LEASE_MILLIS / 3);
        arena.tick();
        assertThat(arena.holds()).as("a renewing holder keeps it").isFalse();

        clock.addAndGet(NetworkRoles.LEASE_MILLIS + 1);
        arena.tick();
        smp.tick();
        assertThat(arena.holds()).isTrue();
        assertThat(smp.holds()).isFalse();
        assertThat(events).containsExactly("smp+", "arena+", "smp-");
    }

    @Test
    @DisplayName("stopping releases the role at once, so the other server need not wait out the lease")
    void releasedAtShutdown() {
        WatchRole smp = watch("smp", smpCan);
        WatchRole arena = watch("arena", new AtomicBoolean(true));
        smp.tick();
        smp.stop();
        arena.tick();
        assertThat(arena.holds()).isTrue();
        assertThat(smp.holds()).isFalse();
    }

    @Test
    @DisplayName("a server that cannot watch (no login) does not hold the role, so one that can is not blocked")
    void unableServerDoesNotBlock() {
        smpCan.set(false);
        WatchRole smp = watch("smp", smpCan);
        WatchRole arena = watch("arena", new AtomicBoolean(true));
        smp.tick();
        arena.tick();
        assertThat(smp.holds()).isFalse();
        assertThat(arena.holds()).isTrue();

        smpCan.set(true);
        smp.tick();
        assertThat(smp.holds()).as("arena still holds it").isFalse();
    }

    @Test
    @DisplayName("a holder that can no longer watch gives the role up at once")
    void givesUpWhenUnable() {
        WatchRole smp = watch("smp", smpCan);
        WatchRole arena = watch("arena", new AtomicBoolean(true));
        smp.tick();
        smpCan.set(false);
        smp.tick();
        arena.tick();
        assertThat(smp.holds()).isFalse();
        assertThat(arena.holds()).isTrue();
    }

    @Test
    @DisplayName("a server of its own always watches, and refresh restarts the watch")
    void aloneAlwaysWatches() {
        List<String> alone = new ArrayList<>();
        WatchRole role = new WatchRole(null, () -> true, () -> alone.add("+"), () -> alone.add("-"));
        role.tick();
        role.tick();
        role.refresh();
        assertThat(role.holds()).isTrue();
        assertThat(alone).containsExactly("+", "+");
        assertThat(role.holder()).isEmpty();
    }
}
