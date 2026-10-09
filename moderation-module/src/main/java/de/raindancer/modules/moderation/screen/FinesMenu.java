package de.raindancer.modules.moderation.screen;

import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.FineRecord;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.service.FineService;
import de.raindancer.modules.moderation.util.FineTalk;
import de.raindancer.modules.moderation.util.Players;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Somebody's fines, newest first: what each was for, what was paid and what is still owed. Clicking one revokes it
 * (after asking), which gives back what was paid; the buttons below fine them again or write their debt off.
 */
public final class FinesMenu extends ModerationList<FineRecord> {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final UUID subject;
    private final String subjectName;

    public FinesMenu(ModerationServices services, Player viewer, Menu parent, UUID subject, String subjectName) {
        super(services, viewer, parent);
        this.subject = subject;
        this.subjectName = subjectName;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Fines — <white>" + subjectName);
    }

    @Override
    public String breadcrumb() {
        return "Fines";
    }

    @Override
    protected List<FineRecord> entries() {
        return services().fines().finesOf(subject);
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>No fines", "<gray>Nothing has cost " + subjectName + " money.");
    }

    @Override
    protected ItemStack icon(FineRecord fine) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + MINI.escapeTags(fine.reason()));
        lore.add("<dark_gray>" + fine.source().replace('-', ' ') + ", "
                + Times.describe(
                Duration.ofMillis(Math.max(0, System.currentTimeMillis() - fine.givenAt()))) + " ago");
        lore.add("");
        lore.add("<gray>Fined <white>" + Fees.format(Money.of(fine.charged())));
        lore.add("<gray>Paid <white>" + Fees.format(Money.of(fine.paid())));
        if (fine.toVictim() > 0) {
            lore.add("<gray>To the victim <white>" + Fees.format(Money.of(fine.toVictim()))
                    + (fine.victim() == null ? "" : " <dark_gray>(" + Players.nameOf(services().server(), fine.victim()) + ")"));
        }
        if (fine.debt() > 0) {
            lore.add("<red>Still owed " + Fees.format(Money.of(fine.debt())));
        }
        if (fine.forgiven() > 0) {
            lore.add("<green>Written off " + Fees.format(Money.of(fine.forgiven())));
        }
        lore.add("");
        if (fine.revoked()) {
            lore.add("<green>Revoked — " + Fees.format(Money.of(fine.refunded())) + " given back");
        } else {
            lore.add(may(ModerationPermission.FINE)
                    ? "<dark_gray>Click to revoke it and give back what was paid. Asks first."
                    : "<dark_gray>Not yours to revoke.");
        }
        return Icons.of(fine.revoked() ? Material.GRAY_DYE : Material.GOLD_NUGGET, "<yellow>"
                + Fees.format(Money.of(fine.charged())), lore);
    }

    @Override
    protected void onClick(FineRecord fine, InventoryClickEvent event) {
        if (!may(ModerationPermission.FINE)) {
            tell("moderation.no-permission");
            return;
        }
        if (fine.revoked()) {
            tell("moderation.fine.revoke-already", "player", subjectName);
            return;
        }
        new ConfirmScreen(services(), viewer, this,
                "<yellow>Revoke this fine for " + subjectName + "?",
                List.of("<gray>" + Fees.format(Money.of(fine.paid())) + " is given back, and what is still owed is dropped.",
                        "<dark_gray>The fine stays on the record, marked revoked."),
                () -> {
                    if (!may(ModerationPermission.FINE)) {
                        tell("moderation.no-permission");
                        return;
                    }
                    FineTalk.revoked(services(), viewer, viewer.getUniqueId(), viewer.getName(), fine, subjectName);
                    open();
                }).open();
    }

    @Override
    protected void render() {
        super.render();
        Money owing = services().fines().owed(subject);
        toolbar(2, Icons.of(Material.GOLD_INGOT, "<yellow>Fine them",
                        "<gray>You type the amount, then why.",
                        "<dark_gray>Charged from their balance; the rest becomes debt."),
                click -> askForAFine(viewer, this, services(), subject, subjectName));
        toolbar(4, Icons.of(Material.SPONGE, "<green>Forgive their debt",
                        "<gray>Owes <white>" + Fees.format(owing) + "</white>.",
                        "<dark_gray>Writes it off. Asks first."),
                click -> {
                    if (!may(ModerationPermission.FINE)) {
                        tell("moderation.no-permission");
                        return;
                    }
                    if (!owing.isPositive()) {
                        tell("moderation.fine.nothing-to-forgive", "player", subjectName);
                        return;
                    }
                    new ConfirmScreen(services(), viewer, this,
                            "<green>Forgive " + subjectName + "'s debt?",
                            List.of("<gray>" + Fees.format(owing) + " is written off, nothing is paid back."),
                            this::forgive).open();
                });
    }

    private void forgive() {
        if (!may(ModerationPermission.FINE)) {
            tell("moderation.no-permission");
            return;
        }
        Money wiped = services().fines().forgive(viewer.getUniqueId(), viewer.getName(), subject, subjectName);
        tell(wiped.isPositive() ? "moderation.fine.forgiven" : "moderation.fine.nothing-to-forgive",
                "player", subjectName, "amount", Fees.format(wiped));
        open();
    }

    /**
     * The chat prompts for a fine — the amount, then why — and a confirmation. Shared with the player's page, which
     * has the same button.
     */
    public static void askForAFine(Player viewer, Menu back, ModerationServices services, UUID subject, String subjectName) {
        var verdict = services.staffRule().canAct(viewer.getUniqueId(), subject, ModerationPermission.FINE);
        if (verdict.isRefused()) {
            verdict.refusal().ifPresent(reason -> services.messages().send(viewer, reason, "detail",
                    verdict.detail() == null ? "" : verdict.detail()));
            return;
        }
        if (!services.fines().hasEconomy()) {
            services.messages().send(viewer, "moderation.fine.no-economy");
            return;
        }
        viewer.closeInventory();
        services.messages().send(viewer, "moderation.fine.type-an-amount", "player", subjectName);
        services.prompts().ask(viewer.getUniqueId(), "moderation", Duration.ofSeconds(60),
                typedAmount -> {
                    Money amount = Fees.amount(typedAmount);
                    if (!amount.isPositive()) {
                        services.messages().send(viewer, "moderation.fine.bad-amount", "text", typedAmount);
                        return;
                    }
                    var limit = services.fines().limitFor(viewer.getUniqueId());
                    if (limit.isPresent() && amount.isMoreThan(limit.get())) {
                        services.messages().send(viewer, "moderation.fine.too-much-for-you", "detail",
                                Fees.format(limit.get()));
                        return;
                    }
                    services.messages().send(viewer, "moderation.fine.type-a-reason", "player", subjectName);
                    services.prompts().ask(viewer.getUniqueId(), "moderation", Duration.ofSeconds(120),
                            reason -> new ConfirmScreen(services, viewer, back,
                                    "<yellow>Fine " + subjectName + " " + Fees.format(amount) + "?",
                                    List.of("<gray>For: <white>" + MINI.escapeTags(reason),
                                            "<gray>Charged from their balance, offline or not."),
                                    () -> {
                                        var again = services.staffRule().canAct(viewer.getUniqueId(), subject,
                                                ModerationPermission.FINE);
                                        if (again.isRefused()) {
                                            again.refusal().ifPresent(key -> services.messages().send(viewer, key,
                                                    "detail", again.detail() == null ? "" : again.detail()));
                                            return;
                                        }
                                        FineService.Result result = services.fines().fine(viewer.getUniqueId(),
                                                viewer.getName(), subject, subjectName, amount, reason.strip(), null,
                                                FineService.Kind.FINE);
                                        FineTalk.tell(services.messages(), viewer, subjectName, result);
                                        viewer.closeInventory();
                                    }).open(),
                            () -> services.messages().send(viewer, "moderation.nothing-typed"));
                },
                () -> services.messages().send(viewer, "moderation.nothing-typed"));
    }

    @Override
    public String describe() {
        return "somebody's fines: what each was for, what was paid and owed, and revoking them";
    }
}
