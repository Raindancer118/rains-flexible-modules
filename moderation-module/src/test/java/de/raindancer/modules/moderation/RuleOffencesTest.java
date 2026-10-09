package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.store.RuleOffences;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** How often somebody broke each rule — by the rule's id, so renaming or moving a rule keeps the count. */
class RuleOffencesTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("offences are counted per player and per rule, and survive a restart")
    void counted() {
        UUID bo = UUID.randomUUID();
        RuleOffences offences = new RuleOffences(folder.resolve("rule-offences.yml"));
        offences.load();
        assertThat(offences.count(bo, "g1")).isZero();
        offences.add(bo, "g1");
        offences.add(bo, "g1");
        offences.add(bo, "k2");

        RuleOffences again = new RuleOffences(folder.resolve("rule-offences.yml"));
        again.load();
        assertThat(again.count(bo, "g1")).isEqualTo(2);
        assertThat(again.count(bo, "k2")).isEqualTo(1);
        assertThat(again.count(UUID.randomUUID(), "g1")).isZero();
    }
}
