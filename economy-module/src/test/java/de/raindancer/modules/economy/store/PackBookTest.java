package de.raindancer.modules.economy.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Pack;
import de.raindancer.modules.economy.model.PackItem;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PackBookTest {

    private static Optional<Money> money(String written) {
        try {
            return Optional.of(Money.of(Long.parseLong(written.strip()) * 100));
        } catch (NumberFormatException no) {
            return Optional.empty();
        }
    }

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    @Test
    @DisplayName("packs are read in the order written, with contents, price, once and description")
    void reads() throws Exception {
        List<Pack> packs = PackBook.parse(yaml("""
                packs:
                  explorer:
                    title: Explorer's Pack
                    icon: compass
                    description: [ "For a long trip." ]
                    contents:
                      torch: 32
                      cooked_beef: 16
                  starter:
                    title: Starter Pack
                    once: true
                    price: 50
                    contents: { bread: 8 }
                """), PackBookTest::money);
        assertThat(packs).extracting(Pack::id).containsExactly("explorer", "starter");
        Pack explorer = packs.getFirst();
        assertThat(explorer.contents()).containsExactly(new PackItem("TORCH", 32), new PackItem("COOKED_BEEF", 16));
        assertThat(explorer.icon()).isEqualTo("COMPASS");
        assertThat(explorer.description()).containsExactly("For a long trip.");
        assertThat(explorer.ownPrice()).isEmpty();
        assertThat(explorer.once()).isFalse();
        assertThat(packs.get(1).ownPrice()).contains(Money.of(5000));
        assertThat(packs.get(1).once()).isTrue();
    }

    @Test
    @DisplayName("a pack without a title is named after its id; bad amounts are left out, a bad id skipped")
    void forgiving() throws Exception {
        List<Pack> packs = PackBook.parse(yaml("""
                packs:
                  miners-kit:
                    contents: { torch: lots, bread: 0, stone: 3 }
                  "Bad Id!":
                    contents: { stone: 1 }
                """), PackBookTest::money);
        assertThat(packs).hasSize(1);
        assertThat(packs.getFirst().title()).isEqualTo("Miners Kit");
        assertThat(packs.getFirst().icon()).isEqualTo("CHEST");
        assertThat(packs.getFirst().contents()).containsExactly(new PackItem("STONE", 3));
    }

    @Test
    @DisplayName("without a file the shipped packs are written out once, and an owner's file is never overwritten")
    void shipped(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("packs.yml");
        PackBook book = new PackBook(new YamlStore(file),
                () -> new ByteArrayInputStream("packs:\n  a:\n    contents: { stone: 1 }\n".getBytes(StandardCharsets.UTF_8)),
                PackBookTest::money);
        assertThat(book.reload()).isEqualTo(1);
        assertThat(Files.exists(file)).isTrue();
        Files.writeString(file, "packs:\n  b:\n    contents: { dirt: 2 }\n  c:\n    contents: { dirt: 3 }\n");
        assertThat(book.reload()).isEqualTo(2);
        assertThat(book.find("c")).isPresent();
        assertThat(book.find("a")).isEmpty();
    }

    @Test
    @DisplayName("the shipped packs.yml parses, and every pack in it holds something")
    void theShippedFile() throws Exception {
        try (var in = PackBook.class.getResourceAsStream("/de/raindancer/modules/economy/packs.yml")) {
            assertThat(in).isNotNull();
            List<Pack> packs = PackBook.parse(yaml(new String(in.readAllBytes(), StandardCharsets.UTF_8)),
                    PackBookTest::money);
            assertThat(packs).hasSizeGreaterThanOrEqualTo(6);
            assertThat(packs).allSatisfy(pack -> assertThat(pack.contents()).isNotEmpty());
            assertThat(packs).extracting(Pack::id).contains("explorer", "builder", "starter");
        }
    }
}
