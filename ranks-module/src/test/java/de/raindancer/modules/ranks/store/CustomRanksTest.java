package de.raindancer.modules.ranks.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.ranks.RanksModule;
import de.raindancer.modules.ranks.model.Rank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomRanksTest {

    @TempDir
    Path folder;

    private RankCatalogue open() {
        RankCatalogue catalogue = new RankCatalogue(new YamlStore(folder.resolve("ranks.yml")),
                () -> RanksModule.class.getResourceAsStream("ranks.yml"),
                new YamlStore(folder.resolve("custom-ranks.yml")));
        catalogue.reload();
        return catalogue;
    }

    @Test
    @DisplayName("a rank made in game exists at once and after a restart, and ranks.yml is not touched")
    void created() throws Exception {
        RankCatalogue catalogue = open();
        String before = Files.readString(folder.resolve("ranks.yml"));
        assertThat(catalogue.define(new Rank("vip", "VIP", "<light_purple>[VIP]", 40, List.of()))).isTrue();
        assertThat(catalogue.find("vip")).isPresent();
        assertThat(Files.readString(folder.resolve("ranks.yml"))).isEqualTo(before);
        assertThat(open().find("vip").map(Rank::weight)).contains(40);
    }

    @Test
    @DisplayName("editing a shipped rank in game overrides it; deleting it hides it, also after a restart")
    void overrideAndDelete() {
        RankCatalogue catalogue = open();
        Rank dev = catalogue.find("dev").orElseThrow();
        catalogue.define(new Rank("dev", "Coder", "<aqua>[CODER]", dev.weight(), dev.description()));
        assertThat(open().find("dev").map(Rank::title)).contains("Coder");
        assertThat(catalogue.remove("builder")).isTrue();
        assertThat(catalogue.find("builder")).isEmpty();
        assertThat(open().find("builder")).isEmpty();
        assertThat(catalogue.remove("nothing-like-it")).isFalse();
    }

    @Test
    @DisplayName("a deleted rank can be made again under its id")
    void remade() {
        RankCatalogue catalogue = open();
        catalogue.remove("builder");
        catalogue.define(new Rank("builder", "Builder", "<yellow>[BUILD]", 50, List.of()));
        assertThat(open().find("builder").map(Rank::tag)).contains("<yellow>[BUILD]");
    }
}
