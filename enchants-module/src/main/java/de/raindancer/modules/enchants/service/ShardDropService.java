package de.raindancer.modules.enchants.service;

import de.raindancer.core.content.enchant.CustomEnchantments;
import de.raindancer.core.content.enchant.EnchantShards;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.enchants.EnchantAdvancements;
import de.raindancer.modules.enchants.EnchantsSettings;
import de.raindancer.modules.enchants.rules.ShardCap;
import de.raindancer.modules.enchants.rules.ShardRules;
import de.raindancer.modules.enchants.rules.ShardTier;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Shards from the creatures of the shard world: only ones that came by themselves (and so have a kind), only to a
 * player's kill, as many as
 * their kind drops ({@link ShardTier}), within the hourly cap.
 */
public final class ShardDropService {

    private final ShardWorldService worlds;
    private final Supplier<EnchantsSettings> settings;
    private final EnchantAdvancements advancements;
    private final Messages messages;
    private final ShardCap cap = new ShardCap(System::currentTimeMillis);

    public ShardDropService(ShardWorldService worlds, Supplier<EnchantsSettings> settings,
                            EnchantAdvancements advancements, Messages messages) {
        this.worlds = worlds;
        this.settings = settings;
        this.advancements = advancements;
        this.messages = messages;
    }

    public void died(LivingEntity mob, Player killer, List<ItemStack> drops) {
        java.util.Optional<ShardTier> kind = ShardMobService.tierOf(mob);
        if (kind.isEmpty() || !worlds.isShardWorld(mob.getWorld())) {
            return;
        }
        EnchantsSettings config = settings.get();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // The Crystal Wither always drops, and its ton of shards counts against nobody's hour.
        boolean boss = ShardMobService.isCrystalWither(mob);
        if (!boss && random.nextInt(100) >= config.shardDropPercent()) {
            return;
        }
        ShardTier tier = kind.get();
        Map<String, Integer> found = new LinkedHashMap<>();
        int count = boss ? de.raindancer.modules.enchants.rules.CrystalWither.shards(random) : tier.shards(random);
        int rareFactor = boss ? de.raindancer.modules.enchants.rules.CrystalWither.RARE_FACTOR : tier.rareFactor();
        for (int i = 0; i < count && (boss || cap.take(killer.getUniqueId(), config.shardsPerHour())); i++) {
            String enchantment = ShardRules.pick(random, rareFactor);
            if (CustomEnchantments.find(enchantment).isPresent()) {
                found.merge(enchantment, 1, Integer::sum);
            }
        }
        if (found.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>();
        found.forEach((enchantment, amount) -> {
            drops.add(EnchantShards.of(enchantment, amount));
            names.add(PlainTextComponentSerializer.plainText().serialize(CustomEnchantments.find(enchantment).get().description()));
        });
        int total = found.values().stream().mapToInt(Integer::intValue).sum();
        killer.sendActionBar(messages.get("enchants.shards-dropped", "amount", total, "names", String.join(", ", names)));
        advancements.foundShard(killer.getUniqueId());
    }

    public void forget(UUID player) {
        cap.forget(player);
    }
}
