package de.raindancer.modules.claims.screen;

import de.raindancer.core.moderation.punishment.Durations;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.service.UpkeepNotices;
import de.raindancer.modules.claims.service.UpkeepService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Every owner's upkeep on one page, dearest first: what each pays, owes and when they are billed next. Staff
 * can bill everyone now, or pick some owners with a right click and bill only those.
 */
public final class UpkeepLedgerMenu extends PaginatedMenu<UpkeepService.Standing> implements IClaimScreen {

    private final ClaimServices services;
    private final Set<UUID> picked = new LinkedHashSet<>();

    public UpkeepLedgerMenu(ClaimServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return Component.text("All upkeep");
    }

    @Override
    public String breadcrumb() {
        return "the upkeep ledger";
    }

    @Override
    protected List<UpkeepService.Standing> entries() {
        return services.upkeep().standings();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.STRUCTURE_VOID, "<gray>Nobody holds land", "<dark_gray>so nobody is billed");
    }

    @Override
    protected ItemStack icon(UpkeepService.Standing standing) {
        List<String> lore = new ArrayList<>();
        var parts = standing.parts();
        lore.add("<gray>Bill: <white>" + Fees.format(standing.bill()) + "</white> every <white>"
                + Durations.describe(Duration.ofMillis(services.config().upkeepPeriodMillis())));
        lore.add("<dark_gray>" + parts.chunks() + " chunk(s) in " + parts.claims() + " claim(s)");
        if (parts.discounted()) {
            lore.add("<gold>pays " + (long) parts.payPercent() + "% (operator or discount)");
        }
        lore.add(standing.owed().isPositive() ? "<red>Owes <white>" + Fees.format(standing.owed())
                : "<green>Nothing owed");
        if (standing.lapsed()) {
            lore.add("<red>Claims not protecting: unpaid too long");
        }
        lore.add(standing.nextDue().map(due -> "<gray>Next bill in <white>" + Durations.describe(
                        Duration.ofMillis(Math.max(0L, due - System.currentTimeMillis()))))
                .orElse("<gray>Not billed yet"));
        lore.add("");
        lore.add("<yellow>click <dark_gray>breakdown, or bill only them");
        lore.add("<yellow>right click <dark_gray>" + (picked.contains(standing.owner())
                ? "take off the list to bill" : "put on the list to bill"));
        String name = services.names().nameOfOwner(standing.owner());
        return Icons.head(standing.owner(), (picked.contains(standing.owner()) ? "<green>✔ " : "<white>") + name,
                lore);
    }

    @Override
    protected void onClick(UpkeepService.Standing standing, InventoryClickEvent event) {
        if (!services.rights().isServerAdmin(viewer)) {
            services.messages().send(viewer, "error.no-permission");
            return;
        }
        if (event.isRightClick()) {
            if (!picked.remove(standing.owner())) {
                picked.add(standing.owner());
            }
            refresh();
            return;
        }
        new UpkeepMenu(services, viewer, null, this, standing.owner()).open();
    }

    @Override
    protected void render() {
        super.render();
        UpkeepService.Totals totals = services.upkeep().totals();

        toolbar(2, Icons.of(Material.GOLDEN_AXE, "<gold>Bill everyone now",
                        "<gray>" + totals.owners() + " owner(s), <white>" + Fees.format(totals.bills())
                                + "</white> together.",
                        "<gray>Each one's next bill is then a full period away.",
                        "<dark_gray>asks first"),
                event -> bill(new ArrayList<>(services.upkeep().owners()), "everyone"));

        toolbar(4, Icons.of(Material.BOOK, "<white>All together",
                        "<gray>Owners billed: <white>" + totals.owners(),
                        "<gray>One round of bills: <white>" + Fees.format(totals.bills()),
                        totals.owed().isPositive() ? "<red>Unpaid: <white>" + Fees.format(totals.owed())
                                : "<green>Nothing unpaid",
                        services.upkeep().enabled() ? "<green>upkeep is on" : "<dark_gray>upkeep is off"),
                event -> {
                    // A tile to read.
                });

        Money pickedBills = Money.ZERO;
        for (UpkeepService.Standing each : services.upkeep().standings()) {
            if (picked.contains(each.owner())) {
                pickedBills = pickedBills.plus(each.bill());
            }
        }
        toolbar(6, !picked.isEmpty(), Icons.of(Material.NAME_TAG, "<gold>Bill the " + picked.size() + " picked",
                        "<gray>Only the owners ticked with a right click,",
                        "<white>" + Fees.format(pickedBills) + "</white> together.",
                        "<dark_gray>asks first"),
                "Right-click owners to pick them first",
                event -> bill(new ArrayList<>(picked), picked.size() + " picked owner(s)"));
    }

    private void bill(List<UUID> owners, String who) {
        if (!services.rights().isServerAdmin(viewer)) {
            services.messages().send(viewer, "error.no-permission");
            return;
        }
        if (!services.upkeep().enabled()) {
            services.messages().send(viewer, "upkeep.admin-off");
            return;
        }
        new ConfirmScreen(services, viewer, null, this, "<gold>Bill " + who + " now?",
                List.of("<gray>Charged at once instead of when due.",
                        "<gray>Whoever cannot pay is put in arrears."),
                () -> {
                    UpkeepNotices.billNowAndReport(services, viewer, owners);
                    picked.clear();
                    open();
                }).open();
    }
}
