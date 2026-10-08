package de.raindancer.modules.economy;

import de.raindancer.core.ui.choose.Category;
import org.bukkit.Material;
import org.bukkit.entity.Player;


/** Opening this module's screens without naming the classes that draw them. */
public interface IEconomyScreensOpener {

    void bank(Player viewer);

    void shop(Player viewer);

    void shopCategory(Player viewer, Category category);

    void shopSearch(Player viewer, String text);

    void trade(Player viewer, Material material);

    void sell(Player viewer);

    void baltop(Player viewer);

    void withdraw(Player viewer);

    void casino(Player viewer);

    void slots(Player viewer);

    /** The coin flip, with a bet; with a call it flips at once. */
    void coinflip(Player viewer, de.raindancer.core.social.economy.Money stake, Boolean call);

    /** The dice, with a bet; with both an over/under and a number, it rolls at once. */
    void dice(Player viewer, de.raindancer.core.social.economy.Money stake, Boolean over, Integer target);

    void admin(Player viewer);

    void jobs(Player viewer);

    void roulette(Player viewer, de.raindancer.core.social.economy.Money stake);
}
