package de.raindancer.modules.claims.screen;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.CostType;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.ClaimServices;
import de.raindancer.modules.claims.model.CostType;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What this claim actually is: how big, whose, how old, what it cost.
 *
 * <p>A page that only reads. It exists because every one of these facts was previously only visible by running a
 * different command, and the one people wanted most — what was paid, so they know what a resize will refund — was
 * not visible anywhere at all.
 */
public final class ClaimInfoMenu extends ClaimScreen {

    public ClaimInfoMenu(ClaimServices services, Player viewer, Claim claim, Menu parent) {
        super(services, viewer, claim, parent, 3);
    }

    @Override
    protected Component title() {
        return Component.text("About " + claim().name());
    }

    @Override
    protected void render() {
        Claim claim = claim();

        band(MenuLayout.WHO, 2, Icons.of(Material.FILLED_MAP, "<white>The ground",
                claim.shape().areaBlocks() + " blocks",
                "<gray>" + claim.shape().vertices().size() + " corners",
                "<gray>y " + claim.shape().minY() + " to " + claim.shape().maxY(),
                "<gray>in <white>" + claim.worldName()));

        List<String> people = new ArrayList<>();
        people.add("<gray>" + services().names().allOwners(claim));
        people.add("<dark_gray>" + claim.members().size() + " trusted, "
                + claim.bans().size() + " barred");
        // The owner's own face, not a generic head — on a page about who this belongs to, whose face it
        // is carries the answer better than the line underneath it does.
        band(MenuLayout.WHO, 4, Icons.head(claim.primaryOwner(), "<white>Whose it is", people));

        band(MenuLayout.WHO, 6, Icons.of(Material.CLOCK, "<white>How long it has stood",
                "<gray>" + age(claim),
                "<dark_gray>claimed " + Instant.ofEpochMilli(claim.createdAt())));

        boolean mine = services().upkeep().enabled() && claim.isOwner(viewer.getUniqueId());
        boolean staff = services().upkeep().enabled() && claim.primaryOwner() != null
                && services().rights().isServerAdmin(viewer);
        List<String> cost = costLines(claim);
        if (mine) {
            cost.add("");
            cost.add("<dark_gray>click for your upkeep and to pay it");
        } else if (staff) {
            cost.add("");
            cost.add("<dark_gray>click for the owner's upkeep");
        }
        toolbar(4, Icons.of(costIcon(claim), "<white>What it cost", cost), click -> {
            if (mine) {
                new UpkeepMenu(services(), viewer, claim, this).open();
            } else if (staff) {
                new UpkeepMenu(services(), viewer, claim, this, claim.primaryOwner()).open();
            } else if (services().upkeep().enabled()) {
                services().messages().send(viewer, "upkeep.owner-only");
            }
        });
    }

    private String age(Claim claim) {
        long millis = Math.max(0L, System.currentTimeMillis() - claim.createdAt());
        return de.raindancer.core.moderation.punishment.Durations.describe(Duration.ofMillis(millis));
    }

    private static Material costIcon(Claim claim) {
        return switch (claim.paidCostType()) {
            case NONE -> Material.STRUCTURE_VOID;
            case XP_LEVELS, XP_POINTS -> Material.EXPERIENCE_BOTTLE;
            case MONEY -> Material.GOLD_INGOT;
            case ITEM -> claim.paidItem() == null ? Material.CHEST : claim.paidItem().getType();
        };
    }

    /**
     * What was paid, and what is currently invested.
     *
     * <p>Both numbers, because they differ after a resize and the difference is exactly what a refund is worked
     * out from. Showing only one made "why did I get three back and not four" unanswerable.
     */
    private List<String> costLines(Claim claim) {
        List<String> lines = new ArrayList<>();
        if (claim.paidCostType() == CostType.NONE || !claim.hasRecordedPayment()) {
            lines.add("<gray>Nothing — claims are free on this server,");
            lines.add("<gray>or this one predates the charge.");
            addUpkeep(claim, lines);
            return lines;
        }
        lines.add(claim.paidCostType() == CostType.MONEY
                ? "<gray>Paid <white>" + de.raindancer.core.social.economy.Fees.format(
                        de.raindancer.modules.claims.service.CostService.money(claim.paidAmount()))
                : "<gray>Paid <white>" + claim.paidAmount() + "</white> "
                + claim.paidCostType().displayName().toLowerCase(java.util.Locale.ROOT));
        lines.add("<gray>for <white>" + claim.paidArea() + "</white> blocks");
        lines.add("");
        lines.add("<gray>Currently invested: <white>" + claim.settledAmount());
        lines.add("<dark_gray>a resize settles against the original figures,");
        lines.add("<dark_gray>so shrinking and growing back costs nothing extra");
        addUpkeep(claim, lines);
        return lines;
    }

    /** What holding the owner's land costs, when the server charges for it. */
    private void addUpkeep(Claim claim, List<String> lines) {
        var upkeep = services().upkeep();
        if (!upkeep.enabled() || claim.primaryOwner() == null) {
            return;
        }
        var owner = claim.primaryOwner();
        var settings = services().config();
        var parts = upkeep.partsFor(owner);
        lines.add("");
        lines.add("<gray>Upkeep: <white>" + de.raindancer.core.social.economy.Fees.format(
                upkeep.quotedBillFor(owner)) + "</white> every <white>"
                + de.raindancer.core.moderation.punishment.Durations.describe(
                        Duration.ofMillis(settings.upkeepPeriodMillis())) + "</white>");
        lines.add("<dark_gray>for all of the owner's land together");
        if (settings.upkeepPerClaimAmount().isPositive()) {
            var own = de.raindancer.modules.claims.model.UpkeepBill.claimFee(settings.upkeepPerClaimAmount(),
                    settings.upkeepPerClaimAreaPercent(), Math.max(1, claim.shape().coveredChunkKeys().size()));
            lines.add("<gray>This claim's own fee: <white>" + de.raindancer.core.social.economy.Fees.format(
                    de.raindancer.core.social.economy.Fees.quote("claims.upkeep", own)) + "</white>");
            lines.add("<dark_gray>grows with its size, " + claim.shape().coveredChunkKeys().size() + " chunk(s)");
        }
        if (parts.discounted()) {
            lines.add("<gold>The owner pays " + (long) parts.payPercent() + "% of it");
        }
        if (services().rights().isServerAdmin(viewer)) {
            var owed = upkeep.owed(owner);
            lines.add(owed.isPositive()
                    ? "<red>Owed: <white>" + de.raindancer.core.social.economy.Fees.format(owed)
                    : "<gray>Owed: <white>nothing");
            lines.add(upkeep.nextDue(owner).map(due -> "<gray>Next bill in <white>"
                            + de.raindancer.core.moderation.punishment.Durations.describe(
                                    Duration.ofMillis(Math.max(0L, due - System.currentTimeMillis()))) + "</white>")
                    .orElse("<gray>Not billed yet"));
        }
        if (claim.lapsed()) {
            lines.add("<red>Unpaid for too long: not protecting right now");
        }
    }
}
