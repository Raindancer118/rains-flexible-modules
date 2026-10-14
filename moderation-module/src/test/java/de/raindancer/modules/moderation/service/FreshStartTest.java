package de.raindancer.modules.moderation.service;

import de.raindancer.modules.moderation.model.CheckType;
import de.raindancer.modules.moderation.model.Evidence;
import de.raindancer.modules.moderation.model.PlayerTrack;
import de.raindancer.modules.moderation.rules.ActionRule;
import de.raindancer.modules.moderation.store.EvidenceLog;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.moderation.store.AntiCheatDatabase;
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

    @Test
    @DisplayName("a fresh start clears levels and evidence, also on disk, and leaves everybody else alone")
    void wipesOnePlayer() {
        EvidenceLog evidence = evidenceLog();
        ViolationService violations = new ViolationService(new ActionRule(), null, null, evidence);
        PlayerTrack track = new PlayerTrack(cheater, "Cheater", System::currentTimeMillis);
        track.violations().add(CheckType.GRIM_MOVEMENT, 7);
        evidence.add(cheater, "Cheater", evidence("fly"));
        evidence.add(bystander, "Bystander", evidence("reach"));
        assertThat(evidence.flush()).isTrue();

        violations.freshStart(cheater, track);

        assertThat(track.violations().total()).isZero();
        assertThat(evidence.of(cheater)).isEmpty();
        assertThat(evidence.flush()).isTrue();

        EvidenceLog reread = evidenceLog();
        reread.load();
        assertThat(reread.of(cheater)).as("cleared evidence must not come back after a restart").isEmpty();
        assertThat(reread.of(bystander)).hasSize(1);
    }

    @Test
    @DisplayName("a player who is offline gets a fresh start too: there is no track, only what was written down")
    void offline() {
        EvidenceLog evidence = evidenceLog();
        ViolationService violations = new ViolationService(new ActionRule(), null, null, evidence);
        evidence.add(cheater, "Cheater", evidence("fly"));

        violations.freshStart(cheater, null);

        assertThat(evidence.of(cheater)).isEmpty();
    }

    @Test
    @DisplayName("measuring afresh: levels start over, evidence is kept")
    void measureAfresh() {
        EvidenceLog evidence = evidenceLog();
        ViolationService violations = new ViolationService(new ActionRule(), null, null, evidence);
        long[] now = {5_000};
        PlayerTrack track = new PlayerTrack(cheater, "Cheater", () -> now[0]);
        track.violations().add(CheckType.GRIM_MOVEMENT, 7);
        evidence.add(cheater, "Cheater", evidence("fly"));

        violations.measureAfresh(cheater, track, 4_000);

        assertThat(track.violations().total()).isZero();
        assertThat(evidence.of(cheater)).as("the history stays").hasSize(1);
        assertThat(evidence.since(cheater)).as("but nothing counts from before").isEmpty();
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
