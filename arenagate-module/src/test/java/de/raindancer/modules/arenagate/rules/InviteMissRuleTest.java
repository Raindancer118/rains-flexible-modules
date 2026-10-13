package de.raindancer.modules.arenagate.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InviteMissRuleTest {

    private final InviteMissRule rule = new InviteMissRule();

    @Test
    @DisplayName("somebody on another server of the network is not 'nobody': the line says where they are")
    void elsewhere() {
        assertThat(rule.keyFor(Optional.of("OneBlock"))).isEqualTo("arenagate.invite.elsewhere");
        assertThat(rule.keyFor(Optional.empty())).isEqualTo("arenagate.invite.unknown");
    }
}
