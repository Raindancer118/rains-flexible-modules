package de.raindancer.modules.economy.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** One line of a pack: so many of one plain item. */
public record PackItem(String material, int amount) {

    public PackItem {
        material = material == null ? "" : material.toUpperCase(Locale.ROOT);
    }

    /** As written on a pack item: {@code TORCH*32;WHITE_BED*1}. */
    public static String encode(List<PackItem> contents) {
        return contents.stream().map(each -> each.material() + "*" + each.amount()).collect(Collectors.joining(";"));
    }

    /** Read back, skipping anything that is not a material and a positive amount. */
    public static List<PackItem> decode(String written) {
        List<PackItem> read = new ArrayList<>();
        if (written == null) {
            return read;
        }
        for (String part : written.split(";")) {
            int star = part.lastIndexOf('*');
            if (star <= 0) {
                continue;
            }
            try {
                int amount = Integer.parseInt(part.substring(star + 1).strip());
                if (amount > 0) {
                    read.add(new PackItem(part.substring(0, star).strip(), amount));
                }
            } catch (NumberFormatException notANumber) {
                // A line nobody can read is left out rather than guessed at.
            }
        }
        return read;
    }
}
