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
 * What holding land costs one owner: the next bill and what it is made of, what is owed, and the button that
 * pays it. The screen twin of {@code /claim upkeep}.
 *
 * <p>Usually the viewer's own; a server admin may open it for any owner, sees the same figures, and gets a
 * button that bills that owner now. Only the owner can pay.
 */
public final class UpkeepMenu extends ClaimScreen {

    private static final String SOURCE = "claims.upkeep";

    private final UUID owner;

    public UpkeepMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        this(services, viewer, claim, parent, viewer.getUniqueId());
    }

    public UpkeepMenu(ClaimServices services, Player viewer, Claim claim, Menu parent, UUID owner) {
        super(services, viewer, claim, parent, 3);
        this.owner = owner;
    }

    private boolean own() {
        return owner.equals(viewer.getUniqueId());
    }

    @Override
    protected Component title() {
        return Component.text(own() ? "Upkeep" : "Upkeep — " + services().names().nameOfOwner(owner));
    }

    @Override
    protected void render() {
        var upkeep = services().upkeep();
        UUID me = owner;
        boolean own = own();
        var parts = upkeep.partsFor(me);

        List<String> bill = new ArrayList<>();
        if (parts.claims() == 0) {
            bill.add(own ? "<gray>You hold no land, so there is nothing to pay."
                    : "<gray>They hold no land, so there is nothing to pay.");
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
                bill.add("<gold>" + (own ? "You pay " : "They pay ") + trim(parts.payPercent())
                        + "% of that (operator or discount)");
            }
            bill.add("");
            bill.add("<white>Next bill: " + Fees.format(upkeep.quotedBillFor(me)));
        }
        if (upkeep.nextDue(me).isEmpty() && parts.claims() > 0) {
            bill.add("<dark_gray>not billed yet: the first bill comes a period after they are first seen");
        }
        band(MenuLayout.WHO, 2, own ? Icons.of(Material.CLOCK, "<white>The next bill", bill)
                : Icons.head(me, "<white>" + services().names().nameOfOwner(me) + "'s next bill", bill));

        var owed = upkeep.owed(me);
        List<String> arrears = new ArrayList<>();
        if (owed.isPositive()) {
            arrears.add("<red>" + (own ? "You owe" : "They owe") + " <white>" + Fees.format(owed));
            arrears.add(own ? "<gray>You cannot make or enlarge claims until it is paid."
                    : "<gray>They cannot make or enlarge claims until it is paid.");
            if (upkeep.lapsed(me)) {
                arrears.add(own ? "<red>Your claims are not protecting right now."
                        : "<red>Their claims are not protecting right now.");
            }
        } else {
            arrears.add("<green>Nothing owed.");
        }
        band(MenuLayout.WHO, 4, Icons.of(owed.isPositive() ? Material.REDSTONE : Material.EMERALD,
                owed.isPositive() ? "<red>In arrears" : "<green>All paid", arrears));

        if (services().rights().isServerAdmin(viewer) && parts.claims() > 0) {
            String name = own ? "yourself" : services().names().nameOfOwner(me);
            band(MenuLayout.WHO, 8, Icons.of(Material.GOLDEN_AXE, "<gold>Bill now",
                            "<gray>Charges " + name + " <white>" + Fees.format(upkeep.quotedBillFor(me))
                                    + "</white> now",
                            "<gray>instead of when it is due; the next bill",
                            "<gray>is then a full period from now.",
                            "<dark_gray>staff · asks first"),
                    click -> new ConfirmScreen(services(), viewer, claim(), this,
                            "<gold>Bill " + name + " now?",
                            List.of("<gray>" + Fees.format(upkeep.quotedBillFor(me)) + " for "
                                    + parts.chunks() + " chunk(s)",
                                    "<gray>Unpaid, it becomes a debt."),
                            () -> {
                                if (!services().rights().isServerAdmin(viewer)) {
                                    services().messages().send(viewer, "error.no-permission");
                                    return;
                                }
                                de.raindancer.modules.claims.service.UpkeepNotices.billNowAndReport(
                                        services(), viewer, List.of(me));
                                open();
                            }).open());
        }

        if (!own) {
            return;
        }
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
