package de.raindancer.modules.arena.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.arena.model.Kit;
import de.raindancer.modules.arena.model.Stats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("database")
class KitsAndStatsTest {

    @TempDir
    Path folder;

    private static Kit kit(String id) {
        List<String> slots = new ArrayList<>(Collections.nCopies(Kit.SLOTS, ""));
        slots.set(0, "sword-bytes");
        slots.set(38, "chestplate-bytes");
        return new Kit(id, "The " + id, "IRON_SWORD", slots, List.of("minecraft:speed 600 0"), false);
    }

    @Test
    @DisplayName("kits come back as they were written, in order; a default never overwrites the owner's own version")
    void kitsRoundTrip() {
        KitBook book = new KitBook(folder.resolve("kits.yml"));
        book.put(kit("gladiator"));
        book.put(kit("archer"));
        Kit mine = new Kit("gladiator", "My gladiator", "GOLDEN_SWORD", List.of(), List.of(), false);
        book.put(mine);

        KitBook reread = new KitBook(folder.resolve("kits.yml"));

        assertThat(reread.all()).extracting(Kit::id).containsExactly("gladiator", "archer");
        assertThat(reread.get("GLADIATOR")).contains(mine);
        assertThat(reread.get("archer").orElseThrow().items().get(38)).isEqualTo("chestplate-bytes");
        reread.putIfAbsent(kit("gladiator"));
        assertThat(reread.get("gladiator").orElseThrow().name()).isEqualTo("My gladiator");
        assertThat(reread.remove("archer")).isTrue();
        assertThat(new KitBook(folder.resolve("kits.yml")).has("archer")).isFalse();
    }

    @Test
    @DisplayName("'own' is not a kit name — it means fighting with your own gear")
    void ownIsReserved() {
        assertThatThrownBy(() -> new Kit("own", "x", null, List.of(), List.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("stats add up, the best wave is the best ever, and the leaderboard is in order")
    void stats() {
        Database database = TestDatabases.open(folder.resolve("arena.db"), StatsDatabase.SCHEMA);
        try {
            StatsStore stats = new StatsStore(database);
            UUID ada = UUID.randomUUID();
            UUID bo = UUID.randomUUID();
            stats.add(ada, "Ada", 1, 0, 0, 2, 0, 0);
            stats.add(ada, "Ada", 1, 0, 0, 1, 1, 7);
            stats.add(ada, "Ada", 0, 1, 0, 0, 1, 3);
            stats.add(bo, "Bo", 3, 0, 0, 5, 0, 12);

            assertThat(stats.of(ada)).contains(new Stats(ada, "Ada", 2, 1, 0, 3, 2, 7));
            assertThat(stats.top(false, 10)).extracting(Stats::name).containsExactly("Bo", "Ada");
            assertThat(stats.top(true, 1)).extracting(Stats::name).containsExactly("Bo");
            assertThat(stats.of(UUID.randomUUID())).isEmpty();
        } finally {
            database.close();
        }
    }

    @Test
    @DisplayName("fights ending at once all count — no result is lost to another written at the same moment")
    void concurrentAdds() throws Exception {
        Database database = TestDatabases.open(folder.resolve("arena.db"), StatsDatabase.SCHEMA);
        try {
            StatsStore stats = new StatsStore(database);
            UUID ada = UUID.randomUUID();
            ExecutorService threads = Executors.newFixedThreadPool(8);
            for (int i = 0; i < 200; i++) {
                threads.submit(() -> stats.add(ada, "Ada", 1, 0, 0, 1, 0, 0));
            }
            threads.shutdown();
            assertThat(threads.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            assertThat(stats.of(ada).orElseThrow().wins()).isEqualTo(200);
        } finally {
            database.close();
        }
    }
}
