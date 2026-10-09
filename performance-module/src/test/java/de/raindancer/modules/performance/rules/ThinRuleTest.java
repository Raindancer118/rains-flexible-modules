package de.raindancer.modules.performance.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Which animals go when a crowd is thinned. */
class ThinRuleTest {

    private final ThinRule rule = new ThinRule();

    private static ThinRule.Animal adult(int n) {
        return new ThinRule.Animal(new UUID(0, n), false, false);
    }

    private static ThinRule.Animal baby(int n) {
        return new ThinRule.Animal(new UUID(0, n), true, false);
    }

    private static ThinRule.Animal kept(int n) {
        return new ThinRule.Animal(new UUID(0, n), false, true);
    }

    @Test
    @DisplayName("down to the number asked for, babies first so the breeding stock stays")
    void babiesFirst() {
        List<ThinRule.Animal> animals = new ArrayList<>(List.of(adult(1), baby(2), adult(3), baby(4), adult(5)));

        List<UUID> gone = rule.toRemove(animals, 2);

        assertThat(gone).hasSize(3).contains(new UUID(0, 2), new UUID(0, 4));
    }

    @Test
    @DisplayName("named, tamed or leashed animals are never removed, and count towards those kept")
    void protectedStay() {
        List<ThinRule.Animal> animals = List.of(kept(1), kept(2), adult(3), adult(4));

        List<UUID> gone = rule.toRemove(animals, 3);

        assertThat(gone).containsExactly(new UUID(0, 3));
    }

    @Test
    @DisplayName("when only protected animals are over the number, nothing is removed")
    void onlyProtected() {
        assertThat(rule.toRemove(List.of(kept(1), kept(2), kept(3)), 1)).isEmpty();
    }

    @Test
    @DisplayName("already at or under the number, nothing is removed")
    void underLimit() {
        assertThat(rule.toRemove(List.of(adult(1), adult(2)), 5)).isEmpty();
    }
}
