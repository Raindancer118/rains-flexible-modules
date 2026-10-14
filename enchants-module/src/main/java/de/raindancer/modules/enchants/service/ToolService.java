package de.raindancer.modules.enchants.service;

import de.raindancer.core.content.enchant.CustomEnchantments;
import de.raindancer.core.content.enchant.RainsEnchants;
import de.raindancer.core.content.items.FurnaceResults;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.enchants.EnchantAdvancements;
import de.raindancer.modules.enchants.EnchantsSettings;
import de.raindancer.modules.enchants.rules.ToolRules;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Smelting, Telekinesis, Excavator, Harvester, Wisdom and Soulbound: what a tool does to the blocks it breaks,
 * the drops that come out of them and the gear a player dies with. Every extra block is broken through
 * {@link Player#breakBlock}, so claims, logs and the other drop enchantments see it like any other.
 */
public final class ToolService {

    private record Replant(Material crop, int tick) {
    }

    private final Plugin plugin;
    private final Supplier<EnchantsSettings> settings;
    private final EnchantAdvancements advancements;
    private final Messages messages;
    private volatile FurnaceResults furnace = FurnaceResults.of(java.util.Collections.emptyIterator());
    /** Players whose extra breaks are running now: their own breaks must not set off more. */
    private final Set<UUID> breaking = ConcurrentHashMap.newKeySet();
    private final Map<Location, Replant> replants = new ConcurrentHashMap<>();
    private final Map<UUID, Double> smeltExperience = new ConcurrentHashMap<>();

    public ToolService(Plugin plugin, Supplier<EnchantsSettings> settings, EnchantAdvancements advancements,
                       Messages messages) {
        this.plugin = plugin;
        this.settings = settings;
        this.advancements = advancements;
        this.messages = messages;
    }

    /** Reads the furnace recipes; done once the server has them all. */
    public void learnRecipes() {
        furnace = FurnaceResults.server();
    }

    private static int level(ItemStack tool, String enchantment) {
        return CustomEnchantments.levelOn(tool, enchantment);
    }

    private static ItemStack hand(Player player) {
        return player.getInventory().getItemInMainHand();
    }

    // ---------------------------------------------------------------------------- breaking

    /** @return true to cancel the break: an unripe crop under a Harvester hoe */
    public boolean beforeBreak(Player player, Block block) {
        int harvester = level(hand(player), RainsEnchants.HARVESTER);
        if (harvester < 1 || ToolRules.seedOf(block.getType()).isEmpty()) {
            return false;
        }
        if (!(block.getBlockData() instanceof Ageable crop) || crop.getAge() < crop.getMaximumAge()) {
            return !player.isSneaking();
        }
        replants.put(block.getLocation(), new Replant(block.getType(), Bukkit.getCurrentTick()));
        return false;
    }

    /** The block is going: experience for Wisdom, and the extra blocks of Excavator or Harvester. */
    public int experience(Player player, int experience) {
        int wisdom = level(hand(player), RainsEnchants.WISDOM);
        if (wisdom < 1 || experience <= 0) {
            return experience;
        }
        int more = ToolRules.wisdom(experience, wisdom, settings.get().wisdomPercentPerLevel(),
                ThreadLocalRandom.current());
        if (more > experience) {
            advancements.used(player.getUniqueId(), RainsEnchants.WISDOM);
        }
        return more;
    }

    public void afterBreak(Player player, Block block, Material type, float hardness) {
        if (breaking.contains(player.getUniqueId()) || player.isSneaking()) {
            return;
        }
        ItemStack tool = hand(player);
        if (level(tool, RainsEnchants.EXCAVATOR) > 0) {
            excavate(player, block, tool, hardness);
        }
        int harvester = level(tool, RainsEnchants.HARVESTER);
        if (harvester > 0 && replants.containsKey(block.getLocation())) {
            harvest(player, block, type, ToolRules.harvestRadius(harvester));
        }
    }

    private void excavate(Player player, Block center, ItemStack tool, float hardness) {
        Location eye = player.getLocation();
        breaking.add(player.getUniqueId());
        try {
            for (int[] offset : ToolRules.excavation(eye.getPitch(), eye.getYaw())) {
                if (hand(player).getType() != tool.getType()) {
                    return;
                }
                Block next = center.getRelative(offset[0], offset[1], offset[2]);
                Material type = next.getType();
                if (type.isAir() || next.isLiquid() || !next.isPreferredTool(hand(player))
                        || !ToolRules.excavates(hardness, type.getHardness())) {
                    continue;
                }
                if (player.breakBlock(next)) {
                    advancements.excavated(player.getUniqueId());
                }
            }
        } finally {
            breaking.remove(player.getUniqueId());
        }
    }

    private void harvest(Player player, Block center, Material crop, int radius) {
        int crops = 1;
        breaking.add(player.getUniqueId());
        try {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block next = center.getRelative(dx, 0, dz);
                    if ((dx == 0 && dz == 0) || ToolRules.seedOf(next.getType()).isEmpty()
                            || !(next.getBlockData() instanceof Ageable age) || age.getAge() < age.getMaximumAge()) {
                        continue;
                    }
                    replants.put(next.getLocation(), new Replant(next.getType(), Bukkit.getCurrentTick()));
                    if (player.breakBlock(next)) {
                        crops++;
                    } else {
                        replants.remove(next.getLocation());
                    }
                }
            }
        } finally {
            breaking.remove(player.getUniqueId());
        }
        advancements.harvested(player.getUniqueId(), crops);
    }

    // ---------------------------------------------------------------------------- drops

    /** First of all: a replanted crop keeps back one seed for itself. */
    public void keepSeed(Player player, Block block, List<Item> drops) {
        Replant replant = replants.remove(block.getLocation());
        if (replant == null || replant.tick() != Bukkit.getCurrentTick()) {
            return;
        }
        Material seed = ToolRules.seedOf(replant.crop()).orElseThrow();
        boolean taken = false;
        for (Iterator<Item> each = drops.iterator(); each.hasNext() && !taken; ) {
            Item item = each.next();
            ItemStack stack = item.getItemStack();
            if (stack.getType() == seed) {
                taken = true;
                if (stack.getAmount() == 1) {
                    each.remove();
                } else {
                    stack.setAmount(stack.getAmount() - 1);
                    item.setItemStack(stack);
                }
            }
        }
        if (!taken && !player.getInventory().removeItem(new ItemStack(seed)).isEmpty()) {
            return;
        }
        Location at = block.getLocation();
        Scheduling.regionLater(plugin, at, 1, () -> {
            Block below = at.getBlock().getRelative(0, -1, 0);
            boolean soil = replant.crop() == Material.NETHER_WART ? below.getType() == Material.SOUL_SAND
                    : below.getType() == Material.FARMLAND;
            if (at.getBlock().getType().isAir() && soil) {
                at.getBlock().setType(replant.crop());
            }
        });
    }

    /** Ores, sand and the rest come out as the furnace would make them, with its experience. */
    public void smelt(Player player, Block block, List<Item> drops) {
        if (level(hand(player), RainsEnchants.SMELTING) < 1) {
            return;
        }
        double experience = 0;
        for (Item item : drops) {
            ItemStack stack = item.getItemStack();
            FurnaceResults.Result smelt = furnace.of(stack.getType()).orElse(null);
            if (smelt == null) {
                continue;
            }
            ItemStack result = smelt.result();
            result.setAmount(Math.min(result.getMaxStackSize(), stack.getAmount() * result.getAmount()));
            item.setItemStack(result);
            experience += smelt.experience() * stack.getAmount();
        }
        if (experience <= 0) {
            return;
        }
        advancements.used(player.getUniqueId(), RainsEnchants.SMELTING);
        double owed = smeltExperience.merge(player.getUniqueId(), experience, Double::sum);
        int whole = (int) owed;
        if (whole > 0) {
            smeltExperience.put(player.getUniqueId(), owed - whole);
            block.getWorld().spawn(block.getLocation().add(0.5, 0.5, 0.5), ExperienceOrb.class,
                    orb -> orb.setExperience(whole));
        }
    }

    /** Last: whatever is still on the ground flies into the inventory; what does not fit stays. */
    public void pickUp(Player player, List<Item> drops) {
        if (level(hand(player), RainsEnchants.TELEKINESIS) < 1 || drops.isEmpty()) {
            return;
        }
        boolean any = false;
        for (Iterator<Item> each = drops.iterator(); each.hasNext(); ) {
            Item item = each.next();
            int before = item.getItemStack().getAmount();
            Map<Integer, ItemStack> left = player.getInventory().addItem(item.getItemStack());
            if (left.isEmpty()) {
                each.remove();
                any = true;
            } else {
                ItemStack rest = left.values().iterator().next();
                any |= rest.getAmount() < before;
                item.setItemStack(rest);
            }
        }
        if (any) {
            advancements.used(player.getUniqueId(), RainsEnchants.TELEKINESIS);
        }
    }

    /** The same for what a mob dropped, and its experience for Wisdom. @return the experience it drops */
    public int mobDied(Player killer, List<ItemStack> drops, int experience) {
        ItemStack weapon = hand(killer);
        if (level(weapon, RainsEnchants.TELEKINESIS) > 0 && !drops.isEmpty()) {
            boolean any = false;
            for (Iterator<ItemStack> each = drops.iterator(); each.hasNext(); ) {
                ItemStack stack = each.next();
                Map<Integer, ItemStack> left = killer.getInventory().addItem(stack.clone());
                if (left.isEmpty()) {
                    each.remove();
                    any = true;
                } else {
                    any |= left.values().iterator().next().getAmount() < stack.getAmount();
                    stack.setAmount(left.values().iterator().next().getAmount());
                }
            }
            if (any) {
                advancements.used(killer.getUniqueId(), RainsEnchants.TELEKINESIS);
            }
        }
        return experience(killer, experience);
    }

    // ---------------------------------------------------------------------------- dying

    /**
     * Soulbound gear stays with the player, a level poorer; at the last level the enchantment is used up
     * and the item is kept one final time.
     *
     * @return how many stacks were kept
     */
    public int keepSoulbound(Player player, List<ItemStack> drops, List<ItemStack> kept) {
        Optional<Enchantment> soulbound = CustomEnchantments.find(RainsEnchants.SOULBOUND);
        if (soulbound.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Iterator<ItemStack> each = drops.iterator(); each.hasNext(); ) {
            ItemStack stack = each.next();
            int level = level(stack, RainsEnchants.SOULBOUND);
            if (level < 1) {
                continue;
            }
            each.remove();
            ItemStack lowered = stack.clone();
            lowered.removeEnchantment(soulbound.get());
            int left = ToolRules.soulboundAfterDeath(level);
            if (left > 0) {
                lowered.addUnsafeEnchantment(soulbound.get(), left);
            }
            kept.add(lowered);
            count++;
        }
        if (count > 0) {
            advancements.used(player.getUniqueId(), RainsEnchants.SOULBOUND);
            messages.send(player, "enchants.soulbound-kept", "count", String.valueOf(count));
        }
        return count;
    }

    public void forget(UUID player) {
        breaking.remove(player);
        smeltExperience.remove(player);
    }
}
