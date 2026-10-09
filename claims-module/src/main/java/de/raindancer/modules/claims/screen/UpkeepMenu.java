package de.raindancer.modules.claims.screen;

import de.raindancer.core.moderation.punishment.Durations;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.Claim;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What holding land costs this player: the next bill and what it is made of, what is owed, and the button that
 * pays it. The screen twin of {@code /claim upkeep}.
 */
public final class UpkeepMenu extends ClaimScreen {

    private static final String SOURCE = "claims.upkeep";

    public UpkeepMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(services, viewer, claim, parent, 3);
    }

    @Override
    protected Component title() {
        return Component.text("Upkeep");
    }

    @Override
    protected void render() {
        var upkeep = services().upkeep();
        UUID me = viewer.getUniqueId();
        var parts = upkeep.partsFor(me);

        List<String> bill = new ArrayList<>();
        if (parts.claims() == 0) {
            bill.add("<gray>You hold no land, so there is nothing to pay.");
        } else {
            long next = upkeep.nextDue(me).orElse(System.currentTimeMillis()
                    + services().config().upkeepPeriodMillis());
            bill.add("<gray>Every <white>" + Durations.describe(Duration.ofMillis(
                    services().config().upkeepPeriodMillis())) + "</white>, next in <white>"
                    + Durations.describe(Duration.ofMillis(Math.max(0L, next - System.currentTimeMillis())))
                    + "</white>");
            bill.add("");
            bill.add("<gray>Land: <white>" + parts.chunks() + "</white> chunk(s) = <white>"
                    + Fees.format(Fees.quote(SOURCE, parts.land())) + "</white>");
            bill.add("<gray>Claims: <white>" + parts.claims() + "</white> claim(s) = <white>"
                    + Fees.format(Fees.quote(SOURCE, parts.perClaim())) + "</white>");
            if (parts.discounted()) {
                bill.add("<gold>You pay " + trim(parts.payPercent()) + "% of that (operator or discount)");
            }
            bill.add("");
            bill.add("<white>Next bill: " + Fees.format(upkeep.quotedBillFor(me)));
        }
        band(MenuLayout.WHO, 2, Icons.of(Material.CLOCK, "<white>The next bill", bill));

        var owed = upkeep.owed(me);
        List<String> arrears = new ArrayList<>();
        if (owed.isPositive()) {
            arrears.add("<red>You owe <white>" + Fees.format(owed));
            arrears.add("<gray>You cannot make or enlarge claims until it is paid.");
            if (upkeep.lapsed(me)) {
                arrears.add("<red>Your claims are not protecting right now.");
            }
        } else {
            arrears.add("<green>Nothing owed.");
        }
        band(MenuLayout.WHO, 4, Icons.of(owed.isPositive() ? Material.REDSTONE : Material.EMERALD,
                owed.isPositive() ? "<red>In arrears" : "<green>All paid", arrears));

        band(MenuLayout.WHO, 6, owed.isPositive(),
                Icons.of(Material.GOLD_INGOT, "<green>Pay what I owe",
                        "<gray>Pays as much of <white>" + Fees.format(owed) + "</white> as you can afford.",
                        "<dark_gray>also /claim upkeep pay"),
                "You owe nothing",
                click -> {
                    upkeep.payAndTell(viewer, services().messages());
                    refresh();
                });
    }

    private static String trim(double percent) {
        return percent == Math.rint(percent) ? String.valueOf((long) percent) : String.valueOf(percent);
    }
}
