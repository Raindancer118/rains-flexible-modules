package de.raindancer.modules.invsnap.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InsuredStoreTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("nobody is insured until they opt in")
    void emptyByDefault() {
        assertThat(new InsuredStore(folder).isInsured(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("opting in survives a restart, opting out too")
    void persists() {
        UUID player = UUID.randomUUID();
        new InsuredStore(folder).set(player, true);

        assertThat(new InsuredStore(folder).isInsured(player)).isTrue();

        new InsuredStore(folder).set(player, false);
        assertThat(new InsuredStore(folder).isInsured(player)).isFalse();
    }
}
