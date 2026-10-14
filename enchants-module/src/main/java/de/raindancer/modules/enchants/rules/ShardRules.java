package de.raindancer.modules.enchants.rules;

import de.raindancer.core.content.enchant.RainsEnchants;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.random.RandomGenerator;

/** Which enchantment's shard a monster drops: the useful-every-day ones often, the strong ones rarely. */
public final class ShardRules {

    private static final int COMMON = 10;
    private static final int UNCOMMON = 5;
    private static final int RARE = 2;
    /** A strong monster (a lot of health) makes the rare shards this much likelier. */
    private static final int STRONG_RARE_FACTOR = 3;

    private static final Map<String, Integer> WEIGHTS = new LinkedHashMap<>();

    static {
        for (String key : new String[]{RainsEnchants.NIGHT_VISION, RainsEnchants.DOUBLE_JUMP, RainsEnchants.SWIFTNESS,
                RainsEnchants.HASTE, RainsEnchants.WISDOM, RainsEnchants.HARVESTER, RainsEnchants.SMELTING,
                RainsEnchants.TELEKINESIS}) {
            WEIGHTS.put(key, COMMON);
        }
        for (String key : new String[]{RainsEnchants.VEINMINER, RainsEnchants.EXCAVATOR, RainsEnchants.CHEST_LINK,
                RainsEnchants.AUTO_SELL, RainsEnchants.BOOST}) {
            WEIGHTS.put(key, UNCOMMON);
        }
        for (String key : new String[]{RainsEnchants.FLIGHT, RainsEnchants.LAUNCH, RainsEnchants.SOULBOUND}) {
            WEIGHTS.put(key, RARE);
        }
    }

    private ShardRules() {
    }

    public static int weight(String enchantment) {
        return WEIGHTS.getOrDefault(enchantment, 0);
    }

    public static String pick(RandomGenerator random, boolean strong) {
        int total = 0;
        for (int weight : WEIGHTS.values()) {
            total += adjusted(weight, strong);
        }
        int roll = random.nextInt(total);
        for (Map.Entry<String, Integer> each : WEIGHTS.entrySet()) {
            roll -= adjusted(each.getValue(), strong);
            if (roll < 0) {
                return each.getKey();
            }
        }
        throw new IllegalStateException("The weights do not add up.");
    }

    private static int adjusted(int weight, boolean strong) {
        return strong && weight == RARE ? weight * STRONG_RARE_FACTOR : weight;
    }
}
