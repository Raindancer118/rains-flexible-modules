package de.raindancer.modules.enchants.service;

import de.raindancer.core.content.enchant.CustomEnchantments;
import de.raindancer.core.content.enchant.RainsEnchants;
import de.raindancer.core.platform.util.ContainerDelivery;
import de.raindancer.core.platform.util.Inventories;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.enchants.EnchantAdvancements;
import de.raindancer.modules.enchants.model.ChestLink;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chest Link: a tool linked to a chest sends what it mines there, wherever the chest is. Drops are gathered
 * for a moment and sent together, so a vein is one delivery and not one per block. What does not arrive —
 * a full chest, a broken one, one on another server — comes to the player instead; nothing is lost.
 */
public final class ChestLinkService {

    public static final NamespacedKey LINK = new NamespacedKey("rainsenchants", "chest_link");
    private static final long HINT_EVERY_MILLIS = 30_000;

    private record Pending(ChestLink link, Location origin, List<ItemStack> items) {
    }

    private final Plugin plugin;
    private final EnchantAdvancements advancements;
    private final Messages messages;
    private final Effects effects;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> hinted = new ConcurrentHashMap<>();

    public ChestLinkService(Plugin plugin, EnchantAdvancements advancements, Messages messages, Effects effects) {
        this.plugin = plugin;
        this.advancements = advancements;
        this.messages = messages;
        this.effects = effects;
    }

    private static boolean linkable(Block block) {
        var state = block.getState(false);
        return state instanceof Chest || state instanceof Barrel || state instanceof ShulkerBox;
    }

    /**
     * Sneak + right-click on a chest with a Chest Link tool.
     *
     * @param allowed whether the player may open that chest — the claim plugins already answered it
     * @return true if this was a link attempt, to keep the click from doing anything else
     */
    public boolean link(Player player, Block block, boolean allowed) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!player.isSneaking() || CustomEnchantments.levelOn(tool, RainsEnchants.CHEST_LINK) < 1 || !linkable(block)) {
            return false;
        }
        if (!allowed) {
            messages.send(player, "enchants.link-denied");
            effects.play(player.getUniqueId(), Cues.NO);
            return true;
        }
        ChestLink link = new ChestLink(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        tool.editPersistentDataContainer(data -> data.set(LINK, PersistentDataType.STRING, link.write()));
        messages.send(player, "enchants.link-set", "x", String.valueOf(link.x()), "y", String.valueOf(link.y()),
                "z", String.valueOf(link.z()));
        effects.play(player.getUniqueId(), Cues.OK);
        return true;
    }

    /** Takes the drops of a linked tool off the ground and queues them for the chest. */
    public void collect(Player player, Location origin, List<Item> drops) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (CustomEnchantments.levelOn(tool, RainsEnchants.CHEST_LINK) < 1 || drops.isEmpty()) {
            return;
        }
        Optional<ChestLink> link = ChestLink.read(tool.getPersistentDataContainer().get(LINK, PersistentDataType.STRING));
        if (link.isEmpty()) {
            long now = System.currentTimeMillis();
            if (now - hinted.getOrDefault(player.getUniqueId(), 0L) > HINT_EVERY_MILLIS) {
                hinted.put(player.getUniqueId(), now);
                messages.send(player, "enchants.link-missing");
            }
            return;
        }
        List<ItemStack> items = new ArrayList<>();
        drops.forEach(item -> items.add(item.getItemStack()));
        drops.clear();
        pending.compute(player.getUniqueId(), (id, old) -> {
            if (old == null || !old.link().equals(link.get())) {
                if (old != null) {
                    send(id, old);
                }
                return new Pending(link.get(), origin, items);
            }
            old.items().addAll(items);
            return old;
        });
    }

    /** Sends everything gathered so far. */
    public void flush() {
        for (UUID player : List.copyOf(pending.keySet())) {
            Pending batch = pending.remove(player);
            if (batch != null) {
                send(player, batch);
            }
        }
    }

    private void send(UUID player, Pending batch) {
        World world = Bukkit.getWorld(batch.link().world());
        if (world == null) {
            giveBack(player, batch, batch.items(), "enchants.link-elsewhere");
            return;
        }
        Location chest = new Location(world, batch.link().x(), batch.link().y(), batch.link().z());
        ContainerDelivery.deliver(plugin, chest, batch.items()).thenAccept(rest -> {
            if (rest.isEmpty()) {
                Player online = Bukkit.getPlayer(player);
                if (online != null) {
                    Scheduling.entity(plugin, online, () -> advancements.used(player, RainsEnchants.CHEST_LINK));
                }
            } else {
                giveBack(player, batch, rest, "enchants.link-missed");
            }
        });
    }

    private void giveBack(UUID id, Pending batch, List<ItemStack> items, String why) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) {
            Scheduling.region(plugin, batch.origin(), () -> items.forEach(item ->
                    batch.origin().getWorld().dropItemNaturally(batch.origin(), item)));
            return;
        }
        Scheduling.entity(plugin, player, () -> {
            Inventories.giveOrDrop(player, items);
            long now = System.currentTimeMillis();
            if (now - hinted.getOrDefault(id, 0L) > HINT_EVERY_MILLIS) {
                hinted.put(id, now);
                messages.send(player, why);
            }
        });
    }

    public void forget(UUID player) {
        Pending batch = pending.remove(player);
        if (batch != null) {
            send(player, batch);
        }
        hinted.remove(player);
    }
}
