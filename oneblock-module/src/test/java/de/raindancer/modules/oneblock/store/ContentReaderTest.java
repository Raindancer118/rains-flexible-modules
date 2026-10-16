package de.raindancer.modules.oneblock.store;

import de.raindancer.modules.oneblock.OneBlockModule;
import de.raindancer.modules.oneblock.model.Content;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.Upgrade;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ContentReaderTest {

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    private static YamlConfiguration shipped() throws IOException, InvalidConfigurationException {
        try (InputStream in = OneBlockModule.class.getResourceAsStream("content.yml")) {
            assertThat(in).as("content.yml ships beside the module").isNotNull();
            return yaml(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("the shipped file reads without a single problem")
    void shippedIsClean() throws Exception {
        ContentReader.Result result = ContentReader.read(shipped());

        assertThat(result.problems()).isEmpty();
        Content content = result.content();
        assertThat(content.pool(IslandDimension.OVERWORLD)).isNotEmpty();
        assertThat(content.pool(IslandDimension.NETHER)).isNotEmpty();
        assertThat(content.pool(IslandDimension.END)).isNotEmpty();
        assertThat(content.unlockPrice(IslandDimension.NETHER)).contains(400L);
        assertThat(content.unlockPrice(IslandDimension.END)).contains(1200L);
        assertThat(content.unlockPrice(IslandDimension.OVERWORLD)).isEmpty();
        assertThat(content.shardEvery()).isEqualTo(10);
        assertThat(content.teamSize()).isEqualTo(6);
        assertThat(content.upgradesFor(IslandDimension.OVERWORLD)).extracting(Upgrade::id)
                .startsWith("coal", "copper", "iron").contains("smelt", "auto-break").doesNotContain("quartz");
    }

    @Test
    @DisplayName("every dimension has a way to earn: its pool has no block that falls into the void")
    void poolsHaveNoFallingBlocks() throws Exception {
        Content content = ContentReader.read(shipped()).content();
        for (IslandDimension dimension : IslandDimension.values()) {
            assertThat(content.pool(dimension)).extracting(Content.Weighted::material)
                    .as(dimension + "'s pool").noneMatch(Material::hasGravity);
        }
    }

    @Test
    @DisplayName("prices climb with the level, so a higher level is never cheaper than the one below")
    void pricesClimb() throws Exception {
        for (Upgrade upgrade : ContentReader.read(shipped()).content().allUpgrades()) {
            for (int level = 2; level <= upgrade.maxLevel(); level++) {
                assertThat(upgrade.level(level).orElseThrow().price()).as(upgrade.id() + " level " + level)
                        .isGreaterThanOrEqualTo(upgrade.level(level - 1).orElseThrow().price());
                assertThat(upgrade.level(level).orElseThrow().chance()).as(upgrade.id() + " level " + level)
                        .isGreaterThan(upgrade.level(level - 1).orElseThrow().chance());
            }
        }
    }

    @Test
    @DisplayName("an upgrade reads with its kind, block or mob, dimensions and levels")
    void anUpgrade() throws Exception {
        Content content = ContentReader.read(shipped()).content();

        Upgrade coal = content.upgrade("coal").orElseThrow();
        assertThat(coal.kind()).isEqualTo(Upgrade.Kind.BLOCK);
        assertThat(coal.block()).isEqualTo(Material.COAL_ORE);
        assertThat(coal.levels()).containsExactly(new Upgrade.Level(4, 20), new Upgrade.Level(8, 50),
                new Upgrade.Level(12, 100));
        Upgrade cow = content.upgrade("cow").orElseThrow();
        assertThat(cow.mob()).isEqualTo(EntityType.COW);
        assertThat(cow.icon()).isEqualTo(Material.COW_SPAWN_EGG);
        assertThat(content.upgrade("auto-break").orElseThrow().kind()).isEqualTo(Upgrade.Kind.AUTO_BREAK);
    }

    @Test
    @DisplayName("a mistake is named and skipped; the rest of the file still counts")
    void mistakesAreNamed() throws Exception {
        ContentReader.Result result = ContentReader.read(yaml("""
                pools:
                  overworld:
                    dirt: 5
                    not_a_block: 3
                    stone: -1
                  narnia:
                    dirt: 1
                upgrades:
                  good:
                    kind: block
                    block: coal_ore
                    dimensions: [overworld]
                    levels: [{chance: 5, price: 10}]
                  bad-block:
                    kind: block
                    block: nope
                    dimensions: [overworld]
                    levels: [{chance: 5, price: 10}]
                  no-levels:
                    kind: block
                    block: dirt
                    dimensions: [overworld]
                  bad-kind:
                    kind: magic
                  bad-mob:
                    kind: mob
                    mob: dragon_of_doom
                    dimensions: [overworld]
                    levels: [{chance: 5, price: 10}]
                  nowhere:
                    kind: block
                    block: dirt
                    dimensions: [mars]
                    levels: [{chance: 5, price: 10}]
                """));

        assertThat(result.content().pool(IslandDimension.OVERWORLD)).extracting(Content.Weighted::material)
                .containsExactly(Material.DIRT);
        assertThat(result.content().allUpgrades()).extracting(Upgrade::id).containsExactly("good");
        assertThat(result.problems()).hasSize(9).anySatisfy(problem -> assertThat(problem).contains("not_a_block"))
                .anySatisfy(problem -> assertThat(problem).contains("narnia"))
                .anySatisfy(problem -> assertThat(problem).contains("dragon_of_doom"))
                .anySatisfy(problem -> assertThat(problem).contains("magic"));
    }

    @Test
    @DisplayName("an empty file is a playable default: overworld only, nothing to buy")
    void emptyFile() throws Exception {
        ContentReader.Result result = ContentReader.read(yaml(""));

        assertThat(result.problems()).isEmpty();
        assertThat(result.content().allUpgrades()).isEmpty();
        assertThat(result.content().shardEvery()).isEqualTo(10);
        assertThat(result.content().teamSize()).isEqualTo(6);
    }
}
