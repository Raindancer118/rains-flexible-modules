package de.raindancer.modules.roles.model;

import de.raindancer.core.social.economy.TradeSide;
import de.raindancer.core.ui.choose.ItemSelection;

/**
 * One thing a role is good at: so many percent on one side of the shop, for some items — at full strength;
 * a new role gets a share of it that grows (see {@code TenureRule}).
 *
 * @param percent negative is cheaper when buying, positive pays more when selling
 * @param items   what it covers
 * @param text    what it covers, in words ("seeds, saplings and hoes"), or empty to have it written
 */
public record Perk(TradeSide side, int percent, ItemSelection items, String text) {

    public Perk {
        text = text == null ? "" : text;
    }

    public boolean covers(String material) {
        return items.covers(material);
    }

    /** "15% off buying Food, Smoker" — at full strength. */
    public String says() {
        return says(percent);
    }

    /** "6% off buying Food, Smoker" — at whatever size the perk has for somebody now. */
    public String says(int now) {
        String what = text.isBlank() ? items.says() : text;
        return Math.abs(now) + "% " + (side == TradeSide.BUY ? (now < 0 ? "off" : "more") + " buying "
                : (now > 0 ? "more" : "less") + " selling ") + what;
    }
}
