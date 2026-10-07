package de.raindancer.modules.voicebridge.util;

import org.bukkit.permissions.Permissible;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionNodesTest {

    @Test
    @DisplayName("SVC's nodes are everybody's unless taken away — unregistered must not mean operators only")
    void svcDefaultsToEverybody() {
        Permissible ordinary = mock(Permissible.class);
        when(ordinary.isPermissionSet("voicechat.groups")).thenReturn(false);
        when(ordinary.hasPermission("voicechat.groups")).thenReturn(false);
        assertThat(PermissionNodes.svc(ordinary, "voicechat.groups")).isTrue();

        Permissible deniedByOwner = mock(Permissible.class);
        when(deniedByOwner.isPermissionSet("voicechat.groups")).thenReturn(true);
        when(deniedByOwner.hasPermission("voicechat.groups")).thenReturn(false);
        assertThat(PermissionNodes.svc(deniedByOwner, "voicechat.groups")).isFalse();
    }
}
