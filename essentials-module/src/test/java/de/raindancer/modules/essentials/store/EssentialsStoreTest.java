package de.raindancer.modules.essentials.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EssentialsStoreTest {

    @Nested
    @DisplayName("nicknames")
    class Nicknames {

        @Test
        @DisplayName("survive being written and reloaded")
        void surviveAReload(@TempDir Path folder) {
            UUID who = UUID.randomUUID();
            EssentialsStore first = new EssentialsStore(folder);
            first.load();
            first.setNickname(who, "<red>Tom</red>");
            assertThat(first.flush()).isTrue();

            EssentialsStore reloaded = new EssentialsStore(folder);
            reloaded.load();

            assertThat(reloaded.nicknameOf(who)).contains("<red>Tom</red>");
        }

        @Test
        @DisplayName("clearing one removes it, and that survives a reload too")
        void clearingSurvives(@TempDir Path folder) {
            UUID who = UUID.randomUUID();
            EssentialsStore first = new EssentialsStore(folder);
            first.load();
            first.setNickname(who, "Foxy");
            first.flush();
            first.clearNickname(who);
            first.flush();

            EssentialsStore reloaded = new EssentialsStore(folder);
            reloaded.load();

            assertThat(reloaded.nicknameOf(who)).isEmpty();
        }
    }

    @Nested
    @DisplayName("ignore lists")
    class IgnoreLists {

        @Test
        @DisplayName("survive being written and reloaded")
        void surviveAReload(@TempDir Path folder) {
            UUID who = UUID.randomUUID();
            UUID ignored = UUID.randomUUID();
            EssentialsStore first = new EssentialsStore(folder);
            first.load();
            first.ignore(who, ignored);
            assertThat(first.flush()).isTrue();

            EssentialsStore reloaded = new EssentialsStore(folder);
            reloaded.load();

            assertThat(reloaded.isIgnoring(who, ignored)).isTrue();
        }

        @Test
        @DisplayName("nobody ignores themselves — the attempt changes nothing")
        void cannotIgnoreYourself() {
            UUID who = UUID.randomUUID();
            EssentialsStore store = new EssentialsStore(Path.of("target", "unused"));

            assertThat(store.ignore(who, who)).isFalse();
        }
    }

    @Test
    @DisplayName("a fresh store, never loaded from anything, starts empty")
    void freshStoreIsEmpty(@TempDir Path folder) {
        EssentialsStore store = new EssentialsStore(folder);
        store.load();

        assertThat(store.nicknameCount()).isZero();
        assertThat(store.ignoredBy(UUID.randomUUID())).isEmpty();
    }
}
