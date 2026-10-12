package de.raindancer.modules.guilds.rules;

import de.raindancer.modules.guilds.GuildProgressSettings;
import de.raindancer.modules.guilds.GuildsSettings;
import de.raindancer.modules.guilds.model.Activity;
import org.bukkit.Material;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** What a member's activity is worth to their guild before caps and bonuses, and what counts as an ore or a crop. */
public final class ActivityRule implements IGuildsRule {

    private static final Map<Material, String> ORES = new EnumMap<>(Material.class);
    private static final Map<String, Integer> ORE_WEIGHT = Map.ofEntries(
            Map.entry("coal", 1), Map.entry("copper", 1), Map.entry("iron", 2), Map.entry("gold", 3),
            Map.entry("redstone", 1), Map.entry("lapis", 2), Map.entry("diamond", 8), Map.entry("emerald", 10),
            Map.entry("nether-gold", 1), Map.entry("quartz", 1), Map.entry("ancient-debris", 15));

    static {
        for (String ore : java.util.List.of("coal", "copper", "iron", "gold", "redstone", "lapis", "diamond", "emerald")) {
            ORES.put(Material.valueOf(ore.toUpperCase(Locale.ROOT) + "_ORE"), ore);
            ORES.put(Material.valueOf("DEEPSLATE_" + ore.toUpperCase(Locale.ROOT) + "_ORE"), ore);
        }
        ORES.put(Material.NETHER_GOLD_ORE, "nether-gold");
        ORES.put(Material.NETHER_QUARTZ_ORE, "quartz");
        ORES.put(Material.ANCIENT_DEBRIS, "ancient-debris");
    }

    /** Crops that count when fully grown. */
    public static final Set<Material> AGED_CROPS = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES,
            Material.BEETROOTS, Material.NETHER_WART, Material.COCOA, Material.TORCHFLOWER_CROP, Material.PITCHER_CROP);

    /** Crops that are a whole block, and only count when the world grew them. */
    public static final Set<Material> GROWN_BLOCKS = EnumSet.of(Material.MELON, Material.PUMPKIN);

    @Override
    public String describe() {
        return "an activity is worth its setting times how many, ores times how rare they are";
    }

    /** The ore group a block belongs to — "iron" for both iron ores — or empty for anything else. */
    public static Optional<String> oreOf(Material block) {
        return Optional.ofNullable(ORES.get(block));
    }

    public static Set<Material> ores() {
        return ORES.keySet();
    }

    public static int oreWeight(String group) {
        return ORE_WEIGHT.getOrDefault(group, 1);
    }

    /**
     * What an activity is worth, before the member's cap and any bonus. A deposit's amount is in whole thousands
     * of the currency — the bank works that out, since only it knows the currency.
     */
    public long worth(Activity activity, GuildsSettings base, GuildProgressSettings more) {
        long each = switch (activity.source()) {
            case PLAY -> base.xpPerMinute();
            case MONSTER -> base.xpPerMonster();
            case BOSS -> base.xpPerBoss();
            case ADVANCEMENT -> base.xpPerAdvancement();
            case MINE -> (long) more.perOre() * oreWeight(activity.what());
            case FISH -> more.perFish();
            case BREED -> more.perBreed();
            case TRADE -> more.perTrade();
            case HARVEST -> more.perHarvest();
            case ENCHANT -> more.perEnchantLevel();
            case RAID -> more.perRaid();
            case RIVAL_KILL -> more.perRivalKill();
            case DEPOSIT -> more.perThousandDeposited();
            case CONTRACT, STAFF -> 1;
        };
        long amount = activity.amount();
        return each <= 0 || amount <= 0 ? 0 : (each > Long.MAX_VALUE / amount ? Long.MAX_VALUE : each * amount);
    }
}
