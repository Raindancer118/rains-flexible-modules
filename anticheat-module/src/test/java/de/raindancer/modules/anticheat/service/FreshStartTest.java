package de.raindancer.modules.anticheat.service;

import de.raindancer.modules.anticheat.model.CheckType;
import de.raindancer.modules.anticheat.model.Evidence;
import de.raindancer.modules.anticheat.model.PlayerTrack;
import de.raindancer.modules.anticheat.model.Replays;
import de.raindancer.modules.anticheat.rules.ActionRule;
import de.raindancer.modules.anticheat.store.EvidenceLog;
import de.raindancer.modules.anticheat.store.ReplayStore;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.anticheat.store.AntiCheatDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class FreshStartTest {

    @TempDir
    Path folder;

    private Database database;

    private EvidenceLog evidenceLog() {
        if (database == null) {
            database = TestDatabases.open(folder.resolve("anticheat.db"), AntiCheatDatabase.SCHEMA);
        }
        return new EvidenceLog(database, null, 50);
    }

    @AfterEach
    void close() {
        if (database != null) {
            database.close();
        }
    }

    private final UUID cheater = UUID.randomUUID();
    private final UUID bystander = UUID.randomUUID();

    private static Evidence evidence(String check) {
        return new Evidence(1000L, check, 3.0, "detail", "world", 1, 64, 2, 40, 20.0);
    }

    private static Replays.Replay replay() {
        return new Replays.Replay(1000L, "fly", "detail", List.of());
    }

    @Test
    @DisplayName("a fresh start clears levels, evidence and replays, also on disk, and leaves everybody else alone")
    void wipesOnePlayer() {
        EvidenceLog evidence = evidenceLog();
        ReplayStore replays = new ReplayStore(folder, new Replays(5));
        ViolationService violations = new ViolationService(new ActionRule(), null, null, evidence);
        violations.replaysTo(replays);
        PlayerTrack track = new PlayerTrack(cheater, "Cheater", System::currentTimeMillis);
        track.violations().add(CheckType.FLY, 7);
        evidence.add(cheater, "Cheater", evidence("fly"));
        evidence.add(bystander, "Bystander", evidence("reach"));
        replays.add(cheater, replay());
        replays.add(bystander, replay());
        assertThat(evidence.flush()).isTrue();
        assertThat(replays.flush()).isTrue();

        violations.freshStart(cheater, track);

        assertThat(track.violations().total()).isZero();
        assertThat(evidence.of(cheater)).isEmpty();
        assertThat(replays.replays().of(cheater)).isEmpty();
        assertThat(evidence.flush()).isTrue();
        assertThat(replays.flush()).isTrue();

        EvidenceLog reread = evidenceLog();
        reread.load();
        ReplayStore rereadReplays = new ReplayStore(folder, new Replays(5));
        rereadReplays.load();
        assertThat(reread.of(cheater)).as("cleared evidence must not come back after a restart").isEmpty();
        assertThat(rereadReplays.replays().of(cheater)).as("cleared replays must not come back after a restart").isEmpty();
        assertThat(reread.of(bystander)).hasSize(1);
        assertThat(rereadReplays.replays().of(bystander)).hasSize(1);
    }

    @Test
    @DisplayName("a player who is offline gets a fresh start too: there is no track, only what was written down")
    void offline() {
        EvidenceLog evidence = evidenceLog();
        ReplayStore replays = new ReplayStore(folder, new Replays(5));
        ViolationService violations = new ViolationService(new ActionRule(), null, null, evidence);
        violations.replaysTo(replays);
        evidence.add(cheater, "Cheater", evidence("fly"));
        replays.add(cheater, replay());

        violations.freshStart(cheater, null);

        assertThat(evidence.of(cheater)).isEmpty();
        assertThat(replays.replays().of(cheater)).isEmpty();
    }

    @Test
    @DisplayName("measuring afresh: levels, buffers and click statistics start over, evidence and replays are kept")
    void measureAfresh() {
        EvidenceLog evidence = evidenceLog();
        ReplayStore replays = new ReplayStore(folder, new Replays(5));
        ViolationService violations = new ViolationService(new ActionRule(), null, null, evidence);
        violations.replaysTo(replays);
        long[] now = {5_000};
        PlayerTrack track = new PlayerTrack(cheater, "Cheater", () -> now[0]);
        track.violations().add(CheckType.FLY, 7);
        track.buffer(CheckType.SPEED, 2, 0.04).fail(1);
        track.combat.clickIntervals.add(80);
        evidence.add(cheater, "Cheater", evidence("fly"));
        replays.add(cheater, replay());

        violations.measureAfresh(cheater, track, 4_000);

        assertThat(track.violations().total()).isZero();
        assertThat(track.buffer(CheckType.SPEED, 2, 0.04).value()).as("a fresh buffer").isZero();
        assertThat(track.combat.clickIntervals.toArray()).isEmpty();
        assertThat(evidence.of(cheater)).as("the history stays").hasSize(1);
        assertThat(evidence.since(cheater)).as("but nothing counts from before").isEmpty();
        assertThat(replays.replays().of(cheater)).hasSize(1);
        assertThat(evidence.measuredFrom(cheater)).isEqualTo(4_000);
        assertThat(evidence.flush()).isTrue();

        EvidenceLog reread = evidenceLog();
        reread.load();
        assertThat(reread.measuredFrom(cheater)).as("kept across a restart").isEqualTo(4_000);
        assertThat(reread.of(cheater)).hasSize(1);

        evidence.add(cheater, "Cheater", new Evidence(4_500L, "reach", 2.0, "detail", "world", 1, 64, 2, 40, 20.0));
        assertThat(evidence.since(cheater)).extracting(Evidence::check).containsExactly("reach");
    }
}
