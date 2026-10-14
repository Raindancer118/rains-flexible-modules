package de.raindancer.modules.moderation.service;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.moderation.model.CheckType;
import de.raindancer.modules.moderation.model.PlayerTrack;
import de.raindancer.modules.moderation.rules.ActionRule;
import de.raindancer.modules.moderation.store.AntiCheatDatabase;
import de.raindancer.modules.moderation.store.EvidenceLog;
import de.raindancer.modules.moderation.store.ViolationLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** A cheater who hops servers arrives with the levels they earned, and a fresh start wipes them everywhere. */
@Tag("database")
class NetworkLevelsTest {

    @TempDir
    Path folder;

    private final AtomicLong clock = new AtomicLong(10_000_000L);
    private final List<Database> databases = new ArrayList<>();
    private final UUID cheater = UUID.randomUUID();

    private ViolationService server() {
        Database database = TestDatabases.open(folder.resolve("anticheat.db"), AntiCheatDatabase.SCHEMA);
        databases.add(database);
        ViolationService service = new ViolationService(new ActionRule(), null, null, new EvidenceLog(database, null, 50));
        service.ledgerTo(new ViolationLedger(database, clock::get));
        return service;
    }

    private PlayerTrack track() {
        return new PlayerTrack(cheater, "Cheater", clock::get);
    }

    @AfterEach
    void close() {
        databases.forEach(Database::close);
    }

    @Test
    @DisplayName("levels gathered on one server become the player's levels on the one they arrive at")
    void arrivesWithTheirLevels() {
        ViolationService smp = server();
        ViolationService arena = server();
        PlayerTrack onSmp = track();
        onSmp.violations().add(CheckType.GRIM_MOVEMENT, 6);
        smp.gatherForTest(cheater, CheckType.GRIM_MOVEMENT, 6);
        smp.departed();

        PlayerTrack onArena = track();
        arena.arrived(onArena);

        assertThat(onArena.violations().level(CheckType.GRIM_MOVEMENT)).isCloseTo(6, within(1e-6));
    }

    @Test
    @DisplayName("a fresh start wipes the levels on the network, so a hop does not bring them back")
    void freshStartWipesTheNetwork() {
        ViolationService smp = server();
        ViolationService arena = server();
        smp.gatherForTest(cheater, CheckType.GRIM_MOVEMENT, 6);
        smp.departed();

        smp.freshStart(cheater, null);
        smp.departed();

        PlayerTrack onArena = track();
        arena.arrived(onArena);
        assertThat(onArena.violations().total()).isZero();
    }

    @Test
    @DisplayName("a server with no database keeps judging on its own")
    void aloneStillWorks() {
        ViolationService alone = new ViolationService(new ActionRule(), null, null, new EvidenceLog(
                TestDatabases.open(folder.resolve("alone.db"), AntiCheatDatabase.SCHEMA), null, 50));
        alone.arrived(track());
        alone.departed();
    }
}
