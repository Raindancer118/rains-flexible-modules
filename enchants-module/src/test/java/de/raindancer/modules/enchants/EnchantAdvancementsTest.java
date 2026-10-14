package de.raindancer.modules.enchants;

import de.raindancer.core.content.advancement.AdvancementSink;
import de.raindancer.core.content.advancement.CustomAdvancements;
import de.raindancer.core.content.enchant.RainsEnchants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.raindancer.core.content.enchant.EnchantDef;

import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EnchantAdvancementsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());

    private final Map<String, Set<String>> met = new HashMap<>();
    private CustomAdvancements advancements;
    private EnchantAdvancements enchants;

    @BeforeEach
    void setUp() {
        advancements = new CustomAdvancements(new AdvancementSink() {
            @Override
            public Set<String> publish(Map<String, String> json) {
                return new HashSet<>(json.keySet());
            }

            @Override
            public Set<String> met(UUID player, String key) {
                return met.getOrDefault(key, Set.of());
            }

            @Override
            public boolean grant(UUID player, String key, String criterion) {
                return met.computeIfAbsent(key, k -> new HashSet<>()).add(criterion);
            }

            @Override
            public boolean revoke(UUID player, String key, String criterion) {
                return met.getOrDefault(key, new HashSet<>()).remove(criterion);
            }
        });
        enchants = new EnchantAdvancements(advancements);
        enchants.define();
    }

    @Test
    @DisplayName("every advancement loads — a parent that does not exist would leave a whole branch out")
    void allLoad() {
        for (String id : new String[]{"root", "veinminer", "motherlode", "night_vision", "flight", "empty_tank",
                "frequent_flyer", "double_jump", "triple_jump", "full_set", "auto_sell", "boost", "burnout",
                "launch", "ludicrous_speed", "swiftness", "haste", "soulbound", "wisdom", "excavator", "tunnel_vision",
                "harvester", "bumper_crop", "smelting", "telekinesis", "chest_link", "shardlands", "first_shard"}) {
            assertThat(advancements.isLoaded("rainsenchants:" + id)).as(id).isTrue();
        }
    }

    @Test
    @DisplayName("first use of an enchantment awards the tab and its own advancement, once")
    void firstUse() {
        enchants.used(ALICE, RainsEnchants.FLIGHT);

        assertThat(advancements.has(ALICE, "rainsenchants:root")).isTrue();
        assertThat(advancements.has(ALICE, "rainsenchants:flight")).isTrue();
        assertThat(advancements.has(ALICE, "rainsenchants:night_vision")).isFalse();
        assertThat(advancements.stepsDone(ALICE, "rainsenchants:full_set")).isEqualTo(1);

        enchants.used(ALICE, RainsEnchants.FLIGHT);
        assertThat(advancements.stepsDone(ALICE, "rainsenchants:full_set")).as("not counted twice").isEqualTo(1);
    }

    @Test
    @DisplayName("using every one of them completes the full set, and one short does not")
    void fullSet() {
        List<String> all = EnchantsDefinitions.all().stream().map(EnchantDef::key).toList();
        for (String key : all.subList(1, all.size())) {
            enchants.used(ALICE, key);
        }
        assertThat(advancements.has(ALICE, "rainsenchants:full_set")).isFalse();
        assertThat(advancements.stepsDone(ALICE, "rainsenchants:full_set")).isEqualTo(all.size() - 1);

        enchants.used(ALICE, all.get(0));
        assertThat(advancements.has(ALICE, "rainsenchants:full_set")).isTrue();
    }

    @Test
    @DisplayName("an enchantment another module awards (Auto Sell, from the economy) still counts once recounted")
    void awardedElsewhere() {
        advancements.award(ALICE, "rainsenchants:auto_sell");
        assertThat(advancements.stepsDone(ALICE, "rainsenchants:full_set")).isZero();

        enchants.recount(ALICE);
        assertThat(advancements.stepsDone(ALICE, "rainsenchants:full_set")).isEqualTo(1);
        assertThat(advancements.has(ALICE, "rainsenchants:root")).isTrue();
    }

    @Test
    @DisplayName("every enchantment has a first-use advancement")
    void everyEnchantmentHasOne() {
        for (EnchantDef def : EnchantsDefinitions.all()) {
            enchants.used(ALICE, def.key());
            assertThat(advancements.has(ALICE, def.key())).as(def.key()).isTrue();
        }
    }

    @Test
    @DisplayName("a 5x5 field in one swing is a bumper crop; fewer is not")
    void bumperCrop() {
        enchants.harvested(ALICE, 24);
        assertThat(advancements.has(ALICE, "rainsenchants:bumper_crop")).isFalse();
        assertThat(advancements.has(ALICE, "rainsenchants:harvester")).isTrue();
        enchants.harvested(ALICE, 25);
        assertThat(advancements.has(ALICE, "rainsenchants:bumper_crop")).isTrue();
    }

    @Test
    @DisplayName("tunnel vision counts excavated blocks in steps of fifty")
    void tunnelVision() {
        for (int i = 0; i < 149; i++) {
            enchants.excavated(ALICE);
        }
        assertThat(advancements.stepsDone(ALICE, "rainsenchants:tunnel_vision")).isEqualTo(2);
        enchants.excavated(ALICE);
        assertThat(advancements.stepsDone(ALICE, "rainsenchants:tunnel_vision")).isEqualTo(3);
        assertThat(advancements.has(ALICE, "rainsenchants:excavator")).isTrue();
    }

    @Test
    @DisplayName("burning every boost in one flight, and a launch carrying a thousand blocks")
    void elytraGoals() {
        enchants.boostsEmptied(ALICE);
        assertThat(advancements.has(ALICE, "rainsenchants:burnout")).isTrue();
        enchants.launchCarried(ALICE, 999);
        assertThat(advancements.has(ALICE, "rainsenchants:ludicrous_speed")).isFalse();
        enchants.launchCarried(ALICE, 1000);
        assertThat(advancements.has(ALICE, "rainsenchants:ludicrous_speed")).isTrue();
    }

    @Test
    @DisplayName("a vein of twenty is the motherlode; a smaller one is not")
    void motherlode() {
        enchants.vein(ALICE, 19);
        assertThat(advancements.has(ALICE, "rainsenchants:motherlode")).isFalse();
        assertThat(advancements.has(ALICE, "rainsenchants:veinminer")).isTrue();

        enchants.vein(ALICE, 20);
        assertThat(advancements.has(ALICE, "rainsenchants:motherlode")).isTrue();
    }

    @Test
    @DisplayName("flight minutes count one step each, and an empty tank has its own advancement")
    void flightMilestones() {
        enchants.flewMinutes(ALICE, 3);
        assertThat(advancements.stepsDone(ALICE, "rainsenchants:frequent_flyer")).isEqualTo(3);

        enchants.flewMinutes(ALICE, 2);
        assertThat(advancements.stepsDone(ALICE, "rainsenchants:frequent_flyer")).as("never backwards").isEqualTo(3);

        enchants.emptiedTank(ALICE);
        assertThat(advancements.has(ALICE, "rainsenchants:empty_tank")).isTrue();
    }

    @Test
    @DisplayName("the third jump in a row is the triple threat")
    void jumps() {
        enchants.jumped(ALICE, 2);
        assertThat(advancements.has(ALICE, "rainsenchants:triple_jump")).isFalse();
        assertThat(advancements.has(ALICE, "rainsenchants:double_jump")).isTrue();

        enchants.jumped(ALICE, 3);
        assertThat(advancements.has(ALICE, "rainsenchants:triple_jump")).isTrue();
    }
}
