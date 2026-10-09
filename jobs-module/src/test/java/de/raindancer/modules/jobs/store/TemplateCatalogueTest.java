package de.raindancer.modules.jobs.store;

import de.raindancer.modules.jobs.model.GoalKind;
import de.raindancer.modules.jobs.model.GoalTemplate;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateCatalogueTest {

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    @Test
    @DisplayName("a goal kind is read with what counts, its sizes and its days; nonsense is skipped or fixed")
    void reads() throws Exception {
        List<GoalTemplate> read = TemplateCatalogue.parse(yaml("""
                goals:
                  cod:
                    title: Cod for the harbour
                    kind: deliver
                    icon: cod
                    items: [ cod ]
                    start: 150
                    least: 30
                    most: 5000
                    days: 5
                  fish:
                    kind: fish
                    items: [ cod, salmon ]
                  broken:
                    kind: dance
                    items: [ cod ]
                  empty:
                    kind: deliver
                """));
        assertThat(read).extracting(GoalTemplate::id).containsExactly("cod", "fish");
        GoalTemplate cod = read.getFirst();
        assertThat(cod.kind()).isEqualTo(GoalKind.DELIVER);
        assertThat(cod.items().covers("COD")).isTrue();
        assertThat(cod.start()).isEqualTo(150);
        assertThat(cod.days()).isEqualTo(5);
        GoalTemplate fish = read.get(1);
        assertThat(fish.title()).isEqualTo("Fish");
        assertThat(fish.start()).isPositive();
        assertThat(fish.least()).isPositive().isLessThanOrEqualTo(fish.start());
        assertThat(fish.most()).isGreaterThanOrEqualTo(fish.start());
        assertThat(fish.days()).isBetween(1, 30);
    }

    @Test
    @DisplayName("the shipped jobs.yml: cod to deliver, fish to catch, and every kind counts something")
    void shipped() throws Exception {
        try (var in = TemplateCatalogue.class.getResourceAsStream("/de/raindancer/modules/jobs/jobs.yml")) {
            List<GoalTemplate> read = TemplateCatalogue.parse(yaml(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            assertThat(read).hasSizeGreaterThanOrEqualTo(8);
            assertThat(read).anyMatch(each -> each.kind() == GoalKind.DELIVER && each.items().covers("COD"));
            assertThat(read).anyMatch(each -> each.kind() == GoalKind.FISH && each.items().covers("SALMON"));
            assertThat(read).allSatisfy(each -> assertThat(each.items().isEmpty()).as(each.id()).isFalse());
        }
    }
}
