package de.raindancer.modules.essentials.rules;

import de.raindancer.core.platform.rule.AbstractRule;
import de.raindancer.core.platform.rule.Verdict;

import java.util.Set;

/**
 * What somebody in admin mode may put items into: only windows that hand everything back when they close,
 * and their own (admin) ender chest. Anything that keeps an item, or trades it away, would carry admin
 * items out to the survival side — by name, so a window type added later is refused until it is listed.
 */
public final class AdminKeepApartRule extends AbstractRule<String> {

    private static final Set<String> HANDS_BACK = Set.of("CRAFTING", "CREATIVE", "PLAYER", "WORKBENCH",
            "ENDER_CHEST", "ANVIL", "ENCHANTING", "GRINDSTONE", "SMITHING", "STONECUTTER", "LOOM", "CARTOGRAPHY");

    private static final Set<String> TAKES_FROM_HAND = Set.of("JUKEBOX", "LECTERN", "DECORATED_POT", "CAMPFIRE",
            "SOUL_CAMPFIRE", "FLOWER_POT", "COMPOSTER", "VAULT", "CRAFTER");

    public AdminKeepApartRule() {
        super("admin items stay on the admin side: no storage, trading or display blocks");
    }

    /** @param inventoryType an {@code InventoryType} name */
    public boolean mayUseWindow(String inventoryType) {
        return HANDS_BACK.contains(inventoryType);
    }

    /**
     * The same, when the owner lets admin mode use containers: every window then, except trading with villagers,
     * which is not working on the server's things but spending on them.
     */
    public boolean mayUseWindow(String inventoryType, boolean containers) {
        return containers ? !"MERCHANT".equals(inventoryType) : mayUseWindow(inventoryType);
    }

    /**
     * Whether a window may be used by somebody keeping admin items apart.
     *
     * @param own     whether the window's inventory is the viewer's own — a player inventory or an ender chest is
     *                only ever theirs; somebody else's (looked into by staff) never takes admin items
     * @param inWorld whether a block or an entity in the world holds it — the only containers "use containers" opens
     */
    public boolean mayUse(String inventoryType, boolean containers, boolean own, boolean inWorld) {
        if ("PLAYER".equals(inventoryType) || "ENDER_CHEST".equals(inventoryType)) {
            return own;
        }
        if (mayUseWindow(inventoryType)) {
            return true;
        }
        return containers && inWorld && !"MERCHANT".equals(inventoryType);
    }

    /** What went into a container and what came out, by item name. */
    public record Changes(java.util.Map<String, Integer> in, java.util.Map<String, Integer> out) {

        public boolean none() {
            return in.isEmpty() && out.isEmpty();
        }

        /** "64 Diamond, 1 Nether Star", biggest first. */
        public String says(java.util.Map<String, Integer> which) {
            return which.entrySet().stream()
                    .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed()
                            .thenComparing(java.util.Map.Entry.comparingByKey()))
                    .map(each -> each.getValue() + " " + readable(each.getKey()))
                    .collect(java.util.stream.Collectors.joining(", "));
        }
    }

    /** "DIAMOND_SWORD (enchanted #1a2b)" → "Diamond Sword (enchanted #1a2b)". */
    static String readable(String key) {
        int mark = key.indexOf(" (");
        return mark < 0 ? de.raindancer.core.ui.choose.Catalogue.readable(key)
                : de.raindancer.core.ui.choose.Catalogue.readable(key.substring(0, mark)) + key.substring(mark);
    }

    /** The difference between two counts of a container's items, by item name. */
    public Changes changes(java.util.Map<String, Integer> before, java.util.Map<String, Integer> after) {
        java.util.Map<String, Integer> in = new java.util.TreeMap<>();
        java.util.Map<String, Integer> out = new java.util.TreeMap<>();
        java.util.Set<String> names = new java.util.TreeSet<>(before.keySet());
        names.addAll(after.keySet());
        for (String name : names) {
            int delta = after.getOrDefault(name, 0) - before.getOrDefault(name, 0);
            if (delta > 0) {
                in.put(name, delta);
            } else if (delta < 0) {
                out.put(name, -delta);
            }
        }
        return new Changes(in, out);
    }

    /** @param material the clicked block's material name */
    public boolean mayUseBlock(String material) {
        return !TAKES_FROM_HAND.contains(material) && !material.endsWith("SHELF");
    }

    @Override
    public Verdict judge(String inventoryType) {
        return mayUseWindow(inventoryType) ? Verdict.allowed() : Verdict.refused("essentials.admin.kept-apart");
    }
}
