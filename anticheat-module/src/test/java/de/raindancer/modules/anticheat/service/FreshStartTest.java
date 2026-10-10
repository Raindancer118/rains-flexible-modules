package de.raindancer.modules.anticheat.service;

import de.raindancer.modules.anticheat.model.CheckType;
import de.raindancer.modules.anticheat.model.Evidence;
import de.raindancer.modules.anticheat.model.PlayerTrack;
import de.raindancer.modules.anticheat.model.Replays;
import de.raindancer.modules.anticheat.rules.ActionRule;
import de.raindancer.modules.anticheat.store.EvidenceLog;
import de.raindancer.modules.anticheat.store.ReplayStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FreshStartTest {

    @TempDir
    Path folder;

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
        EvidenceLog evidence = new EvidenceLog(folder, 50);
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

        EvidenceLog reread = new EvidenceLog(folder, 50);
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
        EvidenceLog evidence = new EvidenceLog(folder, 50);
        ReplayStore replays = new ReplayStore(folder, new Replays(5));
        ViolationService violations = new ViolationService(new ActionRule(), null, null, evidence);
        violations.replaysTo(replays);
        evidence.add(cheater, "Cheater", evidence("fly"));
        replays.add(cheater, replay());

        violations.freshStart(cheater, null);

        assertThat(evidence.of(cheater)).isEmpty();
        assertThat(replays.replays().of(cheater)).isEmpty();
    }
}
