package de.raindancer.modules.arenagate.service;

import com.google.gson.JsonArray;
import de.raindancer.core.data.nbt.ItemText;
import de.raindancer.core.moderation.invsee.Carried;
import de.raindancer.core.moderation.invsee.Inventories;
import de.raindancer.core.moderation.invsee.Section;
import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A copy of what somebody carries here, for a fight in their own gear on the arena server. Read from the live inventory
 * when they are online, from the file the server saved them in when they are already over there — through Core's
 * inventory reading, which knows both. A copy only: nothing here is taken, and nothing ever comes back.
 */
public final class GearService {

    private static final int SLOTS = 41;

    private final Plugin plugin;
    private final Server server;
    private final Inventories inventories;
    private final ItemText codec;

    public GearService(Plugin plugin, Server server, Inventories inventories, ItemText codec) {
        this.plugin = plugin;
        this.server = server;
        this.inventories = inventories;
        this.codec = codec;
    }

    /** Called off the server thread (a link or wire worker), so waiting here for it is safe. */
    public Map<String, String> copy(Map<String, String> data) {
        try {
            UUID player = UUID.fromString(data.get("player"));
            List<String> slots = read(player).get(5, TimeUnit.SECONDS).orElse(null);
            if (slots == null) {
                return Map.of("ok", "false");
            }
            JsonArray array = new JsonArray();
            slots.forEach(array::add);
            return Map.of("ok", "true", "items", array.toString());
        } catch (Exception failed) {
            return Map.of("ok", "false");
        }
    }

    private CompletableFuture<Optional<List<String>>> read(UUID player) {
        CompletableFuture<Optional<List<String>>> answer = new CompletableFuture<>();
        Player online = server.getPlayer(player);
        if (online != null) {
            // A player on their way to the arena is being removed right now: their scheduler then drops the task and
            // the answer would never come, so the saved file — written as they leave — stands in.
            Scheduling.entity(plugin, online, () -> {
                List<String> slots = new ArrayList<>();
                for (ItemStack item : online.getInventory().getContents()) {
                    String line = item == null ? null : codec.write(item);
                    slots.add(line == null ? "" : line);
                }
                answer.complete(Optional.of(slots));
            }, () -> CompletableFuture.runAsync(() -> answer.complete(savedAfterLeaving(player)),
                    CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS)));
            return answer;
        }
        Optional<Carried<ItemStack>> carried = inventories.read(player);
        answer.complete(carried.map(this::slots));
        return answer;
    }

    private Optional<List<String>> savedAfterLeaving(UUID player) {
        for (int attempt = 0; attempt < 8; attempt++) {
            Optional<List<String>> found = inventories.read(player).map(this::slots);
            if (found.isPresent()) {
                return found;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return Optional.empty();
    }

    /** Core's sections back into inventory slot order: hotbar, backpack, boots to helmet, off hand. */
    private List<String> slots(Carried<ItemStack> carried) {
        List<String> slots = new ArrayList<>(Collections.nCopies(SLOTS, ""));
        put(slots, carried.allOf(Section.HOTBAR), 0);
        put(slots, carried.allOf(Section.STORAGE), 9);
        List<ItemStack> armour = carried.allOf(Section.ARMOUR);
        for (int index = 0; index < Math.min(4, armour.size()); index++) {
            // Core lists armour helmet first; the inventory stores it boots first.
            slots.set(39 - index, encode(armour.get(index)));
        }
        put(slots, carried.allOf(Section.OFF_HAND), 40);
        return slots;
    }

    private void put(List<String> slots, List<ItemStack> items, int from) {
        for (int index = 0; index < items.size() && from + index < SLOTS; index++) {
            slots.set(from + index, encode(items.get(index)));
        }
    }

    private String encode(ItemStack item) {
        String line = item == null ? null : codec.write(item);
        return line == null ? "" : line;
    }
}
