package de.raindancer.modules.arena.service;

import de.raindancer.core.data.nbt.ItemText;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.arena.ArenaSettings;
import de.raindancer.modules.arena.model.Kit;
import de.raindancer.modules.arena.store.KitBook;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Giving a fighter their kit, and making kits from what an admin holds. */
public final class KitService implements IArenaService {

    private static final LogChannel log = Log.of("arena");

    private final KitBook kits;
    private final ItemText codec;

    public KitService(KitBook kits, ItemText codec) {
        this.kits = kits;
        this.codec = codec;
    }

    @Override
    public void settings(ArenaSettings settings) {
        // Kits are their own file; nothing in the settings changes how one is handed out.
    }

    public List<Kit> all() {
        return kits.all();
    }

    public Optional<Kit> get(String id) {
        return kits.get(id);
    }

    /** The defaults, where the owner has none of that name. */
    public void seed(List<Kit> defaults) {
        defaults.forEach(kits::putIfAbsent);
    }

    /** Everything they hold is replaced by the kit. On the player's own thread. */
    public void give(Player player, Kit kit) {
        ItemStack[] contents = new ItemStack[player.getInventory().getContents().length];
        for (int slot = 0; slot < Math.min(contents.length, kit.items().size()); slot++) {
            String line = kit.items().get(slot);
            contents[slot] = line == null || line.isEmpty() ? null : codec.read(line);
        }
        player.getInventory().setContents(contents);
        for (String effect : kit.effects()) {
            effect(effect).ifPresent(player::addPotionEffect);
        }
    }

    /** Gives a copy of somebody's own gear, slot for slot, as the survival server sent it. */
    public void giveCopy(Player player, List<String> slots) {
        ItemStack[] contents = new ItemStack[player.getInventory().getContents().length];
        for (int slot = 0; slot < Math.min(contents.length, slots.size()); slot++) {
            String line = slots.get(slot);
            contents[slot] = line == null || line.isEmpty() ? null : codec.read(line);
        }
        player.getInventory().setContents(contents);
    }

    /** A kit of exactly what this player holds now. */
    public boolean saveFrom(Player player, String id, String name, String icon) {
        List<String> slots = new ArrayList<>(Collections.nCopies(Kit.SLOTS, ""));
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < Math.min(Kit.SLOTS, contents.length); slot++) {
            String line = contents[slot] == null ? null : codec.write(contents[slot]);
            slots.set(slot, line == null ? "" : line);
        }
        return kits.put(new Kit(id, name, icon, slots, List.of(), false));
    }

    public boolean remove(String id) {
        return kits.remove(id);
    }

    /** {@code minecraft:speed 600 0} — type, ticks, amplifier. */
    private static Optional<PotionEffect> effect(String line) {
        String[] parts = line.trim().split("\\s+");
        try {
            NamespacedKey key = NamespacedKey.fromString(parts[0]);
            PotionEffectType type = key == null ? null : Registry.EFFECT.get(key);
            if (type == null) {
                return Optional.empty();
            }
            return Optional.of(new PotionEffect(type, parts.length > 1 ? Integer.parseInt(parts[1]) : 600,
                    parts.length > 2 ? Integer.parseInt(parts[2]) : 0));
        } catch (RuntimeException broken) {
            log.warn("The kit effect '{}' could not be read and is left out.", line);
            return Optional.empty();
        }
    }
}
