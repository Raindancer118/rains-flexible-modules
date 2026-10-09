package de.raindancer.modules.economy.util;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Bulk;
import de.raindancer.modules.economy.model.YourPrice;

import java.util.ArrayList;
import java.util.List;

/**
 * A price as one player sees it: the shop's crossed out next to theirs, and — on lines of their own, under
 * the supply-and-demand trend — what made it differ: their role, buying in bulk.
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

    /** "Cook discount: 6%", "Cook: 4% more when selling" — empty when nothing of theirs changes the price. */
    public static List<String> why(YourPrice yours) {
        List<String> lines = new ArrayList<>();
        int buy = yours.buyChange().percent();
        if (yours.shop().buyable() && buy != 0) {
            lines.add("<dark_aqua>" + String.join(", ", yours.buyChange().reasons())
                    + (buy < 0 ? " discount: " + -buy + "%" : ": " + buy + "% dearer"));
        }
        int sell = yours.sellChange().percent();
        if (yours.shop().sellable() && sell != 0) {
            lines.add("<dark_aqua>" + String.join(", ", yours.sellChange().reasons()) + ": " + Math.abs(sell) + "% "
                    + (sell > 0 ? "more" : "less") + " when selling");
        }
        return lines;
    }

    /** "Bulk: 5% off from 128, up to 20%" — empty for items that are not cheaper in bulk. */
    public static String bulk(Bulk bulk) {
        if (!bulk.applies()) {
            return "";
        }
        return "<gray>Bulk: <white>" + bulk.first().percent() + "%</white> off from " + bulk.first().from()
                + ", up to <white>" + bulk.most() + "%";
    }
}
