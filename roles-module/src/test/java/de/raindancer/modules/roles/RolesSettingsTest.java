package de.raindancer.modules.roles;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RolesSettingsTest {

    @Test
    @DisplayName("every default is spelled out; selling roles is off, so a server behaves as it did before prices")
    void defaults() {
        RolesSettings d = RolesSettings.DEFAULTS;
        assertThat(d.perks()).isTrue();
        assertThat(d.changeEveryHours()).isEqualTo(72);
        assertThat(d.announce()).isTrue();
        assertThat(d.remindOnJoin()).isTrue();
        assertThat(d.startShare()).isEqualTo(40);
        assertThat(d.fullAfterDays()).isEqualTo(14);
        assertThat(d.sellRoles()).isFalse();
    }
}
