package de.raindancer.modules.jobs.store;

import de.raindancer.modules.jobs.model.QuestTask;
import de.raindancer.modules.jobs.model.QuestTemplate;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class QuestCatalogueTest {

    private static List<QuestTemplate> shipped() throws Exception {
        try (var in = QuestCatalogue.class.getResourceAsStream("/de/raindancer/modules/jobs/quests.yml")) {
            assertThat(in).isNotNull();
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return QuestCatalogue.parse(yaml);
        }
    }

    @Test
    @DisplayName("every shipped role has quests of its own, and there are enough for anybody")
    void everyRole() throws Exception {
        List<QuestTemplate> quests = shipped();
        assertThat(quests.stream().filter(each -> !each.forRole())).hasSizeGreaterThanOrEqualTo(6);
        for (String role : List.of("cook", "farmer", "builder", "explorer", "miner", "mage", "hunter", "engineer")) {
            assertThat(quests.stream().filter(each -> each.role().equals(role))).as(role).hasSizeGreaterThanOrEqualTo(3);
        }
    }

    @Test
    @DisplayName("every shipped quest names things that exist: blocks for mining, mobs for killing, items for fish")
    void thingsExist() throws Exception {
        for (QuestTemplate quest : shipped()) {
            assertThat(Material.matchMaterial(quest.icon())).as(quest.id() + " icon").isNotNull();
            if (quest.task() == QuestTask.TRAVEL) {
                continue;
            }
            List<String> names = switch (quest.task()) {
                case KILL, BREED -> Arrays.stream(EntityType.values()).map(Enum::name).toList();
                default -> Arrays.stream(Material.values()).filter(each -> !each.isLegacy()).map(Enum::name).toList();
            };
            for (String pattern : quest.things().items()) {
                assertThat(names.stream().anyMatch(name -> de.raindancer.core.ui.choose.ItemSelection.matches(pattern, name)))
                        .as(quest.id() + ": " + pattern.toLowerCase(Locale.ROOT)).isTrue();
            }
        }
    }

    @Test
    @DisplayName("an unknown task or a quest with nothing to count is left out; travel needs nothing")
    void leftOut() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                quests:
                  a: { task: dance, things: [ stone ] }
                  b: { task: mine }
                  c: { task: travel, amount: 500, role: Explorer }
                """);
        List<QuestTemplate> read = QuestCatalogue.parse(yaml);
        assertThat(read).extracting(QuestTemplate::id).containsExactly("c");
        assertThat(read.getFirst().role()).isEqualTo("explorer");
    }
}
