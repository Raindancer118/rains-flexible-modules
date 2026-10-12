package de.raindancer.modules.ranks.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.ranks.RanksModule;
import de.raindancer.modules.ranks.model.Rank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RankCatalogueTest {

    @TempDir
    Path folder;

    private RankCatalogue shipped() {
        return new RankCatalogue(new YamlStore(folder.resolve("ranks.yml")),
                () -> RanksModule.class.getResourceAsStream("ranks.yml"));
    }

    @Test
    @DisplayName("the shipped file is written out once and has OWNER, ADMIN and DEV, most senior first")
    void shippedRanks() {
        RankCatalogue catalogue = shipped();
        assertThat(catalogue.reload()).isGreaterThanOrEqualTo(3);
        assertThat(Files.exists(folder.resolve("ranks.yml"))).isTrue();
        assertThat(catalogue.problems()).isEmpty();
        assertThat(catalogue.all().getFirst().id()).isEqualTo("owner");
        assertThat(catalogue.find("DEV")).map(Rank::tag).contains("<aqua><bold>[DEV]</bold></aqua>");
        assertThat(catalogue.find("admin")).isPresent();
    }

    @Test
    @DisplayName("an owner's edit survives a reload, and a deleted shipped rank stays deleted")
    void ownerEdits() throws Exception {
        RankCatalogue catalogue = shipped();
        catalogue.reload();
        Path file = folder.resolve("ranks.yml");
        String text = Files.readString(file).replace("[DEV]", "[CODER]")
                .replaceAll("(?s)  builder:.*?(?=\\n  [a-z]|\\n#|\\z)", "");
        Files.writeString(file, text);

        catalogue.reload();
        assertThat(catalogue.find("dev")).map(Rank::tag).contains("<aqua><bold>[CODER]</bold></aqua>");
        assertThat(catalogue.find("builder")).isEmpty();
    }

    @Test
    @DisplayName("a bad rank is reported and left out; the others still load")
    void problems() throws Exception {
        Files.writeString(folder.resolve("ranks.yml"), """
                ranks:
                  good:
                    tag: "<green>[OK]"
                    weight: 150
                  empty:
                    tag: ""
                  long:
                    tag: "[THIS TAG IS FAR TOO LONG]"
                  button:
                    tag: "<click:run_command:'/op me'>[CLICK]</click>"
                  Bad Id:
                    tag: "[X]"
                """);
        RankCatalogue catalogue = new RankCatalogue(new YamlStore(folder.resolve("ranks.yml")),
                () -> new ByteArrayInputStream("ranks:\n".getBytes(StandardCharsets.UTF_8)));
        catalogue.reload();

        assertThat(catalogue.byId()).containsOnlyKeys("good");
        assertThat(catalogue.find("good").orElseThrow().weight()).isEqualTo(99);
        assertThat(catalogue.problems()).hasSize(5)
                .anyMatch(line -> line.startsWith("good: weight"))
                .anyMatch(line -> line.startsWith("empty:"))
                .anyMatch(line -> line.startsWith("long:"))
                .anyMatch(line -> line.startsWith("button:"));
    }
}
