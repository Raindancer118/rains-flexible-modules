package de.raindancer.modules.guilds.service;

import de.raindancer.core.content.enchant.CustomEnchantments;
import de.raindancer.core.content.enchant.EnchantShards;
import de.raindancer.core.content.enchant.EnchantUnlocks;
import de.raindancer.core.platform.util.Inventories;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.guilds.GuildsSettings;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildEvent;
import de.raindancer.modules.guilds.store.GuildBook;
import de.raindancer.modules.guilds.store.ShardBook;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * A guild unlocking Rain's enchantments: its members pay in the enchantment's shards until there are enough,
 * then every member may buy the enchantment's books — the guilds answer Core's {@link EnchantUnlocks} for it.
 */
public final class ShardService implements EnchantUnlocks.Source {

    private static final String NAMESPACE = "rainsenchants";

    private final Plugin plugin;
    private final GuildBook guilds;
    private final ShardBook shards;
    private final GuildNotifier notifier;
    private final Supplier<GuildsSettings> settings;

    public ShardService(Plugin plugin, GuildBook guilds, ShardBook shards, GuildNotifier notifier,
                        Supplier<GuildsSettings> settings) {
        this.plugin = plugin;
        this.guilds = guilds;
        this.shards = shards;
        this.notifier = notifier;
        this.settings = settings;
    }

    @Override
    public boolean unlocked(UUID player, String enchantment) {
        return guilds.guildOf(player).map(guild -> shards.unlocked(guild.id().value(), enchantment)).orElse(false);
    }

    /** Rain's enchantments the server has, in name order. */
    public List<String> enchantments() {
        List<String> keys = new ArrayList<>();
        RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).forEach(enchantment -> {
            if (enchantment.getKey().getNamespace().equals(NAMESPACE)) {
                keys.add(enchantment.getKey().asString());
            }
        });
        keys.sort(Comparator.comparing(ShardService::name));
        return keys;
    }

    public static String name(String enchantment) {
        return CustomEnchantments.find(enchantment)
                .map(found -> PlainTextComponentSerializer.plainText().serialize(found.description()))
                .orElse(enchantment);
    }

    public int needed() {
        return settings.get().unlockShards();
    }

    public ShardBook.Progress progress(Guild guild, String enchantment) {
        return shards.progress(guild.id().value(), enchantment);
    }

    /**
     * Pays in every shard of this enchantment the player carries, up to what is still needed. The shards leave
     * at once; the guild's records are written off the server's thread, and should that fail they come back.
     */
    public void payIn(Player player, String enchantment, Runnable afterwards) {
        Optional<Guild> found = guilds.guildOf(player.getUniqueId());
        String name = name(enchantment);
        if (found.isEmpty()) {
            notifier.tell(player.getUniqueId(), "guilds.enchant-no-guild");
            return;
        }
        Guild guild = found.get();
        ShardBook.Progress progress = shards.progress(guild.id().value(), enchantment);
        if (progress.unlocked()) {
            notifier.tell(player.getUniqueId(), "guilds.enchant-already", "name", name);
            return;
        }
        int needed = needed();
        int giving = Math.min(EnchantShards.count(player.getInventory(), enchantment), Math.max(0, needed - progress.paid()));
        if (giving <= 0 || !EnchantShards.take(player.getInventory(), enchantment, giving)) {
            notifier.tell(player.getUniqueId(), "guilds.enchant-no-shards", "name", name);
            return;
        }
        UUID payer = player.getUniqueId();
        Scheduling.async(plugin, () -> {
            Optional<ShardBook.Paid> paid = shards.pay(guild.id().value(), enchantment, giving, needed);
            Scheduling.entity(plugin, player, () -> {
                if (paid.isEmpty()) {
                    List<ItemStack> back = new ArrayList<>();
                    for (int left = giving; left > 0; left -= 64) {
                        back.add(EnchantShards.of(enchantment, Math.min(64, left)));
                    }
                    Inventories.giveOrDrop(player, back);
                    notifier.tell(payer, "guilds.enchant-failed");
                    return;
                }
                guilds.log(GuildEvent.of(guild.id().value(), System.currentTimeMillis(), GuildEvent.Kind.SHARDS, payer,
                        null, giving, name));
                notifier.tell(payer, "guilds.enchant-paid", "amount", giving, "name", name,
                        "paid", Math.min(paid.get().paid(), needed), "needed", needed);
                if (paid.get().justUnlocked()) {
                    guilds.log(GuildEvent.of(guild.id().value(), System.currentTimeMillis(), GuildEvent.Kind.ENCHANTMENT,
                            payer, null, 0, name));
                    notifier.members(guild, "guilds.enchant-unlocked", "name", name);
                }
                afterwards.run();
            });
        });
    }
}
