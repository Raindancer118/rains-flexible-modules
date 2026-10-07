package de.raindancer.modules.voicebridge.util;

import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermissionNodesTest {

    @Test
    @DisplayName("SVC's nodes are asked exactly as SVC asks them: as a permission defaulting to everybody")
    void asksLikeSvc() {
        Permissible who = mock(Permissible.class);
        when(who.hasPermission(any(Permission.class))).thenReturn(false);

        assertThat(PermissionNodes.svc(who, "voicechat.groups")).isFalse();

        ArgumentCaptor<Permission> asked = ArgumentCaptor.forClass(Permission.class);
        verify(who).hasPermission(asked.capture());
        assertThat(asked.getValue().getName()).isEqualTo("voicechat.groups");
        assertThat(asked.getValue().getDefault()).isEqualTo(PermissionDefault.TRUE);
    }
}
