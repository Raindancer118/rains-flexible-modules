package de.raindancer.modules.roles.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.roles.model.Choice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChoiceBookTest {

    @Test
    @DisplayName("a choice is kept on disk and read back after a restart; clearing one removes it")
    void persists(@TempDir Path folder) {
        Path file = folder.resolve("choices.yml");
        UUID tom = UUID.randomUUID();
        UUID ana = UUID.randomUUID();
        ChoiceBook book = new ChoiceBook(new YamlStore(file));
        book.load();
        assertThat(book.of(tom)).isEmpty();
        assertThat(book.put(new Choice(tom, "cook", 1234L))).isTrue();
        assertThat(book.put(new Choice(ana, "mage", 99L))).isTrue();

        ChoiceBook again = new ChoiceBook(new YamlStore(file));
        again.load();
        assertThat(again.of(tom)).contains(new Choice(tom, "cook", 1234L));
        assertThat(again.of(ana)).map(Choice::role).contains("mage");
        assertThat(again.clear(ana)).isTrue();

        ChoiceBook third = new ChoiceBook(new YamlStore(file));
        third.load();
        assertThat(third.of(ana)).isEmpty();
        assertThat(third.of(tom)).isPresent();
        assertThat(third.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a line that is not a player or has no role is skipped, the rest still read")
    void forgiving(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("choices.yml");
        UUID tom = UUID.randomUUID();
        Files.writeString(file, """
                choices:
                  not-a-uuid: { role: cook, chosen-at: 1 }
                  %s: { role: builder, chosen-at: 5 }
                  %s: { chosen-at: 5 }
                """.formatted(tom, UUID.randomUUID()));
        ChoiceBook book = new ChoiceBook(new YamlStore(file));
        book.load();
        assertThat(book.count()).isEqualTo(1);
        assertThat(book.of(tom)).map(Choice::role).contains("builder");
    }

    @Test
    @DisplayName("an unreadable file is not overwritten by the next choice")
    void unreadable(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("choices.yml");
        Files.writeString(file, "choices: [ this is: : not yaml");
        ChoiceBook book = new ChoiceBook(new YamlStore(file));
        book.load();
        assertThat(book.readable()).isFalse();
        assertThat(book.put(new Choice(UUID.randomUUID(), "cook", 1))).isFalse();
        assertThat(Files.readString(file)).contains("not yaml");
    }
}
