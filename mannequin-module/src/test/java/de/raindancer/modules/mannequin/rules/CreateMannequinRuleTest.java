package de.raindancer.modules.mannequin.rules;

import de.raindancer.modules.mannequin.util.PermissionNodes;
import org.bukkit.permissions.Permissible;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateMannequinRuleTest {

    private final CreateMannequinRule rule = new CreateMannequinRule();

    @Mock
    private Permissible player;

    @Test
    void openCreationAlwaysAllowsAndNeverAsksThePlayer() {
        assertThat(rule.mayCreate(true, player)).isTrue();
        verifyNoInteractions(player);
    }

    @Test
    void closedCreationDefersToThePermission() {
        when(player.hasPermission(PermissionNodes.CREATE)).thenReturn(true);
        assertThat(rule.mayCreate(false, player)).isTrue();
    }

    @Test
    void closedCreationRefusesWithoutThePermission() {
        when(player.hasPermission(PermissionNodes.CREATE)).thenReturn(false);
        assertThat(rule.mayCreate(false, player)).isFalse();
    }

    @Test
    void closedCreationWithNoPlayerAtAllIsRefused() {
        assertThat(rule.mayCreate(false, null)).isFalse();
    }
}
