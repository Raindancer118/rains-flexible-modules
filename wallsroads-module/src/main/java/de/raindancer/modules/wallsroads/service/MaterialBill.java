package de.raindancer.modules.wallsroads.service;

import de.raindancer.core.world.build.BatchBuilder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a build costs, and taking it out of somebody's pockets.
 *
 * <h2>Why a wall should cost anything</h2>
 * A four-thousand-block town wall conjured out of nothing is creative mode wearing a survival
 * server's clothes. Charged for, the same wall is the thing a town spent a season quarrying for —
 * and that is the entire difference between a decoration and an achievement.
 *
 * <p>Clearing is free and always affordable. A tunnel that stopped being bored because the builder
 * ran out of stone bricks halfway would leave the hill sitting in the middle of the road.
 */
public final class MaterialBill {

    /** One line per material, with air left out — nobody pays for a hole. */
    public Map<String, Integer> costOf(List<BatchBuilder.Placement> placements) {
        Map<String, Integer> cost = new LinkedHashMap<>();
        for (BatchBuilder.Placement placement : placements) {
            if (isFree(placement.material())) {
                continue;
            }
            cost.merge(placement.material(), 1, Integer::sum);
        }
        return cost;
    }

    /** What is on the bill and not in the pocket. */
    public Map<String, Integer> shortfall(Map<String, Integer> cost, Map<String, Integer> carried) {
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> line : cost.entrySet()) {
            int have = carried.getOrDefault(line.getKey(), 0);
            if (have < line.getValue()) {
                missing.put(line.getKey(), line.getValue() - have);
            }
        }
        return missing;
    }

    /**
     * The queue truncated to what the materials actually stretch to.
     *
     * <p>Truncated rather than refused: a builder who is short two hundred blocks gets two hundred
     * blocks less of road, ending where they ran out, which is both what they can see and what they
     * can carry on from.
     */
    public List<BatchBuilder.Placement> affordable(List<BatchBuilder.Placement> placements,
                                                   Map<String, Integer> carried) {
        Map<String, Integer> left = new HashMap<>(carried);
        List<BatchBuilder.Placement> affordable = new ArrayList<>(placements.size());
        for (BatchBuilder.Placement placement : placements) {
            if (isFree(placement.material())) {
                affordable.add(placement);
                continue;
            }
            int have = left.getOrDefault(placement.material(), 0);
            if (have <= 0) {
                continue;
            }
            left.put(placement.material(), have - 1);
            affordable.add(placement);
        }
        return affordable;
    }

    /** What this player is carrying, by material name. */
    public Map<String, Integer> carriedBy(Player player) {
        Map<String, Integer> carried = new HashMap<>();
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            carried.merge(stack.getType().name(), stack.getAmount(), Integer::sum);
        }
        return carried;
    }

    /** Takes the bill out of their inventory. Called only after {@link #affordable} has trimmed it. */
    public void charge(Player player, Map<String, Integer> cost) {
        for (Map.Entry<String, Integer> line : cost.entrySet()) {
            Material material = Material.matchMaterial(line.getKey());
            if (material == null) {
                continue;
            }
            player.getInventory().removeItem(new ItemStack(material, line.getValue()));
        }
    }

    private static boolean isFree(String material) {
        return material == null || material.equals("AIR") || material.equals("CAVE_AIR")
                || material.equals("VOID_AIR");
    }
}
