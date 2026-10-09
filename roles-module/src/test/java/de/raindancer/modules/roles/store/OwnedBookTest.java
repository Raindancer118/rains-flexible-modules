package de.raindancer.modules.roles.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.roles.model.Ownership;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OwnedBookTest {

    @Test
    @DisplayName("what a player bought or rents is kept on disk, with the due date, and read back")
    void persists(@TempDir Path folder) {
        Path file = folder.resolve("owned.yml");
        UUID tom = UUID.randomUUID();
        OwnedBook book = new OwnedBook(new YamlStore(file));
        book.load();
        assertThat(book.put(new Ownership(tom, "cook", Ownership.Kind.BOUGHT, 0))).isTrue();
        assertThat(book.put(new Ownership(tom, "mage", Ownership.Kind.RENTED, 777))).isTrue();

        OwnedBook again = new OwnedBook(new YamlStore(file));
        again.load();
        assertThat(again.of(tom, "cook")).contains(new Ownership(tom, "cook", Ownership.Kind.BOUGHT, 0));
        assertThat(again.of(tom, "mage")).contains(new Ownership(tom, "mage", Ownership.Kind.RENTED, 777));
        assertThat(again.of(tom)).hasSize(2);
        assertThat(again.remove(tom, "mage")).isTrue();

        OwnedBook third = new OwnedBook(new YamlStore(file));
        third.load();
        assertThat(third.of(tom, "mage")).isEmpty();
        assertThat(third.of(tom, "cook")).isPresent();
    }

    @Test
    @DisplayName("a file that cannot be read is never written over")
    void unreadable(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("owned.yml");
        Files.writeString(file, "owned: [ this: is: not yaml");
        OwnedBook book = new OwnedBook(new YamlStore(file));
        book.load();

        assertThat(book.readable()).isFalse();
        assertThat(book.put(new Ownership(UUID.randomUUID(), "cook", Ownership.Kind.BOUGHT, 0))).isFalse();
        assertThat(Files.readString(file)).contains("this: is: not yaml");
    }
}
