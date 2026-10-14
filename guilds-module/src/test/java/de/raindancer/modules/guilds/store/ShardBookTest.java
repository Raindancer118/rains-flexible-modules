package de.raindancer.modules.guilds.store;

import de.raindancer.core.platform.network.NetworkBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class ShardBookTest {

    private static final String TELEKINESIS = "rainsenchants:telekinesis";

    @TempDir
    Path folder;
    private TwoServers servers;

    @BeforeEach
    void open() {
        servers = new TwoServers(folder);
    }

    @AfterEach
    void close() {
        servers.close();
    }

    private ShardBook book(String server) {
        ShardBook book = new ShardBook(servers.database());
        assertThat(book.load()).isTrue();
        book.connect(servers.hub.bus(server));
        return book;
    }

    @Test
    @DisplayName("shards paid in add up; the one payment that reaches the threshold unlocks, once")
    void unlocks() {
        ShardBook book = book("smp");
        assertThat(book.progress("g1", TELEKINESIS)).isEqualTo(ShardBook.Progress.NONE);

        assertThat(book.pay("g1", TELEKINESIS, 60, 100)).hasValueSatisfying(paid -> {
            assertThat(paid.paid()).isEqualTo(60);
            assertThat(paid.justUnlocked()).isFalse();
        });
        assertThat(book.pay("g1", TELEKINESIS, 40, 100)).hasValueSatisfying(paid -> {
            assertThat(paid.paid()).isEqualTo(100);
            assertThat(paid.justUnlocked()).isTrue();
        });
        assertThat(book.unlocked("g1", TELEKINESIS)).isTrue();
        assertThat(book.unlocked("g2", TELEKINESIS)).as("another guild").isFalse();
        assertThat(book.pay("g1", TELEKINESIS, 5, 100).orElseThrow().justUnlocked()).as("not twice").isFalse();
    }

    @Test
    @DisplayName("another server of the network hears of it, and a restart reads it back")
    void network() {
        ShardBook here = book("smp");
        ShardBook there = book("lobby");
        here.pay("g1", TELEKINESIS, 100, 100);
        servers.settle();
        assertThat(there.unlocked("g1", TELEKINESIS)).isTrue();
        assertThat(there.progress("g1", TELEKINESIS).paid()).isEqualTo(100);

        ShardBook restarted = new ShardBook(servers.database());
        restarted.load();
        restarted.connect(NetworkBus.NONE);
        assertThat(restarted.unlocked("g1", TELEKINESIS)).isTrue();
    }
}
