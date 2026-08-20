package de.raindancer.modules.xaeromap.rules;

import de.raindancer.modules.xaeromap.Facts;
import de.raindancer.modules.xaeromap.model.ClaimFacts;
import de.raindancer.modules.xaeromap.model.MapAudience;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** That a claim a player may not see is never sent — the only way a claim stays private. */
class ClaimVisibilityRuleTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID TRUSTED = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();

    private static ClaimFacts theirs() {
        return Facts.claim("Theirs", OWNER, Facts.OVERWORLD, 0L, Set.of(TRUSTED), Facts.chunk(0, 0));
    }

    @Test
    @DisplayName("everybody sees everything, when that is what the server chose")
    void everybodyMeansEverybody() {
        ClaimVisibilityRule rule = new ClaimVisibilityRule(MapAudience.EVERYBODY);

        assertThat(rule.maySee(STRANGER, theirs())).isTrue();
        assertThat(rule.maySee(OWNER, theirs())).isTrue();
    }

    @Test
    @DisplayName("otherwise a player sees their own and the ones they are trusted on")
    void mineAndSharedIsExactlyThat() {
        ClaimVisibilityRule rule = new ClaimVisibilityRule(MapAudience.MINE_AND_SHARED);

        assertThat(rule.maySee(OWNER, theirs())).isTrue();
        assertThat(rule.maySee(TRUSTED, theirs())).isTrue();
        assertThat(rule.maySee(STRANGER, theirs())).isFalse();
    }

    @Test
    @DisplayName("nobody in particular sees nothing in particular")
    void unknownsAreNotOwners() {
        ClaimVisibilityRule rule = new ClaimVisibilityRule(MapAudience.MINE_AND_SHARED);

        assertThat(rule.maySee(null, theirs()))
                .as("a null viewer must not accidentally match a claim with no owner")
                .isFalse();
        assertThat(rule.maySee(OWNER, null)).isFalse();
    }

    @Test
    @DisplayName("a config naming no audience shows everything rather than nothing")
    void theDefaultIsTheGenerousOne() {
        assertThat(new ClaimVisibilityRule(null).audience()).isEqualTo(MapAudience.EVERYBODY);
    }
}
