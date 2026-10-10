package de.raindancer.modules.roles.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.social.economy.TradeSide;
import de.raindancer.core.ui.choose.Category;
import de.raindancer.modules.roles.model.Perk;
import de.raindancer.modules.roles.model.Role;
import de.raindancer.modules.roles.rules.PerkRule;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoleCatalogueTest {

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    private static List<Role> shipped() throws Exception {
        try (var in = RoleCatalogue.class.getResourceAsStream("/de/raindancer/modules/roles/roles.yml")) {
            assertThat(in).isNotNull();
            return RoleCatalogue.parse(yaml(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }

    @Test
    @DisplayName("price and rent-per-month are read as written, and are \"0\" when absent")
    void prices() throws Exception {
        List<Role> roles = RoleCatalogue.parse(yaml("""
                roles:
                  mage:
                    price: 1.5k
                    rent-per-month: 40
                  cook:
                    title: Cook
                """));
        assertThat(roles.get(0).price()).isEqualTo("1.5k");
        assertThat(roles.get(0).rent()).isEqualTo("40");
        assertThat(roles.get(0).forSale()).isTrue();
        assertThat(roles.get(1).price()).isEqualTo("0");
        assertThat(roles.get(1).rent()).isEqualTo("0");
        assertThat(roles.get(1).forSale()).isFalse();
    }

    @Test
    @DisplayName("the shipped roles are all free, so a server behaves as before until its owner sets a price")
    void shippedAreFree() throws Exception {
        assertThat(shipped()).noneMatch(Role::forSale);
    }

    @Test
    @DisplayName("a role is read with its perks: buy or sell, categories, items and exceptions")
    void reads() throws Exception {
        List<Role> roles = RoleCatalogue.parse(yaml("""
                roles:
                  builder:
                    title: Builder
                    icon: bricks
                    colour: "#c08040"
                    description: [ "Builds things." ]
                    perks:
                      - buy: -20
                        categories: [ building_blocks, decorations ]
                        items: [ scaffolding ]
                        except: [ diamond_block ]
                      - sell: 10
                        items: [ "*_log" ]
                        says: logs
                """));
        assertThat(roles).hasSize(1);
        Role builder = roles.getFirst();
        assertThat(builder.title()).isEqualTo("Builder");
        assertThat(builder.icon()).isEqualTo("BRICKS");
        assertThat(builder.perks()).hasSize(2);
        Perk buy = builder.perks().getFirst();
        assertThat(buy.side()).isEqualTo(TradeSide.BUY);
        assertThat(buy.percent()).isEqualTo(-20);
        assertThat(buy.items().categories()).containsExactly(Category.BUILDING_BLOCKS, Category.DECORATIONS);
        assertThat(buy.items().except()).containsExactly("DIAMOND_BLOCK");
        Perk sell = builder.perks().get(1);
        assertThat(sell.side()).isEqualTo(TradeSide.SELL);
        assertThat(sell.says()).isEqualTo("10% more selling logs");
    }

    @Test
    @DisplayName("a buy perk is always a discount and a sell perk a bonus, whatever sign was written")
    void signs() throws Exception {
        List<Role> roles = RoleCatalogue.parse(yaml("""
                roles:
                  cook:
                    perks:
                      - buy: 25
                        items: [ bread ]
                      - sell: -10
                        items: [ wheat ]
                      - buy: 0
                        items: [ stone ]
                      - items: [ dirt ]
                """));
        Role cook = roles.getFirst();
        assertThat(cook.perks()).extracting(Perk::percent).containsExactly(-25, 10);
        assertThat(cook.title()).isEqualTo("Cook");
        assertThat(cook.colour()).isEqualTo("#ffffff");
    }

    @Test
    @DisplayName("an unknown category is skipped, not the whole role; a bad id is skipped; the biggest discount is capped")
    void forgiving() throws Exception {
        List<Role> roles = RoleCatalogue.parse(yaml("""
                roles:
                  "Not An Id!":
                    perks: []
                  mage:
                    colour: purple
                    perks:
                      - buy: -95
                        categories: [ wizardry, brewing ]
                """));
        assertThat(roles).extracting(Role::id).containsExactly("mage");
        assertThat(roles.getFirst().perks().getFirst().items().categories()).containsExactly(Category.BREWING);
        assertThat(roles.getFirst().perks().getFirst().percent()).isEqualTo(-RoleCatalogue.LARGEST_PERK);
        assertThat(roles.getFirst().colour()).as("a colour that is not hex falls back").isEqualTo("#ffffff");
    }

    @Test
    @DisplayName("without a file the shipped roles are written out once, and an owner's file is never overwritten")
    void writesOnce(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("roles.yml");
        RoleCatalogue catalogue = new RoleCatalogue(new YamlStore(file),
                () -> new ByteArrayInputStream("roles:\n  a: {}\n".getBytes(StandardCharsets.UTF_8)));
        assertThat(catalogue.reload()).isEqualTo(1);
        Files.writeString(file, "roles:\n  b: {}\n  c: {}\n");
        assertThat(catalogue.reload()).isEqualTo(2);
        assertThat(catalogue.find("C")).isPresent();
        assertThat(catalogue.find("a")).isEmpty();
    }

    @Test
    @DisplayName("the shipped roles: cook, builder, explorer and mage are there, and do what was asked")
    void theShippedRoles() throws Exception {
        List<Role> roles = shipped();
        assertThat(roles).extracting(Role::id).contains("cook", "builder", "explorer", "mage");
        assertThat(roles).allSatisfy(role -> {
            assertThat(role.perks()).as(role.id()).isNotEmpty();
            assertThat(role.description()).as(role.id()).isNotEmpty();
        });
        PerkRule rule = new PerkRule();
        Role cook = roles.stream().filter(role -> role.id().equals("cook")).findFirst().orElseThrow();
        Role builder = roles.stream().filter(role -> role.id().equals("builder")).findFirst().orElseThrow();
        Role explorer = roles.stream().filter(role -> role.id().equals("explorer")).findFirst().orElseThrow();
        Role mage = roles.stream().filter(role -> role.id().equals("mage")).findFirst().orElseThrow();
        assertThat(rule.change(cook, "COOKED_BEEF", TradeSide.BUY)).isPresent();
        assertThat(rule.change(cook, "BREAD", TradeSide.BUY)).isPresent();
        assertThat(rule.change(builder, "STONE_BRICKS", TradeSide.BUY)).isPresent();
        assertThat(rule.change(builder, "OAK_PLANKS", TradeSide.BUY)).isPresent();
        assertThat(rule.change(builder, "DIAMOND_BLOCK", TradeSide.BUY)).as("value blocks are not building").isEmpty();
        assertThat(rule.change(builder, "COOKED_BEEF", TradeSide.BUY)).isEmpty();
        assertThat(rule.change(explorer, "FIREWORK_ROCKET", TradeSide.BUY)).isPresent();
        assertThat(rule.change(mage, "ENCHANTED_BOOK", TradeSide.BUY)).isPresent();
        assertThat(rule.change(mage, "LAPIS_LAZULI", TradeSide.BUY)).isPresent();
        // No role may undercut the shop's sell ratio (40%) on what it buys, or buying and selling back pays.
        assertThat(roles).allSatisfy(role -> assertThat(role.perks()).allSatisfy(perk ->
                assertThat(Math.abs(perk.percent())).as(role.id()).isLessThanOrEqualTo(RoleCatalogue.LARGEST_PERK)));
    }

    @Test
    @DisplayName("abilities are read by kind, capped, and unknown kinds are left out")
    void abilities() throws Exception {
        List<Role> roles = RoleCatalogue.parse(yaml("""
                roles:
                  miner:
                    abilities:
                      - tools: 20
                        items: [ "*_pickaxe" ]
                      - speed: 99
                      - flying: 50
                """));
        var abilities = roles.getFirst().abilities();
        assertThat(abilities).hasSize(2);
        assertThat(abilities.getFirst().kind()).isEqualTo(de.raindancer.modules.roles.model.AbilityKind.TOOLS);
        assertThat(abilities.getFirst().covers("DIAMOND_PICKAXE")).isTrue();
        assertThat(abilities.getFirst().covers("DIAMOND_AXE")).isFalse();
        assertThat(abilities.get(1).percent()).isEqualTo(de.raindancer.modules.roles.model.AbilityKind.SPEED.most());
    }

    @Test
    @DisplayName("every shipped role has an in-game ability as well as its shop perks")
    void shippedHaveAbilities() throws Exception {
        assertThat(shipped()).isNotEmpty().allSatisfy(role -> assertThat(role.abilities()).as(role.id()).isNotEmpty());
    }

    @Test
    @DisplayName("an explorer gets hungry slower; a cook gets more from crops and from the animals they kill")
    void explorerAndCook() throws Exception {
        var kind = (java.util.function.Function<Role, java.util.List<de.raindancer.modules.roles.model.AbilityKind>>)
                role -> role.abilities().stream().map(de.raindancer.modules.roles.model.Ability::kind).toList();
        Role explorer = shipped().stream().filter(role -> role.id().equals("explorer")).findFirst().orElseThrow();
        Role cook = shipped().stream().filter(role -> role.id().equals("cook")).findFirst().orElseThrow();
        assertThat(kind.apply(explorer)).contains(de.raindancer.modules.roles.model.AbilityKind.HUNGER,
                de.raindancer.modules.roles.model.AbilityKind.SPEED);
        assertThat(kind.apply(cook)).contains(de.raindancer.modules.roles.model.AbilityKind.HARVEST,
                de.raindancer.modules.roles.model.AbilityKind.BUTCHER);
    }

    @Test
    @DisplayName("a farmer gets the most extra crops there is; a miner a little luck with ore")
    void farmerAndMiner() throws Exception {
        Role farmer = shipped().stream().filter(role -> role.id().equals("farmer")).findFirst().orElseThrow();
        Role miner = shipped().stream().filter(role -> role.id().equals("miner")).findFirst().orElseThrow();
        assertThat(farmer.abilities()).anySatisfy(ability -> {
            assertThat(ability.kind()).isEqualTo(de.raindancer.modules.roles.model.AbilityKind.HARVEST);
            assertThat(ability.percent()).isEqualTo(de.raindancer.modules.roles.model.AbilityKind.HARVEST.most());
        });
        assertThat(miner.abilities()).anySatisfy(ability -> {
            assertThat(ability.kind()).isEqualTo(de.raindancer.modules.roles.model.AbilityKind.FORTUNE);
            assertThat(ability.percent()).isBetween(5, 15);
        });
    }

    @Test
    @DisplayName("a biologist sells anything that grows and anything mobs drop for more")
    void biologist() throws Exception {
        Role biologist = shipped().stream().filter(role -> role.id().equals("biologist")).findFirst().orElseThrow();
        PerkRule rule = new PerkRule();
        for (String plant : List.of("OAK_SAPLING", "POPPY", "WHEAT", "WHEAT_SEEDS", "CARROT", "RED_MUSHROOM", "KELP",
                "CACTUS", "SUGAR_CANE", "BAMBOO", "MOSS_BLOCK", "OAK_LEAVES", "SWEET_BERRIES", "NETHER_WART",
                "CHORUS_FRUIT", "SUNFLOWER", "LILY_PAD", "VINE", "CRIMSON_FUNGUS", "APPLE", "MELON_SLICE", "PUMPKIN")) {
            assertThat(rule.change(biologist, plant, TradeSide.SELL)).as(plant).hasValueSatisfying(
                    change -> assertThat(change.percent()).isPositive());
        }
        for (String drop : List.of("ROTTEN_FLESH", "BONE", "STRING", "SPIDER_EYE", "GUNPOWDER", "ENDER_PEARL",
                "LEATHER", "FEATHER", "SLIME_BALL", "BLAZE_ROD", "SHULKER_SHELL", "TURTLE_SCUTE", "BEEF", "MUTTON")) {
            assertThat(rule.change(biologist, drop, TradeSide.SELL)).as(drop).hasValueSatisfying(
                    change -> assertThat(change.percent()).isPositive());
        }
        assertThat(rule.change(biologist, "POPPY", TradeSide.SELL)).as("plants: up to 20%")
                .hasValueSatisfying(change -> assertThat(change.percent()).isEqualTo(20));
        assertThat(rule.change(biologist, "DIAMOND", TradeSide.SELL)).as("not a plant, not a drop").isEmpty();
        assertThat(rule.change(biologist, "OAK_PLANKS", TradeSide.SELL)).as("made from a plant is not a plant").isEmpty();
    }

    @Test
    @DisplayName("every item a shipped perk names exists: a typo would quietly cover nothing")
    void perkItemsExist() throws Exception {
        java.util.List<String> materials = java.util.Arrays.stream(org.bukkit.Material.values())
                .filter(each -> !each.isLegacy()).map(Enum::name).toList();
        for (Role role : shipped()) {
            for (Perk perk : role.perks()) {
                for (String pattern : perk.items().items()) {
                    assertThat(materials.stream().anyMatch(name -> de.raindancer.core.ui.choose.ItemSelection.matches(pattern, name)))
                            .as(role.id() + ": " + pattern).isTrue();
                }
            }
        }
    }
}
