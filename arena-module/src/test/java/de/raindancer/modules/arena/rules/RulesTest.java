package de.raindancer.modules.arena.rules;

import de.raindancer.modules.arena.model.ArenaAction;
import de.raindancer.core.world.combat.Attack;
import de.raindancer.core.world.combat.Verdict;
import de.raindancer.modules.arena.model.ArenaWorld;
import de.raindancer.modules.arena.model.Box;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RulesTest {

    // ── the border ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the border is square round the map's middle, as wide as its longer side plus a margin each way")
    void border() {
        ArenaWorld world = new ArenaWorld("arena", "b.schem", new Box("arena", 0, 64, 0, 230, 127, 195));

        BorderRule.Border border = new BorderRule().border(world, 6);

        assertThat(border.centreX()).isEqualTo(115.5);
        assertThat(border.centreZ()).isEqualTo(98.0);
        assertThat(border.size()).isEqualTo(231 + 12);
    }

    @Test
    @DisplayName("a negative margin is no margin, not a border that cuts into the map")
    void borderNeverCutsTheMap() {
        ArenaWorld world = new ArenaWorld("arena", "a.schem", new Box("arena", 0, 64, 0, 99, 100, 49));

        assertThat(new BorderRule().border(world, -20).size()).isEqualTo(100);
    }

    // ── what may be touched ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("somebody building may do anything; anybody else changes nothing and opens nothing")
    void protection() {
        ProtectionRule rule = new ProtectionRule();

        for (ArenaAction action : ArenaAction.values()) {
            assertThat(rule.allows(action, true)).as("a builder: " + action).isTrue();
            assertThat(rule.allows(action, false)).as("a fighter: " + action).isFalse();
        }
    }

    @Test
    @DisplayName("a wither or an ender dragon may never exist in an arena world; everything else is not this rule's business")
    void noBosses() {
        ProtectionRule rule = new ProtectionRule();

        assertThat(rule.mayExist("WITHER")).isFalse();
        assertThat(rule.mayExist("ender_dragon")).isFalse();
        assertThat(rule.mayExist("ZOMBIE")).isTrue();
        assertThat(rule.mayExist("WITHER_SKELETON")).as("a wither skeleton is a fair wave mob").isTrue();
    }

    @Test
    @DisplayName("the actions cover the ways an item gets out of the arena, not only the ways a block changes")
    void protectionCoversItemEscapes() {
        assertThat(Set.of(ArenaAction.values())).contains(ArenaAction.ENDER_CHEST, ArenaAction.CONTAINER,
                ArenaAction.DROP, ArenaAction.PICK_UP, ArenaAction.FRAME, ArenaAction.ARMOUR_STAND);
    }

    // ── who may hurt whom ──────────────────────────────────────────────────────────────────────────

    private static Attack attack(Attack.Fighter attacker, Attack.Fighter victim, String world) {
        Attack.At at = new Attack.At(0, 64, 0);
        return new Attack(attacker, UUID.randomUUID(), victim, UUID.randomUUID(), world, false, at, at);
    }

    @Test
    @DisplayName("outside a fight nobody in the arena can be hurt by anybody, and nobody there can hurt anything")
    void nobodyHurtOutsideAFight() {
        FightRule rule = new FightRule();

        assertThat(rule.verdict(attack(Attack.Fighter.PLAYER, Attack.Fighter.PLAYER, "arena"), "arena"::equals,
                (attacker, victim) -> false)).isEqualTo(Verdict.PROTECTED);
        assertThat(rule.verdict(attack(Attack.Fighter.MOB, Attack.Fighter.PLAYER, "arena"), "arena"::equals,
                (attacker, victim) -> false)).isEqualTo(Verdict.PROTECTED);
        assertThat(rule.verdict(attack(Attack.Fighter.PLAYER, Attack.Fighter.MOB, "arena"), "arena"::equals,
                (attacker, victim) -> false)).isEqualTo(Verdict.PROTECTED);
    }

    @Test
    @DisplayName("two who are fighting each other may, and the arena has no opinion about anywhere else")
    void fightersMay() {
        FightRule rule = new FightRule();

        assertThat(rule.verdict(attack(Attack.Fighter.PLAYER, Attack.Fighter.PLAYER, "arena"), "arena"::equals,
                (attacker, victim) -> true)).isEqualTo(Verdict.ALLOWED);
        assertThat(rule.verdict(attack(Attack.Fighter.PLAYER, Attack.Fighter.PLAYER, "world"), "arena"::equals,
                (attacker, victim) -> false)).isNull();
        assertThat(rule.verdict(attack(Attack.Fighter.MOB, Attack.Fighter.MOB, "arena"), "arena"::equals,
                (attacker, victim) -> false)).as("mobs fighting mobs are not the arena's business").isNull();
    }

    // ── what may be used while aiming at a block ───────────────────────────────────────────────────

    @Test
    @DisplayName("fighting gear and food work whatever the crosshair is on; anything that changes or makes things does not")
    void itemsAgainstBlocks() {
        ItemUseRule rule = new ItemUseRule();

        for (String fighting : java.util.List.of("BOW", "CROSSBOW", "TRIDENT",
                "MACE", "SHIELD", "COOKED_BEEF", "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE", "POTION", "SPLASH_POTION",
                "LINGERING_POTION", "ENDER_PEARL", "SNOWBALL", "WIND_CHARGE", "TOTEM_OF_UNDYING", "MILK_BUCKET",
                "FISHING_ROD", "AIR")) {
            assertThat(rule.usableAgainstBlocks(fighting)).as(fighting).isTrue();
        }
        for (String changing : java.util.List.of("FLINT_AND_STEEL", "FIRE_CHARGE", "BONE_MEAL", "WATER_BUCKET",
                "LAVA_BUCKET", "BUCKET", "ZOMBIE_SPAWN_EGG", "OAK_BOAT", "MINECART", "ARMOR_STAND", "ITEM_FRAME",
                "PAINTING", "END_CRYSTAL", "STONE", "TNT", "IRON_HOE", "IRON_SHOVEL", "SHEARS", "EGG", "LEAD",
                "BRUSH", "INK_SAC", "HONEYCOMB", "WRITABLE_BOOK")) {
            assertThat(rule.usableAgainstBlocks(changing)).as(changing).isFalse();
        }
    }

    @Test
    @DisplayName("a right click with an axe or a sword does nothing in a fight, so neither works on a block — no stripping")
    void axesAndSwordsDoNotWorkOnBlocks() {
        assertThat(new ItemUseRule().usableAgainstBlocks("IRON_AXE")).isFalse();
        assertThat(new ItemUseRule().usableAgainstBlocks("DIAMOND_SWORD")).isFalse();
        assertThat(new ItemUseRule().usableAgainstBlocks("IRON_CHESTPLATE")).as("armour still goes on").isTrue();
    }
}
