package de.raindancer.modules.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FreezeServiceTest {

    private final FreezeService service = new FreezeService();

    @Test
    @DisplayName("starts unfrozen")
    void startsUnfrozen() {
        assertThat(service.isFrozen()).isFalse();
    }

    @Test
    @DisplayName("freezing then unfreezing leaves it unfrozen, and each step reports the change")
    void freezeThenUnfreeze() {
        assertThat(service.freeze()).isTrue();
        assertThat(service.isFrozen()).isTrue();

        assertThat(service.unfreeze()).isTrue();
        assertThat(service.isFrozen()).isFalse();
    }

    @Test
    @DisplayName("freezing twice changes nothing the second time")
    void freezingTwiceIsNotAChange() {
        assertThat(service.freeze()).isTrue();
        assertThat(service.freeze()).isFalse();
    }

    @Test
    @DisplayName("unfreezing when already unfrozen changes nothing")
    void unfreezingWhenAlreadyUnfrozenIsNotAChange() {
        assertThat(service.unfreeze()).isFalse();
    }
}
