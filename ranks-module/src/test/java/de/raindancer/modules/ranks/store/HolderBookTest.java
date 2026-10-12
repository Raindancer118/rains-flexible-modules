package de.raindancer.modules.ranks.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.ranks.model.Holder;
import de.raindancer.modules.ranks.model.Place;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HolderBookTest {

    private static final UUID ADA = UUID.randomUUID();
    private static final UUID BO = UUID.randomUUID();

    @TempDir
    Path folder;

    private HolderBook open() {
        HolderBook book = new HolderBook(new YamlStore(folder.resolve("holders.yml")));
        book.load();
        return book;
    }

    @Test
    @DisplayName("ranks, the worn pick and every switch survive a restart")
    void roundTrip() {
        HolderBook book = open();
        assertThat(book.change(ADA, holder -> holder.withRanks(Set.of("owner", "dev")).wearing("dev")
                .showing(Place.NAMETAG, false))).isTrue();
        assertThat(book.change(BO, holder -> holder.withRanks(Set.of("helper")))).isTrue();

        HolderBook reopened = open();
        Holder ada = reopened.of(ADA);
        assertThat(ada.ranks()).containsExactlyInAnyOrder("owner", "dev");
        assertThat(ada.worn()).isEqualTo("dev");
        assertThat(ada.shows(Place.NAMETAG)).isFalse();
        assertThat(ada.shows(Place.CHAT)).isTrue();
        assertThat(reopened.of(BO).ranks()).containsExactly("helper");
    }

    @Test
    @DisplayName("somebody back to nothing is dropped from the file rather than kept as an empty line")
    void blankIsForgotten() {
        HolderBook book = open();
        book.change(ADA, holder -> holder.withRanks(Set.of("owner")));
        book.change(ADA, holder -> holder.withRanks(Set.of()));
        assertThat(book.find(ADA)).isEmpty();
        assertThat(open().all()).isEmpty();
    }

    @Test
    @DisplayName("a file that cannot be read is never written over, and changes are refused")
    void unreadable() throws Exception {
        Files.writeString(folder.resolve("holders.yml"), "holders: [this is: not: yaml");
        HolderBook book = open();
        assertThat(book.readable()).isFalse();
        assertThat(book.change(ADA, holder -> holder.withRanks(Set.of("owner")))).isFalse();
        assertThat(book.find(ADA)).isEmpty();
    }
}
