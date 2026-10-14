package de.raindancer.modules.enchants;

import de.raindancer.core.content.advancement.AdvancementDef;
import de.raindancer.core.content.advancement.AdvancementFrame;
import de.raindancer.core.content.advancement.CustomAdvancements;
import de.raindancer.core.content.enchant.EnchantDef;
import de.raindancer.core.content.enchant.RainsEnchants;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final int BUMPER_CROP = 25;
    private static final int TUNNEL_STEP = 50;
    private static final int TUNNEL_STEPS = 10;
    private static final int LAUNCH_BLOCKS = 1000;

    /** Every enchantment's first-use advancement has the enchantment's own key. */
    private static final List<String> FIRST_USE = EnchantsDefinitions.all().stream().map(EnchantDef::key).toList();

    private final CustomAdvancements advancements;
    private final Map<UUID, Integer> excavated = new ConcurrentHashMap<>();

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
        child("auto_sell", "root", Material.GOLD_INGOT, "Cash on Delivery",
                "Have a tool with Auto Sell sell what it broke", AdvancementFrame.TASK, 1);
        child("boost", "root", Material.BLAZE_POWDER, "Afterburner",
                "Boost through the air on an elytra with Boost", AdvancementFrame.TASK, 1);
        child("burnout", "boost", Material.CAMPFIRE, "Burnout",
                "Use every Boost charge of an elytra in one flight", AdvancementFrame.GOAL, 1);
        child("launch", "root", Material.ELYTRA, "Liftoff",
                "Sneak and jump on an elytra with Launch", AdvancementFrame.TASK, 1);
        child("ludicrous_speed", "launch", Material.WIND_CHARGE, "Ludicrous Speed",
                "Get " + LAUNCH_BLOCKS + " blocks away on a single launch", AdvancementFrame.GOAL, 1);
        child("swiftness", "root", Material.SUGAR, "Gotta Go Fast",
                "Put on leggings with Swiftness", AdvancementFrame.TASK, 1);
        child("haste", "root", Material.GOLDEN_PICKAXE, "Caffeinated",
                "Hold a tool with Haste", AdvancementFrame.TASK, 1);
        child("soulbound", "root", Material.TOTEM_OF_UNDYING, "Not Today",
                "Die and keep something Soulbound", AdvancementFrame.TASK, 1);
        child("wisdom", "root", Material.EXPERIENCE_BOTTLE, "Book Smart",
                "Earn extra experience with Wisdom", AdvancementFrame.TASK, 1);
        child("excavator", "root", Material.IRON_SHOVEL, "Strip Mine",
                "Break a whole face of blocks with Excavator", AdvancementFrame.TASK, 1);
        child("tunnel_vision", "excavator", Material.MINECART, "Tunnel Vision",
                "Excavate " + TUNNEL_STEP * TUNNEL_STEPS + " extra blocks", AdvancementFrame.GOAL, TUNNEL_STEPS);
        child("harvester", "root", Material.WHEAT, "Green Thumb",
                "Harvest a crop that plants itself again", AdvancementFrame.TASK, 1);
        child("bumper_crop", "harvester", Material.HAY_BLOCK, "Bumper Crop",
                "Harvest " + BUMPER_CROP + " crops with one swing", AdvancementFrame.GOAL, 1);
        child("smelting", "root", Material.FURNACE, "Hot Hands",
                "Mine something that comes out smelted", AdvancementFrame.TASK, 1);
        child("telekinesis", "root", Material.ENDER_EYE, "Look, No Hands",
                "Have drops fly straight into your inventory", AdvancementFrame.TASK, 1);
        child("chest_link", "root", Material.ENDER_CHEST, "Special Delivery",
                "Send what you mined to a linked chest", AdvancementFrame.TASK, 1);
        child("heat_seeking", "root", Material.SPECTRAL_ARROW, "Fox Two",
                "Hit something with a heat-seeking shot", AdvancementFrame.TASK, 1);
        child("shardlands", "root", Material.CRYING_OBSIDIAN, "Into the Dark",
                "Step through a portal into the shard world", AdvancementFrame.TASK, 1);
        child("first_shard", "shardlands", Material.ECHO_SHARD, "Shard Hunter",
                "Take an enchantment's shard off a monster in the shard world", AdvancementFrame.TASK, 1);
        child("full_set", "root", Material.NETHER_STAR, "Fully Enchanted",
                "Use every one of Rain's " + FIRST_USE.size() + " enchantments", AdvancementFrame.CHALLENGE,
                FIRST_USE.size());
    }

    /** An enchantment did its thing for somebody. */
    public void used(UUID player, String enchantment) {
        if (!FIRST_USE.contains(enchantment)) {
            return;
        }
        advancements.award(player, ROOT);
        if (advancements.award(player, enchantment)) {
            recount(player);
        }
    }

    /**
     * Brings the full set up to date with the first-use advancements this player has — for one another
     * module awarded (the economy's Auto Sell), which this class never saw happen.
     */
    public void recount(UUID player) {
        int have = (int) FIRST_USE.stream().filter(key -> advancements.has(player, key)).count();
        if (have > 0) {
            advancements.award(player, ROOT);
        }
        advancements.progress(player, FULL_SET, have);
    }

    /** Whether this advancement is one an enchantment's first use earns. */
    public static boolean isFirstUse(String key) {
        return FIRST_USE.contains(key);
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

    /** One swing of a Harvester hoe brought in this many ripe crops. */
    public void harvested(UUID player, int crops) {
        used(player, RainsEnchants.HARVESTER);
        if (crops >= BUMPER_CROP) {
            advancements.award(player, "rainsenchants:bumper_crop");
        }
    }

    /** One extra block came out with Excavator. Counted in memory up to the next step, so a few may be lost on a quit. */
    public void excavated(UUID player) {
        used(player, RainsEnchants.EXCAVATOR);
        int count = excavated.merge(player, 1, Integer::sum);
        if (count >= TUNNEL_STEP) {
            excavated.remove(player);
            advancements.progress(player, "rainsenchants:tunnel_vision",
                    advancements.stepsDone(player, "rainsenchants:tunnel_vision") + 1);
        }
    }

    public void boostsEmptied(UUID player) {
        advancements.award(player, "rainsenchants:burnout");
    }

    /** How far, in blocks, one launch has carried the player from where it started. */
    public void launchCarried(UUID player, double blocks) {
        if (blocks >= LAUNCH_BLOCKS) {
            advancements.award(player, "rainsenchants:ludicrous_speed");
        }
    }

    public void enteredShardWorld(UUID player) {
        advancements.award(player, ROOT);
        advancements.award(player, "rainsenchants:shardlands");
    }

    public void foundShard(UUID player) {
        advancements.award(player, "rainsenchants:first_shard");
    }

    public void forget(UUID player) {
        excavated.remove(player);
    }

    private void child(String id, String parent, Material icon, String title, String description,
                       AdvancementFrame frame, int steps) {
        advancements.define(AdvancementDef.builder(PLUGIN, id).parent(parent).icon(icon).title(title)
                .description(description).frame(frame).steps(steps).build());
    }
}
