package de.raindancer.modules.veintoggle.service;

import de.raindancer.modules.veintoggle.model.BlockKey;
import de.raindancer.modules.veintoggle.model.BrokenBlock;
import de.raindancer.modules.veintoggle.model.VeinOperation;
import de.raindancer.modules.veintoggle.rules.UndoRule;
import de.raindancer.modules.veintoggle.store.RestoredBlocks;
import de.raindancer.modules.veintoggle.store.VeinHistory;
import org.bukkit.Server;
import org.bukkit.SoundGroup;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts a vein back. Each block costs exactly what it dropped, taken from the items still lying around
 * the vein first and then from the player's inventory; a block that cannot be paid for stays mined.
 *
 * <p>Must run on the thread owning the vein's region (and the player, who has to be standing near it).
 * Blocks and items in another region are left alone rather than touched from the wrong thread.
 */
public final class VeinUndoService {

    /** How far around the vein dropped items are looked for — Veinminer drops at the block or at the source. */
    private static final double GROUND_MARGIN = 4.0;

    public record Outcome(int restored, int inTheWay, int unpaid) {
    }

    private final Server server;
    private final UndoRule rule;
    private final VeinHistory history;
    private final RestoredBlocks restored;

    public VeinUndoService(Server server, UndoRule rule, VeinHistory history, RestoredBlocks restored) {
        this.server = server;
        this.rule = rule;
        this.history = history;
        this.restored = restored;
    }

    public Outcome undo(Player player, VeinOperation vein) {
        World world = player.getWorld();
        List<BrokenBlock> blocks = vein.blocks();

        // Items have no usable key, so each kind is the first stack of it seen, and similar ones count as it.
        List<ItemStack> kinds = new ArrayList<>();
        Map<BrokenBlock, Map<Integer, Integer>> needs = new IdentityHashMap<>();
        for (BrokenBlock block : blocks) {
            Map<Integer, Integer> need = new HashMap<>();
            for (ItemStack drop : block.drops()) {
                need.merge(kindOf(kinds, drop), drop.getAmount(), Integer::sum);
            }
            needs.put(block, need);
        }

        List<Item> ground = groundAround(world, vein, blocks);
        ItemStack[] storage = player.getInventory().getStorageContents();
        Map<Integer, Integer> available = new HashMap<>();
        for (int kind = 0; kind < kinds.size(); kind++) {
            ItemStack wanted = kinds.get(kind);
            int count = 0;
            for (Item item : ground) {
                ItemStack lying = item.getItemStack();
                if (wanted.isSimilar(lying)) {
                    count += lying.getAmount();
                }
            }
            for (ItemStack held : storage) {
                if (held != null && wanted.isSimilar(held)) {
                    count += held.getAmount();
                }
            }
            available.put(kind, count);
        }

        UndoRule.Plan<BrokenBlock, Integer> plan = rule.plan(blocks, block -> free(world, block.at()),
                needs::get, available);

        plan.toTake().forEach((kind, amount) -> {
            int left = takeFromGround(ground, kinds.get(kind), amount);
            takeFromStorage(storage, kinds.get(kind), left);
        });
        if (!plan.toTake().isEmpty()) {
            player.getInventory().setStorageContents(storage);
        }
        for (BrokenBlock block : plan.restore()) {
            BlockKey at = block.at();
            // No physics: a vein of gravel put back must stay where it was, not pour into the tunnel.
            world.getBlockAt(at.x(), at.y(), at.z()).setBlockData(block.data(), false);
            restored.mark(at, block.data(), block.drops());
        }
        if (!plan.restore().isEmpty()) {
            BrokenBlock first = plan.restore().getFirst();
            SoundGroup sounds = first.data().getSoundGroup();
            if (sounds != null) {
                world.playSound(first.at().centre(world), sounds.getPlaceSound(), 1.0f, 1.0f);
            }
        }

        vein.removeAll(plan.restore());
        if (vein.isEmpty()) {
            history.remove(player.getUniqueId(), vein);
        }
        return new Outcome(plan.restore().size(), plan.inTheWay().size(), plan.unpaid().size());
    }

    private static int kindOf(List<ItemStack> kinds, ItemStack stack) {
        for (int kind = 0; kind < kinds.size(); kind++) {
            if (kinds.get(kind).isSimilar(stack)) {
                return kind;
            }
        }
        kinds.add(stack);
        return kinds.size() - 1;
    }

    private boolean free(World world, BlockKey at) {
        int chunkX = at.x() >> 4;
        int chunkZ = at.z() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ) || !server.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            return false;
        }
        Block now = world.getBlockAt(at.x(), at.y(), at.z());
        return now.isReplaceable();
    }

    private List<Item> groundAround(World world, VeinOperation vein, List<BrokenBlock> blocks) {
        double reach = 0;
        for (BrokenBlock block : blocks) {
            reach = Math.max(reach, block.at().distance(vein.source()));
        }
        return world.getNearbyEntitiesByType(Item.class, vein.source().centre(world), reach + GROUND_MARGIN)
                .stream()
                .filter(Item::isValid)
                .filter(server::isOwnedByCurrentRegion)
                .toList();
    }

    /** @return how many are still owed */
    private static int takeFromGround(List<Item> ground, ItemStack kind, int amount) {
        for (Item item : ground) {
            if (amount == 0) {
                break;
            }
            ItemStack lying = item.getItemStack();
            if (!item.isValid() || !kind.isSimilar(lying)) {
                continue;
            }
            int taken = Math.min(amount, lying.getAmount());
            amount -= taken;
            if (taken == lying.getAmount()) {
                item.remove();
            } else {
                lying.setAmount(lying.getAmount() - taken);
                item.setItemStack(lying);
            }
        }
        return amount;
    }

    private static void takeFromStorage(ItemStack[] storage, ItemStack kind, int amount) {
        for (int slot = 0; slot < storage.length && amount > 0; slot++) {
            ItemStack held = storage[slot];
            if (held == null || !kind.isSimilar(held)) {
                continue;
            }
            int taken = Math.min(amount, held.getAmount());
            amount -= taken;
            if (taken == held.getAmount()) {
                storage[slot] = null;
            } else {
                held.setAmount(held.getAmount() - taken);
            }
        }
    }
}
