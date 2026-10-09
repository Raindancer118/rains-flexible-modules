package de.raindancer.modules.economy.rules;

import de.raindancer.core.ui.choose.ItemSelection;
import de.raindancer.modules.economy.model.Bulk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which items get cheaper bought in quantity, and by how much — building blocks and iron, by default, never
 * the valuable ones: buying a lot of something is a builder's need, and a discount on diamonds is a discount
 * on the server's money.
 */
public final class BulkRule implements IEconomyRule {

    /** The most any bulk discount may be, whatever an owner writes. */
    public static final int HARD_CAP = 30;

    public Bulk forItem(String material, ItemSelection items, List<Bulk.Tier> tiers, int mostPercent) {
        int most = Math.clamp(mostPercent, 0, HARD_CAP);
        if (most == 0 || tiers.isEmpty() || !items.covers(material)) {
            return Bulk.NONE;
        }
        return new Bulk(tiers, most);
    }

    /** {@code "128 5"} — from 128, 5% off. Ascending; anything that is not two positive numbers is skipped. */
    public static List<Bulk.Tier> tiers(List<String> lines) {
        List<Bulk.Tier> read = new ArrayList<>();
        for (String line : lines == null ? List.<String>of() : lines) {
            String[] parts = line == null ? new String[0] : line.strip().split("\\s+");
            if (parts.length != 2) {
                continue;
            }
            try {
                int from = Integer.parseInt(parts[0]);
                int percent = Integer.parseInt(parts[1]);
                if (from > 0 && percent > 0) {
                    read.add(new Bulk.Tier(from, Math.min(percent, HARD_CAP)));
                }
            } catch (NumberFormatException notANumber) {
                // Skipped; the other tiers still count.
            }
        }
        read.sort(Comparator.comparingInt(Bulk.Tier::from));
        return read;
    }

    @Override
    public String describe() {
        return "how much cheaper building blocks and iron get bought in quantity, hard capped";
    }
}
