package de.raindancer.modules.moderation.util;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The switch is Core's, so it follows the player; what was switched off before that stays off. */
class AttemptAlertSwitchTest {

    private final Map<NamespacedKey, Object> data = new HashMap<>();
    private final Player staff = mock(Player.class);
    private final AttemptAlertSwitch alerts;

    AttemptAlertSwitchTest() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.namespace()).thenReturn("rainsflexiblemodules");
        PersistentDataContainer container = mock(PersistentDataContainer.class);
        when(container.has(any(NamespacedKey.class), any())).thenAnswer(call -> data.containsKey(call.<NamespacedKey>getArgument(0)));
        when(container.get(any(NamespacedKey.class), any())).thenAnswer(call -> data.get(call.<NamespacedKey>getArgument(0)));
        org.mockito.Mockito.doAnswer(call -> data.put(call.getArgument(0), call.getArgument(2)))
                .when(container).set(any(NamespacedKey.class), any(), any());
        org.mockito.Mockito.doAnswer(call -> data.remove(call.<NamespacedKey>getArgument(0)))
                .when(container).remove(any(NamespacedKey.class));
        when(staff.getPersistentDataContainer()).thenReturn(container);
        alerts = new AttemptAlertSwitch(plugin);
    }

    @Test
    @DisplayName("on unless switched off, and switched off in Core's shared namespace")
    void sharedSwitch() {
        assertThat(alerts.isOn(staff)).isTrue();
        alerts.set(staff, false);
        assertThat(alerts.isOn(staff)).isFalse();
        assertThat(data.keySet()).extracting(NamespacedKey::getNamespace).containsOnly("rainsswitches");
        alerts.set(staff, true);
        assertThat(alerts.isOn(staff)).isTrue();
    }

    @Test
    @DisplayName("an opt-out kept the old way still counts, and is cleared once the staff member chooses again")
    void oldOptOut() {
        data.put(new NamespacedKey("rainsflexiblemodules", "attempt-alerts-off"), (byte) 1);
        assertThat(alerts.isOn(staff)).isFalse();

        alerts.set(staff, true);
        assertThat(alerts.isOn(staff)).isTrue();
        assertThat(data.keySet()).extracting(NamespacedKey::getNamespace).doesNotContain("rainsflexiblemodules");
    }
}
