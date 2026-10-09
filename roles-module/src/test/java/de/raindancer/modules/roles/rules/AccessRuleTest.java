package de.raindancer.modules.roles.rules;

import de.raindancer.modules.roles.model.Ownership;
import de.raindancer.modules.roles.model.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccessRuleTest {

    private final AccessRule rule = new AccessRule();
    private final UUID tom = UUID.randomUUID();

    private static Role role(String price, String rent) {
        return new Role("cook", "Cook", "BREAD", "#ffffff", List.of(), List.of(), price, rent);
    }

    @Test
    @DisplayName("a role with no price and no rent is open to everybody, as before")
    void free() {
        assertThat(rule.may(role("0", "0"), Optional.empty(), false)).isTrue();
    }

    @Test
    @DisplayName("a priced role is closed until it is bought")
    void closed() {
        assertThat(rule.may(role("500", "0"), Optional.empty(), false)).isFalse();
        assertThat(rule.may(role("0", "50"), Optional.empty(), false)).isFalse();
    }

    @Test
    @DisplayName("bought or rented, it is open")
    void owned() {
        assertThat(rule.may(role("500", "0"), Optional.of(new Ownership(tom, "cook", Ownership.Kind.BOUGHT, 0)), false)).isTrue();
        assertThat(rule.may(role("0", "50"), Optional.of(new Ownership(tom, "cook", Ownership.Kind.RENTED, 99)), false)).isTrue();
    }

    @Test
    @DisplayName("staff who skip the wait skip the price as well")
    void bypass() {
        assertThat(rule.may(role("500", "50"), Optional.empty(), true)).isTrue();
    }
}
