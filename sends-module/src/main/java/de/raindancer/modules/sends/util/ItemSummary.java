package de.raindancer.modules.sends.util;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** What a send holds, in a line a player can read: {@code 3× Diamond, 64× Cobblestone, 2 more kinds}. */
public final class ItemSummary {

    private ItemSummary() {
    }

    /** One kind of thing and how many of it. */
    public record Line(String name, int amount) {
    }

    /** Same-named stacks added together, in the order first seen. */
    public static List<Line> lines(List<Line> stacks) {
        Map<String, Integer> byName = new LinkedHashMap<>();
        for (Line stack : stacks) {
            byName.merge(stack.name(), stack.amount(), Integer::sum);
        }
        List<Line> lines = new ArrayList<>(byName.size());
        byName.forEach((name, amount) -> lines.add(new Line(name, amount)));
        return lines;
    }

    public static String describe(List<Line> stacks, int mostKinds) {
        List<Line> lines = lines(stacks);
        List<String> parts = new ArrayList<>();
        for (int at = 0; at < Math.min(lines.size(), mostKinds); at++) {
            parts.add(lines.get(at).amount() + "× " + lines.get(at).name());
        }
        int more = lines.size() - mostKinds;
        if (more > 0) {
            parts.add(more + " more kind" + (more == 1 ? "" : "s"));
        }
        return String.join(", ", parts);
    }

    public static String describeItems(List<ItemStack> items, int mostKinds) {
        return describe(items.stream().map(ItemSummary::lineOf).toList(), mostKinds);
    }

    public static Line lineOf(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return new Line(PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()),
                    item.getAmount());
        }
        return new Line(humanise(item.getType().name()), item.getAmount());
    }

    static String humanise(String materialName) {
        StringBuilder out = new StringBuilder();
        for (String word : materialName.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}
