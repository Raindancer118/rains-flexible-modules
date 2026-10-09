package de.raindancer.modules.invsnap.screen;

import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.invsnap.InvSnapServices;
import de.raindancer.modules.invsnap.model.ItemPolicy;
import de.raindancer.modules.invsnap.service.InsuranceService;
import de.raindancer.modules.invsnap.service.ItemInsuranceService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A player's insurance: the death-insurance switch, the item they can insure, the items they have
 * insured, and what is waiting for them to collect. What {@code /insurance} opens.
 */
public final class InsuranceMenu extends Menu implements IInvSnapScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final int POLICY_ROW = 2;
    private static final int WAITING_ROW = 3;

    private final InvSnapServices services;

    public InsuranceMenu(InvSnapServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Insurance");
    }

    @Override
    public String breadcrumb() {
        return "Insurance";
    }

    @Override
    protected void render() {
        UUID me = viewer.getUniqueId();
        InsuranceService death = services.insurance();
        ItemInsuranceService items = services.itemInsurance();

        boolean insured = death.isInsured(me);
        List<String> deathLore = new ArrayList<>(List.of(
                "<gray>Keep your inventory when you die.",
                "<gray>Dying now would cost about <white>"
                        + Fees.format(Fees.quote(InsuranceService.SOURCE, death.premiumFor(viewer))) + "</white>."));
        if (services.config().insuranceKeepXp()) {
            deathLore.add("<gray>Your experience is kept too.");
        }
        deathLore.add("");
        deathLore.add("<yellow>Click: switch " + (insured ? "off" : "on"));
        band(MenuLayout.WHO, 2, death.enabled(),
                Icons.of(insured ? Material.LIME_DYE : Material.GRAY_DYE,
                        "<white>Death insurance: " + (insured ? "<green>on" : "<red>off"), deathLore),
                "Death insurance is not offered on this server",
                click -> {
                    death.setInsured(me, !insured);
                    refresh();
                });

        ItemStack held = viewer.getInventory().getItemInMainHand();
        ItemInsuranceService.Quote quote = items.quote(me, held);
        List<String> handLore = new ArrayList<>();
        if (quote.ok()) {
            handLore.add("<gray>" + quote.description());
            handLore.add("<gray>Premium: <white>" + price(quote.premium()) + "</white>, now and every <white>"
                    + services.config().itemInsuranceEveryHours() + "</white> hours");
            handLore.add("<gray>Deductible: <white>" + deductible() + "</white>");
            handLore.add("");
            handLore.add("<yellow>Click: insure this item (asks first)");
        } else {
            handLore.add("<gray>One item that does not stack, from your main hand.");
        }
        band(MenuLayout.WHO, 4, quote.ok(),
                Icons.of(quote.ok() ? held.getType() : Material.SHIELD, "<white>Insure the item in your hand",
                        handLore),
                quote.refusal() == null ? "" : quote.refusal(),
                click -> offer(services, viewer, this));

        band(MenuLayout.WHO, 6, Icons.of(Material.BOOK, "<white>How item insurance works",
                "<gray>If an insured item is destroyed (despawn, lava,",
                "<gray>fire, cactus, blast, the void) it comes back to",
                "<gray>you. You can drop, lend or store it; whoever",
                "<gray>dies with it, it goes back to you.",
                "<gray>Wearing it out ends the policy. Insurance",
                "<gray>covers loss, not wear: that is what /repair is for.",
                "<gray>An unpaid renewal ends the policy."));

        List<ItemPolicy> policies = items.policiesOf(me);
        for (int index = 0; index < Math.min(9, policies.size()); index++) {
            ItemPolicy policy = policies.get(index);
            cell(POLICY_ROW, index, policyIcon(policy), click -> cancel(policy));
        }
        if (policies.isEmpty()) {
            cell(POLICY_ROW, 4, Icons.of(Material.COBWEB, "<gray>No insured items",
                    items.enabled() ? "<gray>Hold one and use the button above."
                            : "<gray>Item insurance is not offered on this server."), null);
        }

        List<ItemStack> waiting = items.pending(me);
        for (int index = 0; index < Math.min(9, waiting.size()); index++) {
            int at = index;
            ItemStack stack = waiting.get(index);
            cell(WAITING_ROW, index, Icons.of(stack.getType(), "<white>" + items.describe(stack),
                    "<gray>Waiting for you to collect.", "", "<yellow>Click: collect"),
                    click -> collect(at));
        }
        if (waiting.isEmpty()) {
            cell(WAITING_ROW, 4, Icons.of(Material.COBWEB, "<gray>Nothing to collect",
                    "<gray>Insured items that could not go straight",
                    "<gray>back to your inventory wait here."), null);
        }

        toolbar(4, !waiting.isEmpty(),
                Icons.of(Material.HOPPER, "<white>Collect everything (" + waiting.size() + ")",
                        "<gray>Needs one free slot per item.", "", "<yellow>Click: collect"),
                "Nothing is waiting for you",
                click -> {
                    int handed = items.deliver(viewer);
                    if (handed == 0) {
                        services.messages().send(viewer, "invsnap.item.no-room");
                    }
                    refresh();
                });
    }

    private ItemStack policyIcon(ItemPolicy policy) {
        Material material = Material.matchMaterial(policy.material());
        ItemInsuranceService items = services.itemInsurance();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Premium: <white>" + price(items.premiumOf(policy)) + "</white> every <white>"
                + services.config().itemInsuranceEveryHours() + "</white> hours");
        lore.add("<gray>Next due: <white>" + STAMP.format(Instant.ofEpochMilli(policy.nextDue())) + "</white>");
        lore.add("<gray>Deductible: <white>" + deductible() + "</white>");
        if (!items.enabled()) {
            lore.add("<red>Not in force while item insurance is switched off.");
        }
        lore.add("");
        lore.add("<red>Click: cancel (asks first)");
        return Icons.of(material == null ? Material.SHIELD : material, "<white>" + policy.description(), lore);
    }

    private String deductible() {
        Money fee = services.itemInsurance().claimFee();
        return fee.isPositive() ? Fees.format(Fees.quote(ItemInsuranceService.CLAIM_SOURCE, fee))
                + " each time it comes back after your death" : "none";
    }

    private static String price(Money written) {
        return Fees.format(Fees.quote(ItemInsuranceService.SOURCE, written));
    }

    private void cancel(ItemPolicy policy) {
        new ConfirmScreen(services, viewer, this, "<red>Cancel the policy on " + policy.description() + "?",
                List.of("<gray>The item stays yours, but is no longer protected from dropping.",
                        "<red>Nothing is refunded for what you have paid."),
                () -> {
                    if (services.itemInsurance().cancel(viewer, policy.id())) {
                        services.messages().send(viewer, "invsnap.item.cancelled", "item", policy.description());
                    }
                }).open();
    }

    private void collect(int index) {
        if (!services.itemInsurance().collect(viewer, index)) {
            services.messages().send(viewer, "invsnap.item.no-room");
        }
        refresh();
    }

    /** Offers the item in hand: what it costs, and a Yes. Says why when it cannot be insured. */
    public static void offer(InvSnapServices services, Player viewer, Menu parent) {
        ItemInsuranceService items = services.itemInsurance();
        ItemInsuranceService.Quote quote = items.quote(viewer.getUniqueId(), viewer.getInventory().getItemInMainHand());
        if (!quote.ok()) {
            services.messages().send(viewer, "invsnap.item.refused", "reason", quote.refusal());
            return;
        }
        new ConfirmScreen(services, viewer, parent, "<white>Insure " + quote.description() + "?",
                List.of("<gray>Premium: <white>" + price(quote.premium()) + "</white> now, then every <white>"
                                + services.config().itemInsuranceEveryHours() + "</white> hours.",
                        "<gray>If it is destroyed, or you die, it comes back to you.",
                        "<gray>Wearing it out ends the policy."),
                "<dark_gray>An unpaid renewal ends the policy.",
                () -> tell(services, viewer, items.insure(viewer))).open();
    }

    /** What taking a policy came to, said to the player. */
    public static void tell(InvSnapServices services, Player viewer, ItemInsuranceService.Taken taken) {
        if (taken.done()) {
            services.messages().send(viewer, "invsnap.item.taken", "item", taken.policy().description(),
                    "price", Fees.format(taken.paid()),
                    "hours", String.valueOf(services.config().itemInsuranceEveryHours()));
        } else if (taken.unpaid() != null) {
            EconomyResult.Outcome why = taken.unpaid().outcome();
            services.messages().send(viewer, "invsnap.item.unpaid."
                    + (why == EconomyResult.Outcome.NOT_ENOUGH ? "not-enough"
                    : why == EconomyResult.Outcome.UNAVAILABLE ? "no-economy" : "refused"));
        } else {
            services.messages().send(viewer, "invsnap.item.refused", "reason", taken.refusal());
        }
    }

    @Override
    public String describe() {
        return "a player's insurance: death insurance, the item in hand, insured items, and what is to collect";
    }
}
