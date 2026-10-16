package de.raindancer.modules.oneblock.rules;

import de.raindancer.modules.oneblock.model.Content;
import de.raindancer.modules.oneblock.model.IslandDimension;
import de.raindancer.modules.oneblock.model.Upgrade;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A small catalogue the rule tests share: two blocks in the overworld pool, coal, a cow and auto-smelt. */
final class Contents {

    static final Upgrade COAL = new Upgrade("coal", "Coal", Upgrade.Kind.BLOCK, Material.COAL_ORE, null,
            Set.of(IslandDimension.OVERWORLD), List.of(new Upgrade.Level(5, 20), new Upgrade.Level(10, 50)));
    static final Upgrade COW = new Upgrade("cow", "Cows", Upgrade.Kind.MOB, Material.COW_SPAWN_EGG, EntityType.COW,
            Set.of(IslandDimension.OVERWORLD), List.of(new Upgrade.Level(5, 30)));
    static final Upgrade SMELT = new Upgrade("smelt", "Auto-smelt", Upgrade.Kind.SMELT, Material.FURNACE, null,
            Set.of(IslandDimension.OVERWORLD, IslandDimension.NETHER), List.of(new Upgrade.Level(50, 100)));
    static final Upgrade QUARTZ = new Upgrade("quartz", "Quartz", Upgrade.Kind.BLOCK, Material.NETHER_QUARTZ_ORE, null,
            Set.of(IslandDimension.NETHER), List.of(new Upgrade.Level(80, 10), new Upgrade.Level(90, 10)));

    static Content content() {
        Map<String, Upgrade> upgrades = new LinkedHashMap<>();
        for (Upgrade upgrade : List.of(COAL, COW, SMELT, QUARTZ)) {
            upgrades.put(upgrade.id(), upgrade);
        }
        return new Content(Map.of(
                IslandDimension.OVERWORLD, List.of(new Content.Weighted(Material.DIRT, 3), new Content.Weighted(Material.OAK_LOG, 1)),
                IslandDimension.NETHER, List.of(new Content.Weighted(Material.NETHERRACK, 1))),
                upgrades, Map.of(IslandDimension.NETHER, 300L, IslandDimension.END, 1000L), 10, 1, 4, 8);
    }

    private Contents() {
    }
}
