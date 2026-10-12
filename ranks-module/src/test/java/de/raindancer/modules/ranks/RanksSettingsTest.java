package de.raindancer.modules.ranks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RanksSettingsTest {

    @Test
    @DisplayName("out of the box a rank shows everywhere, players may choose, and the tablist sorts by it")
    void defaults() {
        RanksSettings d = RanksSettings.DEFAULTS;
        assertThat(d.chat()).isTrue();
        assertThat(d.nametag()).isTrue();
        assertThat(d.tablist()).isTrue();
        assertThat(d.playersChoose()).isTrue();
        assertThat(d.sortTablist()).isTrue();
    }
}
