package de.raindancer.modules.economy.util;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.SellBreakdown;
import de.raindancer.modules.economy.model.YourPrice;

import java.util.ArrayList;
import java.util.List;

/**
 * A price as one player sees it: the shop's crossed out next to theirs, and on lines under each price what
 * made it differ — a role and bulk under buying; a role, the economy, selling a lot lately and the cap under selling.
 */
public final class PriceLines {

    private PriceLines() {
    }

    /** @param everybody what the line costs anybody; @param theirs what it costs this player */
    public static String amount(Currency currency, Money everybody, Money theirs) {
        String mine = Mini.of(currency.render(theirs));
        if (everybody.equals(theirs)) {
            return mine;
        }
        return "<dark_gray><st>" + Mini.of(currency.render(everybody)) + "</st> " + mine;
    }

    private static final String BULLET = "<dark_gray> • ";

    /** What made this player's buy price differ, for the lines under "Buy:" — their role, then buying in bulk. */
    public static List<String> buyNotes(YourPrice yours) {
        List<String> lines = new ArrayList<>();
        int buy = yours.buyChange().percent();
        if (yours.shop().buyable() && buy != 0) {
            lines.add(BULLET + "<dark_aqua>" + String.join(", ", yours.buyChange().reasons())
                    + (buy < 0 ? ": " + -buy + "% off" : ": " + buy + "% dearer"));
        }
        if (yours.shop().buyable() && yours.bulk().applies()) {
            lines.add(BULLET + "<gray>Bulk: <white>" + yours.bulk().first().percent() + "%</white> off from "
                    + yours.bulk().first().from() + ", up to <white>" + yours.bulk().most() + "%");
        }
        return lines;
    }

    /** What made this player's sell price what it is, for the lines under "Sell:"; empty when nothing did. */
    public static List<String> sellNotes(Currency currency, SellBreakdown why) {
        List<String> lines = new ArrayList<>();
        if (why.rolePercent() != 0) {
            lines.add(BULLET + "<dark_aqua>" + String.join(", ", why.roleReasons()) + ": " + signed(why.rolePercent()));
        }
        if (why.leverPercent() != 0) {
            lines.add(BULLET + (why.leverPercent() < 0 ? "<red>" : "<green>") + "Economy: " + signed(why.leverPercent())
                    + " <dark_gray>(" + (why.leverPercent() < 0 ? "the server is short of money" : "the server pays extra")
                    + ")");
        }
        if (why.againPercent() != 0) {
            lines.add(BULLET + "<red>Sold lately: " + signed(why.againPercent()) + " <dark_gray>(" + why.againStacks()
                    + (why.againStacks() == 1 ? " stack" : " stacks") + " in " + why.againMinutes() + " min, recovers)");
        }
        if (why.capped()) {
            lines.add(BULLET + "<gray>Kept under the cheapest you could buy it for");
        }
        why.budgetLeft().ifPresent(left -> lines.add(BULLET + "<gray>The shop pays you " + Mini.of(currency.render(left))
                + " <gray>more today"));
        return lines;
    }

    private static String signed(int percent) {
        return (percent > 0 ? "+" : "") + percent + "%";
    }
}
