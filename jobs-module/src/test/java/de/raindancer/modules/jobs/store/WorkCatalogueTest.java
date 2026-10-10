package de.raindancer.modules.jobs.store;

import de.raindancer.modules.jobs.model.QuestTask;
import de.raindancer.modules.jobs.model.Work;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkCatalogueTest {

    private static List<Work> shipped() throws Exception {
        try (var in = WorkCatalogue.class.getResourceAsStream("/de/raindancer/modules/jobs/orders.yml")) {
            assertThat(in).isNotNull();
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return WorkCatalogue.parse(yaml);
        }
    }

    @Test
    @DisplayName("plenty of work at every hardness, from stone to the dragon")
    void variety() throws Exception {
        List<Work> works = shipped();
        assertThat(works).hasSizeGreaterThanOrEqualTo(45);
        for (double band = 0; band < 1; band += 0.2) {
            double low = band;
            assertThat(works.stream().filter(each -> each.hardness() >= low && (each.hardness() < low + 0.2 || low >= 0.79)))
                    .as("work between " + low + " and " + (low + 0.2)).hasSizeGreaterThanOrEqualTo(4);
        }
        assertThat(works).extracting(Work::id).contains("wardens", "withers", "dragons", "elder-guardians");
    }

    @Test
    @DisplayName("every shipped work names things that exist and an icon that is an item")
    void thingsExist() throws Exception {
        for (Work work : shipped()) {
            Material icon = Material.matchMaterial(work.icon());
            assertThat(icon).as(work.id() + " icon").isNotNull();
            if (work.task() == QuestTask.TRAVEL) {
                continue;
            }
            List<String> names = switch (work.task()) {
                case KILL, BREED -> Arrays.stream(EntityType.values()).map(Enum::name).toList();
                default -> Arrays.stream(Material.values()).filter(each -> !each.isLegacy()).map(Enum::name).toList();
            };
            for (String pattern : work.things().items()) {
                assertThat(names.stream().anyMatch(name -> de.raindancer.core.ui.choose.ItemSelection.matches(pattern, name)))
                        .as(work.id() + ": " + pattern).isTrue();
            }
        }
    }
}
