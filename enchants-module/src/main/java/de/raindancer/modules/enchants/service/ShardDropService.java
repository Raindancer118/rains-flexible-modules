package de.raindancer.modules.enchants.service;

import de.raindancer.core.content.enchant.CustomEnchantments;
import de.raindancer.core.content.enchant.EnchantShards;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.enchants.EnchantAdvancements;
import de.raindancer.modules.enchants.EnchantsSettings;
import de.raindancer.modules.enchants.rules.ShardCap;
import de.raindancer.modules.enchants.rules.ShardRules;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/** Shards from monsters of the shard world: only ones that spawned by themselves, only to a player's kill, capped. */
public final class ShardDropService {

    /** A monster with at least this much health counts as strong: the rare shards come likelier. */
    private static final double STRONG_HEALTH = 40;

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
        if (!(mob instanceof Enemy) || !worlds.isShardWorld(mob.getWorld())
                || mob.getEntitySpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }
        EnchantsSettings config = settings.get();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextInt(100) >= config.shardDropPercent() || !cap.take(killer.getUniqueId(), config.shardsPerHour())) {
            return;
        }
        var health = mob.getAttribute(Attribute.MAX_HEALTH);
        String enchantment = ShardRules.pick(random, health != null && health.getValue() >= STRONG_HEALTH);
        if (CustomEnchantments.find(enchantment).isEmpty()) {
            return;
        }
        drops.add(EnchantShards.of(enchantment, 1));
        String name = PlainTextComponentSerializer.plainText().serialize(CustomEnchantments.find(enchantment).get().description());
        killer.sendActionBar(messages.get("enchants.shard-dropped", "name", name));
        advancements.foundShard(killer.getUniqueId());
    }

    public void forget(UUID player) {
        cap.forget(player);
    }
}
