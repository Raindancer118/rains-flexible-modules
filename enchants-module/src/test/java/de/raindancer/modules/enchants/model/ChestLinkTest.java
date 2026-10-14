package de.raindancer.modules.enchants.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChestLinkTest {

    @Test
    @DisplayName("a link reads back as written; anything mangled reads as no link")
    void roundTrip() {
        ChestLink link = new ChestLink(UUID.randomUUID(), -120, 64, 33);
        assertThat(ChestLink.read(link.write())).contains(link);
        assertThat(ChestLink.read("nonsense")).isEmpty();
        assertThat(ChestLink.read("not-a-uuid;1;2;3")).isEmpty();
        assertThat(ChestLink.read(null)).isEmpty();
    }
}
