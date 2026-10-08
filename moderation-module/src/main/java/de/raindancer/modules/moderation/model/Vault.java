package de.raindancer.modules.moderation.model;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One operator's vault: a list of stacks, up to a capacity, and four armour stands.
 *
 * <p>Nothing outside ever holds a stack that is in here — everything goes in and comes out as a copy —
 * so a screen showing the vault cannot change it by accident, and a stale screen cannot take the same
 * stack twice: {@link #take} only hands something out when the entry is still the one that was drawn.
 *
 * <p>Synchronised, because the owner's own thread changes it while the save runs on another.
 */
public final class Vault {

    /** One page of the screen. */
    public static final int PER_PAGE = 36;
    /** How many pages a vault has. */
    public static final int PAGES = 6;
    public static final int CAPACITY = PER_PAGE * PAGES;

    /**
     * Everything that has to reach the disk, as of one moment.
     *
     * @param kept    encoded items this server could not read when the vault was loaded — written back
     *                untouched, so a missing mod or a downgrade does not quietly delete them
     * @param version moves on with every change, so a store can tell an older picture from a newer one
     */
    public record Contents(List<ItemStack> items, Map<ArmourPiece, ItemStack> armour, List<String> kept,
                           long version) {
    }

    private final int capacity;
    private final List<ItemStack> items = new ArrayList<>();
    private final Map<ArmourPiece, ItemStack> armour = new EnumMap<>(ArmourPiece.class);
    private final List<String> kept = new ArrayList<>();
    private long version;

    public Vault(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** Rebuilds a vault from what was on disk, without counting as a change. */
    public static Vault restored(int capacity, List<ItemStack> items, Map<ArmourPiece, ItemStack> armour,
                                 List<String> kept) {
        Vault vault = new Vault(capacity);
        for (ItemStack item : items) {
            if (!isNothing(item)) {
                // Not deposit(): a vault saved under a bigger capacity keeps everything, and stacks are
                // left as the owner arranged them.
                vault.items.add(item.clone());
            }
        }
        armour.forEach((piece, item) -> {
            if (!isNothing(item)) {
                vault.armour.put(piece, item.clone());
            }
        });
        vault.kept.addAll(kept);
        return vault;
    }

    /**
     * Puts a copy of this in: a piece of armour on its empty stand, everything else topping up similar
     * stacks first and then starting new ones while there is room.
     *
     * @return how many were taken; the caller removes exactly that many from wherever it came from
     */
    public synchronized int deposit(ItemStack item) {
        if (isNothing(item)) {
            return 0;
        }
        int left = item.getAmount();
        int max = Math.max(1, item.getMaxStackSize());

        Optional<ArmourPiece> piece = ArmourPiece.of(item.getType());
        if (piece.isPresent() && !armour.containsKey(piece.get())) {
            int placed = Math.min(left, max);
            armour.put(piece.get(), item.asQuantity(placed));
            left -= placed;
        }
        for (ItemStack stack : items) {
            if (left <= 0) {
                break;
            }
            if (stack.getAmount() < max && stack.isSimilar(item)) {
                int added = Math.min(left, max - stack.getAmount());
                stack.setAmount(stack.getAmount() + added);
                left -= added;
            }
        }
        while (left > 0 && items.size() < capacity) {
            int placed = Math.min(left, max);
            items.add(item.asQuantity(placed));
            left -= placed;
        }
        int accepted = item.getAmount() - left;
        if (accepted > 0) {
            version++;
        }
        return accepted;
    }

    /**
     * Takes an entry out, if it is still exactly what the screen showed.
     *
     * @return the stack, or null when the entry has gone or changed since it was drawn
     */
    public synchronized ItemStack take(int index, ItemStack shown) {
        if (index < 0 || index >= items.size() || shown == null) {
            return null;
        }
        ItemStack there = items.get(index);
        if (!there.isSimilar(shown) || there.getAmount() != shown.getAmount()) {
            return null;
        }
        items.remove(index);
        version++;
        return there;
    }

    /** Takes whatever is on this stand off it, or null when it is empty. */
    public synchronized ItemStack takeArmour(ArmourPiece piece) {
        ItemStack there = armour.remove(piece);
        if (there != null) {
            version++;
        }
        return there;
    }

    /**
     * Puts this on the stand and hands back what was on it — or, when it is not that kind of armour,
     * hands the item itself straight back and changes nothing.
     *
     * @param item null to empty the stand
     */
    public synchronized ItemStack swapArmour(ArmourPiece piece, ItemStack item) {
        if (!isNothing(item) && ArmourPiece.of(item.getType()).orElse(null) != piece) {
            return item;
        }
        ItemStack before = isNothing(item) ? armour.remove(piece) : armour.put(piece, item.clone());
        if (before != null || !isNothing(item)) {
            version++;
        }
        return before;
    }

    public synchronized List<ItemStack> items() {
        List<ItemStack> copies = new ArrayList<>(items.size());
        items.forEach(item -> copies.add(item.clone()));
        return copies;
    }

    public synchronized Optional<ItemStack> armour(ArmourPiece piece) {
        ItemStack there = armour.get(piece);
        return there == null ? Optional.empty() : Optional.of(there.clone());
    }

    public synchronized boolean hasArmour() {
        return !armour.isEmpty();
    }

    public synchronized boolean isEmpty() {
        return items.isEmpty() && armour.isEmpty();
    }

    public synchronized int size() {
        return items.size();
    }

    public int capacity() {
        return capacity;
    }

    public synchronized Contents contents() {
        Map<ArmourPiece, ItemStack> stands = new EnumMap<>(ArmourPiece.class);
        armour.forEach((piece, item) -> stands.put(piece, item.clone()));
        return new Contents(items(), Collections.unmodifiableMap(stands), List.copyOf(kept), version);
    }

    private static boolean isNothing(ItemStack item) {
        return item == null || item.isEmpty();
    }
}
