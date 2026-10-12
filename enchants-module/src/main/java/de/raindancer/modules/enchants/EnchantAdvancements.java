package de.raindancer.modules.enchants;

import de.raindancer.core.content.advancement.AdvancementDef;
import de.raindancer.core.content.advancement.AdvancementFrame;
import de.raindancer.core.content.advancement.CustomAdvancements;
import de.raindancer.core.content.enchant.RainsEnchants;
import org.bukkit.Material;

import java.util.Map;
import java.util.UUID;

/**
 * The enchantments' advancement tab, and what earns each. Every award is safe to repeat; the callers
 * only report moments (a take-off, a vein) and this decides what that is worth.
 */
public final class EnchantAdvancements {

    private static final String PLUGIN = "rainsenchants";
    private static final String ROOT = "rainsenchants:root";
    private static final String FULL_SET = "rainsenchants:full_set";
    private static final int MOTHERLODE_BLOCKS = 20;
    private static final int FLIGHT_MINUTES = 10;

    /** Enchantment key → the advancement for using it the first time. */
    private static final Map<String, String> FIRST_USE = Map.of(
            RainsEnchants.VEINMINER, "rainsenchants:veinminer",
            RainsEnchants.NIGHT_VISION, "rainsenchants:night_vision",
            RainsEnchants.FLIGHT, "rainsenchants:flight",
            RainsEnchants.DOUBLE_JUMP, "rainsenchants:double_jump");

    private final CustomAdvancements advancements;

    public EnchantAdvancements(CustomAdvancements advancements) {
        this.advancements = advancements;
    }

    public void define() {
        advancements.define(AdvancementDef.builder(PLUGIN, "root").icon(Material.ENCHANTED_BOOK)
                .title("<gold>Rain's Enchantments").description("Use one of the server's own enchantments")
                .background("minecraft:gui/advancements/backgrounds/adventure").build());
        child("veinminer", "root", Material.DIAMOND_PICKAXE, "Strike It Rich",
                "Mine a vein with a Veinminer tool", AdvancementFrame.TASK, 1);
        child("motherlode", "veinminer", Material.NETHERITE_PICKAXE, "Motherlode",
                "Pull " + MOTHERLODE_BLOCKS + " blocks out of the ground in a single vein", AdvancementFrame.GOAL, 1);
        child("night_vision", "root", Material.GOLDEN_CARROT, "Cat's Eyes",
                "Put on a helmet with Night Vision", AdvancementFrame.TASK, 1);
        child("flight", "root", Material.FEATHER, "Up, Up and Away",
                "Take off in a chestplate with Flight", AdvancementFrame.TASK, 1);
        child("empty_tank", "flight", Material.FIREWORK_ROCKET, "Running on Empty",
                "Use up all the flight time of a chestplate in one go", AdvancementFrame.TASK, 1);
        child("frequent_flyer", "flight", Material.CLOCK, "Frequent Flyer",
                "Fly for " + FLIGHT_MINUTES + " minutes in total", AdvancementFrame.GOAL, FLIGHT_MINUTES);
        child("double_jump", "root", Material.RABBIT_FOOT, "Hop, Skip",
                "Jump a second time in mid-air", AdvancementFrame.TASK, 1);
        child("triple_jump", "double_jump", Material.SLIME_BLOCK, "Triple Threat",
                "Jump three times without touching the ground", AdvancementFrame.GOAL, 1);
        child("full_set", "root", Material.NETHER_STAR, "Fully Enchanted",
                "Use all four of Rain's enchantments", AdvancementFrame.CHALLENGE, FIRST_USE.size());
    }

    /** An enchantment did its thing for somebody. */
    public void used(UUID player, String enchantment) {
        String own = FIRST_USE.get(enchantment);
        if (own == null) {
            return;
        }
        advancements.award(player, ROOT);
        if (advancements.award(player, own)) {
            advancements.progress(player, FULL_SET, advancements.stepsDone(player, FULL_SET) + 1);
        }
    }

    /** A Veinminer tool took {@code blocks} extra blocks out in one vein. */
    public void vein(UUID player, int blocks) {
        used(player, RainsEnchants.VEINMINER);
        if (blocks >= MOTHERLODE_BLOCKS) {
            advancements.award(player, "rainsenchants:motherlode");
        }
    }

    /** Total whole minutes flown; each is one step, and an older, larger count is never lowered. */
    public void flewMinutes(UUID player, int totalMinutes) {
        advancements.progress(player, "rainsenchants:frequent_flyer", Math.min(totalMinutes, FLIGHT_MINUTES));
    }

    public void emptiedTank(UUID player) {
        advancements.award(player, "rainsenchants:empty_tank");
    }

    /** @param jumpsInARow how many mid-air jumps this is, without having landed */
    public void jumped(UUID player, int jumpsInARow) {
        used(player, RainsEnchants.DOUBLE_JUMP);
        if (jumpsInARow >= 3) {
            advancements.award(player, "rainsenchants:triple_jump");
        }
    }

    private void child(String id, String parent, Material icon, String title, String description,
                       AdvancementFrame frame, int steps) {
        advancements.define(AdvancementDef.builder(PLUGIN, id).parent(parent).icon(icon).title(title)
                .description(description).frame(frame).steps(steps).build());
    }
}
