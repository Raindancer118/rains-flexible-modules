package de.raindancer.modules.cosmetics.store;

import de.raindancer.core.data.store.YamlStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UnlockBookTest {

    @Test
    @DisplayName("what a player bought is kept on disk and read back")
    void persists(@TempDir Path folder) {
        Path file = folder.resolve("unlocks.yml");
        UUID tom = UUID.randomUUID();
        UnlockBook book = new UnlockBook(new YamlStore(file));
        book.load();
        assertThat(book.add(tom, "name.gradient")).isTrue();
        assertThat(book.add(tom, "preset.sunset")).isTrue();
        assertThat(book.add(tom, "preset.sunset")).as("twice is once").isTrue();

        UnlockBook again = new UnlockBook(new YamlStore(file));
        again.load();
        assertThat(again.has(tom, "name.gradient")).isTrue();
        assertThat(again.of(tom)).containsExactlyInAnyOrder("name.gradient", "preset.sunset");
        assertThat(again.has(UUID.randomUUID(), "name.gradient")).isFalse();
    }

    @Test
    @DisplayName("a file that cannot be read is never written over")
    void unreadable(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("unlocks.yml");
        Files.writeString(file, "unlocks: [ this: is: not yaml");
        UnlockBook book = new UnlockBook(new YamlStore(file));
        book.load();

        assertThat(book.readable()).isFalse();
        assertThat(book.add(UUID.randomUUID(), "particles")).isFalse();
        assertThat(Files.readString(file)).contains("this: is: not yaml");
    }
}
